package com.djiquick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Поиск индексов быстрых параметров на конкретном борту.
 *
 * Порядок стадий (каждая следующая включается только для ещё не найденных параметров):
 *   1. 0xE0 → отпечаток прошивки {crc, count} — «паспорт» модели;
 *   2. выбор модели в ModelDb по crc/count/кодовому имени, иначе проба по содержимому;
 *   3. индекс из таблицы модели;
 *   4. индексы этого же параметра у других моделей;
 *   5. спираль вокруг якорей (при обновлении прошивки индекс уезжает недалеко);
 *   6. линейный перебор всей таблицы — крайний резерв, минуты.
 *
 * ЛЮБОЙ кандидат подтверждается живым get_info: имя с борта должно совпасть с ожидаемым.
 * Без этого на чужой прошивке тот же номер означает совсем другой параметр.
 *
 * Читает поток с пульта → работает только с остановленной DJI Fly.
 * Если транспорт по умолчанию молчит, стадии 1 предшествует подбор (Duml.probe).
 */
public final class Detector {

    /** Пауза между DUML-запросами: вплотную роутер роняет ответы. */
    private static final int PACE_MS = 150;
    private static final int SCAN_MAX = 2100;    // потолок линейного перебора
    private static final int R_STRONG = 48;      // радиус спирали вокруг индекса выбранной модели
    private static final int R_WEAK   = 16;      // радиус вокруг прочих кандидатов
    private static final int TRACE_MAX = 4096;   // потолок трассы (уходит в диагностику)

    public interface Listener {
        void phase(String text);
        /** errOrNull — текст ошибки, hardFail — показать его как отказ, а не как результат. */
        void done(String errOrNull, boolean hardFail);
    }

    private final Duml duml;
    private final ModelDb db;
    private final QuickParam[] params;
    private final Listener listener;
    private final Store store;
    private Listeners netInv;

    private volatile boolean cancelled = false;
    private volatile boolean running = false;

    private long crc = 0, count = 0;
    private ModelDb.Entry model;
    private boolean modelWeak = false;
    private final StringBuilder trace = new StringBuilder();

    public Detector(Duml duml, ModelDb db, QuickParam[] params, Store store, Listener listener) {
        this.duml = duml; this.db = db; this.params = params;
        this.store = store; this.listener = listener;
    }

    /** Что слушало на пульте в момент детекта — уходит в диагностику. null, пока детект не шёл. */
    public Listeners listeners() { return netInv; }

    public boolean isRunning() { return running; }
    public void cancel() { cancelled = true; }

    public long crc() { return crc; }
    public long count() { return count; }
    public ModelDb.Entry model() { return model; }
    public boolean modelWeak() { return modelWeak; }
    /** Ключ прошивки: под ним хранятся найденные индексы. null, пока борт не ответил. */
    public String crcKey() { return crc == 0 ? null : Long.toHexString(crc) + "_" + count; }
    /** Компактная трасса поиска — на страницу «О программе» и в пакет диагностики. */
    public String trace() { return trace.toString(); }

    public void start() {
        if (running) return;
        running = true; cancelled = false;
        trace.setLength(0);
        new Thread(this::work, "detect").start();
    }

