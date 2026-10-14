package com.bimba.setu.collect;

import com.bimba.setu.config.TagModel;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalOut;
import com.ghgande.j2mod.modbus.procimg.SimpleInputRegister;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Machine commands: engineering value -> device value, Modbus (in-process slave), MQTT (in-memory broker), OPC UA types. */
class WriterTest {
    private static ModbusSlave slave;
    private static SimpleProcessImage image;
    private static int modbusPort;
    private static Server broker;
    private static String mqttUrl;
    private static MqttClient device;

    @BeforeAll
    static void start() throws Exception {
        modbusPort = freePort();
        image = new SimpleProcessImage(1);
        for (int i = 0; i < 10; i++) {
            image.addRegister(new SimpleRegister(0));
            image.addDigitalOut(new SimpleDigitalOut(false));
            image.addInputRegister(new SimpleInputRegister(0));
        }
        slave = ModbusSlaveFactory.createTCPSlave(InetAddress.getLoopbackAddress(), modbusPort, 5, false);
        slave.addProcessImage(1, image);
        slave.open();

        int port = freePort();
        Properties p = new Properties();
        p.setProperty("host", "127.0.0.1");
        p.setProperty("port", String.valueOf(port));
        p.setProperty("allow_anonymous", "true");
        p.setProperty("persistence_enabled", "false");
        broker = new Server();
        broker.startServer(new MemoryConfig(p));
        mqttUrl = "tcp://127.0.0.1:" + port;
        device = new MqttClient(mqttUrl, "test-device", new MemoryPersistence());
        device.connect();
    }

