package com.bimba.gateway.delivery;

import com.bimba.gateway.Json;
import com.bimba.gateway.config.BimbaConfig;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * HTTPS/HTTP POST of each batch: BIMBA Ingest /api/ingest/, Boomi Web Services Server listener, webhooks, and in their
 * own body formats ({@link Formats}) Splunk HEC, Elasticsearch / OpenSearch _bulk and InfluxDB line protocol.
 * Sign-in: basic, bearer, token with a scheme, or OAuth2 client credentials (token fetched and renewed before it
 * expires; one retry with a fresh token on 401). Optional gzip body, own certificates and HTTP proxy.
 */
public class RestDestination extends Destination {
    private final HttpClient http;
    private volatile String oauthToken;
    private volatile long oauthExpiresAt;

    public RestDestination(BimbaConfig.Destination cfg) {
        super(cfg);
        this.http = httpClient();
    }

    private String format() {
        return cfg.format == null ? "envelope" : cfg.format;
    }

    @Override
    public void send(byte[] batch) throws Exception {
        // envelope: the batch in this destination's format version (2 = the BRD batch form with records[])
        Formats.Body body = "envelope".equalsIgnoreCase(format()) ? new Formats.Body(mappedEnvelope(batch), "application/json")
                : Formats.render(format(), batch, cfg.url, mapsReadings() ? this::layout : null);
        if (body.bytes().length == 0) {
            return;                                    // nothing this format can carry (e.g. only empty values)
        }
        HttpResponse<String> resp = post(body, false);
        if (resp.statusCode() == 401 && oauth()) {
            resp = post(body, true);                   // the token may have been revoked early: once with a new one
        }
        int code = resp.statusCode();
        if (code >= 200 && code < 300) {
            // _bulk answers 200 even when documents failed
            if ("elastic-bulk".equals(format()) && resp.body() != null && resp.body().replace(" ", "").contains("\"errors\":true")) {
                throw new java.io.IOException("bulk request had errors: " + firstReason(resp.body()));
            }
            return;
        }
        String detail = detail(resp);
        if (code == 400 || code == 413 || code == 422) {
            throw new RejectedException(detail);
        }
        throw new java.io.IOException(detail);
    }

