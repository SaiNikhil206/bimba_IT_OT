// Catalog of services a destination can be created from. Each entry picks the transport the gateway uses and fills in
// the settings that service always needs; `fixed` settings are not shown in the form. The gateway stores the key as
// "service" so the form looks the same when the destination is edited later. `hidden` entries are not offered any more
// but still describe destinations made from them earlier.

const TOPIC_KAFKA = "bimba.{plantId}.telemetry";
const TOPIC_MQTT = "bimba/v1/{plantId}/batch";

export const CATEGORIES = ["Built into the gateway", "Boomi", "Kafka", "MQTT", "Analytics & monitoring", "Generic"];

export const SERVICES = [
  {
    key: "bimba-database", label: "BIMBA database", category: "Built into the gateway", transport: "database", role: "failover",
    description: "The gateway stores every reading in the BIMBA database itself. Nothing to set up; add it once if you need it.",
    defaults: { timeoutMs: 10000 }, single: true,
  },
  {
    key: "boomi-eventstreams", label: "Boomi Event Streams", category: "Boomi", transport: "eventstreams", role: "primary",
    description: "Produce to an Event Streams topic through its REST endpoint.",
    defaults: { esMode: "batch", esApi: "auto" },
  },
  {
    key: "boomi-atom", label: "Boomi Atom web service", category: "Boomi", transport: "rest", role: "primary",
    description: "POST to a Web Services Server listener of a Boomi process (Atom, Molecule, Atom Cloud).",
    defaults: { url: "http://boomi-atom:9090/ws/rest/bimba/ingest", format: "envelope", auth: { type: "basic" } },
    fixed: ["format"], authTypes: ["basic", "none"],
    labels: { "auth.username": "User name (user@account)", "auth.password": "Password or token" },
  },
  {
    key: "bimba-ingest", label: "BIMBA Ingest (old receiver)", category: "BIMBA", transport: "rest", role: "failover", hidden: true,
    description: "The separate BIMBA Ingest receiver (Django). The gateway now stores readings itself: use the BIMBA database destination.",
    defaults: { url: "http://django:8000/api/ingest/", format: "envelope", headers: { "X-API-Key": "" }, probe: true },
    fixed: ["format"],
  },
  {
    key: "kafka", label: "Apache Kafka", category: "Kafka", transport: "kafka", role: "mirror",
    description: "Any Kafka cluster you run yourself (PLAINTEXT, SSL or SASL).",
    defaults: { bootstrapServers: "kafka:9092", topic: TOPIC_KAFKA, securityProtocol: "PLAINTEXT" },
  },
  {
    key: "confluent-cloud", label: "Confluent Cloud", category: "Kafka", transport: "kafka", role: "mirror",
    description: "Kafka on Confluent Cloud, signed in with a cluster API key.",
    defaults: { topic: TOPIC_KAFKA, securityProtocol: "SASL_SSL", saslMechanism: "PLAIN" },
    fixed: ["securityProtocol", "saslMechanism"],
    labels: { username: "API key", password: "API secret" },
    placeholders: { bootstrapServers: "pkc-xxxxx.<region>.<cloud>.confluent.cloud:9092" },
  },
  {
    key: "azure-event-hubs", label: "Azure Event Hubs", category: "Kafka", transport: "kafka", role: "mirror",
    description: "Event Hubs through its Kafka endpoint (Standard tier or higher).",
    defaults: { topic: "bimba-telemetry", securityProtocol: "SASL_SSL", saslMechanism: "PLAIN", username: "$ConnectionString" },
    fixed: ["securityProtocol", "saslMechanism", "username"],
    labels: { bootstrapServers: "Namespace host", topic: "Event hub", password: "Connection string" },
    hints: { password: "Shared access policy connection string (Endpoint=sb://…;SharedAccessKeyName=…;SharedAccessKey=…)." },
    placeholders: { bootstrapServers: "<namespace>.servicebus.windows.net:9093" },
  },
  {
    key: "aws-msk", label: "Amazon MSK", category: "Kafka", transport: "kafka", role: "mirror",
    description: "Amazon Managed Streaming for Apache Kafka with SASL/SCRAM sign-in.",
    defaults: { topic: TOPIC_KAFKA, securityProtocol: "SASL_SSL", saslMechanism: "SCRAM-SHA-512" },
    fixed: ["securityProtocol", "saslMechanism"],
    hints: { username: "SCRAM secret from AWS Secrets Manager (IAM sign-in is not supported)." },
    placeholders: { bootstrapServers: "b-1.<cluster>.kafka.<region>.amazonaws.com:9096" },
  },
  {
    key: "redpanda", label: "Redpanda", category: "Kafka", transport: "kafka", role: "mirror",
    description: "Redpanda Cloud or self-hosted (Kafka API).",
    defaults: { topic: TOPIC_KAFKA, securityProtocol: "SASL_SSL", saslMechanism: "SCRAM-SHA-256" },
  },
  {
    key: "mqtt", label: "MQTT broker", category: "MQTT", transport: "mqtt", role: "mirror",
    description: "Mosquitto, EMQX, VerneMQ, HiveMQ or any MQTT 3.1.1 broker.",
    defaults: { brokerUrl: "tcp://mqtt:1883", topic: TOPIC_MQTT, qos: 1 },
  },
  {
    key: "mqtt-uns", label: "Unified Namespace (MQTT)", category: "MQTT", transport: "mqtt", role: "mirror",
    description: "One JSON message per reading on its own topic, e.g. bimba/PLANT01/PUMP-P1/Flow.",
    defaults: { brokerUrl: "tcp://mqtt:1883", topic: "bimba/{plantId}/{machineId}/{tagName}", qos: 1, mqttMode: "message", retain: true,
      template: '{\n  "value": "{tagValue}",\n  "unit": "{unit}",\n  "quality": "{quality}",\n  "ts": "{timestampMs}"\n}' },
    fixed: ["mqttMode"],
  },
  {
    key: "hivemq-cloud", label: "HiveMQ Cloud", category: "MQTT", transport: "mqtt", role: "mirror",
    description: "HiveMQ Cloud cluster over TLS with a user name and password.",
    defaults: { brokerUrl: "ssl://", topic: TOPIC_MQTT, qos: 1 },
    placeholders: { brokerUrl: "ssl://<cluster>.s1.eu.hivemq.cloud:8883" },
  },
  {
    key: "rabbitmq", label: "RabbitMQ", category: "MQTT", transport: "mqtt", role: "mirror",
    description: "RabbitMQ with the MQTT plugin: messages arrive on the amq.topic exchange.",
    defaults: { brokerUrl: "tcp://rabbitmq:1883", topic: TOPIC_MQTT, qos: 1 },
    hints: { topic: "Routing key in amq.topic = the topic with / replaced by . (bimba.v1.<plant>.batch)." },
  },
  {
    key: "splunk-hec", label: "Splunk HTTP Event Collector", category: "Analytics & monitoring", transport: "rest", role: "mirror",
    description: "One Splunk event per reading (sourcetype _json).",
    defaults: { url: "https://splunk:8088/services/collector/event", format: "splunk-hec", auth: { type: "token", scheme: "Splunk" } },
    fixed: ["format", "auth.type", "auth.scheme"],
    labels: { "auth.token": "HEC token" },
  },
  {
    key: "elasticsearch", label: "Elasticsearch / OpenSearch", category: "Analytics & monitoring", transport: "rest", role: "mirror",
    description: "Bulk-indexes one document per reading; a retried batch is not stored twice.",
    defaults: { url: "https://elasticsearch:9200/bimba-telemetry/_bulk", format: "elastic-bulk", auth: { type: "basic" } },
    fixed: ["format"], authTypes: ["basic", "token", "none"], tokenScheme: "ApiKey",
    hints: { url: "…/<index>/_bulk. Token sign-in = an Elasticsearch API key (scheme ApiKey)." },
  },
  {
    key: "influxdb", label: "InfluxDB", category: "Analytics & monitoring", transport: "rest", role: "mirror",
    description: "InfluxDB 2.x / Cloud: one point per reading (measurement bimba).",
    defaults: { url: "http://influxdb:8086/api/v2/write?org=my-org&bucket=bimba&precision=ms", format: "influx-line", auth: { type: "token", scheme: "Token" } },
    fixed: ["format", "auth.type", "auth.scheme"],
    labels: { "auth.token": "API token" },
    hints: { url: "…/api/v2/write?org=<org>&bucket=<bucket>&precision=ms" },
  },
  {
    key: "webhook", label: "HTTP webhook", category: "Generic", transport: "rest", role: "mirror",
    description: "POST every batch to any HTTPS endpoint, as one JSON document or one line per reading.",
    defaults: { url: "https://", format: "envelope" },
  },
];

const BY_KEY = Object.fromEntries(SERVICES.map((s) => [s.key, s]));

/** The catalog entry of a destination (older destinations without "service" are matched by their settings). */
export function serviceOf(d) {
  if (d.service && BY_KEY[d.service]) return BY_KEY[d.service];
  if (d.transport === "database") return BY_KEY["bimba-database"];
  if (d.transport === "eventstreams") return BY_KEY["boomi-eventstreams"];
  if (d.transport === "kafka") return BY_KEY.kafka;
  if (d.transport === "mqtt") return BY_KEY.mqtt;
  if (/\/api\/ingest\/?$/.test(d.url ?? "")) return BY_KEY["bimba-ingest"];
  if (/\/ws\/rest\//.test(d.url ?? "")) return BY_KEY["boomi-atom"];
  return { ...BY_KEY.webhook, ...(d.format && d.format !== "envelope" && d.format !== "ndjson" ? { fixed: ["format"] } : {}) };
}