    private void work() {
        try {
            netInv = Listeners.scan();          // пассивно, ни одного сокета не открывает
            log("слушают: " + netInv.portsLine());
            applySavedTransport();
            duml.start();

            // 1) отпечаток прошивки
            long[] ti = null;
            for (int a = 0; a < 8 && !cancelled; a++) {
                phase("Связь с бортом… (" + (a + 1) + "/8)");
                ti = duml.tableInfo(0, 1200);
                if (ti != null) break;
                sleep(400);
            }
            // Транспорт не отозвался — возможно, это не наш пульт. Перебрать варианты.
            if (ti == null && !cancelled) ti = probeTransport();
            if (cancelled) { finish("Отменено", true); return; }
            if (ti == null) {
                log("E0 нет ответа ни на одном транспорте");
                finish("Нет связи с бортом. Проверь, что дрон включён и DJI Fly остановлена.", true);
                return;
            }
            crc = ti[0]; count = ti[1];
            log("E0 crc=" + Long.toHexString(crc) + " count=" + count);
            for (QuickParam q : params) q.reset();

            // 2) модель: по crc/count/кодовому имени, иначе по содержимому таблицы
            String acModel = duml.acModel();
            model = db.pick(crc, count, acModel);
            if (model != null) {
                modelWeak = db.pickIsWeak(model, crc, count);
                log("модель=" + model.code + (modelWeak ? " (нестрого)" : "") + " ac=" + acModel);
            } else {
                model = probeModel();
                modelWeak = model != null;
                log("модель по пробе=" + (model != null ? model.code : "нет"));
            }

            int cap = (int) Math.min(count > 0 ? count : SCAN_MAX, SCAN_MAX);
            // probed: индекс -> прочитанное имя. Один на все параметры, но результат проверяется
            // против КАЖДОГО ещё не найденного — иначе спираль одного параметра «съедала» индекс
            // другого (на Neo 2 LED=4 попадал внутрь спирали GPS вокруг 53 и терялся навсегда).
            Map<Integer, String> probed = new HashMap<>();

            // 3) индекс из таблицы выбранной модели
            for (QuickParam q : params) {
                if (cancelled) { finish("Отменено", true); return; }
                Integer ix = model != null ? model.indexOf(q.key) : null;
                if (ix == null || q.idx >= 0) continue;
                phase("Проверка «" + q.title + "»" + (model != null ? " (" + model.label + ")" : "") + "…");
                harvest(ix, probed, "таблица");
            }

            // 4) индексы того же параметра у других моделей
            for (QuickParam q : params) {
                if (q.idx >= 0 || cancelled) continue;
                phase("Поиск «" + q.title + "» по кандидатам…");
                for (int cand : db.candidates(q.key)) {
                    if (cancelled) { finish("Отменено", true); return; }
                    if (probed.containsKey(cand)) continue;
                    harvest(cand, probed, "кандидат");
                    if (q.idx >= 0) break;
                }
            }

            // 5) спираль вокруг якорей
            for (QuickParam q : params) {
                if (q.idx >= 0 || cancelled) continue;
                Integer strongI = model != null ? model.indexOf(q.key) : null;
                phase("Поиск «" + q.title + "» рядом с известными…");
                for (int idx : radialOrder(strongI != null ? strongI : -1,
                                           db.candidates(q.key), probed.keySet(), cap)) {
                    if (cancelled) break;
                    harvest(idx, probed, "рядом");
                    if (q.idx >= 0) break;
                }
            }

            // 6) линейный перебор — через тот же probeName с ретраями, что и остальные стадии:
            //    single-shot здесь ложно отбраковывал реальный индекс, а это последний рубеж.
            if (needMore() && !cancelled) {
                for (int idx = 0; idx < cap && !cancelled && needMore(); idx++) {
                    if (probed.containsKey(idx)) continue;
                    if ((idx & 15) == 0) phase("Полный перебор " + idx + "/" + cap + "…");
                    harvest(idx, probed, "скан");
                }
            }

            StringBuilder found = new StringBuilder();
            for (QuickParam q : params)
                found.append(q.key).append('=').append(q.idx).append(' ');
            log("итог: " + found.toString().trim());

            boolean noneFound = true;
            for (QuickParam q : params) if (q.idx >= 0) noneFound = false;
            finish(null, noneFound);
        } catch (Throwable e) {
            Logger.e("detect error", e);
            log("исключение: " + e);
            finish("Ошибка: " + e, true);
        } finally {
            duml.stop();
            running = false;
        }
    }

    // ---- транспорт ----

    /** Поставить транспорт, ранее подтверждённый на этом пульте. Иначе останется тот, что по умолчанию. */
    private void applySavedTransport() {
        if (store == null) return;
        Transport saved = Transport.byName(store.transport(Store.rcKey(duml.rcModel())));
        if (saved != null) {
            duml.setTransport(saved);
            log("транспорт из кеша: " + saved.name);
        }
    }

