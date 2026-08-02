package com.djiquick;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/**
 * Автоотчёт о падении — только при включённом согласии.
 *
 * Отправить прямо в момент падения нельзя: процесс уже умирает, а сетевой запрос занимает
 * секунды. Поэтому падение СОХРАНЯЕТСЯ в файл, а отправляется при следующем запуске.
 *
 * Согласие проверяется дважды и по-разному:
 *  - при падении: если согласия нет, файл не пишется вовсе;
 *  - при запуске: если согласие успели отозвать, накопленный файл удаляется НЕОТПРАВЛЕННЫМ.
 * Так «выключил галочку» означает «ничего не уйдёт», даже для уже случившегося падения.
 */
public final class CrashReporter {

    private static final String FILE = "crash.json";
    private static final int LOG_TAIL = 16 * 1024;

    private CrashReporter() {}

    /**
     * Повесить обработчик. Прежний обработчик ВСЕГДА вызывается следом — приложение должно
     * упасть штатно, а не «зависнуть живым трупом» из-за нашего перехвата.
     */
    public static void install(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, err) -> {
            try { save(app, thread, err); } catch (Throwable ignore) { /* падаем дальше молча */ }
            if (prev != null) prev.uncaughtException(thread, err);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    /** Записать отчёт на диск. Ничего не делает без согласия. */
    private static void save(Context ctx, Thread thread, Throwable err) {
        Store store = new Store(ctx);
        if (!store.consentAllowed()) return;

        JSONObject o = new JSONObject();
        try {
            o.put("kind", "crash");
            o.put("ts", System.currentTimeMillis());
            o.put("nonce", Long.toHexString(System.nanoTime()));
            o.put("thread", thread != null ? thread.getName() : "?");
            o.put("exception", err != null ? err.toString() : "?");
            o.put("stack", stackOf(err));

            JSONObject app = new JSONObject();
            app.put("name", BuildStamp.NAME);
            app.put("code", BuildStamp.CODE);
            app.put("git", BuildStamp.GIT);
            app.put("built", BuildStamp.BUILT_MS);
            o.put("app", app);

            JSONObject dev = new JSONObject();
            dev.put("model", Build.MODEL);
            dev.put("device", Build.DEVICE);
            dev.put("fingerprint", Build.FINGERPRINT);
            dev.put("sdk", Build.VERSION.SDK_INT);
            o.put("device", dev);

            o.put("log", Logger.tail(LOG_TAIL));
        } catch (Throwable ignore) {
            // даже частично собранный отчёт лучше, чем ничего
        }

        File f = file(ctx);
        if (f == null) return;
        try (FileWriter w = new FileWriter(f, false)) {     // держим только последнее падение
            w.write(o.toString());
        } catch (Throwable ignore) {}
        android.util.Log.e("DjiQuick", "crash saved: " + f.getAbsolutePath(), err);
    }

    /**
     * Отправить сохранённый отчёт, если он есть. Зовётся при старте, работает в фоне.
     * Без согласия — файл удаляется без отправки.
     */
    public static void flush(final Context ctx) {
        final File f = file(ctx);
        if (f == null || !f.exists()) return;
        final Store store = new Store(ctx);
        if (!store.consentAllowed()) {
            if (!f.delete()) Logger.w("[crash] не удалось удалить отчёт после отзыва согласия");
            else Logger.i("[crash] согласия нет — сохранённый отчёт удалён без отправки");
            return;
        }
        new Thread(() -> {
            try {
                String body = read(f);
                if (body.isEmpty()) { f.delete(); return; }
                TelemetrySink sink = (TelemetrySink) Class.forName("com.djiquick.Telemetry")
                        .getDeclaredConstructor(Context.class).newInstance(ctx.getApplicationContext());
                if (sink.sendDiagnostics(new JSONObject(body))) {
                    if (f.delete()) Logger.i("[crash] отчёт о падении отправлен");
                } else {
                    Logger.i("[crash] отправить не удалось — попробуем при следующем запуске");
                }
            } catch (Throwable t) {
                Logger.w("[crash] отправка: " + t);
            }
        }, "crashflush").start();
    }

    /** Есть ли неотправленный отчёт — показываем на странице «О программе». */
    public static boolean hasPending(Context ctx) {
        File f = file(ctx);
        return f != null && f.exists();
    }

    private static File file(Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            return dir == null ? null : new File(dir, FILE);
        } catch (Throwable t) { return null; }
    }

    private static String read(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) { return ""; }
    }

    private static String stackOf(Throwable err) {
        if (err == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        err.printStackTrace(new java.io.PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 8192 ? s.substring(0, 8192) + "…" : s;
    }
}
