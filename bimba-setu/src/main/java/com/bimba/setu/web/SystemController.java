package com.bimba.setu.web;

import com.bimba.setu.Db;
import com.bimba.setu.SetuService;
import com.bimba.setu.agents.AgentRegistry;
import com.bimba.setu.agents.AgentSources;
import com.bimba.setu.agents.AgentUpdates;
import com.bimba.setu.config.BimbaConfig;
import com.bimba.setu.security.SecurityService.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Machine endpoints (no console sign-in; same paths as before, so BIMBA Operations and installed agents keep working):
 * <pre>
 *   GET  /system/bimba-status            Setu status JSON (read by BIMBA Operations through Django)
 *   GET  /system/bimba-agent-enroll      reachability check for the installer's "Test connection"
 *   POST /system/bimba-agent-enroll      installer: credentials + machine details -> agent id + agent secret
 *   POST /system/bimba-agent-heartbeat   agent service: client certificate (mutual TLS) or, until it has one,
 *                                        Bearer agent secret + X-BIMBA-Agent id (+ refreshed details)
 *   POST /system/bimba-agent-certificate agent service: first client certificate / renewal (body {csr})
 *   GET  /system/bimba-agent-config      edge collector: the data sources to read (answer carries "version")
 *   POST /system/bimba-agent-commands    edge collector: long-poll for the next console command (204 = none)
 *   POST /system/bimba-agent-commands/{id}/result   the command's answer {ok, result | error}
 *   POST /system/bimba-agent-readings    edge collector: {messages:[canonical messages]} -> delivery
 *   GET  /system/bimba-agent-update/download?kind=exe|msi|deb|tar   agent service: the installer of an offered update
 *   GET  /system/bimba-agent-update/release   the installers' versions and SHA-256 (no sign-in: the computer's updater
 *                                        checks a downloaded installer against it on its own; nothing secret)
 * </pre>
 */
@RestController
public class SystemController {
    private final SetuService setu;
    private final AgentRegistry agents;
    private final AgentSources sources;
    private final AgentUpdates updates;
    private final Db db;

    public SystemController(SetuService setu, AgentRegistry agents, AgentSources sources, AgentUpdates updates, Db db) {
        this.setu = setu;
        this.agents = agents;
        this.sources = sources;
        this.updates = updates;
        this.db = db;
    }

