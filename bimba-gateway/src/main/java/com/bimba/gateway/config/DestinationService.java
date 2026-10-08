package com.bimba.gateway.config;

import com.bimba.gateway.GatewayService;
import com.bimba.gateway.Json;
import com.bimba.gateway.delivery.Destination;
import com.bimba.gateway.security.SecurityService.ApiException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Destinations as the gateway console manages them (stored by {@link DestinationStore}): connections only, they get
 * machine data when a pipeline uses them. Validates what the console sends, keeps keys it does not know, masks secrets
 * on the way out ({@link #MASK}; sending the mask back keeps the stored value), encrypts new ones, applies changes at once.
 */
@Service
public class DestinationService {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Destinations");
    public static final String MASK = "********";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private static final Pattern SERVICE = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Map<String, List<String>> FIELDS = Map.of(
            "rest", List.of("url", "format", "headers", "auth", "probe", "probeEverySeconds", "template"),
            "eventstreams", List.of("url", "auth", "esMode", "esApi", "template"),
            "mqtt", List.of("brokerUrl", "topic", "qos", "clientId", "username", "password", "mqttMode", "template"),
            "kafka", List.of("bootstrapServers", "topic", "kafkaMode", "securityProtocol", "saslMechanism", "username", "password",
                    "clientId", "kafka", "template"),
            "database", List.of());
    private static final Set<String> FORMATS = Set.of("envelope", "ndjson", "json-array", "splunk-hec", "elastic-bulk", "influx-line");
    /** keys this editor owns; any other key of a destination is kept when it is saved */
    private static final Set<String> MANAGED = new HashSet<>(List.of("id", "uid", "name", "service", "role", "enabled", "transport", "timeoutMs",
            "retryAfterSeconds", "status", "usedBy", "live", "tlsInfo", "healthCheck", "maxRetryAfterSeconds", "maxBatchMessages",
            "tlsCa", "tlsCert", "tlsKey", "proxy", "proxyUsername", "proxyPassword", "compress", "retain", "maxInflight"));

    static {
        FIELDS.values().forEach(MANAGED::addAll);
    }

    private final GatewayService gateway;
    private final DestinationStore store;
    private final com.bimba.gateway.Db db;

    public DestinationService(GatewayService gateway, DestinationStore store, com.bimba.gateway.Db db) {
        this.gateway = gateway;
        this.store = store;
        this.db = db;
    }

    /**
     * Every destination, secrets masked, with the pipelines that use it (usedBy: id, name, role, active) and its live
     * state in each active pipeline (live: pipeline, role, state, messages, lastSuccessAt, lastError).
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> list(long orgId) {
        Map<String, Object> status = gateway.status();
        List<JsonObject> pipelines = store.pipelines(orgId);
        java.util.Set<Long> mine = new java.util.HashSet<>();
        pipelines.forEach(p -> mine.add(p.get("id").getAsLong()));
        Map<String, List<Object>> live = new LinkedHashMap<>();
        if (status.get("delivery") instanceof Map<?, ?> d && d.get("destinations") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> s = (Map<String, Object>) o;
                // only the live state in this organization's pipelines (another organization can use the same name)
                if (s.get("pipelineId") instanceof Number n && mine.contains(n.longValue())) {
                    live.computeIfAbsent(String.valueOf(s.get("name")).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(s);
                }
            }
        }
        Map<Long, List<Object>> usedBy = usage(pipelines);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonObject row : store.list(orgId)) {
            JsonObject d = row.deepCopy();
            DestinationStore.secrets(d, v -> v.startsWith("${") && v.endsWith("}") ? v : MASK);
            Map<String, Object> m = (Map<String, Object>) Json.toJava(Json.GSON.toJson(d));
            m.put("id", row.get("id").getAsLong());
            m.put("usedBy", usedBy.getOrDefault(row.get("id").getAsLong(), List.of()));
            m.put("live", live.getOrDefault(Json.str(d, "name").toLowerCase(Locale.ROOT), List.of()));
            Map<String, Object> tls = new LinkedHashMap<>();
            if (com.bimba.gateway.delivery.TlsSupport.has(Json.str(row, "tlsCa"))) {
                tls.put("ca", com.bimba.gateway.delivery.TlsSupport.describe(Json.str(row, "tlsCa")));
            }
            if (com.bimba.gateway.delivery.TlsSupport.has(Json.str(row, "tlsCert"))) {
                tls.put("cert", com.bimba.gateway.delivery.TlsSupport.describe(Json.str(row, "tlsCert")));
            }
            m.put("tlsInfo", tls);
            out.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("destinations", out);
        m.put("mask", MASK);
        m.put("source", status.get("destinationSource"));
        m.put("problems", status.get("destinationProblems"));
        return m;
    }

    /** destination id -> [{id, uid, name, role, active}] of the pipelines that use it */
    static Map<Long, List<Object>> usage(List<JsonObject> pipelines) {
        Map<Long, List<Object>> out = new LinkedHashMap<>();
        for (JsonObject p : pipelines) {
            java.util.function.BiConsumer<JsonElement, String> use = (ref, role) -> {
                if (ref != null && ref.isJsonPrimitive() && ref.getAsJsonPrimitive().isNumber()) {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("id", p.get("id").getAsLong());
                    u.put("uid", Json.str(p, "uid"));
                    u.put("name", Json.str(p, "name"));
                    u.put("role", role);
                    u.put("active", p.get("active").getAsBoolean());
                    out.computeIfAbsent(ref.getAsLong(), k -> new ArrayList<>()).add(u);
                }
            };
            use.accept(p.get("primary"), "primary");
            if (p.has("secondary") && p.get("secondary").isJsonArray()) {
                p.getAsJsonArray("secondary").forEach(e -> use.accept(e, "secondary"));
            }
            if (p.has("mirrors") && p.get("mirrors").isJsonArray()) {
                p.getAsJsonArray("mirrors").forEach(e -> use.accept(e, "mirror"));
            }
        }
        return out;
    }

    /** Adds (id null) or replaces a destination; returns {id, name}. */
    public synchronized Map<String, Object> save(long orgId, Long id, JsonObject in, String by) throws ApiException {
        List<JsonObject> rows = store.list(orgId);
        JsonObject old = id == null ? new JsonObject() : find(rows, id);
        JsonObject d = build(in, old);
        String name = Json.str(d, "name");
        for (JsonObject r : rows) {
            boolean other = id == null || r.get("id").getAsLong() != id;
            if (Json.str(r, "name").equalsIgnoreCase(name) && other) {
                throw new ApiException(409, "name", "A destination named " + name + " already exists.");
            }
            if (other && "database".equals(Json.str(d, "transport")) && "database".equals(Json.str(r, "transport"))) {
                throw new ApiException(409, "transport", "The BIMBA database is already a destination (" + Json.str(r, "name")
                        + "): use that one in the pipelines.");
            }
        }
        store.seal(d);
        long saved;
        if (id == null) {
            saved = store.insert(orgId, d, by);
        } else {
            store.update(orgId, id, d, by);
            saved = id;
        }
        gateway.reloadNow();
        return Map.of("id", saved, "name", name);
    }

    /** Refused while a pipeline uses it: the pipelines are named so they can be changed first. Returns the name. */
    public synchronized String delete(long orgId, long id) throws ApiException {
        String name = Json.str(find(store.list(orgId), id), "name");
        List<Object> users = usage(store.pipelines(orgId)).getOrDefault(id, List.of());
        if (!users.isEmpty()) {
            List<String> names = users.stream().map(u -> "\"" + ((Map<?, ?>) u).get("name") + "\"").distinct().toList();
            throw new ApiException(409, "in_use", name + " is used by the pipeline" + (names.size() > 1 ? "s " : " ")
                    + String.join(", ", names) + ". Remove it from " + (names.size() > 1 ? "them" : "it") + " first.");
        }
        store.delete(orgId, id);
        gateway.reloadNow();
        return name;
    }

    /**
     * Checks the connection and sign-in of a destination as the form shows it (saved or not) without delivering data:
     * MQTT connects, Kafka reads the topic's partitions, the built-in BIMBA database checks its connection and tables,
     * HTTP receivers with the BIMBA health check answer it, other HTTP receivers must answer without rejecting the credentials.
     */
    public Map<String, Object> test(long orgId, Long originalId, JsonObject in) throws ApiException {
        JsonObject form = in.deepCopy();
        if (Json.str(form, "name").isBlank()) {
            form.addProperty("name", "test");
        }
        JsonObject old = originalId == null ? new JsonObject() : find(store.list(orgId), originalId);
        JsonObject d;
        try {
            d = store.open(build(form, old));
        } catch (IllegalStateException e) {
            return result(false, e.getMessage(), 0);
        }
        BimbaConfig c = new BimbaConfig();
        BimbaConfig running = gateway.config();
        c.plantId = running == null ? c.plantId : running.plantId;
        c.destinations = new ArrayList<>(List.of(Json.GSON.fromJson(d, BimbaConfig.Destination.class)));
        c.resolveEnv();
        long started = System.currentTimeMillis();
        try (Destination dest = Destination.create(c.destinations.get(0), c.plantId, db)) {
            String message = dest.test();
            return result(true, message, System.currentTimeMillis() - started);
        } catch (Exception e) {
            String msg = describe(e);
            log.info("connection test of {} failed: {}", Json.str(d, "name"), msg);
            return result(false, msg, System.currentTimeMillis() - started);
        }
    }

    /** A failed test in plain words: the network reason of the root cause, plus the client's own message if it says more. */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root && !KNOWN.contains(root.getClass().getSimpleName())) {
            root = root.getCause();
        }
        String rm = root.getMessage() == null ? "" : root.getMessage();
        String reason = switch (root.getClass().getSimpleName()) {
            case "UnknownHostException" -> "Host not found: " + rm;
            case "ConnectException", "ClosedChannelException" -> "Connection refused or closed - check the address and port";
            case "SocketTimeoutException", "HttpConnectTimeoutException", "HttpTimeoutException", "TimeoutException" ->
                    "No answer in time" + (rm.isEmpty() ? "" : " (" + rm + ")");
            case "SSLHandshakeException", "SSLException", "CertPathBuilderException", "ValidatorException" -> tls(rm);
            default -> rm.isEmpty() ? root.getClass().getSimpleName() : rm;
        };
        if (rm.contains("PKIX path") || rm.contains("certificate_required") || rm.contains("bad_certificate") || rm.contains("No subject alternative")) {
            return tls(rm);                            // also when the client library wraps it in its own exception
        }
        String top = e.getMessage();
        if (root != e && top != null && !top.isBlank() && !top.equals(e.getClass().getSimpleName()) && !top.contains(rm) && !reason.contains(top)) {
            return top + ": " + reason;
        }
        return reason;
    }

    /** TLS failures in plain words, with what to do */
    private static String tls(String m) {
        if (m.contains("PKIX path") || m.contains("unable to find valid certification path")) {
            return "The server's certificate is not trusted (self-signed or a company authority): add that authority under Certificates.";
        }
        if (m.contains("certificate_required")) {
            return "The server requires a client certificate: add it and its private key under Certificates.";
        }
        if (m.contains("bad_certificate") || m.contains("unknown_ca") || m.contains("certificate_unknown")) {
            return "The server refused the client certificate (not signed by an authority it trusts, or expired).";
        }
        if (m.contains("No subject alternative") || m.contains("No name matching")) {
            return "The server's certificate is for another name than the address used: " + m;
        }
        if (m.contains("expired") || m.contains("NotAfter")) {
            return "A certificate has expired: " + m;
        }
        return "TLS problem: " + m;
    }

    private static final Set<String> KNOWN = Set.of("UnknownHostException", "ConnectException", "ClosedChannelException",
            "SocketTimeoutException", "HttpConnectTimeoutException", "HttpTimeoutException", "SSLHandshakeException", "SSLException",
            "CertPathBuilderException", "ValidatorException");

    /** What a template makes of a sample reading (and of a sample average), before it is saved. */
    public Map<String, Object> preview(JsonObject in) throws ApiException {
        com.bimba.gateway.delivery.Template t;
        try {
            t = com.bimba.gateway.delivery.Template.compile(str(in, "template"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "template", e.getMessage());
        }
        BimbaConfig c = gateway.config();
        JsonObject env = new JsonObject();
        env.addProperty("batchId", "5f0c6b1e-8f4e-4c55-9d7a-2b9d1d0c7e11");
        env.addProperty("plantId", c == null ? "PLANT01" : c.plantId);
        env.addProperty("edgeId", c == null ? "bimba-gw01" : c.edgeId);
        JsonObject m = com.google.gson.JsonParser.parseString("""
                {"schemaVersion":"1.0","messageId":"0b6c2d6e-3a54-4a8a-9c0e-6f1d2f3a4b5c","plantId":"%s","machineId":"PUMP-P1",
                 "timestamp":"2026-10-07T10:15:30.250Z","tagName":"Flow","tagValue":42.7,"dataType":"number","unit":"m3/h",
                 "quality":"GOOD","category":"telemetry","source":{"protocol":"modbus","system":"%s","address":"PLC/40001"},
                 "edge":{"edgeId":"%s","session":"a1b2c3","seq":1842}}""".formatted(Json.str(env, "plantId"), Json.str(env, "edgeId"),
                Json.str(env, "edgeId"))).getAsJsonObject();
        Map<String, Object> out = new LinkedHashMap<>();
        // a second reading of another type, for the batch example
        JsonObject m2 = m.deepCopy();
        m2.addProperty("messageId", "7d1e4f2a-0c9b-4e3d-8a6f-1b2c3d4e5f60");
        m2.addProperty("tagName", "running");
        m2.addProperty("tagValue", true);
        m2.addProperty("dataType", "boolean");
        m2.addProperty("unit", "");
        m2.getAsJsonObject("source").addProperty("address", "PLC/00001");
        m2.getAsJsonObject("edge").addProperty("seq", 1843);
        JsonObject batch = new JsonObject();
        batch.addProperty("schemaVersion", "1.0");
        batch.addProperty("batchId", Json.str(env, "batchId"));
        batch.addProperty("plantId", Json.str(env, "plantId"));
        batch.addProperty("edgeId", Json.str(env, "edgeId"));
        batch.addProperty("source", "bimba-gateway");
        batch.addProperty("createdAt", "2026-10-07T10:15:31.000Z");
        batch.addProperty("count", 2);
        com.google.gson.JsonArray readings = new com.google.gson.JsonArray();
        readings.add(t == null ? m : t.render(m, env));
        readings.add(t == null ? m2 : t.render(m2, env));
        batch.add("messages", readings);
        // as JSON text: numbers stay exactly as they are sent (no 1.79E12 for a millisecond timestamp)
        out.put("message", Json.PRETTY.toJson(m));
        out.put("rendered", Json.PRETTY.toJson(t == null ? m : t.render(m, env)));
        out.put("batch", Json.PRETTY.toJson(batch));
        out.put("fields", com.bimba.gateway.delivery.Template.FIELDS);
        return out;
    }

    /**
     * Sends ONE test reading (machine BIMBA-TEST, tag connection_test, category test) through the destination as the form
     * shows it: the end-to-end check that Test connection does not do. The receiver stores it like a real reading.
     */
    public Map<String, Object> sendTest(long orgId, Long originalId, JsonObject in) throws ApiException {
        JsonObject form = in.deepCopy();
        if (Json.str(form, "name").isBlank()) {
            form.addProperty("name", "test");
        }
        JsonObject old = originalId == null ? new JsonObject() : find(store.list(orgId), originalId);
        JsonObject d;
        try {
            d = store.open(build(form, old));
        } catch (IllegalStateException e) {
            return result(false, e.getMessage(), 0);
        }
        BimbaConfig c = new BimbaConfig();
        BimbaConfig running = gateway.config();
        c.plantId = running == null ? c.plantId : running.plantId;
        c.edgeId = running == null ? c.edgeId : running.edgeId;
        c.destinations = new ArrayList<>(List.of(Json.GSON.fromJson(d, BimbaConfig.Destination.class)));
        c.resolveEnv();
        String now = java.time.Instant.now().toString();
        JsonObject m = new JsonObject();
        m.addProperty("schemaVersion", "1.0");
        m.addProperty("messageId", java.util.UUID.randomUUID().toString());
        m.addProperty("plantId", c.plantId);
        m.addProperty("machineId", "BIMBA-TEST");
        m.addProperty("timestamp", now);
        m.addProperty("tagName", "connection_test");
        m.addProperty("tagValue", 1.0);
        m.addProperty("dataType", "number");
        m.addProperty("unit", "");
        m.addProperty("quality", "GOOD");
        m.addProperty("category", "test");
        JsonObject src = new JsonObject();
        src.addProperty("protocol", "test");
        src.addProperty("system", c.edgeId);
        src.addProperty("address", "gateway console");
        m.add("source", src);
        JsonObject edge = new JsonObject();
        edge.addProperty("edgeId", c.edgeId);
        edge.addProperty("session", "test");
        m.add("edge", edge);
        JsonObject env = new JsonObject();
        env.addProperty("schemaVersion", "1.0");
        env.addProperty("batchId", java.util.UUID.randomUUID().toString());
        env.addProperty("plantId", c.plantId);
        env.addProperty("edgeId", c.edgeId);
        env.addProperty("source", "bimba-gateway");
        env.addProperty("createdAt", now);
        env.addProperty("count", 1);
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        a.add(m);
        env.add("messages", a);
        long started = System.currentTimeMillis();
        try (Destination dest = Destination.create(c.destinations.get(0), c.plantId, db)) {
            dest.sendAll(Json.GSON.toJson(env).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return result(true, "The test reading was accepted (machine BIMBA-TEST, tag connection_test, messageId "
                    + Json.str(m, "messageId") + ").", System.currentTimeMillis() - started);
        } catch (Destination.RejectedException e) {
            return result(false, "Refused as invalid: " + e.getMessage(), System.currentTimeMillis() - started);
        } catch (Exception e) {
            return result(false, describe(e), System.currentTimeMillis() - started);
        }
    }

    private static Map<String, Object> result(boolean ok, String message, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("message", message);
        m.put("ms", ms);
        return m;
    }

    private static JsonObject find(List<JsonObject> rows, long id) throws ApiException {
        for (JsonObject r : rows) {
            if (r.get("id").getAsLong() == id) {
                return r;
            }
        }
        throw new ApiException(404, "not_found", "This destination no longer exists. Reload the page.");
    }

    // ------------------------------------------------------------------ validation

    private JsonObject build(JsonObject in, JsonObject old) throws ApiException {
        String name = str(in, "name").trim();
        if (!NAME.matcher(name).matches()) {
            throw new ApiException(400, "name", "Name: 1-64 letters, digits and . _ - (starting with a letter or digit).");
        }
        String transport = str(in, "transport").toLowerCase(Locale.ROOT);
        if (!FIELDS.containsKey(transport)) {
            throw new ApiException(400, "transport", "Choose how to deliver: REST, Boomi Event Streams, MQTT, Kafka or the BIMBA database.");
        }
        String service = str(in, "service").trim();
        if (!service.isEmpty() && !SERVICE.matcher(service).matches()) {
            throw new ApiException(400, "service", "Unknown service " + service + ".");
        }

        JsonObject d = new JsonObject();
        for (Map.Entry<String, JsonElement> e : old.entrySet()) {
            if (!MANAGED.contains(e.getKey())) {
                d.add(e.getKey(), e.getValue());
            }
        }
        d.addProperty("name", name);
        if (!service.isEmpty()) {
            d.addProperty("service", service);
        }
        d.addProperty("transport", transport);
        d.addProperty("timeoutMs", integer(in, "timeoutMs", 5000, 100, 120_000, "Timeout"));
        d.addProperty("retryAfterSeconds", integer(in, "retryAfterSeconds", 15, 1, 86_400, "Retry after"));

        d.addProperty("healthCheck", !in.has("healthCheck") || in.get("healthCheck").getAsBoolean());
        int retry = d.get("retryAfterSeconds").getAsInt();
        d.addProperty("maxRetryAfterSeconds", Math.max(retry, integer(in, "maxRetryAfterSeconds", Math.max(retry, 300), 1, 86_400, "Longest retry wait")));
        d.addProperty("maxBatchMessages", integer(in, "maxBatchMessages", 0, 0, 100_000, "Readings per request"));

        // certificates (PEM): authorities to trust, client certificate + key (a secret)
        String ca = str(in, "tlsCa").trim();
        String cert = str(in, "tlsCert").trim();
        String key = secret(in, old, "tlsKey", "private key of the client certificate").trim();
        try {
            boolean sealedKey = com.bimba.gateway.config.SecretBox.isSealed(key);
            com.bimba.gateway.delivery.TlsSupport.validate(ca, sealedKey ? "" : cert, sealedKey ? "" : key);
            if (sealedKey && !com.bimba.gateway.delivery.TlsSupport.has(cert)) {
                throw new IllegalArgumentException("Certificate sign-in needs both the client certificate and its private key.");
            }
            if (sealedKey) {
                com.bimba.gateway.delivery.TlsSupport.certificates(cert);
            }
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "tls", e.getMessage());
        }
        optional(d, "tlsCa", ca.isEmpty() ? null : ca + "\n");
        optional(d, "tlsCert", cert.isEmpty() ? null : cert + "\n");
        optional(d, "tlsKey", key.isEmpty() ? null : (com.bimba.gateway.config.SecretBox.isSealed(key) ? key : key + "\n"));

        String template = str(in, "template").trim();
        if (!template.isEmpty()) {
            try {
                com.bimba.gateway.delivery.Template.compile(template);
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, "template", e.getMessage());
            }
            if ("database".equals(transport)) {
                throw new ApiException(400, "template", "The BIMBA database stores the BIMBA standard message; remove the template.");
            }
            if ("influx-line".equals(str(in, "format"))) {
                throw new ApiException(400, "template", "InfluxDB uses its own line format; remove the template.");
            }
            d.addProperty("template", template);
        }

        switch (transport) {
            case "rest" -> {
                d.addProperty("url", url(in, "url", "http", "https"));
                d.addProperty("format", choice(in, "format", "envelope", FORMATS, "Body format"));
                d.add("headers", map(in, "headers", old, "header"));
                JsonObject auth = auth(in, old, false);
                if (!"none".equals(str(auth, "type"))) {
                    d.add("auth", auth);
                }
                d.addProperty("probe", in.has("probe") && in.get("probe").getAsBoolean());
                d.addProperty("compress", in.has("compress") && in.get("compress").getAsBoolean());
                proxy(in, old, d);
                d.addProperty("probeEverySeconds", integer(in, "probeEverySeconds", 30, 5, 3600, "Health check interval"));
            }
            case "eventstreams" -> {
                d.addProperty("url", url(in, "url", "http", "https"));
                d.add("auth", auth(in, old, true));
                d.addProperty("esMode", choice(in, "esMode", "batch", Set.of("batch", "message"), "Messages"));
                d.addProperty("esApi", choice(in, "esApi", "auto", Set.of("auto", "single", "multi"), "API"));
                proxy(in, old, d);
            }
            case "mqtt" -> {
                d.addProperty("brokerUrl", url(in, "brokerUrl", "tcp", "ssl", "ws", "wss"));
                String mqttMode = choice(in, "mqttMode", "batch", Set.of("batch", "message"), "Publish");
                String topic = required(in, "topic", "Topic");
                String perReading = topic.replace("{plantId}", "");
                if ("batch".equals(mqttMode) && perReading.matches(".*\\{[A-Za-z].*")) {
                    throw new ApiException(400, "topic", "Only {plantId} works in the topic of one message per batch;"
                            + " choose one message per reading to use {machineId}, {tagName}, ...");
                }
                if (topic.contains("+") || topic.contains("#")) {
                    throw new ApiException(400, "topic", "A topic to publish to cannot contain + or #.");
                }
                d.addProperty("mqttMode", mqttMode);
                d.addProperty("retain", in.has("retain") && in.get("retain").getAsBoolean());
                d.addProperty("maxInflight", integer(in, "maxInflight", 100, 1, 1000, "Messages in flight"));
                d.addProperty("topic", topic);
                d.addProperty("qos", integer(in, "qos", 1, 0, 2, "QoS"));
                optional(d, "clientId", str(in, "clientId").trim());
                optional(d, "username", str(in, "username").trim());
                optional(d, "password", secret(in, old, "password", "password"));
            }
            case "database" -> {
                // built in: the gateway's own connection to the shared database (BIMBA_DB_*), nothing to fill in
            }
            default -> {
                d.addProperty("bootstrapServers", required(in, "bootstrapServers", "Bootstrap servers").replaceAll("\\s+", ""));
                d.addProperty("topic", required(in, "topic", "Topic"));
                d.addProperty("kafkaMode", choice(in, "kafkaMode", "batch", Set.of("batch", "message"), "Records"));
                String protocol = str(in, "securityProtocol").trim().toUpperCase(Locale.ROOT);
                if (protocol.isEmpty()) {
                    protocol = "PLAINTEXT";
                }
                if (!Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL").contains(protocol)) {
                    throw new ApiException(400, "securityProtocol", "Security must be PLAINTEXT, SSL, SASL_PLAINTEXT or SASL_SSL.");
                }
                d.addProperty("securityProtocol", protocol);
                if (protocol.startsWith("SASL")) {
                    String mech = str(in, "saslMechanism").trim().toUpperCase(Locale.ROOT);
                    if (!Set.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512").contains(mech)) {
                        throw new ApiException(400, "saslMechanism", "SASL mechanism must be PLAIN, SCRAM-SHA-256 or SCRAM-SHA-512.");
                    }
                    d.addProperty("saslMechanism", mech);
                    d.addProperty("username", required(in, "username", "User name / API key"));
                    d.addProperty("password", nonEmpty(secret(in, old, "password", "password"), "Password / secret"));
                }
                optional(d, "clientId", str(in, "clientId").trim());
                d.add("kafka", map(in, "kafka", old, "Kafka property"));
            }
        }
        return d;
    }

    private static JsonObject auth(JsonObject in, JsonObject old, boolean bearerOnly) throws ApiException {
        JsonObject a = obj(in, "auth");
        JsonObject oldAuth = obj(old, "auth");
        String type = bearerOnly ? "bearer" : str(a, "type").toLowerCase(Locale.ROOT);
        JsonObject out = new JsonObject();
        switch (type.isEmpty() ? "none" : type) {
            case "none" -> out.addProperty("type", "none");
            case "basic" -> {
                out.addProperty("type", "basic");
                out.addProperty("username", required(a, "username", "User name"));
                out.addProperty("password", nonEmpty(secret(a, oldAuth, "password", "password"), "Password"));
            }
            case "bearer" -> {
                out.addProperty("type", "bearer");
                out.addProperty("token", nonEmpty(secret(a, oldAuth, "token", "token"), "Token"));
            }
            case "token" -> {
                String scheme = str(a, "scheme").trim();
                if (!scheme.matches("[A-Za-z][A-Za-z0-9_-]{0,30}")) {
                    throw new ApiException(400, "scheme", "Enter the word that goes before the token, e.g. Splunk, Token or ApiKey.");
                }
                out.addProperty("type", "token");
                out.addProperty("scheme", scheme);
                out.addProperty("token", nonEmpty(secret(a, oldAuth, "token", "token"), "Token"));
            }
            case "oauth2" -> {
                out.addProperty("type", "oauth2");
                out.addProperty("tokenUrl", url(a, "tokenUrl", "http", "https"));
                out.addProperty("clientId", required(a, "clientId", "Client ID"));
                out.addProperty("clientSecret", nonEmpty(secret(a, oldAuth, "clientSecret", "client secret"), "Client secret"));
                optional(out, "scope", str(a, "scope").trim());
                optional(out, "audience", str(a, "audience").trim());
            }
            default -> throw new ApiException(400, "auth", "Sign-in must be none, basic, bearer, token or OAuth2.");
        }
        return out;
    }

    /** String map (HTTP headers, Kafka properties); masked secrets keep the stored value. */
    private static JsonObject map(JsonObject in, String key, JsonObject old, String what) throws ApiException {
        JsonObject m = obj(in, key);
        JsonObject oldMap = obj(old, key);
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : m.entrySet()) {
            String k = e.getKey().trim();
            if (k.isEmpty()) {
                throw new ApiException(400, key, "Every " + what + " needs a name.");
            }
            out.addProperty(k, secret(m, oldMap, e.getKey(), what + " " + k));
        }
        return out;
    }

    /** The value sent, or the stored one when the console sent the mask back. */
    private static String secret(JsonObject in, JsonObject old, String key, String what) throws ApiException {
        String v = str(in, key);
        if (!MASK.equals(v)) {
            return v;
        }
        String stored = str(old, key);
        if (stored.isEmpty()) {
            throw new ApiException(400, key, "Enter the " + what + " again.");
        }
        return stored;
    }

    /** HTTP proxy for REST / Event Streams: http://host:port, optional user name + password (a secret) */
    private static void proxy(JsonObject in, JsonObject old, JsonObject d) throws ApiException {
        String p = str(in, "proxy").trim();
        if (p.isEmpty()) {
            return;
        }
        java.net.URI u;
        try {
            u = java.net.URI.create(p);
        } catch (IllegalArgumentException e) {
            u = null;
        }
        if (u == null || !"http".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) {
            throw new ApiException(400, "proxy", "Proxy: enter it as http://host:port.");
        }
        d.addProperty("proxy", p);
        optional(d, "proxyUsername", str(in, "proxyUsername").trim());
        if (!str(in, "proxyUsername").isBlank()) {
            optional(d, "proxyPassword", secret(in, old, "proxyPassword", "proxy password"));
        }
    }

    private static String url(JsonObject in, String key, String... schemes) throws ApiException {
        String v = str(in, key).trim();
        for (String s : schemes) {
            if (v.toLowerCase(Locale.ROOT).startsWith(s + "://") && v.length() > s.length() + 3) {
                return v;
            }
        }
        throw new ApiException(400, key, "Enter an address starting with " + String.join("://, ", schemes) + "://");
    }

    private static int integer(JsonObject in, String key, int dflt, int min, int max, String what) throws ApiException {
        if (!in.has(key) || in.get(key).isJsonNull() || str(in, key).isBlank()) {
            return dflt;
        }
        try {
            double v = Double.parseDouble(str(in, key).trim());
            if (v == Math.rint(v) && v >= min && v <= max) {
                return (int) v;
            }
        } catch (NumberFormatException ignored) {
            // falls through
        }
        throw new ApiException(400, key, what + " must be a whole number from " + min + " to " + max + ".");
    }

    private static String choice(JsonObject in, String key, String dflt, Set<String> allowed, String what) throws ApiException {
        String v = str(in, key).trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return dflt;
        }
        if (!allowed.contains(v)) {
            throw new ApiException(400, key, what + " must be one of " + String.join(", ", allowed) + ".");
        }
        return v;
    }

    private static String required(JsonObject in, String key, String what) throws ApiException {
        return nonEmpty(str(in, key).trim(), what);
    }

    private static String nonEmpty(String v, String what) throws ApiException {
        if (v == null || v.isBlank()) {
            throw new ApiException(400, "required", what + " is required.");
        }
        return v;
    }

    private static void optional(JsonObject d, String key, String v) {
        if (v != null && !v.isEmpty()) {
            d.add(key, new JsonPrimitive(v));
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static String str(JsonObject o, String key) {
        return Json.str(o, key);
    }
}
