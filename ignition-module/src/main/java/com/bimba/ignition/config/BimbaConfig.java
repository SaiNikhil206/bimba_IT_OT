package com.bimba.ignition.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module configuration, read from {@code <ignition>/data/bimba/config.json}.
 * The file is created with these defaults on first start and hot-reloaded on change.
 */
public class BimbaConfig {
    public String plantId = "PLANT01";
    public String edgeId = "ignition-gw01";
    /** Tag folders to stream. Convention: {root}/{machineId}/{tag...} */
    public List<String> tagRoots = new ArrayList<>(List.of("[default]BIMBA"));
    /** machineId used for tags placed directly under a root */
    public String defaultMachineId = "IGNITION";
    public int rediscoverSeconds = 30;
    public Batch batch = new Batch();
    public int queueCapacity = 100_000;
    public Buffer buffer = new Buffer();
    /** Optional Ignition tag-export JSON (relative to data/bimba) imported into the first root's provider at startup */
    public String provisionTagsFile = null;
    public String provisionTagsBasePath = "[default]";
    public List<Destination> destinations = new ArrayList<>();

    public static class Batch {
        public int maxMessages = 500;
        public int maxWaitMs = 1000;
    }

    public static class Buffer {
        public int maxMegabytes = 512;
        /** buffered batches replayed per delivery cycle */
        public int replayPerCycle = 20;
    }

    public static class Destination {
        public String name;
        /** primary | failover | mirror (independent copy of every batch, own retry + disk buffer) */
        public String role = "primary";
        public boolean enabled = true;
        /** rest | mqtt | eventstreams (Boomi Event Streams produce REST API) | kafka */
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

        // REST
        public String url;
        public Map<String, String> headers = new LinkedHashMap<>();
        public Auth auth;

        // Event Streams: url = the topic's REST URL, auth = {"type":"bearer","token":"<environment token>"}
        /** batch (one Event Streams message per batch) | message (one per tag value) */
        public String esMode = "batch";
        /** auto (detect /singlemsg/ in url) | single | multi */
        public String esApi = "auto";

        // Kafka: bootstrapServers + topic; "kafka" = extra producer properties (security.protocol, sasl.*, ssl.*)
        public String bootstrapServers;
        /** batch (one record per batch, key = edgeId) | message (one record per tag value, key = machine/tag) */
        public String kafkaMode = "batch";
        public Map<String, String> kafka = new LinkedHashMap<>();

        // MQTT / Kafka
        public String brokerUrl;
        public String topic = "bimba/v1/{plantId}/batch";
        public int qos = 1;
        public String clientId;
        public String username;
        public String password;
    }

    public static class Auth {
        /** none | basic | bearer */
        public String type = "none";
        public String username;
        public String password;
        public String token;
    }

    /**
     * Replaces "${ENV_VAR}" secrets (auth token/password, MQTT password, header values) with the gateway's
     * environment, so tokens can live in docker-compose's .env instead of config.json.
     */
    public void resolveEnv() {
        for (Destination d : destinations) {
            if (d.auth != null) {
                d.auth.token = env(d.auth.token);
                d.auth.password = env(d.auth.password);
            }
            d.password = env(d.password);
            d.headers.replaceAll((k, v) -> env(v));
        }
    }

    private static String env(String v) {
        if (v == null || !v.startsWith("${") || !v.endsWith("}")) {
            return v;
        }
        String value = System.getenv(v.substring(2, v.length() - 1));
        return value == null ? "" : value.trim();
    }

    public static BimbaConfig defaults() {
        BimbaConfig c = new BimbaConfig();
        Destination boomi = new Destination();
        boomi.name = "boomi-eventstreams";
        boomi.role = "primary";
        boomi.transport = "eventstreams";
        boomi.url = "https://CHANGE_ME/topic-rest-url-from-event-streams";
        boomi.auth = new Auth();
        boomi.auth.type = "bearer";
        boomi.auth.token = "CHANGE_ME";

        Destination ingest = new Destination();
        ingest.name = "bimba-ingest";
        ingest.role = "failover";
        ingest.transport = "rest";
        ingest.url = "http://django:8000/api/ingest/";
        ingest.headers.put("X-API-Key", "dev-ingest-key");
        ingest.probe = true;

        c.destinations.add(boomi);
        c.destinations.add(ingest);
        return c;
    }
}