    private HttpResponse<String> post(Formats.Body body, boolean newToken) throws Exception {
        HttpRequest.Builder req = authorized(URI.create(cfg.url), newToken).header("Content-Type", body.contentType());
        if (cfg.compress) {
            req.header("Content-Encoding", "gzip").POST(HttpRequest.BodyPublishers.ofByteArray(gzip(body.bytes())));
        } else {
            req.POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes()));
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** BIMBA's own health check (config "probe"): POST with X-BIMBA-Probe: 1, the receiver stores nothing */
    @Override
    public void probe() throws Exception {
        if (!cfg.probe) {
            signInCheck();
            return;
        }
        HttpRequest req = authorized(URI.create(cfg.url), false)
                .header("Content-Type", "application/json")
                .header("X-BIMBA-Probe", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"probe\":true}"))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new java.io.IOException("probe " + detail(resp));
        }
    }

    @Override
    public String test() throws Exception {
        if (cfg.probe) {
            probe();
            return "Health check passed: the receiver is reachable and accepted the credentials.";
        }
        return signInCheck();
    }

    /** Signs in without storing anything: a request each service answers without writing data. */
    private String signInCheck() throws Exception {
        String token = oauth() ? " (OAuth2 token received)" : "";
        URI url = URI.create(cfg.url);
        URI root = URI.create(url.getScheme() + "://" + url.getRawAuthority() + "/");
        switch (format()) {
            case "elastic-bulk" -> {
                HttpResponse<String> r = http.send(authorized(root, false).GET().build(), HttpResponse.BodyHandlers.ofString());
                checkAccess(r, "Elasticsearch / OpenSearch");
                String cluster = field(r.body(), "cluster_name");
                return "Signed in to " + (cluster.isEmpty() ? root.toString() : "cluster " + cluster) + " (HTTP " + r.statusCode() + ")." + token;
            }
            case "influx-line" -> {
                HttpResponse<String> r = http.send(authorized(root.resolve("api/v2/buckets?limit=1"), false).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                checkAccess(r, "InfluxDB");
                return (r.statusCode() == 200 ? "Token accepted by InfluxDB." : "InfluxDB is reachable (HTTP " + r.statusCode() + ").") + token;
            }
            case "splunk-hec" -> {
                // an empty event request is answered 400 "No data" when the token is valid; nothing is indexed
                HttpResponse<String> r = http.send(authorized(url, false).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                checkAccess(r, "Splunk");
                return "Token accepted by Splunk HEC (HTTP " + r.statusCode() + ", nothing was indexed).";
            }
            default -> {
                HttpResponse<String> r = http.send(authorized(url, false).GET().build(), HttpResponse.BodyHandlers.ofString());
                checkAccess(r, "The receiver");
                return "Reachable: the receiver answered HTTP " + r.statusCode() + " to a test request"
                        + (r.statusCode() == 404 ? " - check the path" : "") + ". The first batch confirms the rest." + token;
            }
        }
    }

    private boolean oauth() {
        return cfg.auth != null && "oauth2".equalsIgnoreCase(cfg.auth.type);
    }

    private HttpRequest.Builder authorized(URI uri, boolean newToken) throws Exception {
        HttpRequest.Builder req = proxied(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(cfg.timeoutMs))
                .header("User-Agent", "BIMBA-IT-OT-Agent/1.4"));
        for (Map.Entry<String, String> h : cfg.headers.entrySet()) {
            req.header(h.getKey(), h.getValue());
        }
        BimbaConfig.Auth a = cfg.auth;
        if (a == null) {
            return req;
        }
        switch (a.type == null ? "none" : a.type.toLowerCase()) {
            case "basic" -> req.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((a.username + ":" + a.password).getBytes(StandardCharsets.UTF_8)));
            case "bearer" -> req.header("Authorization", "Bearer " + a.token);
            case "token" -> req.header("Authorization", (a.scheme == null || a.scheme.isBlank() ? "Bearer" : a.scheme) + " " + a.token);
            case "oauth2" -> req.header("Authorization", "Bearer " + oauthToken(newToken));
            default -> {
                // none: headers only
            }
        }
        return req;
    }

    /** OAuth2 client credentials: cached until 60 s before it expires */
    private synchronized String oauthToken(boolean renew) throws Exception {
        long now = System.currentTimeMillis();
        if (!renew && oauthToken != null && now < oauthExpiresAt) {
            return oauthToken;
        }
        BimbaConfig.Auth a = cfg.auth;
        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        if (a.scope != null && !a.scope.isBlank()) {
            form.append("&scope=").append(URLEncoder.encode(a.scope, StandardCharsets.UTF_8));
        }
        if (a.audience != null && !a.audience.isBlank()) {
            form.append("&audience=").append(URLEncoder.encode(a.audience, StandardCharsets.UTF_8));
        }
        // client_secret_basic (RFC 6749 2.3.1): the id and secret are form-encoded first
        String basic = URLEncoder.encode(a.clientId, StandardCharsets.UTF_8) + ":"
                + URLEncoder.encode(a.clientSecret == null ? "" : a.clientSecret, StandardCharsets.UTF_8);
        HttpRequest req = proxied(HttpRequest.newBuilder(URI.create(a.tokenUrl)))
                .timeout(Duration.ofMillis(cfg.timeoutMs))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new java.io.IOException("OAuth2 token request: " + detail(r));
        }
        JsonObject t = Json.GSON.fromJson(r.body(), JsonObject.class);
        String token = t == null ? "" : Json.str(t, "access_token");
        if (token.isEmpty()) {
            throw new java.io.IOException("OAuth2 token request: no access_token in the answer");
        }
        long life = t.has("expires_in") ? t.get("expires_in").getAsLong() : 300;
        oauthToken = token;
        oauthExpiresAt = now + Math.max(10, life - 60) * 1000L;
        return token;
    }

    private static byte[] gzip(byte[] in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, in.length / 4));
        try (GZIPOutputStream z = new GZIPOutputStream(out)) {
            z.write(in);
        }
        return out.toByteArray();
    }

    private static String firstReason(String body) {
        String r = field(body, "reason");
        return r.isEmpty() ? body.substring(0, Math.min(200, body.length())) : r;
    }

    /** first "key":"value" in a JSON text (enough for an error reason or a cluster name) */
    private static String field(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body == null ? "" : body);
        return m.find() ? m.group(1) : "";
    }

    private static String detail(HttpResponse<String> resp) {
        if (resp.statusCode() == 407) {
            return "HTTP 407: the proxy refused the user name or password";
        }
        String body = resp.body() == null ? "" : resp.body();
        return "HTTP " + resp.statusCode() + " " + body.substring(0, Math.min(200, body.length()));
    }

    @Override
    public String endpoint() {
        return cfg.url;
    }
}
