package com.bimba.gateway.delivery;

import com.bimba.gateway.Json;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One independent delivery path with its own sender thread and disk buffer.
 *
 * Every pipeline has one "main" route (its primary first, then its secondaries in order) and one "mirror" route per
 * mirror destination. Every batch of the pipeline is copied to each of its routes, so a slow or broken mirror never
 * delays the main route, and one pipeline never holds up another.
 */
public class Route {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Delivery");

    private final String name;
    private final List<Destination> destinations;
    private final DiskBuffer buffer;
    private final int replayPerCycle;
    private final BlockingQueue<byte[]> pending = new ArrayBlockingQueue<>(2000);
    private final AtomicLong replayed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile String activeDestination;
    /** the last refusal (worker thread only): who and why, kept with the rejected batch */
    private String rejectedBy;
    private String rejectReason;
    private long pipelineId;
    private String pipeline = "";
    private String kind = "main";
    private volatile boolean running;
    private Thread worker;
    private Thread prober;

    public Route(String name, List<Destination> destinations, DiskBuffer buffer, int replayPerCycle) {
        this.name = name;
        this.destinations = destinations;
        this.buffer = buffer;
        this.replayPerCycle = replayPerCycle;
    }

    public String name() {
        return name;
    }

    /** the pipeline this route belongs to; kind = main | mirror */
    public Route in(long pipelineId, String pipeline, String kind) {
        this.pipelineId = pipelineId;
        this.pipeline = pipeline;
        this.kind = kind;
        return this;
    }

    /** e.g. main[boomi-eventstreams/eventstreams -> bimba-ingest/rest] (buffered: 0) */
    public String describe() {
        return name + destinations.stream().map(d -> d.name() + "/" + d.cfg.transport).toList()
                .toString().replace(", ", " -> ") + " (buffered: " + buffer.count() + ")";
    }

    public void start() {
        running = true;
        worker = new Thread(this::run, "BIMBA-Route-" + name);
        worker.setDaemon(true);
        worker.start();
        if (destinations.stream().anyMatch(Destination::canProbe)) {
            prober = new Thread(this::probeLoop, "BIMBA-Probe-" + name);
            prober.setDaemon(true);
            prober.start();
        }
    }

    /** Hands a batch to this route; never blocks the batcher. */
    public void submit(byte[] batch) {
        if (!pending.offer(batch)) {
            buffer.write(batch); // route is far behind: keep it on disk
        }
    }

    /** Writes a batch straight to this route's disk buffer (used on shutdown). */
    public void persist(byte[] batch) {
        buffer.write(batch);
    }

    public void stop() {
        running = false;
        if (prober != null) {
            prober.interrupt();
        }
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        List<byte[]> rest = new ArrayList<>();
        pending.drainTo(rest);
        rest.forEach(buffer::write);
        destinations.forEach(Destination::close);
    }

