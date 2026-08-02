package com.djiquick;

import java.util.ArrayDeque;

/**
 * Единственная точка логирования: Logcat + кольцевой буфер для показа на экране + (после
 * attachFile) файл, который можно забрать с пульта без деплоя и приложить к диагностике.
 *
 * Метка времени обязательна: без неё чужой дамп читается почти вслепую — непонятно, где
 * пауза в минуту, а где подряд идущие попытки.
 */
public final class Logger {

    private static final String TAG = "DjiQuick";
    private static final int RING = 200;
    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static Runnable listener;
    private static DiagLog file;

    private Logger() {}

    public static void i(String msg) { write(android.util.Log.INFO, "I", msg); }
    public static void w(String msg) { write(android.util.Log.WARN, "W", msg); }
    public static void e(String msg) { write(android.util.Log.ERROR, "E", msg); }

    public static void e(String msg, Throwable t) {
        write(android.util.Log.ERROR, "E", msg + " :: " + t);
        android.util.Log.e(TAG, msg, t);
    }

    /** Подключить файловый журнал. Идемпотентно — зовётся и из активности, и из сервиса. */
    public static synchronized void attachFile(android.content.Context ctx) {
        if (file != null) return;
        file = DiagLog.open(ctx);
        if (file != null) file.banner();
    }

    private static synchronized void write(int prio, String lvl, String msg) {
        android.util.Log.println(prio, TAG, msg);
        String line = stamp() + " " + lvl + " " + msg;
        ring.addLast(line);
        while (ring.size() > RING) ring.removeFirst();
        if (file != null) file.append(line);
        if (listener != null) listener.run();
    }

    /** HH:mm:ss.SSS без SimpleDateFormat — он не потокобезопасен, а write() общий на всё. */
    private static String stamp() {
        long ms = System.currentTimeMillis();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND), c.get(java.util.Calendar.MILLISECOND));
    }

    /** Кольцевой буфер этой сессии (для экрана). Файл содержит больше — см. DiagLog.tail. */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : ring) sb.append(s).append('\n');
        return sb.toString();
    }

    /** Хвост файлового журнала, если он подключён; иначе — кольцевой буфер. */
    public static synchronized String tail(int maxChars) {
        if (file != null) {
            String t = file.tail(maxChars);
            if (t != null && !t.isEmpty()) return t;
        }
        return dump();
    }

    public static synchronized void setListener(Runnable r) { listener = r; }
}
