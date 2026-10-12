package com.bimba.agent;

import com.bimba.agent.SetuClient.SetuException;
import com.bimba.agent.SetuClient.Json;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * The agent's connection to Setu and its credential: the client certificate once it has one, before that the
 * secret from enrollment. Gets the first certificate when Setu asks for it and renews it in time.
 */
final class Session {
    final AgentConfig cfg;
    private final boolean persist;
    private volatile SetuClient client;
    private volatile AgentCertificate.Credential credential;

    /** @param persist false = test run: nothing is written to the installed configuration */
    Session(AgentConfig cfg, boolean persist) {
        this.cfg = cfg;
        this.persist = persist;
        if (cfg.certFile != null) {
            try {
                credential = AgentCertificate.load(cfg.certPath(), cfg.keyStorePassword);
            } catch (Exception e) {
                Log.warn("the client certificate " + cfg.certPath() + " cannot be read: " + e.getMessage());
            }
            if (credential == null) {
                Log.warn("the client certificate " + cfg.certPath() + " is missing. Reinstall the agent if Setu refuses it.");
            }
        }
        client = new SetuClient(cfg.server, cfg.pinnedCertSha256, credential == null ? null : credential.keyManagers());
    }

    SetuClient client() {
        return client;
    }

    AgentCertificate.Credential credential() {
        return credential;
    }

    JsonObject heartbeat(Map<String, Object> machine, Map<String, Object> collector) throws Exception {
        SetuClient c = client;
        JsonObject resp = c.heartbeat(cfg, machine, "running", collector);
        if (persist && c.usesCertificate && cfg.secret != null) {
            // the certificate works: Setu no longer accepts the secret, so it is not kept either
            cfg.secret = null;
            cfg.save();
            Log.info("signed in with the client certificate; the agent secret was removed from this computer");
        }
        return resp;
    }

    /** no certificate yet and Setu offers one, or the current one is due for renewal */
    boolean certificateDue(JsonObject heartbeatAnswer) {
        return credential == null
                ? Json.bool(heartbeatAnswer, "certificateWanted")
                : AgentCertificate.needsRenewal(credential.certificate());
    }

    /**
     * New key + certificate. The configuration points to the new certificate before it is first used. Setu accepts the
     * new certificate from the moment it issued it, and the previous credential only until the new one is first used.
     * So the old credential is restored only when Setu refuses the new certificate; when the trial heartbeat fails
     * without an answer (it may have reached Setu, which then no longer accepts the old credential), the new one is kept.
     */
    synchronized void updateCertificate() throws Exception {
        AgentCertificate.Request req = AgentCertificate.newKey(cfg.agentId);
        JsonObject answer = client.requestCertificate(cfg, req.csr());
        AgentCertificate.Credential fresh = AgentCertificate.credential(AgentCertificate.parse(Json.str(answer, "certificate")),
                req.keys().getPrivate());

        String oldFile = cfg.certFile, oldSha = cfg.certSha256, oldNotAfter = cfg.certNotAfter, oldPassword = cfg.keyStorePassword;
        Path freshFile = null;
        if (persist) {
            String password = AgentCertificate.newPassword();
            String name = "agent-cert-" + LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".p12";
            freshFile = Paths.dataDir().resolve(name);
            Files2.privateDir(Paths.dataDir());
            AgentCertificate.save(freshFile, fresh, password);
            cfg.certFile = name;
            cfg.keyStorePassword = password;
        }
        cfg.certSha256 = fresh.sha256();
        cfg.certNotAfter = fresh.certificate().getNotAfter().toInstant().toString();
        SetuClient trial = new SetuClient(cfg.server, cfg.pinnedCertSha256, fresh.keyManagers());
        try {
            if (persist) {
                cfg.save();
            }
            trial.heartbeat(cfg, null, "running", null);      // first use: Setu now refuses the previous credential
        } catch (Exception e) {
            if (e instanceof SetuException g && g.status == 410) {
                throw e;
            }
            if (!(e instanceof SetuException refused && (refused.status == 401 || refused.status == 403))) {
                // no answer: keep the new certificate (always valid); the secret goes after the first confirmed heartbeat
                Log.warn("the new client certificate was not confirmed yet (" + e.getMessage() + "); it stays in use");
                client = trial;
                credential = fresh;
                return;
            }
            cfg.certFile = oldFile;
            cfg.certSha256 = oldSha;
            cfg.certNotAfter = oldNotAfter;
            cfg.keyStorePassword = oldPassword;
            if (persist) {
                try {
                    cfg.save();
                } catch (Exception se) {
                    Log.warn("could not restore the configuration: " + se.getMessage());
                }
                Files.deleteIfExists(freshFile);
            }
            throw new IllegalStateException("the new client certificate could not be used: " + e.getMessage(), e);
        }
        if (persist && cfg.secret != null) {
            cfg.secret = null;
            cfg.save();
        }
        client = trial;
        credential = fresh;
        if (persist) {
            AgentCertificate.deleteOthers(cfg.certFile);
        }
    }
}
