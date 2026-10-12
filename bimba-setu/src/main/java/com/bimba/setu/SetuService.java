package com.bimba.setu;

import com.bimba.setu.canonical.Ingest;
import com.bimba.setu.collect.Collector;
import com.bimba.setu.config.BimbaConfig;
import com.bimba.setu.config.DestinationStore;
import com.bimba.setu.config.TagModel;
import com.bimba.setu.delivery.DeliveryEngine;
import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the collector + delivery engine. Reads config.json, the tag tree and the device connections from the
 * config folder (the same files BIMBA Operations → Settings edits) and the delivery destinations from the database
 * (Setu console → Delivery), and restarts both when any of them changes.
 */
@Service
public class SetuService {
    private static final Logger log = LoggerFactory.getLogger("BIMBA");

    private final File configDir;
    private final File dataDir;
    private final File configFile;
    private final DestinationStore destinations;
    private final Db db;
    private final Ingest ingest;
    private final Instant startedAt = Instant.now();
    /** notes when Setu ran, for the gap markers of its own downtime (FR-RES-03) */
    private final Downtime downtime;
    private ScheduledExecutorService scheduler;
    private volatile BimbaConfig config;
    private volatile DeliveryEngine delivery;
    private volatile Collector collector;
    private volatile String configError;
    private volatile List<String> tagProblems = List.of();
    private volatile String stamp = "";
    /** machines + tags collected by site agents (edge collectors), merged into the collector status */
    private volatile java.util.function.Supplier<List<Map<String, Object>>> agentTags = List::of;
    /** told when a route's disk buffer is 70 % full and when it is back (delivery/BufferAlerts) */
    private volatile java.util.function.Consumer<DeliveryEngine.BufferAlert> bufferAlerts = a -> { };

    public SetuService(@Value("${bimba.config-dir:config}") File configDir, @Value("${bimba.data-dir:data}") File dataDir,
                          DestinationStore destinations, Db db, Ingest ingest) {
        this.destinations = destinations;
        this.db = db;
        this.ingest = ingest;
        this.configDir = configDir;
        this.dataDir = dataDir;
        this.configFile = new File(configDir, "config.json");
        this.downtime = new Downtime(dataDir);
    }

    public BimbaConfig config() {
        return config;
    }

    /** the running delivery engine (null while collection is stopped) */
    public DeliveryEngine delivery() {
        return delivery;
    }

    public void setAgentTags(java.util.function.Supplier<List<Map<String, Object>>> supplier) {
        agentTags = supplier;
    }

    public void setBufferAlerts(java.util.function.Consumer<DeliveryEngine.BufferAlert> listener) {
        bufferAlerts = listener;
    }

    public File configFile() {
        return configFile;
    }

    /** Applies a config.json written by the console right away instead of at the next 5 s check. */
    public void reloadNow() {
        reload();
    }

