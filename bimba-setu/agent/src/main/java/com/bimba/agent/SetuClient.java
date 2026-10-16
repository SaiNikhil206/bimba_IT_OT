package com.bimba.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Talks to the BIMBA Setu (/system/bimba-agent-*), the same protocol as the Windows agent. */
final class SetuClient {
    /** keeps nulls: a reading without a value must still carry "tagValue": null (Setu's schema requires it) */
    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    /** refused by Setu (status &gt; 0) or not reachable (status 0) */
    static final class SetuException extends IOException {
        final int status;
        final JsonObject body;
        /** SHA-256 of Setu certificate when TLS validation failed (offer --trust-cert) */
        final String untrustedCertSha256;

        SetuException(int status, String message, JsonObject body, String untrustedCert) {
            super(message);
            this.status = status;
            this.body = body == null ? new JsonObject() : body;
            this.untrustedCertSha256 = untrustedCert;
        }

        /** the user's organizations when Setu asks which one to enroll into (HTTP 409): slug -> name */
        Map<String, String> orgs() {
            Map<String, String> m = new LinkedHashMap<>();
            if (body.has("orgs") && body.get("orgs").isJsonArray()) {
                for (JsonElement e : body.getAsJsonArray("orgs")) {
                    if (e.isJsonObject()) {
                        m.put(Json.str(e.getAsJsonObject(), "slug"), Json.str(e.getAsJsonObject(), "name"));
                    }
                }
            }
            return m;
        }
    }

    final String server;
    /** true when requests sign in with the client certificate (mutual TLS) instead of the secret */
    final boolean usesCertificate;
    private final String pin;
    private final HttpClient http;
    private volatile String lastCertSha256;
    private volatile boolean lastCertRejected;

