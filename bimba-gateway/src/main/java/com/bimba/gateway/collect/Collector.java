package com.bimba.gateway.collect;

import com.bimba.gateway.config.BimbaConfig;
import com.bimba.gateway.config.TagModel;
import com.bimba.gateway.delivery.DeliveryEngine;
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

/**
 * Polls every device (one thread each) and turns every change of value or quality into a canonical
 * BIMBA message (docs/payload.md) for the delivery engine. A device that cannot be reached makes all of
 * its tags BAD (one message per tag) until it answers again.
 */
public class Collector {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Collector");
    private static final MathContext DIGITS = new MathContext(12);

    private final BimbaConfig cfg;
    private final DeliveryEngine delivery;
    private final String session = UUID.randomUUID().toString();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong events = new AtomicLong();
    private final List<Device> devices = new ArrayList<>();
    private final List<TagModel.Tag> tags;
    private ScheduledExecutorService pool;

    public Collector(BimbaConfig cfg, List<TagModel.Tag> tags, JsonObject sources, DeliveryEngine delivery) {
        this.cfg = cfg;
        this.tags = tags;
        this.delivery = delivery;
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
        long every = Math.max(100, cfg.pollMs);
        for (Device d : devices) {
            pool.scheduleWithFixedDelay(d::cycle, 0, every, TimeUnit.MILLISECONDS);
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
        m.put("pollMs", cfg.pollMs);
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
        final String kind;      // modbus | opcua | expression
        final String name;
        final List<TagModel.Tag> tags;
        final Driver driver;
        final Map<String, Expression> expressions = new HashMap<>();
        final Map<String, Object[]> last = new HashMap<>();   // path -> {value, quality}
        volatile String error;
        volatile Instant lastOk;
        volatile int good;
        volatile boolean wasDown;

        Device(String key, List<TagModel.Tag> tags, JsonObject sources) {
            this.kind = key.substring(0, key.indexOf(':'));
            this.name = key.substring(key.indexOf(':') + 1);
            this.tags = tags;
            JsonObject conn = section(sources, kind, name);
            Driver d = null;
            if (kind.equals("modbus") && conn != null) {
                d = new ModbusDriver(conn);
            } else if (kind.equals("opcua") && conn != null) {
                d = new OpcUaDriver(conn);
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
                lastOk = now;
                if (wasDown) {
                    log.info("{} {} reachable again", kind, name);
                    wasDown = false;
                }
                error = null;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!wasDown || !msg.equals(error)) {
                    log.warn("{} {}: {}", kind, name, msg);
                }
                wasDown = true;
                error = msg;
                good = 0;
                Instant now = Instant.now();
                for (TagModel.Tag t : tags) {
                    emit(t, Driver.Reading.bad(msg), now);
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

        private void emit(TagModel.Tag t, Driver.Reading r, Instant at) {
            Object value = normalize(t, r.value());
            String quality = r.good() ? "GOOD" : "BAD";
            Object[] prev = last.get(t.path());
            if (prev != null && Objects.equals(prev[0], value) && prev[1].equals(quality)) {
                return;
            }
            last.put(t.path(), new Object[]{value, quality});
            events.incrementAndGet();
            delivery.offer(message(t, value, quality, r.detail(), at));
        }

        void close() {
            if (driver != null) {
                driver.close();
            }
        }

        Map<String, Object> status() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("name", name.isEmpty() ? "expressions" : name);
            m.put("target", driver == null ? (kind.equals("expression") ? "computed in the gateway" : null) : driver.describe());
            m.put("connected", error == null && lastOk != null);
            m.put("tags", tags.size());
            m.put("machines", tags.stream().map(TagModel.Tag::machine).distinct().sorted().toList());   // per-organization views
            m.put("goodTags", good);
            m.put("lastOk", lastOk == null ? null : lastOk.toString());
            m.put("error", error);
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

    /** Booleans stay booleans; numbers become doubles rounded to 12 significant digits (no 52.300000000000004). */
    private static Object normalize(TagModel.Tag t, Object v) {
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                return null;
            }
            if ("Boolean".equalsIgnoreCase(t.dataType())) {
                return d != 0;
            }
            return new BigDecimal(d).round(DIGITS).doubleValue();
        }
        if (v instanceof Number n) {
            return normalize(t, n.doubleValue());
        }
        return v;
    }

    private JsonObject message(TagModel.Tag t, Object value, String quality, String detail, Instant at) {
        JsonObject m = new JsonObject();
        m.addProperty("schemaVersion", "1.0");
        m.addProperty("messageId", UUID.randomUUID().toString());
        m.addProperty("plantId", cfg.plantId);
        m.addProperty("machineId", t.machine());
        m.addProperty("timestamp", at.toString());
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
        m.addProperty("quality", quality);
        if (detail != null) {
            m.addProperty("qualityDetail", detail);
        }
        m.addProperty("category", "telemetry");
        JsonObject src = new JsonObject();
        src.addProperty("protocol", t.kind().name().toLowerCase());
        src.addProperty("system", cfg.edgeId);
        src.addProperty("address", t.kind() == TagModel.Kind.EXPRESSION ? t.path() : t.device() + "/" + t.address());
        m.add("source", src);
        JsonObject edge = new JsonObject();
        edge.addProperty("edgeId", cfg.edgeId);
        edge.addProperty("session", session);
        edge.addProperty("seq", seq.incrementAndGet());
        m.add("edge", edge);
        return m;
    }
}
