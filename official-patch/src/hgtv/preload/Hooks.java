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
    private static final int AHEAD = 2;
    /** 播放器最近这么久内还在向网络要数据，就算它忙，预载先让路。 */
    private static final long PLAYER_BUSY_MS = 4_000L;
    /** 每下完一块歇一下，别把 CDN 打满。 */
    private static final long CHUNK_PAUSE_MS = 300L;
    private static final int CHUNK = 1024 * 1024;
    private static final long INCOMPLETE_TTL = 4 * 60_000L;
    private static final long MIN_FREE = 300L * 1024 * 1024;
    /** 没下完的那一集只在取到地址后这段时间内交出去（地址还新鲜）；更久的就让官方重取。 */
    private static final long FRESH_TTL = 120_000L;
    /** 打开剧集页时，可能会点的几集先取好地址、下好开头这么多字节。 */
    private static final int HEAD_BYTES = 2 * 1024 * 1024;

    /**
     * 预载暂时关闭（2026-10-31，以 1.2.9.1 实测）：
     *  - 1.2.9.1 的播放器不再走被挂钩的分段读取，预载以为播放器闲着，全速下载，和播放器抢同一个 CDN 连线；
     *  - 预载下载的集数 ID 与播放器实际要的对不上，从没命中过（log 里没有任何 Preload hit / served from disk）；
     *  - 官方 1.2.9.1 自己已会提前取下一集地址。
     * 改回 true 前要先解决上面两点。
     */
    private static volatile boolean enabled = false;
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
    private static volatile long lastPlayAt;
    private static volatile long lastRangeAt;
    /** 播放器最近一次真的走网络（磁盘没有）的时间。 */
    private static volatile long lastNetAt;
    /** CDN 限流（429）或连续出错时，到这个时间之前都不预载。 */
    private static volatile long pausedUntil;

    private Hooks() {}

    // ───────────────────────────── 入口：取播放地址 ─────────────────────────────

    public static Object streamBefore(Object repo, String id, int quality) {
        if (!enabled || Boolean.TRUE.equals(INTERNAL.get())) return null;
        try {
            STREAM_CALL.set(new Object[]{repo, id, quality, System.currentTimeMillis()});
            repository = repo;
            Entry e = BY_KEY.get(key(id, quality));
            lastId = id; lastQuality = quality;
            rememberWatched(id);
            if (e != null && !e.closed && e.complete()) {
                log("Preload hit " + key(id, quality));
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
            log("Stream fetched " + key(id, quality) + " in " + (System.currentTimeMillis() - (Long) call[3]) + "ms");
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
            lastRangeAt = System.currentTimeMillis();
            if (videoClass == null) {
                learnVideo(video);
                // 第一次认出播放器的读取方式之前排的任务下不了：认出之后把这一轮重排一次。
                if (lastId != null) schedule(lastId, lastQuality);
            } else if (videoHttpField != null) {
                Object http = videoHttpField.get(video);
                if (http != null) httpClient = http;
            }
            String url = urlOfVideo(video);
            Entry e = url == null ? null : BY_URL.get(url);
            byte[] bytes = e == null || rangeConstructor == null ? null : e.read(start, end);
            if (bytes == null) { lastNetAt = System.currentTimeMillis(); return null; }
            if (start == 0) log("Preload served from disk " + e.key);
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

    /** 取详情方法的入口：记下内容仓库（还没播过任何一集时，预热也要用它取地址）。 */
    public static void repoSeen(Object repo) {
        if (!enabled || repo == null || Boolean.TRUE.equals(INTERNAL.get())) return;
        try {
            if (repository == null) repository = repo;
            if (streamMethod == null) {
                Method found = null; int count = 0;
                for (Method m : allMethods(repo.getClass())) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 2 || p[0] != String.class || p[1] != int.class || Modifier.isStatic(m.getModifiers())) continue;
                    if (looksLikeStream(m.getReturnType())) { found = m; count++; }
                }
                if (count == 1) { found.setAccessible(true); streamMethod = found; streamClass = found.getReturnType(); }
            }
        } catch (Throwable t) { fail("repoSeen", t); }
    }

    private static boolean looksLikeStream(Class<?> c) {
        if (c.isPrimitive() || c.getName().startsWith("java.")) return false;
        int strings = 0, bytes = 0;
        for (Field f : allFields(c)) { if (f.getType() == String.class) strings++; if (f.getType() == byte[].class) bytes++; }
        return strings >= 1 && bytes == 1;
    }

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

    /**
     * 打开剧集页时的预热：第 1 集、上次看到的那一集和它的下一集，先取好地址、下好开头。
     * 点下去时「正在获取播放地址」那段等待就省掉了。正在播放时不做（详情也可能是播放器里取的）。
     */
    private static void warm(List<String> episodes) {
        if (Boolean.TRUE.equals(INTERNAL.get()) || repository == null || streamMethod == null) return;
        long now = System.currentTimeMillis();
        // 播放中（或刚播完几分钟内）取到的详情多半是播放器自己要的，不打扰正在进行的预载。
        if (now - lastPlayAt < 180_000 || now - lastRangeAt < 60_000) return;
        final int quality = lastQuality > 0 ? lastQuality : 1080;
        final List<String> picks = new ArrayList<>();
        String watched = lastWatched(episodes);
        if (watched != null) {
            picks.add(watched);
            int i = episodes.indexOf(watched);
            if (i >= 0 && i + 1 < episodes.size()) picks.add(episodes.get(i + 1));
        }
        if (!picks.contains(episodes.get(0))) picks.add(episodes.get(0));
        final long ticket = WINDOW.incrementAndGet();
        Set<String> keep = new LinkedHashSet<>();
        for (String id : picks) keep.add(key(id, quality));
        // 同一部剧已经预载好的那几集也留着（刚看完退回剧集页时，后面几集不用重下）。
        for (String k : BY_KEY.keySet()) if (episodes.contains(k.substring(0, k.indexOf(':')))) keep.add(k);
        prune(keep);
        WORKER.execute(() -> {
            INTERNAL.set(Boolean.TRUE);
            try {
                cleanOnce();
                for (String id : picks) {
                    if (WINDOW.get() != ticket) return;
                    String k = key(id, quality);
                    Entry e = BY_KEY.get(k);
                    if (e == null || !e.servable()) {
                        long t0 = System.currentTimeMillis();
                        Object stream = fetchStream(id, quality);
                        if (stream == null) { log("Warm failed " + k); continue; }
                        e = entryFor(k, stream);
                        if (e == null) continue;
                        log("Warm prepared " + k + " in " + (System.currentTimeMillis() - t0) + "ms");
                    }
                    download(e, ticket, HEAD_BYTES);
                }
            } catch (Throwable t) {
                log("Warm stopped: " + t);
            } finally {
                INTERNAL.remove();
            }
        });
    }

    private static void prune(Set<String> keep) {
        for (Iterator<Map.Entry<String, Entry>> it = BY_KEY.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry> item = it.next();
            if (!keep.contains(item.getKey())) { it.remove(); BY_URL.remove(item.getValue().url); item.getValue().close(); }
        }
    }

    private static void schedule(String currentId, int quality) {
        lastPlayAt = System.currentTimeMillis();
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
        prune(keep);
        WORKER.execute(() -> {
            INTERNAL.set(Boolean.TRUE);
            try {
                cleanOnce();
                // 正在播的这一集不另外下载：播放器自己读到的字节 rangeAfter 会顺手存下来，
                // 再抓一遍等于对同一个文件发两倍请求，最容易被 CDN 限流（429）。
                for (String id : window) {
                    if (WINDOW.get() != ticket) return;
                    String k = key(id, quality);
                    Entry e = BY_KEY.get(k);
                    if (!waitForIdle(ticket)) return;
                    if (e == null || !e.usable()) {
                        Object stream = fetchStream(id, quality);
                        if (stream == null) { log("Preload failed " + k + ": no stream"); continue; }
                        e = entryFor(k, stream);
                        if (e == null) continue;
                        log("Preload prepared " + k);
                    }
                    if (download(e, ticket, Long.MAX_VALUE)) log("Preload complete " + k);
                    else if (System.currentTimeMillis() < pausedUntil) return;
                }
            } catch (Throwable t) {
                log("Preload stopped: " + t);
            } finally {
                INTERNAL.remove();
            }
        });
    }

    /** 等到播放器不忙、也不在限流暂停期内。返回 false 表示这一轮已作废（换集了）。 */
    private static boolean waitForIdle(long ticket) {
        try {
            while (WINDOW.get() == ticket) {
                long now = System.currentTimeMillis();
                if (now >= pausedUntil && now - lastNetAt >= PLAYER_BUSY_MS) return true;
                Thread.sleep(1_000L);
            }
        } catch (InterruptedException ignored) {}
        return false;
    }

    private static Object fetchStream(String id, int quality) {
        Object repo = repository; Method m = streamMethod;
        if (repo == null || m == null) return null;
        try { return m.invoke(repo, id, quality); }
        catch (Throwable t) { log("Preload stream error " + id + ": " + rootMessage(t)); return null; }
    }

    /** 用官方自己的分段读取把整集原始字节下载到磁盘。返回是否已完整。 */
    private static boolean download(Entry e, long ticket, long limit) {
        if (e.file == null || e.complete()) return e.complete();
        if (videoConstructor == null || httpClient == null || rangeMethod == null) return false;
        for (int attempt = 1; attempt <= 2 && !e.complete() && WINDOW.get() == ticket && !e.closed && e.filled < limit; attempt++) {
            Object video = null;
            try {
                video = newVideo(e);
                while (!e.complete() && WINDOW.get() == ticket && !e.closed && e.filled < limit) {
                    if (!waitForIdle(ticket)) return e.complete();
                    long start = e.filled;
                    long end = e.total > 0 ? Math.min(e.total, start + CHUNK) - 1 : start + 256 * 1024 - 1;
                    Object range = rangeMethod.invoke(video, start, end);
                    learnRange(range);
                    if (!e.offer(start, (byte[]) rangeBytesField.get(range), rangeTotalField.getLong(range))) break;
                    Thread.sleep(CHUNK_PAUSE_MS);
                }
            } catch (InterruptedException ie) {
                return e.complete();
            } catch (Throwable t) {
                String why = rootMessage(t);
                boolean limited = why.contains("429");
                // 被限流就整整停 2 分钟；其他错误停 30 秒，不连着重试（第二次也失败才放弃这一集）。
                pausedUntil = System.currentTimeMillis() + (limited ? 120_000L : 30_000L);
                log("Preload download error " + e.key + " attempt " + attempt + " at " + e.filled + "/" + e.total + ": " + why
                        + " → pause " + (limited ? 120 : 30) + "s");
                if (limited) return e.complete();
            } finally {
                if (video instanceof Closeable) try { ((Closeable) video).close(); } catch (Throwable ignored) {}
            }
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
        // 构造参数随版本会变（1.2.6：连接 + 地址；1.2.7 起又多一个「id:清晰度」字串），
        // 只要求其中有一个 StreamInfo、一个连接对象，其余的字串 / 基本型别按类型补。
        Class<?> httpType = null;
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            Class<?>[] p = k.getParameterTypes();
            boolean hasStream = false; Class<?> other = null; boolean ok = true;
            for (Class<?> t : p) {
                if (streamClass != null && t == streamClass) hasStream = true;
                else if (t == String.class || t.isPrimitive()) continue;
                else if (other == null) other = t;
                else ok = false;
            }
            if (hasStream && other != null && ok) { k.setAccessible(true); videoConstructor = k; httpType = other; }
        }
        if (videoConstructor == null) throw new IllegalStateException("video constructor not found");
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

    private static Object newVideo(Entry e) throws Exception {
        Class<?>[] p = videoConstructor.getParameterTypes();
        Object[] args = new Object[p.length];
        for (int i = 0; i < p.length; i++) {
            Class<?> t = p[i];
            if (t == streamClass) args[i] = e.stream;
            else if (t == String.class) args[i] = e.key;
            else if (t == boolean.class) args[i] = false;
            else if (t == int.class) args[i] = 0;
            else if (t == long.class) args[i] = 0L;
            else if (t.isPrimitive()) throw new IllegalStateException("unsupported constructor param " + t);
            else args[i] = httpClient;
        }
        return videoConstructor.newInstance(args);
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
        /** 可以直接交给播放器：整集已在磁盘上，或地址取到还不久（没过期）。 */
        boolean servable() { return !closed && (complete() || System.currentTimeMillis() - createdAt < FRESH_TTL); }

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

    // ───────────────────────────── 上次看到哪一集（按剧记，存在缓存目录） ─────────────────────────────

    private static final java.util.Properties WATCHED = new java.util.Properties();
    private static volatile boolean watchedLoaded;

    private static File watchedFile() {
        File dir = cacheDir();
        return dir == null ? null : new File(dir.getParentFile(), "hgtv-preload-watched.properties");
    }

    private static synchronized void loadWatched() {
        if (watchedLoaded) return;
        watchedLoaded = true;
        File f = watchedFile();
        if (f == null || !f.exists()) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { WATCHED.load(in); } catch (Throwable ignored) {}
    }

    private static synchronized void rememberWatched(String id) {
        String series = null;
        synchronized (SERIES) { for (List<String> list : SERIES) if (list.contains(id)) { series = list.get(0); break; } }
        if (series == null) return;
        loadWatched();
        if (id.equals(WATCHED.getProperty(series))) return;
        WATCHED.setProperty(series, id);
        while (WATCHED.size() > 200) WATCHED.remove(WATCHED.keys().nextElement());
        File f = watchedFile();
        if (f == null) return;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { WATCHED.store(out, null); } catch (Throwable ignored) {}
    }

    private static synchronized String lastWatched(List<String> episodes) {
        loadWatched();
        String id = WATCHED.getProperty(episodes.get(0));
        return id != null && episodes.contains(id) ? id : null;
    }

    // ═════════════════════════════ 修补版功能（2026-11）═════════════════════════════
    //  1. 启动清理：删 dns-v1.txt（作者存的 DNS）与 playback-v1 里没下完的 .tmp。
    //  2. 失败时清 DNS：播放失败（连不上 / 本地准备超时 / 缓冲停住）就清这一集用到的伺服器；
    //     同一集 3 分钟内失败 2 次就全部清。同一台 30 秒内最多清一次。
    //  3. 大陆公共 DNS 查到的结果只用 60 秒，不留到下次。
    //  4. 主线路 / 备用线路轮替：同一集重取地址时 main_url 与 backup_url_1 轮流用。
    //  5. 诊断页：电视上开一个只读网页 http://<电视IP>:18321/diag，内容是修补事件 + 本 App 的 log。

    private static final int DIAG_PORT = 18321;
    private static final int EVENT_MAX = 300;
    private static final java.util.ArrayDeque<String> EVENTS = new java.util.ArrayDeque<>();
    private static volatile File diagFile;
    private static volatile File appCacheDir;
    private static volatile String appVersion = "?";
    private static final long STARTED = System.currentTimeMillis();
    private static final java.util.concurrent.atomic.AtomicInteger DNS_CLEARS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger LINE_SWITCHES = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger FAILURES = new java.util.concurrent.atomic.AtomicInteger();

    /** 记一条修补事件：内存保留最近 300 条，同时写进 files/hgtv-fix-diag.txt（重开 App 也看得到）。 */
    static void ev(String what) {
        String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + " " + what;
        synchronized (EVENTS) {
            EVENTS.addLast(line);
            while (EVENTS.size() > EVENT_MAX) EVENTS.removeFirst();
            File f = diagFile;
            if (f != null) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, true)) { out.write((line + "\n").getBytes("UTF-8")); } catch (Throwable ignored) {}
            }
        }
        log("[fix] " + what);
    }

    // ───────────── 1. 启动 ─────────────

    /** 插在 Application.onCreate 最前面（作者读 DNS / 播放缓存之前）。 */
    public static void onAppStart(Object app) {
        try {
            android.content.Context ctx = (android.content.Context) app;
            File cache = ctx.getCacheDir();
            appCacheDir = cache;
            File files = ctx.getFilesDir();
            File df = new File(files, "hgtv-fix-diag.txt");
            synchronized (EVENTS) {
                // 只留上次最后 300 行，避免档案越长越大
                List<String> old = new ArrayList<>();
                if (df.exists()) {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(df), "UTF-8"))) {
                        for (String l; (l = r.readLine()) != null; ) old.add(l);
                    } catch (Throwable ignored) {}
                }
                int from = Math.max(0, old.size() - EVENT_MAX);
                StringBuilder keep = new StringBuilder();
                for (int i = from; i < old.size(); i++) { EVENTS.addLast(old.get(i)); keep.append(old.get(i)).append('\n'); }
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(df, false)) { out.write(keep.toString().getBytes("UTF-8")); } catch (Throwable ignored) {}
                diagFile = df;
            }
            try { appVersion = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Throwable ignored) {}
            boolean dns = new File(cache, "dns-v1.txt").delete();
            int tmp = deleteTmp(new File(cache, "playback-v1"));
            ev("=== App 启动 " + appVersion + "：清 DNS 档=" + dns + "，清未完成暂存=" + tmp + " 个");
            startDiag();
        } catch (Throwable t) { log("onAppStart failed: " + rootMessage(t)); }
    }

    private static int deleteTmp(File dir) {
        int n = 0;
        File[] list = dir == null ? null : dir.listFiles();
        if (list == null) return 0;
        for (File f : list) {
            if (f.isDirectory()) n += deleteTmp(f);
            else if (f.getName().endsWith(".tmp") && f.delete()) n++;
        }
        return n;
    }

    // ───────────── 2 / 3. DNS ─────────────

    private static volatile Object dnsInstance;
    private static volatile Method dnsPersist;
    private static final List<Map<?, ?>> DNS_MAPS = new ArrayList<>();
    private static final Map<String, Long> HOST_CLEARED = new ConcurrentHashMap<>();

    /** 插在作者 DNS 查询方法（OkHttp Dns.lookup）最前面：认出它的快取。 */
    public static void dnsSeen(Object dns) {
        if (dnsInstance != null || dns == null) return;
        try {
            Class<?> c = dns.getClass();
            synchronized (DNS_MAPS) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || !Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object m = f.get(null);
                    if (m instanceof Map) DNS_MAPS.add((Map<?, ?>) m);
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0 || m.getReturnType() != void.class) continue;
                m.setAccessible(true); dnsPersist = m; break;
            }
            dnsInstance = dns;
        } catch (Throwable t) { log("dnsSeen failed: " + rootMessage(t)); }
    }

    /** 插在「改问大陆公共 DNS」那一支：这个结果 60 秒后丢掉，下次重新问系统 DNS。 */
    public static void dnsPublic(final String host) {
        if (host == null) return;
        ev("系统 DNS 查不到，改用大陆公共 DNS：" + host + "（60 秒后作废）");
        Thread t = new Thread(() -> {
            try { Thread.sleep(60_000L); } catch (InterruptedException ignored) {}
            dropHost(host);
        }, "hgtv-dns-expire");
        t.setDaemon(true); t.start();
    }

    private static boolean dropHost(String host) {
        boolean any = false;
        synchronized (DNS_MAPS) { for (Map<?, ?> m : DNS_MAPS) any |= m.remove(host) != null; }
        persistDns();
        return any;
    }

    private static void persistDns() {
        try { Object d = dnsInstance; Method m = dnsPersist; if (d != null && m != null) m.invoke(d); } catch (Throwable ignored) {}
    }

    static void clearHost(String host, String why) {
        if (host == null || host.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long last = HOST_CLEARED.get(host);
        if (last != null && now - last < 30_000L) return;
        HOST_CLEARED.put(host, now);
        dropHost(host);
        DNS_CLEARS.incrementAndGet();
        ev("清 DNS " + host + "（" + why + "）");
    }

    static void clearAllDns(String why) {
        synchronized (DNS_MAPS) { for (Map<?, ?> m : DNS_MAPS) m.clear(); }
        File cache = appCacheDir;
        if (cache != null) new File(cache, "dns-v1.txt").delete();
        persistDns();
        DNS_CLEARS.incrementAndGet();
        ev("清全部 DNS（" + why + "）");
    }

    // ───────────── 2. 播放失败 ─────────────

    private static volatile String curFid = "";
    private static final Map<String, List<Long>> FAILS = new ConcurrentHashMap<>();
    private static volatile long lastTmpClean;

    /** 插在作者的播放失败上报（stage = playback_io / playback_prepare / playback_stall_buffer …）最前面。 */
    public static void onFailure(Throwable error, String stage) {
        try {
            FAILURES.incrementAndGet();
            String fid = curFid;
            Line line = LINES.get(fid);
            ev("播放失败 stage=" + stage + " err=" + (error == null ? "-" : rootMessage(error))
                    + " 线路=" + (line == null ? "?" : (line.usingBackup ? "备用 " : "主 ") + line.current()));
            if (stage == null || !(stage.contains("io") || stage.contains("prepare") || stage.contains("stall") || stage.contains("source"))) return;
            if (line != null) clearHost(line.current(), stage);
            if (stage.contains("prepare")) {
                long now = System.currentTimeMillis();
                if (now - lastTmpClean > 60_000L) { lastTmpClean = now; int n = deleteTmp(appCacheDir == null ? null : new File(appCacheDir, "playback-v1")); if (n > 0) ev("清未完成暂存 " + n + " 个"); }
            }
            List<Long> times = FAILS.get(fid);
            if (times == null) { times = new ArrayList<>(); FAILS.put(fid, times); }
            long now = System.currentTimeMillis();
            synchronized (times) {
                times.add(now);
                for (Iterator<Long> it = times.iterator(); it.hasNext(); ) if (now - it.next() > 180_000L) it.remove();
                if (times.size() >= 2) { times.clear(); clearAllDns("同一集 3 分钟内失败 2 次"); }
            }
        } catch (Throwable t) { log("onFailure hook failed: " + rootMessage(t)); }
    }

    // ───────────── 4. 主 / 备线路轮替 ─────────────

    private static final class Line {
        String mainHost = "-", backupHost = "-"; int fetches; long last; boolean usingBackup;
        String current() { return usingBackup ? backupHost : mainHost; }
    }
    private static final Map<String, Line> LINES = new ConcurrentHashMap<>();

    /** 插在作者解析 main_url 之前（参数是这一档影片的 JSONObject）。同一集重取地址时主 / 备轮流。 */
    public static void pickLine(Object video) {
        try {
            if (!(video instanceof org.json.JSONObject)) return;
            org.json.JSONObject o = (org.json.JSONObject) video;
            String fid = o.optString("file_id", "");
            if (fid.isEmpty()) fid = o.optString("file_hash", "");
            String main = o.optString("main_url", "");
            String backup = plainUrl(o.optString("backup_url_1", ""));
            long now = System.currentTimeMillis();
            Line l = LINES.get(fid);
            if (l == null || now - l.last > 180_000L) { l = new Line(); LINES.put(fid, l); }
            if (LINES.size() > 50) LINES.clear();
            l.fetches++; l.last = now;
            l.mainHost = hostOf(plainUrl(main)); l.backupHost = hostOf(backup);
            curFid = fid;
            boolean wantBackup = l.fetches % 2 == 0 && backup.startsWith("http");
            if (wantBackup) {
                o.put("main_url", backup);
                l.usingBackup = true;
                LINE_SWITCHES.incrementAndGet();
                ev("线路 第" + l.fetches + "次取地址 → 备用 " + l.backupHost + "（主 " + l.mainHost + "）");
            } else {
                l.usingBackup = false;
                if (l.fetches > 1) ev("线路 第" + l.fetches + "次取地址 → 主 " + l.mainHost);
            }
        } catch (Throwable t) { log("pickLine failed: " + rootMessage(t)); }
    }

    /** 平台的网址可能是明文、带 \/ 跳脱，或 base64。统一转成明文网址；转不了就回传空字串。 */
    private static String plainUrl(String s) {
        if (s == null) return "";
        s = s.trim().replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
        if (s.startsWith("http")) return s;
        if (s.isEmpty()) return "";
        try {
            String d = new String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), "UTF-8").trim();
            return d.startsWith("http") ? d : "";
        } catch (Throwable t) { return ""; }
    }

    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "-";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^https?://([^/:?#]+)").matcher(url);
        return m.find() ? m.group(1) : "(?)";
    }

    // ───────────── 5. 诊断页 ─────────────

    private static volatile boolean diagStarted;

    private static void startDiag() {
        if (diagStarted) return;
        diagStarted = true;
        Thread t = new Thread(() -> {
            try (java.net.ServerSocket server = new java.net.ServerSocket(DIAG_PORT)) {
                ev("诊断页已开：http://<电视IP>:" + DIAG_PORT + "/diag");
                while (true) {
                    try (java.net.Socket s = server.accept()) {
                        s.setSoTimeout(5_000);
                        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), "UTF-8"));
                        String first = in.readLine();
                        String body = first != null && first.startsWith("GET /diag") ? report() : "HGTV 修补版：请开 /diag\n";
                        byte[] bytes = body.getBytes("UTF-8");
                        java.io.OutputStream out = s.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " + bytes.length + "\r\n\r\n").getBytes("UTF-8"));
                        out.write(bytes);
                        out.flush();
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable e) { ev("诊断页开不起来：" + rootMessage(e)); }
        }, "hgtv-diag");
        t.setDaemon(true);
        t.start();
    }

    private static String report() {
        StringBuilder b = new StringBuilder();
        b.append("HGTV 修补版诊断\n");
        b.append("版本 ").append(appVersion).append("  装置 ").append(android.os.Build.MODEL).append(" Android ").append(android.os.Build.VERSION.RELEASE).append('\n');
        b.append("现在 ").append(new java.util.Date()).append("  已运行 ").append((System.currentTimeMillis() - STARTED) / 60_000L).append(" 分钟\n");
        b.append("清 DNS ").append(DNS_CLEARS.get()).append(" 次 / 换备用线路 ").append(LINE_SWITCHES.get()).append(" 次 / 播放失败 ").append(FAILURES.get()).append(" 次\n");
        Line l = LINES.get(curFid);
        if (l != null) b.append("目前这集：主 ").append(l.mainHost).append(" / 备 ").append(l.backupHost).append(" / 正在用 ").append(l.usingBackup ? "备用" : "主").append(" / 取地址 ").append(l.fetches).append(" 次\n");
        b.append("\n===== 修补事件（最近 ").append(EVENT_MAX).append(" 条，含之前启动的） =====\n");
        synchronized (EVENTS) { for (String e : EVENTS) b.append(e).append('\n'); }
        b.append("\n===== 本 App 的 log（最近 800 行） =====\n");
        b.append(appLog());
        return b.toString();
    }

    private static String appLog() {
        int pid = android.os.Process.myPid();
        String[][] tries = { {"logcat", "-d", "-v", "time", "-t", "800", "--pid=" + pid}, {"logcat", "-d", "-v", "time", "-t", "3000"} };
        for (String[] cmd : tries) {
            try {
                java.lang.Process p = Runtime.getRuntime().exec(cmd);
                StringBuilder out = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String needle = "(" + pid + ")";
                    for (String line; (line = r.readLine()) != null; ) {
                        if (cmd.length == 6 && !line.contains(needle) && !line.contains(" " + pid + " ")) continue;
                        out.append(line).append('\n');
                    }
                }
                p.waitFor();
                if (out.length() > 0) return out.toString();
            } catch (Throwable ignored) {}
        }
        return "（读不到 log）\n";
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
