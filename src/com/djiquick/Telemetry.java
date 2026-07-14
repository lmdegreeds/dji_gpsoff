package com.djiquick;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

/**
 * One-shot connection telemetry: after a drone is identified we POST its crc/serial/model + the resolved
 * quick-param ids to a webhook, exactly ONCE per (serial, crc, count). Design constraints (see the feature
 * request):
 *  - endpoint + auth header are NOT stored in cleartext in the APK: they live in the gitignored, XOR+Base64
 *    obfuscated asset {@code telemetry.b64} (decoded at runtime). If the asset is absent, telemetry is a no-op.
 *  - interception/forgery: transport is HTTPS-only (an http url is refused); the body is signed with
 *    HMAC-SHA256 (key = the shared header secret) over "body.timestamp", sent as X-Signature + X-Timestamp,
 *    so the receiver can reject tampered or replayed requests.
 *  - offline-tolerant: if there is no network the record is queued and retried later (on the next connect /
 *    resume), throttled so we never hammer. A key that POSTs 2xx is remembered and never sent again.
 * All work happens off the main thread; every failure degrades silently (this is best-effort diagnostics).
 */
final class Telemetry implements TelemetrySink {

    private static final String PREFS = "djiquick";
    private static final String K_SENT  = "tlm_sent";     // Set<String>: keys already delivered (never resend)
    private static final String K_QUEUE = "tlm_queue";    // JSONArray string: [{k,b}] pending bodies
    private static final String K_LAST  = "tlm_last";     // long: last network attempt (ms) — throttle
    private static final long   MIN_ATTEMPT_GAP_MS = 60_000;   // не пробовать чаще раза в минуту
    private static final int    QUEUE_MAX = 20;
    private static final byte[] XKEY = "Dq7#kP2!aZ9mL0xR8tVeN3wS5uY1bG6h".getBytes();   // matches the generator

    private final Context ctx;
    private String url, ip, hName, hValue;
    private boolean loaded, available;
    private volatile boolean sending;

    Telemetry(Context c) { ctx = c.getApplicationContext(); }

