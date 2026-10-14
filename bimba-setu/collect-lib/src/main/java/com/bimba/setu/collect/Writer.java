package com.bimba.setu.collect;

import com.bimba.setu.config.TagModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Machine commands: writes one value to one tag (OPC UA, Modbus TCP, MQTT / Sparkplug B), each on its own short
 * connection beside the collector's. The value arrives as an engineering value (the tag's unit, as the console shows it);
 * it goes back to the device's unit and raw range ({@link #toDevice}), is written, read back and compared, and the answer
 * tells what the tag read before and after in engineering values:
 * {ok, target, value, deviceValue, before, after, verified (true | false | null = cannot be checked), detail}.
 * Setu has already checked that the tag is writable and the value inside its limits.
 */
public final class Writer {
    /** how long an MQTT command waits for the machine to report the new value */
    static final long MQTT_CONFIRM_MS = 5000;
    /** OPC UA and Modbus are read back this long after the write: a PLC applies its own limits in its next scan */
    static final long SETTLE_MS = 500;

    private Writer() {
    }

    /** the kinds that take commands */
    public static boolean writable(String kind) {
        return "opcua".equals(kind) || "modbus".equals(kind) || "mqtt".equals(kind);
    }

    public static Map<String, Object> write(String kind, JsonObject settings, TagModel.Tag tag, JsonObject tagJson, JsonElement value)
            throws Exception {
        if (!writable(kind)) {
            throw new Exception("commands are not available for " + kind + " sources");
        }
        Object eng = value(value);
        Object raw = toDevice(tag, eng);
        Object before, after;
        Boolean verified;
        String target, detail = null;
        switch (kind) {
            case "modbus" -> {
                ModbusDriver d = new ModbusDriver(settings);
                try {
                    Object[] r = d.write(tag.address(), raw, SETTLE_MS);
                    before = r[0];
                    after = r[1];
                    raw = r[2];                                   // whole counts
                    target = d.describe();
                } finally {
                    d.close();
                }
                verified = same(raw, after);
            }
            case "opcua" -> {
                OpcUaDriver d = new OpcUaDriver(settings);
                try {
                    Object[] r = d.write(tag.address(), raw, SETTLE_MS);
                    before = r[0];
                    after = r[1];
                    raw = r[2];                                   // in the node's type (integers rounded)
                    target = d.describe();
                } finally {
                    d.close();
                }
                verified = same(raw, after);
            }
            default -> {
                String writeAddress = tagJson.has("writeAddress") && tagJson.get("writeAddress").isJsonPrimitive()
                        ? tagJson.get("writeAddress").getAsString() : null;
                Map<String, Object> r = MqttDriver.write(settings, tag, writeAddress, raw, MQTT_CONFIRM_MS);
                before = r.get("before");
                after = r.get("after");
                target = r.get("target") + " → " + r.get("topic");
                verified = (Boolean) r.get("confirmed");
                if (verified == null) {
                    detail = "Published on the topic the tag is read from: the broker hands the command back, so it cannot be confirmed separately.";
                } else if (!verified) {
                    detail = "Published; the machine has not reported the new value within " + MQTT_CONFIRM_MS / 1000 + " s.";
                }
            }
        }
        if (Boolean.FALSE.equals(verified) && detail == null) {
            Object reads = engineering(tag, after);
            detail = "Written, but the machine reads " + (reads instanceof Number n ? MqttDriver.text(n) : reads)
                    + " (it may limit or change the value itself).";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("target", target);
        m.put("value", eng);
        m.put("deviceValue", raw);
        m.put("before", engineering(tag, before));
        m.put("after", engineering(tag, after));
        m.put("verified", verified);
        m.put("detail", detail);
        return m;
    }

    /** a JSON value as Boolean, Double or String */
    static Object value(JsonElement e) throws Exception {
        if (e == null || !e.isJsonPrimitive()) {
            throw new Exception("which value?");
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            double d = p.getAsDouble();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new Exception("not a number");
            }
            return d;
        }
        return p.getAsString();
    }

    /**
     * engineering value -> device value: the unit conversion backwards (unit -> device unit), then the linear scaling
     * backwards (scaled range -> raw range). Booleans and text stay as they are.
     */
    public static Object toDevice(TagModel.Tag t, Object value) {
        if (!(value instanceof Number n)) {
            return value;
        }
        double x = n.doubleValue();
        if (t.converts()) {
            x = Units.convert(x, t.unit().trim(), t.sourceUnit().trim());
        }
        if (t.scaled() && t.scaledHigh() != t.scaledLow()) {
            x = (x - t.scaledLow()) * (t.rawHigh() - t.rawLow()) / (t.scaledHigh() - t.scaledLow()) + t.rawLow();
        }
        return x;
    }

    /** a device value as the collector reports it (scaled, converted, rounded) */
    public static Object engineering(TagModel.Tag t, Object raw) {
        if (!(raw instanceof Number)) {
            return raw;
        }
        Driver.Reading r = Collector.engineering(t, Driver.Reading.ok(raw));
        return r.value() == null ? raw : r.value();
    }

    /** the device holds what was written: numbers within 1e-5 (Float nodes), booleans as 0 / 1 too, text exactly */
    static boolean same(Object wanted, Object got) {
        if (wanted == null || got == null) {
            return false;
        }
        if (wanted instanceof Boolean || got instanceof Boolean) {
            try {
                return bool(wanted) == bool(got);
            } catch (Exception e) {
                return false;
            }
        }
        if (wanted instanceof Number a && got instanceof Number b) {
            double x = a.doubleValue(), y = b.doubleValue();
            return Math.abs(x - y) <= Math.max(1e-9, Math.abs(x) * 1e-5);
        }
        String w = wanted instanceof Number n ? MqttDriver.text(n) : String.valueOf(wanted);
        String g = got instanceof Number n ? MqttDriver.text(n) : String.valueOf(got);
        return w.equals(g);
    }

    /** true / false, 1 / 0, "true" / "false", "on" / "off" */
    static boolean bool(Object v) throws Exception {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n && (n.doubleValue() == 0 || n.doubleValue() == 1)) {
            return n.doubleValue() == 1;
        }
        if (v instanceof String s) {
            switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "true", "on", "1" -> {
                    return true;
                }
                case "false", "off", "0" -> {
                    return false;
                }
                default -> {
                }
            }
        }
        throw new Exception("this tag takes on / off (true or false)");
    }
}
