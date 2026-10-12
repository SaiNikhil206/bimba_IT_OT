package com.bimba.setu.collect;

import com.bimba.setu.Json;
import com.bimba.setu.canonical.MessageCheck;
import com.bimba.setu.config.TagModel;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-RES-03: a device that stops answering gets its tags BAD and a gap marker per machine; "restored" when it is back */
class GapMarkerTest {

    @Test
    void lostAndRestoredMarkersPerMachine() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String tree = """
                {"name":"BIMBA","tagType":"Folder","tags":[
                  {"name":"PUMP-1","tagType":"Folder","tags":[
                    {"name":"flow","tagType":"AtomicTag","valueSource":"opc","opcServer":"BIMBA Modbus","opcItemPath":"ns=1;s=[PLC]HR1"}]},
                  {"name":"TANK-2","tagType":"Folder","tags":[
                    {"name":"level","tagType":"AtomicTag","valueSource":"opc","opcServer":"BIMBA Modbus","opcItemPath":"ns=1;s=[PLC]HR2"}]}]}""";
        List<TagModel.Tag> tags = TagModel.parse(Json.GSON.fromJson(tree, JsonObject.class), new ArrayList<>());
        JsonObject sources = Json.GSON.fromJson("{\"modbus\":{\"PLC\":{\"host\":\"127.0.0.1\",\"port\":" + port + ",\"timeoutMs\":300,\"retryMaxMs\":1000}}}", JsonObject.class);
        List<JsonObject> out = new CopyOnWriteArrayList<>();
        Collector c = new Collector("P1", "gw", 200, tags, sources, out::add);
        ModbusSlave slave = null;
        c.start();
        try {
            waitFor(() -> count(out, gap("start")) == 2);
            JsonObject lost = out.stream().filter(gap("start")).findFirst().orElseThrow();
            assertEquals(Canonical.GAP_TAG, lost.get("tagName").getAsString());
            assertEquals("lost", lost.get("tagValue").getAsString());
            assertEquals("BAD", lost.get("quality").getAsString());
            assertEquals("modbus PLC", lost.getAsJsonObject("gap").get("device").getAsString());
            assertTrue(lost.getAsJsonObject("gap").get("reason").getAsString().contains("cannot connect"));
            assertNull(MessageCheck.check(lost), "a valid 1.x message");
            assertEquals(2, out.stream().filter(m -> !"gap".equals(Json.str(m, "category")) && "BAD".equals(Json.str(m, "quality"))).count(),
                    "and every tag BAD");
            Thread.sleep(600);
            assertEquals(2, count(out, gap("start")), "said once, not every poll");
            java.util.Map<?, ?> dev = (java.util.Map<?, ?>) ((List<?>) c.status().get("devices")).get(0);
            assertTrue(dev.get("retryAt") != null, "lost: tried again with a growing wait (FR-RES-03)");

            SimpleProcessImage image = new SimpleProcessImage();
            image.addRegister(new SimpleRegister(11));
            image.addRegister(new SimpleRegister(22));
            slave = ModbusSlaveFactory.createTCPSlave(port, 2);
            slave.addProcessImage(1, image);
            slave.open();
            waitFor(() -> count(out, gap("end")) == 2);
            JsonObject back = out.stream().filter(gap("end")).findFirst().orElseThrow();
            assertEquals("restored", back.get("tagValue").getAsString());
            assertEquals("GOOD", back.get("quality").getAsString());
            JsonObject g = back.getAsJsonObject("gap");
            assertTrue(Instant.parse(g.get("from").getAsString()).isBefore(Instant.parse(g.get("to").getAsString())));
            assertTrue(g.get("seconds").getAsDouble() > 0.5);
            assertTrue(out.stream().anyMatch(m -> "flow".equals(Json.str(m, "tagName")) && "GOOD".equals(Json.str(m, "quality"))));
        } finally {
            c.stop();
            if (slave != null) {
                ModbusSlaveFactory.close(slave);
            }
        }
    }

    private static Predicate<JsonObject> gap(String state) {
        return m -> "gap".equals(Json.str(m, "category")) && state.equals(Json.str(m.getAsJsonObject("gap"), "state"));
    }

    private static long count(List<JsonObject> l, Predicate<JsonObject> p) {
        return l.stream().filter(p).count();
    }

    private static void waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(50);
        }
    }
}
