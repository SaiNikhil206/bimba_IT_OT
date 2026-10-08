package com.bimba.gateway.delivery;

import com.bimba.gateway.Json;
import com.bimba.gateway.config.BimbaConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batches canonical messages and hands every batch to the active pipelines. Each pipeline takes the readings it selects
 * (machines + tag patterns, {@link Selection}), at its rate (every change, or a snapshot / average every N seconds,
 * {@link Sampler}) and delivers them on independent routes:
 * <ul>
 *   <li><b>main</b>: its primary destination, then its secondaries in order, with automatic failback;</li>
 *   <li><b>one route per mirror</b>: an independent copy (Kafka, Splunk, webhooks, the BIMBA database, ...).</li>
 * </ul>
 * Each route has its own sender thread and disk buffer ({@code buffer/pipelines/<id>/main} and
 * {@code .../mirror-<name>}), so an outage on one never affects another. Without an active pipeline nothing is sent.
 */
public class DeliveryEngine {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Delivery");

    /**
     * one active pipeline: what it selects, its rate (sampler null = every change) and its routes. owns = whether a machine
     * belongs to the pipeline's organization (null = every machine does), so an organization never gets another's readings.
     */
    private record Lane(BimbaConfig.Pipeline pipeline, Selection selection, java.util.function.Predicate<String> owns, Sampler sampler,
                        Route main, List<Route> mirrors) {
        List<Route> routes() {
            List<Route> l = new ArrayList<>();
            l.add(main);
            l.addAll(mirrors);
            return l;
        }

        boolean everything() {
            return owns == null && selection.all();
        }

        List<JsonObject> take(List<JsonObject> batch) {
            return everything() ? batch
                    : batch.stream().filter(m -> (owns == null || owns.test(Json.str(m, "machineId"))) && selection.matches(m)).toList();
        }
    }

    private final BimbaConfig cfg;
    private final BlockingQueue<JsonObject> queue;
    private final List<Lane> lanes = new ArrayList<>();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong spilled = new AtomicLong();
    private final AtomicLong notRouted = new AtomicLong();
    private volatile boolean running;
    private Thread batcher;
    private Thread sampler;
    private Thread stats;
    private final File statsFile;

    public DeliveryEngine(BimbaConfig cfg, File dataDir, com.bimba.gateway.Db db) throws Exception {
        this.cfg = cfg;
        this.queue = new ArrayBlockingQueue<>(cfg.queueCapacity);
        this.statsFile = new File(dataDir, "delivery-stats.json");
        File bufferRoot = new File(dataDir, "buffer");
        for (BimbaConfig.Pipeline p : cfg.pipelines) {
            // the pipeline made from the former destination roles keeps the buffers of that version (nothing is lost)
            File dir = p.legacyBuffer ? bufferRoot : new File(bufferRoot, "pipelines/" + p.id);
            List<Destination> chain = new ArrayList<>();
            chain.add(Destination.create(p.primary, cfg.plantId, db));
            p.secondary.forEach(d -> chain.add(Destination.create(d, cfg.plantId, db)));
            Route main = new Route(p.name, chain, new DiskBuffer(p.legacyBuffer ? dir : new File(dir, "main"), cfg.buffer.maxMegabytes),
                    cfg.buffer.replayPerCycle).in(p.id, p.name, "main");
            List<Route> mirrors = new ArrayList<>();
            for (BimbaConfig.Destination d : p.mirrors) {
                String sub = "mirror-" + d.name.replaceAll("[^A-Za-z0-9_.-]", "_");
                mirrors.add(new Route(p.name + " / " + d.name, List.of(Destination.create(d, cfg.plantId, db)),
                        new DiskBuffer(new File(dir, sub), cfg.buffer.maxMegabytes), cfg.buffer.replayPerCycle).in(p.id, p.name, "mirror"));
            }
            Sampler rate = "change".equalsIgnoreCase(p.rateMode) || p.rateMode == null ? null
                    : new Sampler(p.rateMode, p.rateSeconds, System.currentTimeMillis());
            lanes.add(new Lane(p, new Selection(p.machines, p.tagInclude, p.tagExclude), owner(cfg, p.orgId), rate, main, mirrors));
        }
        if (lanes.isEmpty()) {
            log.warn("no active pipeline: machine data is not delivered until a pipeline is activated");
        }
        loadStats();
    }

