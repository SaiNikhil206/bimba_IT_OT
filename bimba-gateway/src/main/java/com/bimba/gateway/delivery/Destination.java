package com.bimba.gateway.delivery;

import com.bimba.gateway.Json;
import com.bimba.gateway.config.BimbaConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import java.util.concurrent.atomic.AtomicLong;

/** An end system (Boomi, Kafka, MQTT, HTTP receivers, or the BIMBA database built into the gateway), with a simple circuit breaker. */
public abstract class Destination implements AutoCloseable {

    /** Thrown when the end system says the batch itself is invalid; retrying it would never succeed. */
    public static class RejectedException extends Exception {
        public RejectedException(String msg) {
            super(msg);
        }
    }

    protected final BimbaConfig.Destination cfg;
    /** this destination's own layout of a reading, or null = the BIMBA message as it is */
    protected final Template template;
    private volatile long retryAt = 0;
    private volatile String lastError;
    private volatile long lastSuccessAt;
    private volatile long lastFailureAt;
    private volatile long lastProbeAt;
    private volatile String state = "unknown";
    /** failures in a row: the retry wait doubles with each, up to maxRetryAfterSeconds */
    private volatile int consecutiveFailures;
    final AtomicLong batches = new AtomicLong();
    final AtomicLong messages = new AtomicLong();
    final AtomicLong failures = new AtomicLong();

    protected Destination(BimbaConfig.Destination cfg) {
        this.cfg = cfg;
        Template t = null;
        try {
            t = Template.compile(cfg.template);
        } catch (IllegalArgumentException e) {
            LoggerFactory.getLogger("BIMBA.Delivery").warn("destination {}: template ignored, {}", cfg.name, e.getMessage());
        }
        this.template = t;
    }

    /** a reading as this destination sends it */
    protected JsonElement layout(JsonObject message, JsonObject envelope) {
        return template == null ? message : template.render(message, envelope);
    }

    /** the batch envelope with every reading in this destination's layout (unchanged without a template) */
    protected byte[] mappedEnvelope(byte[] batch) {
        if (template == null) {
            return batch;
        }
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        JsonArray mapped = new JsonArray();
        for (JsonElement m : env.getAsJsonArray("messages")) {
            mapped.add(template.render(m.getAsJsonObject(), env));
        }
        env.add("messages", mapped);
        return Json.GSON.toJson(env).getBytes(StandardCharsets.UTF_8);
    }

    /** db: the gateway's connection to the shared database, for the built-in BIMBA database destination (may be null) */
    public static Destination create(BimbaConfig.Destination cfg, String plantId, com.bimba.gateway.Db db) {
        if ("database".equalsIgnoreCase(cfg.transport)) {
            return new DatabaseDestination(cfg, db);
        }
        if ("mqtt".equalsIgnoreCase(cfg.transport)) {
            return new MqttDestination(cfg, plantId);
        }
        if ("kafka".equalsIgnoreCase(cfg.transport)) {
            return new KafkaDestination(cfg, plantId);
        }
        if ("eventstreams".equalsIgnoreCase(cfg.transport)) {
            return new EventStreamsDestination(cfg);
        }
        return new RestDestination(cfg);
    }

    /** Sends one batch envelope. Returns normally only when the end system has accepted it. */
    public abstract void send(byte[] batch) throws Exception;

    /** Sends a batch, split into requests of at most maxBatchMessages readings when it is larger. */
    public void sendAll(byte[] batch) throws Exception {
        int max = cfg.maxBatchMessages;
        if (max <= 0) {
            send(batch);
            return;
        }
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        JsonArray all = env.getAsJsonArray("messages");
        if (all.size() <= max) {
            send(batch);
            return;
        }
        // receivers drop repeated readings by messageId, so a retry after a partly sent batch stores nothing twice
        for (int i = 0; i < all.size(); i += max) {
            JsonObject part = env.deepCopy();
            JsonArray slice = new JsonArray();
            for (int j = i; j < Math.min(all.size(), i + max); j++) {
                slice.add(all.get(j));
            }
            part.add("messages", slice);
            part.addProperty("count", slice.size());
            part.addProperty("part", (i / max + 1) + "/" + ((all.size() + max - 1) / max));
            send(Json.GSON.toJson(part).getBytes(StandardCharsets.UTF_8));
        }
    }

    public abstract String endpoint();

    /** True when this destination is health-checked while on standby (config "healthCheck", on by default). */
    public boolean canProbe() {
        return cfg.healthCheck;
    }

    /** Health check without delivering data: the connection test. Returns normally only when the end system is ready. */
    public void probe() throws Exception {
        test();
    }

    /**
     * Connection and sign-in check without delivering data (gateway console "Test", standby health checks). Returns
     * what was checked; throws with the reason when the destination cannot be reached or rejects the credentials.
     */
    public abstract String test() throws Exception;

