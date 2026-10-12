package com.bimba.agent;

import com.bimba.agent.SetuClient.SetuException;
import com.bimba.agent.SetuClient.Json;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The service (systemd unit bimba-site-agent, or `bimba-site-agent run` in a container): heartbeat loop with the
 * inventory every 10 minutes, client certificate renewal and the edge collector. Stops on SIGTERM; exits with
 * {@link #REVOKED} when the organization removed this computer (the unit does not restart on that code).
 */
final class AgentService {
    /** EX_CONFIG: systemd's RestartPreventExitStatus keeps a revoked agent stopped */
    static final int REVOKED = 78;
    private static final Duration FULL_EVERY = Duration.ofMinutes(10);

    private final CountDownLatch stop = new CountDownLatch(1);
    private final CountDownLatch done = new CountDownLatch(1);

    int run(AgentConfig cfg) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop.countDown();
            try {
                done.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // exiting anyway
            }
        }, "shutdown"));
        try {
            return loop(cfg);
        } finally {
            done.countDown();
        }
    }

    private int loop(AgentConfig cfg) throws Exception {
        Log.info("started " + AgentMain.version() + " on " + Inventory.arch() + " (Java " + System.getProperty("java.version")
                + "): agent " + cfg.agentId + ", organization " + cfg.orgSlug + ", Setu " + cfg.server);
        Session session = new Session(cfg, true);
        EdgeCollector edge = new EdgeCollector(cfg, session::client, stop);
        Log.info(session.client().usesCertificate
                ? "signing in with client certificate " + cfg.certSha256 + " (valid until " + cfg.certNotAfter + ")"
                : "signing in with the agent secret");
        edge.start();
        Instant lastFull = Instant.EPOCH;
        Instant nextCertificateTry = Instant.EPOCH;
        int failures = 0;
        try {
            while (true) {
                long wait;
                try {
                    boolean full = Duration.between(lastFull, Instant.now()).compareTo(FULL_EVERY) >= 0;
                    JsonObject resp = session.heartbeat(full ? Inventory.collect(cfg.server, cfg.hardwareSerial) : null, edge.status());
                    if (full) {
                        lastFull = Instant.now();
                    }
                    edge.onHeartbeat(resp);
                    if (failures > 0) {
                        Log.info("gateway reachable again after " + failures + " failed heartbeat(s)");
                    }
                    failures = 0;
                    wait = Math.max(15, Math.min(3600, Json.integer(resp, "heartbeatSeconds", cfg.heartbeatSeconds)));
                    if (Instant.now().isAfter(nextCertificateTry) && session.certificateDue(resp)) {
                        try {
                            boolean renewal = session.credential() != null;
                            session.updateCertificate();
                            Log.info((renewal ? "renewed the" : "received a") + " client certificate " + cfg.certSha256
                                    + ", valid until " + cfg.certNotAfter);
                        } catch (SetuException e) {
                            if (e.status == 410) {
                                throw e;
                            }
                            Log.warn("client certificate: " + e.getMessage() + " (next try in an hour)");
                            nextCertificateTry = Instant.now().plus(Duration.ofHours(1));
                        } catch (Exception e) {
                            Log.warn("client certificate: " + e.getMessage() + " (next try in an hour)");
                            nextCertificateTry = Instant.now().plus(Duration.ofHours(1));
                        }
                    }
                } catch (SetuException e) {
                    if (e.status == 410) {
                        Log.warn("the organization removed this computer: " + e.getMessage() + " Stopping.");
                        return REVOKED;
                    }
                    failures++;
                    wait = failed(e, failures, cfg);
                    lastFull = Instant.EPOCH;               // resend the details once Setu is back
                } catch (Exception e) {
                    failures++;
                    wait = failed(e, failures, cfg);
                    lastFull = Instant.EPOCH;
                }
                if (stop.await(wait, TimeUnit.SECONDS)) {
                    Log.info("service stopping");
                    return 0;
                }
            }
        } finally {
            stop.countDown();
            edge.close();
        }
    }

    private static long failed(Exception e, int failures, AgentConfig cfg) {
        if (failures == 1 || failures % 10 == 0) {
            Log.warn("heartbeat failed (" + failures + "x): " + e.getMessage());
        }
        return Math.min(600, (long) cfg.heartbeatSeconds * Math.min(failures, 5));
    }
}
