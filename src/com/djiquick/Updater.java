package com.djiquick;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Обновление приложения из релизов GitHub.
 *
 * Ставим через PackageInstaller, а не через Intent на APK-файл: на этом пульте системный
 * экран «установка из неизвестных источников» местами заблокирован, а FileProvider под
 * targetSdk 30 всё равно потребовался бы. PackageInstaller обходится без файла на диске.
 * Подпись у релизных сборок та же (общий debug-keystore) → идёт update-install поверх,
 * без удаления и без потери сохранённых индексов параметров.
 *
 * Проверка запускается ТОЛЬКО по кнопке пользователя — фоновых запросов в сеть нет.
 */
public final class Updater {

    private static final String OWNER_REPO = "lmdegreeds/dji_gpsoff";
    private static final String API_LATEST =
            "https://api.github.com/repos/" + OWNER_REPO + "/releases/latest";
    private static final String WEB_LATEST =
            "https://github.com/" + OWNER_REPO + "/releases/latest";
    private static final String UA = "djiquick/" + BuildStamp.NAME;

    /** Что нашлось в последнем релизе. */
    public static final class Release {
        public String tag = "";        // например «v0.18»
        public String version = "";    // тот же тег без ведущей «v»
        public String apkUrl = "";     // ссылка на .apk из ассетов релиза
        public String notes = "";      // описание релиза (первые строки)
        public boolean newer;          // строго новее текущей сборки
        public boolean hasApk() { return !apkUrl.isEmpty(); }
    }

    public interface Callback {
        /** rel == null → проверить не удалось; err — что показать пользователю. */
        void onResult(Release rel, String err);
    }

    private Updater() {}

    // ---- проверка ----

    /** Спросить GitHub о последнем релизе. БЛОКИРУЮЩИЙ — звать не из main-треда. */
    public static Release check() throws Exception {
        Release r = new Release();
        try {
            JSONObject o = new JSONObject(httpGetText(API_LATEST, "application/vnd.github+json"));
            r.tag = o.optString("tag_name", "");
            r.notes = trim(o.optString("body", ""), 1200);
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.toLowerCase().endsWith(".apk")) {
                        r.apkUrl = a.optString("browser_download_url", "");
                        break;
                    }
                }
            }
        } catch (Throwable apiFail) {
            // API лимитирован (60 запросов в час на IP). Тег всё равно достаём из редиректа
            // /releases/latest — он не лимитирован; ссылку на APK тогда не узнаем.
            Logger.w("[update] API недоступен (" + apiFail + "), пробую редирект");
            r.tag = tagFromRedirect();
        }
        if (r.tag == null || r.tag.isEmpty()) throw new IllegalStateException("релиз не найден");
        r.version = r.tag.startsWith("v") || r.tag.startsWith("V") ? r.tag.substring(1) : r.tag;
        r.newer = isNewer(r.version, BuildStamp.NAME);
        Logger.i("[update] последний релиз " + r.tag + " newer=" + r.newer + " apk=" + r.hasApk());
        return r;
    }

    /** Асинхронная проверка с колбэком в вызывающем потоке-обработчике. */
    public static void checkAsync(final android.os.Handler ui, final Callback cb) {
        new Thread(() -> {
            Release rel = null;
            String err = null;
            try { rel = check(); }
            catch (Throwable t) {
                Logger.w("[update] проверка: " + t);
                err = "Не удалось проверить обновление (" + t.getClass().getSimpleName() + ")";
            }
            final Release fr = rel; final String fe = err;
            ui.post(() -> cb.onResult(fr, fe));
        }, "updcheck").start();
    }

    /** Тег последнего релиза по 302-редиректу github.com — в отличие от API не лимитируется. */
    private static String tagFromRedirect() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(WEB_LATEST).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(9000); c.setReadTimeout(9000);
        try {
            c.getResponseCode();
            String loc = c.getHeaderField("Location");     // …/releases/tag/v0.18
            if (loc == null) return null;
            int i = loc.lastIndexOf('/');
            return i >= 0 && i + 1 < loc.length() ? loc.substring(i + 1) : null;
        } finally { c.disconnect(); }
    }

    // ---- установка ----

    public interface InstallCallback {
        /** ok=false → err содержит текст для пользователя. */
        void onDone(boolean ok, String err);
    }

    /** Есть ли разрешение ставить APK. На API &lt; 26 вопрос не стоит. */
    public static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return true;
        try { return ctx.getPackageManager().canRequestPackageInstalls(); }
        catch (Throwable t) { return false; }
    }

    /**
     * Скачать APK и отдать его PackageInstaller'у. Система сама покажет диалог подтверждения
     * (его поднимает InstallReceiver). БЛОКИРУЮЩИЙ вызов — запускать в отдельном потоке.
     */
    public static void download(final Context ctx, final String url, final android.os.Handler ui,
                                final InstallCallback cb) {
        new Thread(() -> {
            String err = null;
            try {
                byte[] apk = httpGetBytes(url);
                if (apk.length < 16 * 1024) throw new IllegalStateException("файл слишком мал");
                Logger.i("[update] скачано " + apk.length + " б");

                PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sid = pi.createSession(params);
                PackageInstaller.Session s = pi.openSession(sid);
                try (java.io.OutputStream out = s.openWrite("apk", 0, apk.length)) {
                    out.write(apk);
                    s.fsync(out);
                }
                Intent it = new Intent(ctx, InstallReceiver.class);
                int flags = Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0;
                android.app.PendingIntent pend = android.app.PendingIntent.getBroadcast(ctx, sid, it, flags);
                s.commit(pend.getIntentSender());
                s.close();
                Logger.i("[update] сессия " + sid + " отправлена на установку");
            } catch (Throwable t) {
                Logger.w("[update] установка: " + t);
                err = "Не удалось установить (" + t.getClass().getSimpleName() + ")";
            }
            final String fe = err;
            ui.post(() -> cb.onDone(fe == null, fe));
        }, "updinstall").start();
    }

    // ---- мелочи ----

    /**
     * Сравнение версий по числам через точку, ведущая «v» игнорируется.
     * «0.18» новее «0.17»; «0.9» новее «0.10» НЕ считается.
     */
    static boolean isNewer(String a, String b) {
        String[] pa = strip(a).split("[.\\-+]"), pb = strip(b).split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? intOr0(pa[i]) : 0;
            int y = i < pb.length ? intOr0(pb[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static String strip(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }

    private static int intOr0(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String httpGetText(String url, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        if (accept != null) c.setRequestProperty("Accept", accept);
        c.setConnectTimeout(9000); c.setReadTimeout(9000);
        try (InputStream in = c.getInputStream()) {
            return new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    private static byte[] httpGetBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);       // GitHub уводит на objects.githubusercontent.com
        c.setConnectTimeout(10000); c.setReadTimeout(60000);
        try (InputStream in = c.getInputStream()) { return readAll(in); }
        finally { c.disconnect(); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
