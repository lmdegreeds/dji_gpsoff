package com.djiquick;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import static com.djiquick.Ui.ACCENT;
import static com.djiquick.Ui.AMBER;
import static com.djiquick.Ui.BG;
import static com.djiquick.Ui.GREEN;
import static com.djiquick.Ui.RED;
import static com.djiquick.Ui.ROW_BG;
import static com.djiquick.Ui.TXT;
import static com.djiquick.Ui.TXT_SUB;

/**
 * «О программе»: версия сборки, что известно о борте, переключатель отчётов, журнал и
 * кнопка отправки диагностики разработчику.
 *
 * Кнопка диагностики НЕ требует включённого согласия — согласие регулирует автоматическую
 * фоновую отправку, которой пользователь не видит, а здесь он сам нажимает и сам видит,
 * что именно уходит. Поэтому предпросмотр содержимого обязателен: без него это уже не
 * осознанное действие. Отправка одноразовая — ничего не ставится в очередь и не ретраится.
 */
public final class AboutActivity extends Activity {

    public static final String EX_BOARD = "board";   // JSON-сводка о борте от MainActivity
    public static final String EX_TRACE = "trace";   // трасса последнего детекта

    private static final int LOG_TAIL_CHARS = 32 * 1024;   // столько журнала кладём в пакет
    private static final int LOG_VIEW_CHARS = 8 * 1024;    // столько показываем на экране

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Store store;
    private LinearLayout body;
    private String boardJson = "", trace = "";
    private Updater.Release found;      // результат последней проверки обновления
    private boolean updBusy = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        Logger.attachFile(this);
        boardJson = getIntent() != null ? str(getIntent().getStringExtra(EX_BOARD)) : "";
        trace = getIntent() != null ? str(getIntent().getStringExtra(EX_TRACE)) : "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("О программе");
        title.setTextColor(TXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setBackgroundColor(0xFF000000);
        title.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.addView(title);

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        render();
    }

    private void render() {
        body.removeAllViews();

        // ---- версия ----
        Ui.caption(this, body, "ВЕРСИЯ");
        Ui.statusLine(this, body, About.versionLine(), TXT);
        Ui.note(this, body, "Собрано: " + new java.util.Date(BuildStamp.BUILT_MS)
                + "  ·  " + About.ageDays() + " дн. назад");
        String hint = About.updateHint(store);
        if (hint != null) Ui.statusLine(this, body, hint, AMBER);
        updateSection();

        // ---- борт ----
        Ui.caption(this, body, "ДРОН");
        JSONObject board = parse(boardJson);
        String crc = board.optString("crc", "");
        if (crc.isEmpty()) {
            Ui.note(this, body, "Дрон ещё не определялся в этом запуске.");
        } else {
            Ui.statusLine(this, body, "Прошивка: " + crc + " · параметров " + board.optLong("count"), TXT);
            String label = board.optString("label", "");
            String picked = board.optString("picked", "");
            Ui.statusLine(this, body, "Модель: "
                    + (label.isEmpty() ? "не определена" : label + (picked.isEmpty() ? "" : " (" + picked + ")")), TXT);
            String ac = board.optString("ac_model", ""), rc = board.optString("rc_model", "");
            Ui.note(this, body, "Кодовое имя борта: " + (ac.isEmpty() ? "—" : ac)
                    + "   ·   пульт: " + (rc.isEmpty() ? "—" : rc)
                    + "\nСерийный номер: " + or(board.optString("serial", ""), "—"));
            JSONObject ids = board.optJSONObject("ids");
            if (ids != null) {
                java.util.Iterator<String> it = ids.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject one = ids.optJSONObject(k);
                    int idx = one != null ? one.optInt("idx", -1) : -1;
                    String src = one != null ? one.optString("src", "") : "";
                    Ui.statusLine(this, body, (idx >= 0 ? "✓ " : "✗ ") + k
                            + (idx >= 0 ? " → idx " + idx + (src.isEmpty() ? "" : " (" + src + ")") : " не найден"),
                            idx >= 0 ? GREEN : RED);
                }
            }
        }

        // ---- диагностика ----
        Ui.caption(this, body, "ДИАГНОСТИКА");
        consentSwitch();
        Ui.note(this, body, "Разрешение относится к автоматическим отчётам: о подключении дрона и "
                + "о падении приложения. Кнопка ниже отправляет отчёт вручную и работает независимо "
                + "от переключателя — перед отправкой ты увидишь, что именно уходит.");
        if (CrashReporter.hasPending(this)) {
            Ui.statusLine(this, body, store.consentAllowed()
                    ? "Есть неотправленный отчёт о падении — уйдёт при появлении сети"
                    : "Есть сохранённый отчёт о падении — он будет удалён неотправленным", AMBER);
        }
        Ui.action(this, body, "Показать журнал", ACCENT, v -> showLog());
        Ui.action(this, body, "Отправить диагностику разработчику", ACCENT, v -> previewAndSend());

