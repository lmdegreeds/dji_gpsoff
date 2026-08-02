package com.djiquick;

import android.content.Context;
import android.os.Build;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;

/**
 * Файловый журнал с ротацией — чтобы можно было разобрать проблему пользователя, а не гадать.
 *
 * Лежит в getExternalFilesDir(): не требует разрешений на любом API, читается системным
 * shell'ом пульта и вытягивается по MTP — то есть журнал можно забрать без деплоя и без root.
 * /data/data/... под Android 11 системным shell'ом читается не всегда.
 *
 * 128 КБ текущий + один архив = потолок 256 КБ. Полный перебор 2100 индексов даёт заметно
 * меньше 4 КБ, так что это много сессий.
 */
final class DiagLog {

    private static final String NAME = "diag.log";
    private static final String NAME_OLD = "diag.log.1";
    private static final long MAX_BYTES = 128 * 1024;

    private final File dir;
    private File cur;
    private BufferedWriter out;
    private long written;

    private DiagLog(File dir) { this.dir = dir; }

    /** null, если писать некуда (тогда журнал остаётся только в кольцевом буфере). */
    static DiagLog open(Context ctx) {
        File dir = null;
        try { dir = ctx.getExternalFilesDir(null); } catch (Throwable ignore) {}
        if (dir == null) dir = ctx.getFilesDir();
        if (dir == null) return null;
        DiagLog d = new DiagLog(dir);
        return d.reopen() ? d : null;
    }

    private synchronized boolean reopen() {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            cur = new File(dir, NAME);
            written = cur.exists() ? cur.length() : 0;
            out = new BufferedWriter(new FileWriter(cur, true));
            return true;
        } catch (Throwable t) {
            android.util.Log.w("DjiQuick", "diag log open: " + t);
            out = null;
            return false;
        }
    }

    /** Заголовок сессии — по нему в чужом журнале видно, какая сборка это писала. */
    synchronized void banner() {
        append("--- " + BuildStamp.NAME + " (" + BuildStamp.CODE + ") git " + BuildStamp.GIT
                + " | " + Build.FINGERPRINT + " | " + new java.util.Date() + " ---");
    }

    /** Дозапись строки. Flush построчно: объём низкий, а потерять хвост при падении нельзя. */
    synchronized void append(String line) {
        if (out == null) return;
        try {
            out.write(line);
            out.write('\n');
            out.flush();
            written += line.length() + 1;
            if (written >= MAX_BYTES) rotate();
        } catch (Throwable t) {
            android.util.Log.w("DjiQuick", "diag log write: " + t);
            try { out.close(); } catch (Throwable ignore) {}
            out = null;
        }
    }

    private void rotate() {
        try {
            out.close();
            File old = new File(dir, NAME_OLD);
            if (old.exists() && !old.delete()) android.util.Log.w("DjiQuick", "diag rotate: старый не удалён");
            if (!cur.renameTo(old)) android.util.Log.w("DjiQuick", "diag rotate: переименование не удалось");
        } catch (Throwable ignore) {
        } finally {
            reopen();
        }
    }

    /** Последние maxChars символов журнала (текущий файл; при нехватке — добираем из архива). */
    synchronized String tail(int maxChars) {
        StringBuilder sb = new StringBuilder();
        String head = readTail(new File(dir, NAME), maxChars);
        if (head.length() < maxChars) {
            String prev = readTail(new File(dir, NAME_OLD), maxChars - head.length());
            sb.append(prev);
        }
        sb.append(head);
        return sb.toString();
    }

    private static String readTail(File f, int maxChars) {
        if (maxChars <= 0 || f == null || !f.exists()) return "";
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long from = Math.max(0, len - maxChars);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String s = new String(buf, "UTF-8");
            // обрезали посреди строки — выкидываем первую неполную
            if (from > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s;
        } catch (Throwable t) {
            return "";
        } finally {
            if (raf != null) try { raf.close(); } catch (Throwable ignore) {}
        }
    }

    /** Путь к файлу — показываем на странице «О программе», чтобы журнал можно было забрать. */
    synchronized String path() { return cur != null ? cur.getAbsolutePath() : "(нет)"; }
}
