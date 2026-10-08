package com.bimba.gateway;

import com.bimba.gateway.collect.Collector;
import com.bimba.gateway.config.BimbaConfig;
import com.bimba.gateway.config.DestinationStore;
import com.bimba.gateway.config.TagModel;
import com.bimba.gateway.delivery.DeliveryEngine;
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
 * (gateway console → Delivery), and restarts both when any of them changes.
 */
@Service
public class GatewayService {
    private static final Logger log = LoggerFactory.getLogger("BIMBA");

    private final File configDir;
    private final File dataDir;
    private final File configFile;
    private final DestinationStore destinations;
    private final Db db;
    private final Instant startedAt = Instant.now();
    private ScheduledExecutorService scheduler;
    private volatile BimbaConfig config;
    private volatile DeliveryEngine delivery;
    private volatile Collector collector;
    private volatile String configError;
    private volatile List<String> tagProblems = List.of();
    private volatile String stamp = "";

    public GatewayService(@Value("${bimba.config-dir:config}") File configDir, @Value("${bimba.data-dir:data}") File dataDir,
                          DestinationStore destinations, Db db) {
        this.destinations = destinations;
        this.db = db;
        this.configDir = configDir;
        this.dataDir = dataDir;
        this.configFile = new File(configDir, "config.json");
    }

    public BimbaConfig config() {
        return config;
    }

    /** the running delivery engine (null while collection is stopped) */
    public DeliveryEngine delivery() {
        return delivery;
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
        reload();
        destinations.onChange(() -> scheduler.execute(this::reload));
        scheduler.scheduleWithFixedDelay(this::watch, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
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
            stopComponents();
            config = next;
            stamp = stamp();
            tagProblems = List.copyOf(problems);
            problems.forEach(p -> log.warn("tag tree: {}", p));
            delivery = new DeliveryEngine(next, dataDir, db);
            delivery.start();
            collector = new Collector(next, tags, sources, delivery);
            collector.start();
            configError = null;
            log.info("BIMBA Gateway running: plant={} edge={} tags={}", next.plantId, next.edgeId, tags.size());
        } catch (Exception e) {
            configError = e.getClass().getSimpleName() + ": " + e.getMessage();
            stamp = stamp();
            log.error("BIMBA Gateway could not start collection - fix {} ({})", configDir, configError, e);
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
        if (collector != null) {
            collector.stop();
            collector = null;
        }
        if (delivery != null) {
            delivery.stop();
            delivery = null;
        }
    }

    /** /system/bimba-status: the same shape the former Ignition module returned, plus the device list. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", "BIMBA Gateway");
        m.put("version", BimbaGatewayApplication.VERSION);
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
        m.put("collector", col == null ? null : col.status());
        m.put("delivery", del == null ? null : del.status());
        return m;
    }
}
