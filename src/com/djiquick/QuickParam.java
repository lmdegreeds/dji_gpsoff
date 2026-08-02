package com.djiquick;

/**
 * Быстрый параметр: имя(а) для сверки с бортом, тип, значения и подписи двух положений.
 * Индекс не зашит — он находится детектом и проверяется живьём по имени, потому что на
 * другой прошивке тот же номер означает совсем другой параметр.
 */
public final class QuickParam {

    public final String key;         // короткое имя (ключ в models.tsv и в SharedPreferences)
    public final String title;       // как называем в UI приложения
    public final String type;        // ширина значения: U8/U16/U32/I8/I16
    public final String[] aliases;   // имена, которые принимаем от борта (short и полный путь)
    public final long onVal, offVal;
    public final String onLabel, offLabel;   // «вкл» / «выкл» — для тостов в приложении
    public final String btnOn, btnOff;       // короткие подписи кнопок оверлея (там мало места)

    public int idx = -1;             // найденный индекс (−1 = не найден)
    public String seenName;          // имя, реально прочитанное с борта
    public String source;            // откуда взяли индекс: «таблица» / «кандидат» / ...

    private QuickParam(String key, String title, String[] aliases, String type,
                       long onVal, String onLabel, String btnOn,
                       long offVal, String offLabel, String btnOff) {
        this.key = key; this.title = title; this.aliases = aliases; this.type = type;
        this.onVal = onVal; this.onLabel = onLabel; this.btnOn = btnOn;
        this.offVal = offVal; this.offLabel = offLabel; this.btnOff = btnOff;
    }

    /** Точное (без учёта регистра) совпадение имени борта с любым алиасом, по сегментам '|'. */
    public boolean matches(String boardName) {
        if (boardName == null || boardName.isEmpty()) return false;
        for (String seg : boardName.split("\\|")) {
            String s = seg.trim();
            for (String a : aliases) if (s.equalsIgnoreCase(a)) return true;
        }
        return false;
    }

    public void reset() { idx = -1; seenName = null; source = null; }

    /**
     * Три параметра, которые умеет переключать приложение. Порядок задаёт порядок строк
     * в хабе и в оверлее.
     *
     * fswitch_selection — это g_config.control.control_mode[0], диапазон 0..21 при default 12;
     * нас интересуют только два его положения: 3 = ATTI, 12 = Cine (штатное).
     */
    public static QuickParam[] all() {
        return new QuickParam[] {
            new QuickParam("gps_enable", "GPS",
                    new String[]{ "gps_enable", "g_config.gps_cfg.gps_enable" }, "U8",
                    1, "вкл", "GPS ON", 0, "выкл", "GPS OFF"),
            new QuickParam("forearm_led_ctrl", "LED",
                    new String[]{ "forearm_led_ctrl", "g_config.misc_cfg.forearm_lamp_ctrl" }, "U8",
                    239, "вкл", "LED ON", 0, "выкл", "LED OFF"),
            new QuickParam("fswitch_selection", "Режим",
                    new String[]{ "fswitch_selection", "g_config.control.control_mode[0]" }, "U8",
                    3, "ATTI", "ATTI", 12, "Cine", "CINE"),
        };
    }
}
