package com.bimba.gateway.canonical;

import com.bimba.gateway.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Message format 2.0: the canonical telemetry envelope of the BRD/FRD (FR-CAN-01, section 8.1), snake_case:
 * <pre>
 * {"schema_version":"2.0","message_id":..,"plant_id":..,"machine_id":..,"tag_name":..,"tag_value":74.82,"data_type":"Double",
 *  "unit":"degC","quality":"GOOD","timestamp":..,"source":{"path":"DIRECT","protocol":"OPCUA","address":..,"interface":"BIMBA_GATEWAY"},
 *  "ingest_time":..,"attributes":{..}}
 * </pre>
 * The gateway keeps every message in the 1.x layout inside (selections, pipelines, templates, the BIMBA database) and
 * converts at its edges: 2.x messages that come in are turned into 1.x ({@link #toInternal}), and a destination set to
 * format 2 gets every reading and batch in 2.0 ({@link #message}, {@link #envelope}). Destinations set to 1 keep
 * receiving exactly what they received before. Fields without a fixed mapping are converted camelCase &lt;-&gt; snake_case,
 * so later 1.x fields reach 2.0 consumers too; "attributes" (free key/values) are passed on unchanged.
 */
public final class CanonicalV2 {
    public static final String VERSION = "2.0";

    /** 1.x dataType -> 2.0 data_type */
    private static final Map<String, String> TYPE_OUT = Map.of("number", "Double", "boolean", "Boolean", "string", "String");
    private static final Set<String> NUMERIC = Set.of("double", "float", "float4", "float8", "int1", "int2", "int4", "int8",
            "integer", "int", "long", "short", "byte", "decimal", "number");
    /** 1.x source.protocol -> 2.0 source.protocol */
    private static final Map<String, String> PROTOCOL_OUT = Map.of("opcua", "OPCUA", "modbus", "MODBUS_TCP", "opcda", "OPCDA",
            "expression", "EXPRESSION", "ignition", "IGNITION", "test", "TEST");
    private static final Map<String, String> PROTOCOL_IN = Map.of("OPCUA", "opcua", "MODBUS_TCP", "modbus", "MODBUS", "modbus",
            "OPCDA", "opcda", "EXPRESSION", "expression", "IGNITION", "ignition", "TEST", "test");

    private CanonicalV2() {
    }

    /** a 1.x message (the gateway's own layout) in format 2.0 */
    public static JsonObject message(JsonObject m) {
        JsonObject out = new JsonObject();
        out.addProperty("schema_version", VERSION);
        for (Map.Entry<String, JsonElement> e : m.entrySet()) {
            String k = e.getKey();
            switch (k) {
                case "schemaVersion" -> {
                }
                case "dataType" -> {
                    String t = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "null";
                    if (TYPE_OUT.containsKey(t)) {
                        out.addProperty("data_type", TYPE_OUT.get(t));
                    } else {
                        out.add("data_type", com.google.gson.JsonNull.INSTANCE);
                    }
                }
                case "source" -> out.add("source", sourceOut(e.getValue()));
                case "attributes" -> out.add("attributes", e.getValue().deepCopy());
                default -> out.add(snake(k), keysTo(e.getValue(), true));
            }
        }
        return out;
    }

    private static JsonElement sourceOut(JsonElement s) {
        if (!s.isJsonObject()) {
            return s.deepCopy();
        }
        JsonObject in = s.getAsJsonObject();
        JsonObject out = keysTo(in, true).getAsJsonObject();
        String protocol = Json.str(in, "protocol");
        out.addProperty("path", Json.str(in, "path").isEmpty() ? ("ignition".equals(protocol) ? "IGNITION" : "DIRECT") : Json.str(in, "path"));
        if (!protocol.isEmpty()) {
            out.addProperty("protocol", PROTOCOL_OUT.getOrDefault(protocol, protocol.toUpperCase(Locale.ROOT)));
        }
        if (Json.str(in, "interface").isEmpty()) {
            out.addProperty("interface", in.has("agentId") ? "BIMBA_SITE_AGENT" : "BIMBA_GATEWAY");
        }
        return out;
    }

    /**
     * A 1.x batch envelope in format 2.0 (the BRD's batch form): {schema_version, batch_id, plant_id, edge_id, source,
     * created_at, count, records:[..]}; records = the readings as the destination sends them.
     */
    public static JsonObject envelope(JsonObject env, JsonArray records) {
        JsonObject out = new JsonObject();
        out.addProperty("schema_version", VERSION);
        for (Map.Entry<String, JsonElement> e : env.entrySet()) {
            String k = e.getKey();
            if (!"schemaVersion".equals(k) && !"messages".equals(k)) {
                out.add(snake(k), e.getValue().deepCopy());
            }
        }
        out.add("records", records);
        return out;
    }

    /** a 2.x message as the gateway keeps it inside (1.x layout, schemaVersion = the 1.x version it now is) */
    public static JsonObject toInternal(JsonObject m) {
        JsonObject out = new JsonObject();
        out.addProperty("schemaVersion", com.bimba.gateway.collect.Canonical.VERSION);
        for (Map.Entry<String, JsonElement> e : m.entrySet()) {
            String k = e.getKey();
            switch (k) {
                case "schema_version", "data_type" -> {
                }
                case "source" -> out.add("source", sourceIn(e.getValue()));
                case "attributes" -> out.add("attributes", e.getValue().deepCopy());
                default -> out.add(camel(k), keysTo(e.getValue(), false));
            }
        }
        JsonElement v = m.get("tag_value");
        String type = Json.str(m, "data_type").toLowerCase(Locale.ROOT);
        String dataType;
        if (v == null || v.isJsonNull()) {
            dataType = "null";
        } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
            dataType = "boolean";
        } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
            dataType = "number";
        } else {
            dataType = NUMERIC.contains(type) ? "number" : "string";      // the schema already refused a mismatch
        }
        out.addProperty("dataType", dataType);
        return out;
    }

    private static JsonElement sourceIn(JsonElement s) {
        if (!s.isJsonObject()) {
            return s.deepCopy();
        }
        JsonObject out = keysTo(s, false).getAsJsonObject();
        String protocol = Json.str(s.getAsJsonObject(), "protocol");
        if (!protocol.isEmpty()) {
            out.addProperty("protocol", PROTOCOL_IN.getOrDefault(protocol, protocol.toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    /** the keys of objects (recursively) in snake_case (toSnake) or camelCase; values stay */
    private static JsonElement keysTo(JsonElement e, boolean toSnake) {
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                out.add(toSnake ? snake(x.getKey()) : camel(x.getKey()), keysTo(x.getValue(), toSnake));
            }
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            e.getAsJsonArray().forEach(x -> out.add(keysTo(x, toSnake)));
            return out;
        }
        return e.deepCopy();
    }

    /** messageId -> message_id */
    public static String snake(String k) {
        StringBuilder sb = new StringBuilder(k.length() + 4);
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && k.charAt(i - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** message_id -> messageId */
    public static String camel(String k) {
        StringBuilder sb = new StringBuilder(k.length());
        boolean up = false;
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c == '_' && i > 0) {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }
}
