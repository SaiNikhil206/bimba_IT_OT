package com.bimba.setu.agents;

import com.bimba.setu.Db;
import com.bimba.setu.SetuService;
import com.bimba.setu.Json;
import com.bimba.setu.canonical.Ingest;
import com.bimba.setu.collect.Canonical;
import com.bimba.setu.config.BimbaConfig;
import com.bimba.setu.delivery.DeliveryEngine;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gap markers for site agents that go silent (FR-RES-03). The agent's own edge collector marks a device it cannot
 * reach; when the whole agent stops (the computer is off, the network is down) only Setu can tell. Every 30 s
 * each active agent's last heartbeat is checked: no heartbeat for 3 heartbeat intervals (as the console calls it
 * offline) sends a "lost" marker for each of its machines, the next heartbeat a "restored" one with the gap's start
 * and end. Which agents are marked as lost is kept in data/agent-gaps.json, so a restart neither repeats nor forgets one.
 */
@Service
public class AgentGaps {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Agents");

    private final Db db;
    private final SetuService setu;
    private final AgentSources sources;
    private final Ingest ingest;
    private final File stateFile;
    /** agent -> since when it is silent (its last heartbeat), for the agents a "lost" marker was sent for */
    private final Map<String, String> lost = new LinkedHashMap<>();

    public AgentGaps(Db db, SetuService setu, AgentSources sources, Ingest ingest,
                     @Value("${bimba.data-dir:data}") File dataDir) {
        this.db = db;
        this.setu = setu;
        this.sources = sources;
        this.ingest = ingest;
        this.stateFile = new File(dataDir, "agent-gaps.json");
        load();
    }

    record Agent(UUID id, String name, Instant lastSeen, int heartbeatSeconds, Instant now) {
        boolean silent() {
            return now.toEpochMilli() - lastSeen.toEpochMilli() > 3_000L * Math.max(heartbeatSeconds, 10);
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 90_000)
    public void check() {
        DeliveryEngine delivery = setu.delivery();
        if (delivery == null) {
            return;                                                    // restarting: next round
        }
        List<Agent> agents;
        try {
            agents = db.sql("SELECT id, display_name, last_seen, heartbeat_seconds, now() FROM {s}.bimba_agent"
                            + " WHERE state = 'active' AND last_seen IS NOT NULL")
                    .query((rs, n) -> new Agent(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getTimestamp(3).toInstant(),
                            rs.getInt(4), rs.getTimestamp(5).toInstant()))
                    .list();
        } catch (RuntimeException e) {
            return;                                                    // database away: nothing to compare with
        }
        boolean changed = false;
        Set<String> active = new java.util.HashSet<>();
        for (Agent a : agents) {
            String id = a.id().toString();
            active.add(id);
            if (a.silent() && !lost.containsKey(id)) {
                send(a, "start", a.lastSeen(), null, delivery);
                lost.put(id, a.lastSeen().toString());
                changed = true;
                log.warn("site agent {} sends no heartbeat since {}: gap markers sent for its machines", a.name(), a.lastSeen());
            } else if (!a.silent() && lost.containsKey(id)) {
                Instant from = Instant.parse(lost.get(id));
                send(a, "end", from, a.now(), delivery);
                lost.remove(id);
                changed = true;
                log.info("site agent {} is back: gap of {} s marked", a.name(), (a.now().toEpochMilli() - from.toEpochMilli()) / 1000);
            }
        }
        changed |= lost.keySet().removeIf(id -> !active.contains(id));   // revoked or uninstalled meanwhile
        if (changed) {
            save();
        }
    }

    private void send(Agent a, String state, Instant from, Instant to, DeliveryEngine delivery) {
        BimbaConfig c = setu.config();
        for (String machine : sources.machinesOf(a.id())) {
            ingest.fromCollector(marker(c == null ? "PLANT01" : c.plantId, c == null ? "" : c.edgeId, a, machine, state, from, to), delivery::offer);
        }
    }

    /** a gap marker as the collector makes them (collect/Collector), for a machine of a silent site agent */
    static JsonObject marker(String plantId, String edgeId, Agent a, String machine, String state, Instant from, Instant to) {
        String reason = "start".equals(state)
                ? "no heartbeat from site agent " + a.name() + " for more than " + 3 * Math.max(a.heartbeatSeconds(), 10) + " s" : null;
        JsonObject src = new JsonObject();
        src.addProperty("protocol", "agent");
        src.addProperty("system", edgeId);
        src.addProperty("address", a.name());
        src.addProperty("agentId", a.id().toString());
        return Canonical.gapMarker(plantId, machine, state, to != null ? to : a.now(), from, to, "site agent " + a.name(), reason, src);
    }

    private void load() {
        try {
            if (stateFile.isFile()) {
                JsonObject o = Json.GSON.fromJson(Files.readString(stateFile.toPath()), JsonObject.class);
                if (o != null) {
                    o.entrySet().forEach(e -> lost.put(e.getKey(), e.getValue().getAsString()));
                }
            }
        } catch (Exception e) {
            log.warn("could not read {}: {}", stateFile, e.getMessage());
        }
    }

    private void save() {
        try {
            java.nio.file.Path tmp = stateFile.toPath().resolveSibling(stateFile.getName() + ".tmp");
            Files.createDirectories(stateFile.toPath().getParent());
            Files.writeString(tmp, Json.GSON.toJson(lost));
            Files.move(tmp, stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("could not save {}: {}", stateFile, e.getMessage());
        }
    }
}
