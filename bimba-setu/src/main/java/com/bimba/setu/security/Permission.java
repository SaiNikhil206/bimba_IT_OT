package com.bimba.setu.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a role can allow in the Setu console, always within one organization (roles are assigned per organization).
 * Django superusers (platform admins) have all of them in every organization.
 * <p>
 * scoped = the permission is about site agents and the machines they collect, so a role assignment limited to some
 * sites, site agents or machines ({@link Scope}) allows it only there. The others hold for the whole organization.
 * Version 2 split delivery.manage and agents.manage; {@link RoleCatalog} moves existing roles over.
 */
public enum Permission {
    GATEWAY_VIEW("gateway.view", "Setu", false, "See the overview, pipelines, destinations, quarantine and tag registry"),
    MESSAGES_VIEW("messages.view", "Setu", false, "Open the contents of rejected and quarantined messages"),
    PIPELINES_MANAGE("pipelines.manage", "Setu", false, "Add, edit, switch on or off and remove pipelines"),
    DESTINATIONS_MANAGE("destinations.manage", "Setu", false, "Add, edit, test and remove destinations"),
    QUARANTINE_MANAGE("quarantine.manage", "Setu", false, "Send again or discard quarantined and rejected messages; quarantine settings and alerts"),
    REGISTRY_MANAGE("registry.manage", "Setu", false, "Edit, import and remove tag registry entries"),
    AGENTS_VIEW("agents.view", "Site agents", true, "See site agents, their data sources and live readings"),
    AGENTS_ENROLL("agents.enroll", "Site agents", false, "Install new site agents: enrollment tokens, or enrolling with one's own e-mail and password"),
    AGENTS_MANAGE("agents.manage", "Site agents", true, "Revoke site agents; update their software (update now, hold, automatic update settings)"),
    SOURCES_MANAGE("sources.manage", "Site agents", true, "Add, edit and remove data sources, writable tags and their limits; browse, test and scan networks"),
    MACHINES_VIEW("machines.view", "Machines", true, "See machines and their current values, and read them now"),
    WRITES_SEND("writes.send", "Machine commands", true, "Write values to machines (only tags marked writable, within their limits)"),
    WRITES_APPROVE("writes.approve", "Machine commands", true, "Approve or reject commands that need a second person"),
    WRITES_MANAGE("writes.manage", "Machine commands", true, "Resume machine commands after they were stopped (anyone who may send or approve can stop them)"),
    USERS_VIEW("users.view", "Administration", false, "See members, their roles, scopes and access; export the access review"),
    USERS_MANAGE("users.manage", "Administration", false, "Add and remove members, assign their roles and scopes, reset passwords"),
    ROLES_MANAGE("roles.manage", "Administration", false, "Create and edit the organization's own roles"),
    AUDIT_VIEW("audit.view", "Administration", false, "Read the organization's audit log");

    public final String key;
    public final String group;
    public final boolean scoped;
    public final String label;

    Permission(String key, String group, boolean scoped, String label) {
        this.key = key;
        this.group = group;
        this.scoped = scoped;
        this.label = label;
    }

    public static Permission of(String key) {
        for (Permission p : values()) {
            if (p.key.equals(key)) {
                return p;
            }
        }
        return null;
    }

    public static List<Map<String, Object>> catalog() {
        return java.util.Arrays.stream(values()).map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.key);
            m.put("group", p.group);
            m.put("label", p.label);
            m.put("scoped", p.scoped);
            return m;
        }).toList();
    }
}
