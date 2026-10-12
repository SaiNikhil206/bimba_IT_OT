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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** in-order recovery (FR-RES-01), gap markers kept out of the BIMBA database, repeats, state kept over a reload */
class ResilienceTest {

    /** a destination that is down or records the batch ids it got, in order */
    static class Recorder extends Destination {
        final List<String> got = new CopyOnWriteArrayList<>();
        volatile boolean down = true;

        Recorder() {
            super(cfg());
        }

        static BimbaConfig.Destination cfg() {
            BimbaConfig.Destination c = new BimbaConfig.Destination();
            c.name = "rec";
            c.healthCheck = false;
            c.retryAfterSeconds = 1;
            c.maxRetryAfterSeconds = 1;
            return c;
        }

        @Override
        public void send(byte[] batch) throws Exception {
            if (down) {
                throw new java.io.IOException("down");
            }
            got.add(Json.str(Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class), "batchId"));
        }

        @Override
        public String test() {
            return "ok";
        }

        @Override
        public String endpoint() {
            return "rec";
        }
    }

    private static byte[] batch(String id) {
        return ("{\"batchId\":\"" + id + "\",\"count\":1,\"messages\":[{\"messageId\":\"" + id + "\",\"machineId\":\"M\",\"tagName\":\"T\"}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void afterAnOutageBatchesArriveInTheOrderTheyWereMade(@TempDir File dir) throws Exception {
        Recorder d = new Recorder();
        Route r = new Route("t", List.of(d), new DiskBuffer(dir, 10), 2);       // 2 per cycle: the backlog takes several
        r.start();
        try {
            for (int i = 1; i <= 6; i++) {
                r.submit(batch("b" + i));
            }
            RestThrottleTest.waitFor(() -> r.buffer().count() == 6);
            d.down = false;
            for (int i = 7; i <= 9; i++) {                                      // live batches while the backlog drains
                r.submit(batch("b" + i));
            }
            RestThrottleTest.waitFor(() -> d.got.size() == 9);
            List<String> want = new ArrayList<>();
            for (int i = 1; i <= 9; i++) {
                want.add("b" + i);
            }
            assertEquals(want, d.got, "oldest first, live batches behind the ones that waited");
            assertEquals(0, r.buffer().count());
        } finally {
            r.stop();
        }
    }

    @Test
    void theBimbaDatabaseGetsNoGapMarkers() {
        String readingsOnly = "{\"count\":1,\"messages\":[{\"machineId\":\"M\",\"tagName\":\"T\",\"category\":\"telemetry\"}]}";
        assertEquals(readingsOnly, DatabaseDestination.withoutGapMarkers(readingsOnly), "nothing to take out: unchanged");
        String mixed = "{\"count\":2,\"messages\":[{\"machineId\":\"M\",\"tagName\":\"T\",\"category\":\"telemetry\"},"
                + "{\"machineId\":\"M\",\"tagName\":\"_connection\",\"category\":\"gap\"}]}";
        JsonObject left = Json.GSON.fromJson(DatabaseDestination.withoutGapMarkers(mixed), JsonObject.class);
        assertEquals(1, left.getAsJsonArray("messages").size());
        assertEquals(1, left.get("count").getAsInt());
        assertNull(DatabaseDestination.withoutGapMarkers("{\"count\":1,\"messages\":[{\"category\":\"gap\"}]}"), "only markers: nothing to store");
    }

    @Test
    void unchangedTagsAreSentAgain() {
        Refresher r = new Refresher(60);
        JsonObject m = JsonParser.parseString("{\"messageId\":\"a\",\"machineId\":\"M\",\"tagName\":\"T\",\"tagValue\":5,"
                + "\"timestamp\":\"2026-10-09T08:00:00Z\",\"edge\":{\"seq\":4}}").getAsJsonObject();
        JsonObject gap = JsonParser.parseString("{\"machineId\":\"M\",\"tagName\":\"_connection\",\"category\":\"gap\"}").getAsJsonObject();
        r.sent(List.of(m, gap), 0);
        assertTrue(r.due(59_999).isEmpty());
        List<JsonObject> again = r.due(60_000);
        assertEquals(1, again.size(), "the reading, not the gap marker");
        JsonObject c = again.get(0);
        assertTrue(!"a".equals(Json.str(c, "messageId")));
        assertEquals("2026-10-09T08:00:00Z", Json.str(c, "sourceTimestamp"), "when the value was read");
        assertEquals("1970-01-01T00:01:00Z", Json.str(c, "timestamp"));
        assertEquals("repeat", Json.str(c.getAsJsonObject("aggregate"), "fn"));
        assertTrue(!c.getAsJsonObject("edge").has("seq"));
        assertTrue(r.due(60_500).isEmpty(), "not again before the next period");
        r.sent(List.of(m), 100_000);                                            // a new reading resets the wait
        assertTrue(r.due(159_999).isEmpty());
        assertEquals(1, r.due(160_000).size());
    }

    private static BimbaConfig cfg(String url, boolean dedupe, int refresh) {
        BimbaConfig.Pipeline p = new BimbaConfig.Pipeline();
        p.id = 7;
        p.name = "p";
        p.dedupe = dedupe;
        p.refreshSeconds = refresh;
        p.batchMaxWaitMs = 50;
        BimbaConfig.Destination d = new BimbaConfig.Destination();
        d.name = "d";
        d.url = url;
        d.healthCheck = false;
        p.primary = d;
        BimbaConfig c = new BimbaConfig();
        c.pipelines = List.of(p);
        return c;
    }

    private static JsonObject reading(String id) {
        return JsonParser.parseString("{\"messageId\":\"" + id + "\",\"machineId\":\"M\",\"tagName\":\"T\",\"tagValue\":1,"
                + "\"timestamp\":\"2026-10-09T08:00:0" + id.charAt(id.length() - 1) + "Z\"}").getAsJsonObject();
    }

    @Test
    void aReloadKeepsWhatThePipelineRemembersAndRepeatsAreSent(@TempDir File dir) throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            JsonObject env = Json.GSON.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            env.getAsJsonArray("messages").forEach(e -> got.add(Json.str(e.getAsJsonObject(), "messageId")
                    + (e.getAsJsonObject().has("aggregate") ? "+repeat" : "")));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        DeliveryEngine e1 = new DeliveryEngine(cfg(url, true, 0), dir, null);
        e1.start();
        DeliveryEngine e2 = null;
        DeliveryEngine e3 = null;
        try {
            e1.offer(reading("r1"));
            RestThrottleTest.waitFor(() -> got.contains("r1"));
            BimbaConfig same = cfg(url, true, 0);
            e1.stop(same);
            e2 = new DeliveryEngine(same, dir, null, e1);                       // e.g. another pipeline was edited
            e2.start();
            e2.offer(reading("r1"));                                            // the agent sends it again
            e2.offer(reading("r2"));
            RestThrottleTest.waitFor(() -> got.contains("r2"));
            assertEquals(1, got.stream().filter("r1"::equals).count(), "still known as a duplicate after the reload");

            BimbaConfig refresh = cfg(url, false, 1);                           // filters changed: forgotten; repeats every 1 s
            e2.stop(refresh);
            e3 = new DeliveryEngine(refresh, dir, null, e2);
            e3.start();
            e3.offer(reading("r1"));
            RestThrottleTest.waitFor(() -> got.stream().filter(x -> x.endsWith("+repeat")).count() >= 1);
            assertEquals(2, got.stream().filter("r1"::equals).count(), "new filter settings start fresh");
        } finally {
            (e3 != null ? e3 : e2 != null ? e2 : e1).stop();
            server.stop(0);
        }
    }
}
