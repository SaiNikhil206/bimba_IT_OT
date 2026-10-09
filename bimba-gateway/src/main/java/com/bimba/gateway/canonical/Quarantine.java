package com.bimba.gateway.canonical;

import com.bimba.gateway.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Records the gateway refused before routing (FR-CAN-03), kept with their original payload and a reason code so they
 * can be inspected and replayed after the cause is fixed. One entry = the records of one delivery (a site agent's
 * request, or one reading of the gateway's collector) that failed for the same reason: {@code <data>/quarantine/<id>.json}
 * holds the records, {@code <id>.meta} the summary the console lists. The oldest entries are removed beyond
 * bimba.quarantine.max-megabytes / max-entries.
 */
@Component
public class Quarantine {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Quarantine");
    private static final Pattern ID = Pattern.compile("\\d{16}-\\d{6}");
    /** a record's raw text is kept up to this size (a whole unreadable request body can be larger) */
    static final int MAX_RAW = 256 * 1024;
    private static final int MAX_MACHINES = 20;

    /** where the records came from: the gateway's own collector, or a site agent (of an organization) */
    public record Origin(String kind, UUID agentId, Long orgId) {
        public static Origin gateway() {
            return new Origin("gateway", null, null);
        }

        public static Origin agent(UUID agentId, long orgId) {
            return new Origin("agent", agentId, orgId);
        }
    }

    /** one refused record: its text as received, why, and the machine / tag it names (when it could be read) */
    public record Item(String raw, String detail, String machineId, String tagName) {
    }

    /** what the console lists about an entry */
    public record Meta(String id, Instant at, MessageCheck.Reason reason, String detail, Origin origin, List<String> machines,
                       int count, long bytes) {
        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("at", at.toString());
            m.put("reason", reason.name());
            m.put("detail", detail);
            m.put("origin", origin.kind());
            m.put("agentId", origin.agentId() == null ? null : origin.agentId().toString());
            m.put("machines", machines);
            m.put("count", count);
            m.put("bytes", bytes);
            return m;
        }
    }

    public record Entry(Meta meta, List<Item> items) {
    }

    private final Path dir;
    private final long maxBytes;
    private final int maxEntries;
    private final NavigableMap<String, Meta> index = new ConcurrentSkipListMap<>();
    private final AtomicLong counter = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong records = new AtomicLong();
    private long bytes;
    private long lastLog;

    public Quarantine(@Value("${bimba.data-dir:data}") File dataDir,
                      @Value("${bimba.quarantine.max-megabytes:200}") int maxMegabytes,
                      @Value("${bimba.quarantine.max-entries:10000}") int maxEntries) throws IOException {
        this.dir = new File(dataDir, "quarantine").toPath();
        this.maxBytes = maxMegabytes * 1024L * 1024L;
        this.maxEntries = maxEntries;
        Files.createDirectories(dir);
        load();
    }

    private void load() {
        File[] metas = dir.toFile().listFiles((d, n) -> n.endsWith(".meta") && ID.matcher(n.substring(0, n.length() - 5)).matches());
        if (metas == null) {
            return;
        }
        for (File f : metas) {
            try {
                Meta m = fromJson(Json.GSON.fromJson(Files.readString(f.toPath()), JsonObject.class));
                index.put(m.id(), m);
                bytes += m.bytes();
            } catch (IOException | RuntimeException e) {
                log.warn("unreadable quarantine note {}: {}", f.getName(), e.getMessage());
            }
        }
        if (!index.isEmpty()) {
            log.info("{} quarantined entr{} kept from earlier", index.size(), index.size() == 1 ? "y" : "ies");
        }
    }

    /** keeps the records (same origin, same reason) as one entry; returns its id */
    public synchronized String put(Origin origin, MessageCheck.Reason reason, List<Item> items) {
        if (items.isEmpty()) {
            return null;
        }
        String id = String.format("%016d-%06d", System.currentTimeMillis(), counter.incrementAndGet() % 1_000_000);
        JsonArray arr = new JsonArray();
        List<String> machines = new ArrayList<>();
        for (Item it : items) {
            JsonObject o = new JsonObject();
            String raw = it.raw() == null ? "" : it.raw();
            o.addProperty("raw", raw.length() > MAX_RAW ? raw.substring(0, MAX_RAW) : raw);
            o.addProperty("truncated", raw.length() > MAX_RAW);
            o.addProperty("detail", it.detail());
            o.addProperty("machineId", it.machineId());
            o.addProperty("tagName", it.tagName());
            arr.add(o);
            if (it.machineId() != null && !it.machineId().isBlank() && machines.size() < MAX_MACHINES && !machines.contains(it.machineId())) {
                machines.add(it.machineId());
            }
        }
        JsonObject body = new JsonObject();
        body.add("items", arr);
        byte[] data = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        String detail = items.get(0).detail();
        Meta meta = new Meta(id, Instant.now(), reason, detail == null ? "" : detail.substring(0, Math.min(1000, detail.length())),
                origin, List.copyOf(machines), items.size(), data.length);
        try {
            Files.write(dir.resolve(id + ".json"), data);
            Files.writeString(dir.resolve(id + ".meta"), Json.GSON.toJson(toJson(meta)));
        } catch (IOException e) {
            log.error("could not quarantine {} record(s) ({}): they are lost", items.size(), reason, e);
            dropped.addAndGet(items.size());
            return null;
        }
        index.put(id, meta);
        bytes += data.length;
        records.addAndGet(items.size());
        long now = System.currentTimeMillis();
        if (now - lastLog > 60_000) {                // a source that sends nothing but bad records must not flood the log
            lastLog = now;
            log.warn("quarantined {} record(s) from {}{}: {} {}", items.size(), origin.kind(),
                    origin.agentId() == null ? "" : " " + origin.agentId(), reason, meta.detail());
        }
        enforceLimits();
        return id;
    }

    private void enforceLimits() {
        while (!index.isEmpty() && (bytes > maxBytes || index.size() > maxEntries)) {
            Map.Entry<String, Meta> oldest = index.firstEntry();
            dropped.addAndGet(oldest.getValue().count());
            remove(oldest.getKey());
            log.warn("quarantine full: removed the oldest entry {}", oldest.getKey());
        }
    }

    private void remove(String id) {
        Meta m = index.remove(id);
        if (m != null) {
            bytes -= m.bytes();
        }
        try {
            Files.deleteIfExists(dir.resolve(id + ".json"));
            Files.deleteIfExists(dir.resolve(id + ".meta"));
        } catch (IOException e) {
            log.warn("could not delete quarantine entry {}: {}", id, e.getMessage());
        }
    }

    /** newest first, at most limit of the visible entries */
    public List<Meta> list(Predicate<Meta> visible, int limit) {
        List<Meta> out = new ArrayList<>();
        for (Meta m : index.descendingMap().values()) {
            if (visible.test(m)) {
                out.add(m);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /** visible entries and the records in them: {entries, records} */
    public long[] count(Predicate<Meta> visible) {
        long n = 0;
        long r = 0;
        for (Meta m : index.values()) {
            if (visible.test(m)) {
                n++;
                r += m.count();
            }
        }
        return new long[]{n, r};
    }

    /** the entry when it exists and is visible, else null */
    public Entry read(String id, Predicate<Meta> visible) throws IOException {
        Meta m = id == null || !ID.matcher(id).matches() ? null : index.get(id);
        if (m == null || !visible.test(m)) {
            return null;
        }
        JsonObject body = Json.GSON.fromJson(Files.readString(dir.resolve(id + ".json")), JsonObject.class);
        List<Item> items = new ArrayList<>();
        for (JsonElement e : body.getAsJsonArray("items")) {
            JsonObject o = e.getAsJsonObject();
            items.add(new Item(Json.str(o, "raw"), opt(o, "detail"), opt(o, "machineId"), opt(o, "tagName")));
        }
        return new Entry(m, items);
    }

    /** removes a visible entry; false when it is not there */
    public synchronized boolean discard(String id, Predicate<Meta> visible) {
        Meta m = id == null ? null : index.get(id);
        if (m == null || !visible.test(m)) {
            return false;
        }
        remove(id);
        return true;
    }

    /** gateway-wide: entries, records quarantined since start, records lost (full / disk errors) */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entries", index.size());
        m.put("bytes", bytes);
        m.put("quarantinedSinceStart", records.get());
        m.put("dropped", dropped.get());
        return m;
    }

    private static JsonObject toJson(Meta m) {
        JsonObject o = Json.GSON.toJsonTree(m.json()).getAsJsonObject();
        o.addProperty("orgId", m.origin().orgId());
        return o;
    }

    /** a string member, null when missing or empty */
    static String opt(JsonObject o, String key) {
        String s = Json.str(o, key);
        return s.isEmpty() ? null : s;
    }

    private static Meta fromJson(JsonObject o) {
        String agent = opt(o, "agentId");
        Long org = o.has("orgId") && !o.get("orgId").isJsonNull() ? o.get("orgId").getAsLong() : null;
        List<String> machines = new ArrayList<>();
        if (o.has("machines") && o.get("machines").isJsonArray()) {
            o.getAsJsonArray("machines").forEach(x -> machines.add(x.getAsString()));
        }
        return new Meta(Json.str(o, "id"), Instant.parse(Json.str(o, "at")), MessageCheck.Reason.valueOf(Json.str(o, "reason")),
                Json.str(o, "detail"), new Origin(Json.str(o, "origin"), agent == null ? null : UUID.fromString(agent), org),
                machines, o.get("count").getAsInt(), o.get("bytes").getAsLong());
    }
}
