package com.djiquick;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Таблица моделей из assets/models.tsv — строка на прошивку: crc, встреченные count,
 * индексы трёх быстрых параметров, кодовые имена борта и «отпечаток» содержимого таблицы.
 * Генерируется tools/gen_models.py из дампов и телеметрии.
 *
 * Строк с одним и тем же code может быть НЕСКОЛЬКО — это варианты прошивок одной модели,
 * у которых таблица переехала (например, Neo 2 и Lito X1). Варианты не мешают опознанию:
 * для выбора модели строки с одинаковым code считаются одной моделью, а между самими
 * вариантами разбирается живая сверка имени — индексы всех вариантов идут в кандидаты.
 *
 * Даёт три вещи: (1) индекс параметра по имени для опознанной модели, (2) опознание модели
 * по отпечатку, когда crc неизвестен, (3) список индексов-кандидатов по всем моделям.
 *
 * Формат:
 *   P<TAB>i1<TAB>i2...                                      индексы-пробы
 *   M<TAB>code<TAB>crc<TAB>counts<TAB>led<TAB>gps<TAB>fsw<TAB>boards<TAB>fp<TAB>label
 */
public final class ModelDb {

    private static final String ASSET = "models.tsv";

    public static final class Entry {
        public String code = "?", label = "?";
        public long crc = 0;                 // 0 = неизвестен, НИКОГДА не сопоставляется
        public long[] counts = new long[0];
        public String[] boards = new String[0];
        public String[] fp = new String[0];  // выровнен по probeIndices(); "-" = неизвестно
        public final java.util.HashMap<String, Integer> idx = new java.util.HashMap<>();

        /** Индекс параметра по короткому имени, или null. */
        public Integer indexOf(String key) {
            Integer v = idx.get(key);
            return v != null && v >= 0 ? v : null;
        }
        boolean hasCount(long c) { for (long x : counts) if (x == c) return true; return false; }
        boolean hasBoard(String b) {
            if (b == null || b.isEmpty()) return false;
            for (String x : boards) if (x.equalsIgnoreCase(b)) return true;
            return false;
        }
        long countDistance(long c) {
            long best = Long.MAX_VALUE;
            for (long x : counts) best = Math.min(best, Math.abs(x - c));
            return best;
        }
        @Override public String toString() { return code + " (" + label + ")"; }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int[] probes = new int[0];
    private String[] keys = new String[0];   // порядок колонок led/gps/fsw из заголовка

    private ModelDb() {}

    public List<Entry> all() { return entries; }
    public int[] probeIndices() { return probes; }

