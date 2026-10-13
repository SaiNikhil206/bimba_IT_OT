package com.bimba.setu.security;

import com.bimba.setu.Db;
import com.bimba.setu.Json;
import com.bimba.setu.security.SecurityService.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Organizations (tenants) of Setu: who can work in which organization, and which organization owns which machine.
 * <ul>
 *   <li>A user works in an organization when they have a Setu role with at least one permission there
 *       (bimba_gw_user_role.org_id); one account can belong to several organizations, with different roles in each.
 *       Platform admins (Django superusers) can open every organization.</li>
 *   <li>Destinations, pipelines, custom roles, site agents and audit entries belong to one organization.</li>
 *   <li>Every machine of the tag tree belongs to exactly one organization: bimba_gw_machine, else the default
 *       organization (setting machines.default_org, else the oldest organization). A pipeline only carries machines of
 *       its own organization, so one organization never receives another's readings.</li>
 * </ul>
 */
@Service
public class OrgService {
    public static final String DEFAULT_ORG = "machines.default_org";
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$");
    /**
     * The console's addresses start with the organization's slug (/northwind-water/pipelines), next to addresses of no
     * organization (web/SpaController.java) and the server's own paths: a slug may not be one of their first segments.
     */
    public static final java.util.Set<String> RESERVED = java.util.Set.of("overview", "agents", "pipelines", "destinations", "delivery", "quarantine", "registry",
            "users", "roles", "audit", "tokens", "profile", "organizations", "login", "logout", "forgot-password", "reset-password", "api", "system",
            "actuator", "assets", "static", "index", "new", "admin", "settings", "help");

    private final Db db;

    public OrgService(Db db) {
        this.db = db;
    }

