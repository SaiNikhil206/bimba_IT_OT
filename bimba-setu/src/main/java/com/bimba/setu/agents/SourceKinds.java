package com.bimba.setu.agents;

import com.bimba.setu.Json;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The data source types a site agent reads and their settings, from resources/source-kinds.json. The console builds the
 * Add data source form from it (GET /api/gw/agents/source-kinds) and Setu checks saved settings against it, so a new
 * setting is added in one place. Rules a field list cannot say (one setting against another, the SQL query, OPC UA
 * devices) stay in {@link AgentSources}.
 */
public final class SourceKinds {
    private static final Map<String, JsonObject> KINDS = load();

    private SourceKinds() {
    }

    private static Map<String, JsonObject> load() {
        try (InputStream in = SourceKinds.class.getResourceAsStream("/source-kinds.json")) {
            if (in == null) {
                throw new IllegalStateException("source-kinds.json is missing");
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, JsonObject> m = new LinkedHashMap<>();
            for (JsonElement e : root.getAsJsonArray("kinds")) {
                JsonObject k = e.getAsJsonObject();
                m.put(k.get("id").getAsString(), k);
            }
            return m;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read source-kinds.json", e);
        }
    }

    /** every type with its fields, for the console */
    public static Map<String, Object> definitions() {
        JsonArray list = new JsonArray();
        KINDS.values().forEach(list::add);
        return Map.of("kinds", Json.toJava(list.toString()));
    }

    public static boolean known(String kind) {
        return kind != null && KINDS.containsKey(kind);
    }

    public static Set<String> ids() {
        return KINDS.keySet();
    }

    /** the type's name for people ("Modbus TCP"), or the id */
    public static String label(String kind) {
        JsonObject k = kind == null ? null : KINDS.get(kind);
        return k == null ? kind : k.get("label").getAsString();
    }

    /** "OPC UA, Modbus TCP, ... or OPC DA" */
    public static String names() {
        List<String> n = KINDS.values().stream().map(k -> k.get("label").getAsString()).toList();
        return String.join(", ", n.subList(0, n.size() - 1)) + " or " + n.get(n.size() - 1);
    }

    /** the ports a network scan tries for this type (none = the type cannot be found on the network) */
    public static List<Integer> scanPorts(String kind) {
        JsonObject k = KINDS.get(kind);
        List<Integer> out = new ArrayList<>();
        if (k != null && k.has("scan")) {
            k.getAsJsonObject("scan").getAsJsonArray("ports").forEach(p -> out.add(p.getAsInt()));
        }
        return out;
    }

    /**
     * Checks and tidies the settings of a {@code kind} source in place: the fields shown for these settings must be valid
     * (required, length, pattern, range, one of the options); numbers are stored as numbers, empty numbers and time zones
     * are removed (the agent's default applies), an empty choice gets its default. Settings the list does not name
     * (pinned certificates, OPC UA devices) are kept.
     */
    public static void check(String kind, JsonObject s) throws ApiException {
        JsonObject k = KINDS.get(kind);
        if (k == null) {
            throw new ApiException(400, "kind", "Choose " + names() + ".");
        }
        for (JsonElement fe : k.getAsJsonArray("fields")) {
            JsonObject f = fe.getAsJsonObject();
            String key = f.get("key").getAsString();
            String type = Json.str(f, "type");
            if ("note".equals(type) || "certPin".equals(type)) {
                continue;
            }
            if (!shown(f, s)) {
                if (f.has("dropWhenHidden") && f.get("dropWhenHidden").getAsBoolean()) {
                    s.remove(key);
                }
                continue;
            }
            if (f.has("disabledIf") && holds(f.get("disabledIf"), s)) {
                continue;                                                 // greyed out: its value is not used
            }
            JsonElement v = s.get(key);
            boolean blank = v == null || v.isJsonNull() || v.isJsonPrimitive() && v.getAsString().isBlank();
            boolean required = f.has("required") && f.get("required").getAsBoolean();
            if (!blank && !v.isJsonPrimitive()) {
                throw bad(f, "check the value");
            }
            switch (type) {
                case "number" -> {
                    if (blank) {
                        s.remove(key);
                        if (required) {
                            throw bad(f, "required");
                        }
                        continue;
                    }
                    long n;
                    try {
                        n = new BigDecimal(v.getAsString().trim()).longValueExact();     // 1, "1" and 1.0 (stored JSON)
                    } catch (NumberFormatException | ArithmeticException e) {
                        throw bad(f, "a whole number");
                    }
                    if (f.has("min") && n < f.get("min").getAsLong() || f.has("max") && n > f.get("max").getAsLong()) {
                        throw bad(f, range(f));
                    }
                    s.addProperty(key, n);
                }
                case "select", "choice" -> {
                    if (blank) {
                        if (f.has("default")) {
                            s.add(key, f.get("default").deepCopy());
                        } else if (required) {
                            throw bad(f, "required");
                        }
                        continue;
                    }
                    JsonElement match = null;
                    for (JsonElement o : f.getAsJsonArray("options")) {
                        JsonElement ov = o.getAsJsonObject().get("value");
                        if (same(ov.getAsString(), v.getAsString().trim())) {
                            match = ov;
                        }
                    }
                    if (match == null) {
                        throw bad(f, "one of " + options(f));
                    }
                    s.add(key, match.deepCopy());
                }
                case "checkbox" -> {
                    if (!blank) {
                        s.addProperty(key, Boolean.parseBoolean(v.getAsString().trim()));
                    }
                }
                case "timezone" -> {
                    if (blank) {
                        s.remove(key);
                        continue;
                    }
                    try {
                        s.addProperty(key, java.time.ZoneId.of(v.getAsString().trim()).getId());
                    } catch (java.time.DateTimeException e) {
                        throw new ApiException(400, key, f.get("label").getAsString() + ": a name like UTC, Europe/Berlin or Asia/Kolkata.");
                    }
                }
                default -> {                                              // text, textarea, password
                    if (blank) {
                        if (required && !"password".equals(type)) {
                            throw bad(f, "required");
                        }
                        continue;
                    }
                    String x = v.getAsString();
                    if ("text".equals(type)) {
                        x = x.trim();
                        s.addProperty(key, x);
                    }
                    if (f.has("maxLength") && x.length() > f.get("maxLength").getAsInt()) {
                        throw bad(f, "at most " + f.get("maxLength").getAsInt() + " characters");
                    }
                    if (f.has("pattern") && !pattern(f).matcher(x).find()) {
                        throw bad(f, "check the value");
                    }
                }
            }
        }
    }

    /** the field is shown (its showIf holds) for these settings */
    static boolean shown(JsonObject f, JsonObject s) {
        return !f.has("showIf") || holds(f.get("showIf"), s);
    }

    /** a condition {field, in | notIn | matches}, or a list of them that must all hold */
    static boolean holds(JsonElement cond, JsonObject s) {
        if (cond.isJsonArray()) {
            for (JsonElement c : cond.getAsJsonArray()) {
                if (!holds(c, s)) {
                    return false;
                }
            }
            return true;
        }
        JsonObject c = cond.getAsJsonObject();
        String value = Json.str(s, c.get("field").getAsString());
        if (c.has("in")) {
            return contains(c.getAsJsonArray("in"), value);
        }
        if (c.has("notIn")) {
            return !contains(c.getAsJsonArray("notIn"), value);
        }
        if (c.has("matches")) {
            return Pattern.compile(c.get("matches").getAsString(), Pattern.CASE_INSENSITIVE).matcher(value).find();
        }
        return true;
    }

    private static boolean contains(JsonArray list, String value) {
        for (JsonElement e : list) {
            if (e.getAsString().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** an option's value and a sent one: the same text, or the same number (1 and 1.0) */
    private static boolean same(String option, String sent) {
        if (option.equals(sent)) {
            return true;
        }
        try {
            return new BigDecimal(option).compareTo(new BigDecimal(sent)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Pattern pattern(JsonObject f) {
        boolean ic = f.has("ignoreCase") && f.get("ignoreCase").getAsBoolean();
        return Pattern.compile(f.get("pattern").getAsString(), ic ? Pattern.CASE_INSENSITIVE : 0);
    }

    private static String range(JsonObject f) {
        return (f.has("min") ? f.get("min").getAsString() : "") + "–" + (f.has("max") ? f.get("max").getAsString() : "");
    }

    private static String options(JsonObject f) {
        List<String> o = new ArrayList<>();
        f.getAsJsonArray("options").forEach(e -> o.add(e.getAsJsonObject().get("value").getAsString()));
        return String.join(", ", o);
    }

    /** the field's own message, or "<Label>: <what is wrong>." */
    private static ApiException bad(JsonObject f, String what) {
        String key = f.get("key").getAsString();
        if (f.has("message")) {
            return new ApiException(400, key, f.get("message").getAsString());
        }
        String label = f.has("label") ? f.get("label").getAsString() : key;
        return new ApiException(400, key, "required".equals(what)
                ? "Enter the " + label.replaceAll(" \\(optional\\)$", "").toLowerCase(Locale.ROOT) + "."
                : label + ": " + what + ".");
    }
}
