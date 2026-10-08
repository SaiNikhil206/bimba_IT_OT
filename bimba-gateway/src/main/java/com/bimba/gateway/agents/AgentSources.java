package com.bimba.gateway.agents;

import com.bimba.gateway.Db;
import com.bimba.gateway.GatewayService;
import com.bimba.gateway.Json;
import com.bimba.gateway.config.SecretBox;
import com.bimba.gateway.delivery.DeliveryEngine;
import com.bimba.gateway.security.OrgService;
import com.bimba.gateway.security.SecurityService.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The BIMBA Site Agent as edge collector. Each agent reads the SCADA data sources configured for it here
 * (bimba_agent_source: OPC UA, Modbus TCP, OPC DA on its machine) and pushes the readings to
 * POST /system/bimba-agent-readings; they enter the delivery engine like the gateway's own readings, and every source's
 * machine name belongs to the agent's organization (bimba_gw_machine), so only that organization's pipelines get them.
 * <p>
 * Console requests that need the machine (test a connection, browse tags, read once) are commands
 * (bimba_agent_command): the agent long-polls /system/bimba-agent-commands and posts the answer back.
 */
@Service
public class AgentSources {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Agents");
    private static final Set<String> KINDS = Set.of("opcua", "modbus", "opcda");
    private static final Set<String> COMMANDS = Set.of("test", "browse", "read", "discover");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,119}");
    private static final int MAX_TAGS = 5000;
    private static final int MAX_BATCH = 5000;
    private static final long COMMAND_TTL_MS = 60_000;
    private static final long WAIT_MS = 25_000;
    private static final String MASK = "********";

    private final Db db;
    private final GatewayService gateway;
    private final AgentRegistry registry;
    private final OrgService orgs;
    private final SecretBox secrets;

    /** agent -> (machine/tag -> last message), for the console's Live tab */
    private final Map<UUID, Map<String, JsonObject>> live = new ConcurrentHashMap<>();
    /** agent -> its waiting command long-poll */
    private final Map<UUID, DeferredResult<ResponseEntity<Object>>> waiting = new ConcurrentHashMap<>();
    /** agent -> machine names it may report (cache, refreshed when sources change) */
    private final Map<UUID, Set<String>> machinesOf = new ConcurrentHashMap<>();
    private volatile List<Map<String, Object>> tagCache = List.of();
    /** agent -> readings accepted / rejected since start */
    private final Map<UUID, long[]> counters = new ConcurrentHashMap<>();

    public AgentSources(Db db, GatewayService gateway, AgentRegistry registry, OrgService orgs, SecretBox secrets) {
        this.db = db;
        this.gateway = gateway;
        this.registry = registry;
        this.orgs = orgs;
        this.secrets = secrets;
    }

    @PostConstruct
    void init() {
        // the agents' machines/tags join the gateway's own (Overview, pipeline selections, Organizations page)
        gateway.setAgentTags(() -> tagCache);
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("agent data sources not loaded yet: {}", e.getMessage());
        }
    }

    // ================================================================== agent endpoints

    /** GET /system/bimba-agent-config: the sources to collect, secrets in plain text (mutual TLS / HTTPS only). */
    public Map<String, Object> config(AgentRegistry.Caller caller) throws AgentRegistry.ApiError {
        AgentRegistry.Agent a = registry.authenticate(caller);
        List<Map<String, Object>> sources = new ArrayList<>();
        for (JsonObject s : rows("WHERE s.agent_id = ? ORDER BY s.name", a.id())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", Json.str(s, "id"));
            m.put("name", Json.str(s, "name"));
            m.put("kind", Json.str(s, "kind"));
            m.put("settings", Json.toJava(open(s.getAsJsonObject("settings")).toString()));
            m.put("tags", Json.toJava(s.get("tags").toString()));
            m.put("pollMs", s.get("pollMs").getAsInt());
            m.put("enabled", s.get("enabled").getAsBoolean());
            sources.add(m);
        }
        Object[] meta = db.sql("SELECT a.site, a.hostname, o.slug FROM {s}.bimba_agent a JOIN {s}.bimba_org o ON o.id = a.org_id WHERE a.id = ?")
                .param(a.id()).query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)}).single();
        String site = (String) meta[0];
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version(a.id()));
        out.put("plantId", site == null || site.isBlank() ? meta[2] : site);
        out.put("edgeId", meta[1]);
        out.put("sources", sources);
        return out;
    }

    /** changes whenever a source of the agent is added, edited or removed (sent with every heartbeat answer) */
    public String version(UUID agentId) {
        return db.sql("SELECT coalesce(count(*), 0) || '-' || coalesce(extract(epoch FROM max(updated_at))::bigint, 0)"
                        + " || '-' || coalesce(sum(length(name) + length(tags::text) + length(settings::text) + poll_ms"
                        + " + (CASE WHEN enabled THEN 1 ELSE 0 END)), 0) FROM {s}.bimba_agent_source WHERE agent_id = ?")
                .param(agentId).query(String.class).single();
    }

    public String version(String agentId) {
        try {
            return version(UUID.fromString(agentId));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * POST /system/bimba-agent-commands: the next console command for the agent, or 204 after ~25 s without one.
     * A command created meanwhile is handed out right away.
     */
    public DeferredResult<ResponseEntity<Object>> nextCommand(AgentRegistry.Caller caller) throws AgentRegistry.ApiError {
        AgentRegistry.Agent a = registry.authenticate(caller);
        DeferredResult<ResponseEntity<Object>> result = new DeferredResult<>(WAIT_MS + 5_000, ResponseEntity.noContent().build());
        Map<String, Object> cmd = claim(a.id());
        if (cmd != null) {
            result.setResult(ResponseEntity.ok(cmd));
            return result;
        }
        DeferredResult<ResponseEntity<Object>> previous = waiting.put(a.id(), result);
        if (previous != null) {
            previous.setResult(ResponseEntity.noContent().build());     // a newer poll replaces an older one
        }
        result.onCompletion(() -> waiting.remove(a.id(), result));
        // a command created between claim() and put() must not wait for the next poll
        Map<String, Object> late = claim(a.id());
        if (late != null && waiting.remove(a.id(), result)) {
            result.setResult(ResponseEntity.ok(late));
        }
        return result;
    }

    private Map<String, Object> claim(UUID agentId) {
        return db.sql("UPDATE {s}.bimba_agent_command SET state = 'sent', sent_at = now() WHERE id = ("
                        + " SELECT id FROM {s}.bimba_agent_command WHERE agent_id = ? AND state = 'pending'"
                        + " AND created_at > now() - interval '60 seconds' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED)"
                        + " RETURNING id, kind, request::text")
                .param(agentId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("kind", rs.getString(2));
                    m.put("request", Json.toJava(rs.getString(3)));
                    return m;
                })
                .optional().orElse(null);
    }

    /** POST /system/bimba-agent-commands/{id}/result body {ok, result | error} */
    public Map<String, Object> commandResult(AgentRegistry.Caller caller, String commandId, JsonObject body) throws AgentRegistry.ApiError {
        AgentRegistry.Agent a = registry.authenticate(caller);
        boolean ok = body.has("ok") && body.get("ok").getAsBoolean();
        String error = ok ? "" : AgentRegistry.str(body, "error", 4000);
        JsonElement result = body.get("result");
        int n = db.sql("UPDATE {s}.bimba_agent_command SET state = ?, result = ?::jsonb, error = ?, done_at = now()"
                        + " WHERE id = ? AND agent_id = ? AND state IN ('pending', 'sent')")
                .params(ok ? "done" : "failed", result == null || result.isJsonNull() ? null : result.toString(),
                        error == null ? "" : error, uuid(commandId), a.id())
                .update();
        return Map.of("ok", n > 0);
    }

    /**
     * POST /system/bimba-agent-readings body {messages:[canonical messages]}: each message must report one of the
     * agent's own machines (its sources' names); accepted ones go to the delivery engine. 503 while the gateway's
     * delivery engine is restarting, so the agent keeps the batch and sends it again.
     */
    public Map<String, Object> readings(AgentRegistry.Caller caller, JsonObject body, String remoteIp) throws AgentRegistry.ApiError {
        AgentRegistry.Agent a = registry.authenticate(caller);
        JsonArray messages = body.has("messages") && body.get("messages").isJsonArray() ? body.getAsJsonArray("messages") : null;
        if (messages == null) {
            throw new AgentRegistry.ApiError(400, "messages[] is required");
        }
        if (messages.size() > MAX_BATCH) {
            throw new AgentRegistry.ApiError(413, "at most " + MAX_BATCH + " messages per request");
        }
        DeliveryEngine delivery = gateway.delivery();
        if (delivery == null) {
            throw new AgentRegistry.ApiError(503, "the gateway's delivery is restarting; send again shortly");
        }
        Set<String> mine = machinesOf.computeIfAbsent(a.id(), this::loadMachines);
        Map<String, JsonObject> last = live.computeIfAbsent(a.id(), k -> new ConcurrentHashMap<>());
        int accepted = 0;
        int rejected = 0;
        List<String> unknown = new ArrayList<>();
        for (JsonElement e : messages) {
            if (!e.isJsonObject()) {
                rejected++;
                continue;
            }
            JsonObject m = e.getAsJsonObject();
            String machine = Json.str(m, "machineId");
            String tag = Json.str(m, "tagName");
            if (machine == null || tag == null || !mine.contains(machine)) {
                rejected++;
                if (machine != null && unknown.size() < 5 && !unknown.contains(machine)) {
                    unknown.add(machine);
                }
                continue;
            }
            JsonObject src = m.has("source") && m.get("source").isJsonObject() ? m.getAsJsonObject("source") : new JsonObject();
            src.addProperty("agentId", a.id().toString());
            m.add("source", src);
            delivery.offer(m);
            if (last.size() < MAX_TAGS || last.containsKey(machine + "/" + tag)) {
                last.put(machine + "/" + tag, m);
            }
            accepted++;
        }
        long[] c = counters.computeIfAbsent(a.id(), k -> new long[2]);
        synchronized (c) {
            c[0] += accepted;
            c[1] += rejected;
        }
        if (!unknown.isEmpty()) {
            log.warn("site agent {} sent readings for machines it does not own: {}", a.id(), unknown);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", accepted);
        out.put("rejected", rejected);
        if (!unknown.isEmpty()) {
            out.put("unknownMachines", unknown);
        }
        return out;
    }

    // ================================================================== console

    /** One agent of the organization with its inventory, sources (secrets masked) and collector status. */
    public Map<String, Object> detail(String agentId, long orgId) throws ApiException {
        UUID id = agentOf(agentId, orgId);
        Map<String, Object> agent = registry.list(orgId).stream().filter(x -> id.toString().equals(x.get("id"))).findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "That machine does not exist in this organization."));
        Object[] extra = db.sql("SELECT details::text, collector::text FROM {s}.bimba_agent WHERE id = ?").param(id)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2)}).single();
        Map<String, Object> m = new LinkedHashMap<>(agent);
        m.put("details", Json.toJava((String) extra[0]));
        m.put("collector", Json.toJava((String) extra[1]));
        List<Map<String, Object>> sources = new ArrayList<>();
        for (JsonObject s : rows("WHERE s.agent_id = ? ORDER BY s.name", id)) {
            sources.add(sourceJson(s));
        }
        m.put("sources", sources);
        long[] c = counters.get(id);
        m.put("readingsAccepted", c == null ? 0 : c[0]);
        m.put("readingsRejected", c == null ? 0 : c[1]);
        return m;
    }

    private static Map<String, Object> sourceJson(JsonObject s) {
        JsonObject settings = s.getAsJsonObject("settings").deepCopy();
        boolean hasPassword = settings.has("password") && !Json.str(settings, "password").isEmpty();
        settings.remove("password");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", Json.str(s, "id"));
        m.put("name", Json.str(s, "name"));
        m.put("kind", Json.str(s, "kind"));
        m.put("settings", Json.toJava(settings.toString()));
        m.put("hasPassword", hasPassword);
        m.put("tags", Json.toJava(s.get("tags").toString()));
        m.put("pollMs", s.get("pollMs").getAsInt());
        m.put("enabled", s.get("enabled").getAsBoolean());
        m.put("updatedAt", Json.str(s, "updatedAt"));
        m.put("createdBy", Json.str(s, "createdBy"));
        return m;
    }

    public Map<String, Object> saveSource(String agentId, String sourceId, JsonObject b, long orgId, String actor) throws ApiException {
        UUID agent = agentOf(agentId, orgId);
        String name = text(b, "name", 120);
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ApiException(400, "name", "Machine name: 1–120 letters, digits, spaces, '.', '_' or '-', starting with a letter or digit.");
        }
        String kind = text(b, "kind", 16);
        if (kind == null || !KINDS.contains(kind)) {
            throw new ApiException(400, "kind", "Choose OPC UA, Modbus TCP or OPC DA.");
        }
        JsonObject settings = b.has("settings") && b.get("settings").isJsonObject() ? b.getAsJsonObject("settings").deepCopy() : new JsonObject();
        validateSettings(kind, settings);
        JsonArray tags = tags(b, kind);
        int pollMs = b.has("pollMs") && b.get("pollMs").isJsonPrimitive() ? b.get("pollMs").getAsInt() : 1000;
        if (pollMs < 100 || pollMs > 3_600_000) {
            throw new ApiException(400, "pollMs", "Read every: 100 ms to 1 hour.");
        }
        boolean enabled = !b.has("enabled") || b.get("enabled").getAsBoolean();
        UUID sid = sourceId == null ? UUID.randomUUID() : uuidOr404(sourceId);
        JsonObject existing = sourceId == null ? null : rows("WHERE s.id = ? AND s.agent_id = ?", sid, agent).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "That data source does not exist."));
        // password: empty = keep the stored one; otherwise sealed
        String pw = Json.str(settings, "password");
        if ((pw == null || pw.isEmpty() || MASK.equals(pw)) && existing != null) {
            String old = Json.str(existing.getAsJsonObject("settings"), "password");
            if (old != null && !old.isEmpty()) {
                settings.addProperty("password", old);
            } else {
                settings.remove("password");
            }
        } else if (pw != null && !pw.isEmpty()) {
            settings.addProperty("password", secrets.seal(pw));
        }
        // the machine name must not belong to another organization or source
        Long owner = orgs.owners().get(name);
        if (owner != null && owner != orgId) {
            throw new ApiException(409, "name", "Machine name \"" + name + "\" belongs to another organization. Choose another name.");
        }
        boolean taken = db.sql("SELECT count(*) FROM {s}.bimba_agent_source WHERE lower(name) = lower(?) AND id <> ?")
                .params(name, sid).query(Long.class).single() > 0;
        if (taken) {
            throw new ApiException(409, "name", "Another data source already reports as \"" + name + "\". Choose another machine name.");
        }
        String oldName = existing == null ? null : Json.str(existing, "name");
        if (existing == null) {
            db.sql("INSERT INTO {s}.bimba_agent_source (id, org_id, agent_id, name, kind, settings, tags, poll_ms, enabled,"
                            + " created_at, updated_at, created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, now(), now(), ?)")
                    .params(sid, orgId, agent, name, kind, settings.toString(), tags.toString(), pollMs, enabled, actor).update();
        } else {
            db.sql("UPDATE {s}.bimba_agent_source SET name = ?, kind = ?, settings = ?::jsonb, tags = ?::jsonb, poll_ms = ?,"
                            + " enabled = ?, updated_at = now() WHERE id = ?")
                    .params(name, kind, settings.toString(), tags.toString(), pollMs, enabled, sid).update();
        }
        if (owner == null) {
            orgs.assign(name, orgId, "site agent " + actor);     // readings of this machine go to the organization's pipelines
        }
        if (oldName != null && !oldName.equals(name)) {
            releaseMachine(oldName, orgId);
        }
        refresh();
        reloadAgent(agent, orgId, actor);
        return sourceJson(rows("WHERE s.id = ?", sid).get(0));
    }

    public String deleteSource(String agentId, String sourceId, long orgId) throws ApiException {
        UUID agent = agentOf(agentId, orgId);
        String name = db.sql("DELETE FROM {s}.bimba_agent_source WHERE id = ? AND agent_id = ? RETURNING name")
                .params(uuidOr404(sourceId), agent).query(String.class).optional()
                .orElseThrow(() -> new ApiException(404, "not_found", "That data source does not exist."));
        releaseMachine(name, orgId);
        reloadAgent(agent, orgId, "console");
        Map<String, JsonObject> last = live.get(agent);
        if (last != null) {
            last.keySet().removeIf(k -> k.startsWith(name + "/"));
        }
        refresh();
        return name;
    }

    /** the machine goes back to "unassigned" once no source reports as it any more */
    private void releaseMachine(String name, long orgId) throws ApiException {
        boolean used = db.sql("SELECT count(*) FROM {s}.bimba_agent_source WHERE name = ?").param(name).query(Long.class).single() > 0;
        Long owner = orgs.owners().get(name);
        if (!used && owner != null && owner == orgId) {
            orgs.assign(name, null, "site agent");
        }
    }

    /**
     * Queues a command for the agent: body {kind: test|browse|read|discover, sourceId?, source?{kind, settings}, nodeId?,
     * limit?, tags?}. A stored source's password is used when the form leaves it empty.
     */
    public Map<String, Object> command(String agentId, JsonObject b, long orgId, String actor) throws ApiException {
        UUID agent = agentOf(agentId, orgId);
        String kind = text(b, "kind", 16);
        if (kind == null || !COMMANDS.contains(kind)) {
            throw new ApiException(400, "kind", "Unknown request.");
        }
        JsonObject req = new JsonObject();
        JsonObject source = b.has("source") && b.get("source").isJsonObject() ? b.getAsJsonObject("source").deepCopy() : null;
        String sourceId = text(b, "sourceId", 64);
        JsonObject stored = sourceId == null || sourceId.isBlank() ? null
                : rows("WHERE s.id = ? AND s.agent_id = ?", uuidOr404(sourceId), agent).stream().findFirst().orElse(null);
        if (source == null && stored != null) {
            source = new JsonObject();
            source.addProperty("kind", Json.str(stored, "kind"));
            source.add("settings", stored.getAsJsonObject("settings").deepCopy());
        }
        if (source != null) {
            JsonObject settings = source.has("settings") && source.get("settings").isJsonObject() ? source.getAsJsonObject("settings") : new JsonObject();
            String pw = Json.str(settings, "password");
            if ((pw == null || pw.isEmpty() || MASK.equals(pw)) && stored != null) {
                String old = Json.str(stored.getAsJsonObject("settings"), "password");
                if (old != null) {
                    settings.addProperty("password", old);
                }
            }
            source.add("settings", open(settings));
            req.add("source", source);
        } else if (!"discover".equals(kind)) {
            throw new ApiException(400, "source", "Which data source?");
        }
        for (String k : List.of("nodeId", "limit", "tags")) {
            if (b.has(k)) {
                req.add(k, b.get(k));
            }
        }
        UUID id = UUID.randomUUID();
        db.sql("DELETE FROM {s}.bimba_agent_command WHERE created_at < now() - interval '1 day'").update();
        db.sql("INSERT INTO {s}.bimba_agent_command (id, org_id, agent_id, kind, request, state, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, 'pending', now(), ?)")
                .params(id, orgId, agent, kind, req.toString(), actor).update();
        wake(agent);
        return Map.of("id", id.toString());
    }

    /** tells a waiting agent to fetch its data sources now (instead of at its next heartbeat) */
    private void reloadAgent(UUID agent, long orgId, String actor) {
        db.sql("DELETE FROM {s}.bimba_agent_command WHERE agent_id = ? AND kind = 'config' AND state = 'pending'").param(agent).update();
        db.sql("INSERT INTO {s}.bimba_agent_command (id, org_id, agent_id, kind, request, state, created_at, created_by)"
                        + " VALUES (?, ?, ?, 'config', '{}'::jsonb, 'pending', now(), ?)")
                .params(UUID.randomUUID(), orgId, agent, actor).update();
        wake(agent);
    }

    private void wake(UUID agent) {
        DeferredResult<ResponseEntity<Object>> w = waiting.remove(agent);
        if (w != null) {
            Map<String, Object> cmd = claim(agent);
            w.setResult(cmd != null ? ResponseEntity.ok(cmd) : ResponseEntity.noContent().build());
        }
    }

    /** {state: pending|sent|done|failed|expired, result, error} */
    public Map<String, Object> commandState(String agentId, String commandId, long orgId) throws ApiException {
        UUID agent = agentOf(agentId, orgId);
        Object[] row = db.sql("SELECT state, result::text, error, created_at, now() FROM {s}.bimba_agent_command WHERE id = ? AND agent_id = ?")
                .params(uuidOr404(commandId), agent)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4), rs.getTimestamp(5)})
                .optional().orElseThrow(() -> new ApiException(404, "not_found", "Unknown request."));
        String state = (String) row[0];
        String error = (String) row[2];
        if (("pending".equals(state) || "sent".equals(state))
                && ((Timestamp) row[4]).getTime() - ((Timestamp) row[3]).getTime() > COMMAND_TTL_MS) {
            state = "expired";
            error = "pending".equals(row[0]) ? "The machine did not pick up the request. Is the agent online and version 1.4 or later?"
                    : "The machine did not answer in time.";
            db.sql("UPDATE {s}.bimba_agent_command SET state = 'expired', error = ? WHERE id = ?").params(error, uuidOr404(commandId)).update();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("result", row[1] == null ? null : Json.toJava((String) row[1]));
        m.put("error", error == null || error.isEmpty() ? null : error);
        return m;
    }

    /** last value of every tag the agent sent since the gateway started */
    public Map<String, Object> live(String agentId, long orgId) throws ApiException {
        UUID agent = agentOf(agentId, orgId);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, JsonObject> last = live.getOrDefault(agent, Map.of());
        last.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            JsonObject m = e.getValue();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("machine", Json.str(m, "machineId"));
            v.put("tag", Json.str(m, "tagName"));
            v.put("value", m.has("tagValue") ? Json.toJava(m.get("tagValue").toString()) : null);
            v.put("unit", Json.str(m, "unit"));
            v.put("quality", Json.str(m, "quality"));
            v.put("detail", Json.str(m, "qualityDetail"));
            v.put("timestamp", Json.str(m, "timestamp"));
            out.add(v);
        });
        long[] c = counters.get(agent);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("values", out);
        r.put("accepted", c == null ? 0 : c[0]);
        r.put("rejected", c == null ? 0 : c[1]);
        r.put("now", Instant.now().toString());
        return r;
    }

    // ================================================================== helpers

    /** machines + tags of every agent source, in the collector status shape ({machine, tag, unit}) */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void refresh() {
        List<Map<String, Object>> tags = new ArrayList<>();
        Map<UUID, Set<String>> byAgent = new LinkedHashMap<>();
        for (JsonObject s : rows("WHERE s.enabled ORDER BY s.name")) {
            String machine = Json.str(s, "name");
            byAgent.computeIfAbsent(UUID.fromString(Json.str(s, "agentId")), k -> ConcurrentHashMap.newKeySet()).add(machine);
            for (JsonElement t : s.getAsJsonArray("tags")) {
                if (!t.isJsonObject()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("machine", machine);
                m.put("tag", tagName(t.getAsJsonObject()));
                m.put("unit", Json.str(t.getAsJsonObject(), "unit"));
                m.put("agent", true);
                tags.add(m);
            }
        }
        tagCache = List.copyOf(tags);
        machinesOf.clear();
        machinesOf.putAll(byAgent);
    }

    /** same rule as the edge collector: lower case, non-alphanumerics -> "_" */
    static String tagName(JsonObject t) {
        String n = Json.str(t, "name");
        if (n == null || n.isBlank()) {
            n = Json.str(t, "address");
        }
        return (n == null ? "tag" : n).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private Set<String> loadMachines(UUID agent) {
        Set<String> s = ConcurrentHashMap.newKeySet();
        db.sql("SELECT name FROM {s}.bimba_agent_source WHERE agent_id = ? AND enabled").param(agent).query(String.class).list().forEach(s::add);
        return s;
    }

    private List<JsonObject> rows(String where, Object... params) {
        return db.sql("SELECT s.id, s.agent_id, s.name, s.kind, s.settings::text, s.tags::text, s.poll_ms, s.enabled, s.updated_at,"
                        + " s.created_by FROM {s}.bimba_agent_source s " + where)
                .params(params)
                .query((rs, n) -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", rs.getString(1));
                    o.addProperty("agentId", rs.getString(2));
                    o.addProperty("name", rs.getString(3));
                    o.addProperty("kind", rs.getString(4));
                    o.add("settings", Json.GSON.fromJson(rs.getString(5), JsonObject.class));
                    o.add("tags", Json.GSON.fromJson(rs.getString(6), JsonArray.class));
                    o.addProperty("pollMs", rs.getInt(7));
                    o.addProperty("enabled", rs.getBoolean(8));
                    o.addProperty("updatedAt", rs.getTimestamp(9).toInstant().toString());
                    o.addProperty("createdBy", rs.getString(10));
                    return o;
                })
                .list();
    }

    private JsonObject open(JsonObject settings) {
        JsonObject s = settings.deepCopy();
        String pw = Json.str(s, "password");
        if (pw != null && SecretBox.isSealed(pw)) {
            s.addProperty("password", secrets.open(pw));
        }
        return s;
    }

    private UUID agentOf(String agentId, long orgId) throws ApiException {
        UUID id = uuidOr404(agentId);
        boolean mine = db.sql("SELECT count(*) FROM {s}.bimba_agent WHERE id = ? AND org_id = ?").params(id, orgId).query(Long.class).single() > 0;
        if (!mine) {
            throw new ApiException(404, "not_found", "That machine does not exist in this organization.");
        }
        return id;
    }

    private static void validateSettings(String kind, JsonObject s) throws ApiException {
        switch (kind) {
            case "opcua" -> {
                String ep = Json.str(s, "endpoint");
                if (ep == null || !ep.trim().startsWith("opc.tcp://") || ep.length() > 300) {
                    throw new ApiException(400, "endpoint", "Endpoint: an address like opc.tcp://localhost:62541.");
                }
                s.addProperty("endpoint", ep.trim());
                String auth = Json.str(s, "auth");
                if ("Username".equals(auth) && (Json.str(s, "username") == null || Json.str(s, "username").isBlank())) {
                    throw new ApiException(400, "username", "Enter the OPC UA user name.");
                }
            }
            case "modbus" -> {
                String host = Json.str(s, "host");
                if (host == null || host.isBlank() || host.length() > 255) {
                    throw new ApiException(400, "host", "Enter the PLC's host name or IP address.");
                }
                int port = s.has("port") ? s.get("port").getAsInt() : 502;
                if (port < 1 || port > 65535) {
                    throw new ApiException(400, "port", "Port: 1–65535.");
                }
            }
            case "opcda" -> {
                String prog = Json.str(s, "progId");
                if (prog == null || prog.isBlank() || prog.length() > 200) {
                    throw new ApiException(400, "progId", "Choose the OPC DA server (ProgID, e.g. OPCLabs.KitServer.2).");
                }
            }
            default -> throw new ApiException(400, "kind", "Unknown source type.");
        }
    }

    private static JsonArray tags(JsonObject b, String kind) throws ApiException {
        JsonArray in = b.has("tags") && b.get("tags").isJsonArray() ? b.getAsJsonArray("tags") : new JsonArray();
        if (in.size() > MAX_TAGS) {
            throw new ApiException(400, "tags", "At most " + MAX_TAGS + " tags per data source.");
        }
        JsonArray out = new JsonArray();
        Set<String> names = new java.util.HashSet<>();
        for (JsonElement e : in) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject t = e.getAsJsonObject();
            String address = Json.str(t, "address");
            if (address == null || address.isBlank() || address.length() > 500) {
                throw new ApiException(400, "tags", "Every tag needs an address.");
            }
            if ("modbus".equals(kind) && !address.trim().toUpperCase(Locale.ROOT).matches("^(?:\\d+\\.)?(HR|IR|C|DI)\\d+$")) {
                throw new ApiException(400, "tags", "Modbus address \"" + address + "\": use HR1, IR5, C3 or DI2 (optionally 2.HR1 for unit 2).");
            }
            JsonObject clean = new JsonObject();
            clean.addProperty("address", address.trim());
            String name = Json.str(t, "name");
            clean.addProperty("name", name == null || name.isBlank() ? address.trim() : name.trim());
            String tn = tagName(clean);
            if (tn.isEmpty() || !names.add(tn)) {
                throw new ApiException(400, "tags", "Tag names must be unique (\"" + clean.get("name").getAsString() + "\").");
            }
            for (String k : List.of("unit", "dataType")) {
                String v = Json.str(t, k);
                if (v != null && !v.isBlank()) {
                    clean.addProperty(k, v.trim().length() > 40 ? v.trim().substring(0, 40) : v.trim());
                }
            }
            for (String k : List.of("rawLow", "rawHigh", "scaledLow", "scaledHigh")) {
                if (t.has(k) && t.get(k).isJsonPrimitive() && !t.get(k).getAsString().isBlank()) {
                    try {
                        clean.addProperty(k, Double.parseDouble(t.get(k).getAsString()));
                    } catch (NumberFormatException ex) {
                        throw new ApiException(400, "tags", "Scaling of \"" + clean.get("name").getAsString() + "\" must be numbers.");
                    }
                }
            }
            out.add(clean);
        }
        return out;
    }

    private static String text(JsonObject b, String k, int max) {
        String v = Json.str(b, k);
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static UUID uuid(String v) throws AgentRegistry.ApiError {
        try {
            return UUID.fromString(v);
        } catch (RuntimeException e) {
            throw new AgentRegistry.ApiError(404, "unknown command");
        }
    }

    private static UUID uuidOr404(String v) throws ApiException {
        try {
            return UUID.fromString(v);
        } catch (RuntimeException e) {
            throw new ApiException(404, "not_found", "Not found.");
        }
    }
}