    /** uid: the public id in the console's addresses (/organizations/<uid>) */
    public record Org(long id, String uid, String slug, String name) {
        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("uid", uid);
            m.put("slug", slug);
            m.put("name", name);
            return m;
        }
    }

    /** An organization a user can work in, with their role names there. */
    public record Access(Org org, List<String> roles) {
        public Map<String, Object> json() {
            Map<String, Object> m = org.json();
            m.put("roles", roles);
            return m;
        }
    }

    // ------------------------------------------------------------------ access

    /** The organizations the user can work in, by name. Platform admins: every organization. */
    public List<Access> access(long userId, boolean platformAdmin) {
        // a role counts when it allows something (the locked Administrator role always does)
        String roles = "SELECT ur.org_id, r.name FROM {s}.bimba_gw_user_role ur JOIN {s}.bimba_gw_role r ON r.id = ur.role_id"
                + " WHERE ur.user_id = ? AND (jsonb_array_length(r.permissions) > 0 OR (r.builtin AND r.name = 'Administrator'))";
        Map<Long, List<String>> byOrg = new LinkedHashMap<>();
        db.sql(roles + " ORDER BY r.name").param(userId)
                .query((rs, n) -> byOrg.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2))).list();
        List<Access> out = new ArrayList<>();
        for (Org o : all()) {
            List<String> r = byOrg.get(o.id());
            if (r != null || platformAdmin) {
                out.add(new Access(o, r == null ? List.of() : r));
            }
        }
        return out;
    }

    public List<Org> all() {
        return db.sql("SELECT id, uid, slug, name FROM {s}.bimba_org ORDER BY lower(name), id")
                .query((rs, n) -> new Org(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    public Org get(long id) throws ApiException {
        return db.sql("SELECT id, uid, slug, name FROM {s}.bimba_org WHERE id = ?").param(id)
                .query((rs, n) -> new Org(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).optional()
                .orElseThrow(() -> new ApiException(404, "not_found", "Organization not found."));
    }

    // ------------------------------------------------------------------ platform admin: organizations

    /** Every organization with what it holds (platform admins). */
    public List<Map<String, Object>> overview() {
        Long def = defaultOrgId();
        return db.sql("SELECT o.id, o.slug, o.name, o.created_at,"
                        + " (SELECT count(*) FROM {s}.bimba_org_member m WHERE m.org_id = o.id),"
                        + " (SELECT count(DISTINCT ur.user_id) FROM {s}.bimba_gw_user_role ur WHERE ur.org_id = o.id),"
                        + " (SELECT count(*) FROM {s}.bimba_gw_destination d WHERE d.org_id = o.id),"
                        + " (SELECT count(*) FROM {s}.bimba_gw_pipeline p WHERE p.org_id = o.id),"
                        + " (SELECT count(*) FROM {s}.bimba_gw_pipeline p WHERE p.org_id = o.id AND p.active),"
                        + " (SELECT count(*) FROM {s}.bimba_agent a WHERE a.org_id = o.id AND a.state = 'active'), o.uid"
                        + " FROM {s}.bimba_org o ORDER BY lower(o.name), o.id")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("uid", rs.getString(11));
                    m.put("slug", rs.getString(2));
                    m.put("name", rs.getString(3));
                    Timestamp t = rs.getTimestamp(4);
                    m.put("createdAt", t == null ? null : t.toInstant().toString());
                    m.put("members", rs.getLong(5));
                    m.put("gatewayUsers", rs.getLong(6));
                    m.put("destinations", rs.getLong(7));
                    m.put("pipelines", rs.getLong(8));
                    m.put("activePipelines", rs.getLong(9));
                    m.put("agents", rs.getLong(10));
                    m.put("default", def != null && def == rs.getLong(1));
                    return m;
                }).list();
    }

    public Org create(String name, String slug) throws ApiException {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 200) {
            throw new ApiException(400, "name", "Give the organization a name (up to 200 characters).");
        }
        String s = slug == null || slug.isBlank() ? slugify(n) : slug.trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(s).matches()) {
            throw new ApiException(400, "slug", "The short name uses lower-case letters, digits and - (up to 64), e.g. acme-water.");
        }
        if (RESERVED.contains(s)) {
            throw new ApiException(400, "slug", "The short name " + s + " is used by the console's own addresses. Choose another one.");
        }
        try {
            String uid = java.util.UUID.randomUUID().toString();
            long id = db.sql("INSERT INTO {s}.bimba_org (slug, name, created_at, uid) VALUES (?, ?, now(), ?::uuid) RETURNING id")
                    .params(s, n, uid).query(Long.class).single();
            if (defaultOrgId() == null || all().size() == 1) {
                setDefault(id);
            }
            return new Org(id, uid, s, n);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "exists", "An organization with the short name " + s + " already exists.");
        }
    }

    public void rename(long id, String name) throws ApiException {
        get(id);
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 200) {
            throw new ApiException(400, "name", "Give the organization a name (up to 200 characters).");
        }
        db.sql("UPDATE {s}.bimba_org SET name = ? WHERE id = ?").params(n, id).update();
    }

    /** Only an empty organization can be deleted: no destinations, pipelines, site agents or machines, not the default. */
    public Org delete(long id) throws ApiException {
        Org o = get(id);
        Long def = defaultOrgId();
        if (def != null && def == id) {
            throw new ApiException(409, "default", o.name() + " receives the machines nobody assigned. Make another organization the default first.");
        }
        long held = db.sql("SELECT (SELECT count(*) FROM {s}.bimba_gw_destination WHERE org_id = ?)"
                        + " + (SELECT count(*) FROM {s}.bimba_gw_pipeline WHERE org_id = ?)"
                        + " + (SELECT count(*) FROM {s}.bimba_agent WHERE org_id = ? AND state = 'active')"
                        + " + (SELECT count(*) FROM {s}.bimba_gw_machine WHERE org_id = ?)")
                .params(id, id, id, id).query(Long.class).single();
        if (held > 0) {
            throw new ApiException(409, "not_empty", o.name() + " still has destinations, pipelines, site agents or machines. Remove or move them first.");
        }
        // Django's foreign keys have no ON DELETE in the database: the dependent rows go first. Open sessions in this
        // organization move to another organization of their user at the next request (or end when there is none).
        db.tx(() -> {
            db.sql("UPDATE {s}.bimba_gw_session SET org_id = NULL WHERE org_id = ?").param(id).update();
            db.sql("UPDATE {s}.bimba_gw_user_profile SET last_org_id = NULL WHERE last_org_id = ?").param(id).update();
            db.sql("DELETE FROM {s}.bimba_gw_user_role WHERE org_id = ? OR role_id IN (SELECT r.id FROM {s}.bimba_gw_role r WHERE r.org_id = ?)")
                    .params(id, id).update();
            db.sql("DELETE FROM {s}.bimba_gw_role WHERE org_id = ?").param(id).update();
            db.sql("DELETE FROM {s}.bimba_org_member WHERE org_id = ?").param(id).update();
            db.sql("DELETE FROM {s}.bimba_agent_event WHERE org_id = ? OR agent_id IN (SELECT a.id FROM {s}.bimba_agent a WHERE a.org_id = ?)")
                    .params(id, id).update();
            db.sql("DELETE FROM {s}.bimba_agent WHERE org_id = ?").param(id).update();
            db.sql("DELETE FROM {s}.bimba_enroll_token WHERE org_id = ?").param(id).update();
            db.sql("DELETE FROM {s}.bimba_org WHERE id = ?").param(id).update();
            return null;
        });
        return o;
    }

    // ------------------------------------------------------------------ machines

    /** The organization of machines nobody assigned: the setting, else the oldest organization; null without organizations. */
    public Long defaultOrgId() {
        Long set = db.sql("SELECT (value->>'orgId')::bigint FROM {s}.bimba_gw_setting s WHERE key = ?"
                        + " AND EXISTS (SELECT 1 FROM {s}.bimba_org o WHERE o.id = (s.value->>'orgId')::bigint)")
                .param(DEFAULT_ORG).query(Long.class).optional().orElse(null);
        return set != null ? set : db.sql("SELECT min(id) FROM {s}.bimba_org").query(Long.class).optional().orElse(null);
    }

    /** The default organization, created ("Default organization") when there is none yet, e.g. to own imported destinations. */
    public long ensureDefaultOrg() {
        Long id = defaultOrgId();
        if (id != null) {
            return id;
        }
        long created = db.sql("INSERT INTO {s}.bimba_org (slug, name, created_at) VALUES ('default', 'Default organization', now())"
                + " ON CONFLICT (slug) DO UPDATE SET name = {s}.bimba_org.name RETURNING id").query(Long.class).single();
        setDefault(created);
        return created;
    }

    public void setDefault(long orgId) {
        db.sql("INSERT INTO {s}.bimba_gw_setting (key, value, updated_at) VALUES (?, ?::jsonb, now())"
                        + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()")
                .params(DEFAULT_ORG, Json.GSON.toJson(Map.of("orgId", orgId))).update();
    }

    /** machine name -> organization id, for the machines someone assigned */
    public Map<String, Long> owners() {
        Map<String, Long> m = new LinkedHashMap<>();
        db.sql("SELECT name, org_id FROM {s}.bimba_gw_machine").query((rs, n) -> m.put(rs.getString(1), rs.getLong(2))).list();
        return m;
    }

    /** Moves a machine to an organization (null = back to the default organization). */
    public void assign(String machine, Long orgId, String by) throws ApiException {
        if (machine == null || machine.isBlank() || machine.length() > 120) {
            throw new ApiException(400, "machine", "Unknown machine.");
        }
        if (orgId == null) {
            db.sql("DELETE FROM {s}.bimba_gw_machine WHERE name = ?").param(machine).update();
            recentAssignments.put(machine, UNASSIGNED);
            return;
        }
        get(orgId);
        db.sql("INSERT INTO {s}.bimba_gw_machine (name, org_id, assigned_at, assigned_by) VALUES (?, ?, now(), ?)"
                        + " ON CONFLICT (name) DO UPDATE SET org_id = EXCLUDED.org_id, assigned_at = now(), assigned_by = EXCLUDED.assigned_by")
                .params(machine, orgId, by).update();
        recentAssignments.put(machine, orgId);
    }

    /** in recentAssignments(): the machine went back to the default organization */
    public static final long UNASSIGNED = -1;
    private final Map<String, Long> recentAssignments = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Assignments made since the delivery engine last loaded owners() (machine -> organization, or UNASSIGNED). The
     * engine checks them first, so a new site agent machine's first readings already go to its organization's pipelines
     * (the engine reloads the owners only when it notices the change, up to 15 s later).
     */
    public Map<String, Long> recentAssignments() {
        return recentAssignments;
    }

    /** called right before the delivery engine reads owners() */
    public void clearRecentAssignments() {
        recentAssignments.clear();
    }

    /** Whether the machine belongs to the organization, given owners() and defaultOrgId(). */
    public static boolean owns(long orgId, String machine, Map<String, Long> owners, Long defaultOrg) {
        Long o = owners.get(machine);
        return o != null ? o == orgId : defaultOrg != null && defaultOrg == orgId;
    }

    /** The machines of the organization among the given ones (e.g. the tag tree's). */
    public List<String> machinesOf(long orgId, List<String> machines) {
        Map<String, Long> owners = owners();
        Long def = defaultOrgId();
        return machines.stream().filter(m -> owns(orgId, m, owners, def)).toList();
    }

    static String slugify(String name) {
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.length() > 64) {
            s = s.substring(0, 64).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "org" : s;
    }
}
