package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonObject;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-CAN-06: batch size and longest wait per pipeline. */
class PipelineBatchingTest {

    private static BimbaConfig.Pipeline pipeline(long id, String url, int max, int waitMs) {
        BimbaConfig.Destination d = new BimbaConfig.Destination();
        d.name = "d" + id;
        d.transport = "rest";
        d.url = url;
        d.healthCheck = false;
        BimbaConfig.Pipeline p = new BimbaConfig.Pipeline();
        p.id = id;
        p.name = "p" + id;
        p.primary = d;
        p.batchMaxMessages = max;
        p.batchMaxWaitMs = waitMs;
        return p;
    }

    private static JsonObject reading(int i) {
        JsonObject m = new JsonObject();
        m.addProperty("messageId", "m" + i);
        m.addProperty("machineId", "PUMP-P1");
        m.addProperty("tagName", "flow");
        m.addProperty("tagValue", i);
        return m;
    }

    @Test
    void eachPipelineBatchesItsOwnWay(@TempDir File dir) throws Exception {
        Map<String, List<Integer>> got = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            JsonObject env = Json.GSON.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            got.computeIfAbsent(ex.getRequestURI().getPath(), k -> new CopyOnWriteArrayList<>()).add(env.getAsJsonArray("messages").size());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        DeliveryEngine e = null;
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            BimbaConfig cfg = new BimbaConfig();
            cfg.batch.maxMessages = 500;
            cfg.batch.maxWaitMs = 5000;                    // Setu default would hold everything for 5 s
            cfg.pipelines = List.of(pipeline(1, base + "/small", 3, 150), pipeline(2, base + "/one", 1, 0),
                    pipeline(3, base + "/default", 0, 0));
            e = new DeliveryEngine(cfg, dir, null);
            e.start();
            for (int i = 0; i < 7; i++) {
                e.offer(reading(i));
            }
            long until = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < until && (sum(got.get("/small")) < 7 || sum(got.get("/one")) < 7)) {
                Thread.sleep(50);
            }
            assertEquals(List.of(3, 3, 1), got.get("/small"), "3 per batch, the rest after 150 ms");
            assertEquals(7, got.get("/one").size(), "batch size 1: every reading in its own envelope");
            assertTrue(got.get("/one").stream().allMatch(n -> n == 1));
            assertTrue(!got.containsKey("/default"), "the default pipeline still waits for its 5 s");
            Map<String, Object> status = e.status();
            assertTrue(String.valueOf(status).contains("batchMaxMessages=3"), String.valueOf(status));
        } finally {
            if (e != null) {
                e.stop();
            }
            server.stop(0);
        }
        File saved = new File(dir, "buffer/pipelines/3/main");
        String[] files = saved.list((d, n) -> n.endsWith(".json"));
        assertEquals(1, files == null ? 0 : files.length, "what the default pipeline collected is kept on disk at stop");
    }

    private static int sum(List<Integer> l) {
        return l == null ? 0 : l.stream().mapToInt(Integer::intValue).sum();
    }
}
