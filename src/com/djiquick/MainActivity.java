package com.djiquick;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
 * Мини-приложение «GPS / LED / Режим» для DJI RC 2 — быстрые тумблеры поверх DJI Fly.
 *
 * Мастер первого запуска:
 *   0) согласие на отправку диагностики (спрашивается один раз, решение можно менять);
 *   1) остановить DJI Fly — приложение открывает её экран «О приложении»;
 *   2) включить дрон и подключиться;
 *   3) Detector находит индексы параметров и проверяет каждый живьём по имени;
 *   4) поднимается overlay-меню; запись слепая на 40008 и сосуществует с Fly.
 * При повторном запуске с тем же дроном мастер пропускается.
 *
 * Детект читает на 40007 → ТОЛЬКО с остановленной DJI Fly. Оверлей не читает вовсе.
 */
public final class MainActivity extends Activity implements Detector.Listener {

    private static final int STEP_STOP = 0, STEP_CONNECT = 1, STEP_DETECT = 2,
                             STEP_RESULT = 3, STEP_HUB = 4, STEP_CONSENT = 5;
    private int step = STEP_STOP;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Duml duml = new Duml();
    private final QuickParam[] params = QuickParam.all();

    private Store store;
    private ModelDb db;
    private Detector detector;

    private boolean wentToSettings = false;          // ушли на «О приложении» DJI Fly
    private volatile String detectPhase = "";
    private volatile String detectError = null;
    private String crcKey = null;                    // отпечаток прошивки текущего борта
    private long boardCrc = 0, boardCount = 0;
    private String modelLabel = null;                // подпись модели для UI и телеметрии
    private TelemetrySink telemetry;                 // создаётся ЛЕНИВО и только при согласии

    private LinearLayout body;                        // контейнер, перерисовываемый по step

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        immersive();
        Logger.attachFile(this);
        store = new Store(this);
        CrashReporter.install(this);   // падение сохранится в файл; отправка — ниже и только при согласии
        CrashReporter.flush(this);     // без согласия сохранённый отчёт удаляется неотправленным

        // Согласие спрашиваем ДО быстрого пути: иначе обновившийся пользователь с уже
        // найденным бортом никогда бы не увидел этот экран.
        if (store.consent() == Store.CONSENT_UNKNOWN) {
            step = STEP_CONSENT;
            buildShell();
            render();
            return;
        }

