package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import java.util.concurrent.atomic.AtomicLong;

/** An end system (Boomi, Kafka, MQTT, HTTP receivers, or the BIMBA database built into Setu), with a simple circuit breaker. */
public abstract class Destination implements AutoCloseable {

    /** Thrown when the end system says the batch itself is invalid; retrying it would never succeed. */
    public static class RejectedException extends Exception {
        public RejectedException(String msg) {
            super(msg);
        }
    }

    /**
     * The end system is busy and said when to come back (HTTP 429 / 503 with Retry-After, FR-END-01): the destination
     * waits exactly that long (at most an hour) instead of its own backoff; seconds 0 = it did not say (normal backoff).
     */
    public static class RetryLaterException extends java.io.IOException {
        public final long seconds;

        public RetryLaterException(String msg, long seconds) {
            super(msg);
            this.seconds = seconds;
        }
    }

    /** longest wait a Retry-After answer can impose */
    static final long MAX_RETRY_AFTER_SECONDS = 3600;

    protected final BimbaConfig.Destination cfg;
    /** this destination's own layout of a reading, or null = the BIMBA message as it is */
    protected final Template template;
    /** message format major version it receives: 1 (camelCase) or 2 (BRD canonical envelope, snake_case) */
    protected final int major;
    /** what each reading must look like as it is sent (null = no check) */
    private final com.networknt.schema.JsonSchema outputSchema;
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
        this.major = "2".equals(cfg.schemaVersion) && !"database".equalsIgnoreCase(cfg.transport) ? 2 : 1;
        com.networknt.schema.JsonSchema s = null;
        if (cfg.outputSchema != null && !cfg.outputSchema.isBlank()) {
            try {
                s = com.bimba.setu.canonical.MessageCheck.compile(cfg.outputSchema);
            } catch (IllegalArgumentException e) {
                LoggerFactory.getLogger("BIMBA.Delivery").warn("destination {}: output schema ignored, {}", cfg.name, e.getMessage());
            }
        }
        this.outputSchema = s;
    }

    /**
     * FR-RTE-03: every reading as this destination sends it must match its output schema; otherwise the whole batch is
     * refused (kept with the reason, sent again after the template or schema is fixed) and nothing is sent.
     */
    void checkOutput(byte[] batch) throws RejectedException {
        if (outputSchema == null) {
            return;
        }
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        java.util.List<String> bad = new java.util.ArrayList<>();
        int failing = 0;
        for (JsonElement e : env.getAsJsonArray("messages")) {
            JsonObject m = e.getAsJsonObject();
            java.util.List<String> p = com.bimba.setu.canonical.MessageCheck.problems(outputSchema, layout(m, env), 3);
            if (!p.isEmpty()) {
                failing++;
                if (bad.size() < 3) {
                    bad.add(Json.str(m, "machineId") + "/" + Json.str(m, "tagName") + ": " + String.join("; ", p));
                }
            }
        }
        if (failing > 0) {
            throw new RejectedException(failing + " reading(s) do not match this destination's output schema, e.g. "
                    + String.join(" | ", bad));
        }
    }

    /** a reading as this destination sends it: its template, else the BIMBA message in its format version */
    protected JsonElement layout(JsonObject message, JsonObject envelope) {
        if (template != null) {
            return template.render(message, envelope);
        }
        return major == 2 ? com.bimba.setu.canonical.CanonicalV2.message(message) : message;
    }

    /** readings are sent differently from Setu's own 1.x layout (template or format 2) */
    protected boolean mapsReadings() {
        return template != null || major == 2;
    }

    /**
     * The batch envelope as this destination sends it: every reading in its layout; format 2 = the BRD batch form
     * {schema_version, batch_id, plant_id, ..., records:[..]}. Unchanged for format 1 without a template.
     */
    protected byte[] mappedEnvelope(byte[] batch) {
        if (!mapsReadings()) {
            return batch;
        }
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        JsonArray mapped = new JsonArray();
        for (JsonElement m : env.getAsJsonArray("messages")) {
            mapped.add(layout(m.getAsJsonObject(), env));
        }
        if (major == 2) {
            return Json.GSON.toJson(com.bimba.setu.canonical.CanonicalV2.envelope(env, mapped)).getBytes(StandardCharsets.UTF_8);
        }
        env.add("messages", mapped);
        return Json.GSON.toJson(env).getBytes(StandardCharsets.UTF_8);
    }

    /** one message of a queue destination: whose readings (machineId; tag and messageId per reading), the body, how many */
    protected record Part(String machineId, String tagName, String messageId, JsonObject first, byte[] body, int count) {
    }

    /**
     * A batch as the messages of a queue (JMS, AMQP): machine = one per machine (its readings as a batch in this
     * destination's format, keyed by the machine: FR-END-04), message = one per reading (its layout), batch = the whole batch.
     */
    protected java.util.List<Part> parts(byte[] batch, JsonObject env, String mode) {
        java.util.List<Part> out = new java.util.ArrayList<>();
        JsonArray all = env.getAsJsonArray("messages");
        if ("batch".equalsIgnoreCase(mode) || all.isEmpty()) {
            JsonObject first = all.isEmpty() ? new JsonObject() : all.get(0).getAsJsonObject();
            out.add(new Part(null, null, null, first, mappedEnvelope(batch), all.size()));
            return out;
        }
        if ("message".equalsIgnoreCase(mode)) {
            for (JsonElement e : all) {
                JsonObject m = e.getAsJsonObject();
                out.add(new Part(Json.str(m, "machineId"), Json.str(m, "tagName"), Json.str(m, "messageId"), m,
                        Json.GSON.toJson(layout(m, env)).getBytes(StandardCharsets.UTF_8), 1));
            }
            return out;
        }
        java.util.Map<String, JsonArray> byMachine = new java.util.LinkedHashMap<>();
        for (JsonElement e : all) {
            byMachine.computeIfAbsent(Json.str(e.getAsJsonObject(), "machineId"), k -> new JsonArray()).add(e);
        }
        for (java.util.Map.Entry<String, JsonArray> g : byMachine.entrySet()) {
            JsonObject sub = env.deepCopy();
            sub.add("messages", g.getValue());
            sub.addProperty("count", g.getValue().size());
            out.add(new Part(g.getKey(), null, null, g.getValue().get(0).getAsJsonObject(),
                    mappedEnvelope(Json.GSON.toJson(sub).getBytes(StandardCharsets.UTF_8)), g.getValue().size()));
        }
        return out;
    }

    /** a header / property name in this destination's format: batchId (1) or batch_id (2) */
    protected String key(String camel) {
        return major == 2 ? com.bimba.setu.canonical.CanonicalV2.snake(camel) : camel;
    }

    /** the schema version this destination's batches carry */
    protected String version(JsonObject envelope) {
        return major == 2 ? com.bimba.setu.canonical.CanonicalV2.VERSION : Json.str(envelope, "schemaVersion");
    }

    /** db: Setu's connection to the shared database, for the built-in BIMBA database destination (may be null) */
    public static Destination create(BimbaConfig.Destination cfg, String plantId, com.bimba.setu.Db db) {
        return create(cfg, plantId, db, null);
    }

    /**
     * workDir: where a file destination keeps its open window and its archive (the route's buffer folder); null = no
     * window (every batch is its own file at once) and no archive, as for the console's tests.
     */
    public static Destination create(BimbaConfig.Destination cfg, String plantId, com.bimba.setu.Db db, java.io.File workDir) {
        String t = cfg.transport == null ? "rest" : cfg.transport.toLowerCase(java.util.Locale.ROOT);
        return switch (t) {
            case "database" -> new DatabaseDestination(cfg, db);
            case "mqtt" -> new MqttDestination(cfg, plantId);
            case "kafka" -> new KafkaDestination(cfg, plantId);
            case "eventstreams" -> new EventStreamsDestination(cfg);
            case "sftp", "ftp" -> new FileDestination(cfg, plantId, workDir);
            case "mllp" -> new MllpDestination(cfg);
            case "jms" -> new JmsDestination(cfg, plantId);
            case "amqp" -> new AmqpDestination(cfg, plantId);
            case "jdbc" -> new JdbcDestination(cfg);
            default -> new RestDestination(cfg);
        };
    }

    /**
     * What the destination would send for this batch, as text, when it is not the JSON the console already shows
     * (CSV, XML, HL7, NDJSON, the SQL of a database): for the console's preview; null = nothing more to show.
     */
    protected String wire(byte[] batch) {
        return null;
    }

    /** the console's preview of a destination as the form shows it (not saved, nothing is connected); null = none */
    public static String preview(BimbaConfig.Destination cfg, String plantId, byte[] batch) {
        try (Destination d = create(cfg, plantId, null, null)) {
            return d.wire(batch);
        } catch (RuntimeException e) {
            return null;                                               // the form is not complete yet
        }
    }

    /** Sends one batch envelope. Returns normally only when the end system has accepted it. */
    public abstract void send(byte[] batch) throws Exception;

    /** Sends a batch, split into requests of at most maxBatchMessages readings when it is larger. */
    public void sendAll(byte[] batch) throws Exception {
        checkOutput(batch);
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
     * Connection and sign-in check without delivering data (Setu console "Test", standby health checks). Returns
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

    /** work the destination does on its own (a file window) went through again after failing */
    void markRecovered() {
        lastSuccessAt = System.currentTimeMillis();
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
        return "down".equals(state) || "throttled".equals(state);
    }

    /**
     * Busy (HTTP 429 / 503): with a Retry-After the next try is exactly then (at most an hour) and the backoff does
     * not grow; without one, the normal backoff. Either way the route meanwhile tries its next destination.
     */
    void markThrottled(String error, long retryAfterSeconds) {
        if (retryAfterSeconds <= 0) {
            markFailure(error);
        } else {
            failures.incrementAndGet();
            lastError = error;
            lastFailureAt = System.currentTimeMillis();
            retryAt = lastFailureAt + Math.min(MAX_RETRY_AFTER_SECONDS, retryAfterSeconds) * 1000L;
        }
        state = "throttled";
    }

    /** the Retry-After of an HTTP answer in seconds: a number, or an HTTP date; 0 = none or unreadable */
    static long retryAfter(java.util.Optional<String> header, long now) {
        if (header.isEmpty() || header.get().isBlank()) {
            return 0;
        }
        String v = header.get().trim();
        try {
            return Math.max(0, Long.parseLong(v));
        } catch (NumberFormatException ignored) {
            // an HTTP date
        }
        try {
            long at = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return Math.max(1, (at - now + 999) / 1000);
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
    }

    /** HTTP 429 / 503: busy; the answer's Retry-After (if any) is respected */
    static void throwIfBusy(java.net.http.HttpResponse<String> r, String who) throws RetryLaterException {
        int code = r.statusCode();
        if (code != 429 && code != 503) {
            return;
        }
        long wait = retryAfter(r.headers().firstValue("Retry-After"), System.currentTimeMillis());
        String body = r.body() == null ? "" : r.body().replaceAll("\\s+", " ");
        throw new RetryLaterException(who + " is busy (HTTP " + code + (wait > 0 ? ", Retry-After " + wait + " s" : "") + ")"
                + (body.isEmpty() ? "" : ": " + body.substring(0, Math.min(160, body.length()))), wait);
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
