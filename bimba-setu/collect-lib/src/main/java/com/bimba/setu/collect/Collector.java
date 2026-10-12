package com.bimba.setu.collect;

import com.bimba.setu.collect.Canonical.Quality;
import com.bimba.setu.config.TagModel;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Polls every device (one thread each) and turns every change of value or quality into a canonical
 * BIMBA message (docs/payload.md) for the sink (Setu's delivery engine, or the edge collector's uplink to the
 * site agent service). A device that cannot be reached makes all of its tags BAD (one message per tag) until it
 * answers again, and each of its machines gets a gap marker (FR-RES-03): "lost" when the device stops answering and
 * "restored", with the gap's start and end, when it answers again ({@link #gap}). A device that cannot be reached is
 * tried again with a growing wait (FR-RES-03): its poll interval, doubled after every failure, at most "retryMaxMs"
 * of its connection settings (default 60 s). A device's connection settings may carry its own "pollMs".
 * <p>
 * Each reading becomes an engineering value ({@link #engineering}): scaled (raw range -> scaled range, every protocol),
 * converted to the tag's unit, checked against its
 * engineering range (outside = UNCERTAIN), numbers rounded to 12 significant digits; a GOOD reading without a usable
 * value (NaN, empty) is BAD.
 */
public class Collector {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Collector");
    private static final MathContext DIGITS = new MathContext(12);

    private final String plantId;
    private final String edgeId;
    private final long pollMs;
    private final Consumer<JsonObject> sink;
    private final String session = UUID.randomUUID().toString();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong events = new AtomicLong();
    private final List<Device> devices = new ArrayList<>();
    private final List<TagModel.Tag> tags;
    private final Instant startedAt = Instant.now();
    private ScheduledExecutorService pool;

    /**
     * @param sources {kind: {device: connection settings}}, kind = modbus | opcua | opcda | mqtt | s7 | eip | dnp3 | iec104 | sql
     * @param sink    receives each canonical message; must not block
     */
    public Collector(String plantId, String edgeId, long pollMs, List<TagModel.Tag> tags, JsonObject sources,
                     Consumer<JsonObject> sink) {
        this.plantId = plantId;
        this.edgeId = edgeId;
        this.pollMs = pollMs;
        this.tags = tags;
        this.sink = sink;
        Map<String, List<TagModel.Tag>> groups = new LinkedHashMap<>();
        for (TagModel.Tag t : tags) {
            String key = t.kind() == TagModel.Kind.EXPRESSION ? "expression:" : t.kind().name().toLowerCase() + ":" + t.device();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        groups.forEach((key, list) -> devices.add(new Device(key, list, sources)));
    }

    public void start() {
        pool = Executors.newScheduledThreadPool(Math.max(1, devices.size()), r -> {
            Thread t = new Thread(r, "BIMBA-Poll");
            t.setDaemon(true);
            return t;
        });
        long every = Math.max(100, pollMs);
        for (Device d : devices) {
            pool.scheduleWithFixedDelay(d::cycle, 0, d.pollMs > 0 ? Math.max(100, d.pollMs) : every, TimeUnit.MILLISECONDS);
        }
        log.info("polling {} tags on {} device(s) every {} ms", tags.size(), devices.size(), every);
    }

    public void stop() {
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        devices.forEach(Device::close);
    }

    /** Status page: same keys as the former Ignition module (subscribedTags, machines, ...) plus devices. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session", session);
        m.put("subscribedTags", tags.size());
        m.put("tagEvents", events.get());
        m.put("lastSeq", seq.get());
        m.put("pollMs", pollMs);
        TreeSet<String> machines = new TreeSet<>();
        tags.forEach(t -> machines.add(t.machine()));
        m.put("machines", List.copyOf(machines));
        List<Map<String, Object>> tagList = new ArrayList<>();
        for (TagModel.Tag t : tags) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("machine", t.machine());
            e.put("tag", t.name());
            e.put("unit", t.unit());
            tagList.add(e);
        }
        m.put("tags", tagList);                 // what pipelines can select (machine/tag)
        List<Map<String, Object>> list = new ArrayList<>();
        devices.forEach(d -> list.add(d.status()));
        m.put("devices", list);
        return m;
    }

    // ------------------------------------------------------------------ one device

    private final class Device {
        final String kind;      // modbus | opcua | ... | sql | expression
        final String name;
        final List<TagModel.Tag> tags;
        final Driver driver;
        final Map<String, Expression> expressions = new HashMap<>();
        final Map<String, Object[]> last = new HashMap<>();   // path -> {value, quality}
        volatile String error;
        volatile Instant lastOk;
        volatile int good;
        volatile boolean wasDown;
        /** the last good read before the device was lost (the start of the gap) */
        Instant gapFrom;
        final long pollMs;
        /** reconnect backoff: the next try (epoch ms) and the current wait; 0 = connected */
        volatile long retryAt;
        long retryDelay;
        final long retryMaxMs;

        Device(String key, List<TagModel.Tag> tags, JsonObject sources) {
            this.kind = key.substring(0, key.indexOf(':'));
            this.name = key.substring(key.indexOf(':') + 1);
            this.tags = tags;
            JsonObject conn = section(sources, kind, name);
            pollMs = conn != null && conn.has("pollMs") && conn.get("pollMs").isJsonPrimitive() ? conn.get("pollMs").getAsLong() : 0;
            retryMaxMs = conn != null && conn.has("retryMaxMs") && conn.get("retryMaxMs").isJsonPrimitive()
                    ? Math.max(100, conn.get("retryMaxMs").getAsLong()) : 60_000;
            Driver d = null;
            if (kind.equals("modbus") && conn != null) {
                d = new ModbusDriver(conn);
            } else if (kind.equals("opcua") && conn != null) {
                d = new OpcUaDriver(conn);
            } else if (kind.equals("opcda") && conn != null) {
                d = new OpcDaDriver(conn);
            } else if (kind.equals("mqtt") && conn != null) {
                d = new MqttDriver(conn);
            } else if (kind.equals("s7") && conn != null) {
                d = new S7Driver(conn);
            } else if (kind.equals("eip") && conn != null) {
                d = new LogixDriver(conn);
            } else if (kind.equals("dnp3") && conn != null) {
                d = new Dnp3Driver(conn);
            } else if (kind.equals("iec104") && conn != null) {
                d = new Iec104Driver(conn);
            } else if (kind.equals("sql") && conn != null) {
                d = new SqlDriver(conn);
            } else if (!kind.equals("expression")) {
                error = "no connection settings for " + kind + " device " + name + " (Settings page)";
            }
            driver = d;
            if (kind.equals("expression")) {
                for (TagModel.Tag t : tags) {
                    try {
                        expressions.put(t.path(), Expression.parse(t.address()));
                    } catch (IllegalArgumentException e) {
                        log.warn("expression of {}: {}", t.path(), e.getMessage());
                    }
                }
            }
        }

        void cycle() {
            if (wasDown && System.currentTimeMillis() < retryAt) {
                return;                                          // lost: wait for the next try (its tags stay BAD)
            }
            try {
                List<Driver.Reading> values = read();
                Instant now = Instant.now();
                int ok = 0;
                for (int i = 0; i < tags.size(); i++) {
                    Driver.Reading r = values.get(i);
                    ok += r.good() ? 1 : 0;
                    emit(tags.get(i), r, now);
                }
                good = ok;
                if (wasDown) {
                    log.info("{} {} reachable again", kind, name);
                    wasDown = false;
                    for (String machine : machines()) {
                        sink.accept(gap(machine, this, "end", gapFrom, now, null));
                    }
                }
                lastOk = now;
                error = null;
                retryDelay = 0;
                retryAt = 0;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!wasDown || !msg.equals(error)) {
                    log.warn("{} {}: {}", kind, name, msg);
                }
                boolean lost = !wasDown;
                long every = Math.max(100, pollMs > 0 ? pollMs : Collector.this.pollMs);
                retryDelay = retryDelay == 0 ? every : Math.min(Math.max(every, retryMaxMs), retryDelay * 2);
                retryAt = System.currentTimeMillis() + retryDelay;
                if (lost) {
                    log.info("{} {}: trying again with a growing wait, at most every {} s", kind, name, Math.max(every, retryMaxMs) / 1000);
                }
                wasDown = true;
                error = msg;
                good = 0;
                Instant now = Instant.now();
                for (TagModel.Tag t : tags) {
                    emit(t, Driver.Reading.bad(msg), now);
                }
                if (lost) {
                    gapFrom = lastOk != null ? lastOk : startedAt;
                    for (String machine : machines()) {
                        sink.accept(gap(machine, this, "start", gapFrom, null, msg));
                    }
                }
            }
        }

        private List<Driver.Reading> read() throws Exception {
            if (kind.equals("expression")) {
                List<Driver.Reading> out = new ArrayList<>(tags.size());
                for (TagModel.Tag t : tags) {
                    Expression x = expressions.get(t.path());
                    if (x == null) {
                        out.add(Driver.Reading.bad("expression does not parse"));
                        continue;
                    }
                    try {
                        out.add(Driver.Reading.ok(x.evaluate()));
                    } catch (RuntimeException e) {
                        out.add(Driver.Reading.bad("expression failed: " + e.getMessage()));
                    }
                }
                return out;
            }
            if (driver == null) {
                throw new Exception(error);
            }
            return driver.read(tags);
        }

        private void emit(TagModel.Tag t, Driver.Reading raw, Instant at) {
            Driver.Reading r = engineering(t, raw);
            Object value = r.value();
            String quality = r.quality().name();
            Object[] prev = last.get(t.path());
            if (prev != null && Objects.equals(prev[0], value) && prev[1].equals(quality)) {
                return;
            }
            last.put(t.path(), new Object[]{value, quality});
            events.incrementAndGet();
            sink.accept(message(t, r, at));
        }

        void close() {
            if (driver != null) {
                driver.close();
            }
        }

        List<String> machines() {
            return tags.stream().map(TagModel.Tag::machine).distinct().toList();
        }

        Map<String, Object> status() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("name", name.isEmpty() ? "expressions" : name);
            if (driver instanceof OpcUaDriver ua && ua.serverCertSha256() != null) {
                m.put("serverCertSha256", ua.serverCertSha256());
            }
            m.put("target", driver == null ? (kind.equals("expression") ? "computed in Setu" : null) : driver.describe());
            m.put("connected", error == null && lastOk != null);
            m.put("tags", tags.size());
            m.put("machines", tags.stream().map(TagModel.Tag::machine).distinct().sorted().toList());   // per-organization views
            m.put("goodTags", good);
            m.put("lastOk", lastOk == null ? null : lastOk.toString());
            m.put("error", error);
            long next = retryAt;
            m.put("retryAt", wasDown && next > 0 ? Instant.ofEpochMilli(next).toString() : null);
            return m;
        }
    }

    private static JsonObject section(JsonObject sources, String kind, String device) {
        if (sources == null || !sources.has(kind) || !sources.get(kind).isJsonObject()) {
            return null;
        }
        JsonObject k = sources.getAsJsonObject(kind);
        return k.has(device) && k.get(device).isJsonObject() ? k.getAsJsonObject(device) : null;
    }

    /**
     * The reading as delivered: numbers are scaled (Linear, when the tag has a raw and a scaled range); Boolean tags read
     * as numbers become booleans; numbers are converted from the device's
     * unit to the tag's, checked against the engineering range (outside = UNCERTAIN) and rounded to 12 significant
     * digits (no 52.300000000000004); NaN / infinity / no value is BAD.
     */
    static Driver.Reading engineering(TagModel.Tag t, Driver.Reading r) {
        if (r.quality() == Quality.BAD) {
            return r.value() == null ? r : new Driver.Reading(null, Quality.BAD, r.detail(), r.sourceTime());
        }
        Object v = r.value() instanceof Number n && !(r.value() instanceof Double) ? (Object) n.doubleValue() : r.value();
        if (v == null) {
            return new Driver.Reading(null, Quality.BAD, r.detail() != null ? r.detail() : "no value", r.sourceTime());
        }
        if (!(v instanceof Double d)) {
            return r.value() == v ? r : new Driver.Reading(v, r.quality(), r.detail(), r.sourceTime());
        }
        if (d.isNaN() || d.isInfinite()) {
            return new Driver.Reading(null, Quality.BAD, "not a number (" + d + ")", r.sourceTime());
        }
        double x = t.scale(d);                                          // Linear scaling (raw range -> scaled range), if set
        if ("Boolean".equalsIgnoreCase(t.dataType())) {
            return new Driver.Reading(x != 0, r.quality(), r.detail(), r.sourceTime());
        }
        if (t.converts()) {
            try {
                x = Units.convert(x, t.sourceUnit().trim(), t.unit().trim());
            } catch (IllegalArgumentException e) {
                return new Driver.Reading(null, Quality.BAD, e.getMessage(), r.sourceTime());
            }
        }
        Quality q = r.quality();
        String detail = r.detail();
        if (!t.inRange(x)) {
            q = Quality.UNCERTAIN;
            detail = (detail == null ? "" : detail + "; ") + "outside the engineering range "
                    + (t.engLow() == null ? "" : plain(t.engLow())) + ".." + (t.engHigh() == null ? "" : plain(t.engHigh()));
        }
        return new Driver.Reading(new BigDecimal(x).round(DIGITS).doubleValue(), q, detail, r.sourceTime());
    }

    private static String plain(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /**
     * A gap marker of one machine: start (value "lost", quality BAD, gap.from = its last good read) or end (value
     * "restored", GOOD, gap.from / gap.to / gap.seconds = the time without data). Category "gap", tag
     * {@link Canonical#GAP_TAG}, so receivers can keep it apart from readings.
     */
    private JsonObject gap(String machine, Device d, String state, Instant from, Instant to, String reason) {
        JsonObject src = new JsonObject();
        src.addProperty("protocol", d.kind);
        src.addProperty("system", edgeId);
        src.addProperty("address", d.name);
        JsonObject m = Canonical.gapMarker(plantId, machine, state, to != null ? to : Instant.now(), from, to, d.kind + " " + d.name, reason, src);
        JsonObject edge = new JsonObject();
        edge.addProperty("edgeId", edgeId);
        edge.addProperty("session", session);
        edge.addProperty("seq", seq.incrementAndGet());
        m.add("edge", edge);
        return m;
    }

    private JsonObject message(TagModel.Tag t, Driver.Reading r, Instant at) {
        Object value = r.value();
        String detail = r.detail();
        JsonObject m = new JsonObject();
        m.addProperty("schemaVersion", Canonical.VERSION);
        m.addProperty("messageId", UUID.randomUUID().toString());
        m.addProperty("plantId", plantId);
        m.addProperty("machineId", t.machine());
        m.addProperty("timestamp", at.toString());
        if (r.sourceTime() != null) {
            m.addProperty("sourceTimestamp", r.sourceTime().toString());
        }
        m.addProperty("tagName", t.name());
        if (value instanceof Boolean b) {
            m.addProperty("tagValue", b);
            m.addProperty("dataType", "boolean");
        } else if (value instanceof Double d) {
            m.addProperty("tagValue", d);
            m.addProperty("dataType", "number");
        } else if (value == null) {
            m.add("tagValue", JsonNull.INSTANCE);
            m.addProperty("dataType", "null");
        } else {
            m.addProperty("tagValue", value.toString());
            m.addProperty("dataType", "string");
        }
        m.addProperty("unit", t.unit());
        m.addProperty("quality", r.quality().name());
        if (detail != null) {
            m.addProperty("qualityDetail", detail);
        }
        m.addProperty("category", "telemetry");
        JsonObject src = new JsonObject();
        src.addProperty("protocol", t.kind().name().toLowerCase());
        src.addProperty("system", edgeId);
        src.addProperty("address", t.kind() == TagModel.Kind.EXPRESSION ? t.path() : t.device() + "/" + t.address());
        m.add("source", src);
        JsonObject edge = new JsonObject();
        edge.addProperty("edgeId", edgeId);
        edge.addProperty("session", session);
        edge.addProperty("seq", seq.incrementAndGet());
        m.add("edge", edge);
        return m;
    }
}