    /** Decode the obfuscated secret asset once. available=false (silent no-op) if missing/invalid/non-https. */
    private synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            java.io.InputStream in = ctx.getAssets().open("telemetry.b64");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int nb;
            while ((nb = in.read(buf)) > 0) bo.write(buf, 0, nb);
            in.close();
            byte[] x = android.util.Base64.decode(bo.toString("US-ASCII").trim(), android.util.Base64.DEFAULT);
            byte[] raw = new byte[x.length];
            for (int i = 0; i < x.length; i++) raw[i] = (byte) (x[i] ^ XKEY[i % XKEY.length]);
            JSONObject o = new JSONObject(new String(raw, "UTF-8"));
            url = o.getString("url"); hName = o.getString("hn"); hValue = o.getString("hv");
            ip = o.optString("ip", "");                 // fixed server IP → skip DNS (empty = resolve normally)
            available = url != null && url.startsWith("https://") && hName != null && hValue != null;
            if (!available) Logger.w("[tlm] disabled: endpoint must be https");
        } catch (Throwable t) {
            available = false;
            Logger.i("[tlm] секрет не найден — телеметрия отключена (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Report a fresh connection. Deduped: skipped entirely if this (serial,crc,count) already delivered or is
     * already queued. Then tries to send (or leaves it queued for later if offline).
     */
    @Override public void reportConnection(String crcHex, long count, String serial, String acModel,
                          String friendly, JSONObject quickIds, String appVersion) {
        ensureLoaded();
        if (!available) return;
        final String key = (serial == null ? "" : serial) + "|" + crcHex + "|" + count;
        if (sent().contains(key)) { Logger.i("[tlm] уже отправлено для " + key + " — пропуск"); return; }
        try {
            JSONObject b = new JSONObject();
            b.put("key", key);
            b.put("crc", crcHex);
            b.put("count", count);
            b.put("serial", serial == null ? "" : serial);
            b.put("model", acModel == null ? "" : acModel);
            b.put("model_name", friendly == null ? "" : friendly);
            b.put("quick_ids", quickIds == null ? new JSONObject() : quickIds);
            b.put("app", appVersion == null ? "" : appVersion);
            b.put("ts", System.currentTimeMillis());
            b.put("nonce", Long.toHexString(System.nanoTime()) + Integer.toHexString(hashCode()));
            enqueue(key, b.toString());
        } catch (Throwable t) { Logger.w("[tlm] build: " + t); return; }
        trySend();
    }

    /** Retry any queued records (called on resume). Throttled + network-gated inside. */
    @Override public void flush() { ensureLoaded(); if (available) trySend(); }

    // ---- queue + send ----

    private void trySend() {
        if (!available || sending) return;
        final SharedPreferences p = prefs();
        if (queueRaw(p).length() == 0) return;
        if (!online()) { Logger.i("[tlm] офлайн — отложено (" + queueRaw(p).length() + " в очереди)"); return; }
        long now = System.currentTimeMillis();
        if (now - p.getLong(K_LAST, 0) < MIN_ATTEMPT_GAP_MS) return;   // не слишком часто
        p.edit().putLong(K_LAST, now).apply();
        sending = true;
        new Thread(() -> {
            try {
                JSONArray q = queueRaw(prefs());
                for (int i = 0; i < q.length(); i++) {
                    if (!online()) break;
                    JSONObject item = q.optJSONObject(i);
                    if (item == null) continue;
                    String key = item.optString("k"), body = item.optString("b");
                    if (post(body)) {
                        markSent(key);
                        removeFromQueue(key);
                        Logger.i("[tlm] доставлено " + key);
                    } else {
                        Logger.w("[tlm] не доставлено, оставляю в очереди: " + key);
                        break;                       // сервер/сеть недоступны — прекратить, повторим позже
                    }
                }
            } finally { sending = false; }
        }).start();
    }

    /**
     * POST over TLS to the FIXED server IP so no DNS lookup for the hostname ever happens (the endpoint IP
     * is constant). Security is preserved by connecting the raw socket to the IP but doing the TLS handshake
     * for the real hostname: SNI + certificate chain trust + an explicit hostname check against `host`, and
     * the HTTP Host header is the hostname. A cert that isn't valid for the hostname → the send is refused
     * (anti-interception/MITM). HTTP/1.1 request is written by hand (one short POST, Connection: close).
     */
    private boolean post(String body) {
        javax.net.ssl.SSLSocket ss = null;
        try {
            URL u = new URL(url);
            if (!"https".equalsIgnoreCase(u.getProtocol())) return false;   // anti-interception: HTTPS only
            final String host = u.getHost();
            final int port = u.getPort() > 0 ? u.getPort() : 443;
            String path = u.getFile(); if (path == null || path.isEmpty()) path = "/";
            final String dialHost = (ip != null && !ip.isEmpty()) ? ip : host;
            // getByName on a numeric IP literal parses it — it does NOT issue a DNS query.
            java.net.Socket raw = new java.net.Socket();
            raw.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByName(dialHost), port), 8000);
            raw.setSoTimeout(10000);
            javax.net.ssl.SSLSocketFactory f = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            ss = (javax.net.ssl.SSLSocket) f.createSocket(raw, host, port, true);   // host → SNI + session for `host`
            try {   // belt-and-suspenders explicit SNI (API 24+)
                javax.net.ssl.SSLParameters sp = ss.getSSLParameters();
                sp.setServerNames(java.util.Collections.<javax.net.ssl.SNIServerName>singletonList(
                        new javax.net.ssl.SNIHostName(host)));
                ss.setSSLParameters(sp);
            } catch (Throwable ignore) {}
            ss.startHandshake();                                            // validates the cert CHAIN (trust store)
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ss.getSession())) {
                Logger.w("[tlm] cert не для " + host + " — отказ (возможен MITM)"); return false;
            }
            long ts = System.currentTimeMillis();
            byte[] payload = body.getBytes("UTF-8");
            StringBuilder req = new StringBuilder()
                    .append("POST ").append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("User-Agent: djiquick\r\n")
                    .append("Content-Type: application/json; charset=utf-8\r\n")
                    .append(hName).append(": ").append(hValue).append("\r\n")           // n8n header auth
                    .append("X-Timestamp: ").append(ts).append("\r\n")
                    .append("X-Signature: ").append(hmac(body + "." + ts, hValue)).append("\r\n")  // anti-forgery/replay
                    .append("Content-Length: ").append(payload.length).append("\r\n")
                    .append("Connection: close\r\n\r\n");
            java.io.OutputStream os = ss.getOutputStream();
            os.write(req.toString().getBytes("UTF-8")); os.write(payload); os.flush();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(ss.getInputStream(), "UTF-8"));
            String statusLine = r.readLine();                              // "HTTP/1.1 200 OK"
            Logger.i("[tlm] POST(" + dialHost + ") -> " + statusLine);
            if (statusLine == null) return false;
            String[] parts = statusLine.split(" ");
            int code = parts.length >= 2 ? Integer.parseInt(parts[1].trim()) : 0;
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            Logger.w("[tlm] post: " + t);
            return false;
        } finally { if (ss != null) try { ss.close(); } catch (Throwable ignore) {} }
    }

    private static String hmac(String msg, String key) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
            byte[] d = m.doFinal(msg.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) { return false; }
    }

    // ---- persistence ----

    private SharedPreferences prefs() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private Set<String> sent() { return new HashSet<>(prefs().getStringSet(K_SENT, new HashSet<>())); }
    private void markSent(String key) {
        Set<String> s = sent(); s.add(key);
        prefs().edit().putStringSet(K_SENT, s).apply();
    }

    private JSONArray queueRaw(SharedPreferences p) {
        try { return new JSONArray(p.getString(K_QUEUE, "[]")); } catch (Throwable t) { return new JSONArray(); }
    }
    private synchronized void enqueue(String key, String body) {
        SharedPreferences p = prefs();
        JSONArray q = queueRaw(p);
        for (int i = 0; i < q.length(); i++) {                 // dedupe: already queued for this key
            JSONObject it = q.optJSONObject(i);
            if (it != null && key.equals(it.optString("k"))) return;
        }
        try {
            JSONObject it = new JSONObject().put("k", key).put("b", body);
            JSONArray nq = new JSONArray();
            // keep the newest QUEUE_MAX-1, then append this one (bounded to avoid unbounded growth offline)
            int drop = Math.max(0, q.length() - (QUEUE_MAX - 1));
            for (int i = drop; i < q.length(); i++) nq.put(q.get(i));
            nq.put(it);
            p.edit().putString(K_QUEUE, nq.toString()).apply();
        } catch (Throwable t) { Logger.w("[tlm] enqueue: " + t); }
    }
    private synchronized void removeFromQueue(String key) {
        SharedPreferences p = prefs();
        JSONArray q = queueRaw(p), nq = new JSONArray();
        for (int i = 0; i < q.length(); i++) {
            JSONObject it = q.optJSONObject(i);
            if (it != null && !key.equals(it.optString("k"))) nq.put(it);
        }
        p.edit().putString(K_QUEUE, nq.toString()).apply();
    }
}
