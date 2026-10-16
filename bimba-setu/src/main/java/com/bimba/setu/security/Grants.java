package com.bimba.setu.security;

import com.bimba.setu.Db;
import com.bimba.setu.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a user may do in one organization: their role assignments there (bimba_gw_user_role), each for the whole
 * organization or limited to a {@link Scope}. Scopes live in bimba_gw_setting "access.org.&lt;org id&gt;":
 * {"&lt;user id&gt;": {"&lt;role id&gt;": {sites, agents, machines}}}; an assignment without an entry holds everywhere.
 * The whole entry of a user is written together with their roles (SecurityService.setRoles).
 */
@Service
public class Grants {
    static final String KEY = "access.org.";

    private final Db db;

    public Grants(Db db) {
        this.db = db;
    }

    /** one role of a user in an organization; admin = the locked Administrator role (every permission) */
    public record Assignment(long roleId, String roleName, boolean builtin, Set<String> permissions, boolean admin, Scope scope) {
        /** the built-in Auditor role (duty separation: no operational permission next to it) */
        public boolean auditor() {
            return builtin && RoleCatalog.AUDITOR_ROLE.equalsIgnoreCase(roleName);
        }

        Assignment withScope(Scope s) {
            return new Assignment(roleId, roleName, builtin, permissions, admin, s);
        }

        Set<String> effective() {
            if (!admin) {
                return permissions;
            }
            Set<String> all = new LinkedHashSet<>();
            Arrays.stream(Permission.values()).forEach(p -> all.add(p.key));
            return all;
        }
    }

    /**
     * What the assignments allow together: permissions (held somewhere) and, for scoped permissions held only through
     * limited assignments, where they hold (the union of those scopes). A permission that one assignment gives for the
     * whole organization is not limited by another.
     */
    public record Access(Set<String> permissions, Map<String, Scope> scopes) {
        public static final Access NONE = new Access(Set.of(), Map.of());

        public boolean can(String key) {
            return permissions.contains(key);
        }

        /** null = everywhere it is held */
        public Scope scope(String key) {
            return scopes.get(key);
        }
    }

    public static Access combine(List<Assignment> assignments) {
        Set<String> wide = new LinkedHashSet<>();
        Map<String, Scope> part = new LinkedHashMap<>();
        for (Assignment a : assignments) {
            for (String k : a.effective()) {
                Permission p = Permission.of(k);
                if (p == null) {
                    continue;
                }
                if (!p.scoped || a.scope() == null) {
                    wide.add(k);
                } else {
                    part.merge(k, a.scope(), Scope::union);
                }
            }
        }
        wide.forEach(part::remove);
        Set<String> perms = new LinkedHashSet<>();
        Map<String, Scope> scopes = new LinkedHashMap<>();
        for (Permission p : Permission.values()) {        // in catalog order
            if (wide.contains(p.key)) {
                perms.add(p.key);
            } else if (part.containsKey(p.key)) {
                perms.add(p.key);
                scopes.put(p.key, part.get(p.key));
            }
        }
        return new Access(perms, scopes);
    }

    /** the user's permissions in one organization (platform admins: all of them, everywhere) */
    public Access access(long userId, boolean superuser, long orgId) {
        if (superuser) {
            Set<String> all = new LinkedHashSet<>();
            Arrays.stream(Permission.values()).forEach(p -> all.add(p.key));
            return new Access(all, Map.of());
        }
        return combine(assignments(userId, orgId));
    }

    /** the user's roles in the organization with their scopes */
    public List<Assignment> assignments(long userId, long orgId) {
        Map<Long, Scope> scopes = scopesOf(orgId, userId);
        return db.sql("SELECT r.id, r.name, r.builtin, r.permissions::text, (r.builtin AND r.name = ?) FROM {s}.bimba_gw_user_role ur"
                        + " JOIN {s}.bimba_gw_role r ON r.id = ur.role_id WHERE ur.user_id = ? AND ur.org_id = ? ORDER BY r.name")
                .params(SecurityService.ADMIN_ROLE, userId, orgId)
                .query((rs, n) -> new Assignment(rs.getLong(1), rs.getString(2), rs.getBoolean(3), keys(rs.getString(4)), rs.getBoolean(5),
                        scopes.get(rs.getLong(1))))
                .list();
    }

