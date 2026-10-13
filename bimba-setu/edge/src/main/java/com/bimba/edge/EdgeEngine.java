package com.bimba.edge;

import com.bimba.setu.collect.Collector;
import com.bimba.setu.collect.Dnp3Driver;
import com.bimba.setu.collect.Driver;
import com.bimba.setu.collect.Iec104Driver;
import com.bimba.setu.collect.ModbusDriver;
import com.bimba.setu.collect.NetScan;
import com.bimba.setu.collect.MqttDriver;
import com.bimba.setu.collect.OpcDaDriver;
import com.bimba.setu.collect.LogixDriver;
import com.bimba.setu.collect.S7Driver;
import com.bimba.setu.collect.OpcUaDriver;
import com.bimba.setu.collect.OpcUaPki;
import com.bimba.setu.collect.SqlDriver;
import com.bimba.setu.config.TagModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The edge collector itself, without its transport: data sources in ({"sources":[..]}), canonical messages out, and the
 * console requests (test, browse, scan, read). Used by EdgeMain (JSON lines for the Windows agent service) and in-process
 * by the Linux site agent (com.bimba.agent).
 */
public final class EdgeEngine {
    private final Consumer<JsonObject> readings;
    private final Consumer<String> log;
    private Collector collector;

    /** @param readings every canonical message (change of value or quality); @param log one line per event */
    public EdgeEngine(Consumer<JsonObject> readings, Consumer<String> log) {
        this.readings = readings;
        this.log = log;
    }

    /** {"plantId":..,"edgeId":..,"sources":[{id,name,kind,settings,tags,pollMs,enabled}]}: replaces what is collected */
    public synchronized void configure(JsonObject cfg) {
        stop();
        List<TagModel.Tag> tags = new ArrayList<>();
        JsonObject sources = new JsonObject();
        JsonArray list = cfg.has("sources") && cfg.get("sources").isJsonArray() ? cfg.getAsJsonArray("sources") : new JsonArray();
        int count = 0;
        for (JsonElement e : list) {
            JsonObject s = e.getAsJsonObject();
            String kind = str(s, "kind");
            if (!collectable(kind) || (s.has("enabled") && !s.get("enabled").getAsBoolean())) {
                continue;
            }
            String id = str(s, "id");
            String machine = str(s, "name");
            JsonObject settings = s.has("settings") && s.get("settings").isJsonObject() ? s.getAsJsonObject("settings").deepCopy() : new JsonObject();
            if (s.has("pollMs")) {
                settings.add("pollMs", s.get("pollMs"));
            }
            if (!sources.has(kind)) {
                sources.add(kind, new JsonObject());
            }
            sources.getAsJsonObject(kind).add(id, settings);
            for (JsonElement te : s.has("tags") && s.get("tags").isJsonArray() ? s.getAsJsonArray("tags") : new JsonArray()) {
                JsonObject t = te.getAsJsonObject();
                String own = str(t, "machine");
                tags.add(tag(own == null || own.isBlank() ? machine : own, id, kind, t));
            }
            count++;
        }
        if (tags.isEmpty()) {
            log.accept("no tags to collect (" + count + " source(s))");
            return;
        }
        collector = new Collector(strOr(cfg, "plantId", "PLANT"), strOr(cfg, "edgeId", OpcUaPki.hostName()),
                cfg.has("pollMs") ? cfg.get("pollMs").getAsLong() : 1000, tags, sources, readings);
        collector.start();
        log.accept("collecting " + tags.size() + " tag(s) from " + count + " source(s)");
    }

    /** opcua, modbus, mqtt, s7, eip, dnp3, iec104, sql and opcda (opcda needs the Windows bridge, see OpcDaBridge) */
    public static boolean collectable(String kind) {
        return "opcua".equals(kind) || "modbus".equals(kind) || "mqtt".equals(kind) || "s7".equals(kind) || "eip".equals(kind)
                || "dnp3".equals(kind) || "iec104".equals(kind) || "sql".equals(kind) || "opcda".equals(kind);
    }

