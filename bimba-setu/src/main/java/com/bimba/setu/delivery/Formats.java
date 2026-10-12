package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * Request bodies of the REST destination ("format"), built from the BIMBA batch envelope
 * {schemaVersion, batchId, plantId, edgeId, count, messages:[canonical message]}:
 * <ul>
 *   <li>envelope: the batch as it is (BIMBA Ingest, Boomi Web Services Server, webhooks)</li>
 *   <li>ndjson: one canonical message per line; json-array: one JSON array of them</li>
 *   <li>splunk-hec: one HEC event per message (POST /services/collector/event)</li>
 *   <li>elastic-bulk: Elasticsearch / OpenSearch _bulk, document id = messageId (a retried batch is not stored twice)</li>
 *   <li>influx-line: InfluxDB line protocol, measurement "bimba", tags plant/machine/tag/quality/unit</li>
 * </ul>
 */
final class Formats {
    record Body(byte[] bytes, String contentType) {
    }

    /**
     * csv: separator, header line; xml: root and reading element names; v2 = the destination receives format 2.0
     * (batch attributes and default columns in snake_case)
     */
    record Options(String csvSeparator, boolean csvHeader, String xmlRoot, String xmlItem, boolean v2, boolean template) {
        static final Options DEFAULT = new Options(",", true, "batch", "reading", false, false);

        static Options of(com.bimba.setu.config.BimbaConfig.Destination d, boolean v2, boolean template) {
            return new Options(d.csvSeparator == null || d.csvSeparator.isEmpty() ? "," : d.csvSeparator, d.csvHeader,
                    d.xmlRoot == null || d.xmlRoot.isBlank() ? "batch" : d.xmlRoot,
                    d.xmlItem == null || d.xmlItem.isBlank() ? "reading" : d.xmlItem, v2, template);
        }
    }

    /** the columns a CSV file starts with when no template picks them (any other field follows) */
    static final java.util.List<String> CSV_COLUMNS = java.util.List.of("plantId", "machineId", "tagName", "timestamp", "tagValue",
            "dataType", "unit", "quality", "qualityDetail", "category", "messageId", "sourceTimestamp", "ingestTime");

    private Formats() {
    }

    static Body render(String format, byte[] batch, String url) {
        return render(format, batch, url, null);
    }

    static Body render(String format, byte[] batch, String url, BiFunction<JsonObject, JsonObject, JsonElement> layout) {
        return render(format, batch, url, layout, Options.DEFAULT);
    }

