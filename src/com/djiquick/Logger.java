package com.djiquick;

import java.util.ArrayDeque;

/**
 * Мини-логгер: только Logcat + небольшой кольцевой буфер для показа на экране.
 * Полноценный файловый логгер основного приложения тут не нужен.
 */
public final class Logger {

    private static final String TAG = "DjiQuick";
    private static final int RING = 200;
    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static Runnable listener;

    private Logger() {}

    public static void i(String msg) { write(android.util.Log.INFO, "I", msg); }
    public static void w(String msg) { write(android.util.Log.WARN, "W", msg); }
    public static void e(String msg) { write(android.util.Log.ERROR, "E", msg); }

    public static void e(String msg, Throwable t) {
        write(android.util.Log.ERROR, "E", msg + " :: " + t);
        android.util.Log.e(TAG, msg, t);
    }

    private static synchronized void write(int prio, String lvl, String msg) {
        android.util.Log.println(prio, TAG, msg);
        ring.addLast(lvl + " " + msg);
        while (ring.size() > RING) ring.removeFirst();
        if (listener != null) listener.run();
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : ring) sb.append(s).append('\n');
        return sb.toString();
    }

    public static synchronized void setListener(Runnable r) { listener = r; }
}
