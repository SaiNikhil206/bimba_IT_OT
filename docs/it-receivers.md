# Kafka and other IT receivers

Besides the main path (Boomi Event Streams as primary, BIMBA Ingest as failover), the
BIMBA IT/OT Agent can **fan out** a copy of every batch to any number of extra
receivers. Give a destination `"role": "mirror"`:

```
                              ┌─ route "main":  boomi-eventstreams ──(down)──► bimba-ingest (primary → failover)
BIMBA Setu ─► batcher ─────┼─ route "kafka": kafka                                     (mirror)
                              └─ route "it-webhook": REST endpoint                         (mirror)
```

* Each mirror is an **independent route** with its own sender thread, retry/failback
  timer and disk buffer (`data/bimba/buffer/mirror-<name>/`). An outage or slow
  receiver never delays Boomi or BIMBA Ingest, and vice versa.
* Every route gets the **same batch** (same `batchId`, same `messageId`s), so receivers
  can correlate or de-duplicate across systems.
* The plugin status page lists each route under `delivery.routes[]` (active destination,
  buffered batches, replayed messages).

## Kafka (`"transport": "kafka"`)

```json
{
  "name": "kafka", "role": "mirror", "enabled": true, "transport": "kafka",
  "bootstrapServers": "kafka:9092",
  "topic": "bimba.{plantId}.telemetry",
  "kafkaMode": "batch",
  "kafka": {},
  "timeoutMs": 5000, "retryAfterSeconds": 15
}
```

| `kafkaMode` | Record key | Record value | Headers |
|---|---|---|---|
| `batch` (default) | `edgeId` | batch envelope JSON (`docs/payload.md`) | `batchId`, `plantId`, `edgeId`, `schemaVersion`, `count` |
| `message` | `machineId/tagName` (ordering per tag within a partition) | one canonical message | the same + `messageId` |

The producer uses `acks=all`, idempotence and gzip. A batch counts as delivered only
after the brokers acknowledge every record. If the broker is unreachable, the route
buffers to disk and replays in order once it recovers.

### Managed Kafka services
Put the client security settings in `"kafka"`. They're passed to the producer unchanged.

**Confluent Cloud**
```json
"bootstrapServers": "pkc-xxxxx.<region>.<cloud>.confluent.cloud:9092",
"kafka": {
  "security.protocol": "SASL_SSL",
  "sasl.mechanism": "PLAIN",
  "sasl.jaas.config": "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"<API key>\" password=\"<API secret>\";"
}
```

**Azure Event Hubs (Kafka endpoint)**, where the topic is the event hub name:
```json
"bootstrapServers": "<namespace>.servicebus.windows.net:9093",
"kafka": {
  "security.protocol": "SASL_SSL",
  "sasl.mechanism": "PLAIN",
  "sasl.jaas.config": "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$ConnectionString\" password=\"<Event Hubs connection string>\";"
}
```

**AWS MSK (SASL/SCRAM)**
```json
"bootstrapServers": "b-1.<cluster>.kafka.<region>.amazonaws.com:9096",
"kafka": {
  "security.protocol": "SASL_SSL",
  "sasl.mechanism": "SCRAM-SHA-512",
  "sasl.jaas.config": "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"<user>\" password=\"<password>\";"
}
```
Redpanda, Strimzi and on-prem Kafka work with the same settings (PLAINTEXT, SSL or SASL).
Only these three setups have example configs here. Only local PLAINTEXT Kafka was
tested in this POC.

## Any other IT receiver

| Receiver | Transport | Example |
|---|---|---|
| Webhook / REST API (Azure Function, AWS API Gateway, Logic Apps, ServiceNow, custom service) | `rest` | `"url": "https://…", "headers": {"Authorization": "Bearer …"}` |
| Splunk HEC | `rest` | `"url": "https://splunk:8088/services/collector/raw", "headers": {"Authorization": "Splunk <HEC token>"}` |
| MQTT broker (HiveMQ, EMQX, AWS IoT Core, Azure IoT Hub via MQTT, Mosquitto) | `mqtt` | `"brokerUrl": "ssl://…:8883", "topic": "bimba/v1/{plantId}/batch"` |
| Kafka-compatible (Confluent, MSK, Event Hubs, Redpanda) | `kafka` | see above |
| Boomi Event Streams (as an extra copy) | `eventstreams` | `url` from *View REST* + `auth.token` |

Any receiver that accepts HTTP POST JSON, MQTT or the Kafka protocol can be added as a
mirror by configuration alone, with no code change. A REST receiver should answer
**2xx** when it has stored the batch, **400/413/422** for an invalid batch (the batch is
parked in `rejected/`), and anything else to request a retry.

## Local test setup
```bash
docker compose --profile kafka up -d kafka kafka-ui
```
* Kafka UI: http://localhost:8085 (topic `bimba.PLANT01.telemetry`)
* From the host: bootstrap `localhost:29092`. From containers: `kafka:9092`.

Tested in this POC:
* Records arrive with the key and headers above.
* Kafka was stopped for 40 s. Meanwhile the main route kept storing to PostgreSQL
  (286 rows) and the Kafka route buffered 37 batches.
* After the restart the Kafka route replayed everything. The topic held seq 1–3,324
  for the session, with **0 missing and 0 duplicates**.
