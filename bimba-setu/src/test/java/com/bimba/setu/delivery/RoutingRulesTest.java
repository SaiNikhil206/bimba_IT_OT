package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-RTE-05 (min / max / count windows, duplicates, change only), gap markers in pipelines, FR-RES-05 buffer alert */
class RoutingRulesTest {
    private static JsonObject reading(String id, String machine, String tag, Object value, String quality, String ts) {
        String v = value == null ? "null" : value instanceof String s ? "\"" + s + "\"" : value.toString();
        String type = value instanceof Number ? "number" : value instanceof Boolean ? "boolean" : value == null ? "null" : "string";
        return JsonParser.parseString(("{\"schemaVersion\":\"1.3\",\"messageId\":\"%s\",\"plantId\":\"P1\",\"machineId\":\"%s\",\"tagName\":\"%s\","
                + "\"tagValue\":%s,\"dataType\":\"%s\",\"unit\":\"C\",\"quality\":\"%s\",\"timestamp\":\"%s\"}")
                .formatted(id, machine, tag, v, type, quality, ts)).getAsJsonObject();
    }

    private static JsonObject gap(String machine) {
        JsonObject m = reading("g-" + machine, machine, "_connection", "lost", "BAD", "2026-10-09T08:00:05Z");
        m.addProperty("category", "gap");
        return m;
    }

    @Test
    void minMaxAndCountWindows() {
        List<JsonObject> in = List.of(reading("1", "M", "T", 10.0, "GOOD", "2026-10-09T08:00:01Z"), reading("2", "M", "T", 30.0, "GOOD", "2026-10-09T08:00:02Z"),
                reading("3", "M", "T", -99.0, "BAD", "2026-10-09T08:00:03Z"), reading("4", "M", "Mode", "auto", "GOOD", "2026-10-09T08:00:04Z"));
        Map<String, Double> want = Map.of("min", 10.0, "max", 30.0, "average", 20.0);
        for (Map.Entry<String, Double> w : want.entrySet()) {
            Sampler s = new Sampler(w.getKey(), 10, 0);
            s.add(in);
            JsonObject t = s.due(10_000).get(0);
            assertEquals(w.getValue(), t.get("tagValue").getAsDouble(), w.getKey());
            assertEquals("average".equals(w.getKey()) ? "avg" : w.getKey(), t.getAsJsonObject("aggregate").get("fn").getAsString());
            assertEquals(2, t.getAsJsonObject("aggregate").get("count").getAsInt(), "GOOD numbers only");
        }
        Sampler count = new Sampler("count", 10, 0);
        count.add(in);
        List<JsonObject> out = count.due(10_000);
        assertEquals(3.0, out.get(0).get("tagValue").getAsDouble(), "every reading of the tag, also the BAD one");
        assertEquals(1.0, out.get(1).get("tagValue").getAsDouble(), "text tags are counted too");
        assertEquals("number", out.get(1).get("dataType").getAsString());
        assertFalse(out.get(0).has("unit"), "a count has no unit");
    }

    @Test
    void duplicatesAndUnchangedValuesAreDropped() {
        Filters f = new Filters(true, true, 0.5);
        assertTrue(f.fresh(reading("a", "M", "T", 1.0, "GOOD", "2026-10-09T08:00:01Z")));
        assertFalse(f.fresh(reading("a", "M", "T", 1.0, "GOOD", "2026-10-09T08:00:01Z")), "same message id");
        assertFalse(f.fresh(reading("b", "M", "T", 1.0, "GOOD", "2026-10-09T08:00:01Z")), "same machine, tag and time");
        assertTrue(f.fresh(reading("c", "M", "T", 1.0, "GOOD", "2026-10-09T08:00:02Z")));

        assertTrue(f.changed(reading("1", "M", "T", 10.0, "GOOD", "t")));
        assertFalse(f.changed(reading("2", "M", "T", 10.4, "GOOD", "t")), "within the deadband");
        assertFalse(f.changed(reading("3", "M", "T", 10.5, "GOOD", "t")), "exactly the deadband: unchanged");
        assertTrue(f.changed(reading("4", "M", "T", 10.6, "GOOD", "t")));
        assertTrue(f.changed(reading("5", "M", "T", 10.6, "BAD", "t")), "a quality change is sent");
        assertTrue(f.changed(reading("6", "M", "Mode", "auto", "GOOD", "t")));
        assertFalse(f.changed(reading("7", "M", "Mode", "auto", "GOOD", "t")));
        assertTrue(f.changed(gap("M")) && f.changed(gap("M")) && f.fresh(gap("M")) && f.fresh(gap("M")), "gap markers always pass");
        assertEquals(2L, f.status().get("duplicatesDropped"));
        assertEquals(3L, f.status().get("unchangedDropped"));
    }

