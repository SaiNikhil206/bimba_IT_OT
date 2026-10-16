package com.bimba.setu.web;

import com.bimba.setu.agents.SafetyChanges;
import com.bimba.setu.security.SecurityService.ApiException;
import com.bimba.setu.security.SecurityService.Principal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Console API for safety configuration change requests (four-eyes, agents/SafetyChanges), organization-scoped:
 * <pre>
 *   GET  /safety-changes?agent=&state=&limit=   requests the user may see, newest first   agents.view | sources.manage | machines.view | writes.*
 *   POST /safety-changes/{id}/approve           by another person                         writes.approve (where the agent and machines are)
 *   POST /safety-changes/{id}/reject            {reason}                                  writes.approve (same)
 *   POST /safety-changes/{id}/withdraw          the proposer                              sources.manage
 * </pre>
 * Requests are made by saving a data source (AgentSourcesController). Every step is audited (agent.source.safety.*).
 */
@RestController
@RequestMapping("/api/gw/safety-changes")
public class SafetyChangesController {
    private final SafetyChanges changes;

    public SafetyChangesController(SafetyChanges changes) {
        this.changes = changes;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('agents.view', 'sources.manage', 'machines.view', 'writes.send', 'writes.approve', 'writes.manage')")
    Map<String, Object> list(@AuthenticationPrincipal Principal p, @RequestParam(required = false) String agent,
                             @RequestParam(required = false) String state, @RequestParam(defaultValue = "50") int limit) throws ApiException {
        return changes.list(p, agent, state, limit);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('writes.approve')")
    Map<String, Object> approve(@AuthenticationPrincipal Principal p, @PathVariable String id, HttpServletRequest req) throws ApiException {
        return changes.approve(p, id, req.getRemoteAddr());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('writes.approve')")
    Map<String, Object> reject(@AuthenticationPrincipal Principal p, @PathVariable String id, @RequestBody(required = false) String body,
                               HttpServletRequest req) throws ApiException {
        return changes.reject(p, id, ApiErrors.parse(body), req.getRemoteAddr());
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('sources.manage')")
    Map<String, Object> withdraw(@AuthenticationPrincipal Principal p, @PathVariable String id, HttpServletRequest req) throws ApiException {
        return changes.withdraw(p, id, req.getRemoteAddr());
    }
}
