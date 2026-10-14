package com.bimba.setu.web;

import com.bimba.setu.agents.MachineCommands;
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
 * Console API for machine commands (agents/MachineCommands), organization-scoped like the rest of /api/gw:
 * <pre>
 *   GET  /machine-commands?limit=&state=   commands, newest first (state=approval: waiting) writes.send | approve | manage
 *   GET  /machine-commands/{id}            one command (poll while it runs)                 writes.send | approve | manage
 *   POST /machine-commands                 {sourceId, machine, tag, value, reason}          writes.send
 *   POST /machine-commands/{id}/approve    by another person                                writes.approve
 *   POST /machine-commands/{id}/reject     {reason}                                         writes.approve
 *   POST /machine-commands/{id}/cancel     the requester, or an approver                    writes.send | approve
 *   POST /machine-commands/stop            {scope: org|site|agent, target, reason}          writes.send | approve | manage
 *   POST /machine-commands/resume          {scope, target}                                  writes.manage
 * </pre>
 * Every change is audited (machine.write.*). The machines themselves: MachinesController.
 */
@RestController
@RequestMapping("/api/gw/machine-commands")
public class MachineCommandsController {
    private static final String ANY = "hasAnyAuthority('writes.send', 'writes.approve', 'writes.manage')";

    private final MachineCommands commands;

    public MachineCommandsController(MachineCommands commands) {
        this.commands = commands;
    }

    @GetMapping
    @PreAuthorize(ANY)
    Map<String, Object> list(@AuthenticationPrincipal Principal p, @RequestParam(defaultValue = "100") int limit,
                             @RequestParam(required = false) String state) throws ApiException {
        return commands.list(p, limit, state);
    }

    @GetMapping("/{id}")
    @PreAuthorize(ANY)
    Map<String, Object> get(@AuthenticationPrincipal Principal p, @PathVariable String id) throws ApiException {
        return commands.get(p, id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('writes.send')")
    Map<String, Object> request(@AuthenticationPrincipal Principal p, @RequestBody(required = false) String body, HttpServletRequest req)
            throws ApiException {
        return commands.request(p, ApiErrors.parse(body), req.getRemoteAddr());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('writes.approve')")
    Map<String, Object> approve(@AuthenticationPrincipal Principal p, @PathVariable String id, HttpServletRequest req) throws ApiException {
        return commands.approve(p, id, req.getRemoteAddr());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('writes.approve')")
    Map<String, Object> reject(@AuthenticationPrincipal Principal p, @PathVariable String id, @RequestBody(required = false) String body,
                               HttpServletRequest req) throws ApiException {
        return commands.reject(p, id, ApiErrors.parse(body), req.getRemoteAddr());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('writes.send', 'writes.approve')")
    Map<String, Object> cancel(@AuthenticationPrincipal Principal p, @PathVariable String id, HttpServletRequest req) throws ApiException {
        return commands.cancel(p, id, req.getRemoteAddr());
    }

    @PostMapping("/stop")
    @PreAuthorize(ANY)
    Map<String, Object> stop(@AuthenticationPrincipal Principal p, @RequestBody(required = false) String body, HttpServletRequest req)
            throws ApiException {
        return commands.stop(p, ApiErrors.parse(body), req.getRemoteAddr());
    }

    @PostMapping("/resume")
    @PreAuthorize("hasAuthority('writes.manage')")
    Map<String, Object> resume(@AuthenticationPrincipal Principal p, @RequestBody(required = false) String body, HttpServletRequest req)
            throws ApiException {
        return commands.resume(p, ApiErrors.parse(body), req.getRemoteAddr());
    }
}
