package com.djiquick;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Единственное место, где живут ключи SharedPreferences. Раньше часть из них дублировалась
 * в Telemetry — файл prefs один и тот же, поэтому расхождение имён было бы тихим багом.
 *
 * Ключи найденных индексов НЕ переименовывать: по ним существующие установки пропускают
 * мастер при обновлении приложения.
 */
public final class Store {

    public static final String PREFS = "djiquick";

    // найденные индексы
    private static final String K_LAST_KEY = "lastCrcKey";          // отпечаток последней прошивки
    private static final String K_VERIFIED = "verified_";           // + crcKey -> JSON {имя: индекс}

    // телеметрия (читаются и из Telemetry — держим имена здесь)
    static final String K_TLM_SENT  = "tlm_sent";                   // Set<String>: уже доставленные ключи
    static final String K_TLM_QUEUE = "tlm_queue";                  // JSONArray: отложенные отправки
    static final String K_TLM_LAST  = "tlm_last";                   // long: время последней попытки

    private static final String K_CONSENT    = "tlm_consent";       // 0 не спрашивали / 1 да / 2 нет
    private static final String K_CONSENT_TS = "tlm_consent_ts";
    private static final String K_LATEST     = "latest_version";    // версия с сервера, если сообщил
    private static final String K_TRANSPORT  = "transport_";        // + ключ пульта -> имя варианта

    public static final int CONSENT_UNKNOWN = 0, CONSENT_ALLOW = 1, CONSENT_DENY = 2;

    private final Context ctx;

    public Store(Context c) { ctx = c.getApplicationContext(); }

    private SharedPreferences p() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ---- индексы параметров ----

    public String lastCrcKey() { return p().getString(K_LAST_KEY, null); }

    /** Сохранённые индексы для этой прошивки: {имя параметра: индекс}. Пустой JSON, если нет. */
    public JSONObject verified(String crcKey) {
        String js = crcKey == null ? null : p().getString(K_VERIFIED + crcKey, null);
        if (js != null) try { return new JSONObject(js); } catch (Exception ignore) {}
        return new JSONObject();
    }

    public void saveVerified(String crcKey, QuickParam[] params) {
        if (crcKey == null) return;
        JSONObject o = new JSONObject();
        try {
            for (QuickParam q : params) if (q.idx >= 0) o.put(q.key, q.idx);
        } catch (Exception ignore) {}
        p().edit().putString(K_VERIFIED + crcKey, o.toString())
                  .putString(K_LAST_KEY, crcKey).apply();
        Logger.i("[save] " + crcKey + " -> " + o);
    }

    // ---- согласие на телеметрию ----

    public int consent() { return p().getInt(K_CONSENT, CONSENT_UNKNOWN); }
    public boolean consentAllowed() { return consent() == CONSENT_ALLOW; }

    public void setConsent(int state) {
        p().edit().putInt(K_CONSENT, state).putLong(K_CONSENT_TS, System.currentTimeMillis()).apply();
        if (state != CONSENT_ALLOW) clearTelemetryQueue();
        Logger.i("[tlm] согласие = " + state);
    }

    /**
     * Выбросить всё, что ждёт отправки. Работает, даже если класс Telemetry в сборке
     * отсутствует — файл prefs общий. K_TLM_SENT оставляем: он лишь не даёт слать повторно
     * уже доставленное, и его очистка привела бы к дублям при повторном включении.
     */
    public void clearTelemetryQueue() {
        p().edit().remove(K_TLM_QUEUE).remove(K_TLM_LAST).apply();
    }

    // ---- транспорт DUML ----

    /**
     * Ключ пульта: Build.DEVICE + пойманное кодовое имя. Кодовое имя приходит только когда
     * транспорт уже работает, поэтому Build.DEVICE обязателен — он есть всегда и служит
     * бутстрапом на первом запуске.
     */
    public static String rcKey(String rcModel) {
        String dev = android.os.Build.DEVICE == null ? "?" : android.os.Build.DEVICE;
        return dev + (rcModel == null || rcModel.isEmpty() ? "" : "_" + rcModel);
    }

    /** Имя ранее подтверждённого варианта транспорта для этого пульта, или null. */
    public String transport(String rcKey) {
        return p().getString(K_TRANSPORT + rcKey, null);
    }

    /**
     * Сохранить подтверждённый транспорт. Пишем под ДВА ключа: с кодовым именем пульта (точный)
     * и без него (только Build.DEVICE). Второй нужен оверлею — он поднимается отдельным сервисом,
     * кодового имени не знает и без этого ключа не нашёл бы запись.
     */
    public void saveTransport(String rcModel, String transportName) {
        if (transportName == null) return;
        p().edit().putString(K_TRANSPORT + rcKey(rcModel), transportName)
                  .putString(K_TRANSPORT + rcKey(""), transportName).apply();
        Logger.i("[duml] транспорт " + transportName + " сохранён для " + rcKey(rcModel));
    }

    /** Забыть подтверждённый транспорт — например, когда он перестал отвечать. */
    public void forgetTransport(String rcModel) {
        p().edit().remove(K_TRANSPORT + rcKey(rcModel)).remove(K_TRANSPORT + rcKey("")).apply();
    }

    // ---- версия, о которой сообщил сервер (если сообщил) ----

    public String latestVersion() { return p().getString(K_LATEST, null); }
    public void setLatestVersion(String v) {
        if (v == null || v.isEmpty()) return;
        p().edit().putString(K_LATEST, v).apply();
    }
}
