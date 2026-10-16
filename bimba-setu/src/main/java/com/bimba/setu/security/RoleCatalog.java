package com.bimba.setu.security;

import com.bimba.setu.Db;
import com.bimba.setu.Json;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The built-in roles (shared by every organization) and the move of existing roles to new permission versions.
 * <p>
 * The built-in roles follow the personas of the BIMBA OT/IT Bridge BRD (section 4.2), so each persona has a default
 * role; Administrator (every permission) and the older Operator role stay. Two personas have no role yet: Release
 * manager (Setu has no environments to promote between) and Service account (needs API clients, which cannot sign in
 * to the console).
 * <p>
 * Version 2 of the permissions split delivery.manage (pipelines, destinations, quarantine, registry, message contents)
 * and agents.manage (enrolling, data sources), and added machines.view and users.view. Every role is moved over once
 * at start-up (bimba_gw_setting "permissions.version"); the built-in persona roles get their persona's permissions.
 */
@Service
public class RoleCatalog {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Security");
    static final String VERSION_KEY = "permissions.version";
    static final int VERSION = 2;

    /** code and name of a BRD persona, and the permissions of its default role */
    public record Persona(String code, String brdName, String role, String description, List<String> permissions) {
    }

    public static final List<Persona> PERSONAS = List.of(
            new Persona("Adm", "Platform Administrator", "Access administrator",
                    "Manages members, roles, scopes and enrollment, reads the audit log; can stop and resume machine commands but sends none.",
                    List.of("gateway.view", "agents.view", "machines.view", "agents.enroll", "writes.manage", "users.view", "users.manage",
                            "roles.manage", "audit.view")),
            new Persona("Dev", "Integration Developer", "Integration developer",
                    "Designs pipelines, destinations and the tag registry; sends quarantined and rejected messages again.",
                    List.of("gateway.view", "agents.view", "machines.view", "messages.view", "pipelines.manage", "destinations.manage",
                            "registry.manage", "quarantine.manage")),
            new Persona("OT", "OT Engineer", "OT engineer",
                    "Installs site agents and defines their data sources, devices, writable tags and limits, and the tag registry. Sends no commands.",
                    List.of("gateway.view", "agents.view", "machines.view", "messages.view", "agents.enroll", "agents.manage", "sources.manage",
                            "registry.manage")),
            new Persona("Opr", "Plant Operator", "Machine operator",
                    "Watches live data and sends machine commands (writable tags, within their limits) on the machines of their scope.",
                    List.of("gateway.view", "agents.view", "machines.view", "writes.send")),
            new Persona("Sup", "Plant Supervisor / Approver", "Supervisor",
                    "Sends and approves machine commands, stops and resumes them, and reads the audit log.",
                    List.of("gateway.view", "agents.view", "machines.view", "messages.view", "writes.send", "writes.approve", "writes.manage",
                            "audit.view")),
            new Persona("Own", "Application Owner", "Application owner",
                    "Looks after the pipelines and destinations of their applications and sends their rejected messages again.",
                    List.of("gateway.view", "messages.view", "pipelines.manage", "destinations.manage", "quarantine.manage")),
            new Persona("Aud", "Auditor", "Auditor",
                    "Read only: configuration, machines, commands, the audit log and the access review. Holds no operational role (duty separation).",
                    List.of("gateway.view", "agents.view", "machines.view", "messages.view", "users.view", "audit.view")),
            new Persona("Vwr", "Viewer", "Viewer",
                    "Read only: overview, site agents and machines.",
                    List.of("gateway.view", "agents.view", "machines.view")));

    /** BRD personas without a built-in role, and why */
    public static final List<Map<String, String>> NOT_SHIPPED = List.of(
            Map.of("code", "Rel", "name", "Release Manager", "why", "Setu has no DEV / TEST / PROD environments to promote between yet."),
            Map.of("code", "Svc", "name", "Service Account", "why", "Needs API clients (no console sign-in), which Setu does not have yet."));

    /** the persona whose default role this is, or null */
    public static Persona persona(String roleName, boolean builtin) {
        if (!builtin) {
            return null;
        }
        return PERSONAS.stream().filter(p -> p.role().equalsIgnoreCase(roleName)).findFirst().orElse(null);
    }

