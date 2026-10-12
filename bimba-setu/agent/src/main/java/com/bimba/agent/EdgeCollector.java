package com.bimba.agent;

import com.bimba.agent.SetuClient.SetuException;
import com.bimba.agent.SetuClient.Json;
import com.bimba.edge.EdgeEngine;
import com.bimba.setu.collect.OpcUaPki;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The agent as edge collector. Reads the data sources configured for this computer in the Setu console
 * (GET /system/bimba-agent-config, refetched when the heartbeat's configVersion changes or the console says so),
 * collects them in-process with Setu's own drivers (EdgeEngine: OPC UA, Modbus TCP), keeps every reading in a disk
 * buffer and uploads the buffer in batches (POST /system/bimba-agent-readings), so nothing is lost while Setu is
 * unreachable. Console requests (test a connection, browse tags) arrive through a long-poll (POST /system/bimba-agent-commands).
 */
final class EdgeCollector {
    /** Setu refuses requests over 256 KB (413) */
    private static final int MAX_UPLOAD_CHARS = 250 * 1024;
    private final AgentConfig cfg;
    private final Supplier<SetuClient> client;
    private final CountDownLatch stop;
    private final Spool spool;
    private final EdgeEngine engine;
    private final List<Thread> threads = new ArrayList<>();
    private final ExecutorService requests = new ThreadPoolExecutor(1, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64),
            r -> daemon(r, "console-request"));
    private final Object configGate = new Object();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final String version = AgentMain.version();
    private volatile JsonObject config;
    private volatile String appliedVersion;
    private volatile String wantedVersion;
    private volatile String lastUpload, lastUploadError, lastConfigError, engineError;

    EdgeCollector(AgentConfig cfg, Supplier<SetuClient> client, CountDownLatch stop) throws Exception {
        this.cfg = cfg;
        this.client = client;
        this.stop = stop;
        this.spool = new Spool(Paths.spoolDir(), cfg.spoolMaxBytes());
        this.engine = new EdgeEngine(m -> spool.add(SetuClient.GSON.toJson(m)), msg -> Log.info("[edge] " + msg));
    }

    void start() {
        String host = OpcUaPki.hostName();
        try {
            Files2.privateDir(Paths.edgePkiDir());
            OpcUaPki.Identity id = OpcUaPki.configure(Paths.edgePkiDir().toFile(), "BIMBA Site Agent " + host,
                    "urn:bimba:site-agent:" + host.toLowerCase(Locale.ROOT));
            Log.info("OPC UA client certificate " + id.applicationUri() + " SHA-256 " + id.certificateSha256());
        } catch (Exception e) {
            Log.warn("OPC UA client certificate unavailable (only security None works): " + e);
        }
        config = loadCache();
        if (config != null) {
            appliedVersion = Json.str(config, "version");
            apply(config);
        }
        run("uploader", this::uploadLoop);
        run("commands", this::commandLoop);
        run("edge-watch", this::watchLoop);
    }

    private void run(String name, Runnable body) {
        Thread t = daemon(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                Log.error(name + " stopped: " + e);
            }
        }, name);
        threads.add(t);
        t.start();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private boolean stopped(long millis) {
        try {
            return stop.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    // ------------------------------------------------------------------ config

    /** called with every heartbeat answer: fetch the config when Setu has a newer one */
    void onHeartbeat(JsonObject resp) {
        String v = Json.str(resp, "configVersion");
        if (v == null) {
            return;                                    // Setu without edge collection
        }
        wantedVersion = v;
        if (!v.equals(appliedVersion)) {
            requests.execute(this::refresh);
        }
    }

    void refresh() {
        synchronized (configGate) {
            try {
                JsonObject fresh = client.get().getConfig(cfg);
                String v = Json.str(fresh, "version");
                if (v != null && v.equals(appliedVersion) && config != null) {
                    return;
                }
                saveCache(fresh);
                config = fresh;
                appliedVersion = v;
                lastConfigError = null;
                apply(fresh);
            } catch (Exception e) {
                lastConfigError = e.getMessage();
                Log.warn("could not fetch the data sources from Setu: " + e.getMessage());
            }
        }
    }

    private void apply(JsonObject c) {
        List<JsonObject> sources = Json.objects(c, "sources");
        JsonArray forEdge = new JsonArray();
        for (JsonObject s : sources) {
            String kind = Json.str(s, "kind");
            if (EdgeEngine.collectable(kind) && !"opcda".equals(kind)) {
                forEdge.add(s);
            }
        }
        int other = sources.size() - forEdge.size();
        Log.info("data sources: " + sources.size() + " (" + forEdge.size() + " OPC UA / Modbus / MQTT / S7 / EtherNet/IP"
                + (other > 0 ? ", " + other + " OPC DA (Windows only, not collected here)" : "") + ")");
        JsonObject line = new JsonObject();
        line.addProperty("plantId", Json.str(c, "plantId"));
        String edgeId = Json.str(c, "edgeId");
        line.addProperty("edgeId", edgeId == null ? OpcUaPki.hostName() : edgeId);
        line.add("sources", forEdge);
        try {
            engine.configure(line);
            engineError = null;
        } catch (RuntimeException e) {
            engineError = "cannot start collecting: " + e.getMessage();
            Log.error(engineError);
        }
    }

    private static JsonObject loadCache() {
        try {
            Path f = Paths.sourcesFile();
            return Files.isRegularFile(f) ? JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject() : null;
        } catch (Exception e) {
            Log.warn("cached data sources unreadable: " + e.getMessage());
            return null;
        }
    }

    private static void saveCache(JsonObject c) {
        try {
            Files2.writePrivate(Paths.sourcesFile(), SetuClient.GSON.toJson(c));
        } catch (Exception e) {
            Log.warn("could not cache the data sources: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ upload

    private void uploadLoop() {
        int failures = 0;
        try {
            while (!stopped(0)) {
                spool.flushIfDue();
                Path file = spool.oldest();
                if (file == null) {
                    if (stopped(1000)) {
                        return;
                    }
                    continue;
                }
                long wait = 0;
                List<String> lines = List.of();
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> l.length() > 1).toList();
                    String body = "{\"messages\":[" + String.join(",", lines) + "]}";
                    // a file from an older agent can be over Setu's request limit: upload it in halves
                    if (body.length() > MAX_UPLOAD_CHARS && spool.split(file, lines)) {
                        continue;
                    }
                    if (!lines.isEmpty()) {
                        JsonObject resp = client.get().postReadings(cfg, body);
                        sent.addAndGet(Json.integer(resp, "accepted", 0));
                        rejected.addAndGet(Json.integer(resp, "rejected", 0));
                    }
                    spool.done(file);
                    lastUpload = Instant.now().toString();
                    if (failures > 0) {
                        Log.info("readings upload works again after " + failures + " failure(s)");
                    }
                    failures = 0;
                    lastUploadError = null;
                } catch (SetuException e) {
                    if (e.status == 413 && spool.split(file, lines)) {
                        Log.info("Setu found a batch of " + lines.size() + " readings too large; sending it in two halves");
                    } else if (e.status == 400 || e.status == 413 || e.status == 422) {
                        Log.warn("Setu refused a batch of readings (" + e.status + "): " + e.getMessage() + "; moved to spool/rejected");
                        spool.reject(file);
                    } else {
                        failures++;
                        wait = failed(e, failures);
                    }
                } catch (Exception e) {
                    failures++;
                    wait = failed(e, failures);
                }
                if (wait > 0 && stopped(wait)) {
                    return;
                }
            }
        } finally {
            spool.flush();
        }
    }

    private long failed(Exception e, int failures) {
        lastUploadError = e.getMessage();
        if (failures == 1 || failures % 20 == 0) {
            Log.warn("readings upload failed (" + failures + "x): " + e.getMessage() + " (kept on disk)");
        }
        int status = e instanceof SetuException g ? g.status : 0;
        return status == 404 ? 300_000 : Math.min(60_000, 2000L * Math.min(failures, 30));
    }

    // ------------------------------------------------------------------ console commands

    private void commandLoop() {
        int failures = 0;
        while (!stopped(0)) {
            JsonObject cmd;
            try {
                cmd = client.get().nextCommand(cfg);
                failures = 0;
            } catch (Exception e) {
                failures++;
                int status = e instanceof SetuException g ? g.status : 0;
                if (failures == 1) {
                    Log.warn("console requests unavailable: " + e.getMessage());
                }
                // 404: a Setu server without edge collection; 410: revoked (the heartbeat loop stops the service)
                if (stopped(status == 404 ? 300_000 : Math.min(60_000, 5000L * failures))) {
                    return;
                }
                continue;
            }
            if (cmd == null) {
                continue;
            }
            try {
                requests.execute(() -> answer(cmd));
            } catch (RuntimeException e) {
                Log.warn("too many console requests at once: " + e.getMessage());
            }
        }
    }

    private void answer(JsonObject cmd) {
        String id = Json.str(cmd, "id");
        String kind = Json.str(cmd, "kind");
        JsonObject req = Json.obj(cmd, "request");
        if (req == null) {
            req = new JsonObject();
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        try {
            if ("config".equals(kind)) {
                refresh();
                answer.put("ok", lastConfigError == null);
                answer.put("result", Map.of("version", String.valueOf(appliedVersion)));
                if (lastConfigError != null) {
                    answer.put("error", lastConfigError);
                }
            } else if ("discover".equals(kind)) {
                answer.put("ok", true);
                answer.put("result", Discovery.all());
            } else {
                String sourceKind = Json.str(Json.obj(req, "source"), "kind");
                if (EdgeEngine.collectable(sourceKind) && !"opcda".equals(sourceKind)) {
                    answer.put("ok", true);
                    answer.put("result", EdgeEngine.command(kind, req));
                } else if ("opcda".equals(sourceKind)) {
                    answer.put("ok", false);
                    answer.put("error", "OPC DA (classic) is a Windows technology: use the Windows site agent, or the server's OPC UA endpoint.");
                } else {
                    answer.put("ok", false);
                    answer.put("error", "unknown data source type " + sourceKind);
                }
            }
        } catch (Exception e) {
            answer.put("ok", false);
            answer.remove("result");
            answer.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        try {
            client.get().commandResult(cfg, id, answer);
        } catch (Exception e) {
            Log.warn("could not send the answer to console request " + kind + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ supervision + status

    private void watchLoop() {
        while (!stopped(5000)) {
            String w = wantedVersion;
            if (w != null && !w.equals(appliedVersion) && config != null) {
                refresh();
            }
        }
    }

    /** sent with every heartbeat ("collector"): shown on the agent's page in the console */
    Map<String, Object> status() {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("state", engine.collecting() ? "running" : "idle");
        edge.put("version", version);
        edge.put("java", System.getProperty("java.version"));
        try {
            edge.put("opcuaCertificate", OpcUaPki.identity().certificateSha256());
            edge.put("opcuaApplicationUri", OpcUaPki.identity().applicationUri());
        } catch (RuntimeException e) {
            edge.put("opcuaCertificate", null);
        }
        edge.put("devices", engine.status().get("devices"));
        edge.put("restarts", 0);
        edge.put("error", engineError);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configVersion", appliedVersion);
        m.put("configError", lastConfigError);
        m.put("sources", Json.objects(config, "sources").size());
        m.put("edge", edge);
        m.put("spool", spool.status());
        m.put("sent", sent.get());
        m.put("rejected", rejected.get());
        m.put("lastUpload", lastUpload);
        m.put("lastUploadError", lastUploadError);
        m.put("at", Instant.now().toString());
        return m;
    }

    void close() {
        for (Thread t : threads) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        requests.shutdownNow();
        engine.stop();
        spool.flush();
    }
}