    /** @param keyManagers the client certificate (null = sign in with the agent secret) */
    SetuClient(String server, String pinnedCertSha256, KeyManager[] keyManagers) {
        this.server = normalize(server);
        this.pin = pinnedCertSha256 == null || pinnedCertSha256.isBlank() ? null : pinnedCertSha256.trim().toLowerCase(Locale.ROOT);
        boolean https = this.server.startsWith("https://");
        this.usesCertificate = keyManagers != null && https;
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER);
        ProxySelector proxy = proxyFor(URI.create(this.server));
        if (proxy != null) {
            b.proxy(proxy);
        }
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(usesCertificate ? keyManagers : null, new TrustManager[]{new Trust()}, null);
            b.sslContext(ctx);
        } catch (Exception e) {
            throw new IllegalStateException("TLS is not available: " + e.getMessage(), e);
        }
        http = b.build();
    }

    /** "gw.example.com:8443" -> "https://gw.example.com:8443" (no trailing slash) */
    static String normalize(String server) {
        String s = server == null ? "" : server.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("enter Setu address");
        }
        if (!s.regionMatches(true, 0, "http://", 0, 7) && !s.regionMatches(true, 0, "https://", 0, 8)) {
            s = "https://" + s;
        }
        try {
            URI u = new URI(s);
            if (u.getHost() == null || u.getHost().isEmpty()) {
                throw new IllegalArgumentException("Setu address is not a valid URL");
            }
            return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getRawAuthority();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Setu address is not a valid URL");
        }
    }

    static boolean isPlainHttpToRemote(String server) {
        try {
            URI u = URI.create(normalize(server));
            String h = u.getHost();
            return "http".equals(u.getScheme()) && !(h.equals("localhost") || h.startsWith("127.") || h.equals("[::1]"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ endpoints

    /** reachability + "is site agent registration enabled" */
    JsonObject check() throws SetuException {
        return send("GET", "/system/bimba-agent-enroll", null, null, 20);
    }

    JsonObject enroll(Map<String, Object> auth, String displayName, String site, Map<String, Object> machine) throws SetuException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("auth", auth);
        body.put("displayName", displayName);
        body.put("site", site);
        body.put("machine", machine);
        return send("POST", "/system/bimba-agent-enroll", GSON.toJson(body), null, 30);
    }

    /** @param machine full inventory (every 10 minutes) or null; @param collector edge collector status or null */
    JsonObject heartbeat(AgentConfig cfg, Map<String, Object> machine, String status, Map<String, Object> collector) throws SetuException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("agentVersion", AgentMain.version());
        if (machine != null) {
            body.put("machine", machine);
        }
        if (collector != null && "running".equals(status)) {
            body.put("collector", collector);
        }
        Updater u = Updater.current;
        if (u != null && "running".equals(status)) {
            body.put("updates", u.report());              // how this computer updates itself, and how far an update got
        }
        return send("POST", "/system/bimba-agent-heartbeat", GSON.toJson(body), cfg, 20);
    }

    /** the installers' versions and SHA-256 (no sign-in): the root updater checks a downloaded installer against it */
    JsonObject release() throws SetuException {
        return send("GET", "/system/bimba-agent-update/release", null, null, 30);
    }

    /** downloads the installer of an offered update (signed in as the agent) into target */
    void download(AgentConfig cfg, String path, java.nio.file.Path target) throws SetuException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(server + path))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "BIMBA-Site-Agent/" + AgentMain.version() + " (Linux)")
                .header("X-BIMBA-Agent", cfg.agentId)
                .GET();
        if (!usesCertificate && cfg.secret != null) {
            rb.header("Authorization", "Bearer " + cfg.secret);
        }
        HttpResponse<java.nio.file.Path> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofFile(target));
        } catch (IOException e) {
            throw new SetuException(0, "download from " + server + " failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()), null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SetuException(0, "interrupted", null, null);
        }
        if (resp.statusCode() != 200) {
            String text = "";
            try {
                text = java.nio.file.Files.size(target) < 65_536 ? java.nio.file.Files.readString(target, StandardCharsets.UTF_8) : "";
                java.nio.file.Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // the error text is optional
            }
            String message = Json.str(parse(text), "error");
            throw new SetuException(resp.statusCode(), message == null ? "Setu answered HTTP " + resp.statusCode() : message, null, null);
        }
    }

    /** first client certificate (signed in with the secret) or renewal (signed in with the current certificate) */
    JsonObject requestCertificate(AgentConfig cfg, byte[] csr) throws SetuException {
        return send("POST", "/system/bimba-agent-certificate", GSON.toJson(Map.of("csr", Base64.getEncoder().encodeToString(csr))), cfg, 30);
    }

    /** the edge collector's data sources (secrets in plain text: HTTPS + client certificate) */
    JsonObject getConfig(AgentConfig cfg) throws SetuException {
        return send("GET", "/system/bimba-agent-config", null, cfg, 30);
    }

    /** long-poll (about 25 s) for the next console command; null when there is none */
    JsonObject nextCommand(AgentConfig cfg) throws SetuException {
        return send("POST", "/system/bimba-agent-commands", "{}", cfg, 45);
    }

    JsonObject commandResult(AgentConfig cfg, String id, Map<String, Object> answer) throws SetuException {
        return send("POST", "/system/bimba-agent-commands/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/result",
                GSON.toJson(answer), cfg, 30);
    }

    /** body = {"messages":[...]} built from the disk buffer without re-parsing the messages */
    JsonObject postReadings(AgentConfig cfg, String body) throws SetuException {
        return send("POST", "/system/bimba-agent-readings", body, cfg, 60);
    }

    // ------------------------------------------------------------------ transport

    /** null for 204 No Content */
    private JsonObject send(String method, String path, String body, AgentConfig agent, int timeoutSeconds) throws SetuException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(server + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "BIMBA-Site-Agent/" + AgentMain.version() + " (Linux)")
                .header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            rb.header("Content-Type", "application/json; charset=utf-8");
        }
        if (agent != null) {
            rb.header("X-BIMBA-Agent", agent.agentId);
            // with a client certificate the secret is not sent (Setu no longer accepts it)
            if (!usesCertificate && agent.secret != null) {
                rb.header("Authorization", "Bearer " + agent.secret);
            }
        }
        HttpResponse<String> resp;
        lastCertRejected = false;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (lastCertRejected) {
                throw new SetuException(0, pin != null
                        ? "Setu's certificate changed and no longer matches the trusted certificate"
                        : "Setu's certificate is not trusted (self-signed or wrong host name)", null, lastCertSha256);
            }
            Throwable root = e.getMessage() == null && e.getCause() != null ? e.getCause() : e;
            String why = e instanceof HttpConnectTimeoutException ? "connection timed out"
                    : e instanceof HttpTimeoutException ? "timed out"
                    : root.getMessage() != null ? root.getMessage()
                    : e instanceof java.net.ConnectException ? "connection refused" : root.getClass().getSimpleName();
            throw new SetuException(0, "cannot reach Setu at " + server + ": " + why, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SetuException(0, "interrupted", null, null);
        }
        int code = resp.statusCode();
        if (code == 204) {
            return null;
        }
        JsonObject json = parse(resp.body());
        if (code >= 200 && code < 300) {
            return json;
        }
        String message = Json.str(json, "error");
        if (message == null) {
            message = code == 404 ? "this address is not a BIMBA Setu (site agent registration not found)"
                    : "Setu answered HTTP " + code;
        }
        throw new SetuException(code, message, json, null);
    }

    private static JsonObject parse(String text) {
        try {
            JsonElement e = text == null || text.isBlank() ? null : JsonParser.parseString(text);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    /** HTTPS_PROXY / https_proxy (HTTP_PROXY for http://), unless NO_PROXY lists Setu */
    static ProxySelector proxyFor(URI target) {
        boolean https = "https".equals(target.getScheme());
        String p = firstEnv(https ? new String[]{"HTTPS_PROXY", "https_proxy"} : new String[]{"HTTP_PROXY", "http_proxy"});
        if (p == null) {
            return null;
        }
        String noProxy = firstEnv(new String[]{"NO_PROXY", "no_proxy"});
        if (noProxy != null) {
            String host = target.getHost().toLowerCase(Locale.ROOT);
            for (String n : noProxy.split(",")) {
                String d = n.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\*?\\.", "");
                if (!d.isEmpty() && (d.equals("*") || host.equals(d) || host.endsWith("." + d))) {
                    return null;
                }
            }
        }
        try {
            URI u = URI.create(p.contains("://") ? p : "http://" + p);
            return ProxySelector.of(new InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 3128));
        } catch (RuntimeException e) {
            Log.warn("ignoring the proxy setting " + p + ": " + e.getMessage());
            return null;
        }
    }

    private static String firstEnv(String[] names) {
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    static String sha256Hex(byte[] der) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(der)) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Setu certificate check: a pinned certificate (self-signed Setu server the installer was told to trust) must match
     * exactly, otherwise the system trust store and the host name decide. Remembers the certificate's SHA-256 so a
     * refusal can show it.
     */
    private final class Trust extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager system;

        Trust() throws Exception {
            TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            f.init((KeyStore) null);
            X509ExtendedTrustManager found = null;
            for (TrustManager t : f.getTrustManagers()) {
                if (t instanceof X509ExtendedTrustManager x) {
                    found = x;
                }
            }
            system = found;
        }

        private boolean pinned(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                lastCertRejected = true;
                throw new CertificateException("no server certificate");
            }
            lastCertSha256 = sha256Hex(chain[0].getEncoded());
            if (pin == null) {
                return false;
            }
            if (!pin.equals(lastCertSha256)) {
                lastCertRejected = true;
                throw new CertificateException("Setu certificate does not match the trusted certificate");
            }
            return true;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            if (!pinned(chain)) {
                try {
                    system.checkServerTrusted(chain, authType, engine);
                } catch (CertificateException e) {
                    lastCertRejected = true;
                    throw e;
                }
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            if (!pinned(chain)) {
                try {
                    system.checkServerTrusted(chain, authType, socket);
                } catch (CertificateException e) {
                    lastCertRejected = true;
                    throw e;
                }
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (!pinned(chain)) {
                try {
                    system.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    lastCertRejected = true;
                    throw e;
                }
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("not a server");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("not a server");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("not a server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return system == null ? new X509Certificate[0] : system.getAcceptedIssuers();
        }
    }

    /** small JSON accessors */
    static final class Json {
        private Json() {
        }

        static String str(JsonObject o, String k) {
            JsonElement e = o == null ? null : o.get(k);
            return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
        }

        static int integer(JsonObject o, String k, int fallback) {
            try {
                JsonElement e = o == null ? null : o.get(k);
                return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
            } catch (RuntimeException e) {
                return fallback;
            }
        }

        static boolean bool(JsonObject o, String k) {
            JsonElement e = o == null ? null : o.get(k);
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
        }

        static JsonObject obj(JsonObject o, String k) {
            JsonElement e = o == null ? null : o.get(k);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        }

        static List<JsonObject> objects(JsonObject o, String k) {
            List<JsonObject> out = new ArrayList<>();
            JsonElement e = o == null ? null : o.get(k);
            if (e != null && e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    if (x.isJsonObject()) {
                        out.add(x.getAsJsonObject());
                    }
                }
            }
            return out;
        }
    }
}