    public static final String AUDITOR_ROLE = "Auditor";

    private final Db db;

    public RoleCatalog(Db db) {
        this.db = db;
    }

    @PostConstruct
    void init() {
        try {
            upgrade();
            for (Persona p : PERSONAS) {
                seed(p.role(), p.description(), p.permissions());
            }
        } catch (RuntimeException e) {
            log.warn("built-in roles not checked yet: {}", e.getMessage());
        }
    }

    private void upgrade() {
        int at = db.sql("SELECT value::text FROM {s}.bimba_gw_setting WHERE key = ?").param(VERSION_KEY).query(String.class).optional()
                .map(v -> Integer.parseInt(v.replace("\"", "").trim())).orElse(1);
        if (at >= VERSION) {
            return;
        }
        int changed = db.tx(() -> {
            int n = 0;
            List<Object[]> roles = db.sql("SELECT id, name, builtin, org_id IS NULL, permissions::text FROM {s}.bimba_gw_role")
                    .query((rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4), rs.getString(5)}).list();
            for (Object[] r : roles) {
                Persona p = (Boolean) r[3] ? persona((String) r[1], (Boolean) r[2]) : null;
                List<String> before = new java.util.ArrayList<>();
                Json.GSON.fromJson((String) r[4], com.google.gson.JsonArray.class).forEach(e -> before.add(e.getAsString()));
                List<String> after = p != null ? p.permissions() : upgraded(before);
                if (!after.equals(before) || p != null) {
                    if (p != null) {
                        db.sql("UPDATE {s}.bimba_gw_role SET permissions = ?::jsonb, description = ? WHERE id = ?")
                                .params(Json.GSON.toJson(after), p.description(), r[0]).update();
                    } else {
                        db.sql("UPDATE {s}.bimba_gw_role SET permissions = ?::jsonb WHERE id = ?").params(Json.GSON.toJson(after), r[0]).update();
                    }
                    n++;
                }
            }
            db.sql("INSERT INTO {s}.bimba_gw_setting (key, value, updated_at) VALUES (?, ?::jsonb, now())"
                    + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()").params(VERSION_KEY, String.valueOf(VERSION)).update();
            return n;
        });
        log.info("permissions moved to version {}: {} role(s) updated", VERSION, changed);
    }

    /** a role's permissions in version 2: what each old permission allowed, under its new names */
    static List<String> upgraded(List<String> old) {
        Set<String> out = new LinkedHashSet<>();
        for (String k : old) {
            switch (k) {
                case "delivery.manage" -> out.addAll(List.of("messages.view", "pipelines.manage", "destinations.manage", "quarantine.manage",
                        "registry.manage"));
                case "agents.manage" -> out.addAll(List.of("agents.manage", "agents.enroll", "sources.manage"));
                case "users.manage" -> out.addAll(List.of("users.view", "users.manage"));
                default -> out.add(k);
            }
        }
        // machines were visible with any of these before
        if (old.stream().anyMatch(k -> k.equals("gateway.view") || k.equals("agents.view") || k.startsWith("writes."))) {
            out.add("machines.view");
        }
        // in catalog order, unknown keys dropped
        Set<String> ordered = new LinkedHashSet<>();
        for (Permission p : Permission.values()) {
            if (out.contains(p.key)) {
                ordered.add(p.key);
            }
        }
        return List.copyOf(ordered);
    }

    private void seed(String name, String description, List<String> permissions) {
        int n = db.sql("INSERT INTO {s}.bimba_gw_role (org_id, name, description, permissions, builtin, created_at)"
                        + " SELECT NULL, ?, ?, ?::jsonb, true, now() WHERE NOT EXISTS"
                        + " (SELECT 1 FROM {s}.bimba_gw_role WHERE org_id IS NULL AND lower(name) = lower(?))")
                .params(name, description, Json.GSON.toJson(permissions), name).update();
        if (n > 0) {
            log.info("built-in role {} added", name);
        }
    }

    public static Map<String, Object> json(Persona p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.code());
        m.put("name", p.brdName());
        return m;
    }
}
