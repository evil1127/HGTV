// SPDX-License-Identifier: GPL-3.0-only
package hgtv.preload;

import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 预载模块：由 tools/patch.py 插进官方 APK 的三个位置调用。
 *
 *  - 取播放地址（StreamInfo stream(String id, int quality)）的入口 / 出口：
 *      streamBefore 命中已预载的那一集就直接交出地址（不再向平台要）；
 *      streamAfter 记下这一集，并在后台把这一集与后面 {@link #AHEAD} 集整集下载到磁盘。
 *  - 分段读取（Range range(long start, long end)）的入口 / 出口：
 *      rangeBefore 磁盘上已有就直接交出；rangeAfter 把播放器自己读到的字节也存下来。
 *  - 取详情（Detail detail(String seriesId)）的出口：detailAfter 记下集数列表。
 *
 * 全部通过反射按「类型」找字段与方法，不依赖混淆后的名字；任何一步出错都只会让预载停用，
 * 不会影响官方原本的播放流程（所有入口都吞掉异常并返回 null）。
 */
public final class Hooks {
    private static final String TAG = "HGTV";
    private static final int AHEAD = 3;
    private static final int CHUNK = 1024 * 1024;
    private static final long INCOMPLETE_TTL = 4 * 60_000L;
    private static final long MIN_FREE = 300L * 1024 * 1024;

    private static volatile boolean enabled = true;
    private static final ThreadLocal<Boolean> INTERNAL = new ThreadLocal<>();
    private static final ThreadLocal<Object[]> STREAM_CALL = new ThreadLocal<>();
    private static final ThreadLocal<Object[]> RANGE_CALL = new ThreadLocal<>();

    private static volatile Object repository;
    private static volatile Method streamMethod;
    private static volatile Class<?> streamClass;
    private static volatile Class<?> videoClass;
    private static volatile Constructor<?> videoConstructor;
    private static volatile Field videoStreamField;
    private static volatile Field videoHttpField;
    private static volatile Object httpClient;
    private static volatile Method rangeMethod;
    private static volatile Constructor<?> rangeConstructor;
    private static volatile Field rangeBytesField;
    private static volatile Field rangeTotalField;

    /** 已知的集数列表（详情页取到的），最多留几部剧。 */
    private static final List<List<String>> SERIES = new ArrayList<>();
    /** 键：剧集 ID + ":" + 清晰度。 */
    private static final Map<String, Entry> BY_KEY = new ConcurrentHashMap<>();
    /** 键：播放地址。 */
    private static final Map<String, Entry> BY_URL = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hgtv-preload");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong WINDOW = new AtomicLong();
    private static volatile boolean cleaned;
    private static volatile String lastId;
    private static volatile int lastQuality;

    private Hooks() {}

    // ───────────────────────────── 入口：取播放地址 ─────────────────────────────

    public static Object streamBefore(Object repo, String id, int quality) {
        if (!enabled || Boolean.TRUE.equals(INTERNAL.get())) return null;
        try {
            STREAM_CALL.set(new Object[]{repo, id, quality});
            repository = repo;
            Entry e = BY_KEY.get(key(id, quality));
            lastId = id; lastQuality = quality;
            if (e != null && e.usable()) {
                log("Preload hit " + key(id, quality) + " complete=" + e.complete());
                STREAM_CALL.remove();
                schedule(id, quality);
                return e.stream;
            }
        } catch (Throwable t) { fail("streamBefore", t); }
        return null;
    }

    public static void streamAfter(Object result) {
        if (!enabled || result == null || Boolean.TRUE.equals(INTERNAL.get())) return;
        try {
            Object[] call = STREAM_CALL.get();
            STREAM_CALL.remove();
            if (call == null) return;
            String id = (String) call[1];
            int quality = (Integer) call[2];
            learnStream(call[0], result);
            entryFor(key(id, quality), result);
            schedule(id, quality);
        } catch (Throwable t) { fail("streamAfter", t); }
    }

    // ───────────────────────────── 入口：分段读取 ─────────────────────────────

    public static Object rangeBefore(Object video, long start, long end) {
        if (!enabled || Boolean.TRUE.equals(INTERNAL.get())) return null;
        try {
            RANGE_CALL.set(new Object[]{video, start});
            if (videoClass == null) {
                learnVideo(video);
                // 第一次认出播放器的读取方式之前排的任务下不了：认出之后把这一轮重排一次。
                if (lastId != null) schedule(lastId, lastQuality);
            }
            String url = urlOfVideo(video);
            Entry e = url == null ? null : BY_URL.get(url);
            if (e == null || rangeConstructor == null) return null;
            byte[] bytes = e.read(start, end);
            if (bytes == null) return null;
            return rangeConstructor.newInstance(bytes, e.total);
        } catch (Throwable t) { fail("rangeBefore", t); }
        return null;
    }

    public static void rangeAfter(Object result) {
        if (!enabled || result == null || Boolean.TRUE.equals(INTERNAL.get())) return;
        try {
            Object[] call = RANGE_CALL.get();
            RANGE_CALL.remove();
            if (call == null) return;
            learnRange(result);
            String url = urlOfVideo(call[0]);
            Entry e = url == null ? null : BY_URL.get(url);
            if (e != null) e.offer((Long) call[1], (byte[]) rangeBytesField.get(result), rangeTotalField.getLong(result));
        } catch (Throwable t) { fail("rangeAfter", t); }
    }

    // ───────────────────────────── 入口：详情（集数列表） ─────────────────────────────

    public static void detailAfter(Object detail) {
        if (!enabled || detail == null) return;
        try {
            for (Field f : allFields(detail.getClass())) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object value = f.get(detail);
                if (!(value instanceof List) || ((List<?>) value).isEmpty()) continue;
                List<?> list = (List<?>) value;
                boolean ids = true;
                for (Object o : list) if (!(o instanceof String) || !((String) o).matches("[0-9]{6,30}")) { ids = false; break; }
                if (!ids) continue;
                List<String> copy = new ArrayList<>();
                for (Object o : list) copy.add((String) o);
                synchronized (SERIES) {
                    SERIES.remove(copy);
                    SERIES.add(0, copy);
                    while (SERIES.size() > 6) SERIES.remove(SERIES.size() - 1);
                }
                return;
            }
        } catch (Throwable t) { fail("detailAfter", t); }
    }

    // ───────────────────────────── 后台预载 ─────────────────────────────

    private static void schedule(String currentId, int quality) {
        final List<String> window = new ArrayList<>();
        synchronized (SERIES) {
            for (List<String> list : SERIES) {
                int index = list.indexOf(currentId);
                if (index < 0) continue;
                for (int i = index + 1; i <= index + AHEAD && i < list.size(); i++) window.add(list.get(i));
                break;
            }
        }
        final long ticket = WINDOW.incrementAndGet();
        final String currentKey = key(currentId, quality);
        // 只留下当前这一集与后面几集，其余的删掉。
        Set<String> keep = new LinkedHashSet<>();
        keep.add(currentKey);
        for (String id : window) keep.add(key(id, quality));
        for (Iterator<Map.Entry<String, Entry>> it = BY_KEY.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry> item = it.next();
            if (!keep.contains(item.getKey())) { it.remove(); BY_URL.remove(item.getValue().url); item.getValue().close(); }
        }
        WORKER.execute(() -> {
            INTERNAL.set(Boolean.TRUE);
            try {
                cleanOnce();
                Entry current = BY_KEY.get(currentKey);
                if (current != null && download(current, ticket)) log("Preload current complete " + currentKey);
                for (String id : window) {
                    if (WINDOW.get() != ticket) return;
                    String k = key(id, quality);
                    Entry e = BY_KEY.get(k);
                    if (e == null || !e.usable()) {
                        Object stream = fetchStream(id, quality);
                        if (stream == null) { log("Preload failed " + k + ": no stream"); continue; }
                        e = entryFor(k, stream);
                        if (e == null) continue;
                        log("Preload prepared " + k);
                    }
                    if (download(e, ticket)) log("Preload complete " + k);
                }
            } catch (Throwable t) {
                log("Preload stopped: " + t);
            } finally {
                INTERNAL.remove();
            }
        });
    }

    private static Object fetchStream(String id, int quality) {
        Object repo = repository; Method m = streamMethod;
        if (repo == null || m == null) return null;
        try { return m.invoke(repo, id, quality); }
        catch (Throwable t) { log("Preload stream error " + id + ": " + rootMessage(t)); return null; }
    }

    /** 用官方自己的分段读取把整集原始字节下载到磁盘。返回是否已完整。 */
    private static boolean download(Entry e, long ticket) {
        if (e.file == null || e.complete()) return e.complete();
        if (videoConstructor == null || httpClient == null || rangeMethod == null) return false;
        Object video = null;
        try {
            video = videoConstructor.newInstance(httpClient, e.stream);
            while (!e.complete() && WINDOW.get() == ticket && !e.closed) {
                long start = e.filled;
                long end = e.total > 0 ? Math.min(e.total, start + CHUNK) - 1 : start + 256 * 1024 - 1;
                Object range = rangeMethod.invoke(video, start, end);
                learnRange(range);
                if (!e.offer(start, (byte[]) rangeBytesField.get(range), rangeTotalField.getLong(range))) break;
            }
        } catch (Throwable t) {
            log("Preload download error " + e.url.hashCode() + ": " + rootMessage(t));
        } finally {
            if (video instanceof Closeable) try { ((Closeable) video).close(); } catch (Throwable ignored) {}
        }
        return e.complete();
    }

    // ───────────────────────────── 反射：按类型认出官方的类 ─────────────────────────────

    private static void learnStream(Object repo, Object stream) {
        if (streamClass == null) streamClass = stream.getClass();
        if (streamMethod == null) {
            for (Method m : allMethods(repo.getClass())) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == streamClass && p.length == 2 && p[0] == String.class && p[1] == int.class
                        && !Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true); streamMethod = m; break;
                }
            }
        }
    }

    private static void learnVideo(Object video) throws Exception {
        if (videoClass != null) return;
        Class<?> c = video.getClass();
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            Class<?>[] p = k.getParameterTypes();
            if (p.length == 2 && streamClass != null && p[1] == streamClass) { k.setAccessible(true); videoConstructor = k; }
        }
        if (videoConstructor == null) throw new IllegalStateException("video constructor not found");
        Class<?> httpType = videoConstructor.getParameterTypes()[0];
        for (Field f : allFields(c)) {
            if (f.getType() == streamClass) { f.setAccessible(true); videoStreamField = f; }
            if (f.getType() == httpType) { f.setAccessible(true); videoHttpField = f; }
        }
        if (videoStreamField == null || videoHttpField == null) throw new IllegalStateException("video fields not found");
        httpClient = videoHttpField.get(video);
        for (Method m : allMethods(c)) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == long.class && p[1] == long.class && m.getReturnType() != void.class
                    && !m.getReturnType().isPrimitive()) { m.setAccessible(true); rangeMethod = m; }
        }
        videoClass = c;
    }

    private static void learnRange(Object range) throws Exception {
        if (rangeConstructor != null) return;
        Class<?> c = range.getClass();
        for (Field f : allFields(c)) {
            if (f.getType() == byte[].class) { f.setAccessible(true); rangeBytesField = f; }
            if (f.getType() == long.class) { f.setAccessible(true); rangeTotalField = f; }
        }
        Constructor<?> k = c.getDeclaredConstructor(byte[].class, long.class);
        k.setAccessible(true);
        rangeConstructor = k;
    }

    private static String urlOfVideo(Object video) throws Exception {
        Field f = videoStreamField;
        if (f == null) return null;
        return urlOf(f.get(video));
    }

    private static String urlOf(Object stream) throws Exception {
        if (stream == null) return null;
        for (Field f : allFields(stream.getClass())) {
            if (f.getType() != String.class) continue;
            f.setAccessible(true);
            Object v = f.get(stream);
            if (v instanceof String && (((String) v).startsWith("http://") || ((String) v).startsWith("https://"))) return (String) v;
        }
        return null;
    }

    private static Entry entryFor(String key, Object stream) throws Exception {
        String url = urlOf(stream);
        if (url == null) return null;
        Entry old = BY_KEY.get(key);
        if (old != null && old.url.equals(url)) return old;
        if (old != null) { BY_URL.remove(old.url); old.close(); }
        Entry e = new Entry(key, url, stream, newFile());
        BY_KEY.put(key, e);
        BY_URL.put(url, e);
        return e;
    }

    // ───────────────────────────── 磁盘缓存 ─────────────────────────────

    private static File cacheDir() {
        String override = System.getProperty("hgtv.preload.dir");
        if (override != null) { File d = new File(override); d.mkdirs(); return d; }
        try {
            Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            File base = (File) app.getClass().getMethod("getCacheDir").invoke(app);
            File dir = new File(base, "hgtv-preload");
            dir.mkdirs();
            return dir;
        } catch (Throwable t) { return null; }
    }

    private static File newFile() {
        File dir = cacheDir();
        if (dir == null || dir.getUsableSpace() < MIN_FREE) return null;
        return new File(dir, java.util.UUID.randomUUID() + ".bin");
    }

    private static void cleanOnce() {
        if (cleaned) return;
        cleaned = true;
        File dir = cacheDir();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        Set<File> inUse = new LinkedHashSet<>();
        for (Entry e : BY_KEY.values()) if (e.file != null) inUse.add(e.file);
        for (File f : files) if (!inUse.contains(f)) f.delete();
    }

    private static final class Entry {
        final String key, url; final Object stream; final File file; final long createdAt = System.currentTimeMillis();
        volatile long total, filled; volatile boolean closed;
        private RandomAccessFile access;

        Entry(String key, String url, Object stream, File file) { this.key = key; this.url = url; this.stream = stream; this.file = file; }

        boolean complete() { return total > 0 && filled >= total; }
        boolean usable() { return !closed && (complete() || System.currentTimeMillis() - createdAt < INCOMPLETE_TTL); }

        synchronized boolean offer(long start, byte[] bytes, long size) {
            if (closed || file == null || bytes == null || bytes.length == 0) return false;
            if (total == 0) total = size; else if (size != total) return false;
            long end = Math.min(total, start + bytes.length);
            if (start > filled || end <= filled) return start <= filled;
            try {
                if (access == null) access = new RandomAccessFile(file, "rw");
                access.seek(filled);
                access.write(bytes, (int) (filled - start), (int) (end - filled));
                filled = end;
                return true;
            } catch (Throwable t) { return false; }
        }

        /** 整段 [start, end] 都在磁盘上时返回这段字节，否则 null。 */
        synchronized byte[] read(long start, long end) {
            if (closed || access == null || start < 0) return null;
            long last = Math.min(end, total - 1);
            if (last < start || last >= filled) return null;
            byte[] out = new byte[(int) (last - start + 1)];
            try { access.seek(start); access.readFully(out); return out; } catch (Throwable t) { return null; }
        }

        synchronized void close() {
            closed = true;
            try { if (access != null) access.close(); } catch (Throwable ignored) {}
            if (file != null) file.delete();
        }
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    private static String key(String id, int quality) { return id + ":" + quality; }

    private static List<Field> allFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers())) out.add(f);
        return out;
    }

    private static List<Method> allMethods(Class<?> c) {
        List<Method> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods()) out.add(m);
        return out;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static void fail(String where, Throwable t) {
        enabled = false;
        log("Preload disabled at " + where + ": " + rootMessage(t));
    }

    private static void log(String line) { try { Log.i(TAG, line); } catch (Throwable ignored) {} }
}
