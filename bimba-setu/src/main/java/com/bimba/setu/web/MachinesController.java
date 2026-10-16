package com.bimba.setu.web;

import com.bimba.setu.agents.MachineCommands;
import com.bimba.setu.security.SecurityService.ApiException;
import com.bimba.setu.security.SecurityService.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Console API of the Machines page: the machines the organization's site agents collect (agents/MachineCommands).
 * Viewing needs gateway.view, agents.view or a machine command permission; commands themselves are
 * MachineCommandsController.
 * <pre>
 *   GET  /machines                  every machine with its state, pipelines, last command; the kill switch
 *   GET  /machines/{m}              one machine: its site agent, data source, pipelines, live data, commands
 *   POST /machines/{m}/read         the agent reads the machine's tags once (read only)
 *   GET  /machines/{m}/read/{id}    that read, in engineering values
 * </pre>
 */
@RestController
@RequestMapping("/api/gw/machines")
public class MachinesController {
    private static final String VIEW = "hasAnyAuthority('machines.view', 'writes.send', 'writes.approve', 'writes.manage')";
    private static final String READ = VIEW;

    private final MachineCommands machines;

    public MachinesController(MachineCommands machines) {
        this.machines = machines;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    Map<String, Object> list(@AuthenticationPrincipal Principal p) throws ApiException {
        return machines.machines(p);
    }

    @GetMapping("/{machine}")
    @PreAuthorize(VIEW)
    Map<String, Object> machine(@AuthenticationPrincipal Principal p, @PathVariable String machine) throws ApiException {
        return machines.machine(p, machine);
    }

    @PostMapping("/{machine}/read")
    @PreAuthorize(READ)
    Map<String, Object> readNow(@AuthenticationPrincipal Principal p, @PathVariable String machine) throws ApiException {
        return machines.readNow(p, machine);
    }

    @GetMapping("/{machine}/read/{id}")
    @PreAuthorize(READ)
    Map<String, Object> readState(@AuthenticationPrincipal Principal p, @PathVariable String machine, @PathVariable String id)
            throws ApiException {
        return machines.readState(p, machine, id);
    }
}
