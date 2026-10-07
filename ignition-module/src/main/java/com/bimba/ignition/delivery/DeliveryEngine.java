package com.bimba.ignition.delivery;

import com.bimba.ignition.Json;
import com.bimba.ignition.config.BimbaConfig;
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
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batches canonical messages and fans every batch out to independent routes:
 * <ul>
 *   <li><b>main</b>: primary destinations, then failovers (Boomi Event Streams, then BIMBA Ingest),
 *       with automatic failback;</li>
 *   <li><b>one route per mirror</b> (role "mirror": Kafka, webhooks, other IT receivers).</li>
 * </ul>
 * Each route has its own sender thread and disk buffer ({@code buffer/} for main,
 * {@code buffer/mirror-<name>/} for mirrors), so an outage on one never affects another.
 */
public class DeliveryEngine {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Delivery");

    private final BimbaConfig cfg;
    private final BlockingQueue<JsonObject> queue;
    private final List<Route> routes = new ArrayList<>();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong spilled = new AtomicLong();
    private volatile boolean running;
    private Thread batcher;

    public DeliveryEngine(BimbaConfig cfg, File dataDir) throws Exception {
        this.cfg = cfg;
        this.queue = new ArrayBlockingQueue<>(cfg.queueCapacity);
        File bufferRoot = new File(dataDir, "buffer");

        List<Destination> main = new ArrayList<>();
        cfg.destinations.stream()
                .filter(d -> d.enabled && d.name != null && !"mirror".equalsIgnoreCase(d.role))
                .sorted((a, b) -> Boolean.compare(!"primary".equalsIgnoreCase(a.role), !"primary".equalsIgnoreCase(b.role)))
                .forEach(d -> main.add(Destination.create(d, cfg.plantId)));
        if (!main.isEmpty()) {
            routes.add(new Route("main", main, new DiskBuffer(bufferRoot, cfg.buffer.maxMegabytes),
                    cfg.buffer.replayPerCycle));
        }
        for (BimbaConfig.Destination d : cfg.destinations) {
            if (d.enabled && d.name != null && "mirror".equalsIgnoreCase(d.role)) {
                String dir = "mirror-" + d.name.replaceAll("[^A-Za-z0-9_.-]", "_");
                routes.add(new Route(d.name, List.of(Destination.create(d, cfg.plantId)),
                        new DiskBuffer(new File(bufferRoot, dir), cfg.buffer.maxMegabytes), cfg.buffer.replayPerCycle));
            }
        }
        if (routes.isEmpty()) {
            log.warn("no destinations enabled: data will be buffered to disk until one is configured");
            routes.add(new Route("main", List.of(), new DiskBuffer(bufferRoot, cfg.buffer.maxMegabytes),
                    cfg.buffer.replayPerCycle));
        }
    }

    public void start() {
        running = true;
        routes.forEach(Route::start);
        batcher = new Thread(this::run, "BIMBA-Batcher");
        batcher.setDaemon(true);
        batcher.start();
        log.info("delivery started, routes: {}", routes.stream().map(Route::describe).toList());
    }

    public void stop() {
        running = false;
        if (batcher != null) {
            batcher.interrupt();
            try {
                batcher.join(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        List<JsonObject> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            byte[] env = envelope(rest);
            routes.forEach(r -> r.persist(env));
            log.info("stopped: {} queued messages saved to disk buffers", rest.size());
        }
        routes.forEach(Route::stop);
    }

    /** Called from tag change listeners; never blocks. */
    public void offer(JsonObject message) {
        queued.incrementAndGet();
        if (!queue.offer(message)) {
            spilled.incrementAndGet();
            List<JsonObject> one = new ArrayList<>();
            one.add(message);
            queue.drainTo(one, cfg.batch.maxMessages - 1);
            byte[] env = envelope(one);
            routes.forEach(r -> r.persist(env));
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
                byte[] env = envelope(batch); // same batchId on every route -> receivers can correlate
                for (Route r : routes) {
                    r.submit(env);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                log.error("batcher error", e);
            }
        }
    }

    private byte[] envelope(List<JsonObject> messages) {
        JsonObject env = new JsonObject();
        env.addProperty("schemaVersion", "1.0");
        env.addProperty("batchId", UUID.randomUUID().toString());
        env.addProperty("plantId", cfg.plantId);
        env.addProperty("edgeId", cfg.edgeId);
        env.addProperty("source", "ignition");
        env.addProperty("createdAt", Instant.now().toString());
        env.addProperty("count", messages.size());
        JsonArray arr = new JsonArray(messages.size());
        messages.forEach(arr::add);
        env.add("messages", arr);
        return Json.GSON.toJson(env).getBytes(StandardCharsets.UTF_8);
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        Route main = routes.stream().filter(r -> r.name().equals("main")).findFirst().orElse(routes.get(0));
        // top-level fields describe the main (Boomi -> BIMBA Ingest) route, as before
        m.put("activeDestination", main.activeDestination());
        m.put("queueDepth", queue.size());
        m.put("messagesQueued", queued.get());
        m.put("bufferedBatches", main.bufferedBatches());
        m.put("bufferedBytes", main.bufferedBytes());
        m.put("bufferDropped", main.bufferDropped());
        m.put("spilledToDisk", spilled.get());
        m.put("replayedMessages", main.replayedMessages());
        m.put("rejectedMessages", main.rejectedMessages());
        List<Map<String, Object>> rs = new ArrayList<>();
        List<Object> ds = new ArrayList<>();
        for (Route r : routes) {
            Map<String, Object> s = r.status();
            rs.add(s);
            ds.addAll((List<?>) s.get("destinations"));
        }
        m.put("routes", rs);
        m.put("destinations", ds);
        return m;
    }
}
