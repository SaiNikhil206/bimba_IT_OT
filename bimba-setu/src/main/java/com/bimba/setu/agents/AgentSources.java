package com.bimba.setu.agents;

import com.bimba.setu.Db;
import com.bimba.setu.SetuService;
import com.bimba.setu.Json;
import com.bimba.setu.canonical.Ingest;
import com.bimba.setu.collect.SqlDriver;
import com.bimba.setu.collect.Units;
import com.bimba.setu.config.SecretBox;
import com.bimba.setu.delivery.DeliveryEngine;
import com.bimba.setu.security.OrgService;
import com.bimba.setu.security.SecurityService.ApiException;
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
 * (bimba_agent_source: OPC UA, Modbus TCP, OPC DA, MQTT, S7, Logix, DNP3, IEC 104, SQL) and pushes the readings to
 * POST /system/bimba-agent-readings; they enter the delivery engine like Setu's own readings, and every source's
 * machine name belongs to the agent's organization (bimba_gw_machine), so only that organization's pipelines get them.
 * <p>
 * Console requests that need the machine (test a connection, browse tags, read once) are commands
 * (bimba_agent_command): the agent long-polls /system/bimba-agent-commands and posts the answer back.
 * <p>
 * One source normally reports as one machine (its name). An OPC UA source in "one machine per device" mode
 * (settings.machinesFrom = the server's devices folder) reports as several: every tag carries its machine, and
 * settings.devices maps each device's node id to its machine name ("" = ignored). Those sources are scanned again every
 * {@link #SCAN_EVERY_MS} ({@link #syncDevices}): devices that appeared on the server since are suggested in the console,
 * or added right away when settings.newDevices = "add".
 */
@Service
public class AgentSources {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Agents");
    private static final Set<String> KINDS = Set.of("opcua", "modbus", "opcda", "mqtt", "s7", "eip", "dnp3", "iec104", "sql");
    private static final Set<String> S7_CPUS = Set.of("S7_300", "S7_400", "S7_1200", "S7_1500", "LOGO");
    private static final Set<String> MQTT_PAYLOADS = Set.of("value", "json", "sparkplug");
    private static final Set<String> COMMANDS = Set.of("test", "browse", "read", "discover", "scan");
    private static final Set<String> NEW_DEVICES = Set.of("suggest", "add", "ignore");
    private static final long SCAN_EVERY_MS = 10 * 60_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,119}");
    private static final int MAX_TAGS = 5000;
    private static final int MAX_BATCH = 5000;
    private static final int MAX_BODY = 256 * 1024;           // as every other Setu request (web/ApiErrors)
    private static final long COMMAND_TTL_MS = 60_000;
    private static final long WAIT_MS = 25_000;
    private static final String MASK = "********";

    private final Db db;
    private final SetuService setu;
    private final AgentRegistry registry;
    private final OrgService orgs;
    private final SecretBox secrets;
    private final Ingest ingest;

    /** agent -> (machine/tag -> last message), for the console's Live tab */
    private final Map<UUID, Map<String, JsonObject>> live = new ConcurrentHashMap<>();
    /** a waiting command long-poll; secure = it came over HTTPS (passwords may be handed out) */
    private record Poll(DeferredResult<ResponseEntity<Object>> result, boolean secure) {
    }

    /** agent -> its waiting command long-poll */
    private final Map<UUID, Poll> waiting = new ConcurrentHashMap<>();
    /** agents already warned that they fetch data sources over plain HTTP */
    private final Set<UUID> warnedPlain = ConcurrentHashMap.newKeySet();
    /** agent -> machine names it may report (cache, refreshed when sources change) */
    private final Map<UUID, Set<String>> machinesOf = new ConcurrentHashMap<>();
    private volatile List<Map<String, Object>> tagCache = List.of();
    /** agent -> readings accepted / rejected since start */
    private final Map<UUID, long[]> counters = new ConcurrentHashMap<>();

    public AgentSources(Db db, SetuService setu, AgentRegistry registry, OrgService orgs, SecretBox secrets, Ingest ingest) {
        this.ingest = ingest;
        this.db = db;
        this.setu = setu;
        this.registry = registry;
        this.orgs = orgs;
        this.secrets = secrets;
    }

    @PostConstruct
    void init() {
        // the agents' machines/tags join Setu's own (Overview, pipeline selections, Organizations page)
        setu.setAgentTags(() -> tagCache);
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("agent data sources not loaded yet: {}", e.getMessage());
        }
    }

    // ================================================================== agent endpoints

    /**
     * GET /system/bimba-agent-config: the sources to collect, passwords in plain text over HTTPS / mutual TLS only
     * (over plain HTTP they are left out, so a source that needs one cannot sign in until the agent uses HTTPS).
     */
    public Map<String, Object> config(AgentRegistry.Caller caller) throws AgentRegistry.ApiError {
        AgentRegistry.Agent a = registry.authenticate(caller);
        List<Map<String, Object>> sources = new ArrayList<>();
        for (JsonObject s : rows("WHERE s.agent_id = ? ORDER BY s.name", a.id())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", Json.str(s, "id"));
            m.put("name", Json.str(s, "name"));
            m.put("kind", Json.str(s, "kind"));
            m.put("settings", Json.toJava(open(s.getAsJsonObject("settings"), caller.secure(), a.id()).toString()));
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
        boolean secure = caller.secure();
        Map<String, Object> cmd = claim(a.id(), secure);
        if (cmd != null) {
            result.setResult(ResponseEntity.ok(cmd));
            return result;
        }
        Poll poll = new Poll(result, secure);
        Poll previous = waiting.put(a.id(), poll);
        if (previous != null) {
            previous.result().setResult(ResponseEntity.noContent().build());     // a newer poll replaces an older one
        }
        result.onCompletion(() -> waiting.remove(a.id(), poll));
        // a command created between claim() and put() must not wait for the next poll
        Map<String, Object> late = claim(a.id(), secure);
        if (late != null && waiting.remove(a.id(), poll)) {
            result.setResult(ResponseEntity.ok(late));
        }
        return result;
    }

    private Map<String, Object> claim(UUID agentId, boolean secure) {
        return db.sql("UPDATE {s}.bimba_agent_command SET state = 'sent', sent_at = now() WHERE id = ("
                        + " SELECT id FROM {s}.bimba_agent_command WHERE agent_id = ? AND state = 'pending'"
                        + " AND created_at > now() - interval '60 seconds' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED)"
                        + " RETURNING id, kind, request::text")
                .param(agentId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("kind", rs.getString(2));
                    m.put("request", Json.toJava(openRequest(rs.getString(3), secure, agentId)));
                    return m;
                })
                .optional().orElse(null);
    }

    /** a queued request keeps the password sealed; the agent gets it in plain text over HTTPS / mutual TLS only */
    private String openRequest(String json, boolean secure, UUID agentId) {
        JsonObject req = json == null ? new JsonObject() : Json.GSON.fromJson(json, JsonObject.class);
        if (req.has("source") && req.get("source").isJsonObject()) {
            JsonObject src = req.getAsJsonObject("source");
            if (src.has("settings") && src.get("settings").isJsonObject()) {
                src.add("settings", open(src.getAsJsonObject("settings"), secure, agentId));
            }
        }
        return req.toString();
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
     * POST /system/bimba-agent-readings body {messages:[canonical messages]} or {records:[..]}: each message (1.x or 2.x)
     * must match its schema version
     * and report one of the agent's own machines (its sources' names); accepted ones go to the delivery engine, the
     * others to the quarantine with their raw text and reason (canonical/Ingest). A body that is not JSON is
     * quarantined as it came (400). 503 while Setu's delivery engine is restarting, so the agent keeps the batch
     * and sends it again.
     */
    public Map<String, Object> readings(AgentRegistry.Caller caller, String body, String remoteIp) throws AgentRegistry.ApiError {
        AgentRegistry.Agent a = registry.authenticate(caller);
        if (body != null && body.length() > MAX_BODY) {
            throw new AgentRegistry.ApiError(413, "at most " + MAX_BODY / 1024 + " KB per request");
        }
        JsonObject parsed;
        try {
            parsed = Json.GSON.fromJson(body == null || body.isBlank() ? "{}" : body, JsonObject.class);
        } catch (RuntimeException e) {
            parsed = null;
        }
        JsonArray messages = Ingest.batch(parsed);           // {messages:[..]} (1.x) or {records:[..]} (2.x batch form)
        if (messages == null) {
            if (body != null && !body.isBlank()) {
                ingest.malformedFromAgent(a.id(), a.orgId(), body, parsed == null ? "the request body is not JSON" : "messages[] / records[] is missing");
            }
            throw new AgentRegistry.ApiError(400, "messages[] is required");
        }
        if (messages.size() > MAX_BATCH) {
            throw new AgentRegistry.ApiError(413, "at most " + MAX_BATCH + " messages per request");
        }
        DeliveryEngine delivery = setu.delivery();
        if (delivery == null) {
            throw new AgentRegistry.ApiError(503, "Setu's delivery is restarting; send again shortly");
        }
        Ingest.AgentResult r = ingest.fromAgent(a.id(), a.orgId(), messages, machinesOf(a.id()), delivery::offer);
        Map<String, JsonObject> last = live.computeIfAbsent(a.id(), k -> new ConcurrentHashMap<>());
        for (JsonObject m : r.accepted()) {
            String key = Json.str(m, "machineId") + "/" + Json.str(m, "tagName");
            if (last.size() < MAX_TAGS || last.containsKey(key)) {
                last.put(key, m);
            }
        }
        int accepted = r.accepted().size();
        int rejected = r.rejected();
        List<String> unknown = r.unknownMachines();
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
        if (rejected > 0) {
            out.put("quarantined", rejected);
        }
        if (!unknown.isEmpty()) {
            out.put("unknownMachines", unknown);
        }
        return out;
    }

    /** the machine names an agent may report (its sources' machines), for readings and quarantine replays */
    public Set<String> machinesOf(UUID agentId) {
        return machinesOf.computeIfAbsent(agentId, this::loadMachines);
    }

    // ================================================================== console

    /** One agent of the organization with its inventory, sources (secrets masked) and collector status. */
    public Map<String, Object> detail(String agentId, long orgId) throws ApiException {
        UUID id = agentOf(agentId, orgId);
        Map<String, Object> agent = registry.list(orgId).stream().filter(x -> id.toString().equals(x.get("id"))).findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "That site agent does not exist in this organization."));
        Object[] extra = db.sql("SELECT details::text, collector::text FROM {s}.bimba_agent WHERE id = ?").param(id)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2)}).single();
        Map<String, Object> m = new LinkedHashMap<>(agent);
        m.put("details", Json.toJava((String) extra[0]));
        m.put("collector", Json.toJava((String) extra[1]));
        List<Map<String, Object>> sources = new ArrayList<>();
        for (JsonObject s : rows("WHERE s.agent_id = ? ORDER BY s.name", id)) {
            Map<String, Object> sj = sourceJson(s);
            if (perDevice(s)) {
                sj.put("scan", scanState(s));
            }
            sources.add(sj);
        }
        m.put("sources", sources);
        long[] c = counters.get(id);
        m.put("readingsAccepted", c == null ? 0 : c[0]);
        m.put("readingsRejected", c == null ? 0 : c[1]);
        return m;
    }

    /** agent id -> what it collects: {sources: n, machines: [names]} (paused sources count, their machines too) */
    public Map<String, Map<String, Object>> summary(long orgId) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (JsonObject s : rows("WHERE s.org_id = ? ORDER BY s.name", orgId)) {
            Map<String, Object> m = out.computeIfAbsent(Json.str(s, "agentId"), k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("sources", 0);
                x.put("machines", new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER));
                return x;
            });
            m.merge("sources", 1, (a, b) -> (Integer) a + (Integer) b);
            @SuppressWarnings("unchecked")
            Set<String> names = (Set<String>) m.get("machines");
            names.addAll(machines(s));
        }
        out.values().forEach(m -> m.put("machines", List.copyOf((Set<?>) m.get("machines"))));
        return out;
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
        Map<String, Integer> perMachine = new LinkedHashMap<>();
        machines(s).forEach(x -> perMachine.put(x, 0));
        for (JsonElement t : s.getAsJsonArray("tags")) {
            perMachine.merge(machineOf(s, t.getAsJsonObject()), 1, Integer::sum);
        }
        List<Map<String, Object>> ms = new ArrayList<>();
        perMachine.forEach((k, v) -> ms.add(Map.of("name", k, "tags", v)));
        m.put("machines", ms);
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
            throw new ApiException(400, "kind", "Choose OPC UA, Modbus TCP, OPC DA, MQTT, Siemens S7 or EtherNet/IP.");
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
        // password: empty = keep the stored one (a duplicate's: the original's); otherwise sealed
        JsonObject keepFrom = existing != null ? existing : sameOrgSource(text(b, "copyOf", 64), orgId);
        String pw = Json.str(settings, "password");
        if ((pw == null || pw.isEmpty() || MASK.equals(pw)) && keepFrom != null) {
            String old = Json.str(keepFrom.getAsJsonObject("settings"), "password");
            if (old != null && !old.isEmpty()) {
                settings.addProperty("password", old);
            } else {
                settings.remove("password");
            }
        } else if (pw != null && !pw.isEmpty()) {
            settings.addProperty("password", secrets.seal(pw));
        }
        JsonObject draft = new JsonObject();
        draft.addProperty("name", name);
        draft.addProperty("kind", kind);
        draft.add("settings", settings);
        draft.add("tags", tags);
        Set<String> wanted = machines(draft);
        // the source's name and machines must not belong to another organization or source
        Map<String, Long> owners = orgs.owners();
        Map<String, String> taken = takenNames(sid);
        Set<String> check = new java.util.LinkedHashSet<>(wanted);
        check.add(name);
        for (String x : check) {
            Long owner = owners.get(x);
            if (owner != null && owner != orgId) {
                throw new ApiException(409, x.equals(name) ? "name" : "tags",
                        "Machine name \"" + x + "\" belongs to another organization. Choose another name.");
            }
            String other = taken.get(x.toLowerCase(Locale.ROOT));
            if (other != null) {
                throw new ApiException(409, x.equals(name) ? "name" : "tags", "Data source \"" + other + "\" already uses the name \"" + x
                        + "\". Choose another" + (x.equals(name) ? " name." : " machine name (or a name prefix)."));
            }
        }
        Set<String> before = existing == null ? Set.of() : machines(existing);
        if (existing == null) {
            db.sql("INSERT INTO {s}.bimba_agent_source (id, org_id, agent_id, name, kind, settings, tags, poll_ms, enabled,"
                            + " created_at, updated_at, created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, now(), now(), ?)")
                    .params(sid, orgId, agent, name, kind, settings.toString(), tags.toString(), pollMs, enabled, actor).update();
        } else {
            db.sql("UPDATE {s}.bimba_agent_source SET name = ?, kind = ?, settings = ?::jsonb, tags = ?::jsonb, poll_ms = ?,"
                            + " enabled = ?, updated_at = now() WHERE id = ?")
                    .params(name, kind, settings.toString(), tags.toString(), pollMs, enabled, sid).update();
        }
        for (String x : wanted) {
            if (owners.get(x) == null) {
                orgs.assign(x, orgId, "site agent " + actor);     // readings of this machine go to the organization's pipelines
            }
        }
        for (String x : before) {
            if (!wanted.contains(x)) {
                releaseMachine(x, orgId);
                Map<String, JsonObject> last = live.get(agent);
                if (last != null) {
                    last.keySet().removeIf(k -> k.startsWith(x + "/"));
                }
            }
        }
        refresh();
        reloadAgent(agent, orgId, actor);
        return sourceJson(rows("WHERE s.id = ?", sid).get(0));
    }

    public String deleteSource(String agentId, String sourceId, long orgId) throws ApiException {
        UUID agent = agentOf(agentId, orgId);
        UUID sid = uuidOr404(sourceId);
        JsonObject s = rows("WHERE s.id = ? AND s.agent_id = ?", sid, agent).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "That data source does not exist."));
        db.sql("DELETE FROM {s}.bimba_agent_source WHERE id = ?").param(sid).update();
        Map<String, JsonObject> last = live.get(agent);
        for (String x : machines(s)) {
            releaseMachine(x, orgId);
            if (last != null) {
                last.keySet().removeIf(k -> k.startsWith(x + "/"));
            }
        }
        reloadAgent(agent, orgId, "console");
        refresh();
        return Json.str(s, "name");
    }

    /** the machine goes back to "unassigned" once no source reports as it any more */
    private void releaseMachine(String name, long orgId) throws ApiException {
        boolean used = rows("").stream().anyMatch(s -> machines(s).contains(name));
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
        // a duplicate being set up: its form has no password, the original's is used (the result is not the original's)
        JsonObject passwordOf = stored != null ? stored : sameOrgSource(text(b, "passwordFrom", 64), orgId);
        if (source == null && stored != null) {
            source = new JsonObject();
            source.addProperty("kind", Json.str(stored, "kind"));
            source.add("settings", stored.getAsJsonObject("settings").deepCopy());
        }
        if (source != null) {
            JsonObject settings = source.has("settings") && source.get("settings").isJsonObject() ? source.getAsJsonObject("settings") : new JsonObject();
            String pw = Json.str(settings, "password");
            if ((pw == null || pw.isEmpty() || MASK.equals(pw)) && passwordOf != null) {
                String old = Json.str(passwordOf.getAsJsonObject("settings"), "password");
                if (old != null) {
                    settings.addProperty("password", old);
                }
            }
            if (pw != null && !pw.isEmpty() && !MASK.equals(pw)) {
                settings.addProperty("password", secrets.seal(pw));     // opened when the agent fetches the request
            }
            source.add("settings", settings);
            req.add("source", source);
        } else if (!"discover".equals(kind)) {
            throw new ApiException(400, "source", "Which data source?");
        }
        if (stored != null) {
            req.addProperty("sourceId", Json.str(stored, "id"));          // a scan's result belongs to its source
        }
        for (String k : List.of("nodeId", "limit", "tags")) {
            if (b.has(k)) {
                req.add(k, b.get(k));
            }
        }
        return Map.of("id", queue(agent, orgId, kind, req, actor).toString());
    }

    private UUID queue(UUID agent, long orgId, String kind, JsonObject req, String actor) {
        UUID id = UUID.randomUUID();
        db.sql("DELETE FROM {s}.bimba_agent_command WHERE created_at < now() - interval '1 day'").update();
        db.sql("INSERT INTO {s}.bimba_agent_command (id, org_id, agent_id, kind, request, state, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, 'pending', now(), ?)")
                .params(id, orgId, agent, kind, req.toString(), actor).update();
        wake(agent);
        return id;
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
        Poll w = waiting.remove(agent);
        if (w != null) {
            Map<String, Object> cmd = claim(agent, w.secure());
            w.result().setResult(cmd != null ? ResponseEntity.ok(cmd) : ResponseEntity.noContent().build());
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
            error = "pending".equals(row[0]) ? "The agent did not pick up the request. Is it online and version 1.4 or later?"
                    : "The agent did not answer in time.";
            db.sql("UPDATE {s}.bimba_agent_command SET state = 'expired', error = ? WHERE id = ?").params(error, uuidOr404(commandId)).update();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("result", row[1] == null ? null : Json.toJava((String) row[1]));
        m.put("error", error == null || error.isEmpty() ? null : error);
        return m;
    }

    /** last value of every tag the agent sent since Setu started */
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

    // ================================================================== devices as machines

    /** the console's answer to a device the last scan found: body {nodeId, action: add|ignore, machine?} */
    public Map<String, Object> device(String agentId, String sourceId, JsonObject b, long orgId, String actor) throws ApiException {
        UUID agent = agentOf(agentId, orgId);
        JsonObject s = rows("WHERE s.id = ? AND s.agent_id = ?", uuidOr404(sourceId), agent).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "That data source does not exist."));
        if (!perDevice(s)) {
            throw new ApiException(400, "source", "This data source reports as one machine.");
        }
        String nodeId = text(b, "nodeId", 500);
        Scan scan = lastScan(s);
        JsonObject dev = scan == null || nodeId == null ? null : scan.machines().stream()
                .filter(d -> nodeId.equals(Json.str(d, "nodeId"))).findFirst().orElse(null);
        if (dev == null) {
            throw new ApiException(404, "nodeId", "The last scan of the server did not find that device. Check again.");
        }
        String action = text(b, "action", 16);
        if ("ignore".equals(action)) {
            return addDevices(s, List.of(), List.of(nodeId), orgId, actor);
        }
        if (!"add".equals(action)) {
            throw new ApiException(400, "action", "Add or ignore?");
        }
        JsonObject d = dev.deepCopy();
        String machine = text(b, "machine", 120);
        if (machine != null && !machine.isEmpty()) {
            d.addProperty("machine", machine);
        }
        return addDevices(s, List.of(d), List.of(), orgId, actor);
    }

    /**
     * Adds scanned devices as machines of the source (tags and settings.devices) and marks others as ignored, through
     * {@link #saveSource}'s checks. A device without a chosen machine name is named prefix + device name, made unique.
     */
    private Map<String, Object> addDevices(JsonObject s, List<JsonObject> add, List<String> ignore, long orgId, String actor)
            throws ApiException {
        JsonObject settings = s.getAsJsonObject("settings").deepCopy();
        settings.remove("password");                                        // empty = keep the stored one
        JsonObject devices = settings.has("devices") && settings.get("devices").isJsonObject()
                ? settings.getAsJsonObject("devices") : new JsonObject();
        JsonArray tags = s.getAsJsonArray("tags").deepCopy();
        Map<String, String> taken = takenNames(UUID.fromString(Json.str(s, "id")));
        Set<String> mine = new java.util.HashSet<>();
        machines(s).forEach(x -> mine.add(x.toLowerCase(Locale.ROOT)));
        Map<String, Long> owners = orgs.owners();
        String prefix = Json.str(settings, "machinePrefix");
        for (String n : ignore) {
            devices.addProperty(n, "");
        }
        for (JsonObject d : add) {
            String chosen = Json.str(d, "machine");
            String m;
            if (chosen != null && !chosen.isBlank()) {
                m = chosen.trim();
                if (mine.contains(m.toLowerCase(Locale.ROOT))) {
                    throw new ApiException(409, "machine", "\"" + m + "\" is already a machine of this data source. Choose another name.");
                }
            } else {
                String base = machineName((prefix == null ? "" : prefix) + Json.str(d, "name"));
                m = base;
                for (int i = 2; mine.contains(m.toLowerCase(Locale.ROOT)) || taken.containsKey(m.toLowerCase(Locale.ROOT))
                        || (owners.get(m) != null && owners.get(m) != orgId); i++) {
                    m = base.substring(0, Math.min(base.length(), 115)) + "-" + i;
                }
            }
            mine.add(m.toLowerCase(Locale.ROOT));
            for (JsonElement t : d.has("tags") && d.get("tags").isJsonArray() ? d.getAsJsonArray("tags") : new JsonArray()) {
                JsonObject tt = t.getAsJsonObject().deepCopy();
                tt.addProperty("machine", m);
                tags.add(tt);
            }
            devices.addProperty(Json.str(d, "nodeId"), m);
        }
        settings.add("devices", devices);
        JsonObject body = new JsonObject();
        body.addProperty("name", Json.str(s, "name"));
        body.addProperty("kind", Json.str(s, "kind"));
        body.add("settings", settings);
        body.add("tags", tags);
        body.add("pollMs", s.get("pollMs"));
        body.add("enabled", s.get("enabled"));
        return saveSource(Json.str(s, "agentId"), Json.str(s, "id"), body, orgId, actor);
    }

    /** a valid machine name from a device name: other characters become "-" */
    static String machineName(String raw) {
        String n = (raw == null ? "" : raw).replaceAll("[^A-Za-z0-9 ._-]+", "-").replaceAll("^[^A-Za-z0-9]+", "").trim();
        n = n.length() > 120 ? n.substring(0, 120).trim() : n;
        return n.isEmpty() ? "device" : n;
    }

    /** source id -> when its automatic adding last failed (retried after a scan interval, not every minute) */
    private final Map<String, Long> addFailedAt = new ConcurrentHashMap<>();

    /**
     * Every minute: per-device OPC UA sources get new devices added (newDevices = "add") from their last scan, and are
     * scanned again when the last scan is older than {@link #SCAN_EVERY_MS} and their agent is online (version 1.4.1+).
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void syncDevices() {
        List<JsonObject> list;
        try {
            list = rows("WHERE s.kind = 'opcua' AND s.enabled AND coalesce(s.settings->>'machinesFrom', '') <> ''");
        } catch (RuntimeException e) {
            return;                                                         // database not reachable: next round
        }
        for (JsonObject s : list) {
            try {
                sync(s);
            } catch (Exception e) {
                addFailedAt.put(Json.str(s, "id"), System.currentTimeMillis());
                log.warn("new devices of data source {} not added: {}", Json.str(s, "name"), e.getMessage());
            }
        }
    }

    private void sync(JsonObject s) throws ApiException {
        String mode = newDevicesMode(s);
        if ("ignore".equals(mode)) {
            return;
        }
        String id = Json.str(s, "id");
        UUID agent = UUID.fromString(Json.str(s, "agentId"));
        long orgId = s.get("orgId").getAsLong();
        Long failed = addFailedAt.get(id);
        if ("add".equals(mode) && (failed == null || System.currentTimeMillis() - failed > SCAN_EVERY_MS)) {
            List<JsonObject> fresh = newDevices(s, lastScan(s));
            if (!fresh.isEmpty()) {
                addDevices(s, fresh, List.of(), orgId, "new devices on the server");
                addFailedAt.remove(id);
                log.info("data source {}: added {} new device(s) as machines", Json.str(s, "name"), fresh.size());
                return;
            }
        }
        Object[] a = db.sql("SELECT agent_version, state = 'active' AND last_seen > now() - 3 * greatest(heartbeat_seconds, 10) * interval '1 second'"
                        + " FROM {s}.bimba_agent WHERE id = ?")
                .param(agent).query((rs, n) -> new Object[]{rs.getString(1), rs.getBoolean(2)}).optional().orElse(null);
        if (a == null || !Boolean.TRUE.equals(a[1]) || !canScan((String) a[0])) {
            return;
        }
        boolean recent = db.sql("SELECT count(*) FROM {s}.bimba_agent_command WHERE agent_id = ? AND kind = 'scan'"
                        + " AND request->>'sourceId' = ? AND created_at > now() - ? * interval '1 second'")
                .params(agent, id, SCAN_EVERY_MS / 1000).query(Long.class).single() > 0;
        if (recent) {
            return;
        }
        JsonObject source = new JsonObject();
        source.addProperty("kind", "opcua");
        source.add("settings", s.getAsJsonObject("settings").deepCopy());   // password stays sealed until the agent fetches it
        JsonObject req = new JsonObject();
        req.add("source", source);
        req.addProperty("sourceId", id);
        req.addProperty("nodeId", Json.str(s.getAsJsonObject("settings"), "machinesFrom"));
        queue(agent, orgId, "scan", req, "scheduled scan");
    }

    /** agents from 1.4.1 on answer "scan" */
    static boolean canScan(String version) {
        int[] v = new int[3];
        String[] parts = (version == null ? "" : version).split("[^0-9]+");
        for (int i = 0, j = 0; i < parts.length && j < 3; i++) {
            if (!parts[i].isEmpty()) {
                v[j++] = Integer.parseInt(parts[i].length() > 6 ? parts[i].substring(0, 6) : parts[i]);
            }
        }
        return v[0] > 1 || (v[0] == 1 && (v[1] > 4 || (v[1] == 4 && v[2] >= 1)));
    }

    private record Scan(List<JsonObject> machines, Timestamp at) {
    }

    /** the newest successful scan of the source, or null */
    private Scan lastScan(JsonObject s) {
        return db.sql("SELECT result::text, done_at FROM {s}.bimba_agent_command WHERE agent_id = ? AND kind = 'scan' AND state = 'done'"
                        + " AND request->>'sourceId' = ? ORDER BY created_at DESC LIMIT 1")
                .params(UUID.fromString(Json.str(s, "agentId")), Json.str(s, "id"))
                .query((rs, n) -> {
                    JsonObject r = rs.getString(1) == null ? new JsonObject() : Json.GSON.fromJson(rs.getString(1), JsonObject.class);
                    List<JsonObject> ms = new ArrayList<>();
                    if (r.has("machines") && r.get("machines").isJsonArray()) {
                        r.getAsJsonArray("machines").forEach(e -> ms.add(e.getAsJsonObject()));
                    }
                    return new Scan(ms, rs.getTimestamp(2));
                })
                .optional().orElse(null);
    }

    /** devices of the scan the source has neither added nor ignored yet (and that have tags) */
    private static List<JsonObject> newDevices(JsonObject s, Scan scan) {
        if (scan == null) {
            return List.of();
        }
        JsonObject settings = s.getAsJsonObject("settings");
        JsonObject known = settings.has("devices") && settings.get("devices").isJsonObject() ? settings.getAsJsonObject("devices") : new JsonObject();
        return scan.machines().stream()
                .filter(d -> !known.has(Json.str(d, "nodeId")))
                .filter(d -> d.has("tags") && d.get("tags").isJsonArray() && !d.getAsJsonArray("tags").isEmpty())
                .toList();
    }

    /** for the console: {checking, at, error, newDevices:[{nodeId, name, tags}], missing:[machine]} */
    private Map<String, Object> scanState(JsonObject s) {
        Map<String, Object> m = new LinkedHashMap<>();
        Object[] latest = db.sql("SELECT state, error, created_at > now() - interval '60 seconds' FROM {s}.bimba_agent_command"
                        + " WHERE agent_id = ? AND kind = 'scan' AND request->>'sourceId' = ? ORDER BY created_at DESC LIMIT 1")
                .params(UUID.fromString(Json.str(s, "agentId")), Json.str(s, "id"))
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getBoolean(3)}).optional().orElse(null);
        Scan scan = lastScan(s);
        m.put("mode", newDevicesMode(s));
        m.put("checking", latest != null && ("pending".equals(latest[0]) || "sent".equals(latest[0])) && Boolean.TRUE.equals(latest[2]));
        m.put("at", scan == null || scan.at() == null ? null : scan.at().toInstant().toString());
        m.put("error", latest != null && ("failed".equals(latest[0]) || "expired".equals(latest[0])) ? latest[1] : null);
        List<Map<String, Object>> fresh = new ArrayList<>();
        for (JsonObject d : newDevices(s, scan)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("nodeId", Json.str(d, "nodeId"));
            x.put("name", Json.str(d, "name"));
            x.put("tags", d.getAsJsonArray("tags").size());
            fresh.add(x);
        }
        m.put("newDevices", fresh);
        List<String> missing = new ArrayList<>();
        JsonObject settings = s.getAsJsonObject("settings");
        if (scan != null && settings.has("devices") && settings.get("devices").isJsonObject()) {
            Set<String> seen = new java.util.HashSet<>();
            scan.machines().forEach(d -> seen.add(Json.str(d, "nodeId")));
            settings.getAsJsonObject("devices").entrySet().forEach(e -> {
                String machine = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "";
                if (!machine.isEmpty() && !seen.contains(e.getKey())) {
                    missing.add(machine);
                }
            });
        }
        m.put("missing", missing);
        return m;
    }

    private static String newDevicesMode(JsonObject s) {
        String v = Json.str(s.getAsJsonObject("settings"), "newDevices");
        return v == null || !NEW_DEVICES.contains(v) ? "suggest" : v;
    }

    /** one machine per device of an OPC UA server (settings.machinesFrom) instead of one machine per source */
    static boolean perDevice(JsonObject s) {
        String from = Json.str(s.getAsJsonObject("settings"), "machinesFrom");
        return "opcua".equals(Json.str(s, "kind")) && from != null && !from.isBlank();
    }

    /** the machine a tag reports as: its own, or the source's name */
    static String machineOf(JsonObject s, JsonObject t) {
        String m = Json.str(t, "machine");
        return m == null || m.isBlank() ? Json.str(s, "name") : m;
    }

    /** the machines a source reports as (a source without tags still holds its name, unless it is per device) */
    static Set<String> machines(JsonObject s) {
        Set<String> out = new java.util.LinkedHashSet<>();
        JsonArray tags = s.has("tags") && s.get("tags").isJsonArray() ? s.getAsJsonArray("tags") : new JsonArray();
        for (JsonElement t : tags) {
            if (t.isJsonObject()) {
                out.add(machineOf(s, t.getAsJsonObject()));
            }
        }
        if (out.isEmpty() && !perDevice(s)) {
            out.add(Json.str(s, "name"));
        }
        return out;
    }

    /** lower-case name or machine -> the other source using it (all sources but {@code except}) */
    private Map<String, String> takenNames(UUID except) {
        Map<String, String> m = new java.util.HashMap<>();
        for (JsonObject s : rows("WHERE s.id <> ?", except)) {
            String label = Json.str(s, "name");
            m.put(label.toLowerCase(Locale.ROOT), label);
            machines(s).forEach(x -> m.put(x.toLowerCase(Locale.ROOT), label));
        }
        return m;
    }

    // ================================================================== helpers

    /** machines + tags of every agent source, in the collector status shape ({machine, tag, unit}) */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void refresh() {
        List<Map<String, Object>> tags = new ArrayList<>();
        Map<UUID, Set<String>> byAgent = new LinkedHashMap<>();
        for (JsonObject s : rows("WHERE s.enabled ORDER BY s.name")) {
            byAgent.computeIfAbsent(UUID.fromString(Json.str(s, "agentId")), k -> ConcurrentHashMap.newKeySet()).addAll(machines(s));
            for (JsonElement t : s.getAsJsonArray("tags")) {
                if (!t.isJsonObject()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("machine", machineOf(s, t.getAsJsonObject()));
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
        rows("WHERE s.agent_id = ? AND s.enabled", agent).forEach(x -> s.addAll(machines(x)));
        return s;
    }

    private List<JsonObject> rows(String where, Object... params) {
        return db.sql("SELECT s.id, s.agent_id, s.name, s.kind, s.settings::text, s.tags::text, s.poll_ms, s.enabled, s.updated_at,"
                        + " s.created_by, s.org_id FROM {s}.bimba_agent_source s " + where)
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
                    o.addProperty("orgId", rs.getLong(11));
                    return o;
                })
                .list();
    }

    /** a source of the organization (any of its machines), or null */
    private JsonObject sameOrgSource(String sourceId, long orgId) {
        if (sourceId == null || sourceId.isBlank()) {
            return null;
        }
        try {
            return rows("WHERE s.id = ? AND s.org_id = ?", UUID.fromString(sourceId), orgId).stream().findFirst().orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** the settings for the agent: the password opened over HTTPS / mutual TLS, left out over plain HTTP */
    private JsonObject open(JsonObject settings, boolean secure, UUID agentId) {
        JsonObject s = settings.deepCopy();
        String pw = Json.str(s, "password");
        if (pw != null && SecretBox.isSealed(pw)) {
            if (secure) {
                s.addProperty("password", secrets.open(pw));
            } else {
                s.remove("password");
                if (warnedPlain.add(agentId)) {
                    log.warn("site agent {} uses plain HTTP: data source passwords are not sent to it. Install it with "
                            + "Setu's HTTPS address (port 8443).", agentId);
                }
            }
        }
        return s;
    }

    private UUID agentOf(String agentId, long orgId) throws ApiException {
        UUID id = uuidOr404(agentId);
        boolean mine = db.sql("SELECT count(*) FROM {s}.bimba_agent WHERE id = ? AND org_id = ?").params(id, orgId).query(Long.class).single() > 0;
        if (!mine) {
            throw new ApiException(404, "not_found", "That site agent does not exist in this organization.");
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
                // one machine per device: the devices folder, the name prefix, what to do with new devices, node id -> machine
                String from = Json.str(s, "machinesFrom");
                if (from == null || from.isBlank()) {
                    for (String k : List.of("machinesFrom", "machinePrefix", "newDevices", "devices")) {
                        s.remove(k);
                    }
                    return;
                }
                if (from.length() > 500) {
                    throw new ApiException(400, "machinesFrom", "Devices folder: at most 500 characters.");
                }
                String prefix = Json.str(s, "machinePrefix");
                if (prefix != null && (prefix.length() > 60 || !prefix.matches("[A-Za-z0-9 ._-]*"))) {
                    throw new ApiException(400, "machinePrefix", "Name prefix: up to 60 letters, digits, spaces, '.', '_' or '-'.");
                }
                String mode = Json.str(s, "newDevices");
                if (mode != null && !NEW_DEVICES.contains(mode)) {
                    throw new ApiException(400, "newDevices", "New devices: suggest, add or ignore.");
                }
                JsonObject devices = new JsonObject();
                if (s.has("devices") && s.get("devices").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : s.getAsJsonObject("devices").entrySet()) {
                        if (devices.size() >= MAX_TAGS || e.getKey().length() > 500 || !e.getValue().isJsonPrimitive()) {
                            continue;
                        }
                        String m = e.getValue().getAsString().trim();
                        devices.addProperty(e.getKey(), m.length() > 120 ? m.substring(0, 120) : m);
                    }
                }
                s.add("devices", devices);
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
            case "mqtt" -> {
                String url = Json.str(s, "url");
                if (url == null || url.isBlank() || url.length() > 300
                        || !url.trim().matches("(?i)^((tcp|ssl|mqtt|mqtts|ws|wss)://)?[^\\s/?#@]+(:\\d{1,5})?(/[^\\s]*)?$")) {
                    throw new ApiException(400, "url", "Broker: an address like mqtt://broker:1883 or mqtts://broker:8883.");
                }
                s.addProperty("url", url.trim());
                String payload = Json.str(s, "payload");
                if (payload == null || !MQTT_PAYLOADS.contains(payload)) {
                    throw new ApiException(400, "payload", "Messages: value, json or sparkplug.");
                }
                int qos = s.has("qos") && s.get("qos").isJsonPrimitive() ? s.get("qos").getAsInt() : 1;
                if (qos < 0 || qos > 1) {
                    throw new ApiException(400, "qos", "QoS: 0 or 1.");
                }
                String clientId = Json.str(s, "clientId");
                if (clientId != null && (clientId.length() > 64 || !clientId.matches("[A-Za-z0-9._-]*"))) {
                    throw new ApiException(400, "clientId", "Client id: up to 64 letters, digits, '.', '_' or '-' (empty = automatic).");
                }
                String ca = Json.str(s, "caCert");
                if (ca != null && !ca.isBlank() && (!ca.contains("-----BEGIN CERTIFICATE-----") || ca.length() > 20_000)) {
                    throw new ApiException(400, "caCert", "Broker CA certificate: paste the PEM text (-----BEGIN CERTIFICATE----- ...).");
                }
                long stale = s.has("staleSeconds") && s.get("staleSeconds").isJsonPrimitive() ? s.get("staleSeconds").getAsLong() : 0;
                if (stale < 0 || stale > 86_400) {
                    throw new ApiException(400, "staleSeconds", "Stale after: 0 (never) to 86400 seconds.");
                }
            }
            case "s7", "eip" -> {
                String host = Json.str(s, "host");
                if (host == null || host.isBlank() || host.length() > 255 || !host.trim().matches("[A-Za-z0-9.:\\[\\]_-]+")) {
                    throw new ApiException(400, "host", "Enter the PLC's host name or IP address.");
                }
                s.addProperty("host", host.trim());
                for (String k : List.of("port", "rack", "slot", "backplane")) {
                    if (s.has(k) && s.get(k).isJsonPrimitive() && !s.get(k).getAsString().isBlank()) {
                        int v;
                        try {
                            v = s.get(k).getAsInt();
                        } catch (RuntimeException e) {
                            v = -1;
                        }
                        if (v < 0 || v > ("port".equals(k) ? 65535 : 31)) {
                            throw new ApiException(400, k, k.substring(0, 1).toUpperCase(Locale.ROOT) + k.substring(1) + ": "
                                    + ("port".equals(k) ? "1–65535." : "0–31."));
                        }
                    }
                }
                String cpu = Json.str(s, "cpu");
                if ("s7".equals(kind) && cpu != null && !S7_CPUS.contains(cpu)) {
                    throw new ApiException(400, "cpu", "CPU: S7_300, S7_400, S7_1200, S7_1500 or LOGO.");
                }
            }
            case "dnp3", "iec104" -> {
                String host = Json.str(s, "host");
                if (host == null || host.isBlank() || host.length() > 255 || !host.trim().matches("[A-Za-z0-9.:\\[\\]_-]+")) {
                    throw new ApiException(400, "host", "Enter the outstation's host name or IP address.");
                }
                s.addProperty("host", host.trim());
                whole(s, "port", 1, 65535, "Port: 1–65535.");
                if ("dnp3".equals(kind)) {
                    whole(s, "outstation", 0, 65519, "Outstation address: 0–65519.");
                    whole(s, "master", 0, 65519, "Master address: 0–65519.");
                    long out = s.has("outstation") ? s.get("outstation").getAsLong() : 10, master = s.has("master") ? s.get("master").getAsLong() : 1;
                    if (out == master) {
                        throw new ApiException(400, "master", "The master address must differ from the outstation's.");
                    }
                } else {
                    whole(s, "commonAddress", 1, 65534, "Common address: 1–65534.");
                    whole(s, "giMinutes", 0, 1440, "General interrogation: every 0 (only after connecting) to 1440 minutes.");
                    zone(s);
                }
            }
            case "sql" -> {
                String engine = Json.str(s, "engine");
                if (engine == null || !SqlDriver.ENGINES.contains(engine)) {
                    throw new ApiException(400, "engine", "Database: sqlserver, postgresql or mysql.");
                }
                String host = Json.str(s, "host");
                if (host == null || !SqlDriver.HOST.matcher(host.trim()).matches()) {
                    throw new ApiException(400, "host", "Enter the database server's host name or IP address.");
                }
                s.addProperty("host", host.trim());
                whole(s, "port", 1, 65535, "Port: 1–65535.");
                String instance = Json.str(s, "instance");
                if (instance == null || instance.isBlank() || !"sqlserver".equals(engine)) {
                    s.remove("instance");
                } else if (!SqlDriver.INSTANCE.matcher(instance.trim()).matches()) {
                    throw new ApiException(400, "instance", "Instance: up to 16 letters, digits, '_', '$', '#' or '-' (e.g. SQLEXPRESS).");
                } else {
                    s.addProperty("instance", instance.trim());
                }
                String database = Json.str(s, "database");
                if (database == null || database.isBlank()) {
                    s.remove("database");
                } else if (!SqlDriver.DATABASE.matcher(database.trim()).matches()) {
                    throw new ApiException(400, "database", "Database: up to 128 letters, digits, spaces, '_', '.', '$' or '-'.");
                } else {
                    s.addProperty("database", database.trim());
                }
                String user = Json.str(s, "username");
                if (user != null && user.length() > 128) {
                    throw new ApiException(400, "username", "User name: at most 128 characters.");
                }
                String tls = Json.str(s, "tls");
                if (tls.isEmpty()) {
                    s.remove("tls");                                                // off
                } else if (!SqlDriver.TLS.contains(tls)) {
                    throw new ApiException(400, "tls", "Encryption: off, trust or verify.");
                }
                String layout = Json.str(s, "layout");
                if (layout == null || !SqlDriver.LAYOUTS.contains(layout)) {
                    throw new ApiException(400, "layout", "Result: rows (one row per tag) or columns (one column per tag).");
                }
                try {
                    s.addProperty("query", SqlDriver.checkQuery(Json.str(s, "query")));
                } catch (IllegalArgumentException bad) {
                    throw new ApiException(400, "query", bad.getMessage() + ".");
                }
                whole(s, "staleSeconds", 0, 31 * 86_400, "Stale after: 0 (never) to 2678400 seconds.");
                whole(s, "timeoutMs", 1000, 300_000, "Timeout: 1000–300000 ms.");
                zone(s);
            }
            default -> throw new ApiException(400, "kind", "Unknown source type.");
        }
    }

    /** an optional whole number setting within min..max (empty = not set) */
    private static void whole(JsonObject s, String k, long min, long max, String message) throws ApiException {
        if (!s.has(k) || s.get(k).isJsonNull() || s.get(k).isJsonPrimitive() && s.get(k).getAsString().isBlank()) {
            s.remove(k);
            return;
        }
        long v;
        try {
            v = Long.parseLong(s.get(k).getAsString().trim());
        } catch (RuntimeException e) {
            throw new ApiException(400, k, message);
        }
        if (v < min || v > max) {
            throw new ApiException(400, k, message);
        }
        s.addProperty(k, v);
    }

    /** an optional IANA time zone (e.g. Europe/Berlin), default UTC */
    private static void zone(JsonObject s) throws ApiException {
        String z = Json.str(s, "timeZone");
        if (z == null || z.isBlank()) {
            s.remove("timeZone");
            return;
        }
        try {
            java.time.ZoneId.of(z.trim());
        } catch (java.time.DateTimeException e) {
            throw new ApiException(400, "timeZone", "Time zone: a name like UTC, Europe/Berlin or Asia/Kolkata.");
        }
        s.addProperty("timeZone", z.trim());
    }

    /** MQTT tag address for the source's message format (see MqttDriver) */
    private static void mqttAddress(String payload, String address) throws ApiException {
        String a = address.trim();
        int hash = a.indexOf('#');
        String topic = hash < 0 ? a : a.substring(0, hash);
        if (topic.isEmpty() || topic.contains("+") || a.indexOf('#', hash + 1) > 0 && !"sparkplug".equals(payload)) {
            throw new ApiException(400, "tags", "MQTT address \"" + address + "\": an exact topic (no + or # wildcards).");
        }
        if ("value".equals(payload) && hash >= 0) {
            throw new ApiException(400, "tags", "MQTT address \"" + address + "\": a topic (use JSON messages for topic#field).");
        }
        if ("sparkplug".equals(payload) && (hash < 1 || hash == a.length() - 1 || topic.split("/").length < 2 || topic.split("/").length > 3)) {
            throw new ApiException(400, "tags", "Sparkplug address \"" + address + "\": group/edge node#metric or group/edge node/device#metric.");
        }
    }

    static JsonArray tags(JsonObject b, String kind) throws ApiException {
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
            if ("mqtt".equals(kind)) {
                JsonObject st = b.has("settings") && b.get("settings").isJsonObject() ? b.getAsJsonObject("settings") : new JsonObject();
                mqttAddress(Json.str(st, "payload"), address);
            }
            if ("s7".equals(kind) || "eip".equals(kind) || "dnp3".equals(kind) || "iec104".equals(kind)) {
                try {
                    switch (kind) {                                                // same parsers as the agent
                        case "s7" -> com.bimba.setu.collect.S7Driver.parse(address);
                        case "eip" -> com.bimba.setu.collect.LogixDriver.path(address);
                        case "dnp3" -> com.bimba.setu.collect.Dnp3Driver.parse(address);
                        default -> com.bimba.setu.collect.Iec104Driver.parse(address, 1);
                    }
                } catch (IllegalArgumentException bad) {
                    throw new ApiException(400, "tags", bad.getMessage() + ".");
                }
            }
            if ("modbus".equals(kind) && !address.trim().toUpperCase(Locale.ROOT).matches("^(?:\\d+\\.)?(HR|IR|C|DI)\\d+$")) {
                throw new ApiException(400, "tags", "Modbus address \"" + address + "\": use HR1, IR5, C3 or DI2 (optionally 2.HR1 for unit 2).");
            }
            JsonObject clean = new JsonObject();
            clean.addProperty("address", address.trim());
            String name = Json.str(t, "name");
            clean.addProperty("name", name == null || name.isBlank() ? address.trim() : name.trim());
            String machine = Json.str(t, "machine");
            if (machine != null && !machine.isBlank()) {
                if (!NAME.matcher(machine.trim()).matches()) {
                    throw new ApiException(400, "tags", "Machine name \"" + machine + "\": 1–120 letters, digits, spaces, '.', '_' or '-',"
                            + " starting with a letter or digit.");
                }
                clean.addProperty("machine", machine.trim());
            }
            String tn = tagName(clean);
            if (tn.isEmpty() || !names.add((machine == null ? "" : machine.trim().toLowerCase(Locale.ROOT)) + "/" + tn)) {
                throw new ApiException(400, "tags", "Tag names must be unique" + (machine == null || machine.isBlank() ? "" : " per machine")
                        + " (\"" + clean.get("name").getAsString() + "\").");
            }
            for (String k : List.of("unit", "dataType", "sourceUnit")) {
                String v = Json.str(t, k);
                if (v != null && !v.isBlank()) {
                    clean.addProperty(k, v.trim().length() > 40 ? v.trim().substring(0, 40) : v.trim());
                }
            }
            for (String k : List.of("rawLow", "rawHigh", "scaledLow", "scaledHigh", "engLow", "engHigh")) {
                if (t.has(k) && t.get(k).isJsonPrimitive() && !t.get(k).getAsString().isBlank()) {
                    try {
                        double v = Double.parseDouble(t.get(k).getAsString());
                        if (Double.isNaN(v) || Double.isInfinite(v)) {
                            throw new NumberFormatException(k);
                        }
                        clean.addProperty(k, v);
                    } catch (NumberFormatException ex) {
                        throw new ApiException(400, "tags", (k.startsWith("eng") ? "The engineering range" : "Scaling") + " of \""
                                + clean.get("name").getAsString() + "\" must be numbers.");
                    }
                }
            }
            long scaling = List.of("rawLow", "rawHigh", "scaledLow", "scaledHigh").stream().filter(clean::has).count();
            if (scaling > 0 && (scaling < 4 || clean.get("rawLow").getAsDouble() == clean.get("rawHigh").getAsDouble())) {
                throw new ApiException(400, "tags", "Scaling of \"" + clean.get("name").getAsString()
                        + "\": enter raw low and high (different) and scaled low and high, or none of them.");
            }
            String why = engineering(clean);
            if (why != null) {
                throw new ApiException(400, "tags", "Tag \"" + clean.get("name").getAsString() + "\": " + why + ".");
            }
            out.add(clean);
        }
        return out;
    }

    /** why a tag's unit conversion / engineering range cannot work, null when it can */
    private static String engineering(JsonObject t) {
        String from = Json.str(t, "sourceUnit");          // "" when not set
        String to = Json.str(t, "unit");
        if (!from.isEmpty() && !from.equals(to)) {
            if (to.isEmpty()) {
                return "the device unit " + from + " needs a unit to convert to";
            }
            String p = Units.problem(from, to);
            if (p != null) {
                return p;
            }
        }
        if (t.has("engLow") && t.has("engHigh") && t.get("engLow").getAsDouble() > t.get("engHigh").getAsDouble()) {
            return "the engineering range's low end is above its high end";
        }
        return null;
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
