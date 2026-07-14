package com.djiquick;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.EditText;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Мини-приложение «GPS/LED» для DJI RC 2 — только два быстрых тумблера поверх DJI Fly.
 *
 * Первый запуск ведёт пошаговый мастер:
 *   1) остановить DJI Fly (скрин, куда нажать) → системный экран «О приложении» → «Остановить»;
 *   2) включить дрон и подключиться;
 *   3) приложение само находит индексы двух параметров (gps_enable / forearm_led_ctrl)
 *      по имени через get_info (0xE1), проверяет и сохраняет их для этой прошивки;
 *   4) поднимается overlay-меню (GPS on/off, LED on/off), запись слепая на 40008 (coexist с Fly).
 * При следующем запуске с тем же дроном мастер пропускается — сразу overlay.
 *
 * Детект читает на 40007 → делать ТОЛЬКО с остановленной DJI Fly. Overlay пишет на 40008 без
 * reader'а → сосуществует с работающей Fly, поэтому повторные запуски Fly не трогают.
 */
public final class MainActivity extends Activity {

    // ---- палитра (стиль DJI Fly, тёмная тема) ----
    private static final int BG      = 0xFF0A0A0B;
    private static final int ROW_BG  = 0xFF161619;
    private static final int TXT     = 0xFFFFFFFF;
    private static final int TXT_SUB = 0xFF8E8E93;
    private static final int ACCENT  = 0xFF2E9BFF;
    private static final int DIVIDER = 0xFF2A2A2E;
    private static final int GREEN   = 0xFF34C759;
    private static final int RED     = 0xFFFF453A;

    private static final String PREFS = "djiquick";
    private static final int PACE_MS = 150;          // пауза между DUML-запросами (иначе роутер роняет ответы)
    private static final int SCAN_MAX = 2100;        // потолок фолбэк-скана по индексам
    private static final int R_STRONG = 48;          // радиус спирали вокруг индекса выбранной модели
    private static final int R_WEAK   = 16;          // радиус спирали вокруг прочих известных кандидатов

    // ---- два целевых параметра ----
    /** Быстрый параметр: имя(а) для сверки, тип, значения вкл/выкл. Индексы берём из таблиц/детекта. */
    private static final class QP {
        final String key, title, type, onLabel, offLabel;
        final String[] aliases;
        final long onVal, offVal;
        int idx = -1;            // найденный индекс (−1 = не найден)
        String seenName;         // имя, реально прочитанное с борта
        String source;           // «таблица» / «кандидат» / «рядом» / «скан» / «вручную»
        QP(String key, String title, String[] aliases, String type,
           long onVal, String onLabel, long offVal, String offLabel) {
            this.key = key; this.title = title; this.aliases = aliases; this.type = type;
            this.onVal = onVal; this.onLabel = onLabel; this.offVal = offVal; this.offLabel = offLabel;
        }
        /** Точное (case-insensitive) совпадение имени борта с любым алиасом, по сегментам '|'. */
        boolean matches(String boardName) {
            if (boardName == null || boardName.isEmpty()) return false;
            for (String seg : boardName.split("\\|")) {
                String s = seg.trim();
                for (String a : aliases) if (s.equalsIgnoreCase(a)) return true;
            }
            return false;
        }
    }

    private final QP GPS = new QP("gps_enable", "GPS",
            new String[]{ "gps_enable", "g_config.gps_cfg.gps_enable" }, "U8",
            1, "вкл", 0, "выкл");

    private final QP LED = new QP("forearm_led_ctrl", "LED",
            new String[]{ "forearm_led_ctrl", "g_config.misc_cfg.forearm_lamp_ctrl" }, "U8",
            239, "вкл", 0, "выкл");

    private final QP[] PARAMS = { GPS, LED };

    /**
     * Порядок индексов для поиска «рядом с якорями»: сильный якорь (индекс выбранной модели) —
     * спираль ±rStrong, затем каждый кандидат — спираль ±rWeak. Уже пробованные (exclude) и выход
     * за пределы таблицы пропускаются. Сам якорь не добавляем — его точный индекс уже проверен.
     */
    private static java.util.List<Integer> radialOrder(int strong, int[] weak, int rStrong, int rWeak,
                                                       java.util.Set<Integer> exclude, int cap) {
        java.util.LinkedHashSet<Integer> order = new java.util.LinkedHashSet<>();
        addSpiral(order, strong, rStrong, exclude, cap);
        for (int a : weak) addSpiral(order, a, rWeak, exclude, cap);
        return new java.util.ArrayList<>(order);
    }
    private static void addSpiral(java.util.Set<Integer> order, int anchor, int R,
                                  java.util.Set<Integer> exclude, int cap) {
        if (anchor < 0) return;
        for (int r = 1; r <= R; r++) {
            int up = anchor + r, dn = anchor - r;
            if (up >= 0 && up < cap && !exclude.contains(up)) order.add(up);
            if (dn >= 0 && dn < cap && !exclude.contains(dn)) order.add(dn);
        }
    }

