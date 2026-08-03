package com.djiquick;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Что реально слушает на пульте — читаем из /proc/net, НЕ открывая ни одного сокета.
 *
 * Зачем: на незнакомом пульте это единственный способ увидеть картину, не долбясь вслепую
 * в закрытые порты. И главное — если ни один вариант транспорта не заработает, в диагностике
 * всё равно окажется полный список слушателей, и по нему станет понятно, что пробовать дальше.
 * Без этого отчёт «не сработало» бесполезен.
 *
 * /proc/net/{tcp,tcp6,unix} читаются любым приложением. Никаких разрешений не нужно.
 */
public final class Listeners {

    /** Порты, которые вообще имеет смысл упоминать: DUML-кандидаты и соседи по карте портов. */
    private static final int[] INTERESTING = {
            5744, 8901, 8902, 8903, 8904, 8872, 40007, 40008, 40009
    };

    private final Set<Integer> listening = new LinkedHashSet<>();
    private final List<String> tcpRows = new ArrayList<>();
    private final List<String> unixPaths = new ArrayList<>();
    private boolean readOk = false;

    private Listeners() {}

    public static Listeners scan() {
        Listeners l = new Listeners();
        l.readOk = l.readTcp("/proc/net/tcp") | l.readTcp("/proc/net/tcp6");
        l.readUnix();
        Logger.i("[net] слушают: " + l.portsLine());
        return l;
    }

    /** Слушает ли кто-то этот порт. При недоступном /proc/net — true, чтобы не блокировать подбор. */
    public boolean isListening(int port) {
        return !readOk || listening.contains(port);
    }

    public boolean available() { return readOk; }

    /** Короткая строка для UI: «40007, 40008, 40009, 8901, 5744». */
    public String portsLine() {
        if (!readOk) return "(/proc/net недоступен)";
        StringBuilder sb = new StringBuilder();
        for (int p : listening) { if (sb.length() > 0) sb.append(", "); sb.append(p); }
        return sb.length() == 0 ? "(ничего не найдено)" : sb.toString();
    }

    /** Полный отчёт для пакета диагностики: интересные TCP-строки + мейлбоксы DUSS. */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("tcp_listen: ").append(portsLine()).append('\n');
        for (String r : tcpRows) sb.append("  ").append(r).append('\n');
        if (!unixPaths.isEmpty()) {
            sb.append("unix:\n");
            for (String u : unixPaths) sb.append("  ").append(u).append('\n');
        }
        return sb.toString();
    }

    /**
     * Формат /proc/net/tcp: «sl local_address rem_address st ... uid ...».
     * local_address = HEX-адрес:HEX-порт, st=0A → LISTEN.
     */
    private boolean readTcp(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            String line = br.readLine();               // заголовок
            while ((line = br.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 8) continue;
                if (!"0A".equalsIgnoreCase(f[3])) continue;       // только LISTEN
                int colon = f[1].lastIndexOf(':');
                if (colon < 0) continue;
                int port;
                try { port = Integer.parseInt(f[1].substring(colon + 1), 16); }
                catch (Exception e) { continue; }
                listening.add(port);
                if (interesting(port)) {
                    String addr = f[1].substring(0, colon);
                    tcpRows.add("port=" + port + " addr=" + addr + " uid=" + f[7]);
                }
            }
            return true;
        } catch (Throwable t) {
            Logger.w("[net] " + path + ": " + t);
            return false;
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignore) {}
        }
    }

    /** Абстрактные мейлбоксы шины DUSS (@/duss/mb/0x...) — полезны для диагностики чужого пульта. */
    private void readUnix() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/net/unix"));
            String line = br.readLine();               // заголовок
            Set<String> seen = new LinkedHashSet<>();
            while ((line = br.readLine()) != null) {
                int at = line.indexOf("@/duss/");
                if (at < 0) continue;
                String p = line.substring(at).trim();
                if (seen.add(p) && unixPaths.size() < 40) unixPaths.add(p);
            }
        } catch (Throwable t) {
            Logger.i("[net] /proc/net/unix недоступен: " + t.getClass().getSimpleName());
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignore) {}
        }
    }

    private static boolean interesting(int port) {
        for (int p : INTERESTING) if (p == port) return true;
        return false;
    }
}