    @GetMapping("/system/bimba-status")
    ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>(setu.status());
        Map<String, Object> reg = new LinkedHashMap<>(db.status());
        reg.put("enabled", agents.settings().enabled);
        m.put("siteAgents", reg);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(m);
    }

    @GetMapping("/system/bimba-agent-enroll")
    Map<String, Object> enrollCheck(HttpServletRequest req) throws ApiException {
        ready(req);
        return Map.of("service", "bimba-agent-enroll", "ready", true, "secure", req.isSecure());
    }

    @PostMapping("/system/bimba-agent-enroll")
    Map<String, Object> enroll(@RequestBody(required = false) String body, HttpServletRequest req) throws ApiException, AgentRegistry.ApiError {
        ready(req);
        String ip = req.getRemoteAddr();
        BimbaConfig.Agents s = agents.settings();
        if (agents.failures.blocked(ip, s.maxFailures, s.failureWindowMinutes * 60_000L)) {
            throw new ApiException(429, "rate_limited", "Too many failed attempts from this address. Try again in "
                    + s.failureWindowMinutes + " minutes.");
        }
        return agents.enroll(ApiErrors.parse(body), ip);
    }

    @PostMapping("/system/bimba-agent-heartbeat")
    Map<String, Object> heartbeat(@RequestBody(required = false) String body, HttpServletRequest req,
                                  @RequestHeader(value = "X-BIMBA-Agent", required = false) String agentId,
                                  @RequestHeader(value = "Authorization", required = false) String auth)
            throws ApiException, AgentRegistry.ApiError {
        ready(req);
        com.google.gson.JsonObject parsed = ApiErrors.parse(body);
        Map<String, Object> out = new LinkedHashMap<>(agents.heartbeat(caller(req, agentId, auth), parsed, req.getRemoteAddr()));
        if (out.get("agentId") instanceof String id) {
            out.put("configVersion", sources.version(id));       // the edge collector fetches its config when this changes
            Map<String, Object> offer = updates.onHeartbeat(id, parsed);
            if (offer != null) {
                out.put("update", offer);                         // a newer installer: the agent runs it over itself
            }
        }
        return out;
    }

    /** the installer of an offered update, unchanged (its SHA-256 is the published one); signed in as the agent */
    @GetMapping("/system/bimba-agent-update/download")
    ResponseEntity<Resource> updateDownload(@RequestParam String kind, HttpServletRequest req,
                                            @RequestHeader(value = "X-BIMBA-Agent", required = false) String agentId,
                                            @RequestHeader(value = "Authorization", required = false) String auth)
            throws ApiException, AgentRegistry.ApiError {
        ready(req);
        agents.check(caller(req, agentId, auth));
        if (!java.util.Set.of("exe", "msi", "deb", "tar").contains(kind)) {
            throw new ApiException(400, "kind", "kind must be exe, msi, deb or tar");
        }
        java.nio.file.Path file = updates.file(kind);
        if (!java.nio.file.Files.isRegularFile(file)) {
            throw new ApiException(404, "installer", "That installer is not available on this Setu server.");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    /** {files: {kind: {version, sha256, size}}}: what the computer's updater compares a downloaded installer with */
    @GetMapping("/system/bimba-agent-update/release")
    ResponseEntity<Map<String, Object>> updateRelease(HttpServletRequest req) throws ApiException {
        ready(req);
        Map<String, Object> files = new LinkedHashMap<>();
        updates.release().forEach((k, v) -> files.put(k, Map.of("version", v.get("version"), "sha256", v.get("sha256"), "size", v.get("size"))));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of("files", files));
    }

    @GetMapping("/system/bimba-agent-config")
    Map<String, Object> agentConfig(HttpServletRequest req,
                                    @RequestHeader(value = "X-BIMBA-Agent", required = false) String agentId,
                                    @RequestHeader(value = "Authorization", required = false) String auth)
            throws ApiException, AgentRegistry.ApiError {
        ready(req);
        return sources.config(caller(req, agentId, auth));
    }

    @PostMapping("/system/bimba-agent-commands")
    DeferredResult<ResponseEntity<Object>> agentCommands(HttpServletRequest req,
                                                         @RequestHeader(value = "X-BIMBA-Agent", required = false) String agentId,
                                                         @RequestHeader(value = "Authorization", required = false) String auth)
            throws ApiException, AgentRegistry.ApiError {
        ready(req);
        return sources.nextCommand(caller(req, agentId, auth));
    }

    @PostMapping("/system/bimba-agent-commands/{id}/result")
    Map<String, Object> agentCommandResult(@PathVariable String id, @RequestBody(required = false) String body, HttpServletRequest req,
                                           @RequestHeader(value = "X-BIMBA-Agent", required = false) String agentId,
                                           @RequestHeader(value = "Authorization", required = false) String auth)
            throws ApiException, AgentRegistry.ApiError {
        ready(req);
        return sources.commandResult(caller(req, agentId, auth), id, ApiErrors.parse(body));
    }

    @PostMapping("/system/bimba-agent-readings")
    Map<String, Object> agentReadings(@RequestBody(required = false) String body, HttpServletRequest req,
                                      @RequestHeader(value = "X-BIMBA-Agent", required = false) String agentId,
                                      @RequestHeader(value = "Authorization", required = false) String auth)
            throws ApiException, AgentRegistry.ApiError {
        ready(req);
        return sources.readings(caller(req, agentId, auth), body, req.getRemoteAddr());      // parsed there: bad bodies are quarantined
    }

    /** first certificate (with the agent secret) or renewal (with the current certificate); body {csr} */
    @PostMapping("/system/bimba-agent-certificate")
    Map<String, Object> certificate(@RequestBody(required = false) String body, HttpServletRequest req,
                                    @RequestHeader(value = "X-BIMBA-Agent", required = false) String agentId,
                                    @RequestHeader(value = "Authorization", required = false) String auth)
            throws ApiException, AgentRegistry.ApiError {
        ready(req);
        return agents.certificate(caller(req, agentId, auth), ApiErrors.parse(body), req.getRemoteAddr());
    }

    /** the client certificate Tomcat already checked against the agent authority (HTTPS only), else the Bearer secret */
    private static AgentRegistry.Caller caller(HttpServletRequest req, String agentId, String auth) {
        String secret = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7) : null;
        X509Certificate cert = req.getAttribute("jakarta.servlet.request.X509Certificate") instanceof X509Certificate[] chain
                && chain.length > 0 ? chain[0] : null;
        return new AgentRegistry.Caller(agentId, secret, cert, req.isSecure());
    }

    private void ready(HttpServletRequest req) throws ApiException {
        BimbaConfig.Agents s = agents.settings();
        if (!s.enabled) {
            throw new ApiException(404, "disabled", "site agent registration is disabled on this Setu server");
        }
        if (s.requireHttps && !req.isSecure()) {
            throw new ApiException(403, "https", "use Setu's HTTPS address (https://<setu>:8443)");
        }
    }
}