    @PostConstruct
    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BIMBA-Config");
            t.setDaemon(true);
            return t;
        });
        try {
            // OPC UA client certificate for signed/encrypted policies (servers trust it once)
            com.bimba.setu.collect.OpcUaPki.configure(new File(dataDir, "pki"), "BIMBA Setu", "urn:bimba:gateway");   // URI kept: OPC UA servers already trust this client certificate
        } catch (Exception e) {
            log.warn("OPC UA client certificate unavailable (only security policy None works): {}", e.toString());
        }
        Downtime.Last last = downtime.read();
        reload();
        markDowntime(last);
        downtime.beat(false);
        destinations.onChange(() -> scheduler.execute(this::reload));
        scheduler.scheduleWithFixedDelay(this::watch, 5, 5, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(() -> downtime.beat(false), 10, 10, TimeUnit.SECONDS);
    }

    /** the time Setu was not running, marked on every machine it reads itself */
    @SuppressWarnings("unchecked")
    private void markDowntime(Downtime.Last last) {
        Collector col = collector;
        DeliveryEngine engine = delivery;
        BimbaConfig c = config;
        if (col == null || engine == null || c == null || last == null) {
            return;
        }
        List<String> machines = ((List<Object>) col.status().get("machines")).stream().map(String::valueOf).toList();
        List<JsonObject> markers = Downtime.markers(c.plantId, c.edgeId, machines, last, Instant.now());
        if (!markers.isEmpty()) {
            log.warn("Setu was not running since {}{}: gap markers for {} machine(s)", last.at(), last.stopped() ? "" : " (it stopped unexpectedly)",
                    machines.size());
            markers.forEach(m -> ingest.fromCollector(m, engine::offer));
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        downtime.beat(true);
        stopComponents();
    }

    private void watch() {
        try {
            if (!stamp().equals(stamp)) {
                log.info("configuration changed, reloading");
                reload();
            }
        } catch (Exception e) {
            log.error("watch cycle failed", e);
        }
    }

    /** modification times of config.json, the tag tree and the device connections */
    private String stamp() {
        BimbaConfig c = config;
        String tagsFile = c == null ? "demo-tags.json" : c.provisionTagsFile;
        String sourcesFile = c == null ? "console-sources.json" : c.sourcesFile;
        return configFile.lastModified() + "|" + new File(configDir, tagsFile).lastModified() + "|"
                + new File(configDir, sourcesFile).lastModified();
    }

    private synchronized void reload() {
        try {
            BimbaConfig next = loadConfig();
            List<String> problems = new ArrayList<>();
            List<TagModel.Tag> tags = TagModel.parse(readJson(next.provisionTagsFile, true), problems);
            JsonObject sources = readJson(next.sourcesFile, false);
            DeliveryEngine previous = delivery;
            stopComponents(next);
            config = next;
            stamp = stamp();
            tagProblems = List.copyOf(problems);
            problems.forEach(p -> log.warn("tag tree: {}", p));
            delivery = new DeliveryEngine(next, dataDir, db, previous);   // keeps open windows and filter memory
            delivery.onBufferAlert(a -> bufferAlerts.accept(a));
            delivery.start();
            DeliveryEngine engine = delivery;
            // every reading is checked against its schema first; invalid ones go to the quarantine (canonical/Ingest)
            collector = new Collector(next.plantId, next.edgeId, next.pollMs, tags, sources, m -> ingest.fromCollector(m, engine::offer));
            collector.start();
            configError = null;
            log.info("BIMBA Setu running: plant={} edge={} tags={}", next.plantId, next.edgeId, tags.size());
        } catch (Exception e) {
            configError = e.getClass().getSimpleName() + ": " + e.getMessage();
            stamp = stamp();
            log.error("BIMBA Setu could not start collection - fix {} ({})", configDir, configError, e);
        }
    }

    private BimbaConfig loadConfig() throws Exception {
        Files.createDirectories(configDir.toPath());
        if (!configFile.exists()) {
            Files.writeString(configFile.toPath(), Json.PRETTY.toJson(BimbaConfig.defaults()));
            log.info("wrote default configuration to {}", configFile);
        }
        BimbaConfig c = Json.GSON.fromJson(Files.readString(configFile.toPath(), StandardCharsets.UTF_8), BimbaConfig.class);
        if (c == null) {
            throw new IllegalArgumentException("config.json is empty");
        }
        c.pipelines = destinations.runtime(configFile, c.destinations == null ? List.of() : c.destinations);
        c.machineOwners = new java.util.HashMap<>(destinations.machineOwners());
        c.recentOwners = destinations.recentAssignments();
        c.defaultOrgId = destinations.defaultOrgId();
        c.destinations = new ArrayList<>();
        c.resolveEnv();
        return c;
    }

    private JsonObject readJson(String name, boolean required) throws Exception {
        File f = new File(configDir, name == null ? "" : name);
        if (!f.isFile()) {
            if (required) {
                throw new IllegalArgumentException("tag file " + f + " not found");
            }
            return new JsonObject();
        }
        String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        JsonObject o = Json.GSON.fromJson(text, JsonObject.class);
        return o == null ? new JsonObject() : o;
    }

    private void stopComponents() {
        stopComponents(null);
    }

    /** next = the configuration that follows (its pipelines take over open windows), null = shutting down */
    private void stopComponents(BimbaConfig next) {
        if (collector != null) {
            collector.stop();
            collector = null;
        }
        if (delivery != null) {
            delivery.stop(next);
            delivery = null;
        }
    }

    /** /system/bimba-status: the same shape the former Ignition module returned, plus the device list. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", "BIMBA Setu");
        m.put("version", BimbaSetuApplication.VERSION);
        m.put("startedAt", startedAt.toString());
        m.put("configFile", configFile.getAbsolutePath());
        m.put("configError", configError);
        m.put("tagProblems", tagProblems);
        m.put("destinationSource", destinations.source());
        m.put("destinationProblems", destinations.problems());
        BimbaConfig c = config;
        if (c != null) {
            m.put("plantId", c.plantId);
            m.put("edgeId", c.edgeId);
        }
        Collector col = collector;
        DeliveryEngine del = delivery;
        m.put("collector", withAgents(col == null ? null : col.status()));
        m.put("delivery", del == null ? null : del.status());
        m.put("quarantine", ingest.quarantine().status());
        return m;
    }

    /**
     * Adds the site agents' machines and tags to the collector status ("machines", "tags"), so the Overview, the
     * pipeline selections and the Organizations page treat them like Setu's own; "agentMachines" lists them.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withAgents(Map<String, Object> status) {
        List<Map<String, Object>> extra = agentTags.get();
        if (extra.isEmpty()) {
            return status;
        }
        Map<String, Object> m = status == null ? new LinkedHashMap<>() : new LinkedHashMap<>(status);
        java.util.TreeSet<String> machines = new java.util.TreeSet<>();
        if (m.get("machines") instanceof List<?> l) {
            l.forEach(x -> machines.add(String.valueOf(x)));
        }
        List<Map<String, Object>> tags = new ArrayList<>();
        if (m.get("tags") instanceof List<?> l) {
            l.forEach(x -> tags.add((Map<String, Object>) x));
        }
        java.util.TreeSet<String> agentMachines = new java.util.TreeSet<>();
        for (Map<String, Object> t : extra) {
            agentMachines.add(String.valueOf(t.get("machine")));
            tags.add(t);
        }
        machines.addAll(agentMachines);
        m.put("machines", List.copyOf(machines));
        m.put("tags", tags);
        m.put("agentMachines", List.copyOf(agentMachines));
        return m;
    }
}