    private static BimbaConfig.Pipeline pipeline(long id, String url, String rate, List<String> include, boolean gaps, boolean dedupe) {
        BimbaConfig.Pipeline p = new BimbaConfig.Pipeline();
        p.id = id;
        p.name = "p" + id;
        p.rateMode = rate;
        p.rateSeconds = 3600;
        p.tagInclude = include;
        p.gapMarkers = gaps;
        p.dedupe = dedupe;
        p.batchMaxWaitMs = 100;
        BimbaConfig.Destination d = new BimbaConfig.Destination();
        d.name = "d" + id;
        d.url = url;
        d.healthCheck = false;
        p.primary = d;
        return p;
    }

    @Test
    void gapMarkersPassTagPatternsAndSamplersUnlessTurnedOff(@TempDir File dir) throws Exception {
        Map<String, List<JsonObject>> got = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            JsonObject env = Json.GSON.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            env.getAsJsonArray("messages").forEach(e -> got.computeIfAbsent(ex.getRequestURI().getPath(), k -> new CopyOnWriteArrayList<>())
                    .add(e.getAsJsonObject()));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        DeliveryEngine e = null;
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            BimbaConfig cfg = new BimbaConfig();
            cfg.pipelines = List.of(
                    pipeline(1, base + "/avg", "average", List.of("Flow"), true, false),    // hourly averages of Flow only
                    pipeline(2, base + "/nogaps", "change", List.of(), false, true),         // every change, no markers, no duplicates
                    pipeline(3, base + "/flow", "change", List.of("Flow"), true, false));
            e = new DeliveryEngine(cfg, dir, null);
            e.start();
            e.offer(reading("r1", "M", "Flow", 1.0, "GOOD", "2026-10-09T08:00:01Z"));
            e.offer(reading("r1", "M", "Flow", 1.0, "GOOD", "2026-10-09T08:00:01Z"));   // sent twice by an agent
            e.offer(reading("r2", "M", "Temp", 20.0, "GOOD", "2026-10-09T08:00:01Z"));
            e.offer(gap("M"));
            long until = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < until && (got.get("/avg") == null || size(got.get("/nogaps")) < 2 || size(got.get("/flow")) < 3)) {
                Thread.sleep(50);
            }
            assertEquals(List.of("_connection"), got.get("/avg").stream().map(m -> m.get("tagName").getAsString()).toList(),
                    "the average waits for its hour, the gap marker goes at once, whatever the tag patterns");
            assertEquals(List.of("Flow", "Temp"), got.get("/nogaps").stream().map(m -> m.get("tagName").getAsString()).toList(),
                    "no marker, and the reading sent twice arrives once");
            assertEquals(3, size(got.get("/flow")), "without the filter both copies arrive, and the marker");
            Map<String, Object> p2 = ((List<Map<String, Object>>) e.status().get("pipelines")).get(1);
            assertEquals(1L, ((Map<?, ?>) p2.get("filters")).get("duplicatesDropped"));
            assertTrue(((Number) p2.get("bytesPerReading")).longValue() > 100, "measured for the buffer sizing formula");
        } finally {
            if (e != null) {
                e.stop();
            }
            server.stop(0);
        }
    }

    private static int size(List<?> l) {
        return l == null ? 0 : l.size();
    }

    @Test
    void bufferWarnsAt70PercentAndClearsBelow60(@TempDir File dir) throws Exception {
        DiskBuffer b = new DiskBuffer(dir, 1);
        List<String> events = new CopyOnWriteArrayList<>();
        b.onUsage((high, percent) -> events.add((high ? "high " : "normal ") + percent));
        byte[] chunk = new byte[300 * 1024];
        b.write(chunk);
        b.write(chunk);
        assertTrue(events.isEmpty(), "58 %");
        b.write(chunk);
        assertEquals(List.of("high 87"), events);
        assertTrue(b.high());
        b.write(chunk);                                                // over the size: the oldest is discarded
        assertEquals(3, b.count());
        assertEquals(900 * 1024, b.bytes());
        b.delete(b.oldest().orElseThrow());
        assertEquals(List.of("high 87", "normal 58"), events, "below 60 %: back to normal");
        assertFalse(b.high());
        b.delete(b.oldest().orElseThrow());
        assertEquals(2, events.size(), "told once");
        DiskBuffer again = new DiskBuffer(dir, 1);                     // counted from the folder after a restart
        assertEquals(1, again.count());
        assertEquals(300 * 1024, again.bytes());
    }
}