    private void run() {
        while (running) {
            try {
                byte[] batch = pending.poll(1, TimeUnit.SECONDS);
                boolean up = true;
                if (batch != null) {
                    int count = countMessages(batch);
                    Result r = deliver(batch, count);
                    if (r == Result.FAILED) {
                        buffer.write(batch);
                        up = false;
                    } else if (r == Result.REJECTED) {
                        buffer.reject(batch, rejectedBy, rejectReason, count);   // kept for the console, never lost
                    }
                }
                if (up) {
                    replayBacklog();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                log.error("route '{}' loop error", name, e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Health-checks destinations that are NOT carrying the data (a standby failover). Without this a
     * failover is only tried when the primary fails, so its state stays stale ("unknown" or an old "down")
     * and a broken failover is only noticed when it is needed. Runs on its own thread: a slow standby
     * never delays live delivery.
     */
    private void probeLoop() {
        while (running) {
            long sleepMs = 30_000;
            for (Destination d : destinations) {
                if (!d.canProbe()) {
                    continue;
                }
                sleepMs = Math.min(sleepMs, Math.max(5, d.cfg.probeEverySeconds) * 1000L);
                if (d.name().equals(activeDestination)) {
                    continue; // real batches already tell us how it is doing
                }
                boolean wasDown = d.isDown();
                try {
                    d.probe();
                    d.markProbeOk();
                    if (wasDown) {
                        log.info("route '{}': standby '{}' is reachable again (probe OK)", name, d.name());
                    }
                } catch (Throwable e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    d.markProbeFailure(msg);
                    if (!wasDown) {
                        log.warn("route '{}': standby '{}' failed its probe ({})", name, d.name(), msg);
                    }
                }
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void replayBacklog() throws Exception {
        for (int i = 0; i < replayPerCycle && running; i++) {
            Optional<File> f = buffer.oldest();
            if (f.isEmpty()) {
                return;
            }
            byte[] body = Files.readAllBytes(f.get().toPath());
            int count = countMessages(body);
            Result r = deliver(body, count);
            if (r == Result.DELIVERED) {
                buffer.delete(f.get());
                replayed.addAndGet(count);
            } else if (r == Result.REJECTED) {
                buffer.reject(f.get(), rejectedBy, rejectReason, count);
            } else {
                return;
            }
        }
    }

    private enum Result { DELIVERED, REJECTED, FAILED }

    /**
     * Tries the destinations in order. A refusal (invalid for that end system) does not stop the others: a secondary may
     * accept what the primary refused. DELIVERED = one accepted; FAILED = none accepted and at least one was down or
     * waiting to retry (kept on disk, tried again); REJECTED = every one that could be tried refused it.
     */
    private Result deliver(byte[] body, int count) {
        long now = System.currentTimeMillis();
        boolean unreachable = false;
        rejectedBy = null;
        rejectReason = null;
        for (Destination d : destinations) {
            if (!d.isAvailable(now)) {
                unreachable = true;
                continue;
            }
            try {
                d.sendAll(body);
                d.markSuccess(count);
                if (!d.name().equals(activeDestination)) {
                    log.info("route '{}': delivering to '{}' ({}{})", name, d.name(), d.cfg.role,
                            d.isPrimary() || "mirror".equalsIgnoreCase(d.cfg.role) ? "" : " - primary unavailable");
                    activeDestination = d.name();
                }
                return Result.DELIVERED;
            } catch (Destination.RejectedException e) {
                d.markRejected(e.getMessage());
                log.error("route '{}': '{}' refused a batch of {} messages as invalid: {}", name, d.name(), count, e.getMessage());
                if (rejectedBy == null) {
                    rejectedBy = d.name();
                    rejectReason = e.getMessage();
                }
            } catch (Throwable e) { // incl. LinkageError from a broken client library: never kill the route
                unreachable = true;
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                boolean wasUp = !"down".equals(d.status().get("state"));
                d.markFailure(msg);
                if (wasUp) {
                    log.warn("route '{}': '{}' is DOWN ({}); retrying in {}s, then less often", name, d.name(), msg,
                            d.cfg.retryAfterSeconds);
                }
            }
        }
        if (rejectedBy != null && !unreachable) {
            rejected.addAndGet(count);
            return Result.REJECTED;
        }
        if (activeDestination != null) {
            log.warn("route '{}': no destination reachable, buffering to disk", name);
            activeDestination = null;
        }
        return Result.FAILED;
    }

    static int countMessages(byte[] body) {
        try {
            JsonObject o = Json.GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
            return o.has("count") ? o.get("count").getAsInt() : o.getAsJsonArray("messages").size();
        } catch (Exception e) {
            return 0;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("pipelineId", pipelineId);
        m.put("pipeline", pipeline);
        m.put("kind", kind);
        m.put("activeDestination", activeDestination);
        m.put("pendingBatches", pending.size());
        m.put("bufferedBatches", buffer.count());
        m.put("bufferedBytes", buffer.bytes());
        m.put("bufferDropped", buffer.dropped());
        m.put("replayedMessages", replayed.get());
        m.put("rejectedMessages", rejected.get());
        m.put("rejectedBatches", buffer.rejectedCount());
        List<Map<String, Object>> ds = new ArrayList<>();
        for (Destination d : destinations) {
            Map<String, Object> s = d.status();
            s.put("route", name);
            s.put("pipelineId", pipelineId);
            s.put("pipeline", pipeline);
            ds.add(s);
        }
        m.put("destinations", ds);
        return m;
    }

    public DiskBuffer buffer() {
        return buffer;
    }

    List<Destination> destinations() {
        return destinations;
    }

    String kind() {
        return kind;
    }

    int bufferedBatches() {
        return buffer.count();
    }

    long bufferedBytes() {
        return buffer.bytes();
    }

    long bufferDropped() {
        return buffer.dropped();
    }

    long replayedMessages() {
        return replayed.get();
    }

    long rejectedMessages() {
        return rejected.get();
    }

    String activeDestination() {
        return activeDestination;
    }
}
