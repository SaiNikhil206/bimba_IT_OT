package com.bimba.setu.agents;

import com.bimba.setu.Db;
import com.bimba.setu.Json;
import com.bimba.setu.security.Permission;
import com.bimba.setu.security.SecurityService;
import com.bimba.setu.security.SecurityService.ApiException;
import com.bimba.setu.security.SecurityService.Principal;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Change requests for safety configuration (four-eyes, BRD 4.5; what counts: {@link SafetyConfig}). Saving a data source
 * that changes it stores everything else and proposes the safety part here; another person with writes.approve where the
 * data source's site agent (and each changed machine) is approves it, and only then it takes effect. The proposer can
 * withdraw it, an approver can reject it; a request nobody decided within {@link #DAYS} days expires.
 * <p>
 * Requests are bimba_gw_setting rows "safety.org.&lt;org&gt;.&lt;id&gt;" (no schema change): {id, agentId, agent,
 * sourceId, source, state: pending | approved | rejected | withdrawn | expired | outdated, changes:[..], summary,
 * requestedBy, requestedByName, requestedAt, decidedBy, decidedByName, decidedAt, why}. Decided ones are kept 90 days;
 * the audit log (agent.source.safety.*) keeps them for good.
 */
@Service
public class SafetyChanges {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Agents");
    static final String KEY = "safety.org.";
    static final int DAYS = 7;
    /** who sees a request: the site agent's viewers, and anyone with a machine or command permission for its machines */
    private static final List<Permission> SEERS = List.of(Permission.SOURCES_MANAGE, Permission.MACHINES_VIEW, Permission.WRITES_SEND,
            Permission.WRITES_APPROVE, Permission.WRITES_MANAGE);

    private final Db db;
    private final AgentSources sources;
    private final SecurityService security;

    public SafetyChanges(Db db, AgentSources sources, SecurityService security) {
        this.db = db;
        this.sources = sources;
        this.security = security;
    }

    @PostConstruct
    void init() {
        sources.onSafetyChange(this::propose);
    }

    // ================================================================== propose

    /** stores the request (an open one with the very same changes is answered instead of a second one) and audits it */
    Map<String, Object> propose(long org, UUID agent, JsonObject source, List<JsonObject> changes, String actor, String actorName) {
        String sourceId = Json.str(source, "id");
        JsonArray cs = new JsonArray();
        changes.forEach(cs::add);
        for (JsonObject r : all(org)) {
            if ("pending".equals(Json.str(r, "state")) && sourceId.equals(Json.str(r, "sourceId")) && cs.equals(r.get("changes"))
                    && actor.equalsIgnoreCase(Json.str(r, "requestedBy"))) {
                return view(r, null, null, null);
            }
        }
        String[] a = agentOf(org, agent.toString());
        JsonObject r = new JsonObject();
        UUID id = UUID.randomUUID();
        r.addProperty("id", id.toString());
        r.addProperty("agentId", agent.toString());
        r.addProperty("agent", a == null ? "" : a[0]);
        r.addProperty("sourceId", sourceId);
        r.addProperty("source", Json.str(source, "name"));
        r.addProperty("state", "pending");
        r.add("changes", cs);
        r.addProperty("summary", SafetyConfig.describe(changes));
        r.addProperty("requestedBy", actor);
        r.addProperty("requestedByName", actorName == null || actorName.isBlank() ? actor : actorName);
        r.addProperty("requestedAt", Instant.now().toString());
        db.sql("INSERT INTO {s}.bimba_gw_setting (key, value, updated_at) VALUES (?, ?::jsonb, now())")
                .params(key(org, id), r.toString()).update();
        security.audit(org, actor, "agent.source.safety.propose", Json.str(source, "name"), true, null, detail(r));
        log.info("data source {}: machine command settings change {} by {} waits for approval: {}", Json.str(source, "name"), id, actor,
                Json.str(r, "summary"));
        return view(r, null, null, null);
    }

    // ================================================================== decide

    /** another person approves: checked against the data source as it is now, then put into it */
    public Map<String, Object> approve(Principal p, String id, String ip) throws ApiException {
        long org = p.org();
        JsonObject r = open(org, id);
        SafetyConfig.notOwn(Json.str(r, "requestedBy"), p.username());
        JsonObject s = sources.sourceOf(Json.str(r, "sourceId"), org);
        if (s == null) {
            close(org, r, "outdated", null, "The data source was removed.");
            throw new ApiException(409, "outdated", "The data source was removed, so the change cannot be made.");
        }
        if (!decides(p, r, s)) {
            throw security.outside(p, Permission.WRITES_APPROVE, "data source " + Json.str(s, "name"));
        }
        List<JsonObject> changes = changes(r);
        JsonObject settings = s.getAsJsonObject("settings").deepCopy();
        JsonArray tags = s.getAsJsonArray("tags").deepCopy();
        String why = SafetyConfig.conflict(settings, tags, changes);
        if (why != null) {
            close(org, r, "outdated", p, why);
            security.audit(p, "agent.source.safety.outdated", Json.str(r, "source"), false, ip, detail(r));
            throw new ApiException(409, "outdated", why + " Propose the change again.");
        }
        SafetyConfig.apply(settings, tags, changes);
        if (!close(org, r, "approved", p, null)) {
            throw new ApiException(409, "state", "Someone else acted on this change request first.");
        }
        if (sources.applySafety(s, settings, tags, p.username()) == 0) {
            reopen(org, r);
            throw new ApiException(409, "changed", "The data source was changed at the same moment. Approve again.");
        }
        r = get(org, id);
        security.audit(p, "agent.source.safety.approve", Json.str(r, "source"), true, ip, detail(r));
        log.info("data source {}: machine command settings change {} approved by {}", Json.str(r, "source"), id, p.username());
        return view(r, p, sites(org), machines(org));
    }

    /** an approver turns it down, with a reason */
    public Map<String, Object> reject(Principal p, String id, JsonObject b, String ip) throws ApiException {
        long org = p.org();
        JsonObject r = open(org, id);
        JsonObject s = sources.sourceOf(Json.str(r, "sourceId"), org);
        if (!(s == null ? allows(p, Permission.WRITES_APPROVE, r, null, sites(org)) : decides(p, r, s))) {
            throw security.outside(p, Permission.WRITES_APPROVE, "data source " + Json.str(r, "source"));
        }
        String reason = AgentRegistry.str(b, "reason", 300);
        if (!close(org, r, "rejected", p, reason == null ? "" : reason)) {
            throw new ApiException(409, "state", "Someone else acted on this change request first.");
        }
        r = get(org, id);
        security.audit(p, "agent.source.safety.reject", Json.str(r, "source"), true, ip, detail(r));
        return view(r, p, sites(org), machines(org));
    }

    /** the proposer takes it back */
    public Map<String, Object> withdraw(Principal p, String id, String ip) throws ApiException {
        long org = p.org();
        JsonObject r = open(org, id);
        if (!Json.str(r, "requestedBy").equalsIgnoreCase(p.username())) {
            throw new ApiException(403, "forbidden", "Only the person who proposed it can withdraw this change. Approvers reject it.");
        }
        if (!close(org, r, "withdrawn", p, null)) {
            throw new ApiException(409, "state", "Someone else acted on this change request first.");
        }
        r = get(org, id);
        security.audit(p, "agent.source.safety.withdraw", Json.str(r, "source"), true, ip, detail(r));
        return view(r, p, sites(org), machines(org));
    }

    // ================================================================== list

    /**
     * The requests the user may see, newest first: one site agent's (agentId) or the organization's; state "pending" =
     * those waiting. {requests:[..], waiting: n (waiting ones the user may approve)}
     */
    public Map<String, Object> list(Principal p, String agentId, String state, int limit) throws ApiException {
        long org = p.org();
        Map<String, String> sites = sites(org);
        Map<String, Set<String>> machines = machines(org);
        List<Map<String, Object>> out = new ArrayList<>();
        long waiting = 0;
        for (JsonObject r : all(org)) {
            if (agentId != null && !agentId.isBlank() && !agentId.equalsIgnoreCase(Json.str(r, "agentId"))) {
                continue;
            }
            if (!visible(p, r, sites)) {
                continue;
            }
            Map<String, Object> v = view(r, p, sites, machines);
            if ("pending".equals(v.get("state")) && Boolean.TRUE.equals(((Map<?, ?>) v.get("can")).get("approve"))) {
                waiting++;
            }
            if ((state == null || state.isBlank() || state.equals(v.get("state"))) && out.size() < Math.max(1, Math.min(limit, 200))) {
                out.add(v);
            }
        }
        return Map.of("requests", out, "waiting", waiting, "days", DAYS);
    }

    /** machines: source id -> its machines (the writes switch counts for all of them) */
    private Map<String, Object> view(JsonObject r, Principal p, Map<String, String> sites, Map<String, Set<String>> machines) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("id", "state", "agentId", "agent", "sourceId", "source", "summary", "requestedBy", "requestedByName",
                "requestedAt", "decidedBy", "decidedByName", "decidedAt", "why")) {
            m.put(k, r.has(k) && !r.get(k).isJsonNull() ? Json.str(r, k) : null);
        }
        m.put("changes", Json.toJava(r.get("changes").toString()));
        m.put("expiresAt", Instant.parse(Json.str(r, "requestedAt")).plusSeconds(DAYS * 86_400L).toString());
        boolean mine = p != null && Json.str(r, "requestedBy").equalsIgnoreCase(p.username());
        boolean pending = "pending".equals(Json.str(r, "state"));
        boolean approver = p != null && pending && allows(p, Permission.WRITES_APPROVE, r, machines.get(Json.str(r, "sourceId")), sites);
        m.put("mine", p == null || mine);
        m.put("can", Map.of("approve", approver && !mine, "reject", approver && !mine, "withdraw", pending && (p == null || mine)));
        return m;
    }

    // ================================================================== expiry

    /** requests nobody decided within {@link #DAYS} days expire (audited); decided ones go after 90 days */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 150_000)
    public void expire() {
        try {
            List<String[]> gone = db.sql("UPDATE {s}.bimba_gw_setting SET value = value || jsonb_build_object('state', 'expired',"
                            + " 'decidedAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
                            + " 'why', 'Nobody approved it within " + DAYS + " days.'), updated_at = now()"
                            + " WHERE key LIKE 'safety.org.%' AND value->>'state' = 'pending'"
                            + " AND (value->>'requestedAt')::timestamptz < now() - interval '" + DAYS + " days' RETURNING key, value::text")
                    .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).list();
            for (String[] g : gone) {
                JsonObject r = Json.GSON.fromJson(g[1], JsonObject.class);
                long org = Long.parseLong(g[0].substring(KEY.length(), g[0].indexOf('.', KEY.length())));
                security.audit(org, Json.str(r, "requestedBy"), "agent.source.safety.expired", Json.str(r, "source"), false, null, detail(r));
            }
            db.sql("DELETE FROM {s}.bimba_gw_setting WHERE key LIKE 'safety.org.%' AND value->>'state' <> 'pending'"
                    + " AND updated_at < now() - interval '90 days'").update();
        } catch (RuntimeException e) {
            log.debug("change request expiry skipped: {}", e.getMessage());
        }
    }

    // ================================================================== scopes

    /**
     * May the user decide it: writes.approve where the site agent is and, for each changed tag, its machine; the writes
     * switch counts for every machine of the data source.
     */
    private boolean decides(Principal p, JsonObject r, JsonObject source) throws ApiException {
        return allows(p, Permission.WRITES_APPROVE, r, AgentSources.machines(source), sites(p.org()));
    }

    /** the permission holds for every machine the request touches (all = the source's machines, for the writes switch) */
    private static boolean allows(Principal p, Permission perm, JsonObject r, Set<String> all, Map<String, String> sites) {
        String agent = Json.str(r, "agentId");
        String site = sites.get(agent);
        if (!p.can(perm)) {
            return false;
        }
        for (JsonElement e : r.getAsJsonArray("changes")) {
            JsonObject c = e.getAsJsonObject();
            if ("writes".equals(Json.str(c, "kind"))) {
                if (all == null) {
                    if (!p.allows(perm, agent, site, null)) {
                        return false;
                    }
                } else {
                    for (String m : all.isEmpty() ? Set.of(Json.str(r, "source")) : all) {
                        if (!p.allows(perm, agent, site, m)) {
                            return false;
                        }
                    }
                }
            } else if (!p.allows(perm, agent, site, Json.str(c, "machine"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean visible(Principal p, JsonObject r, Map<String, String> sites) {
        String agent = Json.str(r, "agentId");
        if (p.allows(Permission.AGENTS_VIEW, agent, sites.get(agent), null)) {
            return true;
        }
        for (JsonElement e : r.getAsJsonArray("changes")) {
            String m = "writes".equals(Json.str(e.getAsJsonObject(), "kind")) ? Json.str(r, "source") : Json.str(e.getAsJsonObject(), "machine");
            if (SEERS.stream().anyMatch(x -> p.allows(x, agent, sites.get(agent), m))) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== store

    private static String key(long org, UUID id) {
        return KEY + org + "." + id;
    }

    /** the organization's requests, newest first */
    private List<JsonObject> all(long org) {
        return db.sql("SELECT value::text FROM {s}.bimba_gw_setting WHERE key LIKE ? ORDER BY value->>'requestedAt' DESC")
                .param(KEY + org + ".%").query(String.class).list().stream()
                .map(v -> Json.GSON.fromJson(v, JsonObject.class)).toList();
    }

    private JsonObject get(long org, String id) throws ApiException {
        UUID u;
        try {
            u = UUID.fromString(id);
        } catch (RuntimeException e) {
            throw new ApiException(404, "not_found", "That change request does not exist.");
        }
        return db.sql("SELECT value::text FROM {s}.bimba_gw_setting WHERE key = ?").param(key(org, u)).query(String.class).optional()
                .map(v -> Json.GSON.fromJson(v, JsonObject.class))
                .orElseThrow(() -> new ApiException(404, "not_found", "That change request does not exist."));
    }

    private JsonObject open(long org, String id) throws ApiException {
        JsonObject r = get(org, id);
        if (!"pending".equals(Json.str(r, "state"))) {
            throw new ApiException(409, "state", "This change request is no longer waiting (" + Json.str(r, "state") + ").");
        }
        return r;
    }

    /** decides a waiting request; false when it was not waiting any more */
    private boolean close(long org, JsonObject r, String state, Principal p, String why) {
        JsonObject d = new JsonObject();
        d.addProperty("state", state);
        d.addProperty("decidedAt", Instant.now().toString());
        if (p != null) {
            d.addProperty("decidedBy", p.username());
            d.addProperty("decidedByName", p.name());
        }
        if (why != null) {
            d.addProperty("why", why);
        }
        return db.sql("UPDATE {s}.bimba_gw_setting SET value = value || ?::jsonb, updated_at = now() WHERE key = ? AND value->>'state' = 'pending'")
                .params(d.toString(), key(org, UUID.fromString(Json.str(r, "id")))).update() > 0;
    }

    /** an approval that could not be put into the source waits again */
    private void reopen(long org, JsonObject r) {
        db.sql("UPDATE {s}.bimba_gw_setting SET value = (value - 'decidedBy' - 'decidedByName' - 'decidedAt' - 'why')"
                        + " || '{\"state\": \"pending\"}'::jsonb, updated_at = now() WHERE key = ? AND value->>'state' = 'approved'")
                .param(key(org, UUID.fromString(Json.str(r, "id")))).update();
    }

    private static List<JsonObject> changes(JsonObject r) {
        List<JsonObject> out = new ArrayList<>();
        r.getAsJsonArray("changes").forEach(e -> out.add(e.getAsJsonObject()));
        return out;
    }

    /** source id -> the machines it reports as */
    private Map<String, Set<String>> machines(long org) {
        Map<String, Set<String>> m = new java.util.HashMap<>();
        sources.sourcesOf(org).forEach(s -> m.put(Json.str(s, "id"), AgentSources.machines(s)));
        return m;
    }

    /** agent id -> site (for scopes) */
    private Map<String, String> sites(long org) {
        Map<String, String> m = new java.util.HashMap<>();
        db.sql("SELECT id::text, coalesce(site, '') FROM {s}.bimba_agent WHERE org_id = ?").param(org)
                .query((rs, n) -> m.put(rs.getString(1), rs.getString(2))).list();
        return m;
    }

    /** {display name, site} of an agent of the organization, or null */
    private String[] agentOf(long org, String agentId) {
        return db.sql("SELECT display_name, coalesce(site, '') FROM {s}.bimba_agent WHERE id = ?::uuid AND org_id = ?").params(agentId, org)
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).optional().orElse(null);
    }

    private static Map<String, Object> detail(JsonObject r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("requestId", Json.str(r, "id"));
        d.put("agentId", Json.str(r, "agentId"));
        d.put("sourceId", Json.str(r, "sourceId"));
        d.put("changes", Json.str(r, "summary"));
        d.put("requestedBy", Json.str(r, "requestedBy"));
        if (r.has("decidedBy")) {
            d.put("decidedBy", Json.str(r, "decidedBy"));
        }
        if (r.has("why") && !Json.str(r, "why").isBlank()) {
            d.put("why", Json.str(r, "why"));
        }
        return d;
    }
}
