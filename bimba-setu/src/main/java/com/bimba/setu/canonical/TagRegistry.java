package com.bimba.setu.canonical;

import com.bimba.setu.Db;
import com.bimba.setu.Json;
import com.bimba.setu.collect.Canonical;
import com.bimba.setu.collect.Units;
import com.bimba.setu.security.OrgService;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The tag registry (FR-CAN-04), one per organization (bimba_gw_setting "tag_registry.org.&lt;id&gt;"): for a tag as it is
 * collected (machine + tag), its canonical tag name, machine, plant id, data type, unit, writable flag and description.
 * It is applied to every reading after its schema check and before delivery ({@link #apply}); tags without an entry are
 * delivered as collected. Maintained in the console (one tag at a time, or CSV import / export), no code changes.
 * <p>
 * Applying an entry: names are replaced (source.tag keeps the collected machine/tag), the value is converted to the
 * entry's unit ({@link Units}) and data type; a value that cannot be converted is delivered as BAD with the reason.
 * The readings then carry dataTypeName, writable and description (format 1.2; data_type / writable / description in 2.0).
 */
@Service
public class TagRegistry {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Registry");
    static final String KEY = "tag_registry.org.";
    public static final List<String> TYPES = List.of("Boolean", "Int1", "Int2", "Int4", "Int8", "Float4", "Float8", "Double", "String");
    public static final List<String> CSV_COLUMNS = List.of("machine", "tag", "canonical_tag", "machine_id", "plant_id", "data_type",
            "unit", "writable", "description");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._:/-]{0,199}");
    private static final int MAX_ENTRIES = 20_000;

    /** one entry; null fields are not changed */
    public record Entry(String machine, String tag, String tagName, String machineId, String plantId, String dataType, String unit,
                        Boolean writable, String description) {
        String key() {
            return machine + "/" + tag;
        }

        /** "machine/tag" as the tag is delivered (the collected names where the entry keeps them) */
        public String target() {
            return (machineId != null ? machineId : machine) + "/" + (tagName != null ? tagName : tag);
        }

        /** the entry gives the tag another machine or tag name */
        public boolean renames() {
            return !target().equals(key());
        }

        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("machine", machine);
            m.put("tag", tag);
            m.put("tagName", tagName);
            m.put("machineId", machineId);
            m.put("plantId", plantId);
            m.put("dataType", dataType);
            m.put("unit", unit);
            m.put("writable", writable);
            m.put("description", description);
            return m;
        }

        boolean empty() {
            return tagName == null && machineId == null && plantId == null && dataType == null && unit == null && writable == null
                    && description == null;
        }
    }

    private record Owned(long orgId, Entry entry) {
    }

    private final Db db;
    private final OrgService orgs;
    /** "machine/tag" -> entry, every organization's (a machine belongs to one organization) */
    private volatile Map<String, Owned> byTag;
    private volatile Map<String, Long> owners = Map.of();
    private volatile Long defaultOrg;
    private volatile long loadedAt;

    public TagRegistry(Db db, OrgService orgs) {
        this.db = db;
        this.orgs = orgs;
    }

    // ------------------------------------------------------------------ reading side (every message)

    private Map<String, Owned> index() {
        Map<String, Owned> m = byTag;
        if (m != null && System.currentTimeMillis() - loadedAt < 60_000) {
            return m;
        }
        try {
            Map<String, Owned> next = new HashMap<>();
            db.sql("SELECT key, value::text FROM {s}.bimba_gw_setting WHERE key LIKE 'tag_registry.org.%'").query((rs, n) -> {
                long org = Long.parseLong(rs.getString(1).substring(KEY.length()));
                for (Entry e : parse(Json.GSON.fromJson(rs.getString(2), JsonObject.class))) {
                    next.put(e.key(), new Owned(org, e));
                }
                return null;
            }).list();
            owners = orgs.owners();
            defaultOrg = orgs.defaultOrgId();
            byTag = next;
        } catch (RuntimeException e) {
            if (m == null) {
                log.warn("tag registry not loaded ({}): readings are delivered as collected", e.getMessage());
            }
            byTag = m == null ? Map.of() : m;            // database away: keep what we had, try again in a minute
        }
        loadedAt = System.currentTimeMillis();
        return byTag;
    }

    /**
     * The reading with its registry entry applied (a new object), or the reading itself when it has none. org = the
     * organization it came from (a site agent's), null = the owner of its machine.
     */
    public JsonObject apply(JsonObject m, Long org) {
        Owned o = index().get(Json.str(m, "machineId") + "/" + Json.str(m, "tagName"));
        if (o == null) {
            return m;
        }
        long from = org != null ? org : OrgService.owns(o.orgId(), Json.str(m, "machineId"), owners, defaultOrg) ? o.orgId() : -1;
        return from == o.orgId() ? applyEntry(m, o.entry()) : m;
    }

    static JsonObject applyEntry(JsonObject in, Entry e) {
        JsonObject m = in.deepCopy();
        m.addProperty("schemaVersion", Canonical.VERSION);
        if (e.tagName() != null || e.machineId() != null) {
            JsonObject src = m.has("source") && m.get("source").isJsonObject() ? m.getAsJsonObject("source") : new JsonObject();
            src.addProperty("tag", Json.str(in, "machineId") + "/" + Json.str(in, "tagName"));    // as collected
            m.add("source", src);
        }
        if (e.tagName() != null) {
            m.addProperty("tagName", e.tagName());
        }
        if (e.machineId() != null) {
            m.addProperty("machineId", e.machineId());
        }
        if (e.plantId() != null) {
            m.addProperty("plantId", e.plantId());
        }
        if (e.writable() != null) {
            m.addProperty("writable", e.writable());
        }
        if (e.description() != null) {
            m.addProperty("description", e.description());
        }
        String problem = null;
        if (e.unit() != null) {
            String from = Json.str(m, "unit");
            JsonElement v = m.get("tagValue");
            if (!from.isEmpty() && !from.equals(e.unit()) && v != null && v.isJsonPrimitive() && !v.getAsJsonPrimitive().isBoolean()) {
                Double d = number(v);                    // a number, or text that is one ("212"): converted alike
                if (!Units.convertible(from, e.unit())) {
                    problem = "the registry's unit " + e.unit() + " does not fit the collected unit " + from;
                } else if (d == null) {
                    problem = "value \"" + v.getAsString() + "\" is not a number, so it cannot be converted from " + from + " to " + e.unit();
                } else {
                    m.addProperty("tagValue", round(Units.convert(d, from, e.unit())));
                    m.addProperty("dataType", "number");
                }
            }
            m.addProperty("unit", e.unit());
        }
        if (problem == null && e.dataType() != null) {
            m.addProperty("dataTypeName", e.dataType());
            problem = coerce(m, e.dataType());
        }
        if (problem != null) {
            m.add("tagValue", JsonNull.INSTANCE);
            m.addProperty("dataType", "null");
            m.addProperty("quality", "BAD");
            String d = Json.str(m, "qualityDetail");
            m.addProperty("qualityDetail", d.isEmpty() ? problem : d + "; " + problem);
        }
        return m;
    }

    private static double round(double d) {
        return new BigDecimal(d).round(new java.math.MathContext(12)).doubleValue();
    }

    /** a JSON number or numeric text as a finite double, else null */
    private static Double number(JsonElement v) {
        if (v.getAsJsonPrimitive().isNumber()) {
            return v.getAsDouble();
        }
        try {
            double d = Double.parseDouble(v.getAsString().trim());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** tagValue / dataType as the registry's data type; why not, when the value does not convert */
    private static String coerce(JsonObject m, String type) {
        JsonElement v = m.get("tagValue");
        if (v == null || v.isJsonNull()) {
            return null;
        }
        String text = v.getAsString().trim();
        boolean isBool = v.getAsJsonPrimitive().isBoolean();
        boolean isNum = v.getAsJsonPrimitive().isNumber();
        switch (type) {
            case "String" -> {
                m.addProperty("tagValue", isNum ? BigDecimal.valueOf(v.getAsDouble()).stripTrailingZeros().toPlainString() : text);
                m.addProperty("dataType", "string");
                return null;
            }
            case "Boolean" -> {
                Boolean b = isBool ? Boolean.valueOf(v.getAsBoolean()) : isNum ? Boolean.valueOf(v.getAsDouble() != 0)
                        : Set.of("true", "1", "on").contains(text.toLowerCase(Locale.ROOT)) ? Boolean.TRUE
                        : Set.of("false", "0", "off").contains(text.toLowerCase(Locale.ROOT)) ? Boolean.FALSE : null;
                if (b == null) {
                    return "value \"" + text + "\" is not a Boolean";
                }
                m.addProperty("tagValue", b);
                m.addProperty("dataType", "boolean");
                return null;
            }
            default -> {
                double d;
                if (isBool) {
                    d = v.getAsBoolean() ? 1 : 0;
                } else {
                    try {
                        d = isNum ? v.getAsDouble() : Double.parseDouble(text);
                    } catch (NumberFormatException ex) {
                        return "value \"" + text + "\" is not a number (" + type + ")";
                    }
                }
                if (!Double.isFinite(d)) {
                    return "value " + text + " is not a finite number (" + type + ")";
                }
                if (type.startsWith("Int")) {
                    long bits = Long.parseLong(type.substring(3)) * 8;
                    double limit = Math.pow(2, bits - 1);
                    double r = Math.floor(d + 0.5);          // rounded in double: Math.round would cap at Long.MAX_VALUE
                    if (r < -limit || r >= limit) {
                        return "value " + text + " does not fit " + type;
                    }
                    m.addProperty("tagValue", (long) r);
                } else if ("Float4".equals(type)) {
                    if (Math.abs(d) > Float.MAX_VALUE) {
                        return "value " + text + " does not fit Float4";
                    }
                    m.addProperty("tagValue", Double.parseDouble(Float.toString((float) d)));   // 0.1, not 0.10000000149
                } else {
                    m.addProperty("tagValue", d);
                }
                m.addProperty("dataType", "number");
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ console side

    public List<Entry> entries(long orgId) {
        return db.sql("SELECT value::text FROM {s}.bimba_gw_setting WHERE key = ?").param(KEY + orgId).query(String.class).optional()
                .map(t -> parse(Json.GSON.fromJson(t, JsonObject.class))).orElse(List.of());
    }

    private static List<Entry> parse(JsonObject o) {
        List<Entry> out = new ArrayList<>();
        if (o != null && o.has("entries") && o.get("entries").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("entries")) {
                JsonObject x = e.getAsJsonObject();
                out.add(new Entry(Json.str(x, "machine"), Json.str(x, "tag"), opt(x, "tagName"), opt(x, "machineId"), opt(x, "plantId"),
                        opt(x, "dataType"), opt(x, "unit"), x.has("writable") && !x.get("writable").isJsonNull() ? x.get("writable").getAsBoolean() : null,
                        opt(x, "description")));
            }
        }
        return out;
    }

    private static String opt(JsonObject o, String k) {
        String s = Json.str(o, k).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * What the console knows about the organization's tags, for checking an entry: machines = its machines, units =
     * "machine/tag" -> collected unit.
     */
    public record Known(Collection<String> machines, Map<String, String> units) {
    }

    /** one entry from the console (fields empty = not changed); all empty = the entry is removed */
    public Entry entry(JsonObject in) throws ApiException {
        String machine = Json.str(in, "machine").trim();
        String tag = Json.str(in, "tag").trim();
        if (machine.isEmpty() || tag.isEmpty()) {
            throw new ApiException(400, "tag", "Name the tag as it is collected: machine and tag.");
        }
        String writable = Json.str(in, "writable").trim().toLowerCase(Locale.ROOT);
        Boolean w = writable.isEmpty() ? null : Set.of("true", "yes", "1", "y").contains(writable) ? Boolean.TRUE
                : Set.of("false", "no", "0", "n").contains(writable) ? Boolean.FALSE : null;
        if (!writable.isEmpty() && w == null) {
            throw new ApiException(400, "writable", "Writable is yes or no.");
        }
        return new Entry(machine, tag, opt(in, "tagName"), opt(in, "machineId"), opt(in, "plantId"), type(opt(in, "dataType")),
                opt(in, "unit"), w, opt(in, "description"));
    }

    private static String type(String t) throws ApiException {
        if (t == null) {
            return null;
        }
        for (String x : TYPES) {
            if (x.equalsIgnoreCase(t)) {
                return x;
            }
        }
        throw new ApiException(400, "dataType", "Data type " + t + ": use " + String.join(", ", TYPES) + ".");
    }

    /** checks one entry for the organization; why not as an ApiException */
    void check(long orgId, Entry e, Known known) throws ApiException {
        Map<String, Long> own = orgs.owners();
        Long def = orgs.defaultOrgId();
        String where = e.key() + ": ";
        if (!OrgService.owns(orgId, e.machine(), own, def)) {
            throw new ApiException(400, "machine", where + "machine " + e.machine() + " is not one of this organization's.");
        }
        if (e.tagName() != null && !NAME.matcher(e.tagName()).matches()) {
            throw new ApiException(400, "tagName", where + "the canonical tag name has 1-200 letters, digits, spaces and . _ : / -");
        }
        if (e.machineId() != null && !e.machineId().equals(e.machine()) && !known.machines().contains(e.machineId())) {
            throw new ApiException(400, "machineId", where + "machine " + e.machineId() + " is not one of this organization's machines.");
        }
        if (e.plantId() != null && (e.plantId().length() > 120 || !NAME.matcher(e.plantId()).matches())) {
            throw new ApiException(400, "plantId", where + "the plant id has 1-120 letters, digits, spaces and . _ : / -");
        }
        if (e.unit() != null) {
            String now = known.units().getOrDefault(e.key(), "");
            if (e.unit().length() > 40) {
                throw new ApiException(400, "unit", where + "a unit has at most 40 characters.");
            }
            if (!now.isEmpty() && !now.equals(e.unit()) && !Units.convertible(now, e.unit())) {
                throw new ApiException(400, "unit", where + "the tag is collected in " + now + ", which does not convert to " + e.unit() + ".");
            }
        }
        if (e.description() != null && e.description().length() > 500) {
            throw new ApiException(400, "description", where + "the description has at most 500 characters.");
        }
    }

    /**
     * Two tags must not be delivered as the same machine/tag: not two entries, and not an entry and a collected tag
     * that keeps its name. The second is checked for new and changed entries only (before = the saved ones), so a tag
     * collected after an entry was saved does not block unrelated changes.
     */
    static void targets(Collection<Entry> entries, Map<String, Entry> before, Known known) throws ApiException {
        Map<String, String> targets = new HashMap<>();
        Map<String, Entry> byKey = new HashMap<>();
        for (Entry e : entries) {
            byKey.put(e.key(), e);
            String other = targets.put(e.target(), e.key());
            if (other != null) {
                throw new ApiException(400, "tagName", other + " and " + e.key() + " would both be delivered as " + e.target() + ".");
            }
        }
        for (Entry e : entries) {
            if (!e.renames() || e.equals(before.get(e.key()))) {
                continue;
            }
            Entry there = byKey.get(e.target());
            if (known.units().containsKey(e.target()) && (there == null || !there.renames())) {
                throw new ApiException(400, "tagName", e.key() + " would be delivered as " + e.target()
                        + ", which is already a collected tag. Give it another name, or rename " + e.target() + " too.");
            }
        }
    }

    /** replaces the organization's registry with these entries (checked first; nothing is saved when one is wrong) */
    public void save(long orgId, List<Entry> entries, Known known) throws ApiException {
        if (entries.size() > MAX_ENTRIES) {
            throw new ApiException(400, "entries", "At most " + MAX_ENTRIES + " registry entries per organization.");
        }
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.empty()) {
                continue;
            }
            check(orgId, e, known);
            byKey.put(e.key(), e);
        }
        Map<String, Entry> before = new HashMap<>();
        entries(orgId).forEach(e -> before.put(e.key(), e));
        targets(byKey.values(), before, known);
        JsonArray arr = new JsonArray();
        byKey.values().forEach(e -> arr.add(Json.GSON.toJsonTree(e.json())));
        JsonObject o = new JsonObject();
        o.add("entries", arr);
        db.sql("INSERT INTO {s}.bimba_gw_setting (key, value, updated_at) VALUES (?, ?::jsonb, now())"
                        + " ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = now()")
                .params(KEY + orgId, Json.GSON.toJson(o)).update();
        loadedAt = 0;                                    // used from the next reading on
    }

    // ------------------------------------------------------------------ CSV

    public static String csv(List<Entry> entries) {
        StringBuilder sb = new StringBuilder(String.join(",", CSV_COLUMNS)).append("\r\n");
        for (Entry e : entries) {
            List<String> cells = List.of(e.machine(), e.tag(), n(e.tagName()), n(e.machineId()), n(e.plantId()), n(e.dataType()),
                    n(e.unit()), e.writable() == null ? "" : e.writable() ? "yes" : "no", n(e.description()));
            sb.append(String.join(",", cells.stream().map(TagRegistry::cell).toList())).append("\r\n");
        }
        return sb.toString();
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static String cell(String s) {
        boolean quote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")
                || (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0);       // no formulas when opened in a spreadsheet
        String t = (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) ? "'" + s : s;
        return quote ? "\"" + t.replace("\"", "\"\"") + "\"" : t;
    }

    /** entries from CSV text (header row with the CSV_COLUMNS names, any order; machine and tag required) */
    public List<Entry> parseCsv(String text) throws ApiException {
        List<List<String>> rows = rows(text == null ? "" : text);
        if (rows.isEmpty()) {
            throw new ApiException(400, "csv", "The file is empty.");
        }
        List<String> head = rows.get(0).stream().map(h -> h.trim().toLowerCase(Locale.ROOT).replace(' ', '_')).toList();
        if (!head.contains("machine") || !head.contains("tag")) {
            throw new ApiException(400, "csv", "The first row must name the columns: " + String.join(",", CSV_COLUMNS) + ".");
        }
        List<Entry> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.stream().allMatch(String::isBlank)) {
                continue;
            }
            JsonObject o = new JsonObject();
            for (int c = 0; c < head.size() && c < r.size(); c++) {
                String v = r.get(c).trim();
                if (v.startsWith("'") && v.length() > 1 && "=+-@".indexOf(v.charAt(1)) >= 0) {
                    v = v.substring(1);
                }
                String k = switch (head.get(c)) {
                    case "canonical_tag" -> "tagName";
                    case "machine_id" -> "machineId";
                    case "plant_id" -> "plantId";
                    case "data_type" -> "dataType";
                    default -> head.get(c);
                };
                o.addProperty(k, v);
            }
            try {
                out.add(entry(o));
            } catch (ApiException e) {
                throw new ApiException(400, "csv", "Row " + (i + 1) + ": " + e.getMessage());
            }
        }
        return out;
    }

    /** RFC 4180 rows: quoted cells may hold commas, quotes ("") and line breaks */
    static List<List<String>> rows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String t = text.startsWith("﻿") ? text.substring(1) : text;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < t.length() && t.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
