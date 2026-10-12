package com.bimba.setu.delivery;

import com.bimba.setu.config.BimbaConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-END-01: 429 / 503 with Retry-After, PUT, and the CSV / XML bodies of the REST destination */
class RestThrottleTest {

    private static byte[] batch() {
        return ("{\"schemaVersion\":\"1.3\",\"batchId\":\"b1\",\"plantId\":\"P1\",\"edgeId\":\"gw\",\"createdAt\":\"2026-10-09T08:00:00Z\",\"count\":1,"
                + "\"messages\":[{\"messageId\":\"m1\",\"plantId\":\"P1\",\"machineId\":\"M\",\"tagName\":\"T\",\"tagValue\":1.5,"
                + "\"dataType\":\"number\",\"unit\":\"bar\",\"quality\":\"GOOD\",\"timestamp\":\"2026-10-09T08:00:00Z\"}]}").getBytes(StandardCharsets.UTF_8);
    }

    private static BimbaConfig.Destination rest(String name, String url) {
        BimbaConfig.Destination d = new BimbaConfig.Destination();
        d.name = name;
        d.url = url;
        d.healthCheck = false;
        d.retryAfterSeconds = 1;
        return d;
    }

    @Test
    void retryAfterIsReadAsSecondsOrDate() {
        long now = System.currentTimeMillis();
        assertEquals(120, Destination.retryAfter(Optional.of("120"), now));
        assertEquals(0, Destination.retryAfter(Optional.empty(), now));
        assertEquals(0, Destination.retryAfter(Optional.of("soon"), now));
        String in90 = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(90));
        long s = Destination.retryAfter(Optional.of(in90), now);
        assertTrue(s >= 88 && s <= 91, "date form: " + s);
    }

    @Test
    void busyReceiverIsSkippedAsLongAsItAsksAndTheSecondaryTakesOver(@TempDir File dir) throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/busy", ex -> {
            got.add("busy " + ex.getRequestMethod());
            ex.getResponseHeaders().add("Retry-After", "120");
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        server.createContext("/ok", ex -> {
            got.add("ok " + ex.getRequestMethod());
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        Route r = null;
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            BimbaConfig.Destination busy = rest("busy", base + "/busy");
            busy.method = "PUT";
            RestDestination primary = new RestDestination(busy);
            RestDestination secondary = new RestDestination(rest("ok", base + "/ok"));
            r = new Route("t", List.of(primary, secondary), new DiskBuffer(dir, 10), 5);
            r.start();
            long before = System.currentTimeMillis();
            r.submit(batch());
            waitFor(() -> got.contains("ok POST"));
            assertEquals("busy PUT", got.get(0), "the primary is asked first, with PUT");
            assertEquals("throttled", primary.status().get("state"));
            long retryAt = java.time.Instant.parse((String) primary.status().get("retryAt")).toEpochMilli();
            assertTrue(retryAt - before >= 119_000 && retryAt - before <= 125_000, "waits the 120 s it asked for, not its own 1 s");
            assertTrue(String.valueOf(primary.status().get("lastError")).contains("Retry-After 120"));

            r.submit(batch());
            waitFor(() -> got.stream().filter("ok POST"::equals).count() == 2);
            assertEquals(1, got.stream().filter(x -> x.startsWith("busy")).count(), "not asked again before Retry-After");
        } finally {
            if (r != null) {
                r.stop();
            }
            server.stop(0);
        }
    }

    @Test
    void busyWithoutRetryAfterUsesTheBackoffAnd503CountsToo() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        server.start();
        try {
            RestDestination d = new RestDestination(rest("x", "http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            Destination.RetryLaterException e = assertThrows(Destination.RetryLaterException.class, () -> d.send(batch()));
            assertEquals(0, e.seconds);
            d.markThrottled(e.getMessage(), e.seconds);
            assertEquals("throttled", d.status().get("state"));
            assertEquals(1, d.status().get("consecutiveFailures"), "no Retry-After: the normal backoff counts it");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void csvAndXmlBodies() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> types = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            types.add(ex.getRequestHeaders().getFirst("Content-Type"));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            BimbaConfig.Destination c = rest("csv", url);
            c.format = "csv";
            c.csvSeparator = ";";
            new RestDestination(c).send(batch());
            BimbaConfig.Destination x = rest("xml", url);
            x.format = "xml";
            x.template = "{\"@tag\": \"{tagName}\", \"value\": \"{tagValue}\"}";
            new RestDestination(x).send(batch());
            assertTrue(types.get(0).startsWith("text/csv"));
            assertTrue(bodies.get(0).startsWith("plantId;machineId;tagName;timestamp;tagValue;dataType;unit;quality"), bodies.get(0));
            assertTrue(bodies.get(0).contains("P1;M;T;2026-10-09T08:00:00Z;1.5;number;bar;GOOD"), bodies.get(0));
            assertTrue(types.get(1).startsWith("application/xml"));
            assertTrue(bodies.get(1).contains("<batch schemaVersion=\"1.3\" batchId=\"b1\""), bodies.get(1));
            assertTrue(bodies.get(1).contains("<reading tag=\"T\">"), bodies.get(1));
            assertTrue(bodies.get(1).contains("<value>1.5</value>"), bodies.get(1));
        } finally {
            server.stop(0);
        }
    }

    static void waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + 8000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(50);
        }
    }
}
