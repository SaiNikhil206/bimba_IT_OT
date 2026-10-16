package com.bimba.setu.web;

import com.bimba.setu.agents.AgentRegistry;
import com.bimba.setu.agents.AgentUpdates;
import com.bimba.setu.security.Permission;
import com.bimba.setu.security.SecurityService;
import com.bimba.setu.security.SecurityService.ApiException;
import com.bimba.setu.security.SecurityService.Principal;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Automatic site agent updates ({@link AgentUpdates}), organization-scoped like the rest of /api/gw:
 * <pre>
 *   GET  /agents/updates             policy, the installers' versions, counts of the agents the user sees   agents.view
 *   PUT  /agents/updates             policy {mode, window, maxAtOnce}                agents.manage for the whole organization
 *   POST /agents/{id}/update         {action: now | hold | release}                  agents.manage (for that agent)
 * </pre>
 */
@RestController
@RequestMapping("/api/gw")
public class AgentUpdatesController {
    private final AgentUpdates updates;
    private final AgentRegistry agents;
    private final SecurityService security;

    public AgentUpdatesController(AgentUpdates updates, AgentRegistry agents, SecurityService security) {
        this.updates = updates;
        this.agents = agents;
        this.security = security;
    }

    @GetMapping("/agents/updates")
    @PreAuthorize("hasAuthority('agents.view')")
    Map<String, Object> overview(@AuthenticationPrincipal Principal p) throws ApiException {
        long org = p.org();
        Map<String, Map<String, Object>> summary = updates.summary(org);
        List<Map<String, Object>> seen = new ArrayList<>();
        for (Map<String, Object> a : agents.list(org)) {
            if (!p.allows(Permission.AGENTS_VIEW, (String) a.get("id"), (String) a.get("site"), null)
                    || "revoked".equals(a.get("status")) || "uninstalled".equals(a.get("status"))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>(a);
            Map<String, Object> u = summary.get((String) a.get("id"));
            m.put("update", u != null ? u : updates.unreported((String) a.get("agentVersion")));
            seen.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>(updates.overview(org, seen));
        out.put("canManage", p.everywhere(Permission.AGENTS_MANAGE));
        return out;
    }

    @PutMapping("/agents/updates")
    @PreAuthorize("hasAuthority('agents.manage')")
    Map<String, Object> savePolicy(@AuthenticationPrincipal Principal p, @RequestBody(required = false) String body,
                                   HttpServletRequest req) throws ApiException {
        if (!p.everywhere(Permission.AGENTS_MANAGE)) {
            throw new ApiException(403, "scope", "The update settings hold for the whole organization; your roles allow \""
                    + Permission.AGENTS_MANAGE.label + "\" only for some sites or site agents.");
        }
        JsonObject b = ApiErrors.parse(body);
        JsonObject saved = updates.savePolicy(p.org(), b, p.username());
        security.audit(p, "agent.update.policy", "site agents", true, req.getRemoteAddr(),
                Map.of("mode", saved.get("mode").getAsString(), "window", String.valueOf(saved.get("window")),
                        "maxAtOnce", saved.get("maxAtOnce").getAsInt()));
        return Map.of("policy", com.bimba.setu.Json.toJava(saved.toString()));
    }

    @PostMapping("/agents/{id}/update")
    @PreAuthorize("hasAuthority('agents.manage')")
    Map<String, Object> act(@AuthenticationPrincipal Principal p, @PathVariable String id, @RequestBody(required = false) String body,
                            HttpServletRequest req) throws ApiException {
        security.require(p, Permission.AGENTS_MANAGE, id);
        JsonObject b = ApiErrors.parse(body);
        String action = b.has("action") && b.get("action").isJsonPrimitive() ? b.get("action").getAsString() : "";
        Map<String, Object> r = updates.act(id, p.org(), action, p.username());
        security.audit(p, "agent.update." + action, id, true, req.getRemoteAddr(), Map.of("agentId", id,
                "version", String.valueOf(r.get("available"))));
        return r;
    }
}
