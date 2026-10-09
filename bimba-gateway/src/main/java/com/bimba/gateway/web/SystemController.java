package com.bimba.gateway.web;

import com.bimba.gateway.Db;
import com.bimba.gateway.GatewayService;
import com.bimba.gateway.agents.AgentRegistry;
import com.bimba.gateway.agents.AgentSources;
import com.bimba.gateway.config.BimbaConfig;
import com.bimba.gateway.security.SecurityService.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Machine endpoints (no console sign-in; same paths as before, so BIMBA Operations and installed agents keep working):
 * <pre>
 *   GET  /system/bimba-status            gateway status JSON (read by BIMBA Operations through Django)
 *   GET  /system/bimba-agent-enroll      reachability check for the installer's "Test connection"
 *   POST /system/bimba-agent-enroll      installer: credentials + machine details -> agent id + agent secret
 *   POST /system/bimba-agent-heartbeat   agent service: client certificate (mutual TLS) or, until it has one,
 *                                        Bearer agent secret + X-BIMBA-Agent id (+ refreshed details)
 *   POST /system/bimba-agent-certificate agent service: first client certificate / renewal (body {csr})
 *   GET  /system/bimba-agent-config      edge collector: the data sources to read (answer carries "version")
 *   POST /system/bimba-agent-commands    edge collector: long-poll for the next console command (204 = none)
 *   POST /system/bimba-agent-commands/{id}/result   the command's answer {ok, result | error}
 *   POST /system/bimba-agent-readings    edge collector: {messages:[canonical messages]} -> delivery
 * </pre>
 */
@RestController
public class SystemController {
    private final GatewayService gateway;
    private final AgentRegistry agents;
    private final AgentSources sources;
    private final Db db;

    public SystemController(GatewayService gateway, AgentRegistry agents, AgentSources sources, Db db) {
        this.gateway = gateway;
        this.agents = agents;
        this.sources = sources;
        this.db = db;
    }

    @GetMapping("/system/bimba-status")
    ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>(gateway.status());
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
        Map<String, Object> out = new LinkedHashMap<>(agents.heartbeat(caller(req, agentId, auth), ApiErrors.parse(body), req.getRemoteAddr()));
        if (out.get("agentId") instanceof String id) {
            out.put("configVersion", sources.version(id));       // the edge collector fetches its config when this changes
        }
        return out;
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
            throw new ApiException(404, "disabled", "site agent registration is disabled on this gateway");
        }
        if (s.requireHttps && !req.isSecure()) {
            throw new ApiException(403, "https", "use the gateway's HTTPS address (https://<gateway>:8443)");
        }
    }
}
