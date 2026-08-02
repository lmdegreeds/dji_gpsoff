package com.djiquick;

import org.json.JSONObject;

/**
 * Seam for the optional connection-telemetry module. MainActivity talks only to this interface and loads
 * the implementation ({@code com.djiquick.Telemetry}) by reflection, so builds that ship WITHOUT the
 * telemetry source simply run with no reporter (the class is absent → the loader returns null → no-op).
 * This keeps the telemetry code (endpoint, auth, payload) out of the public source tree.
 */
public interface TelemetrySink {
    /** Report one identified connection; the implementation dedupes/queues/sends as it sees fit. */
    void reportConnection(String crcHex, long count, String serial, String acModel,
                          String friendly, JSONObject quickIds, String appVersion);
    /** Retry anything queued from an earlier offline connection. */
    void flush();

    /**
     * Разовый пакет диагностики, отправленный пользователем вручную со страницы «О программе».
     * Мимо дедупликации и очереди: одна попытка, без ретраев, без сохранения — если не ушло,
     * пользователь копирует текст и присылает сам.
     * БЛОКИРУЮЩИЙ — вызывать не из main-треда. true только на 2xx.
     */
    boolean sendDiagnostics(JSONObject bundle);
}