        // ---- о программе ----
        Ui.caption(this, body, "О ПРОГРАММЕ");
        Ui.note(this, body, "Быстрые тумблеры параметров полётного контроллера для пульта DJI RC 2. "
                + "Идеи по гигиене DUML-кадров (монотонный номер запроса, проверка длины кадра, сверка "
                + "маршрутизации ответа) заимствованы концептуально из проекта FreeFCC (AGPL-3.0); "
                + "кода FreeFCC в этой сборке нет.");
        Ui.action(this, body, "Назад", TXT_SUB, v -> finish());
    }

    // ---- обновление из релизов GitHub ----

    /**
     * Проверка запускается только по кнопке — фоновых обращений в сеть приложение не делает.
     * Найденный релиз держим в поле, чтобы «Установить» не ходил в сеть повторно.
     */
    private void updateSection() {
        if (updBusy) {
            Ui.statusLine(this, body, "Проверяю / скачиваю…", TXT_SUB);
            return;
        }
        if (found == null) {
            Ui.action(this, body, "Проверить обновление", ACCENT, v -> checkUpdate());
            return;
        }
        if (!found.newer) {
            Ui.statusLine(this, body, "Установлена последняя версия (" + found.tag + ")", GREEN);
            Ui.action(this, body, "Проверить ещё раз", TXT_SUB, v -> { found = null; checkUpdate(); });
            return;
        }
        Ui.statusLine(this, body, "Доступна версия " + found.tag, AMBER);
        if (!found.notes.isEmpty()) Ui.note(this, body, found.notes);
        if (found.hasApk()) {
            Ui.action(this, body, "Скачать и установить " + found.tag, GREEN, v -> installUpdate());
        } else {
            // Тег узнали по редиректу (API был недоступен), а ссылки на APK в нём нет.
            Ui.note(this, body, "Ссылку на APK получить не удалось — скачай релиз "
                    + found.tag + " со страницы проекта на GitHub и установи вручную.");
        }
    }

    private void checkUpdate() {
        updBusy = true; render();
        toast("Проверяю обновление…");
        Updater.checkAsync(ui, (rel, err) -> {
            updBusy = false;
            found = rel;
            if (err != null) toast(err);
            else if (rel != null && !rel.newer) toast("Обновлений нет");
            render();
        });
    }

    private void installUpdate() {
        if (found == null || !found.hasApk()) return;
        if (!Updater.canInstall(this)) {
            // На пульте системный экран «установка из неизвестных источников» бывает закрыт —
            // тогда остаётся ручная установка, поэтому текст не обещает, что диалог откроется.
            toast("Нужно разрешение на установку приложений");
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                Logger.w("[update] экран разрешения недоступен: " + t);
                toast("Экран разрешения недоступен — установи APK вручную");
            }
            return;
        }
        updBusy = true; render();
        toast("Скачиваю " + found.tag + "…");
        Updater.download(this, found.apkUrl, ui, (ok, err) -> {
            updBusy = false;
            render();
            toast(ok ? "Скачано — подтверди установку" : (err != null ? err : "Не удалось установить"));
        });
    }

    /** Переключатель автоматического отчёта. Выключение сразу чистит очередь отправки. */
    private void consentSwitch() {
        Switch sw = new Switch(this);
        sw.setText("  Отправлять отчёты о подключении и ошибках");
        sw.setTextColor(TXT);
        sw.setTextSize(15);
        sw.setChecked(store.consentAllowed());
        sw.setBackgroundColor(ROW_BG);
        sw.setPadding(dp(16), dp(14), dp(16), dp(14));
        int[][] st = {{ android.R.attr.state_checked }, {}};
        sw.setThumbTintList(new android.content.res.ColorStateList(st, new int[]{ ACCENT, 0xFFCACACF }));
        sw.setTrackTintList(new android.content.res.ColorStateList(st, new int[]{ 0x882E9BFF, 0x33FFFFFF }));
        sw.setOnCheckedChangeListener((btn, checked) -> {
            store.setConsent(checked ? Store.CONSENT_ALLOW : Store.CONSENT_DENY);
            toast(checked ? "Отчёты включены" : "Отчёты выключены, очередь очищена");
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        body.addView(sw, lp);
    }

    // ---- журнал ----

    private void showLog() {
        String log = Logger.tail(LOG_VIEW_CHARS);
        scrollableText("ЖУРНАЛ", log.isEmpty() ? "(пусто)" : log, () -> copy("журнал", log));
    }

    // ---- отправка диагностики ----

    /** Показать ровно те байты, что уйдут, и только потом отправлять. */
    private void previewAndSend() {
        final String payload = buildBundle().toString();
        body.removeAllViews();
        Ui.caption(this, body, "ЭТО БУДЕТ ОТПРАВЛЕНО");
        Ui.note(this, body, "Отправляется однократно, без повторов. Если отправка не пройдёт — "
                + "скопируй текст и пришли разработчику любым способом.");
        addMono(payload);
        Ui.action(this, body, "Отправить", GREEN, v -> doSend(payload));
        Ui.action(this, body, "Скопировать в буфер", ACCENT, v -> copy("диагностика", payload));
        Ui.action(this, body, "Отмена", TXT_SUB, v -> render());
    }

    private void doSend(String payload) {
        toast("Отправляю…");
        new Thread(() -> {
            boolean ok = false;
            try {
                // Модуль телеметрии опционален (грузится рефлексией) — в сборке без него
                // кнопка честно сообщает, что отправлять некуда.
                TelemetrySink sink = (TelemetrySink) Class.forName("com.djiquick.Telemetry")
                        .getDeclaredConstructor(Context.class).newInstance(this);
                ok = sink.sendDiagnostics(new JSONObject(payload));
            } catch (Throwable t) {
                Logger.w("[diag] отправка: " + t);
            }
            final boolean sent = ok;
            ui.post(() -> {
                if (sent) { toast("Отправлено, спасибо"); render(); }
                else toast("Не отправилось — нажми «Скопировать в буфер» и пришли текст");
            });
        }, "diagsend").start();
    }

    /** Пакет диагностики: версия, устройство, борт, трасса детекта, хвост журнала. */
    private JSONObject buildBundle() {
        JSONObject o = new JSONObject();
        try {
            o.put("kind", "diag");
            o.put("ts", System.currentTimeMillis());
            o.put("nonce", Long.toHexString(System.nanoTime()));

            JSONObject app = new JSONObject();
            app.put("name", BuildStamp.NAME);
            app.put("code", BuildStamp.CODE);
            app.put("git", BuildStamp.GIT);
            app.put("built", BuildStamp.BUILT_MS);
            app.put("consent", store.consent());
            o.put("app", app);

            JSONObject dev = new JSONObject();
            dev.put("model", Build.MODEL);
            dev.put("device", Build.DEVICE);
            dev.put("product", Build.PRODUCT);
            dev.put("manufacturer", Build.MANUFACTURER);
            dev.put("fingerprint", Build.FINGERPRINT);
            dev.put("sdk", Build.VERSION.SDK_INT);
            o.put("device", dev);

            o.put("board", parse(boardJson));
            o.put("detect", trace);

            String log = Logger.tail(LOG_TAIL_CHARS);
            o.put("log", log);
            o.put("log_truncated", log.length() >= LOG_TAIL_CHARS);
        } catch (Exception e) {
            Logger.w("[diag] сборка пакета: " + e);
        }
        return o;
    }

    // ---- мелочи ----

    /** Экран «заголовок + моноширинный текст + действие», с кнопкой возврата. */
    private void scrollableText(String caption, String text, Runnable onCopy) {
        body.removeAllViews();
        Ui.caption(this, body, caption);
        addMono(text);
        Ui.action(this, body, "Скопировать в буфер", ACCENT, v -> onCopy.run());
        Ui.action(this, body, "Назад", TXT_SUB, v -> render());
    }

    private void addMono(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TXT_SUB);
        t.setTextSize(10);
        t.setTypeface(Typeface.MONOSPACE);
        t.setBackgroundColor(ROW_BG);
        t.setPadding(dp(10), dp(10), dp(10), dp(10));
        t.setGravity(Gravity.START);
        t.setHorizontallyScrolling(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        body.addView(t, lp);
    }

    private void copy(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) { toast("Буфер обмена недоступен"); return; }
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            toast("Скопировано");
        } catch (Throwable t) { toast("Не удалось скопировать"); }
    }

    private static JSONObject parse(String js) {
        if (js == null || js.isEmpty()) return new JSONObject();
        try { return new JSONObject(js); } catch (Exception e) { return new JSONObject(); }
    }

    private static String str(String s) { return s == null ? "" : s; }
    private static String or(String s, String def) { return s == null || s.isEmpty() ? def : s; }
    private int dp(int v) { return Ui.dp(this, v); }
    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        // из подэкрана (журнал/предпросмотр) возвращаемся на главную страницу «О программе»
        if (body != null && body.getChildCount() > 0 && !isMainRendered()) { render(); return; }
        super.onBackPressed();
    }

    private boolean isMainRendered() {
        View first = body.getChildAt(0);
        return first instanceof TextView && "ВЕРСИЯ".contentEquals(((TextView) first).getText());
    }
}
