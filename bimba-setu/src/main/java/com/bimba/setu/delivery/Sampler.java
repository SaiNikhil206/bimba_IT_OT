package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The rate of a pipeline other than "every change":
 * <ul>
 *   <li>sample: every N seconds, the current value of each tag (also tags that did not change), timestamp = sample
 *       time, sourceTimestamp = the device's time for the value (when it reports one), else when it was read;</li>
 *   <li>average: every N seconds, per tag that reported in the window: numbers = average of the GOOD readings with
 *       aggregate {fn: avg, min, max, count, from, to}; other values = the last one (fn: last);</li>
 *   <li>min / max: the same window, the smallest / largest GOOD number as the value (fn: min / max);</li>
 *   <li>count: the number of readings the tag reported in the window, of any value and quality (fn: count).</li>
 * </ul>
 * (FR-RTE-05: min, max, average and count over a window.)
 * Messages it makes get a new messageId and no edge.seq (they are not readings of the collector's sequence).
 */
final class Sampler {
    private static final class Window {
        double sum;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int count;
        /** every reading of the window, also non-numbers and BAD ones (count mode) */
        int all;
        JsonObject last;
    }

    /** an aggregate mode (average, min, max, count) rather than a snapshot */
    private final boolean average;
    private final String fn;
    private final long periodMs;
    private final Map<String, JsonObject> latest = new LinkedHashMap<>();
    private final Map<String, Window> window = new LinkedHashMap<>();
    private long windowStart;
    private long nextFlush;

    Sampler(String mode, int seconds, long now) {
        String m = mode == null ? "sample" : mode.toLowerCase(Locale.ROOT);
        this.fn = switch (m) {
            case "average" -> "avg";
            case "min", "max", "count" -> m;
            default -> "sample";
        };
        this.average = !"sample".equals(fn);
        this.periodMs = Math.max(1, seconds) * 1000L;
        this.windowStart = now;
        this.nextFlush = now + periodMs;
    }

    synchronized void add(List<JsonObject> messages) {
        for (JsonObject m : messages) {
            String key = Json.str(m, "machineId") + "/" + Json.str(m, "tagName");
            latest.put(key, m);
            if (average) {
                Window w = window.computeIfAbsent(key, k -> new Window());
                w.last = m;
                w.all++;
                if ("number".equals(Json.str(m, "dataType")) && Json.str(m, "quality").toUpperCase(Locale.ROOT).startsWith("GOOD")
                        && m.get("tagValue").isJsonPrimitive()) {
                    double v = m.get("tagValue").getAsDouble();
                    w.sum += v;
                    w.min = Math.min(w.min, v);
                    w.max = Math.max(w.max, v);
                    w.count++;
                }
            }
        }
    }

    /** the messages of every period that ended by now (usually none or one period) */
    synchronized List<JsonObject> due(long now) {
        if (now < nextFlush) {
            return List.of();
        }
        List<JsonObject> out = flush(nextFlush);
        nextFlush = now >= nextFlush + periodMs ? now + periodMs : nextFlush + periodMs;   // after a stall: no burst
        return out;
    }

    /** the period so far (on stop) */
    synchronized List<JsonObject> rest(long now) {
        return average ? flush(now) : List.of();
    }

    private List<JsonObject> flush(long at) {
        List<JsonObject> out = new ArrayList<>();
        String to = Instant.ofEpochMilli(at).toString();
        if (!average) {
            for (JsonObject m : latest.values()) {
                JsonObject c = copy(m, to);
                JsonObject agg = new JsonObject();
                agg.addProperty("fn", "sample");
                agg.addProperty("windowSeconds", periodMs / 1000);
                c.add("aggregate", agg);
                out.add(c);
            }
            return out;
        }
        String from = Instant.ofEpochMilli(windowStart).toString();
        for (Window w : window.values()) {
            JsonObject c = copy(w.last, to);
            JsonObject agg = new JsonObject();
            if ("count".equals(fn)) {
                c.addProperty("tagValue", w.all);
                c.addProperty("dataType", "number");
                c.addProperty("quality", "GOOD");
                c.remove("qualityDetail");
                c.remove("unit");
                agg.addProperty("fn", "count");
                agg.addProperty("count", w.all);
            } else if (w.count > 0) {
                c.addProperty("tagValue", "min".equals(fn) ? w.min : "max".equals(fn) ? w.max : w.sum / w.count);
                c.addProperty("dataType", "number");
                c.addProperty("quality", "GOOD");
                c.remove("qualityDetail");
                agg.addProperty("fn", fn);
                agg.addProperty("min", w.min);
                agg.addProperty("max", w.max);
                agg.addProperty("count", w.count);
            } else {
                agg.addProperty("fn", "last");
            }
            agg.addProperty("windowSeconds", periodMs / 1000);
            agg.addProperty("from", from);
            agg.addProperty("to", to);
            c.add("aggregate", agg);
            out.add(c);
        }
        window.clear();
        windowStart = at;
        return out;
    }

    private static JsonObject copy(JsonObject m, String at) {
        JsonObject c = m.deepCopy();
        c.addProperty("messageId", UUID.randomUUID().toString());
        if (Json.str(m, "sourceTimestamp").isEmpty()) {
            c.addProperty("sourceTimestamp", Json.str(m, "timestamp"));
        }
        c.addProperty("timestamp", at);
        if (c.has("edge") && c.get("edge").isJsonObject()) {
            c.getAsJsonObject("edge").remove("seq");
        }
        return c;
    }
}
