package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonObject;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.qpid.jms.JmsConnectionFactory;

import java.nio.charset.StandardCharsets;

/**
 * JMS over AMQP 1.0 (FR-END-04) with the Apache Qpid JMS client: ActiveMQ Artemis, ActiveMQ Classic, Azure Service
 * Bus, Solace, IBM MQ (AMQP channel), RabbitMQ 4. Address amqp://host:5672, amqps://host:5671 (TLS with the
 * destination's certificates) or failover:(amqp://a:5672,amqp://b:5672)?failover.maxReconnectAttempts=3.
 * <ul>
 *   <li>messageMode machine (default): one text message per machine per batch, JMSXGroupID = the machine id, so a
 *       broker keeps each machine's readings in order on one consumer (the JMS partitioning key);</li>
 *   <li>message: one per reading (also grouped by machine); batch: the whole batch as one message.</li>
 * </ul>
 * Every send waits for the broker to accept the message (persistent by default). Properties: batchId, plantId, edgeId,
 * schemaVersion, count, machineId (+ tagName, messageId per reading); snake_case for format 2.
 */
public class JmsDestination extends Destination {
    private final String plantId;
    private Connection connection;
    private Session session;
    private MessageProducer producer;
    private volatile boolean broken;

    public JmsDestination(BimbaConfig.Destination cfg, String plantId) {
        super(cfg);
        this.plantId = plantId;
    }

    private String address() {
        return cfg.queue == null ? "" : cfg.queue.replace("{plantId}", plantId);
    }

    @Override
    public synchronized void send(byte[] batch) throws Exception {
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        try {
            ensure();
            for (Part p : parts(batch, env, cfg.messageMode)) {
                TextMessage m = session.createTextMessage(new String(p.body(), StandardCharsets.UTF_8));
                m.setStringProperty(key("batchId"), Json.str(env, "batchId"));
                m.setStringProperty(key("plantId"), Json.str(env, "plantId"));
                m.setStringProperty(key("edgeId"), Json.str(env, "edgeId"));
                m.setStringProperty(key("schemaVersion"), version(env));
                m.setIntProperty("count", p.count());
                if (p.machineId() != null) {
                    m.setStringProperty(key("machineId"), p.machineId());
                    m.setStringProperty("JMSXGroupID", p.machineId());
                }
                if (p.tagName() != null) {
                    m.setStringProperty(key("tagName"), p.tagName());
                    m.setStringProperty(key("messageId"), p.messageId());
                }
                producer.send(m, cfg.persistent ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT, 4, 0);
            }
        } catch (JMSException e) {
            close();
            throw new java.io.IOException("JMS: " + reason(e), e);
        }
    }

    private void ensure() throws Exception {
        if (producer != null && !broken) {
            return;
        }
        close();
        Connection c = factory().createConnection();
        try {
            c.setExceptionListener(e -> broken = true);
            Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
            jakarta.jms.Destination d = "topic".equalsIgnoreCase(cfg.jmsDestinationType) ? s.createTopic(address()) : s.createQueue(address());
            MessageProducer p = s.createProducer(d);
            connection = c;
            session = s;
            producer = p;
            broken = false;
        } catch (JMSException e) {
            c.close();
            throw e;
        }
    }

    private JmsConnectionFactory factory() throws Exception {
        JmsConnectionFactory f = new JmsConnectionFactory(cfg.username == null || cfg.username.isBlank() ? null : cfg.username,
                cfg.password, cfg.url.trim());
        f.setConnectTimeout(cfg.timeoutMs);
        f.setSendTimeout(cfg.timeoutMs);
        f.setRequestTimeout(cfg.timeoutMs);
        f.setCloseTimeout(Math.min(cfg.timeoutMs, 5000));
        f.setForceSyncSend(true);                                    // every send waits for the broker's answer
        javax.net.ssl.SSLContext ctx = TlsSupport.context(cfg.tlsCa, cfg.tlsCert, cfg.tlsKey);
        if (ctx != null) {
            f.setSslContext(ctx);
        }
        return f;
    }

    /** Connects, signs in and opens a sender for the queue or topic (the broker checks that it exists). Sends nothing. */
    @Override
    public synchronized String test() throws Exception {
        try (Connection c = factory().createConnection()) {
            Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
            boolean topic = "topic".equalsIgnoreCase(cfg.jmsDestinationType);
            s.createProducer(topic ? s.createTopic(address()) : s.createQueue(address())).close();
            return "Connected to " + cfg.url + (cfg.username == null || cfg.username.isBlank() ? "" : " as " + cfg.username)
                    + "; the " + (topic ? "topic " : "queue ") + address() + " accepts messages. Nothing was sent.";
        } catch (JMSException e) {
            throw new java.io.IOException("JMS: " + reason(e), e);
        }
    }

    /** the broker's own words: Qpid wraps them as the cause of a JMSException */
    static String reason(Throwable e) {
        String m = e.getMessage();
        for (Throwable t = e.getCause(); t != null && t != t.getCause(); t = t.getCause()) {
            if (t.getMessage() != null && (m == null || !m.contains(t.getMessage()))) {
                m = (m == null ? "" : m + ": ") + t.getMessage();
            }
        }
        return m == null ? e.getClass().getSimpleName() : m;
    }

    @Override
    public String endpoint() {
        return cfg.url + " -> " + address();
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (JMSException ignored) {
                // best effort
            }
        }
        connection = null;
        session = null;
        producer = null;
    }
}
