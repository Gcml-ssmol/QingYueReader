package cn.qingyue.pdf;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/** Private, bounded report. No document names, paths, content, or network traffic. */
public final class CrashDiagnostics {
    private static final String FILE = "crash-report.txt";
    private static final String PREFS = "crash-diagnostics";
    private static final int MAX_CHARS = 12000;
    private static final String[] STAGES = {"启动", "资料库", "阅读器", "导航创建", "导航切换", "导航就绪", "数据读取", "删除准备", "删除刷新"};
    private static volatile String stage = STAGES[0];
    private static String persistedStage;
    private CrashDiagnostics() {}

    public static synchronized void markStage(String value) {
        for (String allowed : STAGES) if (allowed.equals(value)) {
            stage = allowed;
            if (allowed.equals(persistedStage)) return;
            Context app = QingYueApplication.instance();
            if (app != null && app.getSharedPreferences(PREFS, 0).edit()
                    .putString("stage", allowed).commit()) persistedStage = allowed;
            return;
        }
    }

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = app.getSharedPreferences(PREFS, 0);
        final String previousStage = prefs.getString("stage", STAGES[0]);
        final long previousSessionStart = prefs.getLong("sessionStart", 0);
        final String previousVersion = prefs.getString("version", "未知（升级前记录）");
        prefs.edit().putString("version", appVersion(app))
                .putLong("sessionStart", processStartedAt).commit();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try { write(app, "未捕获崩溃", thread, error, true, stage); }
            catch (Throwable ignored) { }
            finally {
                if (previous != null) previous.uncaughtException(thread, error);
                else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
            }
        });
        if (Build.VERSION.SDK_INT >= 30) {
            new Thread(() -> inspectPreviousExit(app, previousStage, previousVersion,
                    previousSessionStart), "qingyue-exit-check").start();
        }
    }

    public static void record(Context context, String tag, Throwable error) {
        String category = "已处理异常";
        for (String known : new String[]{"导航打开失败", "目录读取失败", "批注索引读取失败", "文档删除失败"})
            if (known.equals(tag)) { category = known; break; }
        try { write(context.getApplicationContext(), category, Thread.currentThread(), error, false, stage); }
        catch (Throwable ignored) { }
    }

    private static void inspectPreviousExit(Context app, String previousStage, String previousVersion,
                                            long previousSessionStart) {
        try {
            ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            SharedPreferences prefs = app.getSharedPreferences(PREFS, 0);
            long checked = prefs.getLong("checkedExit", 0);
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(app.getPackageName(), 0, 8);
            if (exits == null) return;
            ApplicationExitInfo latest = null;
            for (ApplicationExitInfo exit : exits) {
                if (!app.getPackageName().equals(exit.getProcessName())) continue;
                long when = exit.getTimestamp();
                if (when <= checked || when >= processStartedAt ||
                        (previousSessionStart > 0 && when < previousSessionStart)) continue;
                if (latest == null || when > latest.getTimestamp()) latest = exit;
            }
            if (latest == null) return;
            prefs.edit().putLong("checkedExit", latest.getTimestamp()).commit();
            int reason = latest.getReason();
            if ((reason == ApplicationExitInfo.REASON_CRASH ||
                    reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    reason == ApplicationExitInfo.REASON_ANR) && !hasUnreadFatal(app)) {
                String kind = reason == ApplicationExitInfo.REASON_CRASH_NATIVE ?
                        "系统记录：原生崩溃" : reason == ApplicationExitInfo.REASON_ANR ?
                        "系统记录：无响应" : "系统记录：崩溃";
                writeSystemExit(app, kind, latest.getTimestamp(),
                        previousSessionStart > 0 ? previousStage : "未知", previousVersion);
            }
        } catch (Throwable ignored) { }
    }

    private static final long processStartedAt = System.currentTimeMillis();

    private static synchronized void writeSystemExit(Context app, String kind, long when,
                                                     String previousStage, String previousVersion) throws Exception {
        if (hasUnreadFatal(app)) return;
        StringBuilder out = header(app, kind, "系统", previousStage, when, previousVersion);
        out.append("\n系统退出原因由 Android 提供。未读取系统堆栈或退出描述。\n");
        save(app, out.toString(), true);
    }

    private static synchronized void write(Context app, String kind, Thread thread, Throwable error,
                                           boolean uncaught, String atStage) throws Exception {
        if (!uncaught && hasUnread(app)) return;
        String threadKind = thread != null && thread == android.os.Looper.getMainLooper().getThread()
                ? "主线程" : "后台线程";
        StringBuilder out = header(app, kind, threadKind, atStage,
                System.currentTimeMillis(), appVersion(app));
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (int depth = 0; error != null && depth < 5 && !seen.containsKey(error) &&
                out.length() < MAX_CHARS; depth++, error = error.getCause()) {
            seen.put(error, Boolean.TRUE);
            out.append(depth == 0 ? "\n异常: " : "\n原因: ")
                    .append(limit(error.getClass().getName(), 160)).append('\n');
            StackTraceElement[] frames = error.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 32) && out.length() < MAX_CHARS; i++) {
                StackTraceElement frame = frames[i];
                // Class and method names identify code, while Throwable messages may contain PDF paths.
                out.append("  at ").append(limit(frame.getClassName(), 180)).append('.')
                        .append(limit(frame.getMethodName(), 100)).append(':')
                        .append(frame.getLineNumber()).append('\n');
            }
        }
        save(app, out.toString(), uncaught);
    }

    private static String appVersion(Context app) {
        String version = "unknown";
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            version = info.versionName;
        } catch (Exception ignored) { }
        return version;
    }

    private static StringBuilder header(Context app, String kind, String thread, String atStage,
                                        long when, String version) {
        return new StringBuilder("晴阅故障记录\n时间: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.CHINA).format(new Date(when)))
                .append("\n版本: ").append(limit(version, 50))
                .append("\n设备: ").append(limit(Build.MANUFACTURER, 50)).append(' ')
                .append(limit(Build.MODEL, 70))
                .append("\nAndroid: ").append(limit(Build.VERSION.RELEASE, 30))
                .append(" (API ").append(Build.VERSION.SDK_INT).append(')')
                .append("\n类型: ").append(kind)
                .append("\n线程: ").append(limit(thread, 80))
                .append("\n阶段: ").append(validStage(atStage)).append('\n');
    }

    private static String validStage(String candidate) {
        if ("未知".equals(candidate)) return candidate;
        for (String allowed : STAGES) if (allowed.equals(candidate)) return allowed;
        return STAGES[0];
    }

    private static void save(Context app, String report, boolean fatal) throws Exception {
        File target = file(app), temp = new File(app.getFilesDir(), FILE + ".tmp");
        byte[] bytes = limit(report, MAX_CHARS).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream stream = new FileOutputStream(temp)) {
            stream.write(bytes);
            stream.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            if (!target.delete() || !temp.renameTo(target)) throw new java.io.IOException("report rename failed");
        }
        SharedPreferences prefs = app.getSharedPreferences(PREFS, 0);
        prefs.edit().putLong("generation", prefs.getLong("generation", 0) + 1)
                .putBoolean("fatal", fatal).commit();
    }

    private static String limit(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), max));
    }
    private static File file(Context app) { return new File(app.getFilesDir(), FILE); }
    public static String read(Context context) {
        File report = file(context);
        if (!report.isFile()) return null;
        try (FileInputStream stream = new FileInputStream(report)) {
            byte[] bytes = new byte[(int) Math.min(report.length(), MAX_CHARS * 4)];
            int count = 0, n;
            while (count < bytes.length && (n = stream.read(bytes, count, bytes.length - count)) > 0) count += n;
            return new String(bytes, 0, count, StandardCharsets.UTF_8);
        } catch (Exception ignored) { return null; }
    }
    public static boolean hasUnread(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        return file(context).isFile() &&
                prefs.getLong("generation", 0) != prefs.getLong("seenGeneration", 0);
    }
    public static boolean hasUnreadFatal(Context context) {
        return hasUnread(context) && context.getSharedPreferences(PREFS, 0).getBoolean("fatal", false);
    }
    public static synchronized boolean claimNewFatalPrompt(Context context) {
        if (!hasUnreadFatal(context)) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        long generation = prefs.getLong("generation", 0);
        if (generation == prefs.getLong("promptedGeneration", 0)) return false;
        return prefs.edit().putLong("promptedGeneration", generation).commit();
    }
    public static void markRead(Context context) {
        if (!file(context).isFile()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        prefs.edit().putLong("seenGeneration", prefs.getLong("generation", 0)).apply();
    }
}