    /** layout = the destination's template (reading -> its own JSON), null = the BIMBA message; not used for influx-line */
    static Body render(String format, byte[] batch, String url, BiFunction<JsonObject, JsonObject, JsonElement> layout, Options o) {
        String f = format == null ? "envelope" : format.toLowerCase(Locale.ROOT);
        if ("envelope".equals(f) && layout == null) {
            return new Body(batch, "application/json");
        }
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        BiFunction<JsonObject, JsonObject, JsonElement> out = layout == null ? (m, e) -> m : layout;
        if ("csv".equals(f)) {
            return new Body(csv(env, out, o).getBytes(StandardCharsets.UTF_8), "text/csv; charset=utf-8");
        }
        if ("xml".equals(f)) {
            return new Body(xml(env, out, o).getBytes(StandardCharsets.UTF_8), "application/xml; charset=utf-8");
        }
        if ("envelope".equals(f) || "json-array".equals(f)) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            env.getAsJsonArray("messages").forEach(e -> arr.add(out.apply(e.getAsJsonObject(), env)));
            if ("json-array".equals(f)) {
                return new Body(Json.GSON.toJson(arr).getBytes(StandardCharsets.UTF_8), "application/json");
            }
            env.add("messages", arr);
            return new Body(Json.GSON.toJson(env).getBytes(StandardCharsets.UTF_8), "application/json");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : env.getAsJsonArray("messages")) {
            JsonObject m = e.getAsJsonObject();
            switch (f) {
                case "ndjson" -> sb.append(Json.GSON.toJson(out.apply(m, env))).append('\n');
                case "splunk-hec" -> {
                    JsonObject ev = new JsonObject();
                    Instant at = Instant.parse(Json.str(m, "timestamp"));
                    ev.addProperty("time", new BigDecimal(at.toEpochMilli()).movePointLeft(3));
                    ev.addProperty("host", Json.str(env, "edgeId"));
                    ev.addProperty("source", "bimba-gateway");
                    ev.addProperty("sourcetype", "_json");
                    ev.add("event", out.apply(m, env));
                    sb.append(Json.GSON.toJson(ev)).append('\n');
                }
                case "elastic-bulk" -> {
                    JsonObject action = new JsonObject();
                    JsonObject meta = new JsonObject();
                    meta.addProperty("_id", Json.str(m, "messageId"));
                    action.add("index", meta);
                    JsonElement mapped = out.apply(m, env);
                    JsonObject doc = mapped.isJsonObject() ? mapped.getAsJsonObject().deepCopy() : new JsonObject();
                    if (!doc.has("@timestamp")) {
                        doc.addProperty("@timestamp", Json.str(m, "timestamp"));
                    }
                    sb.append(Json.GSON.toJson(action)).append('\n').append(Json.GSON.toJson(doc)).append('\n');
                }
                case "influx-line" -> influx(sb, m, precision(url));
                default -> throw new IllegalArgumentException("unknown body format " + format);
            }
        }
        String type = switch (f) {
            case "influx-line" -> "text/plain; charset=utf-8";
            case "splunk-hec" -> "application/json";
            default -> "application/x-ndjson";
        };
        return new Body(sb.toString().getBytes(StandardCharsets.UTF_8), type);
    }

    /** "bimba,plant=P,machine=M,tag=T,quality=GOOD value=21.5 1696600000000" (strings: value_str, booleans: value_bool) */
    private static void influx(StringBuilder sb, JsonObject m, String precision) {
        JsonElement v = m.get("tagValue");
        String field;
        if (v == null || v.isJsonNull()) {
            return;                                    // no value to write (bad quality without a reading)
        }
        switch (Json.str(m, "dataType")) {
            case "number" -> {
                double d = v.getAsDouble();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return;
                }
                String n = BigDecimal.valueOf(d).toPlainString();
                field = "value=" + (n.contains(".") ? n : n + ".0");
            }
            case "boolean" -> field = "value_bool=" + v.getAsBoolean();
            default -> field = "value_str=\"" + v.getAsString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        sb.append("bimba");
        tag(sb, "plant", Json.str(m, "plantId"));
        tag(sb, "machine", Json.str(m, "machineId"));
        tag(sb, "tag", Json.str(m, "tagName"));
        tag(sb, "quality", Json.str(m, "quality"));
        tag(sb, "unit", Json.str(m, "unit"));
        Instant at = Instant.parse(Json.str(m, "timestamp"));
        long ts = switch (precision) {
            case "s" -> at.getEpochSecond();
            case "ms" -> at.toEpochMilli();
            case "us" -> at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000;
            default -> at.getEpochSecond() * 1_000_000_000L + at.getNano();
        };
        sb.append(' ').append(field).append(' ').append(ts).append('\n');
    }

    private static void tag(StringBuilder sb, String key, String value) {
        if (!value.isEmpty()) {
            sb.append(',').append(key).append('=').append(value.replace("\\", "\\\\").replace(",", "\\,").replace("=", "\\=").replace(" ", "\\ "));
        }
    }

    // ------------------------------------------------------------------ CSV

    /**
     * One line per reading (RFC 4180 quoting). The columns: a template's fields in its order, else the standard ones
     * ({@link #CSV_COLUMNS}, snake_case for format 2); fields only some readings have follow, nested objects as
     * "source.protocol". Values: numbers as they are, true / false, empty for null.
     */
    static String csv(JsonObject env, BiFunction<JsonObject, JsonObject, JsonElement> layout, Options o) {
        java.util.List<java.util.Map<String, String>> rows = new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> cols = new java.util.LinkedHashSet<>();
        if (!o.template()) {
            CSV_COLUMNS.forEach(c -> cols.add(o.v2() ? com.bimba.setu.canonical.CanonicalV2.snake(c) : c));
        }
        for (JsonElement e : env.getAsJsonArray("messages")) {
            java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
            flatten("", layout.apply(e.getAsJsonObject(), env), row);
            cols.addAll(row.keySet());
            rows.add(row);
        }
        String sep = o.csvSeparator();
        StringBuilder sb = new StringBuilder();
        if (o.csvHeader()) {
            sb.append(String.join(sep, cols.stream().map(c -> csvCell(c, sep)).toList())).append("\r\n");
        }
        for (java.util.Map<String, String> r : rows) {
            sb.append(String.join(sep, cols.stream().map(c -> csvCell(r.getOrDefault(c, ""), sep)).toList())).append("\r\n");
        }
        return sb.toString();
    }

    /** a reading as path -> text: {"source":{"protocol":"modbus"}} -> source.protocol = modbus; arrays as JSON text */
    static void flatten(String prefix, JsonElement e, java.util.Map<String, String> out) {
        if (e == null || e.isJsonNull()) {
            if (!prefix.isEmpty()) {
                out.put(prefix, "");
            }
        } else if (e.isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                flatten(prefix.isEmpty() ? x.getKey() : prefix + "." + x.getKey(), x.getValue(), out);
            }
        } else if (e.isJsonArray()) {
            out.put(prefix.isEmpty() ? "value" : prefix, Json.GSON.toJson(e));
        } else {
            out.put(prefix.isEmpty() ? "value" : prefix, plain(e.getAsJsonPrimitive()));
        }
    }

    /** a JSON value as text: numbers without exponent (1.7E12 -> 1700000000000), booleans true / false */
    static String plain(com.google.gson.JsonPrimitive p) {
        if (p.isNumber()) {
            return p.getAsBigDecimal().stripTrailingZeros().toPlainString();
        }
        return p.getAsString();
    }

    private static String csvCell(String v, String sep) {
        if (v.contains(sep) || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // ------------------------------------------------------------------ XML

    /**
     * {@code <batch batchId=".." plantId=".." edgeId=".." createdAt=".." count="2"><reading>..</reading></batch>}: every
     * field of a reading (its template's, else the BIMBA message's) as an element, nested objects nested, arrays as
     * repeated {@code <item>}; template keys starting with @ become attributes. Names that are no XML names are made
     * into one (spaces and other signs -> _).
     */
    static String xml(JsonObject env, BiFunction<JsonObject, JsonObject, JsonElement> layout, Options o) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<").append(xmlName(o.xmlRoot()));
        String[] attrs = o.v2() ? new String[]{"schema_version", "batch_id", "plant_id", "edge_id", "created_at"}
                : new String[]{"schemaVersion", "batchId", "plantId", "edgeId", "createdAt"};
        String[] from = {"schemaVersion", "batchId", "plantId", "edgeId", "createdAt"};
        for (int i = 0; i < from.length; i++) {
            String v = "schemaVersion".equals(from[i]) && o.v2() ? com.bimba.setu.canonical.CanonicalV2.VERSION : Json.str(env, from[i]);
            if (!v.isEmpty()) {
                sb.append(' ').append(attrs[i]).append("=\"").append(xmlText(v, true)).append('"');
            }
        }
        com.google.gson.JsonArray all = env.getAsJsonArray("messages");
        sb.append(" count=\"").append(all.size()).append("\">\n");
        for (JsonElement e : all) {
            element(sb, o.xmlItem(), layout.apply(e.getAsJsonObject(), env), "  ");
        }
        return sb.append("</").append(xmlName(o.xmlRoot())).append(">\n").toString();
    }

    private static void element(StringBuilder sb, String name, JsonElement v, String indent) {
        String n = xmlName(name);
        sb.append(indent).append('<').append(n);
        if (v == null || v.isJsonNull()) {
            sb.append("/>\n");
            return;
        }
        if (v.isJsonPrimitive()) {
            sb.append('>').append(xmlText(plain(v.getAsJsonPrimitive()), false)).append("</").append(n).append(">\n");
            return;
        }
        if (v.isJsonArray()) {
            sb.append(">\n");
            for (JsonElement x : v.getAsJsonArray()) {
                element(sb, "item", x, indent + "  ");
            }
            sb.append(indent).append("</").append(n).append(">\n");
            return;
        }
        JsonObject obj = v.getAsJsonObject();
        for (java.util.Map.Entry<String, JsonElement> x : obj.entrySet()) {
            if (x.getKey().startsWith("@") && x.getValue().isJsonPrimitive()) {
                sb.append(' ').append(xmlName(x.getKey().substring(1))).append("=\"")
                        .append(xmlText(plain(x.getValue().getAsJsonPrimitive()), true)).append('"');
            }
        }
        boolean children = obj.entrySet().stream().anyMatch(x -> !x.getKey().startsWith("@") || !x.getValue().isJsonPrimitive());
        if (!children) {
            sb.append("/>\n");
            return;
        }
        sb.append(">\n");
        for (java.util.Map.Entry<String, JsonElement> x : obj.entrySet()) {
            if (!x.getKey().startsWith("@") || !x.getValue().isJsonPrimitive()) {
                element(sb, x.getKey().startsWith("@") ? x.getKey().substring(1) : x.getKey(), x.getValue(), indent + "  ");
            }
        }
        sb.append(indent).append("</").append(n).append(">\n");
    }

    /** a usable XML element / attribute name: letters, digits, _ - . and not starting with a digit, - or . */
    static String xmlName(String s) {
        String n = (s == null ? "" : s).replaceAll("[^A-Za-z0-9_.\\-]", "_");
        if (n.isEmpty() || !Character.isLetter(n.charAt(0)) && n.charAt(0) != '_' || n.toLowerCase(Locale.ROOT).startsWith("xml")) {
            n = "_" + n;
        }
        return n;
    }

    /** escaped text; characters XML 1.0 cannot hold (control characters) are left out */
    static String xmlText(String s, boolean attribute) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append(attribute ? "&quot;" : "\"");
                case '\n', '\r', '\t' -> sb.append(attribute ? "&#" + (int) c + ";" : String.valueOf(c));
                default -> {
                    if (c >= 0x20 || Character.isSurrogate(c)) {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** timestamp precision of an InfluxDB write URL (?precision=ns|us|ms|s, default ns) */
    static String precision(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]precision=(ns|us|ms|s)(?:&|$)").matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : "ns";
    }
}
