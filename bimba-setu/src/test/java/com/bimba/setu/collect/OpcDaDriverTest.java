package com.bimba.setu.collect;

import com.bimba.setu.config.TagModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPC DA driver + collector against a fake bridge (the real one, site-agent/opcda, needs Windows and an OPC DA server):
 * protocol, quality mapping, canonical messages with protocol opcda.
 */
class OpcDaDriverTest {
    private static Path script;

    /** answers like bimba-opcda.exe: Random.Real4 = 12.5 GOOD, Bad.Item = OPC_E_UNKNOWNITEMID, Stale = last known value */
    public static void main(String[] args) throws Exception {
        System.out.println("{\"type\":\"hello\",\"version\":\"test\",\"bits\":32}");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            JsonObject req = JsonParser.parseString(line).getAsJsonObject();
            String id = req.get("id").getAsString();
            String op = req.get("op").getAsString();
            JsonObject out = new JsonObject();
            out.addProperty("type", "result");
            out.addProperty("id", id);
            if (req.get("progId").getAsString().equals("Missing.Server")) {
                out.addProperty("ok", false);
                out.addProperty("error", "the OPC DA server Missing.Server is not installed on this computer");
            } else if (op.equals("read") || op.equals("readOnce")) {
                JsonArray values = new JsonArray();
                for (var item : req.getAsJsonArray("items")) {
                    JsonObject v = new JsonObject();
                    switch (item.getAsString()) {
                        case "Random.Real4" -> { v.addProperty("v", 12.5); v.addProperty("q", 192); v.addProperty("t", "2026-10-09T10:00:00Z"); }
                        case "Stale" -> { v.addProperty("v", 3); v.addProperty("q", 0x14); }
                        default -> v.addProperty("e", "unknown item id (OPC_E_UNKNOWNITEMID)");
                    }
                    values.add(v);
                }
                JsonObject r = new JsonObject();
                r.add("values", values);
                out.addProperty("ok", true);
                out.add("result", r);
            } else if (op.equals("status")) {
                JsonObject r = new JsonObject();
                r.addProperty("product", "Fake OPC DA");
                r.addProperty("version", "2.5.0");
                r.addProperty("state", "running");
                out.addProperty("ok", true);
                out.add("result", r);
            } else {
                out.addProperty("ok", true);
                out.add("result", new JsonObject());
            }
            System.out.println(out);
            System.out.flush();
        }
    }

    @BeforeAll
    static void fakeBridge() throws Exception {
        Path dir = Files.createTempDirectory("opcda");
        script = dir.resolve("bimba-opcda");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Files.writeString(script, "#!/bin/sh\nexec \"" + java + "\" -cp \"" + System.getProperty("java.class.path") + "\" "
                + OpcDaDriverTest.class.getName() + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        System.setProperty("bimba.opcda.exe", script.toString());
    }

    @AfterAll
    static void off() {
        System.clearProperty("bimba.opcda.exe");
    }

    private static TagModel.Tag tag(String name, String item) {
        return new TagModel.Tag("DA-1", name, "DA-1/" + name, TagModel.Kind.OPCDA, "src1", item, null, null, 0, 1, 0, 1,
                false, null, null, null);
    }

    private static JsonObject settings(String progId) {
        JsonObject s = new JsonObject();
        s.addProperty("progId", progId);
        s.addProperty("pollMs", 200);
        return s;
    }

    @Test
    void readsValuesWithOpcQuality() throws Exception {
        try (OpcDaDriver d = new OpcDaDriver(settings("Matrikon.OPC.Simulation.1"))) {
            List<Driver.Reading> r = d.read(List.of(tag("t1", "Random.Real4"), tag("t2", "Bad.Item"), tag("t3", "Stale")));
            assertEquals(12.5, r.get(0).value());
            assertTrue(r.get(0).good());
            assertEquals("2026-10-09T10:00:00Z", r.get(0).sourceTime().toString());
            assertEquals(Canonical.Quality.BAD, r.get(1).quality());
            assertTrue(r.get(1).detail().contains("UNKNOWNITEMID"));
            assertEquals(Canonical.Quality.BAD, r.get(2).quality());
            assertTrue(r.get(2).detail().contains("last known value"));
        }
    }

    @Test
    void serverMissingFailsTheRead() {
        OpcDaDriver d = new OpcDaDriver(settings("Missing.Server"));
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> d.read(List.of(tag("t1", "x"))));
        assertTrue(e.getMessage().contains("not installed"));
    }

    @Test
    void qualityCodes() {
        assertEquals(Canonical.Quality.UNCERTAIN, OpcDaDriver.reading(JsonParser.parseString("{\"v\":1,\"q\":84}").getAsJsonObject()).quality());
        assertEquals("engineering units exceeded (OPC quality 0x54)", OpcDaDriver.substatus(0x54));
        assertEquals(Canonical.Quality.GOOD, OpcDaDriver.reading(JsonParser.parseString("{\"v\":true,\"q\":216}").getAsJsonObject()).quality(),
                "GOOD with local override is still good");
        assertNull(OpcDaDriver.reading(JsonParser.parseString("{\"q\":0}").getAsJsonObject()).value());
    }

    @Test
    void testConnectionShowsTheServer() throws Exception {
        var m = OpcDaDriver.test(settings("Matrikon.OPC.Simulation.1"));
        assertEquals("Fake OPC DA", m.get("product"));
        assertEquals("Matrikon.OPC.Simulation.1", m.get("target"));
    }

    @Test
    void collectorSendsCanonicalMessages() throws Exception {
        JsonObject sources = new JsonObject();
        JsonObject opcda = new JsonObject();
        opcda.add("src1", settings("Matrikon.OPC.Simulation.1"));
        sources.add("opcda", opcda);
        List<JsonObject> out = new CopyOnWriteArrayList<>();
        Collector c = new Collector("PLANT", "edge-1", 200, List.of(tag("level", "Random.Real4")), sources, out::add);
        c.start();
        try {
            long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
            while (out.isEmpty() && System.currentTimeMillis() < until) {
                Thread.sleep(100);
            }
        } finally {
            c.stop();
        }
        JsonObject m = out.stream().filter(x -> x.toString().contains("12.5")).findFirst().orElseThrow();
        assertTrue(m.toString().contains("\"opcda\""), m.toString());
        assertTrue(m.toString().contains("DA-1"), m.toString());
    }
}
