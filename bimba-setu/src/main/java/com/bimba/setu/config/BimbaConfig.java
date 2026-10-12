package com.bimba.setu.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BIMBA Setu configuration, read from {@code <config dir>/config.json} (BIMBA_CONFIG_DIR).
 * The file is created with these defaults on first start and hot-reloaded on change.
 */
public class BimbaConfig {
    public String plantId = "PLANT01";
    public String edgeId = "bimba-gw01";
    /** how often every device is read; a message is sent when a value or its quality changes */
    public int pollMs = 1000;
    public Batch batch = new Batch();
    public int queueCapacity = 100_000;
    public Buffer buffer = new Buffer();
    /** Tag tree: folders = machines, tags = Modbus register / OPC UA node / expression (edited in Settings) */
    public String provisionTagsFile = "demo-tags.json";
    /** Device connections (Modbus hosts, OPC UA endpoints) by device name (edited in Settings) */
    public String sourcesFile = "console-sources.json";
    /**
     * Only read to import older files: destinations still listed here are moved into the database once and removed
     * from this file. Destinations and pipelines are managed in the Setu console.
     */
    public List<Destination> destinations = new ArrayList<>();
    /** What the delivery engine runs (from the database, not from this file): the active pipelines of every organization. */
    public transient List<Pipeline> pipelines = new ArrayList<>();
    /** machine -> owning organization (from the database); machines not listed belong to defaultOrgId */
    public transient Map<String, Long> machineOwners = new LinkedHashMap<>();
    /** live: assignments made since machineOwners was loaded, checked first (null = none tracked) */
    public transient Map<String, Long> recentOwners;
    public transient Long defaultOrgId;
    /** Site agent registry (BIMBA Site Agent enroll/heartbeat endpoints). Works without this section. */
    public Agents agents = new Agents();
    /** Console sign-in: password policy, lockout and session lifetime. Works without this section. */
    public Security security = new Security();

    public static class Security {
        /** sign out after this many minutes without activity */
        public int idleMinutes = 30;
        /** sign in again after this many hours, active or not */
        public int maxSessionHours = 12;
        /** wrong passwords in a row before the account is locked */
        public int maxFailedLogins = 5;
        public int lockMinutes = 15;
        public int minPasswordLength = 12;
        /** of upper case, lower case, digit, symbol */
        public int minCharacterClasses = 3;
    }

    /**
     * Machines running the BIMBA Site Agent enroll at /system/bimba-agent-enroll and send heartbeats to
     * /system/bimba-agent-heartbeat. Stored in the shared PostgreSQL (Spring datasource, BIMBA_DB_*).
     */
    public static class Agents {
        public boolean enabled = true;
        /** heartbeat interval handed to agents at enrollment */
        public int heartbeatSeconds = 60;
        /** reject enroll/heartbeat requests that did not arrive over HTTPS (set true outside the lab) */
        public boolean requireHttps = false;
        /** failed enroll attempts per client IP within failureWindowMinutes before it gets 429 */
        public int maxFailures = 10;
        public int failureWindowMinutes = 15;
        /** give agents a client certificate (mutual TLS) instead of their secret, over HTTPS */
        public boolean issueCertificates = true;
        /** lifetime of an agent certificate; agents renew when a third of it is left */
        public int certificateDays = 90;
    }

    public static class Batch {
        public int maxMessages = 500;
        public int maxWaitMs = 1000;
    }

    public static class Buffer {
        public int maxMegabytes = 512;
        /** buffered batches replayed per delivery cycle */
        public int replayPerCycle = 20;
    }

