package com.djiquick;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Таблицы параметров моделей (name→index) — объединение таблиц из app.ledgpsforcefly и
 * наших реальных дампов (`params/*`), лежат плоско в assets/paramtable_*.txt.
 *
 * Формат строки: `index\ttype\tdefault\tmin\tmax\tname` (name = short|full.path).
 * Заголовок: `#crc=<hex> model=<NAME> count=<N> ...`.
 *
 * Даёт две сильные стороны детекта: (1) резолв индекса по ИМЕНИ из таблицы модели,
 * (2) контентную пробу модели по нескольким именам, когда crc неизвестен/неоднозначен.
 */
public final class ParamTable {

    public static final class Row {
        public int index;
        public int type;
        public String name;
        public String shortName;
    }

    public static final class Table {
        public long crc = 0, count = 0;
        public String model = "?";
        public String file = "";
        public final List<Row> rows = new ArrayList<>();
        public final HashMap<Integer, String> nameByIdx = new HashMap<>();

        /** Индекс параметра по короткому имени (до '|'), или null. */
        public Integer indexOf(String shortKey) {
            for (Row r : rows) if (r.shortName.equals(shortKey)) return r.index;
            return null;
        }
    }

    private ParamTable() {}

    /** Загрузить все таблицы из assets/paramtable_*.txt (плоско — надёжнее для aapt на Windows). */
    public static List<Table> loadAll(Context ctx) {
        ArrayList<Table> out = new ArrayList<>();
        try {
            String[] names = ctx.getAssets().list("");
            if (names != null) for (String n : names) {
                if (n == null || !n.endsWith(".txt") || !n.startsWith("paramtable_")) continue;
                Table t = parse(ctx, n);
                if (t != null && !t.rows.isEmpty()) out.add(t);
            }
        } catch (Exception e) { Logger.w("[tbl] loadAll: " + e); }
        Logger.i("[tbl] загружено таблиц: " + out.size());
        return out;
    }

    private static Table parse(Context ctx, String path) {
        Table t = new Table();
        t.file = path;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.charAt(0) == '#') {
                    int i = line.indexOf("crc=");
                    if (i >= 0) try { t.crc = Long.parseLong(line.substring(i + 4).split("\\s+")[0].trim(), 16); } catch (Exception ignore) {}
                    int j = line.indexOf("model=");
                    if (j >= 0) t.model = line.substring(j + 6).split("\\s+")[0].trim();
                    int k = line.indexOf("count=");
                    if (k >= 0) try { t.count = Long.parseLong(line.substring(k + 6).split("\\s+")[0].trim()); } catch (Exception ignore) {}
                    continue;
                }
                String[] c = line.split("\t", 6);
                if (c.length < 6) continue;
                Row r = new Row();
                try { r.index = Integer.parseInt(c[0].trim()); } catch (Exception e) { continue; }
                try { r.type = (int) Double.parseDouble(c[1].trim()); } catch (Exception e) { r.type = 0; }
                r.name = c[5];
                int bar = r.name.indexOf('|');
                r.shortName = bar >= 0 ? r.name.substring(0, bar) : r.name;
                t.rows.add(r);
                t.nameByIdx.put(r.index, r.name);
            }
        } catch (Exception e) { Logger.w("[tbl] parse " + path + ": " + e); return null; }
        if (t.model == null || t.model.equals("?")) t.model = path;
        return t;
    }

    /** До n индексов, равномерно разбросанных по таблице — для контентной пробы модели. */
    public static int[] sampleIndices(Table t, int n) {
        int size = t.rows.size();
        if (size == 0) return new int[0];
        int m = Math.min(n, size);
        int[] out = new int[m];
        for (int i = 0; i < m; i++) out[i] = t.rows.get((int) (((long) i * (size - 1)) / Math.max(1, m - 1))).index;
        return out;
    }
}