    public synchronized boolean collecting() {
        return collector != null;
    }

    public synchronized void stop() {
        if (collector != null) {
            collector.stop();
            collector = null;
        }
    }

    /** {devices:[..], tagEvents:..} of the running collector ({devices:[]} when idle) */
    public Map<String, Object> status() {
        Collector c;
        synchronized (this) {
            c = collector;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (c != null) {
            Map<String, Object> s = c.status();
            m.put("devices", s.get("devices"));
            m.put("tagEvents", s.get("tagEvents"));
        } else {
            m.put("devices", List.of());
        }
        return m;
    }

    /**
     * A console request: op test | browse | scan | read | netscan (NetScan), msg {source:{kind,settings}, nodeId, limit, tags}.
     * Returns the result object; throws with a readable message when the request fails.
     */
    public static Object command(String op, JsonObject msg) throws Exception {
        JsonObject source = msg.has("source") && msg.get("source").isJsonObject() ? msg.getAsJsonObject("source") : new JsonObject();
        String kind = str(source, "kind");
        JsonObject settings = source.has("settings") && source.get("settings").isJsonObject() ? source.getAsJsonObject("settings") : new JsonObject();
        if ("netscan".equals(op)) {
            return NetScan.run(msg);                       // the source only names a type the Windows agent hands over
        }
        if ("opcua".equals(kind) && "test".equals(op)) {
            return OpcUaDriver.test(settings);
        } else if ("opcua".equals(kind) && "browse".equals(op)) {
            return OpcUaDriver.browse(settings, str(msg, "nodeId"), msg.has("limit") ? msg.get("limit").getAsInt() : 500);
        } else if ("opcua".equals(kind) && "scan".equals(op)) {
            return OpcUaDriver.scan(settings, str(msg, "nodeId"), msg.has("limit") ? msg.get("limit").getAsInt() : 5000);
        } else if (("s7".equals(kind) || "eip".equals(kind)) && ("test".equals(op) || "read".equals(op))) {
            List<TagModel.Tag> tags = new ArrayList<>();
            for (JsonElement e : msg.has("tags") && msg.get("tags").isJsonArray() ? msg.getAsJsonArray("tags") : new JsonArray()) {
                tags.add(tag("test", "test", kind, e.getAsJsonObject()));
            }
            return "s7".equals(kind) ? S7Driver.test(settings, tags) : LogixDriver.test(settings, tags);
        } else if (("dnp3".equals(kind) || "iec104".equals(kind) || "sql".equals(kind)) && "browse".equals(op)) {
            int limit = msg.has("limit") ? msg.get("limit").getAsInt() : 1000;
            return switch (kind) {
                case "dnp3" -> Dnp3Driver.browse(settings, str(msg, "nodeId"), limit);
                case "iec104" -> Iec104Driver.browse(settings, str(msg, "nodeId"), limit);
                default -> SqlDriver.browse(settings, limit);
            };
        } else if (("dnp3".equals(kind) || "iec104".equals(kind) || "sql".equals(kind)) && ("test".equals(op) || "read".equals(op))) {
            List<TagModel.Tag> tags = new ArrayList<>();
            for (JsonElement e : msg.has("tags") && msg.get("tags").isJsonArray() ? msg.getAsJsonArray("tags") : new JsonArray()) {
                tags.add(tag("test", "test", kind, e.getAsJsonObject()));
            }
            return switch (kind) {
                case "dnp3" -> Dnp3Driver.test(settings, tags);
                case "iec104" -> Iec104Driver.test(settings, tags);
                default -> SqlDriver.test(settings, tags);
            };
        } else if ("mqtt".equals(kind) && "browse".equals(op)) {
            return MqttDriver.browse(settings, str(msg, "nodeId"), msg.has("limit") ? msg.get("limit").getAsInt() : 1000);
        } else if ("mqtt".equals(kind) && ("test".equals(op) || "read".equals(op))) {
            List<TagModel.Tag> tags = new ArrayList<>();
            for (JsonElement e : msg.has("tags") && msg.get("tags").isJsonArray() ? msg.getAsJsonArray("tags") : new JsonArray()) {
                tags.add(tag("test", "test", kind, e.getAsJsonObject()));
            }
            return MqttDriver.test(settings, tags);
        } else if ("opcda".equals(kind) && "browse".equals(op)) {
            return OpcDaDriver.browse(settings, str(msg, "nodeId"), msg.has("limit") ? msg.get("limit").getAsInt() : 1000);
        } else if ("opcda".equals(kind) && ("test".equals(op) || "read".equals(op))) {
            List<TagModel.Tag> tags = new ArrayList<>();
            for (JsonElement e : msg.has("tags") && msg.get("tags").isJsonArray() ? msg.getAsJsonArray("tags") : new JsonArray()) {
                tags.add(tag("test", "test", kind, e.getAsJsonObject()));
            }
            return tags.isEmpty() ? OpcDaDriver.test(settings) : OpcDaDriver.readOnce(settings, tags);
        } else if ("test".equals(op) || "read".equals(op)) {
            return readOnce(kind, settings, msg);
        }
        throw new Exception(op + " is not available for " + kind + " sources");
    }

    /** connect (Modbus) or read the given tags once: {ok, values:[{name, address, value, good, detail}]} */
    private static Map<String, Object> readOnce(String kind, JsonObject settings, JsonObject msg) throws Exception {
        List<TagModel.Tag> tags = new ArrayList<>();
        for (JsonElement e : msg.has("tags") && msg.get("tags").isJsonArray() ? msg.getAsJsonArray("tags") : new JsonArray()) {
            tags.add(tag("test", "test", kind, e.getAsJsonObject()));
        }
        Driver d = switch (kind == null ? "" : kind) {
            case "modbus" -> new ModbusDriver(settings);
            case "opcua" -> new OpcUaDriver(settings);
            default -> throw new Exception("unsupported source kind " + kind);
        };
        try {
            List<Driver.Reading> values = d.read(tags);      // also connects: throws when the device cannot be reached
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < tags.size(); i++) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("name", tags.get(i).name());
                v.put("address", tags.get(i).address());
                v.put("value", values.get(i).value());
                v.put("good", values.get(i).good());
                v.put("quality", values.get(i).quality().name());
                v.put("detail", values.get(i).detail());
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

    static TagModel.Tag tag(String machine, String sourceId, String kind, JsonObject t) {
        String name = strOr(t, "name", strOr(t, "address", "tag")).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        String address = strOr(t, "address", "");
        if ("modbus".equals(kind)) {
            address = address.trim().toUpperCase(Locale.ROOT);
        }
        boolean scaled = t.has("rawLow") && t.has("rawHigh") && t.has("scaledLow") && t.has("scaledHigh");
        TagModel.Kind k = switch (kind == null ? "" : kind) {
            case "modbus" -> TagModel.Kind.MODBUS;
            case "opcda" -> TagModel.Kind.OPCDA;
            case "mqtt" -> TagModel.Kind.MQTT;
            case "s7" -> TagModel.Kind.S7;
            case "eip" -> TagModel.Kind.EIP;
            case "dnp3" -> TagModel.Kind.DNP3;
            case "iec104" -> TagModel.Kind.IEC104;
            case "sql" -> TagModel.Kind.SQL;
            default -> TagModel.Kind.OPCUA;
        };
        return new TagModel.Tag(machine, name, machine + "/" + name, k,
                sourceId, address, str(t, "dataType"), str(t, "unit"), num(t, "rawLow"), num(t, "rawHigh"), num(t, "scaledLow"),
                num(t, "scaledHigh"), scaled, str(t, "sourceUnit"), TagModel.optNum(t, "engLow"), TagModel.optNum(t, "engHigh"));
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static String strOr(JsonObject o, String k, String d) {
        String v = str(o, k);
        return v == null || v.isBlank() ? d : v;
    }

    private static double num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
