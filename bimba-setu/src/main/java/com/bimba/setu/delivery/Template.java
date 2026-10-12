package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A destination's own layout for each reading: a JSON object whose strings (and keys) may hold {field} placeholders.
 * A string that is exactly one placeholder keeps the value's type ("{tagValue}" -> 21.5); placeholders inside longer
 * text are written as text. Unknown fields are refused when the template is saved; missing values become null / "".
 * <pre>{"asset": "{machineId}", "signal": "{tagName}", "value": "{tagValue}", "ts": "{timestampMs}", "site": "{plantId}"}</pre>
 * The 2.0 snake_case names may be used as well: {machine_id}, {tag_value}, {timestamp_ms}, ...
 */
public final class Template {
    public static final List<String> FIELDS = List.of(
            "plantId", "edgeId", "machineId", "tagName", "path", "tagValue", "dataType", "unit", "quality", "qualityDetail",
            "timestamp", "timestampMs", "epochSeconds", "sourceTimestamp", "ingestTime", "messageId", "batchId", "category", "schemaVersion",
            "source.protocol", "source.address", "source.system", "source.tag", "edge.session", "edge.seq",
            "dataTypeName", "writable", "description", "tagValueNumber", "tagValueText",
            "aggregate.fn", "aggregate.min", "aggregate.max", "aggregate.count", "aggregate.windowSeconds", "aggregate.from", "aggregate.to",
            "gap.state", "gap.from", "gap.to", "gap.seconds", "gap.device", "gap.reason");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.]*)\\}");

    private final JsonObject root;

    private Template(JsonObject root) {
        this.root = root;
    }

    /** null for an empty text; IllegalArgumentException with a readable reason when it is not a usable template */
    public static Template compile(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        JsonElement e;
        try {
            e = JsonParser.parseString(text);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("The template is not valid JSON: " + ex.getMessage().replaceAll("^.*?: ", ""));
        }
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException("The template must be a JSON object, like {\"value\": \"{tagValue}\"}.");
        }
        List<String> unknown = new ArrayList<>();
        check(e, unknown);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown field" + (unknown.size() > 1 ? "s " : " ") + String.join(", ", unknown)
                    + ". Available: " + String.join(", ", FIELDS) + ".");
        }
        return new Template(e.getAsJsonObject());
    }

    private static void check(JsonElement e, List<String> unknown) {
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
                names(m.getKey(), unknown);
                check(m.getValue(), unknown);
            }
        } else if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> check(x, unknown));
        } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            names(e.getAsString(), unknown);
        }
    }

    private static void names(String text, List<String> unknown) {
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            if (!FIELDS.contains(field(m.group(1))) && !unknown.contains("{" + m.group(1) + "}")) {
                unknown.add("{" + m.group(1) + "}");
            }
        }
    }

    /** the reading in this layout; env = the batch envelope (batchId, edgeId), may be null */
    public JsonObject render(JsonObject message, JsonObject env) {
        return walk(root, message, env).getAsJsonObject();
    }

    private static JsonElement walk(JsonElement e, JsonObject m, JsonObject env) {
        if (e.isJsonObject()) {
            JsonObject o = new JsonObject();
            for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                o.add(text(x.getKey(), m, env, UnaryOperator.identity()), walk(x.getValue(), m, env));
            }
            return o;
        }
        if (e.isJsonArray()) {
            JsonArray a = new JsonArray();
            e.getAsJsonArray().forEach(x -> a.add(walk(x, m, env)));
            return a;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            Matcher whole = PLACEHOLDER.matcher(e.getAsString());
            if (whole.matches()) {
                return value(whole.group(1), m, env);
            }
            return new JsonPrimitive(text(e.getAsString(), m, env, UnaryOperator.identity()));
        }
        return e;
    }

    /** placeholders in a text (keys, MQTT topics); clean = applied to every inserted value */
    public static String text(String text, JsonObject m, JsonObject env, UnaryOperator<String> clean) {
        Matcher p = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (p.find()) {
            JsonElement v = value(p.group(1), m, env);
            String s = v.isJsonNull() ? "" : v.isJsonPrimitive() ? v.getAsString() : v.toString();
            p.appendReplacement(sb, Matcher.quoteReplacement(clean.apply(s)));
        }
        p.appendTail(sb);
        return sb.toString();
    }

    /** whether {name} is a field a template can use (1.x or 2.0 name) */
    static boolean known(String name) {
        return FIELDS.contains(field(name));
    }

    /** a placeholder's field: the 2.0 snake_case names work too ({plant_id} = {plantId}, {edge.edge_id} = {edge.edgeId}) */
    private static String field(String name) {
        if (name.indexOf('_') < 0) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("\\.")) {
            sb.append(sb.length() > 0 ? "." : "").append(com.bimba.setu.canonical.CanonicalV2.camel(part));
        }
        return sb.toString();
    }

    static JsonElement value(String placeholder, JsonObject m, JsonObject env) {
        String name = field(placeholder);
        switch (name) {
            case "path" -> {
                return new JsonPrimitive(Json.str(m, "machineId") + "/" + Json.str(m, "tagName"));
            }
            case "timestampMs", "epochSeconds" -> {
                try {
                    long ms = Instant.parse(Json.str(m, "timestamp")).toEpochMilli();
                    return "timestampMs".equals(name) ? new JsonPrimitive(ms) : new JsonPrimitive(ms / 1000.0);
                } catch (RuntimeException e) {
                    return JsonNull.INSTANCE;
                }
            }
            case "edgeId" -> {
                String id = env != null ? Json.str(env, "edgeId") : "";
                return new JsonPrimitive(id.isEmpty() && m.has("edge") ? Json.str(m.getAsJsonObject("edge"), "edgeId") : id);
            }
            case "batchId" -> {
                return env == null ? JsonNull.INSTANCE : new JsonPrimitive(Json.str(env, "batchId"));
            }
            case "tagValueNumber" -> {                 // for a number column: numbers, true / false as 1 / 0, else null
                JsonElement v = m.get("tagValue");
                if (v == null || !v.isJsonPrimitive()) {
                    return JsonNull.INSTANCE;
                }
                if (v.getAsJsonPrimitive().isNumber()) {
                    return v.deepCopy();
                }
                return v.getAsJsonPrimitive().isBoolean() ? new JsonPrimitive(v.getAsBoolean() ? 1 : 0) : JsonNull.INSTANCE;
            }
            case "tagValueText" -> {                   // for a text column: every value as text, null stays null
                JsonElement v = m.get("tagValue");
                return v == null || v.isJsonNull() ? JsonNull.INSTANCE
                        : new JsonPrimitive(v.isJsonPrimitive() ? Formats.plain(v.getAsJsonPrimitive()) : v.toString());
            }
            default -> {
                JsonElement cur = m;
                for (String part : name.split("\\.")) {
                    if (cur == null || !cur.isJsonObject() || !cur.getAsJsonObject().has(part)) {
                        return JsonNull.INSTANCE;
                    }
                    cur = cur.getAsJsonObject().get(part);
                }
                return cur == null ? JsonNull.INSTANCE : cur.deepCopy();
            }
        }
    }
}