    // ---- состояния мастера ----
    private static final int STEP_STOP = 0, STEP_CONNECT = 1, STEP_DETECT = 2, STEP_RESULT = 3, STEP_HUB = 4;
    private int step = STEP_STOP;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Duml duml = new Duml();
    private float d;

    private boolean wentToSettings = false;          // ушли на «О приложении» DJI Fly
    private volatile boolean detecting = false;
    private volatile boolean cancelDetect = false;
    private volatile String detectPhase = "";
    private volatile String detectError = null;
    private String crcKey = null;                    // идентификатор прошивки текущего борта
    private long boardCrc = 0, boardCount = 0;       // сырой отпечаток прошивки (для телеметрии)
    private boolean overlayOn = false;
    private TelemetrySink telemetry;                 // одноразовый отчёт о подключении, грузится рефлексией
    private java.util.List<ParamTable.Table> tables; // таблицы моделей (name→index), грузятся в детекте
    private String detectedModel;                    // имя выбранной модели (для телеметрии/UI)

    private LinearLayout body;                        // контейнер, перерисовываемый по step

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        immersive();

        // необязательный модуль телеметрии (приватная сборка); нет класса/ассета → тихий no-op
        try {
            telemetry = (TelemetrySink) Class.forName("com.djiquick.Telemetry")
                    .getDeclaredConstructor(android.content.Context.class).newInstance(this);
        } catch (Throwable t) { telemetry = null; Logger.i("[tlm] модуль телеметрии отсутствует — пропуск"); }

