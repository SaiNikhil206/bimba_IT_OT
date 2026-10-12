package com.bimba.setu.collect;

import com.bimba.setu.config.TagModel;
import com.google.gson.JsonObject;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MQTT driver against an in-memory broker (Moquette): plain values, JSON fields, Sparkplug B, browse. */
class MqttDriverTest {
    private static Server broker;
    private static String url;
    private static MqttClient pub;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Properties p = new Properties();
        p.setProperty("host", "127.0.0.1");
        p.setProperty("port", String.valueOf(port));
        p.setProperty("allow_anonymous", "true");
        p.setProperty("persistence_enabled", "false");
        p.setProperty("netty.mqtt.message_size", "1048576");
        broker = new Server();
        broker.startServer(new MemoryConfig(p));
        url = "tcp://127.0.0.1:" + port;
        pub = new MqttClient(url, "test-publisher", new MemoryPersistence());
        pub.connect();
    }

    @AfterAll
    static void stop() throws Exception {
        pub.disconnect();
        broker.stopServer();
    }

    private static void publish(String topic, byte[] payload, boolean retained) throws Exception {
        MqttMessage m = new MqttMessage(payload);
        m.setQos(1);
        m.setRetained(retained);
        pub.publish(topic, m);
    }

    private static void publish(String topic, String text, boolean retained) throws Exception {
        publish(topic, text.getBytes(StandardCharsets.UTF_8), retained);
    }

    private static TagModel.Tag tag(String address) {
        return new TagModel.Tag("M", address, "M/" + address, TagModel.Kind.MQTT, "s1", address, null, null, 0, 1, 0, 1,
                false, null, null, null);
    }

    private static JsonObject settings(String payload) {
        JsonObject s = new JsonObject();
        s.addProperty("url", url);
        s.addProperty("payload", payload);
        return s;
    }

    /** reads until every tag has a value (the subscription delivers asynchronously) */
    private static List<Driver.Reading> readUntil(MqttDriver d, List<TagModel.Tag> tags, java.util.function.Predicate<List<Driver.Reading>> done) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        List<Driver.Reading> r = d.read(tags);
        while (!done.test(r) && System.currentTimeMillis() < until) {
            Thread.sleep(100);
            r = d.read(tags);
        }
        return r;
    }

    @Test
    void plainValuesAndRetained() throws Exception {
        publish("plant/line1/temp", "21.5", true);
        publish("plant/line1/run", "true", true);
        publish("plant/line1/mode", "AUTO", true);
        List<TagModel.Tag> tags = List.of(tag("plant/line1/temp"), tag("plant/line1/run"), tag("plant/line1/mode"), tag("plant/line1/none"));
        try (MqttDriver d = new MqttDriver(settings("value"))) {
            List<Driver.Reading> r = readUntil(d, tags, x -> x.get(0).good() && x.get(1).good() && x.get(2).good());
            assertEquals(21.5, r.get(0).value());
            assertEquals(true, r.get(1).value());
            assertEquals("AUTO", r.get(2).value());
            assertFalse(r.get(3).good());
            assertTrue(r.get(3).detail().startsWith("no message on plant/line1/none"));
            publish("plant/line1/temp", "22", false);
            r = readUntil(d, tags, x -> Double.valueOf(22).equals(x.get(0).value()));
            assertEquals(22.0, r.get(0).value());
        }
    }

    @Test
    void jsonFields() throws Exception {
        publish("plant/press/status", "{\"motor\":{\"speed\":1450,\"on\":true},\"items\":[{\"v\":3}],\"name\":\"P1\"}", true);
        List<TagModel.Tag> tags = List.of(tag("plant/press/status#motor.speed"), tag("plant/press/status#items[0].v"),
                tag("plant/press/status#name"), tag("plant/press/status#nope"));
        try (MqttDriver d = new MqttDriver(settings("json"))) {
            List<Driver.Reading> r = readUntil(d, tags, x -> x.get(0).good());
            assertEquals(1450.0, r.get(0).value());
            assertEquals(3.0, r.get(1).value());
            assertEquals("P1", r.get(2).value());
            assertEquals("no field nope in the message", r.get(3).detail());
        }
    }

    @Test
    void sparkplugBirthAliasesAndDeath() throws Exception {
        List<TagModel.Tag> tags = List.of(tag("G1/N1#Temperature"), tag("G1/N1/D1#Motor/Speed"));
        try (MqttDriver d = new MqttDriver(settings("sparkplug"))) {
            List<Driver.Reading> r = d.read(tags);
            assertTrue(r.get(0).detail().startsWith("waiting for G1/N1 to publish"), r.get(0).detail());
            long t = System.currentTimeMillis();
            publish("spBv1.0/G1/NBIRTH/N1", SparkplugB.encode(t, 0L, List.of(
                    new SparkplugB.Metric("bdSeq", null, t, SparkplugB.INT64, false, 7L),
                    new SparkplugB.Metric("Temperature", 1L, t, SparkplugB.FLOAT, false, 20.0f))), false);
            publish("spBv1.0/G1/DBIRTH/N1/D1", SparkplugB.encode(t, 1L, List.of(
                    new SparkplugB.Metric("Motor/Speed", 2L, t, SparkplugB.INT32, false, -5))), false);
            r = readUntil(d, tags, x -> x.get(0).good() && x.get(1).good());
            assertEquals(20.0, r.get(0).value());
            assertEquals(-5.0, r.get(1).value());

            // data by alias only
            publish("spBv1.0/G1/NDATA/N1", SparkplugB.encode(t + 1000, 2L, List.of(
                    new SparkplugB.Metric(null, 1L, t + 1000, SparkplugB.FLOAT, false, 21.5f))), false);
            publish("spBv1.0/G1/DDATA/N1/D1", SparkplugB.encode(t + 1000, 3L, List.of(
                    new SparkplugB.Metric(null, 2L, t + 1000, SparkplugB.INT32, false, 1450))), false);
            r = readUntil(d, tags, x -> Double.valueOf(1450).equals(x.get(1).value()) && Double.valueOf(21.5).equals(x.get(0).value()));
            assertEquals(21.5, r.get(0).value());
            assertEquals(t + 1000, r.get(0).sourceTime().toEpochMilli());

            publish("spBv1.0/G1/DDEATH/N1/D1", SparkplugB.encode(t + 2000, 4L, List.of()), false);
            r = readUntil(d, tags, x -> !x.get(1).good());
            assertTrue(r.get(1).detail().contains("DDEATH"));
            assertTrue(r.get(0).good(), "the node itself is still online");

            // a death of an older session (other bdSeq) is ignored, the matching one is not
            publish("spBv1.0/G1/NDEATH/N1", SparkplugB.encode(t + 3000, null, List.of(
                    new SparkplugB.Metric("bdSeq", null, null, SparkplugB.INT64, false, 6L))), false);
            Thread.sleep(500);
            assertTrue(d.read(tags).get(0).good());
            publish("spBv1.0/G1/NDEATH/N1", SparkplugB.encode(t + 3000, null, List.of(
                    new SparkplugB.Metric("bdSeq", null, null, SparkplugB.INT64, false, 7L))), false);
            r = readUntil(d, tags, x -> !x.get(0).good());
            assertTrue(r.get(0).detail().contains("NDEATH"));
        }
    }

    @Test
    void rebirthOnlyWhenTurnedOn() throws Exception {
        CompletableFuture<byte[]> ncmd = new CompletableFuture<>();
        MqttClient listener = new MqttClient(url, "test-ncmd", new MemoryPersistence());
        listener.connect();
        listener.subscribe("spBv1.0/G2/NCMD/N2", (topic, m) -> ncmd.complete(m.getPayload()));
        JsonObject s = settings("sparkplug");
        s.addProperty("rebirth", true);
        try (MqttDriver d = new MqttDriver(s)) {
            d.read(List.of(tag("G2/N2#Level")));
            publish("spBv1.0/G2/NDATA/N2", SparkplugB.encode(System.currentTimeMillis(), 5L, List.of(
                    new SparkplugB.Metric(null, 9L, null, SparkplugB.DOUBLE, false, 1.0))), false);
            SparkplugB.Payload p = SparkplugB.decode(ncmd.get(10, TimeUnit.SECONDS));
            assertEquals("Node Control/Rebirth", p.metrics().get(0).name());
            assertEquals(true, p.metrics().get(0).value());
        } finally {
            listener.disconnect();
        }
    }

    @Test
    void codecRoundTrip() {
        byte[] b = SparkplugB.encode(1000L, 3L, List.of(
                new SparkplugB.Metric("i8", 1L, 5L, SparkplugB.INT8, false, -3),
                new SparkplugB.Metric("u32", null, null, SparkplugB.UINT32, false, 4_000_000_000L),
                new SparkplugB.Metric("i64", null, null, SparkplugB.INT64, false, -9_000_000_000L),
                new SparkplugB.Metric("d", null, null, SparkplugB.DOUBLE, false, 1.25),
                new SparkplugB.Metric("s", null, null, SparkplugB.STRING, false, "héllo"),
                new SparkplugB.Metric("n", null, null, SparkplugB.DOUBLE, true, null)));
        SparkplugB.Payload p = SparkplugB.decode(b);
        assertEquals(1000L, p.timestamp());
        assertEquals(3L, p.seq());
        assertEquals(-3.0, p.metrics().get(0).value());
        assertEquals(1L, p.metrics().get(0).alias());
        assertEquals(4.0e9, p.metrics().get(1).value());
        assertEquals(-9.0e9, p.metrics().get(2).value());
        assertEquals(1.25, p.metrics().get(3).value());
        assertEquals("héllo", p.metrics().get(4).value());
        assertTrue(p.metrics().get(5).isNull());
        assertNull(p.metrics().get(5).value());
        assertThrows(IllegalArgumentException.class, () -> SparkplugB.decode(new byte[]{0x12, 0x7f}));
    }

    @Test
    void browseTopicsAndSparkplug() throws Exception {
        publish("site/a/temp", "1.5", true);
        publish("site/a/json", "{\"x\":{\"y\":2}}", true);
        JsonObject s = settings("json");
        s.addProperty("browseTopic", "site/#");
        Map<String, Object> root = MqttDriver.browse(s, null, 100);
        assertTrue(root.get("items").toString().contains("nodeId=site/"), root.toString());
        Map<String, Object> a = MqttDriver.browse(s, "site/a/", 100);
        assertTrue(a.get("items").toString().contains("nodeId=site/a/temp, name=temp, nodeClass=Variable, value=1.5"), a.toString());
        assertTrue(a.get("items").toString().contains("nodeId=site/a/json#"), a.toString());
        Map<String, Object> fields = MqttDriver.browse(s, "site/a/json#", 100);
        assertTrue(fields.get("items").toString().contains("nodeId=site/a/json#x.y"), fields.toString());
    }

    @Test
    void unreachableBroker() {
        JsonObject s = new JsonObject();
        s.addProperty("url", "tcp://127.0.0.1:1");
        try (MqttDriver d = new MqttDriver(s)) {
            Exception e = assertThrows(Exception.class, () -> d.read(List.of(tag("x"))));
            assertTrue(e.getMessage().startsWith("cannot connect to tcp://127.0.0.1:1"), e.getMessage());
        }
    }

    @Test
    void urls() {
        assertEquals("tcp://broker:1883", MqttDriver.normalizeUrl("broker"));
        assertEquals("ssl://broker:8883", MqttDriver.normalizeUrl("mqtts://broker"));
        assertEquals("tcp://10.0.0.5:1884", MqttDriver.normalizeUrl("mqtt://10.0.0.5:1884"));
        assertThrows(IllegalArgumentException.class, () -> MqttDriver.normalizeUrl("http://broker"));
        assertEquals(21.5, MqttDriver.plain(" 21.5 "));
        assertEquals("1e", MqttDriver.plain("1e"));
        assertEquals(false, MqttDriver.plain("FALSE"));
    }
}
