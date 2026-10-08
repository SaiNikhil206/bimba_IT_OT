package com.bimba.gateway.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tag tree (provisionTagsFile, e.g. demo-tags.json; edited in BIMBA Operations → Settings):
 * <pre>
 * { "name": "BIMBA", "tagType": "Folder", "tags": [
 *   { "name": "PUMP-P1", "tagType": "Folder", "tags": [            first folder level = machine
 *     { "name": "inlet_flow", "tagType": "AtomicTag", "valueSource": "opc",
 *       "opcServer": "BIMBA Modbus", "opcItemPath": "ns=1;s=[PLC]HR2",   Modbus device PLC, holding register 2
 *       "dataType": "Float8", "engUnit": "m3/h", "scaleMode": "Linear", "rawLow": 0, "rawHigh": 10, "scaledLow": 0, "scaledHigh": 1 },
 *     { "name": "bus_voltage", "valueSource": "opc", "opcServer": "METER", "opcItemPath": "ns=2;s=Meter.Voltage" },  OPC UA device METER
 *     { "name": "speed", "valueSource": "expr", "expression": "240 + 15 * sin(toMillis(now(1000)) / 60000.0)" } ]}]}
 * </pre>
 * The layout is the Ignition tag-export format, so tag exports from an existing Ignition installation can be used
 * unchanged ("Ignition OPC UA Server" is accepted as the Modbus driver name).
 */
public final class TagModel {
    public static final String MODBUS_DRIVER = "BIMBA Modbus";
    private static final Pattern MODBUS_ITEM = Pattern.compile("^ns=1;s=\\[([^\\]]+)](.+)$");

    public enum Kind { MODBUS, OPCUA, EXPRESSION }

    /** One tag: which machine/tag name it reports as, and where its value comes from. */
    public record Tag(String machine, String name, String path, Kind kind, String device, String address,
                      String dataType, String unit, double rawLow, double rawHigh, double scaledLow, double scaledHigh,
                      boolean scaled) {
        /** raw device value -> engineering value (Linear scaling) */
        public double scale(double raw) {
            if (!scaled || rawHigh == rawLow) {
                return raw;
            }
            return (raw - rawLow) * (scaledHigh - scaledLow) / (rawHigh - rawLow) + scaledLow;
        }
    }

    private TagModel() {
    }

    public static List<Tag> parse(JsonObject root, List<String> problems) {
        List<Tag> out = new ArrayList<>();
        walk(root, "", out, problems);
        return out;
    }

    private static void walk(JsonObject node, String prefix, List<Tag> out, List<String> problems) {
        JsonElement children = node.get("tags");
        if (children == null || !children.isJsonArray()) {
            return;
        }
        for (JsonElement e : (JsonArray) children) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject t = e.getAsJsonObject();
            String name = str(t, "name");
            if (name == null) {
                continue;
            }
            if ("Folder".equals(str(t, "tagType")) || t.has("tags")) {
                walk(t, prefix + name + "/", out, problems);
                continue;
            }
            String path = prefix + name;
            String[] parts = path.split("/");
            if (parts.length < 2) {
                problems.add(path + ": tags must sit in a machine folder");
                continue;
            }
            Tag tag = tag(parts, path, t, problems);
            if (tag != null) {
                out.add(tag);
            }
        }
    }

    private static Tag tag(String[] parts, String path, JsonObject t, List<String> problems) {
        String machine = parts[0];
        StringBuilder n = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            n.append(n.length() > 0 ? "_" : "").append(parts[i]);
        }
        String tagName = n.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        String source = str(t, "valueSource");
        String server = str(t, "opcServer");
        String item = str(t, "opcItemPath");
        Kind kind;
        String device;
        String address;
        if ("expr".equals(source)) {
            kind = Kind.EXPRESSION;
            device = null;
            address = str(t, "expression");
        } else if ("opc".equals(source) && item != null && isModbusDriver(server) && MODBUS_ITEM.matcher(item).matches()) {
            Matcher m = MODBUS_ITEM.matcher(item);
            m.matches();
            kind = Kind.MODBUS;
            device = m.group(1);
            address = m.group(2).trim().toUpperCase(Locale.ROOT);
        } else if ("opc".equals(source) && item != null && server != null) {
            kind = Kind.OPCUA;
            device = server;
            address = item;
        } else {
            problems.add(path + ": unsupported value source (use a Modbus register, an OPC UA node or an expression)");
            return null;
        }
        if (address == null || address.isBlank()) {
            problems.add(path + ": no address / expression");
            return null;
        }
        boolean scaled = "Linear".equals(str(t, "scaleMode"));
        return new Tag(machine, tagName, path, kind, device, address, str(t, "dataType"), str(t, "engUnit"),
                num(t, "rawLow", 0), num(t, "rawHigh", 1), num(t, "scaledLow", 0), num(t, "scaledHigh", 1), scaled);
    }

    public static boolean isModbusDriver(String server) {
        return server != null && (server.equals(MODBUS_DRIVER) || server.equalsIgnoreCase("modbus")
                || server.equals("Ignition OPC UA Server"));
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static double num(JsonObject o, String k, double fallback) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
