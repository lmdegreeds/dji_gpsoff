package com.djiquick;

/**
 * Вариант транспорта DUML на пульте: пара портов и «диалект» кадра.
 *
 * Пульты различаются не только номером порта, но и тем, какой адрес отправителя и какой
 * cmd_type принимает роутер, и завёрнут ли кадр во внешнюю оболочку. Захардкодить это по
 * модели нельзя: по DJI RC (rm330) и RC Pro (rm310) нет данных вообще ни у одного проекта.
 * Поэтому варианты перебираются и проверяются НАСТОЯЩИМ обменом — см. {@link Duml#probe}.
 */
public final class Transport {

    /** Внешняя оболочка кадра на порту 40007: 55 CC 30 75 + длина внутреннего кадра u32 LE. */
    private static final byte[] WRAP_MAGIC = { 0x55, (byte) 0xCC, 0x30, 0x75 };

    public final String name;
    public final int injectPort, readPort;
    public final int src;         // адрес отправителя: 0x02 (MOBILE_APP idx 0) или 0x82 (idx 4)
    public final int cmdType;     // 0x40 (request+ACK) или 0x20 (request, ACK before exec)
    public final boolean wrapper;

    private Transport(String name, int injectPort, int readPort, int src, int cmdType, boolean wrapper) {
        this.name = name; this.injectPort = injectPort; this.readPort = readPort;
        this.src = src; this.cmdType = cmdType; this.wrapper = wrapper;
    }

    /**
     * Кандидаты в порядке убывания уверенности.
     *
     * №1 — наш, проверен на живом rc331 (чтение и запись, сосуществует с DJI Fly).
     * №2/№3 — пути FreeFCC, подтверждены их автором на rc331 и RC Pro 2 (rc520).
     * №4 — на случай «роли портов поменялись местами», как замечено на rc520.
     * №5 — 8901 отдаёт identity на rc520 и годился для inject на rc331.
     *
     * 8902-8904 сознательно НЕ включены: в FreeFCC это догадка, по независимым замерам
     * 8903/8904 пусты на обоих известных пультах, а 8902 на rc331 висит на wildcard-интерфейсе
     * и льёт непрошеый поток. В инвентаризацию (Listeners) они попадают, в перебор — нет.
     *
     * ВНИМАНИЕ по варианту №2: там inject идёт в тот же 40007, что и чтение. На rc331 40007 —
     * это зеркало FPV-видео DJI Fly, и лишние соединения на нём Fly роняют. Подбор всегда идёт
     * с остановленной Fly, так что при детекте это безопасно. А вот тумблеры пишут при
     * ЗАПУЩЕННОЙ Fly — если на каком-то пульте победит именно этот вариант, соседство с Fly
     * надо будет проверять отдельно. У FreeFCC ровно этот путь и помечен как «требует
     * запущенной DJI Fly», то есть там он штатный, — но на rc331 это не проверялось.
     */
    public static Transport[] candidates() {
        return new Transport[] {
            new Transport("40008/40007",      40008, 40007, 0x02, 0x40, false),
            new Transport("40007 wrapped",    40007, 40007, 0x02, 0x40, true),
            new Transport("40009 fcc",        40009, 40009, 0x82, 0x20, false),
            new Transport("40009/40007",      40009, 40007, 0x02, 0x40, false),
            new Transport("8901",              8901,  8901, 0x02, 0x40, false),
        };
    }

    /** Вариант по имени (для кеша и ручного выбора), или null. */
    public static Transport byName(String n) {
        if (n == null) return null;
        for (Transport t : candidates()) if (t.name.equals(n)) return t;
        return null;
    }

    /** Наш проверенный на rc331 вариант — используется, пока подбор не сказал иного. */
    public static Transport defaultTransport() { return candidates()[0]; }

    /** Все порты, которые этот вариант хочет открыть. */
    public int[] ports() {
        return injectPort == readPort ? new int[]{ injectPort } : new int[]{ injectPort, readPort };
    }

    /** Обернуть кадр, если вариант того требует; иначе вернуть как есть. */
    public byte[] wrap(byte[] frame) {
        if (!wrapper) return frame;
        byte[] out = new byte[8 + frame.length];
        System.arraycopy(WRAP_MAGIC, 0, out, 0, 4);
        int len = frame.length;
        out[4] = (byte) len;
        out[5] = (byte) (len >> 8);
        out[6] = (byte) (len >> 16);
        out[7] = (byte) (len >> 24);
        System.arraycopy(frame, 0, out, 8, frame.length);
        return out;
    }

    @Override public String toString() {
        return name + " (src=" + Integer.toHexString(src) + " type=" + Integer.toHexString(cmdType)
                + (wrapper ? " wrapped" : "") + ")";
    }
}
