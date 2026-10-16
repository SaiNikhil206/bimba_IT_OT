package com.bimba.setu.agents;

import com.bimba.setu.Json;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Four-eyes on safety configuration (BRD 4.5): what of a data source decides which machine commands are possible, and
 * how a change to it is held back until another person approves it ({@link SafetyChanges}).
 * <p>
 * Safety configuration:
 * <ul>
 *   <li>the data source's settings.writes switch (Allow machine commands);</li>
 *   <li>per tag: writable, writeMin / writeMax, writeApproval, writeAddress, and, while the tag is writable (before or
 *       after), what turns a command into a device write: address, dataType, scaling (raw / scaled low and high) and
 *       unit conversion (sourceUnit, unit). A tag that is not writable before and after changes those freely.</li>
 * </ul>
 * A tag is the same tag when its machine field and its tag name (AgentSources.tagName) stay the same. A new tag starts
 * not writable; a removed tag needs no approval (nothing can be written to it any more).
 * <p>
 * A change is one JSON object: {kind: "writes", before, after} (booleans), or {kind: "tag", key, machine, tag, label,
 * before: {fields}, after: {fields}}. Saving a data source stores everything else at once and keeps the stored safety
 * configuration ({@link #hold}); the changes wait as a change request until they are approved ({@link #apply}).
 */
public final class SafetyConfig {
    /** what a command may do */
    static final List<String> WRITE_FIELDS = List.of("writable", "writeMin", "writeMax", "writeApproval", "writeAddress");
    /** what a command turns into on the device (only counts while the tag is writable) */
    static final List<String> PATH_FIELDS = List.of("address", "dataType", "rawLow", "rawHigh", "scaledLow", "scaledHigh", "sourceUnit", "unit");

    private SafetyConfig() {
    }

    /** is the data source's Allow machine commands switch on */
    static boolean writesOn(JsonObject settings) {
        JsonElement e = settings == null ? null : settings.get("writes");
        return e != null && e.isJsonPrimitive() && (e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : "true".equalsIgnoreCase(e.getAsString()));
    }

    /** the same tag in another version of the source: its machine field and tag name */
    static String key(JsonObject t) {
        return Json.str(t, "machine").trim().toLowerCase(Locale.ROOT) + "/" + AgentSources.tagName(t);
    }

    static boolean writable(JsonObject t) {
        JsonElement e = t == null ? null : t.get("writable");
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    /** a tag's safety fields, normalised (numbers as doubles, missing fields left out); null tag = a tag that is not there */
    static JsonObject fields(JsonObject t) {
        JsonObject f = new JsonObject();
        f.addProperty("writable", writable(t));
        if (t == null) {
            return f;
        }
        for (String k : concat()) {
            JsonElement v = t.get(k);
            if (k.equals("writable") || v == null || v.isJsonNull() || !v.isJsonPrimitive() || v.getAsString().isBlank()) {
                continue;
            }
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (k.equals("writeApproval")) {
                if (p.getAsBoolean()) {
                    f.addProperty(k, true);
                }
            } else if (p.isNumber()) {
                f.addProperty(k, p.getAsDouble());
            } else {
                f.addProperty(k, p.getAsString().trim());
            }
        }
        return f;
    }

    /** do two versions of a tag differ in what commands can do: only the writable flag while it is off in both */
    static boolean differs(JsonObject before, JsonObject after) {
        if (!writable(before) && !writable(after)) {
            return false;
        }
        return !before.equals(after);
    }

    /** the safety changes from the stored source (null: a new one) to the one being saved */
    static List<JsonObject> diff(JsonObject storedSettings, JsonArray storedTags, JsonObject settings, JsonArray tags, String sourceName) {
        List<JsonObject> out = new ArrayList<>();
        boolean was = writesOn(storedSettings), now = writesOn(settings);
        if (was != now) {
            JsonObject c = new JsonObject();
            c.addProperty("kind", "writes");
            c.addProperty("before", was);
            c.addProperty("after", now);
            out.add(c);
        }
        Map<String, JsonObject> stored = byKey(storedTags);
        for (JsonElement e : tags) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject t = e.getAsJsonObject();
            String k = key(t);
            JsonObject before = fields(stored.get(k)), after = fields(t);
            if (differs(before, after)) {
                JsonObject c = new JsonObject();
                c.addProperty("kind", "tag");
                c.addProperty("key", k);
                String m = Json.str(t, "machine");
                c.addProperty("machine", m.isBlank() ? sourceName : m.trim());
                c.addProperty("tag", AgentSources.tagName(t));
                c.addProperty("label", Json.str(t, "name"));
                c.add("before", before);
                c.add("after", after);
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Keeps the stored safety configuration in the source being saved (settings and tags are changed in place): the
     * writes switch as stored, every changed tag with its stored safety fields, a new tag not writable.
     */
    static void hold(JsonObject storedSettings, JsonArray storedTags, JsonObject settings, JsonArray tags, List<JsonObject> changes) {
        boolean switchChanges = changes.stream().anyMatch(c -> "writes".equals(Json.str(c, "kind")));
        if (switchChanges) {
            settings.addProperty("writes", writesOn(storedSettings));
        }
        Map<String, JsonObject> stored = byKey(storedTags);
        java.util.Set<String> changed = new java.util.HashSet<>();
        changes.stream().filter(c -> "tag".equals(Json.str(c, "kind"))).forEach(c -> changed.add(Json.str(c, "key")));
        for (JsonElement e : tags) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject t = e.getAsJsonObject();
            String k = key(t);
            if (changed.contains(k)) {
                // a tag that was not writable keeps the new address, scaling and unit: they are free while it is not
                JsonObject was = fields(stored.get(k));
                put(t, was, writable(was));
            }
        }
    }

    /** why the approved changes no longer fit the source as it is now (someone changed it meanwhile), or null */
    static String conflict(JsonObject settings, JsonArray tags, List<JsonObject> changes) {
        Map<String, JsonObject> now = byKey(tags);
        for (JsonObject c : changes) {
            if ("writes".equals(Json.str(c, "kind"))) {
                if (writesOn(settings) != c.get("before").getAsBoolean()) {
                    return "Allow machine commands was changed since the request was made.";
                }
                continue;
            }
            String k = Json.str(c, "key");
            JsonObject t = now.get(k);
            if (t == null) {
                return "Tag " + Json.str(c, "machine") + " / " + Json.str(c, "tag") + " is no longer on the data source.";
            }
            JsonObject current = fields(t);
            JsonObject before = c.getAsJsonObject("before");
            if (!current.equals(before) && (writable(current) || writable(before))) {
                return "The machine command settings of " + Json.str(c, "machine") + " / " + Json.str(c, "tag") + " were changed since the request was made.";
            }
        }
        return null;
    }

    /** puts the approved changes into the source's settings and tags (in place) */
    static void apply(JsonObject settings, JsonArray tags, List<JsonObject> changes) {
        Map<String, JsonObject> now = byKey(tags);
        for (JsonObject c : changes) {
            if ("writes".equals(Json.str(c, "kind"))) {
                settings.addProperty("writes", c.get("after").getAsBoolean());
                continue;
            }
            JsonObject t = now.get(Json.str(c, "key"));
            if (t != null) {
                put(t, c.getAsJsonObject("after"), true);
            }
        }
    }

    /** the four-eyes rule itself: the person who proposed a change never approves it */
    static void notOwn(String requestedBy, String approver) throws ApiException {
        if (requestedBy == null || approver == null || requestedBy.isBlank() || requestedBy.equalsIgnoreCase(approver)) {
            throw new ApiException(403, "own", "You proposed this change: another person has to approve it.");
        }
    }

    /** the changes in words, for the audit log and notices: "Allow machine commands: off → on; Press-1 / speed: writable off → on" */
    static String describe(List<JsonObject> changes) {
        List<String> parts = new ArrayList<>();
        for (JsonObject c : changes) {
            if ("writes".equals(Json.str(c, "kind"))) {
                parts.add("Allow machine commands: " + onOff(c.get("before").getAsBoolean()) + " → " + onOff(c.get("after").getAsBoolean()));
                continue;
            }
            JsonObject b = c.getAsJsonObject("before"), a = c.getAsJsonObject("after");
            List<String> what = new ArrayList<>();
            for (String k : concat()) {
                JsonElement x = b.get(k), y = a.get(k);
                if (x == null ? y != null : !x.equals(y)) {
                    what.add(k + " " + show(x) + " → " + show(y));
                }
            }
            parts.add(Json.str(c, "machine") + " / " + Json.str(c, "tag") + ": " + String.join(", ", what));
        }
        return String.join("; ", parts);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> concat() {
        List<String> all = new ArrayList<>(WRITE_FIELDS);
        all.addAll(PATH_FIELDS);
        return all;
    }

    /** replaces the tag's write fields with these (fields left out are removed), and its device side too when path is set */
    private static void put(JsonObject t, JsonObject f, boolean path) {
        for (String k : WRITE_FIELDS) {
            t.remove(k);
        }
        if (writable(f)) {
            for (String k : WRITE_FIELDS) {
                if (f.has(k)) {
                    t.add(k, f.get(k));
                }
            }
        }
        if (path) {
            for (String k : PATH_FIELDS) {
                if (f.has(k)) {
                    t.add(k, f.get(k));
                } else if (!k.equals("address")) {
                    t.remove(k);
                }
            }
        }
    }

    private static Map<String, JsonObject> byKey(JsonArray tags) {
        Map<String, JsonObject> m = new LinkedHashMap<>();
        if (tags != null) {
            for (JsonElement e : tags) {
                if (e.isJsonObject()) {
                    m.putIfAbsent(key(e.getAsJsonObject()), e.getAsJsonObject());
                }
            }
        }
        return m;
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    private static String show(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "–";
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return onOff(e.getAsBoolean());
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double d = e.getAsDouble();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
        }
        return e.getAsString();
    }
}