        if (restoreSaved()) {
            step = STEP_HUB;
            buildShell();
            startOverlay(false);                 // тихая попытка; тумблеры работают и без оверлея
            render();
            return;
        }
        step = STEP_STOP;
        buildShell();
        render();
    }

    /** Восстановить индексы для последней прошивки. true — есть хотя бы один сохранённый. */
    private boolean restoreSaved() {
        String lastKey = store.lastCrcKey();
        if (lastKey == null) return false;
        JSONObject v = store.verified(lastKey);
        boolean any = false;
        for (QuickParam q : params) {
            int idx = v.optInt(q.key, -1);
            if (idx >= 0) { q.idx = idx; q.source = "сохранено"; any = true; }
        }
        if (any) crcKey = lastKey;
        return any;
    }

    /**
     * Телеметрия: модуль опционален (грузится рефлексией) И требует согласия. Пока согласия нет,
     * класс вообще не создаётся — значит ничего не собирается, не ставится в очередь и не шлётся.
     */
    private TelemetrySink telemetry() {
        if (!store.consentAllowed()) return null;
        if (telemetry == null) {
            try {
                telemetry = (TelemetrySink) Class.forName("com.djiquick.Telemetry")
                        .getDeclaredConstructor(android.content.Context.class).newInstance(this);
            } catch (Throwable t) {
                Logger.i("[tlm] модуль телеметрии отсутствует — пропуск");
                return null;
            }
        }
        return telemetry;
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(0xFF000000);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("GPS / LED");
        title.setTextColor(TXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(16), dp(12), dp(16), dp(2));
        titleCol.addView(title);
        TextView ver = new TextView(this);
        ver.setText(About.versionLine() + (About.isStale() ? "  ·  возможно, есть новее" : ""));
        ver.setTextColor(About.isStale() ? AMBER : TXT_SUB);
        ver.setTextSize(11);
        ver.setPadding(dp(16), 0, dp(16), dp(8));
        titleCol.addView(ver);
        View underline = new View(this);
        underline.setBackgroundColor(ACCENT);
        titleCol.addView(underline, new LinearLayout.LayoutParams(dp(120), dp(3)));
        header.addView(titleCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Вход в диагностику доступен с ЛЮБОГО шага — в том числе с провалившегося результата,
        // когда он и нужен. Подписан словами и на видимой плашке: одинокий прозрачный «?» в углу
        // ландшафтного экрана пользователи просто не находили.
        Button help = new Button(this);
        help.setText("?  О программе");
        help.setAllCaps(false);
        help.setTextColor(ACCENT);
        help.setTextSize(15);
        help.setBackgroundColor(ROW_BG);
        help.setPadding(dp(12), 0, dp(12), 0);
        help.setOnClickListener(v -> openAbout());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        hp.rightMargin = dp(12);
        header.addView(help, hp);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void openAbout() {
        try {
            Intent i = new Intent(this, AboutActivity.class);
            i.putExtra(AboutActivity.EX_BOARD, boardSummary().toString());
            i.putExtra(AboutActivity.EX_TRACE, detector != null ? detector.trace() : "");
            startActivity(i);
        } catch (Throwable t) { Logger.w("[ui] about: " + t); }
    }

    /** Сводка о борте для страницы «О программе» и пакета диагностики. */
    private JSONObject boardSummary() {
        JSONObject o = new JSONObject();
        try {
            o.put("crc", boardCrc != 0 ? Long.toHexString(boardCrc) : "");
            o.put("count", boardCount);
            o.put("serial", duml.acSerial());
            o.put("ac_model", duml.acModel());
            o.put("rc_model", duml.rcModel());
            o.put("picked", detector != null && detector.model() != null ? detector.model().code : "");
            o.put("label", modelLabel != null ? modelLabel : "");
            o.put("link", duml.linkStats());   // эхо seq и маршрутизация ответов — измерение, см. Duml
            JSONObject ids = new JSONObject();
            for (QuickParam q : params) {
                JSONObject one = new JSONObject();
                one.put("idx", q.idx);
                one.put("src", q.source != null ? q.source : "");
                one.put("name", q.seenName != null ? q.seenName : "");
                ids.put(q.key, one);
            }
            o.put("ids", ids);
        } catch (Exception ignore) {}
        return o;
    }

    // ================= РЕНДЕР ПО ШАГАМ =================

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        switch (step) {
            case STEP_CONSENT: renderConsent(); break;
            case STEP_STOP:    renderStop();    break;
            case STEP_CONNECT: renderConnect(); break;
            case STEP_DETECT:  renderDetect();  break;
            case STEP_RESULT:  renderResult();  break;
            case STEP_HUB:     renderHub();     break;
        }
    }

    /** Шаг 0 — согласие на отправку диагностики. Спрашивается один раз, меняется в «О программе». */
    private void renderConsent() {
        caption("ДИАГНОСТИКА");
        note("Приложение может отправлять разработчику короткий отчёт о подключении — он нужен, "
                + "чтобы новые прошивки попадали во встроенную таблицу и у следующих пользователей "
                + "параметры находились сразу, а не перебором. Если приложение упадёт, так же "
                + "отправится отчёт об ошибке.");
        note("Что отправляется: отпечаток прошивки (контрольная сумма и число параметров), "
                + "серийный номер и кодовое имя дрона, найденные номера параметров, версия приложения, "
                + "а при падении — текст ошибки и журнал работы.");
        note("Что НЕ отправляется: геолокация, фото и видео, логи полёта, данные аккаунта DJI.");
        note("Решение можно изменить в любой момент: кнопка «?» вверху → «О программе».");
        action("Разрешить отправку", GREEN, v -> setConsent(Store.CONSENT_ALLOW));
        action("Не отправлять", TXT_SUB, v -> setConsent(Store.CONSENT_DENY));
    }

    private void setConsent(int state) {
        store.setConsent(state);
        toast(state == Store.CONSENT_ALLOW ? "Спасибо — отчёты включены" : "Отчёты выключены");
        step = restoreSaved() ? STEP_HUB : STEP_STOP;
        render();
        if (step == STEP_HUB) startOverlay(false);
    }

    /** Шаг 1 — остановить DJI Fly. */
    private void renderStop() {
        caption("ШАГ 1 · ОСТАНОВИТЬ DJI FLY");
        note("Приложению нужен канал к дрону, который занимает DJI Fly — останови её один раз "
                + "для настройки. Кнопка ниже откроет экран «О приложении» DJI Fly: там нажми "
                + "«Остановить» и вернись сюда.");

        String pkg = DjiFly.installedPackage(this);
        if (pkg != null) {
            action("Открыть «О приложении» DJI Fly", ACCENT, v -> {
                wentToSettings = true;
                DjiFly.openAppInfo(this, pkg);
            });
        } else {
            note("DJI Fly не установлена — шаг можно пропустить.");
        }
        action("DJI Fly остановлена — далее", GREEN, v -> { step = STEP_CONNECT; render(); });
        action("О программе · версия, обновление, диагностика", TXT_SUB, v -> openAbout());
    }

    /** Шаг 2 — включить дрон и подключиться. */
    private void renderConnect() {
        caption("ШАГ 2 · ВКЛЮЧИ ДРОН");
        note("Включи дрон и дождись связи с пультом (индикатор в DJI Fly / писк). "
                + "Дрон должен стоять на земле в покое.");
        action("Подключиться", ACCENT, v -> startDetect());
        action("Назад", TXT_SUB, v -> { step = STEP_STOP; render(); });
    }

    /** Шаг 3 — идёт детект (текст обновляется из фонового потока). */
    private void renderDetect() {
        caption("ШАГ 3 · ОПРЕДЕЛЕНИЕ ПАРАМЕТРОВ");
        TextView t = new TextView(this);
        t.setTag("detect");
        t.setText(detectPhase.isEmpty() ? "Подключение…" : detectPhase);
        t.setTextColor(TXT);
        t.setTextSize(15);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        body.addView(t);
        note("Идёт чтение имён параметров с борта. Не закрывай приложение.");
        action("Отмена", RED, v -> { if (detector != null) detector.cancel(); });
    }

    private void updateDetectText() {
        View t = body.findViewWithTag("detect");
        if (t instanceof TextView) ((TextView) t).setText(detectPhase);
    }

    /** Шаг 4 — результат детекта + ручной ввод + сохранение. */
    private void renderResult() {
        caption("РЕЗУЛЬТАТ");
        if (detectError != null) {
            statusLine(detectError, RED);
            action("Повторить", ACCENT, v -> { step = STEP_CONNECT; render(); });
            action("Не получилось — открыть диагностику", TXT_SUB, v -> openAbout());
            return;
        }
        for (QuickParam q : params) {
            if (q.idx >= 0) statusLine("✓ " + q.title + " · " + q.key + " → idx " + q.idx
                    + (q.source != null ? " (" + q.source + ")" : ""), GREEN);
            else            statusLine("✗ " + q.title + " · " + q.key + " не найден", RED);
        }
        if (modelLabel != null) note("Модель: " + modelLabel + "  ·  прошивка " + crcKey
                + (detector != null && detector.modelWeak() ? "  (определена нестрого)" : ""));
        else if (crcKey != null) note("Прошивка: " + crcKey + " (модель не определена)");

        caption("РУЧНОЙ ВВОД ID (с проверкой имени)");
        note("Если авто-детект ошибся или не нашёл — впиши индекс и нажми «Проверить»: "
                + "запишем только при совпадении имени с бортом.");
        for (QuickParam q : params) manualRow(q);

        boolean any = false;
        for (QuickParam q : params) if (q.idx >= 0) any = true;
        if (any) action("Сохранить и открыть меню", GREEN, v -> saveAndHub());
        action("Повторить определение", ACCENT, v -> { step = STEP_CONNECT; render(); });
        action(any ? "О программе · версия, обновление, диагностика"
                   : "Не получилось — открыть диагностику", TXT_SUB, v -> openAbout());
    }

    /** Строка ручного ввода индекса + кнопка проверки по имени. */
    private void manualRow(QuickParam q) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(4), dp(12), dp(4));

        TextView lbl = new TextView(this);
        lbl.setText(q.title);
        lbl.setTextColor(TXT);
        lbl.setTextSize(14);
        row.addView(lbl, new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setHint("idx");
        in.setTextColor(TXT);
        in.setHintTextColor(TXT_SUB);
        in.setTextSize(14);
        in.setBackgroundColor(ROW_BG);
        in.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (q.idx >= 0) in.setText(String.valueOf(q.idx));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        il.rightMargin = dp(6);
        row.addView(in, il);

        row.addView(Ui.flatBtn(this, "Проверить", ACCENT, v -> manualVerify(q, in.getText().toString())),
                new LinearLayout.LayoutParams(dp(112), dp(48)));

        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(4);
        body.addView(row, rl);
    }

    /** Проверить введённый индекс: короткая сессия get_info, коммит только при совпадении имени. */
    private void manualVerify(QuickParam q, String text) {
        final int idx;
        try { idx = Integer.parseInt(text.trim()); } catch (Exception e) { toast("Введи число"); return; }
        if (idx < 0 || idx > 65535) { toast("Некорректный idx"); return; }
        if (detector != null && detector.isRunning()) { toast("Идёт определение — подожди"); return; }
        toast("Проверяю idx " + idx + "…");
        new Thread(() -> {
            String name = null;
            try {
                duml.start();
                for (int a = 0; a < 12 && !duml.isUp(); a++) sleep(200);   // дождаться reader 40007
                for (int a = 0; a < 3 && name == null; a++) {
                    byte[] info = duml.getInfoRaw(0, idx, 1100);
                    if (info != null) { name = Duml.nameFromInfo(info); break; }
                    sleep(150);
                }
            } finally { duml.stop(); }
            final String nm = name;
            ui.post(() -> {
                if (nm == null) { toast("idx " + idx + ": нет ответа (дрон включён? DJI Fly остановлена?)"); return; }
                if (q.matches(nm)) {
                    q.idx = idx; q.seenName = nm; q.source = "вручную";
                    if (boardCrc != 0) crcKey = Long.toHexString(boardCrc) + "_" + boardCount;
                    toast("✓ idx " + idx + " = " + nm);
                    render();
                } else {
                    toast("✗ idx " + idx + " = '" + (nm.isEmpty() ? "(без имени)" : nm) + "' — это не " + q.key);
                }
            });
        }, "manualverify").start();
    }

    /** Хаб — быстрые тумблеры прямо в приложении (+ опциональный overlay). */
    private void renderHub() {
        caption("БЫСТРЫЕ ТУМБЛЕРЫ");
        boolean any = false;
        for (QuickParam q : params) if (q.idx >= 0) { toggleRow(q); any = true; }
        if (!any) note("Ни один параметр не сохранён — пройди настройку заново.");
        else note("Тумблеры пишут напрямую и работают поверх запущенной DJI Fly, "
                + "разрешения не требуют.");
        boolean canOverlay = Settings.canDrawOverlays(this);
        statusLine("Overlay-разрешение: " + (canOverlay ? "есть" : "нет — тумблеры выше всё равно работают"),
                canOverlay ? GREEN : TXT_SUB);
        action("Показать плавающее меню (overlay)", ACCENT, v -> { if (startOverlay(true)) toast("Overlay поднят"); });
        action("Перенастроить (мастер заново)", TXT_SUB, v -> { stopOverlay(); step = STEP_STOP; render(); });
        action("О программе · версия, обновление, диагностика", TXT_SUB, v -> openAbout());
    }

    /** Пара кнопок для одного параметра (слепая запись на 40008, без reader и без overlay). */
    private void toggleRow(QuickParam q) {
        caption(q.title.toUpperCase() + " · " + q.key + " (idx " + q.idx + ")");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        Button on  = Ui.flatBtn(this, q.onLabel,  GREEN, v -> writeToggle(q, true));
        Button off = Ui.flatBtn(this, q.offLabel, RED,   v -> writeToggle(q, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1f); lp.rightMargin = dp(4);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(58), 1f); rp.leftMargin  = dp(4);
        row.addView(on, lp);
        row.addView(off, rp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(2);
        body.addView(row, rowLp);
    }

    /** Слепая запись значения параметра на 40008 в фоне (сосуществует с DJI Fly). */
    private void writeToggle(QuickParam q, boolean on) {
        final long val = on ? q.onVal : q.offVal;
        final String label = on ? q.onLabel : q.offLabel;
        new Thread(() -> {
            boolean ok = duml.writeOnceCoexist(0, q.idx, q.type, val);
            ui.post(() -> toast((ok ? "→ " : "✗ ") + q.title + " · " + label));
        }, "toggle").start();
    }

    // ================= ДЕТЕКТ =================

    private void startDetect() {
        if (detector != null && detector.isRunning()) return;
        if (db == null) db = ModelDb.load(this);
        detectError = null; detectPhase = "Подключение…";
        detector = new Detector(duml, db, params, this);
        step = STEP_DETECT; render();
        detector.start();
    }

    @Override public void phase(String text) {
        detectPhase = text;
        ui.post(this::updateDetectText);
    }

    @Override public void done(String errOrNull, boolean hardFail) {
        boardCrc = detector.crc();
        boardCount = detector.count();
        crcKey = detector.crcKey();
        modelLabel = detector.model() != null ? detector.model().label : null;
        detectError = hardFail
                ? (errOrNull != null ? errOrNull : "Параметры не найдены на этом борту.")
                : null;
        reportTelemetry();
        ui.post(() -> { step = STEP_RESULT; render(); });
    }

    /** Отчёт о подключении — только при согласии, один раз на (serial, crc, count). */
    private void reportTelemetry() {
        TelemetrySink t = telemetry();
        if (t == null || boardCrc == 0) return;
        try {
            JSONObject ids = new JSONObject();
            for (QuickParam q : params) if (q.idx >= 0) ids.put(q.key, q.idx);
            String code = detector != null && detector.model() != null ? detector.model().code : "";
            t.reportConnection(Long.toHexString(boardCrc), boardCount, duml.acSerial(),
                    duml.acModel(), code, ids, BuildStamp.NAME);
        } catch (Throwable e) { Logger.w("[tlm] report: " + e); }
    }

    // ================= СОХРАНЕНИЕ / OVERLAY =================

    private void saveAndHub() {
        store.saveVerified(crcKey, params);
        step = STEP_HUB;
        render();
        startOverlay(false);
    }

    /** Поднять overlay-сервис с найденными индексами. false → нет разрешения или нечего показывать. */
    private boolean startOverlay(boolean showHelpIfDenied) {
        if (!Settings.canDrawOverlays(this)) { if (showHelpIfDenied) showOverlayPermHelp(); return false; }
        java.util.ArrayList<QuickParam> use = new java.util.ArrayList<>();
        for (QuickParam q : params) if (q.idx >= 0) use.add(q);
        if (use.isEmpty()) { toast("Нет ни одного параметра для меню"); return false; }
        int n = use.size();
        String[] types = new String[n], onLabels = new String[n], offLabels = new String[n];
        int[] indices = new int[n];
        long[] onVals = new long[n], offVals = new long[n];
        for (int i = 0; i < n; i++) {
            QuickParam q = use.get(i);
            types[i] = q.type; indices[i] = q.idx;
            onVals[i] = q.onVal; offVals[i] = q.offVal;
            onLabels[i] = q.btnOn; offLabels[i] = q.btnOff;   // короткие подписи: в оверлее мало места
        }
        Intent i = new Intent(this, OverlayService.class);
        i.putExtra("indices", indices);
        i.putExtra("onVals", onVals);
        i.putExtra("offVals", offVals);
        i.putExtra("types", types);
        i.putExtra("onLabels", onLabels);
        i.putExtra("offLabels", offLabels);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        return true;
    }

    private void stopOverlay() {
        try { stopService(new Intent(this, OverlayService.class)); } catch (Throwable ignore) {}
    }

    /** Оверлей опционален. Разрешения нет → короткий тост + стандартный системный экран. */
    private void showOverlayPermHelp() {
        toast("Оверлей требует «Поверх приложений». Тумблеры в приложении работают и без него.");
        try {
            Intent it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable ignore) { /* на пульте системный экран может быть недоступен — молча */ }
    }

    // ================= LIFECYCLE =================

    @Override
    protected void onResume() {
        super.onResume();
        TelemetrySink t = telemetry();
        if (t != null) t.flush();               // дослать отложенные отчёты (throttle внутри)
        // вернулись с экрана «О приложении» DJI Fly → продолжаем мастер
        if (wentToSettings && step == STEP_STOP) {
            wentToSettings = false;
            step = STEP_CONNECT;
            render();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (detector != null) detector.cancel();   // не держим reader 40007 в фоне
        duml.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        duml.stop();
    }

    // ================= UI-ХЕЛПЕРЫ =================

    private void caption(String t) { Ui.caption(this, body, t); }
    private void note(String t) { Ui.note(this, body, t); }
    private void statusLine(String t, int color) { Ui.statusLine(this, body, t, color); }
    private void action(String t, int color, View.OnClickListener l) { Ui.action(this, body, t, color, l); }

    private void immersive() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Ui.dp(this, v); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }
}
