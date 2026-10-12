package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
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
 * <p>
 * A pipeline may drop readings it already carried and send only changed values ({@link Filters}); gap markers
 * (category "gap": a device or site agent lost and back, FR-RES-03) reach every pipeline that carries their machine,
 * whatever its tag patterns, and are sent as they come (not sampled). Each route's buffer reports when it is 70 % full
 * ({@link #onBufferAlert}, FR-RES-05).
 */
public class DeliveryEngine {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Delivery");

    /**
     * one active pipeline: what it selects, its rate (sampler null = every change) and its routes. owns = whether a machine
     * belongs to the pipeline's organization (null = every machine does), so an organization never gets another's readings.
     */
    private record Lane(BimbaConfig.Pipeline pipeline, Selection selection, java.util.function.Predicate<String> owns, Sampler sampler,
                        Route main, List<Route> mirrors, int maxMessages, long maxWaitMs, Pending pending, Filters filters, Stats stats,
                        Refresher refresher) {
        List<Route> routes() {
            List<Route> l = new ArrayList<>();
            l.add(main);
            l.addAll(mirrors);
            return l;
        }

        boolean everything() {
            return owns == null && selection.all() && pipeline.gapMarkers;
        }

        /** the readings of the batch this pipeline carries (its organization's machines, its selection) */
        List<JsonObject> take(List<JsonObject> batch) {
            return everything() ? batch : batch.stream().filter(this::carries).toList();
        }

        private boolean carries(JsonObject m) {
            String machine = Json.str(m, "machineId");
            if (owns != null && !owns.test(machine)) {
                return false;
            }
            if (isGap(m)) {
                return pipeline.gapMarkers && selection.machine(machine);
            }
            return selection.matches(m);
        }

        /** what is left after its filters: duplicates dropped; with every-change rate, unchanged values too */
        List<JsonObject> filter(List<JsonObject> mine) {
            if (!filters.any()) {
                return mine;
            }
            List<JsonObject> out = new ArrayList<>(mine.size());
            for (JsonObject m : mine) {
                if (filters.fresh(m) && (sampler != null || filters.changed(m))) {
                    out.add(m);
                }
            }
            return out;
        }
    }

    /** what a pipeline carried: readings per minute and envelope bytes per reading (for the buffer sizing formula) */
    private static final class Stats {
        final long since = System.currentTimeMillis();
        final AtomicLong readings = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();

        void add(int count, int size) {
            readings.addAndGet(count);
            bytes.addAndGet(size);
        }
    }

    /** a route's disk buffer reached 70 % of its size (high) or is below 60 % again */
    public record BufferAlert(long pipelineId, long orgId, String pipeline, String route, boolean high, int percent, long bytes,
                              long maxBytes) {
    }

    /** a gap marker: a device or site agent was lost (gap.state start) or is back (end); not a reading */
    static boolean isGap(JsonObject m) {
        return "gap".equals(Json.str(m, "category"));
    }

    /** readings a lane collected for its next envelope (batcher thread only) and since when */
    private static final class Pending {
        final List<JsonObject> items = new ArrayList<>();
        long since;
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
    private final File dataDir;
    private volatile java.util.function.Consumer<BufferAlert> bufferAlerts;

    public DeliveryEngine(BimbaConfig cfg, File dataDir, com.bimba.setu.Db db) throws Exception {
        this(cfg, dataDir, db, null);
    }

    /**
     * previous: the engine this one replaces after a configuration change (stopped with {@link #stop(BimbaConfig)}).
     * A pipeline whose rate, filters or repeat interval did not change keeps what its open window, its filters and its
     * repeats remember, so a change to one pipeline or destination does not send every tag again or cut a window short.
     */
    public DeliveryEngine(BimbaConfig cfg, File dataDir, com.bimba.setu.Db db, DeliveryEngine previous) throws Exception {
        this.cfg = cfg;
        this.queue = new ArrayBlockingQueue<>(cfg.queueCapacity);
        this.statsFile = new File(dataDir, "delivery-stats.json");
        this.dataDir = dataDir;
        File bufferRoot = new File(dataDir, "buffer");
        for (BimbaConfig.Pipeline p : cfg.pipelines) {
            // the pipeline made from the former destination roles keeps the buffers of that version (nothing is lost)
            File dir = p.legacyBuffer ? bufferRoot : new File(bufferRoot, "pipelines/" + p.id);
            // FR-RES-05: the pipeline's own buffer size (from the sizing formula), else Setu's
            int mb = p.bufferMaxMegabytes > 0 ? p.bufferMaxMegabytes : cfg.buffer.maxMegabytes;
            // file destinations keep their open window and archive here, one folder per route and destination
            File files = new File(dataDir, "files/pipelines/" + p.id);
            List<Destination> chain = new ArrayList<>();
            chain.add(Destination.create(p.primary, cfg.plantId, db, work(files, "main", p.primary)));
            p.secondary.forEach(d -> chain.add(Destination.create(d, cfg.plantId, db, work(files, "main", d))));
            Route main = new Route(p.name, chain, new DiskBuffer(p.legacyBuffer ? dir : new File(dir, "main"), mb),
                    cfg.buffer.replayPerCycle).in(p.id, p.name, "main");
            List<Route> mirrors = new ArrayList<>();
            for (BimbaConfig.Destination d : p.mirrors) {
                String sub = "mirror-" + safe(d.name);
                mirrors.add(new Route(p.name + " / " + d.name, List.of(Destination.create(d, cfg.plantId, db, work(files, sub, d))),
                        new DiskBuffer(new File(dir, sub), mb), cfg.buffer.replayPerCycle).in(p.id, p.name, "mirror"));
            }
            Lane before = previous == null ? null : previous.lanes.stream().filter(l -> l.pipeline().id == p.id).findFirst().orElse(null);
            Sampler rate = "change".equalsIgnoreCase(p.rateMode) || p.rateMode == null ? null
                    : before != null && sameRate(before.pipeline(), p) && before.sampler() != null ? before.sampler()
                    : new Sampler(p.rateMode, p.rateSeconds, System.currentTimeMillis());
            Filters filters = before != null && sameFilters(before.pipeline(), p) ? before.filters() : new Filters(p.dedupe, p.changeOnly, p.deadband);
            Refresher refresh = rate != null || p.refreshSeconds <= 0 ? null
                    : before != null && before.refresher() != null && before.refresher().seconds() == p.refreshSeconds ? before.refresher()
                    : new Refresher(p.refreshSeconds);
            // batching per pipeline (FR-CAN-06): its own size and longest wait, else Setu's
            int max = p.batchMaxMessages > 0 ? p.batchMaxMessages : Math.max(1, cfg.batch.maxMessages);
            long wait = p.batchMaxWaitMs > 0 ? p.batchMaxWaitMs : Math.max(1, cfg.batch.maxWaitMs);
            Lane lane = new Lane(p, new Selection(p.machines, p.tagInclude, p.tagExclude), owner(cfg, p.orgId), rate, main, mirrors,
                    max, wait, new Pending(), filters, before != null ? before.stats() : new Stats(), refresh);
            for (Route r : lane.routes()) {
                r.alerting((route, high, percent) -> {
                    java.util.function.Consumer<BufferAlert> l = bufferAlerts;
                    if (l != null) {
                        l.accept(new BufferAlert(p.id, p.orgId, p.name, route.name(), high, percent, route.bufferedBytes(),
                                route.buffer().maxBytes()));
                    }
                });
            }
            lanes.add(lane);
        }
        if (lanes.isEmpty()) {
            log.warn("no active pipeline: machine data is not delivered until a pipeline is activated");
        }
        loadStats();
    }

    /**
     * The machines of the organization: assigned to it, or not assigned and it is the default organization. Assignments
     * made after the engine started (cfg.recentOwners, e.g. a new site agent source) count at once. null (no check)
     * for a pipeline from before organizations (orgId 0), or when every machine is the organization's and no later
     * assignment is tracked.
     */
    static java.util.function.Predicate<String> owner(BimbaConfig cfg, long orgId) {
        java.util.Map<String, Long> owners = cfg.machineOwners == null ? java.util.Map.of() : cfg.machineOwners;
        java.util.Map<String, Long> recent = cfg.recentOwners;
        boolean isDefault = cfg.defaultOrgId != null && cfg.defaultOrgId == orgId;
        if (orgId == 0 || recent == null && isDefault && owners.values().stream().allMatch(o -> o == orgId)) {
            return null;
        }
        return machine -> {
            Long o = recent == null ? null : recent.get(machine);
            if (o == null) {
                o = owners.get(machine);
            } else if (o == com.bimba.setu.security.OrgService.UNASSIGNED) {
                o = null;
            }
            return o != null ? o == orgId : isDefault;
        };
    }

    /** a snapshot / window rate that did not change (its open window can go on) */
    private static boolean sameRate(BimbaConfig.Pipeline a, BimbaConfig.Pipeline b) {
        return a.rateMode != null && a.rateMode.equalsIgnoreCase(b.rateMode) && a.rateSeconds == b.rateSeconds;
    }

    private static boolean sameFilters(BimbaConfig.Pipeline a, BimbaConfig.Pipeline b) {
        return a.dedupe == b.dedupe && a.changeOnly == b.changeOnly && a.deadband == b.deadband;
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** the work folder of a file destination on a route */
    private static File work(File files, String route, BimbaConfig.Destination d) {
        return new File(files, route + "/" + safe(d.name));
    }

    /** who is told when a route's buffer is 70 % full and when it is back below 60 %; set before start() */
    public void onBufferAlert(java.util.function.Consumer<BufferAlert> listener) {
        this.bufferAlerts = listener;
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
        if (lanes.stream().anyMatch(l -> l.sampler() != null || l.refresher() != null)) {
            sampler = new Thread(this::sample, "BIMBA-Sampler");
            sampler.setDaemon(true);
            sampler.start();
        }
        log.info("delivery started, pipelines: {}", lanes.stream().map(l -> l.pipeline().name + " "
                + l.routes().stream().map(Route::describe).toList()).toList());
    }

    public void stop() {
        stop(null);
    }

    /**
     * next = the configuration the next engine runs: the open windows of pipelines whose rate stays the same are left
     * open for it (it takes them over); all others are closed and their partial values kept on disk. null = close all.
     */
    public void stop(BimbaConfig next) {
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
        int collected = 0;
        for (Lane l : lanes) {                       // what the pipelines collected for their next batch
            collected += l.pending().items.size();
            flush(l, true);
        }
        if (!rest.isEmpty()) {
            dispatch(rest, true);
        }
        if (!rest.isEmpty() || collected > 0) {
            log.info("stopped: {} queued and {} collected messages saved to disk buffers", rest.size(), collected);
        }
        for (Lane l : lanes) {
            boolean handedOver = next != null && next.pipelines.stream().anyMatch(n -> n.id == l.pipeline().id && sameRate(l.pipeline(), n));
            if (l.sampler() != null && !handedOver) {
                List<JsonObject> partial = sampled(l, l.sampler().rest(System.currentTimeMillis()));
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

    /**
     * The batcher: takes readings from the queue and gives each pipeline the ones it selects; a pipeline sends its batch
     * when it holds maxMessages readings or its oldest one waited maxWaitMs (both per pipeline).
     */
    private void run() {
        while (running) {
            try {
                long now = System.currentTimeMillis();
                JsonObject first = queue.poll(Math.max(1, nextDue(now) - now), TimeUnit.MILLISECONDS);
                if (first != null) {
                    List<JsonObject> got = new ArrayList<>();
                    got.add(first);
                    queue.drainTo(got, 10_000);
                    collect(got);
                }
                now = System.currentTimeMillis();
                for (Lane l : lanes) {
                    if (!l.pending().items.isEmpty() && now - l.pending().since >= l.maxWaitMs()) {
                        flush(l, false);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                log.error("batcher error", e);
            }
        }
    }

    /** when the next partly filled batch is due (at most a second from now, so stopping is noticed) */
    private long nextDue(long now) {
        long due = now + 1000;
        for (Lane l : lanes) {
            if (!l.pending().items.isEmpty()) {
                due = Math.min(due, l.pending().since + l.maxWaitMs());
            }
        }
        return due;
    }

    /** readings into the pipelines' next batches (full ones go at once); rate pipelines collect for their sampler */
    void collect(List<JsonObject> batch) {
        Set<JsonObject> routed = lanes.size() == 1 && lanes.get(0).everything() ? null
                : java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        boolean any = false;
        long now = System.currentTimeMillis();
        for (Lane lane : lanes) {
            List<JsonObject> mine = lane.take(batch);
            if (mine.isEmpty()) {
                continue;
            }
            any = true;
            if (routed != null) {
                routed.addAll(mine);
            }
            mine = toSampler(lane, lane.filter(mine));
            if (mine.isEmpty()) {
                continue;
            }
            Pending p = lane.pending();
            if (p.items.isEmpty()) {
                p.since = now;
            }
            p.items.addAll(mine);
            while (p.items.size() >= lane.maxMessages()) {
                send(lane, new ArrayList<>(p.items.subList(0, lane.maxMessages())), false);
                p.items.subList(0, lane.maxMessages()).clear();
                p.since = now;
            }
        }
        if (!any) {
            notRouted.addAndGet(batch.size());
        } else if (routed != null) {
            notRouted.addAndGet(batch.size() - routed.size());
        }
    }

    /** readings of a rate pipeline go to its sampler; what is left (gap markers, or all for every-change) is returned */
    private static List<JsonObject> toSampler(Lane lane, List<JsonObject> mine) {
        if (lane.sampler() == null || mine.isEmpty()) {
            return mine;
        }
        List<JsonObject> events = new ArrayList<>();
        List<JsonObject> readings = new ArrayList<>(mine.size());
        for (JsonObject m : mine) {
            (isGap(m) ? events : readings).add(m);
        }
        if (!readings.isEmpty()) {
            lane.sampler().add(readings);
        }
        return events;
    }

    /** what a rate pipeline's sampler made, after its change-only filter */
    private static List<JsonObject> sampled(Lane lane, List<JsonObject> made) {
        return lane.filters().any() && !made.isEmpty() ? made.stream().filter(m -> lane.filters().changed(m)).toList() : made;
    }

    private void flush(Lane lane, boolean toDisk) {
        Pending p = lane.pending();
        if (!p.items.isEmpty()) {
            send(lane, new ArrayList<>(p.items), toDisk);
            p.items.clear();
        }
    }

    /** the readings as envelopes of at most the pipeline's batch size, to each of its routes (same batchId) */
    private void send(Lane lane, List<JsonObject> messages, boolean toDisk) {
        for (int i = 0; i < messages.size(); i += lane.maxMessages()) {
            List<JsonObject> part = messages.subList(i, Math.min(messages.size(), i + lane.maxMessages()));
            byte[] env = envelope(part);
            lane.stats().add(part.size(), env.length);
            if (lane.refresher() != null) {
                lane.refresher().sent(part, System.currentTimeMillis());
            }
            for (Route r : lane.routes()) {
                if (toDisk) {
                    r.persist(env);
                } else {
                    r.submit(env);
                }
            }
        }
    }

    /** every second: the snapshots / windows of the pipelines whose period ended, and the repeats that are due */
    private void sample() {
        while (running) {
            try {
                Thread.sleep(1000);
                long now = System.currentTimeMillis();
                for (Lane l : lanes) {
                    if (l.sampler() != null) {
                        send(l, sampled(l, l.sampler().due(now)), false);
                    } else if (l.refresher() != null) {
                        send(l, l.refresher().due(now), false);         // unchanged tags again (every-change pipelines)
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
     * Straight to the routes, without waiting for a pipeline's batch to fill: on shutdown and when the queue overflows
     * (toDisk). Rate pipelines collect them for their next snapshot / average instead.
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
            mine = toSampler(lane, lane.filter(mine));
            if (!mine.isEmpty()) {
                send(lane, mine, toDisk);
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
        env.addProperty("schemaVersion", com.bimba.setu.collect.Canonical.VERSION);
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
            p.put("batchMaxMessages", l.maxMessages());
            p.put("batchMaxWaitMs", l.maxWaitMs());
            p.put("filters", l.filters().status());
            p.put("gapMarkers", l.pipeline().gapMarkers);
            p.put("refreshSeconds", l.refresher() == null ? 0 : l.refresher().seconds());
            // FR-RES-05: what the sizing formula needs, and how full the buffers are
            double minutes = Math.max(1, (System.currentTimeMillis() - l.stats().since) / 60_000.0);
            long carried = l.stats().readings.get();
            p.put("readingsPerMinute", Math.round(carried / minutes * 10) / 10.0);
            p.put("bytesPerReading", carried == 0 ? null : l.stats().bytes.get() / carried);
            p.put("bufferMaxBytes", l.main().buffer().maxBytes());
            p.put("bufferUsedPercent", l.routes().stream().mapToInt(r -> r.buffer().usedPercent()).max().orElse(0));
            p.put("bufferAlert", l.routes().stream().anyMatch(r -> r.buffer().high()));
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
        m.put("bufferAlerts", rs.stream().filter(r -> Boolean.TRUE.equals(r.get("bufferAlert"))).count());
        m.put("diskFreeBytes", dataDir.getUsableSpace());
        m.put("routes", rs);
        m.put("destinations", ds);
        return m;
    }
}