    /** adds the proxy sign-in (Basic, sent up front; also used for the CONNECT of HTTPS) when the proxy needs one */
    protected java.net.http.HttpRequest.Builder proxied(java.net.http.HttpRequest.Builder b) {
        if (cfg.proxy != null && !cfg.proxy.isBlank() && cfg.proxyUsername != null && !cfg.proxyUsername.isBlank()) {
            String pair = cfg.proxyUsername + ":" + (cfg.proxyPassword == null ? "" : cfg.proxyPassword);
            b.header("Proxy-Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        }
        return b;
    }

    /** HTTP client with this destination's timeout, certificates (tlsCa / tlsCert) and proxy */
    protected java.net.http.HttpClient httpClient() {
        java.net.http.HttpClient.Builder b = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(cfg.timeoutMs))
                .version(java.net.http.HttpClient.Version.HTTP_1_1);
        try {
            javax.net.ssl.SSLContext ctx = TlsSupport.context(cfg.tlsCa, cfg.tlsCert, cfg.tlsKey);
            if (ctx != null) {
                b.sslContext(ctx);
            }
        } catch (Exception e) {
            throw new IllegalStateException("certificates of " + cfg.name + ": " + e.getMessage(), e);
        }
        if (cfg.proxy != null && !cfg.proxy.isBlank()) {
            java.net.URI u = java.net.URI.create(cfg.proxy.trim());
            b.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 8080)));
            // the proxy password goes in a Proxy-Authorization header (proxied()), not in an HttpClient Authenticator:
            // that one would also take over the servers' 401 answers (OAuth2 renewal, clear "credentials rejected")
        }
        return b.build();
    }

    /** HTTP answers that mean "reached, but not allowed in" or "broken" fail a connection test. */
    protected static void checkAccess(java.net.http.HttpResponse<String> r, String who) throws java.io.IOException {
        int code = r.statusCode();
        if (code == 407) {
            throw new java.io.IOException("The proxy refused the user name or password (HTTP 407).");
        }
        if (code == 401 || code == 403) {
            throw new java.io.IOException(who + " rejected the credentials (HTTP " + code + ").");
        }
        if (code >= 500) {
            String body = r.body() == null ? "" : r.body().replaceAll("\\s+", " ");
            throw new java.io.IOException(who + " answered HTTP " + code + " " + body.substring(0, Math.min(160, body.length())));
        }
    }

    public String name() {
        return cfg.name;
    }

    public boolean isPrimary() {
        return "primary".equalsIgnoreCase(cfg.role);
    }

    public boolean isAvailable(long now) {
        return now >= retryAt;
    }

    void markSuccess(int messageCount) {
        batches.incrementAndGet();
        messages.addAndGet(messageCount);
        lastSuccessAt = System.currentTimeMillis();
        retryAt = 0;
        consecutiveFailures = 0;
        state = "up";
    }

    /** Standby health check passed: reachable and ready, but nothing was delivered. */
    void markProbeOk() {
        lastProbeAt = System.currentTimeMillis();
        retryAt = 0;
        consecutiveFailures = 0;
        state = "up";
    }

    /** the end system refused a batch as invalid: it is reachable, the data is the problem */
    void markRejected(String error) {
        lastError = "rejected a batch: " + error;
        lastFailureAt = System.currentTimeMillis();
    }

    void markProbeFailure(String error) {
        lastProbeAt = System.currentTimeMillis();
        markFailure(error);
    }

    boolean isDown() {
        return "down".equals(state);
    }

    void markFailure(String error) {
        failures.incrementAndGet();
        lastError = error;
        lastFailureAt = System.currentTimeMillis();
        retryAt = lastFailureAt + retryDelaySeconds(++consecutiveFailures) * 1000L;
        state = "down";
    }

    /** retryAfterSeconds, doubled with every failure in a row, at most maxRetryAfterSeconds */
    long retryDelaySeconds(int failuresInARow) {
        long base = Math.max(1, cfg.retryAfterSeconds);
        long cap = Math.max(base, cfg.maxRetryAfterSeconds);
        return Math.min(cap, base << Math.min(20, Math.max(0, failuresInARow - 1)));
    }

    /** counters to keep over a restart */
    java.util.Map<String, Object> counters() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("batches", batches.get());
        m.put("messages", messages.get());
        m.put("failures", failures.get());
        m.put("lastSuccessAt", lastSuccessAt);
        m.put("lastFailureAt", lastFailureAt);
        return m;
    }

    void restore(JsonObject c) {
        batches.set(c.has("batches") ? c.get("batches").getAsLong() : 0);
        messages.set(c.has("messages") ? c.get("messages").getAsLong() : 0);
        failures.set(c.has("failures") ? c.get("failures").getAsLong() : 0);
        lastSuccessAt = c.has("lastSuccessAt") ? c.get("lastSuccessAt").getAsLong() : 0;
        lastFailureAt = c.has("lastFailureAt") ? c.get("lastFailureAt").getAsLong() : 0;
    }

    public java.util.Map<String, Object> status() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", cfg.name);
        m.put("role", cfg.role);
        m.put("transport", cfg.transport);
        m.put("endpoint", endpoint());
        m.put("state", state);
        m.put("batches", batches.get());
        m.put("messages", messages.get());
        m.put("failures", failures.get());
        m.put("lastSuccessAt", lastSuccessAt == 0 ? null : java.time.Instant.ofEpochMilli(lastSuccessAt).toString());
        m.put("lastFailureAt", lastFailureAt == 0 ? null : java.time.Instant.ofEpochMilli(lastFailureAt).toString());
        m.put("lastError", lastError);
        m.put("retryAt", retryAt == 0 ? null : java.time.Instant.ofEpochMilli(retryAt).toString());
        m.put("consecutiveFailures", consecutiveFailures);
        m.put("probe", canProbe());
        m.put("lastProbeAt", lastProbeAt == 0 ? null : java.time.Instant.ofEpochMilli(lastProbeAt).toString());
        return m;
    }

    @Override
    public void close() {
    }
}
