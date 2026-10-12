package com.bimba.setu.collect;

import com.bimba.setu.collect.Canonical.Quality;
import com.bimba.setu.config.TagModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OPC DA (classic) server through the bridge process ({@link OpcDaBridge}). Settings: progId (or "{clsid}"), host (empty =
 * this computer, else DCOM to that computer), pollMs (the group's update rate), readFrom cache | device (default cache:
 * the server keeps its cache fresh at the update rate). Tag address = the item id, e.g. "Channel1.Device1.Tag1".
 * Values: OPC quality GOOD / UNCERTAIN / BAD with the substatus as detail, the server's time stamp as source time.
 */
public final class OpcDaDriver implements Driver {
    private final String progId;
    private final String host;
    private final int rateMs;
    private final boolean fromDevice;

    public OpcDaDriver(JsonObject settings) {
        progId = str(settings, "progId");
        host = str(settings, "host");
        rateMs = settings.has("pollMs") && settings.get("pollMs").isJsonPrimitive() ? settings.get("pollMs").getAsInt() : 1000;
        fromDevice = "device".equalsIgnoreCase(str(settings, "readFrom"));
        if (progId == null || progId.isBlank()) {
            throw new IllegalArgumentException("OPC DA source without progId");
        }
    }

    @Override
    public List<Reading> read(List<TagModel.Tag> tags) throws Exception {
        JsonObject req = request("read", "collect");
        JsonArray items = new JsonArray();
        tags.forEach(t -> items.add(t.address()));
        req.add("items", items);
        req.addProperty("rateMs", rateMs);
        req.addProperty("fromDevice", fromDevice);
        JsonArray values = OpcDaBridge.get().call(req, Math.max(15_000, rateMs * 3L)).getAsJsonObject().getAsJsonArray("values");
        List<Reading> out = new ArrayList<>(tags.size());
        for (int i = 0; i < tags.size(); i++) {
            out.add(reading(i < values.size() ? values.get(i).getAsJsonObject() : null));
        }
        return out;
    }

    /** one value from the bridge: {v, q, t, e} */
    static Reading reading(JsonObject v) {
        if (v == null) {
            return Reading.bad("no value from the OPC DA server");
        }
        String error = str(v, "e");
        if (error != null) {
            return Reading.bad(error);
        }
        int q = v.has("q") && v.get("q").isJsonPrimitive() ? v.get("q").getAsInt() : 0;
        Instant time = null;
        try {
            String t = str(v, "t");
            time = t == null ? null : Instant.parse(t);
        } catch (RuntimeException ignored) {
            // no source time
        }
        Object value = plain(v.get("v"));
        Quality quality = (q & 0xC0) == 0xC0 ? Quality.GOOD : (q & 0xC0) == 0x40 ? Quality.UNCERTAIN : Quality.BAD;
        if (quality == Quality.GOOD) {
            return value == null ? Reading.bad("the server returned no value") : Reading.ok(value, time);
        }
        String detail = substatus(q);
        return quality == Quality.UNCERTAIN ? Reading.uncertain(value, detail, time) : new Reading(null, Quality.BAD, detail, time);
    }

    private static Object plain(JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        if (e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        if (e.getAsJsonPrimitive().isNumber()) {
            return e.getAsDouble();
        }
        return e.getAsString();
    }

    /** OPC DA quality substatus (bits 5..2) in words */
    static String substatus(int q) {
        int sub = q & 0xFC;
        return switch (sub) {
            case 0x04 -> "configuration error (OPC quality 0x04)";
            case 0x08 -> "not connected (OPC quality 0x08)";
            case 0x0C -> "device failure (OPC quality 0x0C)";
            case 0x10 -> "sensor failure (OPC quality 0x10)";
            case 0x14 -> "last known value: communication lost (OPC quality 0x14)";
            case 0x18 -> "communication failure (OPC quality 0x18)";
            case 0x1C -> "out of service (OPC quality 0x1C)";
            case 0x20 -> "waiting for initial data (OPC quality 0x20)";
            case 0x44 -> "last usable value (OPC quality 0x44)";
            case 0x50 -> "sensor not accurate (OPC quality 0x50)";
            case 0x54 -> "engineering units exceeded (OPC quality 0x54)";
            case 0x58 -> "sub-normal (OPC quality 0x58)";
            default -> ((q & 0xC0) == 0x40 ? "uncertain" : "bad") + String.format(" (OPC quality 0x%02X)", q & 0xFF);
        };
    }

    @Override
    public String describe() {
        return progId + (host == null || host.isBlank() ? "" : " on " + host);
    }

    @Override
    public void close() {
        OpcDaBridge.get().post(request("release", "collect"));
    }

    private JsonObject request(String op, String session) {
        JsonObject r = new JsonObject();
        r.addProperty("op", op);
        r.addProperty("progId", progId);
        if (host != null && !host.isBlank()) {
            r.addProperty("host", host);
        }
        r.addProperty("session", session);
        return r;
    }

    // ------------------------------------------------------------------ console requests

    /** Test connection: {ok, product, version, state, progId, clsid, host, serverTime} */
    public static Map<String, Object> test(JsonObject settings) throws Exception {
        OpcDaDriver d = new OpcDaDriver(settings);
        JsonObject status = OpcDaBridge.get().call(d.request("status", "request"), 30_000).getAsJsonObject();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        for (String k : status.keySet()) {
            m.put(k, status.get(k).isJsonNull() ? null : status.get(k).isJsonPrimitive() ? status.get(k).getAsString() : status.get(k).toString());
        }
        m.put("target", d.describe());
        return m;
    }

    /** the server's address space below a branch (null = root): {items:[{nodeId, name, nodeClass, value, good}], ...} */
    public static Object browse(JsonObject settings, String branch, int limit) throws Exception {
        OpcDaDriver d = new OpcDaDriver(settings);
        JsonObject req = d.request("browse", "request");
        if (branch != null && !branch.isBlank()) {
            req.addProperty("branch", branch);
        }
        req.addProperty("limit", Math.max(1, Math.min(5000, limit)));
        return com.google.gson.JsonParser.parseString(OpcDaBridge.get().call(req, 60_000).toString());
    }

    /** one device read of the given items outside the collection group: {ok, target, values:[{name, address, value, good, quality, detail}]} */
    public static Map<String, Object> readOnce(JsonObject settings, List<TagModel.Tag> tags) throws Exception {
        OpcDaDriver d = new OpcDaDriver(settings);
        JsonObject req = d.request("readOnce", "request");
        JsonArray items = new JsonArray();
        tags.forEach(t -> items.add(t.address()));
        req.add("items", items);
        JsonObject res = OpcDaBridge.get().call(req, 30_000).getAsJsonObject();
        JsonArray values = res.getAsJsonArray("values");
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            Reading r = reading(i < values.size() ? values.get(i).getAsJsonObject() : null);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", tags.get(i).name());
            v.put("address", tags.get(i).address());
            v.put("value", r.value());
            v.put("good", r.good());
            v.put("quality", r.quality().name());
            v.put("detail", r.detail());
            out.add(v);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("target", d.describe());
        JsonObject st = res.has("status") && res.get("status").isJsonObject() ? res.getAsJsonObject("status") : new JsonObject();
        m.put("product", str(st, "product"));
        m.put("version", str(st, "version"));
        m.put("values", out);
        return m;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