    /**
     * The machines of the organization: assigned to it, or not assigned and it is the default organization. null (no check)
     * when every machine is the organization's, or for a pipeline from before organizations (orgId 0).
     */
    static java.util.function.Predicate<String> owner(BimbaConfig cfg, long orgId) {
        java.util.Map<String, Long> owners = cfg.machineOwners == null ? java.util.Map.of() : cfg.machineOwners;
        boolean isDefault = cfg.defaultOrgId != null && cfg.defaultOrgId == orgId;
        if (orgId == 0 || isDefault && owners.values().stream().allMatch(o -> o == orgId)) {
            return null;
        }
        return machine -> {
            Long o = owners.get(machine);
            return o != null ? o == orgId : isDefault;
        };
    }

    // ------------------------------------------------------------------ counters kept over restarts

    private static String key(Lane l, Route r, Destination d) {
        return l.pipeline().id + "|" + r.kind() + "|" + d.name();
    }

    private void loadStats() {
        if (!statsFile.isFile()) {
            return;
        }
        try {
            JsonObject all = Json.GSON.fromJson(java.nio.file.Files.readString(statsFile.toPath()), JsonObject.class);
            for (Lane l : lanes) {
                for (Route r : l.routes()) {
                    for (Destination d : r.destinations()) {
                        if (all != null && all.has(key(l, r, d))) {
                            d.restore(all.getAsJsonObject(key(l, r, d)));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("could not read {}: {}", statsFile, e.getMessage());
        }
    }

    private void saveStats() {
        Map<String, Object> all = new LinkedHashMap<>();
        for (Lane l : lanes) {
            for (Route r : l.routes()) {
                for (Destination d : r.destinations()) {
                    all.put(key(l, r, d), d.counters());
                }
            }
        }
        if (all.isEmpty()) {
            return;
        }
        try {
            // merge: counters of pipelines that are paused right now are kept for when they come back
            JsonObject merged = statsFile.isFile() ? Json.GSON.fromJson(java.nio.file.Files.readString(statsFile.toPath()), JsonObject.class) : null;
            if (merged == null) {
                merged = new JsonObject();
            }
            for (Map.Entry<String, Object> e : all.entrySet()) {
                merged.add(e.getKey(), Json.GSON.toJsonTree(e.getValue()));
            }
            java.nio.file.Path tmp = statsFile.toPath().resolveSibling(statsFile.getName() + ".tmp");
            java.nio.file.Files.writeString(tmp, Json.GSON.toJson(merged));
            java.nio.file.Files.move(tmp, statsFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("could not save {}: {}", statsFile, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ rejected batches (console)

    private Lane lane(long pipelineId) {
        return lanes.stream().filter(l -> l.pipeline().id == pipelineId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("pipeline " + pipelineId + " is not active"));
    }

    private Route route(long pipelineId, int route) {
        List<Route> rs = lane(pipelineId).routes();
        if (route < 0 || route >= rs.size()) {
            throw new IllegalArgumentException("no route " + route);
        }
        return rs.get(route);
    }

    /** the rejected batches of an active pipeline, each with route (index), routeName and kind */
    public List<Map<String, Object>> rejected(long pipelineId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Route> rs = lane(pipelineId).routes();
        for (int i = 0; i < rs.size(); i++) {
            for (Map<String, Object> m : rs.get(i).buffer().rejected()) {
                m.put("route", i);
                m.put("routeName", rs.get(i).name());
                m.put("kind", rs.get(i).kind());
                out.add(m);
            }
        }
        return out;
    }

    public String rejectedText(long pipelineId, int route, String file) throws java.io.IOException {
        return route(pipelineId, route).buffer().rejectedText(file, 64 * 1024);
    }

    public void resend(long pipelineId, int route, String file) throws java.io.IOException {
        route(pipelineId, route).buffer().resend(file);
    }

    public void discard(long pipelineId, int route, String file) throws java.io.IOException {
        route(pipelineId, route).buffer().discard(file);
    }

    private List<Route> routes() {
        return lanes.stream().flatMap(l -> l.routes().stream()).toList();
    }

    public void start() {
        running = true;
        routes().forEach(Route::start);
        batcher = new Thread(this::run, "BIMBA-Batcher");
        batcher.setDaemon(true);
        batcher.start();
        stats = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(30_000);
                    saveStats();
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "BIMBA-Stats");
        stats.setDaemon(true);
        stats.start();
        if (lanes.stream().anyMatch(l -> l.sampler() != null)) {
            sampler = new Thread(this::sample, "BIMBA-Sampler");
            sampler.setDaemon(true);
            sampler.start();
        }
        log.info("delivery started, pipelines: {}", lanes.stream().map(l -> l.pipeline().name + " "
                + l.routes().stream().map(Route::describe).toList()).toList());
    }

    public void stop() {
        running = false;
        for (Thread t : new Thread[]{batcher, sampler, stats}) {
            if (t != null) {
                t.interrupt();
                try {
                    t.join(5000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        List<JsonObject> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            dispatch(rest, true);
            log.info("stopped: {} queued messages saved to disk buffers", rest.size());
        }
        for (Lane l : lanes) {
            if (l.sampler() != null) {
                List<JsonObject> partial = l.sampler().rest(System.currentTimeMillis());
                if (!partial.isEmpty()) {
                    byte[] env = envelope(partial);
                    l.routes().forEach(r -> r.persist(env));
                }
            }
        }
        routes().forEach(Route::stop);
        saveStats();
    }

    /** Called from tag change listeners; never blocks. */
    public void offer(JsonObject message) {
        queued.incrementAndGet();
        if (!queue.offer(message)) {
            spilled.incrementAndGet();
            List<JsonObject> one = new ArrayList<>();
            one.add(message);
            queue.drainTo(one, cfg.batch.maxMessages - 1);
            dispatch(one, true);
        }
    }

    private void run() {
        while (running) {
            try {
                JsonObject first = queue.poll(cfg.batch.maxWaitMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<JsonObject> batch = new ArrayList<>();
                batch.add(first);
                long deadline = System.currentTimeMillis() + cfg.batch.maxWaitMs;
                while (batch.size() < cfg.batch.maxMessages) {
                    long left = deadline - System.currentTimeMillis();
                    JsonObject next = left > 0 ? queue.poll(left, TimeUnit.MILLISECONDS) : null;
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }
                dispatch(batch, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                log.error("batcher error", e);
            }
        }
    }

    /** every second: the snapshots / averages of the pipelines whose period ended */
    private void sample() {
        while (running) {
            try {
                Thread.sleep(1000);
                long now = System.currentTimeMillis();
                for (Lane l : lanes) {
                    if (l.sampler() == null) {
                        continue;
                    }
                    List<JsonObject> due = l.sampler().due(now);
                    for (int i = 0; i < due.size(); i += cfg.batch.maxMessages) {
                        byte[] env = envelope(due.subList(i, Math.min(due.size(), i + cfg.batch.maxMessages)));
                        l.routes().forEach(r -> r.submit(env));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                log.error("sampler error", e);
            }
        }
    }

    /**
     * Each pipeline gets one envelope with the readings it selects (same batchId on its routes); pipelines with a rate
     * collect them for their next snapshot / average instead. toDisk on shutdown / overflow.
     */
    private void dispatch(List<JsonObject> batch, boolean toDisk) {
        Set<JsonObject> routed = lanes.size() == 1 && lanes.get(0).everything() ? null
                : java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        boolean any = false;
        for (Lane lane : lanes) {
            List<JsonObject> mine = lane.take(batch);
            if (mine.isEmpty()) {
                continue;
            }
            any = true;
            if (routed != null) {
                routed.addAll(mine);
            }
            if (lane.sampler() != null) {
                lane.sampler().add(mine);
                continue;
            }
            byte[] env = envelope(mine);
            for (Route r : lane.routes()) {
                if (toDisk) {
                    r.persist(env);
                } else {
                    r.submit(env);
                }
            }
        }
        if (!any) {
            notRouted.addAndGet(batch.size());
        } else if (routed != null) {
            notRouted.addAndGet(batch.size() - routed.size());
        }
    }

    private byte[] envelope(List<JsonObject> messages) {
        JsonObject env = new JsonObject();
        env.addProperty("schemaVersion", "1.0");
        env.addProperty("batchId", UUID.randomUUID().toString());
        env.addProperty("plantId", cfg.plantId);
        env.addProperty("edgeId", cfg.edgeId);
        env.addProperty("source", "bimba-gateway");
        env.addProperty("createdAt", Instant.now().toString());
        env.addProperty("count", messages.size());
        JsonArray arr = new JsonArray(messages.size());
        messages.forEach(arr::add);
        env.add("messages", arr);
        return Json.GSON.toJson(env).getBytes(StandardCharsets.UTF_8);
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        // top-level fields: the first pipeline's main route (what the previous version reported), totals of all main routes
        m.put("activeDestination", lanes.isEmpty() ? null : lanes.get(0).main().activeDestination());
        m.put("queueDepth", queue.size());
        m.put("messagesQueued", queued.get());
        m.put("bufferedBatches", lanes.stream().mapToInt(l -> l.main().bufferedBatches()).sum());
        m.put("bufferedBytes", lanes.stream().mapToLong(l -> l.main().bufferedBytes()).sum());
        m.put("bufferDropped", lanes.stream().mapToLong(l -> l.main().bufferDropped()).sum());
        m.put("spilledToDisk", spilled.get());
        m.put("notRoutedMessages", notRouted.get());
        m.put("replayedMessages", lanes.stream().mapToLong(l -> l.main().replayedMessages()).sum());
        m.put("rejectedMessages", lanes.stream().mapToLong(l -> l.main().rejectedMessages()).sum());
        List<Map<String, Object>> ps = new ArrayList<>();
        List<Map<String, Object>> rs = new ArrayList<>();
        List<Object> ds = new ArrayList<>();
        for (Lane l : lanes) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", l.pipeline().id);
            p.put("orgId", l.pipeline().orgId);
            p.put("name", l.pipeline().name);
            p.put("machines", l.pipeline().machines);
            p.put("tagInclude", l.pipeline().tagInclude);
            p.put("tagExclude", l.pipeline().tagExclude);
            p.put("rateMode", l.sampler() == null ? "change" : l.pipeline().rateMode);
            p.put("rateSeconds", l.pipeline().rateSeconds);
            p.put("activeDestination", l.main().activeDestination());
            p.put("bufferedBatches", l.routes().stream().mapToInt(Route::bufferedBatches).sum());
            p.put("rejectedBatches", l.routes().stream().mapToInt(r -> r.buffer().rejectedCount()).sum());
            List<Map<String, Object>> own = new ArrayList<>();
            for (Route r : l.routes()) {
                Map<String, Object> s = r.status();
                own.add(s);
                rs.add(s);
                ds.addAll((List<?>) s.get("destinations"));
            }
            p.put("routes", own);
            ps.add(p);
        }
        m.put("pipelines", ps);
        m.put("routes", rs);
        m.put("destinations", ds);
        return m;
    }
}
