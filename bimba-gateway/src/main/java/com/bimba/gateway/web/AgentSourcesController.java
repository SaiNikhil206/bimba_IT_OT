package com.bimba.gateway.web;

import com.bimba.gateway.agents.AgentSources;
import com.bimba.gateway.security.SecurityService;
import com.bimba.gateway.security.SecurityService.ApiException;
import com.bimba.gateway.security.SecurityService.Principal;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Console API for one site agent's machine page (organization-scoped like the rest of /api/gw):
 * <pre>
 *   GET    /agents/{id}                      machine details, data sources, collector status       agents.view
 *   POST   /agents/{id}/sources              add a data source (OPC UA, Modbus TCP, OPC DA)          agents.manage
 *   PUT    /agents/{id}/sources/{sid}        change it                                               agents.manage
 *   DELETE /agents/{id}/sources/{sid}        remove it                                               agents.manage
 *   POST   /agents/{id}/commands             ask the machine: test | browse | read | discover        agents.manage
 *   GET    /agents/{id}/commands/{cid}       its answer (poll)                                       agents.view
 *   GET    /agents/{id}/live                 last value of every tag the agent sent                  agents.view
 * </pre>
 */
@RestController
@RequestMapping("/api/gw")
public class AgentSourcesController {
    private final AgentSources sources;
    private final SecurityService security;

    public AgentSourcesController(AgentSources sources, SecurityService security) {
        this.sources = sources;
        this.security = security;
    }

    @GetMapping("/agents/{id}")
    @PreAuthorize("hasAuthority('agents.view')")
    Map<String, Object> detail(@AuthenticationPrincipal Principal p, @PathVariable String id) throws ApiException {
        return sources.detail(id, p.org());
    }

    @PostMapping("/agents/{id}/sources")
    @PreAuthorize("hasAuthority('agents.manage')")
    Map<String, Object> create(@AuthenticationPrincipal Principal p, @PathVariable String id, @RequestBody(required = false) String body,
                               HttpServletRequest req) throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        Map<String, Object> r = sources.saveSource(id, null, b, p.org(), p.username());
        security.audit(p, "agent.source.create", String.valueOf(r.get("name")), true, req.getRemoteAddr(), describe(id, r));
        return r;
    }

    @PutMapping("/agents/{id}/sources/{sid}")
    @PreAuthorize("hasAuthority('agents.manage')")
    Map<String, Object> update(@AuthenticationPrincipal Principal p, @PathVariable String id, @PathVariable String sid,
                               @RequestBody(required = false) String body, HttpServletRequest req) throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        Map<String, Object> r = sources.saveSource(id, sid, b, p.org(), p.username());
        security.audit(p, "agent.source.update", String.valueOf(r.get("name")), true, req.getRemoteAddr(), describe(id, r));
        return r;
    }

    @DeleteMapping("/agents/{id}/sources/{sid}")
    @PreAuthorize("hasAuthority('agents.manage')")
    Map<String, Object> delete(@AuthenticationPrincipal Principal p, @PathVariable String id, @PathVariable String sid,
                               HttpServletRequest req) throws ApiException {
        String name = sources.deleteSource(id, sid, p.org());
        security.audit(p, "agent.source.delete", name, true, req.getRemoteAddr(), Map.of("agentId", id, "sourceId", sid));
        return Map.of("ok", true);
    }

    @PostMapping("/agents/{id}/commands")
    @PreAuthorize("hasAuthority('agents.manage')")
    Map<String, Object> command(@AuthenticationPrincipal Principal p, @PathVariable String id, @RequestBody(required = false) String body)
            throws ApiException {
        return sources.command(id, ApiErrors.parse(body), p.org(), p.username());
    }

    @GetMapping("/agents/{id}/commands/{cid}")
    @PreAuthorize("hasAuthority('agents.view')")
    Map<String, Object> commandState(@AuthenticationPrincipal Principal p, @PathVariable String id, @PathVariable String cid)
            throws ApiException {
        return sources.commandState(id, cid, p.org());
    }

    @GetMapping("/agents/{id}/live")
    @PreAuthorize("hasAuthority('agents.view')")
    Map<String, Object> live(@AuthenticationPrincipal Principal p, @PathVariable String id) throws ApiException {
        return sources.live(id, p.org());
    }

    private static Map<String, Object> describe(String agentId, Map<String, Object> source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", agentId);
        m.put("kind", source.get("kind"));
        m.put("tags", source.get("tags") instanceof java.util.List<?> l ? l.size() : 0);
        m.put("pollMs", source.get("pollMs"));
        m.put("enabled", source.get("enabled"));
        return m;
    }
}