    public static ModelDb load(Context ctx) {
        ModelDb db = new ModelDb();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open(ASSET), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.charAt(0) == '#') {
                    int i = line.indexOf("params=");
                    if (i >= 0) db.keys = line.substring(i + 7).split("\\s+")[0].trim().split(",");
                    continue;
                }
                String[] c = line.split("\t");
                if (c.length < 2) continue;
                if ("P".equals(c[0])) {
                    db.probes = new int[c.length - 1];
                    for (int k = 1; k < c.length; k++) db.probes[k - 1] = parseInt(c[k], -1);
                } else if ("M".equals(c[0]) && c.length >= 10) {
                    db.entries.add(parseEntry(db.keys, c));
                }
            }
        } catch (Exception e) {
            Logger.w("[db] загрузка " + ASSET + ": " + e);
        }
        Logger.i("[db] моделей: " + db.entries.size() + ", проб: " + db.probes.length);
        return db;
    }

    private static Entry parseEntry(String[] keys, String[] c) {
        Entry e = new Entry();
        e.code = c[1].trim();
        try { e.crc = Long.parseLong(c[2].trim(), 16); } catch (Exception ignore) {}
        String[] cs = c[3].split(",");
        e.counts = new long[cs.length];
        for (int k = 0; k < cs.length; k++) e.counts[k] = parseLong(cs[k], 0);
        for (int k = 0; k < keys.length && 4 + k < 7; k++) e.idx.put(keys[k], parseInt(c[4 + k], -1));
        e.boards = c[7].split(",");
        e.fp = c[8].split("\\|", -1);
        e.label = c[9].trim();
        return e;
    }

    /**
     * Выбрать модель по отпечатку прошивки. Каждое правило слабее предыдущего, но любой
     * выбранный индекс всё равно проверяется живьём по имени — поэтому неверный выбор стоит
     * секунд поиска, а не записи в чужой параметр. null → зовите pickByProbe().
     *
     * @param acModel кодовое имя борта, пойманное пассивно (может быть пустым)
     */
    public Entry pick(long crc, long count, String acModel) {
        if (crc != 0) {
            List<Entry> byCrc = new ArrayList<>();
            for (Entry e : entries) if (e.crc == crc) byCrc.add(e);

            List<Entry> exact = new ArrayList<>();                           // 1) точное crc+count
            for (Entry e : byCrc) if (e.hasCount(count)) exact.add(e);
            if (!exact.isEmpty()) {
                if (exact.size() > 1)
                    Logger.i("[db] crc+count дают " + exact.size() + " варианта — беру "
                            + exact.get(0) + ", остальные останутся кандидатами");
                return exact.get(0);
            }
            List<Entry> byBoardCrc = new ArrayList<>();                      // 2) crc + кодовое имя
            for (Entry e : byCrc) if (e.hasBoard(acModel)) byBoardCrc.add(e);
            if (!byBoardCrc.isEmpty()) return nearestCount(byBoardCrc, count);
            if (!byCrc.isEmpty()) return nearestCount(byCrc, count);         // 3-4) ближайший count
        }
        List<Entry> byBoard = new ArrayList<>();                             // 5-6) только кодовое имя
        for (Entry e : entries) if (e.hasBoard(acModel)) byBoard.add(e);
        // Несколько строк одной модели — это её варианты прошивки, а не двусмысленность.
        if (!byBoard.isEmpty() && oneCode(byBoard)) return nearestCount(byBoard, count);
        return null;
    }

    /** Ближайшая по числу параметров запись; при равенстве — первая в файле. */
    private static Entry nearestCount(List<Entry> list, long count) {
        Entry best = list.get(0);
        for (Entry e : list) if (e.countDistance(count) < best.countDistance(count)) best = e;
        return best;
    }

    private static boolean oneCode(List<Entry> list) {
        for (Entry e : list) if (!e.code.equals(list.get(0).code)) return false;
        return true;
    }

    /** true, если выбор сделан только по кодовому имени и count далеко — стоит показать в UI. */
    public boolean pickIsWeak(Entry e, long crc, long count) {
        if (e == null) return true;
        if (e.crc != 0 && e.crc == crc && e.hasCount(count)) return false;
        return e.crc != crc || e.countDistance(count) > 32;
    }

    /**
     * Опознать модель по живым именам на индексах-пробах: +1 за совпадение префикса,
     * −1 за явное расхождение, «-» игнорируется. Считаем по МОДЕЛИ (все строки с одним code
     * дают лучший из своих счётов): у варианта прошивки отпечатка нет, и без группировки он
     * бы занижал счёт своей же модели. Принимаем лучшую при score ≥ 2 и строго больше второй
     * (другой модели) — иначе неоднозначно и лучше честно вернуть null. Между вариантами
     * одной модели выбираем по ближайшему числу параметров.
     */
    public Entry pickByProbe(Map<Integer, String> live, long count) {
        java.util.LinkedHashMap<String, Integer> byCode = new java.util.LinkedHashMap<>();
        for (Entry e : entries) {
            int score = 0;
            for (int k = 0; k < probes.length && k < e.fp.length; k++) {
                String want = e.fp[k];
                String got = live.get(probes[k]);
                if (want == null || "-".equals(want) || got == null || got.isEmpty()) continue;
                score += got.length() >= want.length() && got.startsWith(want) ? 1 : -1;
            }
            Integer prev = byCode.get(e.code);
            if (prev == null || score > prev) byCode.put(e.code, score);
        }
        String bestCode = null;
        int bestScore = Integer.MIN_VALUE, secondScore = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> m : byCode.entrySet()) {
            int score = m.getValue();
            if (score > bestScore) { secondScore = bestScore; bestScore = score; bestCode = m.getKey(); }
            else if (score > secondScore) secondScore = score;
        }
        if (bestCode == null || bestScore < 2 || bestScore <= secondScore) {
            Logger.i("[db] проба: неоднозначно (лучший " + bestScore + ", второй " + secondScore + ")");
            return null;
        }
        List<Entry> variants = new ArrayList<>();
        for (Entry e : entries) if (e.code.equals(bestCode)) variants.add(e);
        Entry best = nearestCount(variants, count);
        Logger.i("[db] проба: " + best + " (" + bestScore + "/" + probes.length
                + (variants.size() > 1 ? ", вариантов " + variants.size() : "") + ")");
        return best;
    }

    /** Все известные индексы этого параметра по всем моделям — кандидаты для чужой прошивки. */
    public int[] candidates(String key) {
        java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        for (Entry e : entries) {
            Integer v = e.indexOf(key);
            if (v != null) out.add(v);
        }
        int[] a = new int[out.size()];
        int i = 0;
        for (int v : out) a[i++] = v;
        return a;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }
}
