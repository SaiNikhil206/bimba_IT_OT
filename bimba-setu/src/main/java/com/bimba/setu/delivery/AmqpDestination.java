package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonObject;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AMQP 0-9-1 (FR-END-04): RabbitMQ, Amazon MQ for RabbitMQ, CloudAMQP, LavinMQ. Address amqp://host:5672/vhost or
 * amqps://host:5671/vhost (TLS with the destination's certificates; the server's certificate is always checked).
 * Publishes to an exchange ("" = the default exchange, the routing key is then the queue) with a routing key that may
 * hold {plantId}, {machineId}, {tagName} (per machine / per reading), e.g. bimba.{plantId}.{machineId} on a topic
 * exchange. messageMode machine (default, one message per machine per batch), message (per reading) or batch.
 * Publisher confirms: a send returns when the broker has taken every message; one it could not route to any queue
 * (mandatory) fails the send, so nothing silently disappears.
 */
public class AmqpDestination extends Destination {
    private final String plantId;
    private Connection connection;
    private Channel channel;
    private final AtomicReference<String> returned = new AtomicReference<>();

    public AmqpDestination(BimbaConfig.Destination cfg, String plantId) {
        super(cfg);
        this.plantId = plantId;
    }

    private String exchange() {
        return cfg.exchange == null ? "" : cfg.exchange.trim();
    }

    @Override
    public synchronized void send(byte[] batch) throws Exception {
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        try {
            Channel ch = channel();
            returned.set(null);
            for (Part p : parts(batch, env, cfg.messageMode)) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put(key("batchId"), Json.str(env, "batchId"));
                h.put(key("plantId"), Json.str(env, "plantId"));
                h.put(key("edgeId"), Json.str(env, "edgeId"));
                h.put(key("schemaVersion"), version(env));
                h.put("count", p.count());
                if (p.machineId() != null) {
                    h.put(key("machineId"), p.machineId());
                }
                if (p.tagName() != null) {
                    h.put(key("tagName"), p.tagName());
                }
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .contentType("application/json").contentEncoding("utf-8")
                        .deliveryMode(cfg.persistent ? 2 : 1)
                        .messageId(p.messageId() != null ? p.messageId() : Json.str(env, "batchId") + (p.machineId() == null ? "" : "/" + p.machineId()))
                        .timestamp(new Date()).appId("bimba-setu").headers(h).build();
                ch.basicPublish(exchange(), routingKey(p.first(), env), true, props, p.body());
            }
            ch.waitForConfirmsOrDie(cfg.timeoutMs);
        } catch (java.util.concurrent.TimeoutException e) {
            close();
            throw new java.io.IOException("AMQP: the broker did not confirm within " + cfg.timeoutMs + " ms", e);
        } catch (java.io.IOException | com.rabbitmq.client.AlreadyClosedException e) {
            close();
            throw new java.io.IOException("AMQP: " + reason(e), e);
        }
        String r = returned.get();
        if (r != null) {
            throw new java.io.IOException("AMQP: " + r);           // not stored anywhere: kept and tried again
        }
    }

    /** the routing key for a message: placeholders from its (first) reading; values cannot add levels (. * # -> _) */
    String routingKey(JsonObject first, JsonObject env) {
        String k = cfg.routingKey == null ? "" : cfg.routingKey.replace("{plantId}", plantId);
        return Template.text(k, first, env, v -> v.replace('.', '_').replace('*', '_').replace('#', '_'));
    }

    private Channel channel() throws Exception {
        if (channel != null && channel.isOpen()) {
            return channel;
        }
        close();
        Connection c = factory().newConnection("bimba-" + cfg.name);
        try {
            Channel ch = c.createChannel();
            ch.confirmSelect();
            ch.addReturnListener(r -> returned.compareAndSet(null, "no queue is bound to exchange \""
                    + (r.getExchange().isEmpty() ? "(default)" : r.getExchange()) + "\" for routing key \"" + r.getRoutingKey()
                    + "\" (" + r.getReplyText() + ")"));
            connection = c;
            channel = ch;
            return ch;
        } catch (Exception e) {
            c.abort();
            throw e;
        }
    }

    private ConnectionFactory factory() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setUri(cfg.url.trim());
        if (cfg.username != null && !cfg.username.isBlank()) {
            f.setUsername(cfg.username);
            f.setPassword(cfg.password == null ? "" : cfg.password);
        }
        if (cfg.url.trim().toLowerCase(java.util.Locale.ROOT).startsWith("amqps://")) {
            // setUri would trust any certificate for amqps: use the real checks (and the destination's own authorities)
            javax.net.ssl.SSLContext ctx = TlsSupport.context(cfg.tlsCa, cfg.tlsCert, cfg.tlsKey);
            f.useSslProtocol(ctx != null ? ctx : javax.net.ssl.SSLContext.getDefault());
            f.enableHostnameVerification();
        }
        f.setConnectionTimeout(cfg.timeoutMs);
        f.setHandshakeTimeout(cfg.timeoutMs);
        f.setShutdownTimeout(Math.min(cfg.timeoutMs, 5000));
        f.setAutomaticRecoveryEnabled(false);                         // the route retries with its backoff
        f.setTopologyRecoveryEnabled(false);
        return f;
    }

    /** Connects and signs in; checks that the exchange (or, for the default exchange, the queue) exists. Sends nothing. */
    @Override
    public synchronized String test() throws Exception {
        Connection c = null;
        try {
            c = factory().newConnection("bimba-" + cfg.name + "-test");
            Channel ch = c.createChannel();
            String what;
            if (!exchange().isEmpty()) {
                ch.exchangeDeclarePassive(exchange());
                what = "exchange " + exchange() + " is there";
            } else if (cfg.routingKey != null && !cfg.routingKey.replace("{plantId}", "").contains("{")) {
                AMQP.Queue.DeclareOk q = ch.queueDeclarePassive(cfg.routingKey.replace("{plantId}", plantId));
                what = "queue " + q.getQueue() + " is there (" + q.getConsumerCount() + " consumer(s))";
            } else {
                what = "the default exchange is used";
            }
            return "Connected to " + c.getAddress().getHostName() + ":" + c.getPort() + " (virtual host "
                    + factory().getVirtualHost() + "); " + what + ". Nothing was sent.";
        } catch (java.io.IOException e) {
            throw new java.io.IOException("AMQP: " + reason(e), e);
        } finally {
            if (c != null) {
                c.abort();
            }
        }
    }

    /** the broker's reply text (NOT_FOUND - no exchange 'x' ...) instead of the client's wrapper */
    static String reason(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof com.rabbitmq.client.ShutdownSignalException s && s.getReason() instanceof com.rabbitmq.client.Method m) {
                if (m instanceof AMQP.Channel.Close cc) {
                    return cc.getReplyText();
                }
                if (m instanceof AMQP.Connection.Close cc) {
                    return cc.getReplyText();
                }
            }
            if (t.getMessage() != null && t.getCause() == null) {
                return t.getMessage();
            }
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override
    public String endpoint() {
        String u = cfg.url == null ? "" : cfg.url.replaceAll("//[^/@]*@", "//");    // never a password from the address
        return u + " -> " + (exchange().isEmpty() ? "(default)" : exchange()) + " / " + (cfg.routingKey == null ? "" : cfg.routingKey);
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.abort(1000);
        }
        connection = null;
        channel = null;
    }
}
