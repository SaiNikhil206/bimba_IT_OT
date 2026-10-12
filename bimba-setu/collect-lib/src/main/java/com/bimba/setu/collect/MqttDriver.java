package com.bimba.setu.collect;

import com.bimba.setu.collect.Canonical.Quality;
import com.bimba.setu.config.TagModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MQTT broker as a data source (subscribe only). The driver keeps the latest value of every tag it was asked for; the
 * collector samples them at the source's read rate (so only changes are sent, like the polled protocols).
 * <p>
 * Settings: url (tcp:// | ssl:// | mqtt:// | mqtts:// | ws:// | wss://), clientId (default bimba-&lt;host&gt;-&lt;random&gt;),
 * username / password, caCert (PEM of the broker's CA, else the system trust store), qos (0 | 1, default 1),
 * payload value | json | sparkplug, staleSeconds (0 = values never go stale), rebirth (Sparkplug: ask edge nodes to
 * republish their metrics when needed; a write to the edge node, so off by default).
 * <p>
 * Tag addresses: value: the topic ("plant/line1/temperature", payload "21.5", "true" or text). json: topic#field.path
 * ("plant/line1/status#motor.speed", arrays as items[0]). sparkplug: group/edge node[/device]#metric
 * ("Plant1/Line1/Press3#Hydraulic/Pressure"). Topics are exact (no wildcards).
 */
public final class MqttDriver implements Driver {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Mqtt");
    private static final String SPB = "spBv1.0";
    /** publishing from Paho's callback thread deadlocks the client: commands (rebirth) go out from here */
    private static final java.util.concurrent.ExecutorService COMMANDS = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BIMBA-Mqtt-Commands");
        t.setDaemon(true);
        return t;
    });

    enum Format { VALUE, JSON, SPARKPLUG }

    private record Value(Object v, Quality quality, String detail, Instant sourceTime, long receivedAt) {
    }

    private final String url;
    private final String clientId;
    private final JsonObject settings;
    private final Format format;
    private final int qos;
    private final long staleMs;
    private final boolean rebirth;
    private final Map<String, Value> values = new ConcurrentHashMap<>();
    /** json: topic -> field paths asked for */
    private final Map<String, Set<String>> jsonPaths = new ConcurrentHashMap<>();
    /** sparkplug: "group/node[/device]" -> alias -> metric name (from the last BIRTH) */
    private final Map<String, Map<Long, String>> aliases = new ConcurrentHashMap<>();
    private final Map<String, Long> bdSeq = new ConcurrentHashMap<>();
    private final Map<String, Long> rebirthAskedAt = new ConcurrentHashMap<>();
    private final Set<String> births = ConcurrentHashMap.newKeySet();
    /** browse: every topic seen with its last payload (only when sampling) */
    private final Map<String, byte[]> sample;
    private MqttClient client;
    private List<String> filters = List.of();
    private volatile String lastError;

    public MqttDriver(JsonObject settings) {
        this(settings, null, false);
    }

    private MqttDriver(JsonObject settings, String clientSuffix, boolean sampling) {
        this.settings = settings;
        url = normalizeUrl(str(settings, "url"));
        format = switch (strOr(settings, "payload", "value").toLowerCase(Locale.ROOT)) {
            case "json" -> Format.JSON;
            case "sparkplug", "sparkplugb", "spb" -> Format.SPARKPLUG;
            default -> Format.VALUE;
        };
        qos = settings.has("qos") && settings.get("qos").isJsonPrimitive() ? Math.max(0, Math.min(1, settings.get("qos").getAsInt())) : 1;
        staleMs = settings.has("staleSeconds") && settings.get("staleSeconds").isJsonPrimitive()
                ? Math.max(0, settings.get("staleSeconds").getAsLong()) * 1000 : 0;
        rebirth = settings.has("rebirth") && settings.get("rebirth").isJsonPrimitive() && settings.get("rebirth").getAsBoolean();
        String id = str(settings, "clientId");
        if (id == null || id.isBlank()) {
            String host = OpcUaPki.hostName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            id = "bimba-" + (host.length() > 8 ? host.substring(0, 8) : host) + "-" + Long.toString(new SecureRandom().nextInt() & 0x7fffffffL, 36);
        }
        clientId = clientSuffix == null ? id : (id.length() > 18 ? id.substring(0, 18) : id) + clientSuffix;
        sample = sampling ? new ConcurrentHashMap<>() : null;
    }

    static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("MQTT source without url");
        }
        String u = raw.trim();
        if (!u.contains("://")) {
            u = "tcp://" + u;
        }
        u = u.replaceFirst("(?i)^mqtts://", "ssl://").replaceFirst("(?i)^mqtt://", "tcp://");
        if (!u.matches("(?i)^(tcp|ssl|ws|wss)://.+")) {
            throw new IllegalArgumentException("MQTT url must start with tcp://, ssl://, mqtt://, mqtts://, ws:// or wss://");
        }
        if (u.matches("(?i)^(tcp|ssl)://[^:/]+$")) {
            u += u.regionMatches(true, 0, "ssl", 0, 3) ? ":8883" : ":1883";
        }
        return u;
    }

    // ------------------------------------------------------------------ Driver

    @Override
    public List<Reading> read(List<TagModel.Tag> tags) throws Exception {
        connect(filtersFor(tags));
        long now = System.currentTimeMillis();
        List<Reading> out = new ArrayList<>(tags.size());
        for (TagModel.Tag t : tags) {
            Value v = values.get(t.address());
            if (v == null) {
                out.add(Reading.bad(waiting(t.address())));
            } else if (v.quality() == Quality.GOOD && staleMs > 0 && now - v.receivedAt() > staleMs) {
                out.add(Reading.uncertain(v.v(), "no update for " + (now - v.receivedAt()) / 1000 + " s", v.sourceTime()));
            } else {
                out.add(new Reading(v.v(), v.quality(), v.detail(), v.sourceTime()));
            }
        }
        return out;
    }

    private String waiting(String address) {
        if (format == Format.SPARKPLUG) {
            String node = address.contains("#") ? address.substring(0, address.indexOf('#')) : address;
            return births.contains(node) || births.contains(nodeOf(node))
                    ? "metric not in the edge node's last birth"
                    : "waiting for " + node + " to publish" + (rebirth ? " (rebirth requested)" : " (turn on rebirth requests to ask it now)");
        }
        String topic = format == Format.JSON && address.contains("#") ? address.substring(0, address.indexOf('#')) : address;
        return "no message on " + topic + " yet";
    }

    @Override
    public String describe() {
        return url + (format == Format.VALUE ? "" : " (" + format.name().toLowerCase(Locale.ROOT) + ")");
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            try {
                if (client.isConnected()) {
                    client.disconnect(2000);
                }
                client.close(true);
            } catch (MqttException ignored) {
                // gone
            }
            client = null;
        }
    }

    // ------------------------------------------------------------------ connection

    private List<String> filtersFor(List<TagModel.Tag> tags) {
        Set<String> f = new LinkedHashSet<>();
        for (TagModel.Tag t : tags) {
            String a = t.address();
            switch (format) {
                case VALUE -> f.add(a);
                case JSON -> {
                    int i = a.indexOf('#');
                    String topic = i < 0 ? a : a.substring(0, i);
                    f.add(topic);
                    jsonPaths.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(i < 0 ? "" : a.substring(i + 1));
                }
                case SPARKPLUG -> {
                    String[] p = nodeOf(a.contains("#") ? a.substring(0, a.indexOf('#')) : a).split("/");
                    if (p.length >= 2) {
                        f.add(SPB + "/" + p[0] + "/+/" + p[1]);
                        f.add(SPB + "/" + p[0] + "/+/" + p[1] + "/+");
                    }
                }
            }
        }
        return new ArrayList<>(f);
    }

    /** "group/node/device" -> "group/node" */
    private static String nodeOf(String path) {
        String[] p = path.split("/");
        return p.length >= 2 ? p[0] + "/" + p[1] : path;
    }

    private synchronized void connect(List<String> wanted) throws Exception {
        if (client != null && client.isConnected()) {
            if (!wanted.equals(filters)) {
                subscribe(wanted);
            }
            return;
        }
        if (client != null) {
            // automatic reconnect is running: report why the data is missing
            throw new Exception("not connected to " + url + (lastError != null ? ": " + lastError : " (reconnecting)"));
        }
        MqttClient c = new MqttClient(url, clientId, new MemoryPersistence());
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        o.setAutomaticReconnect(true);
        o.setConnectionTimeout(10);
        o.setKeepAliveInterval(30);
        o.setMaxInflight(1000);
        String user = str(settings, "username");
        if (user != null && !user.isBlank()) {
            o.setUserName(user);
            String pw = str(settings, "password");
            if (pw != null) {
                o.setPassword(pw.toCharArray());
            }
        }
        String ca = str(settings, "caCert");
        if (ca != null && !ca.isBlank() && url.matches("(?i)^(ssl|wss)://.*")) {
            o.setSocketFactory(trusting(ca).getSocketFactory());
        }
        c.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                lastError = null;
                if (reconnect) {
                    log.info("MQTT {} reconnected", url);
                    try {
                        subscribe(filters);
                    } catch (Exception e) {
                        lastError = "subscribe: " + e.getMessage();
                    }
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                lastError = "connection lost: " + (cause == null ? "?" : String.valueOf(cause.getMessage()));
                log.warn("MQTT {} {}", url, lastError);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                try {
                    received(topic, message.getPayload());
                } catch (RuntimeException e) {
                    log.debug("MQTT {}: {}", topic, e.getMessage());
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });
        try {
            c.connect(o);
        } catch (MqttException e) {
            try {
                c.close(true);
            } catch (MqttException ignored) {
                // never connected
            }
            throw new Exception("cannot connect to " + url + ": " + explain(e));
        }
        client = c;
        subscribe(wanted);
        log.info("MQTT {} connected as {} ({} subscription(s))", url, clientId, wanted.size());
    }

    private synchronized void subscribe(List<String> wanted) throws MqttException {
        if (client == null) {
            return;
        }
        List<String> old = filters;
        filters = List.copyOf(wanted);
        if (!old.isEmpty() && client.isConnected()) {
            List<String> gone = old.stream().filter(x -> !wanted.contains(x)).toList();
            if (!gone.isEmpty()) {
                client.unsubscribe(gone.toArray(String[]::new));
            }
        }
        if (!wanted.isEmpty()) {
            int[] q = new int[wanted.size()];
            java.util.Arrays.fill(q, qos);
            client.subscribe(wanted.toArray(String[]::new), q);
        }
    }

    static String explain(MqttException e) {
        return switch (e.getReasonCode()) {
            case MqttException.REASON_CODE_FAILED_AUTHENTICATION, MqttException.REASON_CODE_NOT_AUTHORIZED ->
                    "the broker refused the user name / password (not authorized)";
            case MqttException.REASON_CODE_INVALID_CLIENT_ID -> "the broker refused the client id";
            case MqttException.REASON_CODE_SERVER_CONNECT_ERROR -> "connection refused or unreachable"
                    + (e.getCause() != null ? " (" + e.getCause().getMessage() + ")" : "");
            case MqttException.REASON_CODE_CLIENT_TIMEOUT -> "timed out";
            default -> e.getMessage() + (e.getCause() != null ? " (" + e.getCause().getMessage() + ")" : "");
        };
    }

    private static SSLContext trusting(String pem) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem.trim().getBytes(StandardCharsets.US_ASCII)))) {
            ks.setCertificateEntry("ca" + i++, c);
        }
        if (i == 0) {
            throw new Exception("caCert: no certificate in the PEM text");
        }
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, f.getTrustManagers(), null);
        return ctx;
    }

    // ------------------------------------------------------------------ messages

    void received(String topic, byte[] payload) {
        long now = System.currentTimeMillis();
        if (sample != null && sample.size() < 5000) {
            sample.put(topic, payload);
        }
        switch (format) {
            case VALUE -> values.put(topic, new Value(plain(new String(payload, StandardCharsets.UTF_8)), Quality.GOOD, null, null, now));
            case JSON -> {
                Set<String> paths = jsonPaths.get(topic);
                if (paths == null || paths.isEmpty()) {
                    return;
                }
                JsonElement doc;
                try {
                    doc = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    for (String p : paths) {
                        values.put(key(topic, p), new Value(null, Quality.BAD, "the message on " + topic + " is not JSON", null, now));
                    }
                    return;
                }
                for (String p : paths) {
                    JsonElement e = p.isEmpty() ? doc : path(doc, p);
                    values.put(key(topic, p), e == null
                            ? new Value(null, Quality.BAD, "no field " + p + " in the message", null, now)
                            : new Value(jsonValue(e), Quality.GOOD, null, null, now));
                }
            }
            case SPARKPLUG -> sparkplug(topic, payload, now);
        }
    }

    private static String key(String topic, String path) {
        return path.isEmpty() ? topic : topic + "#" + path;
    }

    /** spBv1.0/group/TYPE/node[/device] */
    private void sparkplug(String topic, byte[] payload, long now) {
        String[] t = topic.split("/");
        if (t.length < 4 || !SPB.equals(t[0])) {
            return;
        }
        String type = t[2];
        String node = t[1] + "/" + t[3];
        String prefix = t.length >= 5 ? node + "/" + t[4] : node;
        SparkplugB.Payload p;
        try {
            p = SparkplugB.decode(payload);
        } catch (RuntimeException e) {
            log.debug("Sparkplug {}: {}", topic, e.getMessage());
            return;
        }
        switch (type) {
            case "NBIRTH", "DBIRTH" -> {
                Map<Long, String> map = new ConcurrentHashMap<>();
                for (SparkplugB.Metric m : p.metrics()) {
                    if (m.name() != null && m.alias() != null) {
                        map.put(m.alias(), m.name());
                    }
                }
                aliases.put(prefix, map);
                births.add(prefix);
                if ("NBIRTH".equals(type)) {
                    Long seq = p.bdSeq();
                    if (seq != null) {
                        bdSeq.put(node, seq);
                    }
                }
                store(prefix, p, now);
            }
            case "NDATA", "DDATA" -> {
                store(prefix, p, now);
                if (rebirth && !births.contains(prefix)) {
                    askRebirth(t[1], t[3], now);
                }
            }
            case "NDEATH" -> {
                Long seq = p.bdSeq(), known = bdSeq.get(node);
                if (seq != null && known != null && !seq.equals(known)) {
                    return;                                  // a late death of an older session
                }
                markDead(node, true, "edge node " + node + " is offline (NDEATH)", now);
                births.removeIf(b -> b.equals(node) || b.startsWith(node + "/"));
            }
            case "DDEATH" -> {
                markDead(prefix, false, "device " + prefix + " is offline (DDEATH)", now);
                births.remove(prefix);
            }
            default -> {
                // NCMD / DCMD / STATE: not data
            }
        }
    }

    private void store(String prefix, SparkplugB.Payload p, long now) {
        Map<Long, String> map = aliases.getOrDefault(prefix, Map.of());
        int unknown = 0;
        for (SparkplugB.Metric m : p.metrics()) {
            String name = m.name() != null ? m.name() : m.alias() != null ? map.get(m.alias()) : null;
            if (name == null) {
                unknown++;
                continue;
            }
            Long ts = m.timestamp() != null ? m.timestamp() : p.timestamp();
            Instant at = ts == null ? null : Instant.ofEpochMilli(ts);
            values.put(prefix + "#" + name, m.isNull()
                    ? new Value(null, Quality.BAD, "the edge node reports no value (is_null)", at, now)
                    : new Value(m.value(), Quality.GOOD, null, at, now));
        }
        if (unknown > 0 && rebirth) {
            String[] n = prefix.split("/");
            askRebirth(n[0], n[1], now);
        }
    }

    private void markDead(String prefix, boolean withDevices, String why, long now) {
        for (Map.Entry<String, Value> e : values.entrySet()) {
            String k = e.getKey();
            String owner = k.substring(0, Math.max(0, k.indexOf('#')));
            if (owner.equals(prefix) || withDevices && owner.startsWith(prefix + "/")) {
                Value v = e.getValue();
                e.setValue(new Value(v.v(), Quality.BAD, why, v.sourceTime(), now));
            }
        }
    }

    /** NCMD Node Control/Rebirth, at most once a minute per edge node */
    private void askRebirth(String group, String node, long now) {
        String key = group + "/" + node;
        Long last = rebirthAskedAt.get(key);
        if (last != null && now - last < 60_000) {
            return;
        }
        rebirthAskedAt.put(key, now);
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            return;
        }
        COMMANDS.execute(() -> {
            try {
                c.publish(SPB + "/" + group + "/NCMD/" + node, SparkplugB.rebirth(now), 0, false);
                log.info("Sparkplug: asked edge node {} to rebirth", key);
            } catch (MqttException e) {
                log.warn("Sparkplug rebirth request to {} failed: {}", key, e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ values

    /** "21.5" -> 21.5, "true" -> true, else the text */
    static Object plain(String s) {
        String t = s.trim();
        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(t);
        }
        try {
            double d = Double.parseDouble(t);
            if (!Double.isNaN(d) && !Double.isInfinite(d) && !t.matches("(?i).*[a-z].*")) {
                return d;
            }
        } catch (NumberFormatException ignored) {
            // text
        }
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static Object jsonValue(JsonElement e) {
        if (e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            return p.isBoolean() ? (Object) p.getAsBoolean() : p.isNumber() ? (Object) p.getAsDouble() : p.getAsString();
        }
        return e.toString();
    }

    /** "a.b[2].c" in a JSON document, or null */
    static JsonElement path(JsonElement doc, String path) {
        JsonElement cur = doc;
        for (String part : path.split("\\.")) {
            if (part.isEmpty()) {
                continue;
            }
            String name = part;
            List<Integer> idx = new ArrayList<>();
            int b = part.indexOf('[');
            if (b >= 0) {
                name = part.substring(0, b);
                for (String i : part.substring(b).split("[\\[\\]]+")) {
                    if (!i.isEmpty()) {
                        idx.add(Integer.parseInt(i.trim()));
                    }
                }
            }
            if (!name.isEmpty()) {
                if (cur == null || !cur.isJsonObject() || !cur.getAsJsonObject().has(name)) {
                    return null;
                }
                cur = cur.getAsJsonObject().get(name);
            }
            for (int i : idx) {
                if (cur == null || !cur.isJsonArray() || i < 0 || i >= cur.getAsJsonArray().size()) {
                    return null;
                }
                cur = cur.getAsJsonArray().get(i);
            }
        }
        return cur;
    }

    // ------------------------------------------------------------------ console requests

    /** Test connection: connects, then waits up to 6 s for the given tags: {ok, target, values:[...]} */
    public static Map<String, Object> test(JsonObject settings, List<TagModel.Tag> tags) throws Exception {
        MqttDriver d = new MqttDriver(settings, "-t" + Integer.toString(new SecureRandom().nextInt(1296), 36), false);
        try {
            List<Reading> r = d.read(tags);
            long until = System.currentTimeMillis() + 6000;
            while (!tags.isEmpty() && r.stream().anyMatch(x -> x.value() == null && x.quality() == Quality.BAD
                    && x.detail() != null && (x.detail().startsWith("no message") || x.detail().startsWith("waiting")))
                    && System.currentTimeMillis() < until) {
                Thread.sleep(250);
                r = d.read(tags);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < tags.size(); i++) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("name", tags.get(i).name());
                v.put("address", tags.get(i).address());
                v.put("value", r.get(i).value());
                v.put("good", r.get(i).good());
                v.put("quality", r.get(i).quality().name());
                v.put("detail", r.get(i).detail());
                out.add(v);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("target", d.describe());
            m.put("values", out);
            return m;
        } finally {
            d.close();
        }
    }

    /**
     * Browse the broker: listens for a few seconds (retained messages arrive at once) and lists what was seen below
     * nodeId. value / json: topic folders ("a/b/"), topics with a value, JSON topics as folders ("topic#") of their
     * fields. sparkplug: groups, edge nodes, devices and their metrics (from births and data).
     */
    public static Map<String, Object> browse(JsonObject settings, String nodeId, int limit) throws Exception {
        MqttDriver d = new MqttDriver(settings, "-b" + Integer.toString(new SecureRandom().nextInt(1296), 36), true);
        String filter = d.format == Format.SPARKPLUG ? SPB + "/#" : strOr(settings, "browseTopic", "#");
        try {
            d.connect(List.of(filter));
            if (d.format == Format.SPARKPLUG && d.rebirth) {
                Thread.sleep(1500);
                Set<String> nodes = new LinkedHashSet<>();
                for (String topic : d.sample.keySet()) {
                    String[] t = topic.split("/");
                    if (t.length >= 4 && !d.births.contains(t[1] + "/" + t[3])) {
                        nodes.add(t[1] + "/" + t[3]);
                    }
                }
                for (String n : nodes) {
                    String[] p = n.split("/");
                    d.askRebirth(p[0], p[1], System.currentTimeMillis());
                }
            }
            Thread.sleep(d.format == Format.SPARKPLUG ? 3500 : 3000);
        } finally {
            d.close();
        }
        List<Map<String, Object>> items = d.format == Format.SPARKPLUG ? d.sparkplugTree(nodeId) : d.topicTree(nodeId);
        boolean truncated = items.size() > limit;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", nodeId);
        m.put("items", truncated ? items.subList(0, limit) : items);
        m.put("truncated", truncated || d.sample.size() >= 5000);
        m.put("topics", d.sample.size());
        if (d.format == Format.SPARKPLUG && !d.rebirth) {
            m.put("note", "Only metrics published while listening are listed. Turn on rebirth requests to list every metric of the edge nodes.");
        }
        m.put("target", d.describe());
        return m;
    }

    private List<Map<String, Object>> topicTree(String nodeId) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (nodeId != null && nodeId.endsWith("#")) {                 // the fields of one JSON topic
            String topic = nodeId.substring(0, nodeId.length() - 1);
            byte[] p = sample.get(topic);
            if (p != null) {
                try {
                    flatten(JsonParser.parseString(new String(p, StandardCharsets.UTF_8)), "", topic, items);
                } catch (RuntimeException ignored) {
                    // not JSON any more
                }
            }
            return items;
        }
        String prefix = nodeId == null ? "" : nodeId;
        Map<String, Map<String, Object>> folders = new TreeMap<>(), leaves = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : sample.entrySet()) {
            String topic = e.getKey();
            if (!topic.startsWith(prefix)) {
                continue;
            }
            String rest = topic.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                String f = prefix + rest.substring(0, slash + 1);
                folders.putIfAbsent(f, node(f, rest.substring(0, slash).isEmpty() ? "(empty)" : rest.substring(0, slash), "Object", null));
                continue;
            }
            String text = new String(e.getValue(), StandardCharsets.UTF_8);
            JsonElement json = null;
            if (format == Format.JSON) {
                try {
                    json = JsonParser.parseString(text);
                } catch (RuntimeException ignored) {
                    // a plain value
                }
            }
            String name = rest.isEmpty() ? topic : rest;
            if (json != null && (json.isJsonObject() || json.isJsonArray())) {
                leaves.put(topic + "#", node(topic + "#", name + " {…}", "Object", null));
            } else {
                Map<String, Object> n = node(topic, name, "Variable", json != null ? jsonValue(json) : plain(text));
                n.put("good", true);
                leaves.put(topic, n);
            }
        }
        items.addAll(folders.values());
        items.addAll(leaves.values());
        return items;
    }

    private static void flatten(JsonElement e, String path, String topic, List<Map<String, Object>> out) {
        if (out.size() >= 500) {
            return;
        }
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> f : e.getAsJsonObject().entrySet()) {
                flatten(f.getValue(), path.isEmpty() ? f.getKey() : path + "." + f.getKey(), topic, out);
            }
        } else if (e.isJsonArray()) {
            for (int i = 0; i < Math.min(50, e.getAsJsonArray().size()); i++) {
                flatten(e.getAsJsonArray().get(i), path + "[" + i + "]", topic, out);
            }
        } else {
            Map<String, Object> n = node(topic + "#" + path, path, "Variable", jsonValue(e));
            n.put("good", true);
            out.add(n);
        }
    }

    /** spb:group / spb:group/node / spb:group/node/device folders, metrics as group/node[/device]#metric */
    private List<Map<String, Object>> sparkplugTree(String nodeId) {
        Map<String, Map<String, Object>> folders = new TreeMap<>(), metrics = new TreeMap<>();
        String at = nodeId == null ? "" : nodeId.replaceFirst("^spb:", "");
        int depth = at.isEmpty() ? 0 : at.split("/").length;
        Set<String> owners = new LinkedHashSet<>();
        for (String topic : sample.keySet()) {
            String[] t = topic.split("/");
            if (t.length >= 4) {
                owners.add(t[1] + "/" + t[3] + (t.length >= 5 ? "/" + t[4] : ""));
            }
        }
        for (String owner : owners) {
            String[] o = owner.split("/");
            if (depth < o.length && (at.isEmpty() || owner.startsWith(at + "/"))) {
                String child = String.join("/", java.util.Arrays.copyOf(o, depth + 1));
                String label = o[depth] + (depth == 0 ? " (group)" : depth == 1 ? " (edge node)" : " (device)");
                folders.putIfAbsent(child, node("spb:" + child, label, "Object", null));
            }
        }
        if (depth >= 2) {
            for (Map.Entry<String, Value> e : values.entrySet()) {
                String k = e.getKey();
                int h = k.indexOf('#');
                if (h > 0 && k.substring(0, h).equals(at)) {
                    Value v = e.getValue();
                    Map<String, Object> n = node(k, k.substring(h + 1), "Variable", v.v());
                    n.put("good", v.quality() == Quality.GOOD);
                    metrics.put(k, n);
                }
            }
        }
        List<Map<String, Object>> items = new ArrayList<>(folders.values());
        items.addAll(metrics.values());
        return items;
    }

    private static Map<String, Object> node(String id, String name, String cls, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", id);
        m.put("name", name);
        m.put("nodeClass", cls);
        if (value != null) {
            m.put("value", value);
        }
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String strOr(JsonObject o, String k, String d) {
        String v = str(o, k);
        return v == null || v.isBlank() ? d : v;
    }

    /** for tests: the values known so far */
    Collection<String> known() {
        return values.keySet();
    }
}
