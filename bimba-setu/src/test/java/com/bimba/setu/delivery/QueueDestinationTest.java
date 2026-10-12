package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonObject;
import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-END-04: JMS over AMQP 1.0 (against an embedded ActiveMQ Artemis) and AMQP 0-9-1 routing, keyed by machine */
class QueueDestinationTest {

    static byte[] batch() {
        return Json.GSON.toJson(Json.GSON.fromJson("""
                {"schemaVersion":"1.3","batchId":"b-7","plantId":"P1","edgeId":"gw","createdAt":"2026-10-09T08:00:00Z","count":3,"messages":[
                 {"messageId":"m1","plantId":"P1","machineId":"PUMP.1","tagName":"Flow","tagValue":1.5,"dataType":"number","unit":"","quality":"GOOD","timestamp":"2026-10-09T08:00:00Z"},
                 {"messageId":"m2","plantId":"P1","machineId":"TANK-2","tagName":"Level","tagValue":40,"dataType":"number","unit":"%","quality":"GOOD","timestamp":"2026-10-09T08:00:00Z"},
                 {"messageId":"m3","plantId":"P1","machineId":"PUMP.1","tagName":"Mode","tagValue":"auto","dataType":"string","unit":"","quality":"GOOD","timestamp":"2026-10-09T08:00:01Z"}]}""",
                JsonObject.class)).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void jmsSendsOneMessagePerMachineGroupedByIt(@TempDir File dir) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        EmbeddedActiveMQ broker = new EmbeddedActiveMQ();
        broker.setConfiguration(new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setJournalDirectory(new File(dir, "journal").getPath()).setBindingsDirectory(new File(dir, "bindings").getPath())
                .setPagingDirectory(new File(dir, "paging").getPath()).setLargeMessagesDirectory(new File(dir, "large").getPath())
                .addAcceptorConfiguration("amqp", "tcp://127.0.0.1:" + port + "?protocols=AMQP"));
        broker.start();
        try {
            BimbaConfig.Destination c = new BimbaConfig.Destination();
            c.name = "jms";
            c.transport = "jms";
            c.url = "amqp://127.0.0.1:" + port;
            c.queue = "bimba.{plantId}";
            c.timeoutMs = 5000;
            try (JmsDestination d = new JmsDestination(c, "P1")) {
                d.send(batch());
                assertTrue(d.test().contains("the queue bimba.P1 accepts messages"));
            }
            JmsConnectionFactory f = new JmsConnectionFactory("amqp://127.0.0.1:" + port);
            try (Connection con = f.createConnection()) {
                con.start();
                Session s = con.createSession(false, Session.AUTO_ACKNOWLEDGE);
                MessageConsumer consumer = s.createConsumer(s.createQueue("bimba.P1"));
                Map<String, JsonObject> byGroup = new HashMap<>();
                for (int i = 0; i < 2; i++) {
                    TextMessage m = (TextMessage) consumer.receive(5000);
                    assertNotNull(m, "message " + i);
                    assertEquals("b-7", m.getStringProperty("batchId"));
                    assertEquals(m.getStringProperty("machineId"), m.getStringProperty("JMSXGroupID"));
                    byGroup.put(m.getStringProperty("JMSXGroupID"), Json.GSON.fromJson(m.getText(), JsonObject.class));
                }
                assertEquals(2, byGroup.get("PUMP.1").getAsJsonArray("messages").size(), "a machine's readings travel together");
                assertEquals(1, byGroup.get("TANK-2").getAsJsonArray("messages").size());
                assertEquals(null, consumer.receive(300), "nothing more");
            }
        } finally {
            broker.stop();
        }
    }

    @Test
    void amqpRoutingKeysPerMachine() {
        BimbaConfig.Destination c = new BimbaConfig.Destination();
        c.name = "rmq";
        c.transport = "amqp";
        c.url = "amqp://guest:guest@localhost/%2F";
        c.exchange = "amq.topic";
        c.routingKey = "bimba.{plantId}.{machineId}";
        AmqpDestination d = new AmqpDestination(c, "P1");
        JsonObject env = Json.GSON.fromJson(new String(batch(), StandardCharsets.UTF_8), JsonObject.class);
        List<Destination.Part> parts = d.parts(batch(), env, "machine");
        assertEquals(2, parts.size());
        assertEquals("bimba.P1.PUMP_1", d.routingKey(parts.get(0).first(), env), "a dot in a machine id adds no routing level");
        assertEquals("bimba.P1.TANK-2", d.routingKey(parts.get(1).first(), env));
        assertEquals(3, d.parts(batch(), env, "message").size());
        assertEquals(1, d.parts(batch(), env, "batch").size());
        assertTrue(!d.endpoint().contains("guest:guest"), "no password from the address in the status");
    }
}
