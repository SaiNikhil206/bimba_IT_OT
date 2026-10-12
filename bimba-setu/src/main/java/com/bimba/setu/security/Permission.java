package com.bimba.setu.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a role can allow in the Setu console, always within one organization (roles are assigned per organization).
 * Django superusers (platform admins) have all of them in every organization.
 */
public enum Permission {
    GATEWAY_VIEW("gateway.view", "Setu", "See the overview, pipelines and destinations of the organization"),
    AGENTS_VIEW("agents.view", "Site agents", "See the organization's site agents"),
    AGENTS_MANAGE("agents.manage", "Site agents", "Revoke the organization's site agents"),
    DELIVERY_MANAGE("delivery.manage", "Delivery", "Add, edit and remove the organization's destinations and pipelines"),
    USERS_MANAGE("users.manage", "Administration", "Add and remove members, assign their roles, reset passwords"),
    ROLES_MANAGE("roles.manage", "Administration", "Create and edit the organization's own roles"),
    AUDIT_VIEW("audit.view", "Administration", "Read the organization's audit log");

    public final String key;
    public final String group;
    public final String label;

    Permission(String key, String group, String label) {
        this.key = key;
        this.group = group;
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

    public static List<Map<String, String>> catalog() {
        return java.util.Arrays.stream(values()).map(p -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("key", p.key);
            m.put("group", p.group);
            m.put("label", p.label);
            return m;
        }).toList();
    }
}