    @AfterAll
    static void stop() throws Exception {
        ModbusSlaveFactory.close(slave);
        device.disconnect();
        broker.stopServer();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static TagModel.Tag tag(TagModel.Kind kind, String address, String dataType, String unit, double rawLow, double rawHigh,
                                    double scaledLow, double scaledHigh, boolean scaled, String sourceUnit) {
        return new TagModel.Tag("M", "t", "M/t", kind, "s1", address, dataType, unit, rawLow, rawHigh, scaledLow, scaledHigh, scaled,
                sourceUnit, null, null);
    }

    private static TagModel.Tag plain(TagModel.Kind kind, String address) {
        return tag(kind, address, null, null, 0, 1, 0, 1, false, null);
    }

    private static JsonObject modbus() {
        JsonObject s = new JsonObject();
        s.addProperty("host", "127.0.0.1");
        s.addProperty("port", modbusPort);
        s.addProperty("unitId", 1);
        return s;
    }

    private static JsonObject mqtt(String payload) {
        JsonObject s = new JsonObject();
        s.addProperty("url", mqttUrl);
        s.addProperty("payload", payload);
        return s;
    }

    // ------------------------------------------------------------------ conversion

    @Test
    void engineeringValueGoesBackToTheDevicesUnitAndRange() {
        // scaled: counts 0..1000 = 0..100 %
        TagModel.Tag level = tag(TagModel.Kind.MODBUS, "HR1", null, "%", 0, 1000, 0, 100, true, null);
        assertEquals(425.0, (double) Writer.toDevice(level, 42.5), 1e-9);
        // converted: the tag shows degC, the device works in degF
        TagModel.Tag temp = tag(TagModel.Kind.OPCUA, "ns=2;s=T", null, "degC", 0, 1, 0, 1, false, "degF");
        assertEquals(212.0, (double) Writer.toDevice(temp, 100.0), 1e-9);
        assertEquals(true, Writer.toDevice(level, true), "booleans stay");
    }

    @Test
    void sameAllowsFloatRoundingOnly() {
        assertTrue(Writer.same(0.1, (double) 0.1f));
        assertFalse(Writer.same(12.4, 12.0));
        assertTrue(Writer.same(true, 1.0));
        assertTrue(Writer.same(42.0, "42"));
        assertFalse(Writer.same(1.0, null));
    }

    @Test
    void opcUaValuesTakeTheNodesType() throws Exception {
        assertEquals((short) 42, OpcUaDriver.typed((short) 7, 41.6));
        assertEquals(UShort.valueOf(65535), OpcUaDriver.typed(UShort.valueOf(1), 65535.0));
        assertEquals(1.5f, OpcUaDriver.typed(2.0f, 1.5));
        assertEquals(true, OpcUaDriver.typed(false, 1.0));
        assertEquals(LocalizedText.english("AUTO"), OpcUaDriver.typed(LocalizedText.english("MAN"), "AUTO"));
        assertEquals(21.5, OpcUaDriver.typed(null, 21.5));
        Exception e = assertThrows(Exception.class, () -> OpcUaDriver.typed((short) 1, 40000.0));
        assertTrue(e.getMessage().contains("Int16"), e.getMessage());
        assertThrows(Exception.class, () -> OpcUaDriver.typed(UShort.valueOf(1), -1.0));
        assertThrows(Exception.class, () -> OpcUaDriver.typed(1.0, "text"));
    }

    // ------------------------------------------------------------------ Modbus

    @Test
    void modbusHoldingRegisterScaledReadBack() throws Exception {
        image.getRegister(1).setValue(100);                      // HR2, 1-based
        TagModel.Tag t = tag(TagModel.Kind.MODBUS, "HR2", null, "%", 0, 1000, 0, 100, true, null);
        Map<String, Object> r = Writer.write("modbus", modbus(), t, new JsonObject(), new JsonPrimitive(42.5));
        assertEquals(425, image.getRegister(1).getValue());
        assertEquals(true, r.get("verified"));
        assertEquals(10.0, r.get("before"));
        assertEquals(42.5, r.get("after"));
        assertEquals(425.0, r.get("deviceValue"));
    }

    @Test
    void modbusNegativeValuesAndCoils() throws Exception {
        Map<String, Object> r = Writer.write("modbus", modbus(), plain(TagModel.Kind.MODBUS, "HR3"), new JsonObject(), new JsonPrimitive(-5));
        assertEquals(65531, image.getRegister(2).getValue(), "two's complement in the register");
        assertEquals(-5.0, r.get("after"));
        assertEquals(true, r.get("verified"));

        r = Writer.write("modbus", modbus(), plain(TagModel.Kind.MODBUS, "C4"), new JsonObject(), new JsonPrimitive(true));
        assertTrue(image.getDigitalOut(3).isSet());
        assertEquals(false, r.get("before"));
        assertEquals(true, r.get("after"));
    }

    @Test
    void modbusRefusesWhatCannotBeWritten() {
        Exception ir = assertThrows(Exception.class, () -> Writer.write("modbus", modbus(), plain(TagModel.Kind.MODBUS, "IR1"),
                new JsonObject(), new JsonPrimitive(1)));
        assertTrue(ir.getMessage().contains("read only"), ir.getMessage());
        Exception big = assertThrows(Exception.class, () -> Writer.write("modbus", modbus(), plain(TagModel.Kind.MODBUS, "HR1"),
                new JsonObject(), new JsonPrimitive(70000)));
        assertTrue(big.getMessage().contains("16-bit"), big.getMessage());
        assertThrows(Exception.class, () -> Writer.write("dnp3", new JsonObject(), plain(TagModel.Kind.DNP3, "AI0"), new JsonObject(),
                new JsonPrimitive(1)), "read-only protocols take no commands");
    }

    // ------------------------------------------------------------------ MQTT

    @Test
    void mqttCommandTopicConfirmedByTheMachine() throws Exception {
        // the "machine": takes the setpoint on plant/sp/set and reports it on plant/sp
        device.subscribe("plant/sp/set", (topic, msg) -> new Thread(() -> {
            try {
                MqttMessage m = new MqttMessage(msg.getPayload());
                m.setRetained(true);
                device.publish("plant/sp", m);
            } catch (Exception ignored) {
                // test fails on the assertion
            }
        }).start());
        JsonObject tagJson = new JsonObject();
        tagJson.addProperty("writeAddress", "plant/sp/set");
        Map<String, Object> r = Writer.write("mqtt", mqtt("value"), plain(TagModel.Kind.MQTT, "plant/sp"), tagJson, new JsonPrimitive(55));
        assertEquals(true, r.get("verified"));
        assertEquals(55.0, r.get("after"));
        device.unsubscribe("plant/sp/set");
    }

    @Test
    void mqttCommandOnTheReadTopicCannotBeConfirmed() throws Exception {
        CompletableFuture<String> got = new CompletableFuture<>();
        device.subscribe("plant/mode", (topic, msg) -> got.complete(new String(msg.getPayload(), StandardCharsets.UTF_8)));
        Map<String, Object> r = Writer.write("mqtt", mqtt("value"), plain(TagModel.Kind.MQTT, "plant/mode"), new JsonObject(),
                new JsonPrimitive("AUTO"));
        assertEquals("AUTO", got.get(5, TimeUnit.SECONDS));
        assertNull(r.get("verified"));
        assertTrue(String.valueOf(r.get("detail")).contains("cannot be confirmed"));
        device.unsubscribe("plant/mode");
    }

    @Test
    void sparkplugDeviceCommand() throws Exception {
        CompletableFuture<SparkplugB.Payload> got = new CompletableFuture<>();
        device.subscribe("spBv1.0/G1/DCMD/N1/D1", (topic, msg) -> {
            SparkplugB.Payload p = SparkplugB.decode(msg.getPayload());
            got.complete(p);
            new Thread(() -> {                                   // the device reports the new speed
                try {
                    long now = System.currentTimeMillis();
                    device.publish("spBv1.0/G1/DDATA/N1/D1", new MqttMessage(SparkplugB.encode(now, 1L, java.util.List.of(
                            new SparkplugB.Metric("Motor/Speed", null, now, SparkplugB.INT32, false, 1500L)))));
                } catch (Exception ignored) {
                    // test fails on the assertion
                }
            }).start();
        });
        TagModel.Tag t = tag(TagModel.Kind.MQTT, "G1/N1/D1#Motor/Speed", "int32", "rpm", 0, 1, 0, 1, false, null);
        Map<String, Object> r = Writer.write("mqtt", mqtt("sparkplug"), t, new JsonObject(), new JsonPrimitive(1500));
        SparkplugB.Metric m = got.get(5, TimeUnit.SECONDS).metrics().get(0);
        assertEquals("Motor/Speed", m.name());
        assertEquals(SparkplugB.INT32, m.datatype());
        assertEquals(1500L, ((Number) m.value()).longValue());
        assertEquals(true, r.get("verified"));
        device.unsubscribe("spBv1.0/G1/DCMD/N1/D1");
    }

    @Test
    void jsonCommandsAndText() throws Exception {
        assertEquals("{\"motor\":{\"speed\":1450}}", MqttDriver.jsonCommand("motor.speed", 1450.0));
        assertEquals("{\"on\":true}", MqttDriver.jsonCommand("on", true));
        assertEquals("21.5", MqttDriver.jsonCommand("", 21.5));
        assertThrows(Exception.class, () -> MqttDriver.jsonCommand("items[0].v", 1.0));
        assertEquals("42", MqttDriver.text(42.0));
        assertEquals("21.5", MqttDriver.text(21.5));
        assertEquals(SparkplugB.BOOLEAN, MqttDriver.sparkplugType(null, true));
        assertEquals(SparkplugB.DOUBLE, MqttDriver.sparkplugType("", 1.0));
        assertEquals(SparkplugB.INT16, MqttDriver.sparkplugType("Int16", 1.0));
    }
}