    /**
     * Which machines' data goes where: one primary destination, secondaries tried in order while the primary is down,
     * and mirrors that each get their own copy. Destinations receive data only through a pipeline.
     */
    public static class Pipeline {
        public long id;
        /** the organization that owns the pipeline: it only carries that organization's machines (0 = copy from before organizations) */
        public long orgId;
        public String name;
        /** empty = every machine of the organization, also ones added later */
        public List<String> machines = new ArrayList<>();
        /** tag patterns on "machine/tag" (or just "tag"), * and ? wildcards; empty include = every tag */
        public List<String> tagInclude = new ArrayList<>();
        public List<String> tagExclude = new ArrayList<>();
        /** change (every value change) | sample (every rateSeconds, each tag's current value) | average (avg/min/max per window) */
        public String rateMode = "change";
        public int rateSeconds = 60;
        /** readings per batch envelope, 0 = Setu's batch.maxMessages (1 = every reading in its own envelope) */
        public int batchMaxMessages;
        /** longest wait before a partly filled batch is sent, 0 = Setu's batch.maxWaitMs */
        public int batchMaxWaitMs;
        /** size of each of its routes' disk buffers, 0 = Setu's buffer.maxMegabytes (FR-RES-05) */
        public int bufferMaxMegabytes;
        /** FR-RTE-05: drop readings it already carried (same messageId, or same machine/tag/timestamp) */
        public boolean dedupe;
        /** FR-RTE-05: send a reading only when its value or quality differs from the last one sent for that tag */
        public boolean changeOnly;
        /** with changeOnly: numbers that moved by at most this much count as unchanged (0 = any change is sent) */
        public double deadband;
        /** every-change pipelines: send each tag's last reading again when it was not sent for this many seconds (0 = never) */
        public int refreshSeconds;
        /** FR-RES-03: carry the gap markers of its machines (a device or site agent lost and restored) */
        public boolean gapMarkers = true;
        public Destination primary;
        public List<Destination> secondary = new ArrayList<>();
        public List<Destination> mirrors = new ArrayList<>();
        /** the pipeline made from the former destination roles keeps using the disk buffers of that time */
        public boolean legacyBuffer;

        public List<Destination> all() {
            List<Destination> l = new ArrayList<>();
            if (primary != null) {
                l.add(primary);
            }
            l.addAll(secondary);
            l.addAll(mirrors);
            return l;
        }
    }

    public static class Destination {
        public String name;
        /** catalog entry the console created it from (boomi-eventstreams, confluent-cloud, splunk-hec, ...) */
        public String service;
        /** set by the pipeline that uses it: primary | failover (a secondary) | mirror; in older files also from the file */
        public String role = "primary";
        /** only in older files (import) */
        public boolean enabled = true;
        /**
         * rest | mqtt | eventstreams (Boomi Event Streams produce REST API) | kafka | database (the BIMBA database) |
         * sftp | ftp (files) | mllp (HL7 v2) | jms (JMS over AMQP 1.0) | amqp (AMQP 0-9-1, RabbitMQ) | jdbc (the customer's database)
         */
        public String transport = "rest";
        public int timeoutMs = 5000;
        /** how long a failed destination is skipped before it is tried again (failback) */
        public int retryAfterSeconds = 15;
        /**
         * Health-check this destination while it is on standby (REST only). The receiver gets a POST with
         * header X-BIMBA-Probe: 1 and must answer 2xx without storing anything (BIMBA Ingest does).
         */
        public boolean probe = false;
        public int probeEverySeconds = 30;
        /** health-check every destination type while it is on standby (MQTT connects, Kafka describes the topic, HTTP signs in) */
        public boolean healthCheck = true;
        /** failed again and again: the wait doubles from retryAfterSeconds up to this */
        public int maxRetryAfterSeconds = 300;
        /** split batches into requests of at most this many readings (0 = as they come) */
        public int maxBatchMessages = 0;
        /**
         * Message format this destination receives: "1" = BIMBA 1.x (camelCase, what destinations made before 2.0 get),
         * "2" = the BRD canonical envelope 2.0 (snake_case, canonical/CanonicalV2). The BIMBA database always takes 1.x.
         */
        public String schemaVersion = "1";

        // TLS (HTTPS, MQTT ssl/wss, Kafka SSL): PEM texts; tlsKey is a secret
        /** extra trusted certificate authorities (self-signed or company PKI), added to the built-in ones */
        public String tlsCa;
        /** client certificate (chain) and its private key (PKCS#8 or RSA PKCS#1) for certificate sign-in */
        public String tlsCert;
        public String tlsKey;

