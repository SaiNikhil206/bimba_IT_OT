package com.bimba.gateway.delivery;

import com.bimba.gateway.Json;
import com.bimba.gateway.config.BimbaConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
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
                        Json.GSON.toJson(layout(m, envelope)).getBytes(StandardCharsets.UTF_8));
                headers(rec, envelope);
                rec.headers().add(new RecordHeader("messageId", bytes(m.get("messageId").getAsString())));
                acks.add(producer.send(rec));
            }
        } else {
            ProducerRecord<String, byte[]> rec = new ProducerRecord<>(topic, str(envelope, "edgeId"), mappedEnvelope(batch));
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
        Properties p = clientProperties();
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");
        p.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(cfg.timeoutMs));      // metadata wait when broker down
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(cfg.timeoutMs));
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(cfg.timeoutMs));
        p.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, String.valueOf(5 * 1024 * 1024));
        if (cfg.kafka != null) {
            p.putAll(cfg.kafka); // ssl.*, other client settings, overrides
        }
        producer = withKafkaLoader(() -> new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer()));
    }

    /** address, client id and security (TLS / SASL sign-in): what the producer and the connection test share */
    private Properties clientProperties() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.bootstrapServers);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, cfg.clientId != null ? cfg.clientId : "bimba-" + cfg.name);
        if (cfg.securityProtocol != null && !cfg.securityProtocol.isBlank()) {
            p.put("security.protocol", cfg.securityProtocol);
            if (cfg.securityProtocol.startsWith("SASL") && cfg.username != null && !cfg.username.isBlank()) {
                String mech = cfg.saslMechanism == null || cfg.saslMechanism.isBlank() ? "PLAIN" : cfg.saslMechanism;
                String module = "PLAIN".equals(mech) ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                        : "org.apache.kafka.common.security.scram.ScramLoginModule";
                p.put("sasl.mechanism", mech);
                p.put("sasl.jaas.config", module + " required username=\"" + jaas(cfg.username) + "\" password=\""
                        + jaas(cfg.password == null ? "" : cfg.password) + "\";");
            }
        }
        // own certificates as PEM text (Kafka 2.7+): authorities to trust (replace Kafka's defaults) and a client certificate
        if (TlsSupport.has(cfg.tlsCa)) {
            p.put("ssl.truststore.type", "PEM");
            p.put("ssl.truststore.certificates", cfg.tlsCa.trim());
        }
        if (TlsSupport.has(cfg.tlsCert) && TlsSupport.has(cfg.tlsKey)) {
            p.put("ssl.keystore.type", "PEM");
            p.put("ssl.keystore.certificate.chain", cfg.tlsCert.trim());
            p.put("ssl.keystore.key", TlsSupport.pkcs8Pem(cfg.tlsKey));
        }
        return p;
    }

    /** Kafka resolves serializer/login classes through the context class loader: use the one that loaded Kafka. */
    private static <T> T withKafkaLoader(java.util.function.Supplier<T> make) {
        Thread t = Thread.currentThread();
        ClassLoader previous = t.getContextClassLoader();
        t.setContextClassLoader(KafkaDestination.class.getClassLoader());
        try {
            return make.get();
        } finally {
            t.setContextClassLoader(previous);
        }
    }

    /**
     * Connects, signs in and describes the topic with an admin client: nothing is produced, and unlike a producer's
     * metadata request this never makes a broker with automatic topic creation create the topic.
     */
    @Override
    public String test() throws Exception {
        Properties p = clientProperties();
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(cfg.timeoutMs));
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, String.valueOf(cfg.timeoutMs));
        if (cfg.kafka != null) {
            cfg.kafka.forEach((k, v) -> {
                if (k.startsWith("ssl.") || k.startsWith("sasl.") || k.equals("security.protocol")) {
                    p.put(k, v);
                }
            });
        }
        p.put(AdminClientConfig.CLIENT_ID_CONFIG, p.getProperty(AdminClientConfig.CLIENT_ID_CONFIG) + "-test");
        try (Admin admin = withKafkaLoader(() -> Admin.create(p))) {
            TopicDescription t = admin.describeTopics(List.of(topic)).allTopicNames()
                    .get(cfg.timeoutMs + 2000L, TimeUnit.MILLISECONDS).get(topic);
            return "Connected to " + cfg.bootstrapServers + "; topic " + topic + " has " + t.partitions().size() + " partition(s).";
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                throw new java.io.IOException("Connected, but topic " + topic + " does not exist. Create it first"
                        + " (or turn on automatic topic creation on the cluster).");
            }
            throw new java.io.IOException("Kafka: " + e.getCause().getMessage(), e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new java.io.IOException("Kafka: no answer from " + cfg.bootstrapServers + " within " + (cfg.timeoutMs + 2000) + " ms", e);
        }
    }

    private static String jaas(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
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