    /** every member's assignments in the organization: user id -> assignments */
    public Map<Long, List<Assignment>> assignments(long orgId) {
        Map<Long, Map<Long, Scope>> scopes = scopes(orgId);
        Map<Long, List<Assignment>> out = new LinkedHashMap<>();
        db.sql("SELECT ur.user_id, r.id, r.name, r.builtin, r.permissions::text, (r.builtin AND r.name = ?) FROM {s}.bimba_gw_user_role ur"
                        + " JOIN {s}.bimba_gw_role r ON r.id = ur.role_id WHERE ur.org_id = ? ORDER BY r.name")
                .params(SecurityService.ADMIN_ROLE, orgId)
                .query((rs, n) -> out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Assignment(rs.getLong(2), rs.getString(3),
                        rs.getBoolean(4), keys(rs.getString(5)), rs.getBoolean(6), scopes.getOrDefault(rs.getLong(1), Map.of()).get(rs.getLong(2)))))
                .list();
        return out;
    }

    /** the roles by id (any organization's; callers check they are usable), without scope */
    public Map<Long, Assignment> roles(java.util.Collection<Long> ids) {
        Map<Long, Assignment> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        db.sql("SELECT id, name, builtin, permissions::text, (builtin AND name = ?) FROM {s}.bimba_gw_role WHERE id = ANY (?::bigint[])")
                .params(SecurityService.ADMIN_ROLE, ids.toArray(new Long[0]))
                .query((rs, n) -> out.put(rs.getLong(1), new Assignment(rs.getLong(1), rs.getString(2), rs.getBoolean(3), keys(rs.getString(4)),
                        rs.getBoolean(5), null)))
                .list();
        return out;
    }

    /** the operational permissions an Auditor must not hold next to the Auditor role (BRD 4.5) */
    public static final Set<String> OPERATIONAL = Set.of("writes.send", "writes.approve", "writes.manage", "sources.manage", "agents.manage",
            "agents.enroll", "pipelines.manage", "destinations.manage", "quarantine.manage", "registry.manage");

    /** the operational permissions these assignments give someone who also holds the Auditor role (empty: fine) */
    public static Set<String> auditorConflicts(List<Assignment> as) {
        Set<String> out = new LinkedHashSet<>();
        if (as.stream().anyMatch(Assignment::auditor)) {
            for (String k : combine(as).permissions()) {
                if (OPERATIONAL.contains(k)) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    /** the permission keys of a role's JSON list (unknown keys dropped) */
    static Set<String> keys(String json) {
        Set<String> out = new LinkedHashSet<>();
        JsonArray a = json == null ? new JsonArray() : Json.GSON.fromJson(json, JsonArray.class);
        for (JsonElement e : a) {
            if (e.isJsonPrimitive() && Permission.of(e.getAsString()) != null) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ scope store

    /** every member's scoped assignments in the organization: user id -> role id -> scope */
    public Map<Long, Map<Long, Scope>> scopes(long orgId) {
        JsonObject all = db.sql("SELECT value::text FROM {s}.bimba_gw_setting WHERE key = ?").param(KEY + orgId).query(String.class)
                .optional().map(v -> Json.GSON.fromJson(v, JsonObject.class)).orElseGet(JsonObject::new);
        Map<Long, Map<Long, Scope>> out = new LinkedHashMap<>();
        for (String u : all.keySet()) {
            out.put(Long.parseLong(u), parse(all.get(u)));
        }
        return out;
    }

    public Map<Long, Scope> scopesOf(long orgId, long userId) {
        return db.sql("SELECT (value -> ?)::text FROM {s}.bimba_gw_setting WHERE key = ?").params(String.valueOf(userId), KEY + orgId)
                .query(String.class).optional().filter(v -> v != null && !"null".equals(v))
                .map(v -> parse(Json.GSON.fromJson(v, JsonElement.class))).orElseGet(Map::of);
    }

    private static Map<Long, Scope> parse(JsonElement e) {
        Map<Long, Scope> out = new LinkedHashMap<>();
        if (e != null && e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> r : e.getAsJsonObject().entrySet()) {
                Scope s = Scope.parse(r.getValue());
                if (s != null) {
                    out.put(Long.parseLong(r.getKey()), s);
                }
            }
        }
        return out;
    }

    /** replaces the user's scoped assignments in the organization (one statement, so concurrent changes of others stay) */
    void saveScopes(long orgId, long userId, Map<Long, Scope> scopes) {
        Map<Long, Scope> limited = new LinkedHashMap<>();
        scopes.forEach((k, v) -> {
            if (v != null) {
                limited.put(k, v);
            }
        });
        if (limited.isEmpty()) {
            db.sql("UPDATE {s}.bimba_gw_setting SET value = value - ?, updated_at = now() WHERE key = ?")
                    .params(String.valueOf(userId), KEY + orgId).update();
            return;
        }
        JsonObject o = new JsonObject();
        limited.forEach((k, v) -> o.add(String.valueOf(k), v.toJson()));
        db.sql("INSERT INTO {s}.bimba_gw_setting (key, value, updated_at) VALUES (?, jsonb_build_object(?::text, ?::jsonb), now())"
                        + " ON CONFLICT (key) DO UPDATE SET value = {s}.bimba_gw_setting.value || EXCLUDED.value, updated_at = now()")
                .params(KEY + orgId, String.valueOf(userId), o.toString()).update();
    }

    // ------------------------------------------------------------------ places

    /** the organization's site agents: id -> site ("" when the agent reports none) */
    public Map<String, String> agentSites(long orgId) {
        Map<String, String> out = new LinkedHashMap<>();
        db.sql("SELECT id::text, coalesce(site, '') FROM {s}.bimba_agent WHERE org_id = ?").param(orgId)
                .query((rs, n) -> out.put(rs.getString(1), rs.getString(2))).list();
        return out;
    }

    /** the site of one agent of the organization, or null when there is no such agent */
    public String siteOf(long orgId, String agentId) {
        if (agentId == null || !agentId.matches("[0-9a-fA-F-]{36}")) {
            return null;
        }
        return db.sql("SELECT coalesce(site, '') FROM {s}.bimba_agent WHERE org_id = ? AND id = ?::uuid").params(orgId, agentId)
                .query(String.class).optional().orElse(null);
    }

}