        // HTTP proxy (REST, Event Streams): http://host:port
        public String proxy;
        public String proxyUsername;
        public String proxyPassword;
        /** REST: gzip the request body (Content-Encoding: gzip) */
        public boolean compress;

        // REST
        public String url;
        /**
         * Body of each POST: envelope (the BIMBA batch JSON) | ndjson (one canonical message per line) |
         * splunk-hec (HEC events) | elastic-bulk (Elasticsearch / OpenSearch _bulk) | influx-line (InfluxDB line protocol)
         */
        public String format = "envelope";
        /** REST: POST (default) or PUT */
        public String method = "POST";
        /** csv format: column separator (, ; or a tab) and whether the first line names the columns */
        public String csvSeparator = ",";
        public boolean csvHeader = true;
        /** xml format: the document's root element and the element of each reading */
        public String xmlRoot = "batch";
        public String xmlItem = "reading";

        // files (sftp / ftp): url = sftp://host[:22]/folder or ftp://host[:21]/folder; format json | ndjson | csv | xml
        /** name of each file: {plantId}, {edgeId}, {batchId}, {count}, {ext}, {time:yyyyMMdd-HHmmss} (UTC) */
        public String fileName = "bimba_{plantId}_{time:yyyyMMdd'T'HHmmss'Z'}_{batchId}.{ext}";
        /** collect readings this many seconds into one file (0 = one file per batch) */
        public int windowSeconds;
        /** keep a copy of every file sent for this many days (0 = no copy) */
        public int archiveDays;
        /** SFTP: the server's host key fingerprint as ssh-keygen -l prints it (SHA256:...); empty = not checked */
        public String hostKey;
        /** SFTP: private key (OpenSSH or PEM) for key sign-in, a secret; sshKeyPassphrase unlocks it */
        public String sshKey;
        public String sshKeyPassphrase;
        /** FTP: none | explicit (AUTH TLS on the normal port) | implicit (FTPS, usually port 990) */
        public String ftpTls = "none";
        public boolean ftpPassive = true;

        // MLLP (HL7 v2): url = mllp://host:port (mllps:// = TLS)
        /** segments, one per line; OBX lines are repeated for every reading (see delivery/Hl7) */
        public String hl7Template;
        /** machine (one message per machine per batch, an OBX per reading) | reading (one message per reading) */
        public String hl7Mode = "machine";

        // JMS (AMQP 1.0: amqp://, amqps://, failover:(...)) and AMQP 0-9-1 (RabbitMQ: amqp://host/vhost)
        /** JMS: the queue or topic; AMQP: not used (exchange + routingKey) */
        public String queue;
        /** JMS: queue | topic */
        public String jmsDestinationType = "queue";
        /** AMQP: the exchange ("" = the default exchange, routingKey is then the queue) */
        public String exchange = "";
        /** AMQP: routing key, may hold {plantId}, {machineId} (per machine / per reading) */
        public String routingKey;
        /** JMS / AMQP: machine (one message per machine per batch, keyed by it) | message (one per reading) | batch */
        public String messageMode = "machine";
        /** JMS / AMQP: the broker keeps messages on disk (persistent delivery) */
        public boolean persistent = true;

        // database of the customer (jdbc): PostgreSQL, SQL Server, MySQL / MariaDB, Oracle
        public String jdbcUrl;
        /** table, may be schema.table */
        public String table;
        /** JSON object column -> placeholder, like a template: {"machine_id": "{machineId}", "ts": "{timestamp}", ...} */
        public String columns;
        /** columns that identify a reading: insert-or-skip / upsert match on them (FR-END-05: machine, tag, timestamp) */
        public List<String> keyColumns = new ArrayList<>();
        /** insert | ignore (insert, skip readings already stored) | upsert (insert or update) */
        public String writeMode = "upsert";

