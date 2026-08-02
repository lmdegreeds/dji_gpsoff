package com.djiquick;

/** Сведения о сборке: строка версии и локальная (без сети) проверка «не устарела ли». */
public final class About {

    /** Через сколько дней считаем сборку старой. */
    private static final long STALE_DAYS = 90;

    private About() {}

    public static String versionLine() {
        return "v" + BuildStamp.NAME + " (" + BuildStamp.CODE + ") · " + BuildStamp.GIT;
    }

    public static long ageDays() {
        long ms = System.currentTimeMillis() - BuildStamp.BUILT_MS;
        return ms > 0 ? ms / (24L * 3600_000L) : 0;
    }

    /**
     * Устарела ли сборка — только по дате сборки, без единого сетевого запроса.
     * Именно этого не хватало раньше: пользователи месяцами сидели на сборке, где поиск
     * параметров был сломан, и никак не могли этого увидеть.
     */
    public static boolean isStale() { return ageDays() > STALE_DAYS; }

    /** Подсказка об обновлении, если сервер сообщил более свежую версию. null — нечего сказать. */
    public static String updateHint(Store store) {
        String latest = store.latestVersion();
        if (latest == null || latest.isEmpty() || latest.equals(BuildStamp.NAME)) {
            return isStale() ? "Сборке " + ageDays() + " дн. — вероятно, есть новее." : null;
        }
        return "Доступна версия " + latest + " (у тебя " + BuildStamp.NAME + ").";
    }
}
