package hgtv.preload;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * 播放画面右上角的小提示框（繁体）：有状况才出现，显示「问题 → 处理 → 结果」，处理完约 5 秒自动消失。
 * 只是 App 自己画面上的一个 TextView（不是系统浮动视窗、不抢遥控器焦点）。
 * 开关：诊断页 http://<电视IP>:18321/osd/on 、 /osd/off（记在 files/hgtv-osd.txt，重开 App 仍有效）。
 */
final class Osd {
    private Osd() {}

    static volatile boolean on = true;
    private static volatile File prefFile;
    private static Handler ui;

    private static WeakReference<Activity> cur = new WeakReference<>(null);
    private static TextView tv;
    private static Activity tvOwner;

    private static String problem, action, result;
    private static long cardAt;
    private static final long ACTIVE_MS = 20_000L, ASSUME_OK_MS = 15_000L, HIDE_AFTER_MS = 5_000L;

    private static final Runnable HIDE = () -> { problem = action = result = null; draw(); };
    private static final Runnable ASSUME_OK = () -> {
        if (problem != null && result == null) {
            result = "✓ 應該已恢復（15 秒內沒有再出錯）";
            draw();
            later(HIDE, HIDE_AFTER_MS);
        }
    };

    static void init(Object app, File filesDir) {
        try {
            ui = new Handler(Looper.getMainLooper());
            prefFile = new File(filesDir, "hgtv-osd.txt");
            if (prefFile.exists()) {
                byte[] b = new byte[8];
                int n;
                try (java.io.FileInputStream in = new java.io.FileInputStream(prefFile)) { n = in.read(b); }
                on = !(n > 0 && new String(b, 0, n, "UTF-8").trim().equals("off"));
            }
            if (app instanceof Application) ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityResumed(Activity a) { cur = new WeakReference<>(a); if (problem != null) draw(); }
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) { if (tvOwner == a) { tv = null; tvOwner = null; } }
            });
        } catch (Throwable ignored) {}
    }

    static void setOn(boolean v) {
        on = v;
        File f = prefFile;
        if (f != null) try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, false)) { out.write((v ? "on" : "off").getBytes("UTF-8")); } catch (Throwable ignored) {}
        if (!v) post(HIDE);
    }

    /** 新状况：盖掉旧的提示。 */
    static void problem(final String p, final String a) {
        post(() -> {
            problem = "⚠ " + p; action = a == null ? null : "→ " + a; result = null; cardAt = System.currentTimeMillis();
            cancel(HIDE); cancel(ASSUME_OK);
            later(ASSUME_OK, ASSUME_OK_MS);
            draw();
        });
    }

    /** 同一个状况的下一步处理（没有进行中的提示就不显示）。 */
    static void action(final String a) {
        post(() -> {
            if (!active()) return;
            action = "→ " + a;
            cancel(ASSUME_OK); later(ASSUME_OK, ASSUME_OK_MS);
            draw();
        });
    }

    /** 处理成功（没有进行中的提示就不显示，平常不打扰）。 */
    static void ok(final String r) {
        post(() -> {
            if (!active()) return;
            result = "✓ " + r;
            cancel(ASSUME_OK); cancel(HIDE); later(HIDE, HIDE_AFTER_MS);
            draw();
        });
    }

    /** 这一步没成功（会继续自动处理）。 */
    static void fail(final String r) {
        post(() -> {
            if (!active()) return;
            result = "✗ " + r;
            cardAt = System.currentTimeMillis();
            cancel(ASSUME_OK); later(ASSUME_OK, ASSUME_OK_MS);
            draw();
        });
    }

    static String state() {
        return on ? "开启（关闭：/osd/off）" : "关闭（开启：/osd/on）";
    }

    // ───── 内部 ─────

    private static boolean active() {
        return problem != null && System.currentTimeMillis() - cardAt < ACTIVE_MS + ASSUME_OK_MS;
    }

    private static void post(Runnable r) { Handler h = ui; if (h != null) h.post(guard(r)); }
    private static void later(Runnable r, long ms) { Handler h = ui; if (h != null) h.postDelayed(r, ms); }
    private static void cancel(Runnable r) { Handler h = ui; if (h != null) h.removeCallbacks(r); }
    private static Runnable guard(final Runnable r) { return () -> { try { r.run(); } catch (Throwable ignored) {} }; }

    /** 只在主执行绪呼叫。 */
    private static void draw() {
        try {
            if (!on || problem == null) { if (tv != null) tv.setVisibility(View.GONE); return; }
            Activity a = cur.get();
            if (a == null) return;
            if (tv == null || tvOwner != a) {
                if (tv != null) { ViewParent p = tv.getParent(); if (p instanceof ViewGroup) ((ViewGroup) p).removeView(tv); }
                float d = a.getResources().getDisplayMetrics().density;
                TextView t = new TextView(a);
                t.setTextColor(0xFFFFFFFF);
                t.setBackgroundColor(0xB0000000);
                t.setTextSize(2 /* SP */, 15f);
                t.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
                t.setMaxWidth((int) (420 * d));
                t.setFocusable(false);
                t.setClickable(false);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, 48 /* TOP */ | 8388613 /* END */);
                lp.topMargin = (int) (24 * d);
                lp.rightMargin = (int) (24 * d);
                ((ViewGroup) a.getWindow().getDecorView()).addView(t, lp);
                tv = t; tvOwner = a;
            }
            StringBuilder b = new StringBuilder(problem);
            if (action != null) b.append('\n').append(action);
            b.append('\n').append(result != null ? result : "… 處理中");
            tv.setText(b.toString());
            tv.setVisibility(View.VISIBLE);
            tv.bringToFront();
        } catch (Throwable ignored) {}
    }

    /** v26-reading-videocdn302.qznovelvod.com → v26-videocdn302 */
    static String host(String h) {
        if (h == null) return "?";
        return h.replace(".qznovelvod.com", "").replace("-reading-videocdn", "-cdn").replace("-reading-video", "").replace("-reading-", "-");
    }
}
