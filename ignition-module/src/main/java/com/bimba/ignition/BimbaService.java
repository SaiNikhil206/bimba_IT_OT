package com.bimba.ignition;

import com.bimba.ignition.collect.TagCollector;
import com.bimba.ignition.config.BimbaConfig;
import com.bimba.ignition.delivery.DeliveryEngine;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.tags.config.CollisionPolicy;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns the collector + delivery engine; reloads them when config.json changes. */
public class BimbaService {
    private static final Logger log = LoggerFactory.getLogger("BIMBA");

    private final GatewayContext context;
    private final File dataDir;
    private final File configFile;
    private ScheduledExecutorService scheduler;
    private volatile BimbaConfig config;
    private volatile DeliveryEngine delivery;
    private volatile TagCollector collector;
    private volatile long configMtime;
    private volatile String configError;
    private final Instant startedAt = Instant.now();

    public BimbaService(GatewayContext context) {
        this.context = context;
        this.dataDir = new File(context.getSystemManager().getDataDir(), "bimba");
        this.configFile = new File(dataDir, "config.json");
    }

    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BIMBA-Scheduler");
            t.setDaemon(true);
            return t;
        });
        // tag providers may not be ready at module startup, so begin shortly after
        scheduler.schedule(this::reload, 5, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::watch, 15, 10, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        stopComponents();
    }

    private void watch() {
        try {
            if (configFile.lastModified() != configMtime) {
                log.info("config.json changed, reloading");
                reload();
            } else if (collector != null && config != null) {
                long age = System.currentTimeMillis() - lastDiscoveryMs;
                if (age >= config.rediscoverSeconds * 1000L) {
                    collector.discover();
                    lastDiscoveryMs = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            log.error("watch cycle failed", e);
        }
    }

    private volatile long lastDiscoveryMs;

    private synchronized void reload() {
        try {
            BimbaConfig next = loadConfig();
            stopComponents();
            config = next;
            configError = null;
            provisionTags(next);
            delivery = new DeliveryEngine(next, dataDir);
            delivery.start();
            collector = new TagCollector(context.getTagManager(), next, delivery);
            collector.discover();
            lastDiscoveryMs = System.currentTimeMillis();
            log.info("BIMBA IT/OT Agent running: plant={} edge={} roots={}", next.plantId, next.edgeId, next.tagRoots);
        } catch (Exception e) {
            configError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("BIMBA IT/OT Agent could not start - fix {} ({})", configFile, configError, e);
        }
    }

    private BimbaConfig loadConfig() throws Exception {
        Files.createDirectories(dataDir.toPath());
        if (!configFile.exists()) {
            Files.writeString(configFile.toPath(), Json.PRETTY.toJson(BimbaConfig.defaults()));
            log.info("wrote default configuration to {}", configFile);
        }
        configMtime = configFile.lastModified();
        BimbaConfig c = Json.GSON.fromJson(Files.readString(configFile.toPath(), StandardCharsets.UTF_8), BimbaConfig.class);
        if (c == null || c.tagRoots == null || c.tagRoots.isEmpty()) {
            throw new IllegalArgumentException("config.json must define tagRoots");
        }
        c.resolveEnv();
        return c;
    }

    /** Imports an Ignition tag export (JSON) once per file version, e.g. demo tags for the POC. */
    private void provisionTags(BimbaConfig c) {
        if (c.provisionTagsFile == null || c.provisionTagsFile.isBlank()) {
            return;
        }
        File f = new File(dataDir, c.provisionTagsFile);
        File marker = new File(dataDir, "." + f.getName() + ".imported");
        try {
            if (!f.exists() || (marker.exists() && marker.lastModified() >= f.lastModified())) {
                return;
            }
            String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            List<QualityCode> result = context.getTagManager()
                    .importTagsAsync(TagPathParser.parse(c.provisionTagsBasePath), json, "json", CollisionPolicy.MergeOverwrite)
                    .get(60, TimeUnit.SECONDS);
            long bad = result.stream().filter(q -> !q.isGood()).count();
            log.info("imported tags from {}: {} results, {} not good", f.getName(), result.size(), bad);
            Files.writeString(marker.toPath(), Instant.now().toString());
        } catch (Exception e) {
            log.error("tag provisioning from {} failed", f, e);
        }
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

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", "BIMBA IT/OT Agent");
        m.put("version", "1.4.1");
        m.put("startedAt", startedAt.toString());
        m.put("configFile", configFile.getAbsolutePath());
        m.put("configError", configError);
        BimbaConfig c = config;
        if (c != null) {
            m.put("plantId", c.plantId);
            m.put("edgeId", c.edgeId);
            m.put("tagRoots", c.tagRoots);
        }
        TagCollector col = collector;
        DeliveryEngine del = delivery;
        m.put("collector", col == null ? null : col.status());
        m.put("delivery", del == null ? null : del.status());
        return m;
    }
}
