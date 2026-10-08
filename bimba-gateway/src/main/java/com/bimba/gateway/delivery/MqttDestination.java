package com.bimba.gateway.delivery;

import com.bimba.gateway.Json;
import com.bimba.gateway.config.BimbaConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * mqttMode "batch" (default): each batch envelope as one MQTT message on the topic.
 * mqttMode "message": each reading as its own message, on a topic that may hold {machineId}, {tagName}, {path}, ...
 * (e.g. bimba/{plantId}/{machineId}/{tagName}: a plain-JSON Unified Namespace); the messages of a batch are published in
 * parallel (up to maxInflight unacknowledged). retain: the broker keeps the last message of each topic for new
 * subscribers. QoS 1 by default; "accepted" means the broker acknowledged every message. TLS (ssl://, wss://) with the
 * destination's own authorities and client certificate.
 */
public class MqttDestination extends Destination {
    private final String topic;
    private MqttAsyncClient client;

    public MqttDestination(BimbaConfig.Destination cfg, String plantId) {
        super(cfg);
        this.topic = cfg.topic.replace("{plantId}", plantId);
    }

    @Override
    public synchronized void send(byte[] batch) throws Exception {
        ensureConnected();
        try {
            List<IMqttDeliveryToken> sent = new ArrayList<>();
            if ("message".equalsIgnoreCase(cfg.mqttMode)) {
                JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
                for (JsonElement e : env.getAsJsonArray("messages")) {
                    JsonObject m = e.getAsJsonObject();
                    String t = Template.text(topic, m, env, MqttDestination::segment);
                    sent.add(publish(t, Json.GSON.toJson(layout(m, env)).getBytes(StandardCharsets.UTF_8), sent));
                }
            } else {
                sent.add(publish(topic, mappedEnvelope(batch), sent));
            }
            for (IMqttDeliveryToken t : sent) {
                t.waitForCompletion(cfg.timeoutMs);      // throws when the broker did not acknowledge in time
            }
        } catch (Exception e) {
            disconnectQuietly();
            throw e;
        }
    }

    /** publishes without waiting; when maxInflight messages are open, waits for the oldest first */
    private IMqttDeliveryToken publish(String t, byte[] payload, List<IMqttDeliveryToken> open) throws Exception {
        int window = Math.max(1, cfg.maxInflight);
        if (open.size() >= window) {
            open.get(open.size() - window).waitForCompletion(cfg.timeoutMs);
        }
        MqttMessage msg = new MqttMessage(payload);
        msg.setQos(cfg.qos);
        msg.setRetained(cfg.retain);
        return client.publish(t, msg);
    }

    /** a value inside one topic level: no level separator or wildcards */
    private static String segment(String v) {
        return v.replace('/', '_').replace('+', '_').replace('#', '_');
    }

    private void ensureConnected() throws Exception {
        if (client != null && client.isConnected()) {
            return;
        }
        disconnectQuietly();
        client = connect(cfg.clientId != null ? cfg.clientId : "bimba-" + cfg.name);
    }

    /** Connects with its own client id, so the running destination's session is not taken over. */
    @Override
    public String test() throws Exception {
        String id = (cfg.clientId != null ? cfg.clientId : "bimba-" + cfg.name) + "-test";
        MqttAsyncClient c = connect(id);
        try {
            return "Connected to " + cfg.brokerUrl + (cfg.username != null ? " as " + cfg.username : "")
                    + (TlsSupport.has(cfg.tlsCert) ? " with the client certificate" : "") + ".";
        } finally {
            try {
                c.disconnect(1000).waitForCompletion(2000);
            } finally {
                c.close();
            }
        }
    }

    private MqttAsyncClient connect(String id) throws Exception {
        MqttAsyncClient c = new MqttAsyncClient(cfg.brokerUrl, id, new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(false);
        opts.setConnectionTimeout(Math.max(1, cfg.timeoutMs / 1000));
        opts.setKeepAliveInterval(30);
        opts.setMaxInflight(Math.max(10, cfg.maxInflight));
        if (cfg.username != null) {
            opts.setUserName(cfg.username);
            opts.setPassword(cfg.password == null ? new char[0] : cfg.password.toCharArray());
        }
        javax.net.ssl.SSLContext ctx = TlsSupport.context(cfg.tlsCa, cfg.tlsCert, cfg.tlsKey);
        if (ctx != null) {
            opts.setSocketFactory(ctx.getSocketFactory());
        }
        try {
            c.connect(opts).waitForCompletion(cfg.timeoutMs + 1000L);
        } catch (Exception e) {
            c.close();
            throw e;
        }
        return c;
    }

    private void disconnectQuietly() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect(1000).waitForCompletion(2000);
            }
            client.close();
        } catch (Exception ignored) {
            // best effort
        }
        client = null;
    }

    @Override
    public String endpoint() {
        return cfg.brokerUrl + " -> " + topic;
    }

    @Override
    public synchronized void close() {
        disconnectQuietly();
    }
}
