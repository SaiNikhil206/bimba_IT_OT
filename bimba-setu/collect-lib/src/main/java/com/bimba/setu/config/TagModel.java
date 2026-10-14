package com.bimba.setu.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.bimba.setu.collect.Units;

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
 *     { "name": "oil_temp", ..., "engUnit": "degC", "sourceUnit": "degF", "engLow": 0, "engHigh": 120 },  converted, range-checked
 *     { "name": "speed", "valueSource": "expr", "expression": "240 + 15 * sin(toMillis(now(1000)) / 60000.0)" } ]}]}
 * </pre>
 * The layout is the Ignition tag-export format, so tag exports from an existing Ignition installation can be used
 * unchanged ("Ignition OPC UA Server" is accepted as the Modbus driver name).
 * <p>
 * Engineering values: a reading is scaled first (scaleMode Linear: raw range -> scaled range), then converted from the
 * device's unit (sourceUnit, optional) to the tag's unit (engUnit, see {@link Units}). A value outside the engineering
 * range (engLow / engHigh, either optional, in engUnit) is delivered with quality UNCERTAIN.
 */
public final class TagModel {
    public static final String MODBUS_DRIVER = "BIMBA Modbus";
    private static final Pattern MODBUS_ITEM = Pattern.compile("^ns=1;s=\\[([^\\]]+)](.+)$");

    public enum Kind { MODBUS, OPCUA, OPCDA, MQTT, S7, EIP, DNP3, IEC104, SQL, EXPRESSION }

    /** One tag: which machine/tag name it reports as, and where its value comes from. */
    public record Tag(String machine, String name, String path, Kind kind, String device, String address,
                      String dataType, String unit, double rawLow, double rawHigh, double scaledLow, double scaledHigh,
                      boolean scaled, String sourceUnit, Double engLow, Double engHigh) {
        /** raw device value -> engineering value (Linear scaling) */
        public double scale(double raw) {
            if (!scaled || rawHigh == rawLow) {
                return raw;
            }
            return (raw - rawLow) * (scaledHigh - scaledLow) / (rawHigh - rawLow) + scaledLow;
        }

        /** the device reports in another unit than the tag's: values are converted (sourceUnit -> unit) */
        public boolean converts() {
            return sourceUnit != null && !sourceUnit.isBlank() && unit != null && !unit.isBlank() && !sourceUnit.trim().equals(unit.trim());
        }

        /** inside the engineering range (bounds that are not set do not limit) */
        public boolean inRange(double v) {
            return (engLow == null || v >= engLow) && (engHigh == null || v <= engHigh);
        }

        /** why the engineering settings cannot work (unknown units, low above high), null when they can */
        public String engineeringProblem() {
            if (sourceUnit != null && !sourceUnit.isBlank() && (unit == null || unit.isBlank())) {
                return "device unit " + sourceUnit + " needs a unit to convert to";
            }
            if (converts()) {
                String p = Units.problem(sourceUnit.trim(), unit.trim());
                if (p != null) {
                    return p;
                }
            }
            if (engLow != null && engHigh != null && engLow > engHigh) {
                return "engLow " + engLow + " is above engHigh " + engHigh;
            }
            return null;
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
        Tag tag = new Tag(machine, tagName, path, kind, device, address, str(t, "dataType"), str(t, "engUnit"),
                num(t, "rawLow", 0), num(t, "rawHigh", 1), num(t, "scaledLow", 0), num(t, "scaledHigh", 1), scaled,
                str(t, "sourceUnit"), optNum(t, "engLow"), optNum(t, "engHigh"));
        String why = tag.engineeringProblem();
        if (why != null) {
            problems.add(path + ": " + why);        // not collected: a wrong unit is worse than no value
            return null;
        }
        return tag;
    }

    /**
     * A site agent data source's tag ({name, address, dataType, unit, rawLow..scaledHigh, sourceUnit, engLow, engHigh}) as
     * the edge collector reads it (also what Setu uses to show an agent's raw read in engineering values).
     */
    public static Tag of(String machine, String sourceId, String kind, JsonObject t) {
        String name = strOr(t, "name", strOr(t, "address", "tag")).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        String address = strOr(t, "address", "");
        if ("modbus".equals(kind)) {
            address = address.trim().toUpperCase(Locale.ROOT);
        }
        boolean scaled = t.has("rawLow") && t.has("rawHigh") && t.has("scaledLow") && t.has("scaledHigh");
        Kind k = switch (kind == null ? "" : kind) {
            case "modbus" -> Kind.MODBUS;
            case "opcda" -> Kind.OPCDA;
            case "mqtt" -> Kind.MQTT;
            case "s7" -> Kind.S7;
            case "eip" -> Kind.EIP;
            case "dnp3" -> Kind.DNP3;
            case "iec104" -> Kind.IEC104;
            case "sql" -> Kind.SQL;
            default -> Kind.OPCUA;
        };
        return new Tag(machine, name, machine + "/" + name, k, sourceId, address, str(t, "dataType"), str(t, "unit"),
                num(t, "rawLow", 0), num(t, "rawHigh", 0), num(t, "scaledLow", 0), num(t, "scaledHigh", 0), scaled,
                str(t, "sourceUnit"), optNum(t, "engLow"), optNum(t, "engHigh"));
    }

    private static String strOr(JsonObject o, String k, String d) {
        String v = str(o, k);
        return v == null || v.isBlank() ? d : v;
    }

    public static boolean isModbusDriver(String server) {
        return server != null && (server.equals(MODBUS_DRIVER) || server.equalsIgnoreCase("modbus")
                || server.equals("Ignition OPC UA Server"));
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** a number, or null when the field is missing, empty or not a number */
    public static Double optNum(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() && !e.getAsString().isBlank() ? e.getAsDouble() : null;
        } catch (NumberFormatException ex) {
            return null;
        }
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
