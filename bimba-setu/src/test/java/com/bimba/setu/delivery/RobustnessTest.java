package com.bimba.setu.delivery;

import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobustnessTest {
    private static String pem(String name) throws Exception {
        return Files.readString(Path.of(RobustnessTest.class.getResource("/tls/" + name).toURI()));
    }

    /** a destination that records what it was sent and answers as told */
    static class Fake extends Destination {
        final List<Integer> sizes = new ArrayList<>();
        volatile String answer = "ok";        // ok | reject | down

        Fake(String name, String role) {
            super(cfg(name, role));
        }

        static BimbaConfig.Destination cfg(String name, String role) {
            BimbaConfig.Destination c = new BimbaConfig.Destination();
            c.name = name;
            c.role = role;
            c.healthCheck = false;
            return c;
        }

        @Override
        public void send(byte[] batch) throws Exception {
            switch (answer) {
                case "reject" -> throw new RejectedException("HTTP 400 bad field");
                case "down" -> throw new java.io.IOException("connection refused");
                default -> sizes.add(JsonParser.parseString(new String(batch, StandardCharsets.UTF_8)).getAsJsonObject()
                        .getAsJsonArray("messages").size());
            }
        }

        @Override
        public String test() {
            return "ok";
        }

        @Override
        public String endpoint() {
            return "fake";
        }
    }

    private static byte[] batch(int n) {
        StringBuilder sb = new StringBuilder("{\"batchId\":\"b\",\"count\":" + n + ",\"messages\":[");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"messageId\":\"m").append(i).append("\",\"machineId\":\"M\",\"tagName\":\"T\"}");
        }
        return sb.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ certificates

    @Test
    void certificatesAndKeysAreRead() throws Exception {
        assertEquals("CN=BIMBA Test CA", TlsSupport.certificates(pem("ca.pem")).get(0).getSubjectX500Principal().getName());
        assertEquals("RSA", TlsSupport.privateKey(pem("client.key")).getAlgorithm());
        assertEquals("RSA", TlsSupport.privateKey(pem("client-pkcs1.key")).getAlgorithm());   // PKCS#1 is converted
        assertTrue(TlsSupport.pkcs8Pem(pem("client-pkcs1.key")).startsWith("-----BEGIN PRIVATE KEY-----"));
        TlsSupport.validate(pem("ca.pem"), pem("client.pem"), pem("client.key"));
        assertNotNull(TlsSupport.context(pem("ca.pem"), pem("client.pem"), pem("client-pkcs1.key")));
        Map<String, Object> d = TlsSupport.describe(pem("client.pem")).get(0);
        assertEquals("CN=bimba-gateway-test", d.get("subject"));
        assertEquals(false, d.get("expired"));
    }

    @Test
    void certificateMistakesAreExplained() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> TlsSupport.validate("", pem("client.pem"), ""));     // key missing
        assertThrows(IllegalArgumentException.class, () -> TlsSupport.certificates("not a certificate"));
        assertThrows(IllegalArgumentException.class, () -> TlsSupport.validate("", pem("client.pem"), pem("server.key"))); // wrong key
    }

    // ------------------------------------------------------------------ retries, splitting

    @Test
    void retryWaitDoublesUpToTheLimit() {
        Fake f = new Fake("x", "primary");
        f.cfg.retryAfterSeconds = 15;
        f.cfg.maxRetryAfterSeconds = 300;
        assertEquals(15, f.retryDelaySeconds(1));
        assertEquals(30, f.retryDelaySeconds(2));
        assertEquals(120, f.retryDelaySeconds(4));
        assertEquals(300, f.retryDelaySeconds(6));
        assertEquals(300, f.retryDelaySeconds(60));
    }

    @Test
    void largeBatchesAreSplit() throws Exception {
        Fake f = new Fake("x", "primary");
        f.cfg.maxBatchMessages = 4;
        f.sendAll(batch(10));
        assertEquals(List.of(4, 4, 2), f.sizes);
        f.sizes.clear();
        f.cfg.maxBatchMessages = 0;
        f.sendAll(batch(10));
        assertEquals(List.of(10), f.sizes);
    }

    // ------------------------------------------------------------------ rejected batches

    @Test
    void secondaryGetsWhatThePrimaryRefusedAndNothingIsLost(@TempDir File dir) throws Exception {
        Fake primary = new Fake("primary", "primary");
        Fake secondary = new Fake("secondary", "failover");
        primary.answer = "reject";
        Route r = new Route("t", List.of(primary, secondary), new DiskBuffer(dir, 10), 5);
        r.start();
        r.submit(batch(3));
        waitFor(() -> secondary.sizes.size() == 1);
        assertEquals(0, r.buffer().rejectedCount(), "delivered by the secondary: not rejected");

        secondary.answer = "reject";
        r.submit(batch(2));
        waitFor(() -> r.buffer().rejectedCount() == 1);
        Map<String, Object> rej = r.buffer().rejected().get(0);
        assertEquals("primary", rej.get("destination"));
        assertTrue(String.valueOf(rej.get("reason")).contains("bad field"));
        assertEquals(2L, rej.get("count"));

        secondary.answer = "ok";
        r.buffer().resend((String) rej.get("file"));
        waitFor(() -> secondary.sizes.size() == 2);                    // replayed from disk
        assertEquals(0, r.buffer().rejectedCount());
        r.stop();
    }

    @Test
    void rejectedFilesCannotEscapeTheirFolder(@TempDir File dir) throws Exception {
        DiskBuffer b = new DiskBuffer(dir, 10);
        assertThrows(java.io.IOException.class, () -> b.discard("../../config.json"));
        assertThrows(java.io.IOException.class, () -> b.resend("..\\x.json"));
        b.reject(batch(1), "d", "why", 1);
        String name = (String) b.rejected().get(0).get("file");
        assertTrue(b.rejectedText(name, 1000).contains("\"messages\""));
        b.discard(name);
        assertEquals(0, b.rejectedCount());
    }

    @Test
    void downThenUpIsCounted(@TempDir File dir) throws Exception {
        Fake only = new Fake("only", "mirror");
        only.answer = "down";
        only.cfg.retryAfterSeconds = 1;
        Route r = new Route("m", List.of(only), new DiskBuffer(dir, 10), 5);
        r.start();
        r.submit(batch(1));
        waitFor(() -> r.buffer().count() == 1);                        // kept on disk while down
        only.answer = "ok";
        waitFor(() -> only.sizes.size() == 1);                         // replayed after the retry wait
        JsonObject c = com.bimba.setu.Json.GSON.toJsonTree(only.counters()).getAsJsonObject();
        assertEquals(1, c.get("failures").getAsInt());
        assertEquals(1, c.get("messages").getAsInt());
        r.stop();
    }

    private static void waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + 8000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(50);
        }
    }
}