    /**
     * Перебрать варианты транспорта. Возвращает ответ 0xE0 при успехе, иначе null.
     *
     * Сюда попадаем только когда транспорт по умолчанию (или из кеша) молчит — на rc331 этого
     * не происходит, поэтому лишних проб там нет. Кеш при неудаче сбрасывается: прошивка пульта
     * могла смениться.
     */
    private long[] probeTransport() {
        phase("Подбор транспорта…");
        log("транспорт " + duml.transport().name + " молчит — перебираю варианты");
        Transport found = duml.probe(netInv, new Duml.ProbeCb() {
            @Override public void trying(Transport t, int n, int total) {
                phase("Подбор транспорта " + n + "/" + total + ": " + t.name + "…");
                log("проба " + t.name);
            }
            @Override public boolean cancelled() { return cancelled; }
        });
        if (found == null) {
            log("подбор: ни один вариант не ответил");
            if (store != null) store.forgetTransport(duml.rcModel());
            return null;
        }
        log("подбор: подошёл " + found.name);
        // rcModel мог появиться только сейчас — сохраняем под ключом с ним.
        if (store != null) store.saveTransport(duml.rcModel(), found.name);
        // Повторный запрос: движок уже поднят на найденном транспорте. С ретраями — один
        // потерянный ответ не должен превратить удачный подбор в «нет связи».
        for (int a = 0; a < 3 && !cancelled; a++) {
            long[] ti = duml.tableInfo(0, 1200);
            if (ti != null) return ti;
            sleep(250);
        }
        return null;
    }

    /** Прочитать имя по индексу и раздать его ВСЕМ ещё не найденным параметрам. */
    private void harvest(int idx, Map<Integer, String> probed, String source) {
        String nm = probeName(idx);
        probed.put(idx, nm == null ? "" : nm);
        if (nm == null || nm.isEmpty()) return;
        for (QuickParam q : params) {
            if (q.idx >= 0 || !q.matches(nm)) continue;
            q.idx = idx; q.seenName = nm; q.source = source;
            log("нашёл " + q.key + " idx=" + idx + " (" + source + ")");
        }
    }

    private boolean needMore() {
        for (QuickParam q : params) if (q.idx < 0) return true;
        return false;
    }

    /** Опознание модели по живым именам на индексах-пробах — когда crc неизвестен/неоднозначен. */
    private ModelDb.Entry probeModel() {
        int[] probes = db.probeIndices();
        if (probes.length == 0) return null;
        phase("Определение модели по именам…");
        HashMap<Integer, String> live = new HashMap<>();
        for (int idx : probes) {
            if (cancelled) break;
            String nm = probeName(idx);
            if (nm != null && !nm.isEmpty()) live.put(idx, shortOf(nm));
        }
        log("проба: прочитано " + live.size() + "/" + probes.length);
        return db.pickByProbe(live);
    }

    /**
     * Имя параметра по индексу через get_info, до 3 попыток. На занятом 40007 одиночный
     * запрос нередко теряет ответ, и без ретрая реальный кандидат ложно отбраковывается.
     * Пауза ПЕРЕД каждым запросом. null — ответа не было вовсе.
     */
    private String probeName(int idx) {
        for (int attempt = 0; attempt < 3 && !cancelled; attempt++) {
            sleep(PACE_MS);
            byte[] info = duml.getInfoRaw(0, idx, 1100);
            if (info != null) return Duml.nameFromInfo(info);
        }
        return null;
    }

    /**
     * Порядок обхода «рядом с якорями»: сначала спираль ±R_STRONG вокруг индекса выбранной
     * модели, затем ±R_WEAK вокруг каждого прочего кандидата. Сами якоря уже проверены.
     */
    private static List<Integer> radialOrder(int strong, int[] weak, Set<Integer> exclude, int cap) {
        LinkedHashSet<Integer> order = new LinkedHashSet<>();
        addSpiral(order, strong, R_STRONG, exclude, cap);
        for (int a : weak) addSpiral(order, a, R_WEAK, exclude, cap);
        return new ArrayList<>(order);
    }

    private static void addSpiral(Set<Integer> order, int anchor, int r, Set<Integer> exclude, int cap) {
        if (anchor < 0) return;
        for (int d = 1; d <= r; d++) {
            int up = anchor + d, dn = anchor - d;
            if (up >= 0 && up < cap && !exclude.contains(up)) order.add(up);
            if (dn >= 0 && dn < cap && !exclude.contains(dn)) order.add(dn);
        }
    }

    private static String shortOf(String name) {
        int bar = name.indexOf('|');
        return bar >= 0 ? name.substring(0, bar) : name;
    }

    private void phase(String p) { if (listener != null) listener.phase(p); }

    private void finish(String err, boolean hardFail) {
        if (listener != null) listener.done(err, hardFail);
    }

    /** Строка в трассу (и в общий журнал). Трасса ограничена, чтобы не раздувать диагностику. */
    private void log(String s) {
        Logger.i("[detect] " + s);
        if (trace.length() < TRACE_MAX) trace.append(s).append('\n');
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }
}