        // роутинг: если под последней прошивкой уже сохранены оба индекса — сразу overlay
        String lastKey = prefs().getString("lastCrcKey", null);
        if (lastKey != null) {
            JSONObject v = loadVerified(lastKey);
            int g = v.optInt(GPS.key, -1), l = v.optInt(LED.key, -1);
            if (g >= 0 && l >= 0) {
                GPS.idx = g; LED.idx = l; crcKey = lastKey;
                step = STEP_HUB;
                buildShell();
                startOverlay(false);                 // тихая попытка; тумблеры в приложении работают и без overlay
                render();
                return;
            }
        }
        step = STEP_STOP;
        buildShell();
        render();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(0xFF000000);
        TextView title = new TextView(this);
        title.setText("GPS / LED");
        title.setTextColor(TXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(16), dp(12), dp(16), dp(10));
        header.addView(title);
        View underline = new View(this);
        underline.setBackgroundColor(ACCENT);
        header.addView(underline, new LinearLayout.LayoutParams(dp(120), dp(3)));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    // ================= РЕНДЕР ПО ШАГАМ =================

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        switch (step) {
            case STEP_STOP:    renderStop();    break;
            case STEP_CONNECT: renderConnect(); break;
            case STEP_DETECT:  renderDetect();  break;
            case STEP_RESULT:  renderResult();  break;
            case STEP_HUB:     renderHub();     break;
        }
    }

    /** Шаг 1 — остановить DJI Fly (со скрином, куда нажать). */
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
        action("Отмена", RED, v -> { cancelDetect = true; });
    }

    private void updateDetectText() {
        View t = body.findViewWithTag("detect");
        if (t instanceof TextView) ((TextView) t).setText(detectPhase);
    }

    /** Шаг 4 — результат детекта + сохранение. */
    private void renderResult() {
        caption("РЕЗУЛЬТАТ");
        if (detectError != null) {
            statusLine(detectError, RED);
            action("Повторить", ACCENT, v -> { step = STEP_CONNECT; render(); });
            return;
        }
        for (QP q : PARAMS) {
            if (q.idx >= 0) statusLine("✓ " + q.title + " · " + q.key + " → idx " + q.idx
                    + (q.source != null ? " (" + q.source + ")" : ""), GREEN);
            else            statusLine("✗ " + q.title + " · " + q.key + " не найден", RED);
        }
        if (detectedModel != null) note("Модель: " + detectedModel + "  ·  прошивка " + crcKey);
        else if (crcKey != null) note("Прошивка: " + crcKey + " (модель не определена)");

        caption("РУЧНОЙ ВВОД ID (с проверкой имени)");
        note("Если авто-детект ошибся или не нашёл — впиши индекс и нажми «Проверить»: "
                + "запишем только при совпадении имени с бортом.");
        for (QP q : PARAMS) manualRow(q);

        boolean any = GPS.idx >= 0 || LED.idx >= 0;
        boolean both = GPS.idx >= 0 && LED.idx >= 0;
        if (both) {
            action("Сохранить и открыть меню", GREEN, v -> saveAndHub());
        } else if (any) {
            note("Найден только один параметр — меню откроется с ним.");
            action("Сохранить и открыть меню", GREEN, v -> saveAndHub());
        }
        action("Повторить определение", ACCENT, v -> { step = STEP_CONNECT; render(); });
    }

    /** Строка ручного ввода индекса для параметра + кнопка проверки по имени. */
    private void manualRow(QP q) {
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

        row.addView(flatBtn("Проверить", ACCENT, v -> manualVerify(q, in.getText().toString())),
                new LinearLayout.LayoutParams(dp(112), dp(48)));

        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(4);
        body.addView(row, rl);
    }

    /** Проверить введённый индекс: короткая сессия get_info, коммит только при совпадении имени. */
    private void manualVerify(QP q, String text) {
        final int idx;
        try { idx = Integer.parseInt(text.trim()); } catch (Exception e) { toast("Введи число"); return; }
        if (idx < 0 || idx > 65535) { toast("Некорректный idx"); return; }
        if (detecting) { toast("Идёт определение — подожди"); return; }
        toast("Проверяю idx " + idx + "…");
        new Thread(() -> {
            String name = null;
            try {
                duml.start();
                for (int a = 0; a < 12 && !duml.isUp(); a++) sleep(200);   // дождаться reader 40007
                for (int a = 0; a < 3 && name == null; a++) {
                    byte[] info = duml.getInfoRaw(0, idx, 1100);
                    if (info != null) { name = Duml.nameFromInfo(info); break; }
                    sleep(PACE_MS);
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
        for (QP q : PARAMS) if (q.idx >= 0) { toggleRow(q); any = true; }
        if (!any) note("Ни один параметр не сохранён — пройди настройку заново.");
        else note("Тумблеры пишут напрямую (40008) и работают поверх запущенной DJI Fly, "
                + "разрешения не требуют.");
        boolean canOverlay = Settings.canDrawOverlays(this);
        statusLine("Overlay-разрешение: " + (canOverlay ? "есть" : "нет — тумблеры выше всё равно работают"),
                canOverlay ? GREEN : TXT_SUB);
        action("Показать плавающее меню (overlay)", ACCENT, v -> { if (startOverlay(true)) toast("Overlay поднят"); });
        action("Перенастроить (мастер заново)", TXT_SUB, v -> { stopOverlay(); step = STEP_STOP; render(); });
    }

    /** Пара кнопок «вкл / выкл» для одного параметра (запись на 40008, без reader и без overlay). */
    private void toggleRow(QP q) {
        caption(q.title.toUpperCase() + " · " + q.key + " (idx " + q.idx + ")");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        Button on  = flatBtn(q.onLabel,  GREEN, v -> writeToggle(q, true));
        Button off = flatBtn(q.offLabel, RED,   v -> writeToggle(q, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1f); lp.rightMargin = dp(4);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(58), 1f); rp.leftMargin  = dp(4);
        row.addView(on, lp);
        row.addView(off, rp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(2);
        body.addView(row, rowLp);
    }

    private Button flatBtn(String text, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(ROW_BG);
        b.setOnClickListener(l);
        return b;
    }

    /** Слепая запись значения параметра на 40008 в фоне (сосуществует с DJI Fly). */
    private void writeToggle(QP q, boolean on) {
        final long val = on ? q.onVal : q.offVal;
        final String label = on ? q.onLabel : q.offLabel;
        new Thread(() -> {
            boolean ok = duml.writeOnceCoexist(0, q.idx, q.type, val);
            ui.post(() -> toast((ok ? "→ " : "✗ ") + q.title + " · " + label));
        }, "toggle").start();
    }

    // ================= ДЕТЕКТ =================

    private void startDetect() {
        if (detecting) return;
        detecting = true; cancelDetect = false; detectError = null; detectPhase = "Подключение…";
        step = STEP_DETECT; render();
        new Thread(this::detectWorker, "detect").start();
    }

    private void detectWorker() {
        try {
            duml.start();
            // 1) идентичность прошивки через 0xE0 (crc+count)
            long[] ti = null;
            for (int a = 0; a < 8 && !cancelDetect; a++) {
                setPhase("Связь с бортом… (" + (a + 1) + "/8)");
                ti = duml.tableInfo(0, 1200);
                if (ti != null) break;
                sleep(400);
            }
            if (cancelDetect) { finishDetect("Отменено", true); return; }
            if (ti == null) { finishDetect("Нет связи с бортом. Проверь, что дрон включён и DJI Fly остановлена.", true); return; }
            boardCrc = ti[0]; boardCount = ti[1];
            crcKey = Long.toHexString(ti[0]) + "_" + ti[1];
            for (QP q : PARAMS) { q.idx = -1; q.seenName = null; q.source = null; }

            // 2) выбор таблицы модели: точный crc+count (различает wa150/wa151) → уникальный crc →
            //    КОНТЕНТНАЯ ПРОБА по нескольким именам (определяет модель даже при crc=0/неизвестном).
            if (tables == null) tables = ParamTable.loadAll(this);
            ParamTable.Table t = pickTable(ti[0], ti[1]);
            detectedModel = t != null ? t.model : null;
            if (t != null) Logger.i("[detect] модель: " + t.model
                    + " (crc=" + Long.toHexString(t.crc) + " count=" + t.count + ")");

            // 3) индекс по ИМЕНИ из таблицы модели → обязательная проверка живьём по имени
            for (QP q : PARAMS) {
                if (cancelDetect) { finishDetect("Отменено", true); return; }
                Integer ix = t != null ? t.indexOf(q.key) : null;
                if (ix == null) continue;
                setPhase("Проверка «" + q.title + "» по таблице" + (t != null ? " (" + t.model + ")" : "") + "…");
                String nm = probeName(ix);
                if (q.matches(nm)) { q.idx = ix; q.seenName = nm; q.source = "таблица"; }
            }

            // 4) кандидаты = объединение индексов этого имени по ВСЕМ таблицам (другая модель/прошивка)
            int cap = (int) Math.min(ti[1] > 0 ? ti[1] : SCAN_MAX, SCAN_MAX);
            java.util.HashSet<Integer> tried = new java.util.HashSet<>();
            for (QP q : PARAMS) {
                if (q.idx >= 0 || cancelDetect) continue;
                Integer strongI = t != null ? t.indexOf(q.key) : null;
                if (strongI != null) tried.add(strongI);          // из таблицы уже пробовали
                setPhase("Поиск «" + q.title + "» по кандидатам…");
                for (int cand : unionIndices(q.key)) {
                    if (cancelDetect) { finishDetect("Отменено", true); return; }
                    if (!tried.add(cand)) continue;
                    String nm = probeName(cand);
                    if (q.matches(nm)) { q.idx = cand; q.seenName = nm; q.source = "кандидат"; break; }
                }
            }

            // 5) фолбэк «рядом»: спираль вокруг якорей (индекс из таблицы — широкий радиус, прочие
            //    кандидаты — узкий). Анализ дампов: при апдейте прошивки индекс уезжает недалеко.
            for (QP q : PARAMS) {
                if (q.idx >= 0 || cancelDetect) continue;
                Integer strongI = t != null ? t.indexOf(q.key) : null;
                int strong = strongI != null ? strongI : -1;
                setPhase("Поиск «" + q.title + "» рядом с известными…");
                for (int idx : radialOrder(strong, unionIndices(q.key), R_STRONG, R_WEAK, tried, cap)) {
                    if (cancelDetect) break;
                    tried.add(idx);
                    String nm = probeName(idx);
                    if (q.matches(nm)) { q.idx = idx; q.seenName = nm; q.source = "рядом"; break; }
                }
            }

            // 6) крайний резерв — линейный добор по всей таблице для всё ещё ненайденных
            boolean needLinear = false;
            for (QP q : PARAMS) if (q.idx < 0) needLinear = true;
            if (needLinear && !cancelDetect) {
                for (int idx = 0; idx < cap && !cancelDetect; idx++) {
                    boolean done = true;
                    for (QP q : PARAMS) if (q.idx < 0) done = false;
                    if (done) break;
                    if (tried.contains(idx)) continue;
                    if ((idx & 15) == 0) setPhase("Полный перебор " + idx + "/" + cap + "…");
                    String nm = Duml.nameFromInfo(duml.getInfoRaw(0, idx, 700));
                    if (nm != null && !nm.isEmpty())
                        for (QP q : PARAMS) if (q.idx < 0 && q.matches(nm)) { q.idx = idx; q.seenName = nm; q.source = "скан"; }
                    sleep(PACE_MS);
                }
            }

            reportTelemetry(detectedModel);          // одноразовый отчёт per (serial,crc,count); офлайн → в очередь
            finishDetect(null, GPS.idx < 0 && LED.idx < 0);
        } catch (Throwable e) {
            Logger.e("detect error", e);
            finishDetect("Ошибка: " + e, true);
        } finally {
            duml.stop();
            detecting = false;
        }
    }

    /**
     * Имя параметра по индексу через get_info, с ретраями. get_info на busy-канале 40007 —
     * single-shot и нередко теряет ответ; без ретрая реальный кандидат (напр. LED idx 23)
     * ложно считается «не тем id». Пауза ПЕРЕД каждым запросом (вплотную роутер роняет ответ).
     * Возвращает имя (может быть пустым, если борт ответил без имени) или null, если ответа нет.
     */
    private String probeName(int idx) {
        for (int attempt = 0; attempt < 3 && !cancelDetect; attempt++) {
            sleep(PACE_MS);
            byte[] info = duml.getInfoRaw(0, idx, 1100);
            if (info != null) return Duml.nameFromInfo(info);
        }
        return null;
    }

    private void setPhase(String p) { detectPhase = p; ui.post(this::updateDetectText); }

    /** Завершение детекта: показать результат. `hardFail` → текст ошибки на экране результата. */
    private void finishDetect(String errOrCancel, boolean hardFail) {
        if (hardFail) detectError = errOrCancel != null ? errOrCancel : "Параметры не найдены на этом борту.";
        else detectError = null;
        ui.post(() -> { step = STEP_RESULT; render(); });
    }

    /**
     * Выбор таблицы модели: точный crc+count (различает wa150/wa151) → единственный crc →
     * контентная проба: читаем разбросанные имена ОДИН раз и выбираем таблицу с max совпадений (≥2).
     * Проба нужна для моделей с crc=0/неизвестным — определяет модель по содержимому таблицы.
     */
    private ParamTable.Table pickTable(long crc, long count) {
        if (tables == null || tables.isEmpty()) return null;
        ParamTable.Table crcUnique = null; int crcHits = 0;
        for (ParamTable.Table t : tables) if (t.crc != 0 && t.crc == crc) {
            crcHits++; crcUnique = t;
            if (t.count == count) return t;
        }
        if (crcHits == 1) return crcUnique;

        setPhase("Определение модели по именам…");
        java.util.HashMap<Integer, String> live = new java.util.HashMap<>();
        java.util.LinkedHashSet<Integer> samples = new java.util.LinkedHashSet<>();
        for (ParamTable.Table t : tables) for (int idx : ParamTable.sampleIndices(t, 5)) samples.add(idx);
        for (int idx : samples) {
            if (cancelDetect) break;
            String nm = probeName(idx);
            if (nm != null && !nm.isEmpty()) live.put(idx, shortOf(nm));
        }
        ParamTable.Table best = null; int bestScore = 1;   // нужно ≥2 совпадения
        for (ParamTable.Table t : tables) {
            int s = 0;
            for (int idx : ParamTable.sampleIndices(t, 5)) {
                String want = t.nameByIdx.get(idx), got = live.get(idx);
                if (want != null && got != null && shortOf(want).equals(got)) s++;
            }
            if (s > bestScore) { bestScore = s; best = t; }
        }
        if (best != null) Logger.i("[detect] модель по пробе: " + best.model + " (" + bestScore + "/5)");
        return best;
    }

    /** Объединение индексов, где параметр с этим коротким именем встречается по ВСЕМ таблицам. */
    private int[] unionIndices(String key) {
        java.util.LinkedHashSet<Integer> s = new java.util.LinkedHashSet<>();
        if (tables != null) for (ParamTable.Table t : tables) for (ParamTable.Row r : t.rows)
            if (r.shortName.equals(key)) s.add(r.index);
        int[] out = new int[s.size()]; int i = 0;
        for (int v : s) out[i++] = v;
        return out;
    }

    private static String shortOf(String name) {
        int bar = name.indexOf('|');
        return bar >= 0 ? name.substring(0, bar) : name;
    }

    /** Передать идентичность борта + найденные quick-id в телеметрию (один раз на serial/crc/count). */
    private void reportTelemetry(String modelName) {
        if (telemetry == null || boardCrc == 0) return;
        try {
            JSONObject ids = new JSONObject();
            for (QP q : PARAMS) if (q.idx >= 0) ids.put(q.key, q.idx);
            telemetry.reportConnection(Long.toHexString(boardCrc), boardCount, duml.acSerial(),
                    duml.acModel(), modelName != null ? modelName : "", ids, appVersion());
        } catch (Throwable t) { Logger.w("[tlm] report: " + t); }
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable t) { return ""; }
    }

    // ================= СОХРАНЕНИЕ / OVERLAY =================

    private void saveAndHub() {
        if (crcKey != null) {
            JSONObject o = new JSONObject();
            try {
                if (GPS.idx >= 0) o.put(GPS.key, GPS.idx);
                if (LED.idx >= 0) o.put(LED.key, LED.idx);
            } catch (Exception ignore) {}
            prefs().edit()
                    .putString("verified_" + crcKey, o.toString())
                    .putString("lastCrcKey", crcKey)
                    .apply();
            Logger.i("[save] " + crcKey + " -> " + o);
        }
        step = STEP_HUB;
        render();
        startOverlay(false);
    }

    private JSONObject loadVerified(String key) {
        String js = prefs().getString("verified_" + key, null);
        if (js != null) try { return new JSONObject(js); } catch (Exception ignore) {}
        return new JSONObject();
    }

    /** Поднять overlay-сервис с найденными индексами. false → нет разрешения. Overlay опционален:
     *  быстрые тумблеры в самом приложении работают и без него. Подсказку по разрешению показываем
     *  только при явном запросе (кнопка), чтобы не навязывать диалог на каждом входе. */
    private boolean startOverlay(boolean showHelpIfDenied) {
        if (!Settings.canDrawOverlays(this)) { if (showHelpIfDenied) showOverlayPermHelp(); return false; }
        java.util.ArrayList<QP> use = new java.util.ArrayList<>();
        for (QP q : PARAMS) if (q.idx >= 0) use.add(q);
        if (use.isEmpty()) { toast("Нет ни одного параметра для меню"); return false; }
        int n = use.size();
        String[] titles = new String[n], types = new String[n], onLabels = new String[n], offLabels = new String[n];
        int[] indices = new int[n];
        long[] onVals = new long[n], offVals = new long[n];
        for (int i = 0; i < n; i++) {
            QP q = use.get(i);
            titles[i] = q.title; types[i] = q.type; indices[i] = q.idx;
            onVals[i] = q.onVal; offVals[i] = q.offVal; onLabels[i] = q.onLabel; offLabels[i] = q.offLabel;
        }
        Intent i = new Intent(this, OverlayService.class);
        i.putExtra("titles", titles);
        i.putExtra("indices", indices);
        i.putExtra("onVals", onVals);
        i.putExtra("offVals", offVals);
        i.putExtra("types", types);
        i.putExtra("onLabels", onLabels);
        i.putExtra("offLabels", offLabels);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        overlayOn = true;
        return true;
    }

    private void stopOverlay() {
        try { stopService(new Intent(this, OverlayService.class)); } catch (Throwable ignore) {}
        overlayOn = false;
    }

    /** Оверлей опционален. Разрешения нет → короткий тост + стандартный системный экран (без appops-подсказок). */
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
        if (telemetry != null) telemetry.flush();   // дослать отложенные отчёты (throttle внутри)
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
        cancelDetect = true;     // не держим reader 40007 в фоне
        duml.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        duml.stop();
    }

    // ================= UI-ХЕЛПЕРЫ =================

    private void caption(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TXT_SUB);
        t.setTextSize(12);
        t.setPadding(dp(16), dp(16), dp(16), dp(6));
        body.addView(t);
    }

    private void note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TXT_SUB);
        t.setTextSize(13);
        t.setPadding(dp(16), dp(4), dp(16), dp(10));
        body.addView(t);
    }

    private void statusLine(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(15);
        t.setBackgroundColor(ROW_BG);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(1);
        body.addView(t, lp);
    }

    private void action(String text, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTextSize(16);
        b.setBackgroundColor(ROW_BG);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(16), dp(6), dp(16), dp(6));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(8);
        body.addView(b, lp);
    }

    private void immersive() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private android.content.SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * d); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }
}
