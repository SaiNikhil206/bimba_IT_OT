package com.bimba.ignition.delivery;

import com.bimba.ignition.config.BimbaConfig;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Publishes each batch envelope as one MQTT message (QoS 1 by default). "Accepted" means the
 * broker acknowledged it; the broker then holds it for the end system's persistent subscription.
 */
public class MqttDestination extends Destination {
    private final String topic;
    private MqttClient client;

    public MqttDestination(BimbaConfig.Destination cfg, String plantId) {
        super(cfg);
        this.topic = cfg.topic.replace("{plantId}", plantId);
    }

    @Override
    public synchronized void send(byte[] batch) throws Exception {
        ensureConnected();
        MqttMessage msg = new MqttMessage(batch);
        msg.setQos(cfg.qos);
        msg.setRetained(false);
        try {
            client.publish(topic, msg); // blocks until PUBACK (QoS 1) or timeToWait
        } catch (Exception e) {
            disconnectQuietly();
            throw e;
        }
    }

    private void ensureConnected() throws Exception {
        if (client != null && client.isConnected()) {
            return;
        }
        disconnectQuietly();
        String id = cfg.clientId != null ? cfg.clientId : "bimba-" + cfg.name;
        client = new MqttClient(cfg.brokerUrl, id, new MemoryPersistence());
        client.setTimeToWait(cfg.timeoutMs);
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(false);
        opts.setConnectionTimeout(Math.max(1, cfg.timeoutMs / 1000));
        opts.setKeepAliveInterval(30);
        if (cfg.username != null) {
            opts.setUserName(cfg.username);
            opts.setPassword(cfg.password == null ? new char[0] : cfg.password.toCharArray());
        }
        client.connect(opts);
    }

    private void disconnectQuietly() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect(1000);
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