        /**
         * Optional JSON template for each reading, e.g. {"asset":"{machineId}","value":"{tagValue}","ts":"{timestampMs}"}.
         * Replaces the BIMBA message wherever this destination sends a reading (see delivery/Template).
         */
        public String template;
        /**
         * Optional JSON Schema every reading must match as this destination sends it (after its template / format,
         * FR-RTE-03); a batch with one that does not is kept as refused instead of sent.
         */
        public String outputSchema;
        /** MQTT: batch (one message per batch on topic) | message (one message per reading, topic may use {machineId}, {tagName}) */
        public String mqttMode = "batch";
        public Map<String, String> headers = new LinkedHashMap<>();
        public Auth auth;

        // Event Streams: url = the topic's REST URL, auth = {"type":"bearer","token":"<environment token>"}
        /** batch (one Event Streams message per batch) | message (one per tag value) */
        public String esMode = "batch";
        /** auto (detect /singlemsg/ in url) | single | multi */
        public String esApi = "auto";

        // Kafka: bootstrapServers + topic; "kafka" = extra producer properties (security.protocol, sasl.*, ssl.*)
        public String bootstrapServers;
        /** batch (one record per batch, key = edgeId) | message (one record per tag value, key below) */
        public String kafkaMode = "batch";
        /** message mode: tag (key = machine/tag) | machine (key = machineId, FR-END-04: a machine's readings in one partition) */
        public String kafkaKey = "tag";
        public Map<String, String> kafka = new LinkedHashMap<>();
        /** PLAINTEXT | SSL | SASL_PLAINTEXT | SASL_SSL; with SASL, username/password below build sasl.jaas.config */
        public String securityProtocol;
        /** PLAIN | SCRAM-SHA-256 | SCRAM-SHA-512 */
        public String saslMechanism;

        // MQTT / Kafka (username/password: MQTT sign-in or Kafka SASL)
        public String brokerUrl;
        /** MQTT: the broker keeps the last message of each topic for new subscribers (Unified Namespace) */
        public boolean retain;
        /** MQTT: messages in flight at once (one message per reading is sent in parallel) */
        public int maxInflight = 100;
        public String topic = "bimba/v1/{plantId}/batch";
        public int qos = 1;
        public String clientId;
        public String username;
        public String password;
    }

    public static class Auth {
        /** none | basic | bearer | token ("Authorization: {scheme} {token}", e.g. Splunk, Token, ApiKey) | oauth2 */
        public String type = "none";
        public String username;
        public String password;
        public String token;
        public String scheme;
        // OAuth2 client credentials: a token from tokenUrl, renewed before it expires
        public String tokenUrl;
        public String clientId;
        public String clientSecret;
        public String scope;
        public String audience;
    }

    /**
     * Replaces "${ENV_VAR}" secrets (auth token/password, MQTT/Kafka password, header values, Kafka properties) with Setu's
     * environment, so tokens can live in docker-compose's .env instead of config.json.
     */
    public void resolveEnv() {
        List<Destination> all = new ArrayList<>(destinations);
        pipelines.forEach(p -> all.addAll(p.all()));
        for (Destination d : all) {
            if (d.auth != null) {
                d.auth.token = env(d.auth.token);
                d.auth.password = env(d.auth.password);
                d.auth.clientSecret = env(d.auth.clientSecret);
            }
            d.password = env(d.password);
            d.proxyPassword = env(d.proxyPassword);
            d.tlsKey = env(d.tlsKey);
            d.sshKey = env(d.sshKey);
            d.sshKeyPassphrase = env(d.sshKeyPassphrase);
            if (d.headers != null) {
                d.headers.replaceAll((k, v) -> env(v));
            }
            if (d.kafka != null) {
                d.kafka.replaceAll((k, v) -> env(v));
            }
        }
    }

    private static String env(String v) {
        if (v == null || !v.startsWith("${") || !v.endsWith("}")) {
            return v;
        }
        String value = System.getenv(v.substring(2, v.length() - 1));
        return value == null ? "" : value.trim();
    }

    /** A new Setu server starts without destinations (data is kept on disk) until they are added in the console. */
    public static BimbaConfig defaults() {
        return new BimbaConfig();
    }
}
