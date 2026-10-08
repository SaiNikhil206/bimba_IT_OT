package com.bimba.gateway.delivery;

import com.bimba.gateway.Json;
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

    private Formats() {
    }

    static Body render(String format, byte[] batch, String url) {
        return render(format, batch, url, null);
    }

    /** layout = the destination's template (reading -> its own JSON), null = the BIMBA message; not used for influx-line */
    static Body render(String format, byte[] batch, String url, BiFunction<JsonObject, JsonObject, JsonElement> layout) {
        String f = format == null ? "envelope" : format.toLowerCase(Locale.ROOT);
        if ("envelope".equals(f) && layout == null) {
            return new Body(batch, "application/json");
        }
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        BiFunction<JsonObject, JsonObject, JsonElement> out = layout == null ? (m, e) -> m : layout;
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

    /** timestamp precision of an InfluxDB write URL (?precision=ns|us|ms|s, default ns) */
    static String precision(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]precision=(ns|us|ms|s)(?:&|$)").matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : "ns";
    }
}
