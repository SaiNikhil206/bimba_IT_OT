package com.bimba.ignition.delivery;

import com.bimba.ignition.config.BimbaConfig;

import java.util.concurrent.atomic.AtomicLong;

/** An end system (Boomi or BIMBA Ingest) reached over REST or MQTT, with a simple circuit breaker. */
public abstract class Destination implements AutoCloseable {

    /** Thrown when the end system says the batch itself is invalid; retrying it would never succeed. */
    public static class RejectedException extends Exception {
        public RejectedException(String msg) {
            super(msg);
        }
    }

    protected final BimbaConfig.Destination cfg;
    private volatile long retryAt = 0;
    private volatile String lastError;
    private volatile long lastSuccessAt;
    private volatile long lastFailureAt;
    private volatile long lastProbeAt;
    private volatile String state = "unknown";
    final AtomicLong batches = new AtomicLong();
    final AtomicLong messages = new AtomicLong();
    final AtomicLong failures = new AtomicLong();

    protected Destination(BimbaConfig.Destination cfg) {
        this.cfg = cfg;
    }

    public static Destination create(BimbaConfig.Destination cfg, String plantId) {
        if ("mqtt".equalsIgnoreCase(cfg.transport)) {
            return new MqttDestination(cfg, plantId);
        }
        if ("kafka".equalsIgnoreCase(cfg.transport)) {
            return new KafkaDestination(cfg, plantId);
        }
        if ("eventstreams".equalsIgnoreCase(cfg.transport)) {
            return new EventStreamsDestination(cfg);
        }
        return new RestDestination(cfg);
    }

    /** Sends one batch envelope. Returns normally only when the end system has accepted it. */
    public abstract void send(byte[] batch) throws Exception;

    public abstract String endpoint();

    /** True when this destination can be health-checked while on standby (config "probe": true). */
    public boolean canProbe() {
        return false;
    }

    /** Health check without delivering data. Returns normally only when the end system is ready. */
    public void probe() throws Exception {
        throw new UnsupportedOperationException("probe not supported for " + cfg.transport);
    }

    public String name() {
        return cfg.name;
    }

    public boolean isPrimary() {
        return "primary".equalsIgnoreCase(cfg.role);
    }

    public boolean isAvailable(long now) {
        return now >= retryAt;
    }

    void markSuccess(int messageCount) {
        batches.incrementAndGet();
        messages.addAndGet(messageCount);
        lastSuccessAt = System.currentTimeMillis();
        retryAt = 0;
        state = "up";
    }

    /** Standby health check passed: reachable and ready, but nothing was delivered. */
    void markProbeOk() {
        lastProbeAt = System.currentTimeMillis();
        retryAt = 0;
        state = "up";
    }

    void markProbeFailure(String error) {
        lastProbeAt = System.currentTimeMillis();
        markFailure(error);
    }

    boolean isDown() {
        return "down".equals(state);
    }

    void markFailure(String error) {
        failures.incrementAndGet();
        lastError = error;
        lastFailureAt = System.currentTimeMillis();
        retryAt = lastFailureAt + cfg.retryAfterSeconds * 1000L;
        state = "down";
    }

    public java.util.Map<String, Object> status() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", cfg.name);
        m.put("role", cfg.role);
        m.put("transport", cfg.transport);
        m.put("endpoint", endpoint());
        m.put("state", state);
        m.put("batches", batches.get());
        m.put("messages", messages.get());
        m.put("failures", failures.get());
        m.put("lastSuccessAt", lastSuccessAt == 0 ? null : java.time.Instant.ofEpochMilli(lastSuccessAt).toString());
        m.put("lastFailureAt", lastFailureAt == 0 ? null : java.time.Instant.ofEpochMilli(lastFailureAt).toString());
        m.put("lastError", lastError);
        m.put("retryAt", retryAt == 0 ? null : java.time.Instant.ofEpochMilli(retryAt).toString());
        m.put("probe", canProbe());
        m.put("lastProbeAt", lastProbeAt == 0 ? null : java.time.Instant.ofEpochMilli(lastProbeAt).toString());
        return m;
    }

    @Override
    public void close() {
    }
}
