package com.bimba.ignition.delivery;

import com.bimba.ignition.Json;
import com.bimba.ignition.config.BimbaConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Apache Kafka producer (also Confluent Cloud, AWS MSK, Azure Event Hubs' Kafka endpoint, Redpanda).
 *
 * kafkaMode "batch"   (default): one record per plugin batch, key = edgeId, value = batch envelope JSON
 * kafkaMode "message"          : one record per tag value, key = machineId/tagName (per-tag ordering
 *                                within a partition), value = canonical message JSON
 * Headers: batchId, plantId, edgeId, schemaVersion (+ messageId in message mode).
 * Delivery = acks=all, idempotent producer; send() returns only after every record is acknowledged.
 * Extra client settings (security.protocol, sasl.mechanism, sasl.jaas.config, ssl.*) go in "kafka".
 */
public class KafkaDestination extends Destination {
    private final String topic;
    private KafkaProducer<String, byte[]> producer;

    public KafkaDestination(BimbaConfig.Destination cfg, String plantId) {
        super(cfg);
        this.topic = cfg.topic.replace("{plantId}", plantId);
    }

    @Override
    public synchronized void send(byte[] batch) throws Exception {
        ensureProducer();
        String json = new String(batch, StandardCharsets.UTF_8);
        JsonObject envelope = Json.GSON.fromJson(json, JsonObject.class);
        List<Future<RecordMetadata>> acks = new ArrayList<>();
        if ("message".equalsIgnoreCase(cfg.kafkaMode)) {
            for (JsonElement e : envelope.getAsJsonArray("messages")) {
                JsonObject m = e.getAsJsonObject();
                String key = m.get("machineId").getAsString() + "/" + m.get("tagName").getAsString();
                ProducerRecord<String, byte[]> rec = new ProducerRecord<>(topic, key,
                        Json.GSON.toJson(m).getBytes(StandardCharsets.UTF_8));
                headers(rec, envelope);
                rec.headers().add(new RecordHeader("messageId", bytes(m.get("messageId").getAsString())));
                acks.add(producer.send(rec));
            }
        } else {
            ProducerRecord<String, byte[]> rec = new ProducerRecord<>(topic, str(envelope, "edgeId"), batch);
            headers(rec, envelope);
            rec.headers().add(new RecordHeader("count", bytes(String.valueOf(envelope.get("count").getAsInt()))));
            acks.add(producer.send(rec));
        }
        producer.flush();
        try {
            for (Future<RecordMetadata> f : acks) {
                f.get(cfg.timeoutMs + 5000L, TimeUnit.MILLISECONDS);
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RecordTooLargeException) {
                throw new RejectedException("Kafka: " + e.getCause().getMessage() + " - lower batch.maxMessages");
            }
            // broker down / auth / unknown topic: rebuild the producer on the next attempt
            closeProducer();
            throw new java.io.IOException("Kafka: " + e.getCause(), e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            closeProducer();
            throw new java.io.IOException("Kafka: no acknowledgement within " + (cfg.timeoutMs + 5000) + " ms");
        }
        // With the idempotent producer, a batch retried after a lost ack is written once per producer
        // session; across sessions, receivers de-duplicate on messageId / batchId.
    }

    private void ensureProducer() {
        if (producer != null) {
            return;
        }
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.bootstrapServers);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, cfg.clientId != null ? cfg.clientId : "bimba-" + cfg.name);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");
        p.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(cfg.timeoutMs));      // metadata wait when broker down
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(cfg.timeoutMs));
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(cfg.timeoutMs));
        p.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, String.valueOf(5 * 1024 * 1024));
        if (cfg.kafka != null) {
            p.putAll(cfg.kafka); // security.protocol, sasl.*, ssl.*, overrides
        }
        // Kafka resolves serializer/login classes through the context class loader; inside an
        // Ignition module that must be the module's own loader.
        Thread t = Thread.currentThread();
        ClassLoader previous = t.getContextClassLoader();
        t.setContextClassLoader(KafkaDestination.class.getClassLoader());
        try {
            producer = new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer());
        } finally {
            t.setContextClassLoader(previous);
        }
    }

    private static void headers(ProducerRecord<String, byte[]> rec, JsonObject env) {
        for (String k : new String[]{"batchId", "plantId", "edgeId", "schemaVersion"}) {
            rec.headers().add(new RecordHeader(k, bytes(str(env, k))));
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private void closeProducer() {
        if (producer != null) {
            try {
                producer.close(java.time.Duration.ofSeconds(2));
            } catch (Exception ignored) {
                // best effort
            }
            producer = null;
        }
    }

    @Override
    public String endpoint() {
        return cfg.bootstrapServers + " -> " + topic;
    }

    @Override
    public synchronized void close() {
        closeProducer();
    }
}
