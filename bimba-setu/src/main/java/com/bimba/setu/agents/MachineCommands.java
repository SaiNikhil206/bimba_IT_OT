package com.bimba.setu.agents;

import com.bimba.setu.Db;
import com.bimba.setu.Json;
import com.bimba.setu.collect.Writer;
import com.bimba.setu.config.PipelineService;
import com.bimba.setu.config.TagModel;
import com.bimba.setu.security.Permission;
import com.bimba.setu.security.SecurityService;
import com.bimba.setu.security.SecurityService.ApiException;
import com.bimba.setu.security.SecurityService.Principal;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Machine commands (southbound writes): a person sets one tag of a machine to one value, and the site agent that
 * collects the machine writes it (OPC UA, Modbus TCP, MQTT; collect-lib Writer), reads it back and answers.
 * <p>
 * Safety, in the order a command meets it:
 * <ol>
 *   <li>the data source allows commands (settings.writes) and the tag is marked writable, with lowest / highest
 *       value for numbers (AgentSources.writeSettings); changing any of that takes a second person (SafetyChanges);</li>
 *   <li>the person has writes.send; the value is inside the tag's limits;</li>
 *   <li>commands are not stopped for the organization, the agent's site or the agent (the kill switch: anyone who may
 *       send or approve can stop, only writes.manage resumes; stopping cancels the waiting commands);</li>
 *   <li>a tag with writeApproval waits up to 15 minutes for another person with writes.approve (never the requester);</li>
 *   <li>a command the agent has not picked up within {@link AgentSources#WRITE_TTL_SECONDS} s is never carried out;</li>
 *   <li>the agent's computer may refuse every command (bimba-site-agent writes off), and runs each command id once;</li>
 *   <li>request, approval, result and expiry go to the audit log; the history stays 90 days.</li>
 * </ol>
 * Commands are rows of bimba_agent_command with kind "write" (states approval, pending, sent, done, failed, expired,
 * rejected, cancelled); request.meta keeps what the console shows. The kill switch is bimba_gw_setting
 * "writes.org.&lt;id&gt;": {org: stop, sites: {site: stop}, agents: {id: stop}}, a stop being {by, byName, at, reason}.
 */
@Service
public class MachineCommands {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Commands");
    static final String KEY = "writes.org.";
    static final int APPROVAL_MINUTES = 15;
    /** an answer this late means the agent lost the command */
    static final int ANSWER_SECONDS = 60;
    /** the first edge collector version that carries out machine commands */
    static final String NEEDS = "1.9";

    private final Db db;
    private final AgentSources sources;
    private final SecurityService security;
    private final AgentRegistry registry;
    private final PipelineService pipelines;

    public MachineCommands(Db db, AgentSources sources, SecurityService security, AgentRegistry registry, PipelineService pipelines) {
        this.db = db;
        this.sources = sources;
        this.security = security;
        this.registry = registry;
        this.pipelines = pipelines;
    }

    @PostConstruct
    void init() {
        sources.onWriteDone(this::answered);
    }

    // ================================================================== machines

    /**
     * The organization's machines (every site agent data source's), each with its state for the console's list:
     * {machines:[..], waiting, stops, sites, agents, can}. Machines with writable tags come first.
     */
    public Map<String, Object> machines(Principal p) throws ApiException {
        long org = p.org();
        JsonObject stops = stops(org);
        Map<String, Agent> agents = agents(org);
        Map<String, Long> waiting = new LinkedHashMap<>();
        Map<String, Map<String, Object>> lastCommand = new LinkedHashMap<>();
        db.sql("SELECT DISTINCT ON (lower(c.request->'meta'->>'machine')) lower(c.request->'meta'->>'machine'), c.state,"
                        + " c.request->'meta'->>'label', c.request->'meta'->>'tag', c.request->'meta'->>'requestedAt',"
                        + " (SELECT count(*) FROM {s}.bimba_agent_command w WHERE w.org_id = c.org_id AND w.kind = 'write'"
                        + " AND w.state = 'approval' AND lower(w.request->'meta'->>'machine') = lower(c.request->'meta'->>'machine'))"
                        + " FROM {s}.bimba_agent_command c WHERE c.org_id = ? AND c.kind = 'write'"
                        + " ORDER BY lower(c.request->'meta'->>'machine'), coalesce((c.request->'meta'->>'requestedAt')::timestamptz, c.created_at) DESC")
                .param(org)
                .query((rs, n) -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("state", rs.getString(2));
                    c.put("tag", rs.getString(3) == null || rs.getString(3).isBlank() ? rs.getString(4) : rs.getString(3));
                    c.put("at", rs.getString(5));
                    lastCommand.put(rs.getString(1), c);
                    waiting.put(rs.getString(1), rs.getLong(6));
                    return null;
                }).list();
        List<PipelineService.Carrier> carriers = pipelines.carriers(org);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonObject s : sources.sourcesOf(org)) {
            Agent a = agents.get(Json.str(s, "agentId"));
            for (String machine : AgentSources.machines(s)) {
                if (!visible(p, Json.str(s, "agentId"), a, machine)) {
                    continue;
                }
                List<JsonObject> tags = tagsOf(s, machine);
                List<String> names = tags.stream().map(AgentSources::tagName).toList();
                long writable = tags.stream().filter(MachineCommands::writable).count();
                int bad = 0;
                String updated = null;
                for (JsonObject t : tags) {
                    JsonObject last = current(a, machine, AgentSources.tagName(t));
                    if (last == null) {
                        continue;
                    }
                    bad += "BAD".equals(Json.str(last, "quality")) ? 1 : 0;
                    String at = Json.str(last, "timestamp");
                    updated = updated == null || at.compareTo(updated) > 0 ? at : updated;
                }
                Map<String, Object> m = header(s, machine, a);
                m.put("tags", tags.size());
                m.put("writable", writable);
                m.put("bad", bad);
                m.put("updated", updated);
                m.put("blocked", writable > 0 ? blocked(s, a, stops) : null);
                m.put("waiting", waiting.getOrDefault(machine.toLowerCase(Locale.ROOT), 0L));
                m.put("lastCommand", lastCommand.get(machine.toLowerCase(Locale.ROOT)));
                m.put("pipelines", carriers.stream().filter(c -> c.active() && c.carried(machine, names) > 0).count());
                m.put("can", can(p, Json.str(s, "agentId"), a, machine));
                out.add(m);
            }
        }
        out.sort((x, y) -> {
            int w = Boolean.compare((Long) y.get("writable") > 0, (Long) x.get("writable") > 0);
            return w != 0 ? w : String.CASE_INSENSITIVE_ORDER.compare((String) x.get("machine"), (String) y.get("machine"));
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("machines", out);
        Set<String> shown = new java.util.HashSet<>();
        out.forEach(x -> shown.add(((String) x.get("machine")).toLowerCase(Locale.ROOT)));
        m.put("waiting", waiting.entrySet().stream().filter(e -> shown.contains(e.getKey())).mapToLong(Map.Entry::getValue).sum());
        common(m, p, stops, agents);
        return m;
    }

    /**
     * One machine: who collects it, its current values (every tag: the last reading its agent sent), what its writable
     * tags take, and its commands (newest first).
     */
    public Map<String, Object> machine(Principal p, String name) throws ApiException {
        long org = p.org();
        JsonObject stops = stops(org);
        Map<String, Agent> agents = agents(org);
        Located l = locate(org, name);
        Agent a = agents.get(Json.str(l.source(), "agentId"));
        see(p, l, a);
        List<Map<String, Object>> tags = new ArrayList<>();
        for (JsonObject t : tagsOf(l.source(), l.machine())) {
            tags.add(tag(l.source(), t, l.machine(), a));
        }
        JsonObject settings = l.source().getAsJsonObject("settings");
        Map<String, Object> m = header(l.source(), l.machine(), a);
        m.put("pollMs", l.source().get("pollMs").getAsInt());
        m.put("writesAllowed", settings.has("writes") && settings.get("writes").getAsBoolean());
        m.put("agentVersion", a == null ? null : a.edgeVersion());
        m.put("blocked", blocked(l.source(), a, stops));
        m.put("values", tags);
        m.put("agentInfo", registry.list(org).stream().filter(x -> Json.str(l.source(), "agentId").equals(x.get("id"))).findFirst()
                .map(x -> {
                    Map<String, Object> i = new LinkedHashMap<>();
                    for (String k : List.of("id", "name", "hostname", "site", "os", "ip", "agentVersion", "status", "lastSeen")) {
                        i.put(k, x.get(k));
                    }
                    return i;
                }).orElse(null));
        m.put("address", address(l.source()));
        List<String> names = tagsOf(l.source(), l.machine()).stream().map(AgentSources::tagName).toList();
        List<Map<String, Object>> carried = new ArrayList<>();
        for (PipelineService.Carrier c : pipelines.carriers(org)) {
            int n = c.carried(l.machine(), names);
            if (n == 0) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", c.id());
            x.put("uid", c.uid());
            x.put("name", c.name());
            x.put("active", c.active());
            x.put("allMachines", c.allMachines());
            x.put("tags", n);
            x.put("destinations", c.destinations());
            x.put("activeDestination", c.live() == null ? null : c.live().get("activeDestination"));
            x.put("readingsPerMinute", c.live() == null ? null : c.live().get("readingsPerMinute"));
            x.put("bufferAlert", c.live() != null && Boolean.TRUE.equals(c.live().get("bufferAlert")));
            carried.add(x);
        }
        carried.sort((x, y) -> Boolean.compare((Boolean) y.get("active"), (Boolean) x.get("active")));
        m.put("pipelines", carried);
        List<Map<String, Object>> commands = new ArrayList<>();
        for (Row r : rows("WHERE org_id = ? AND kind = 'write' AND lower(request->'meta'->>'machine') = lower(?)"
                + " ORDER BY coalesce((request->'meta'->>'requestedAt')::timestamptz, created_at) DESC LIMIT 100", org, l.machine())) {
            commands.add(view(r, p));
        }
        m.put("commands", commands);
        common(m, p, stops, agents);
        // on this machine's page: what the user may do here (their roles may hold only for some places)
        Map<String, Object> here = new LinkedHashMap<>(can(p, Json.str(l.source(), "agentId"), a, l.machine()));
        here.put("read", true);
        Map<?, ?> whole = (Map<?, ?>) m.get("can");
        here.put("stopOrg", whole.get("stopOrg"));
        here.put("resumeOrg", whole.get("resumeOrg"));
        m.put("can", here);
        return m;
    }

    /** one tag of a machine: its last value and, when writable, what it takes */
    private Map<String, Object> tag(JsonObject s, JsonObject t, String machine, Agent a) {
        String kind = Json.str(s, "kind");
        String tag = AgentSources.tagName(t);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", tag);
        m.put("label", Json.str(t, "name"));
        m.put("address", Json.str(t, "address"));
        m.put("unit", Json.str(t, "unit"));
        JsonObject last = current(a, machine, tag);
        m.put("value", last != null && last.has("tagValue") ? Json.toJava(last.get("tagValue").toString()) : null);
        m.put("quality", last == null ? null : Json.str(last, "quality"));
        m.put("detail", last == null || Json.str(last, "qualityDetail").isBlank() ? null : Json.str(last, "qualityDetail"));
        m.put("at", last == null ? null : Json.str(last, "timestamp"));
        m.put("writable", writable(t));
        if (writable(t)) {
            m.put("takes", AgentSources.writeKind(kind, t));
            m.put("writeMin", t.has("writeMin") ? t.get("writeMin").getAsDouble() : null);
            m.put("writeMax", t.has("writeMax") ? t.get("writeMax").getAsDouble() : null);
            m.put("approval", t.has("writeApproval") && t.get("writeApproval").getAsBoolean());
        }
        return m;
    }

    /** who collects the machine: the head of a machine in the list and on its page */
    private static Map<String, Object> header(JsonObject s, String machine, Agent a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("machine", machine);
        m.put("sourceId", Json.str(s, "id"));
        m.put("source", Json.str(s, "name"));
        m.put("kind", Json.str(s, "kind"));
        m.put("kindLabel", SourceKinds.label(Json.str(s, "kind")));
        m.put("agentId", Json.str(s, "agentId"));
        m.put("agent", a == null ? null : a.name());
        m.put("site", a == null ? null : a.site());
        m.put("online", a != null && a.online());
        m.put("paused", !s.get("enabled").getAsBoolean());
        return m;
    }

    /** where the data source reads: its endpoint, broker or host[:port] (no secrets) */
    static String address(JsonObject s) {
        JsonObject st = s.getAsJsonObject("settings");
        for (String k : List.of("endpoint", "url")) {
            if (!Json.str(st, k).isBlank()) {
                return Json.str(st, k);
            }
        }
        String host = Json.str(st, "host");
        if (host.isBlank()) {
            return null;
        }
        String port = Json.str(st, "port");
        String db = Json.str(st, "database");
        return host + (port.isBlank() ? "" : ":" + port.replaceAll("\\.0$", "")) + (db.isBlank() ? "" : " / " + db);
    }

    /** what both pages need: the kill switch, where it can stop, what the user may do, the time limits */
    private static void common(Map<String, Object> m, Principal p, JsonObject stops, Map<String, Agent> agents) {
        m.put("stops", Json.toJava(stops.toString()));
        // the places the kill switch offers: those where the user may stop (a scoped role only its own sites and agents)
        m.put("sites", agents.values().stream().map(Agent::site).filter(x -> x != null && !x.isBlank() && stopSite(p, x, STOPPERS)).distinct().sorted().toList());
        m.put("agents", agents.values().stream().filter(a -> stopAgent(p, a, STOPPERS)).map(a -> Map.of("id", a.id(), "name", a.name())).toList());
        boolean commands = p.can(Permission.WRITES_SEND) || p.can(Permission.WRITES_APPROVE) || p.can(Permission.WRITES_MANAGE);
        m.put("can", Map.of("send", p.can(Permission.WRITES_SEND), "approve", p.can(Permission.WRITES_APPROVE),
                "resume", p.can(Permission.WRITES_MANAGE), "read", commands || p.can(Permission.MACHINES_VIEW),
                "stopOrg", STOPPERS.stream().anyMatch(p::everywhere), "resumeOrg", p.everywhere(Permission.WRITES_MANAGE)));
        m.put("approvalMinutes", APPROVAL_MINUTES);
        m.put("ttlSeconds", AgentSources.WRITE_TTL_SECONDS);
    }

    private record Located(JsonObject source, String machine) {
    }

    private Located locate(long org, String name) throws ApiException {
        if (name != null) {
            for (JsonObject s : sources.sourcesOf(org)) {
                for (String m : AgentSources.machines(s)) {
                    if (m.equalsIgnoreCase(name.trim())) {
                        return new Located(s, m);
                    }
                }
            }
        }
        throw new ApiException(404, "not_found", "That machine is not collected by a site agent of this organization.");
    }

    private static List<JsonObject> tagsOf(JsonObject s, String machine) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement te : s.getAsJsonArray("tags")) {
            if (te.isJsonObject() && AgentSources.machineOf(s, te.getAsJsonObject()).equalsIgnoreCase(machine)) {
                out.add(te.getAsJsonObject());
            }
        }
        return out;
    }

    /** values read on request ("Read now"), agent/machine/tag -> {tagValue, quality, qualityDetail, timestamp} */
    private final Map<String, JsonObject> reads = new java.util.concurrent.ConcurrentHashMap<>();

    /** a tag's newest value: the last reading its agent sent, or a later "Read now" (null when neither) */
    private JsonObject current(Agent a, String machine, String tag) {
        if (a == null) {
            return null;
        }
        JsonObject live = sources.lastReading(UUID.fromString(a.id()), machine, tag);
        JsonObject read = reads.get(a.id() + "/" + machine + "/" + tag);
        if (read == null) {
            return live;
        }
        return live == null || Json.str(read, "timestamp").compareTo(Json.str(live, "timestamp")) > 0 ? read : live;
    }

    private static boolean writable(JsonObject t) {
        return t.has("writable") && t.get("writable").getAsBoolean();
    }

    // ================================================================== read now

    /** asks the machine's agent to read all its tags once, now (read only): {id} */
    public Map<String, Object> readNow(Principal p, String name) throws ApiException {
        long org = p.org();
        Located l = locate(org, name);
        see(p, l, agents(org).get(Json.str(l.source(), "agentId")));
        JsonArray tags = new JsonArray();
        tagsOf(l.source(), l.machine()).forEach(tags::add);
        if (tags.isEmpty()) {
            throw new ApiException(400, "tags", "This machine has no tags to read.");
        }
        JsonObject b = new JsonObject();
        b.addProperty("kind", "read");
        b.addProperty("sourceId", Json.str(l.source(), "id"));
        b.add("tags", tags);
        return sources.command(Json.str(l.source(), "agentId"), b, org, p.username());
    }

    /**
     * The read's answer: {state, values:[{tag, value, quality, detail}], at, error}. The agent reads raw device values;
     * they are scaled and converted here as the collector would.
     */
    public Map<String, Object> readState(Principal p, String name, String id) throws ApiException {
        long org = p.org();
        Located l = locate(org, name);
        see(p, l, agents(org).get(Json.str(l.source(), "agentId")));
        Map<String, Object> st = sources.commandState(Json.str(l.source(), "agentId"), id, org);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", st.get("state"));
        m.put("error", st.get("error"));
        if (!"done".equals(st.get("state"))) {
            return m;
        }
        JsonElement res = Json.GSON.toJsonTree(st.get("result"));
        if (!res.isJsonObject() || !res.getAsJsonObject().has("values") || !res.getAsJsonObject().get("values").isJsonArray()) {
            m.put("state", "failed");
            m.put("error", "The agent answered without values.");
            return m;
        }
        List<JsonObject> tags = tagsOf(l.source(), l.machine());
        JsonArray vs = res.getAsJsonObject().getAsJsonArray("values");
        String kind = Json.str(l.source(), "kind");
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < vs.size() && i < tags.size(); i++) {
            JsonObject v = vs.get(i).getAsJsonObject();
            JsonObject t = tags.get(i);
            Object raw = v.has("value") && !v.get("value").isJsonNull() ? Json.toJava(v.get("value").toString()) : null;
            boolean good = !v.has("good") || v.get("good").getAsBoolean();
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("tag", AgentSources.tagName(t));
            x.put("value", raw == null ? null : Writer.engineering(TagModel.of(l.machine(), "read", kind, t), raw));
            x.put("quality", v.has("quality") ? Json.str(v, "quality") : good ? "GOOD" : "BAD");
            x.put("detail", v.has("detail") && !v.get("detail").isJsonNull() ? Json.str(v, "detail") : null);
            out.add(x);
        }
        String at = Instant.now().toString();
        // kept, so the machine list and page show them until the agent sends something newer
        String agentId = Json.str(l.source(), "agentId");
        if (reads.size() < 20_000) {
            for (Map<String, Object> x : out) {
                JsonObject r = new JsonObject();
                r.add("tagValue", Json.GSON.toJsonTree(x.get("value")));
                r.addProperty("quality", (String) x.get("quality"));
                r.addProperty("qualityDetail", x.get("detail") == null ? "" : (String) x.get("detail"));
                r.addProperty("timestamp", at);
                reads.put(agentId + "/" + l.machine() + "/" + x.get("tag"), r);
            }
        }
        m.put("values", out);
        m.put("at", at);
        return m;
    }

    /** why a command to this source cannot go out now, or null */
    private static String blocked(JsonObject s, Agent a, JsonObject stops) {
        String stop = stoppedWhy(stops, a);
        if (stop != null) {
            return stop;
        }
        JsonObject settings = s.getAsJsonObject("settings");
        if (!settings.has("writes") || !settings.get("writes").getAsBoolean()) {
            return "Commands are off for data source " + Json.str(s, "name") + " (its Advanced settings).";
        }
        if (!s.get("enabled").getAsBoolean()) {
            return "Data source " + Json.str(s, "name") + " is paused.";
        }
        if (a == null) {
            return "The site agent is gone.";
        }
        if (!a.online()) {
            return "Site agent " + a.name() + " is offline.";
        }
        if (!atLeast(a.edgeVersion(), NEEDS)) {
            return "Site agent " + a.name() + " runs " + (a.edgeVersion() == null ? "a version" : a.edgeVersion())
                    + " without machine commands: update it to " + NEEDS + " or later.";
        }
        if ("refused".equals(a.writes())) {
            return "Machine commands are turned off on " + a.name() + " itself (bimba-site-agent writes on).";
        }
        return null;
    }

    static String stoppedWhy(JsonObject stops, Agent a) {
        JsonObject s = obj(stops, "org");
        String scope = "for the whole organization";
        if (s == null && a != null) {
            s = obj(obj(stops, "agents"), a.id());
            scope = "for site agent " + a.name();
        }
        if (s == null && a != null && a.site() != null && !a.site().isBlank()) {
            JsonObject sites = obj(stops, "sites");
            if (sites != null) {
                for (Map.Entry<String, JsonElement> e : sites.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(a.site().trim()) && e.getValue().isJsonObject()) {
                        s = e.getValue().getAsJsonObject();
                        scope = "at site " + e.getKey();
                    }
                }
            }
        }
        if (s == null) {
            return null;
        }
        String reason = Json.str(s, "reason");
        return "Machine commands are stopped " + scope + " (by " + Json.str(s, "byName") + ", " + Json.str(s, "at") + ")"
                + (reason.isBlank() ? "." : ": " + reason);
    }

    // ================================================================== requests

    /** body {sourceId, machine, tag, value, reason}: the command, waiting for approval or on its way to the agent */
    public Map<String, Object> request(Principal p, JsonObject b, String ip) throws ApiException {
        long org = p.org();
        String reason = text(b, "reason", 300);
        Found f = find(org, AgentRegistry.str(b, "sourceId", 64), AgentRegistry.str(b, "machine", 120), AgentRegistry.str(b, "tag", 200));
        here(p, Permission.WRITES_SEND, f);
        JsonPrimitive value = value(f, b.get("value"));
        check(f, org);
        String sourceId = Json.str(f.source(), "id");
        long open = db.sql("SELECT count(*) FROM {s}.bimba_agent_command WHERE kind = 'write' AND org_id = ? AND state IN ('approval', 'pending', 'sent')"
                        + " AND request->'meta'->>'sourceId' = ? AND request->'meta'->>'machine' = ? AND request->'meta'->>'tag' = ?")
                .params(org, sourceId, f.machine(), f.tag()).query(Long.class).single();
        if (open > 0) {
            throw new ApiException(409, "open", "A command for " + f.machine() + " / " + f.tag() + " is still open. Wait for it, or cancel it first.");
        }
        boolean approval = f.t().has("writeApproval") && f.t().get("writeApproval").getAsBoolean();
        JsonObject meta = new JsonObject();
        meta.addProperty("sourceId", sourceId);
        meta.addProperty("source", Json.str(f.source(), "name"));
        meta.addProperty("kind", Json.str(f.source(), "kind"));
        meta.addProperty("agent", f.agent().name());
        meta.addProperty("machine", f.machine());
        meta.addProperty("tag", f.tag());
        meta.addProperty("label", Json.str(f.t(), "name"));
        meta.addProperty("unit", Json.str(f.t(), "unit"));
        meta.add("value", value);
        meta.addProperty("reason", reason == null ? "" : reason);
        meta.addProperty("approval", approval);
        meta.addProperty("requestedBy", p.username());
        meta.addProperty("requestedByName", p.name());
        meta.addProperty("requestedAt", Instant.now().toString());
        JsonObject last = sources.lastReading(UUID.fromString(f.agent().id()), f.machine(), f.tag());
        if (last != null && last.has("tagValue")) {
            meta.add("valueBefore", last.get("tagValue"));
        }
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO {s}.bimba_agent_command (id, org_id, agent_id, kind, request, state, created_at, created_by)"
                        + " VALUES (?, ?, ?, 'write', ?::jsonb, ?, now(), ?)")
                .params(id, org, UUID.fromString(f.agent().id()), command(f, value, meta).toString(), approval ? "approval" : "pending", p.username())
                .update();
        if (!approval) {
            sources.wake(UUID.fromString(f.agent().id()));
        }
        security.audit(p, "machine.write.request", f.machine() + "/" + f.tag(), true, ip, detail(id, meta));
        log.info("machine command {} by {}: {}/{} = {}{}", id, p.username(), f.machine(), f.tag(), value, approval ? " (waits for approval)" : "");
        return view(row(id, org), p);
    }

    /** what the agent gets: the source's stored settings (the password opened when the agent fetches it), the tag, the value */
    private static JsonObject command(Found f, JsonPrimitive value, JsonObject meta) {
        JsonObject source = new JsonObject();
        source.addProperty("kind", Json.str(f.source(), "kind"));
        source.add("settings", f.source().getAsJsonObject("settings").deepCopy());
        JsonObject req = new JsonObject();
        req.add("source", source);
        req.addProperty("sourceId", Json.str(f.source(), "id"));
        req.add("tag", f.t().deepCopy());
        req.add("value", value);
        req.addProperty("requestedBy", Json.str(meta, "requestedBy"));
        req.add("meta", meta);
        return req;
    }

    /** approves a command that waits for a second person: checked again as if sent now, then on its way */
    public Map<String, Object> approve(Principal p, String id, String ip) throws ApiException {
        long org = p.org();
        Row r = row(uuid(id), org);
        if (!"approval".equals(r.state())) {
            throw new ApiException(409, "state", "This command does not wait for approval any more (" + r.state() + ").");
        }
        if (r.createdBy().equalsIgnoreCase(p.username())) {
            throw new ApiException(403, "own", "You asked for this command: another person has to approve it.");
        }
        JsonObject meta = r.meta();
        Found f = find(org, Json.str(meta, "sourceId"), Json.str(meta, "machine"), Json.str(meta, "tag"));
        here(p, Permission.WRITES_APPROVE, f);
        JsonPrimitive value = value(f, meta.get("value"));
        check(f, org);
        meta.addProperty("approvedBy", p.username());
        meta.addProperty("approvedByName", p.name());
        meta.addProperty("approvedAt", Instant.now().toString());
        meta.addProperty("approval", true);
        // created_at restarts: the agent must pick it up within the time limit from now
        int n = db.sql("UPDATE {s}.bimba_agent_command SET state = 'pending', created_at = now(), request = ?::jsonb, agent_id = ?"
                        + " WHERE id = ? AND org_id = ? AND state = 'approval'")
                .params(command(f, value, meta).toString(), UUID.fromString(f.agent().id()), r.id(), org).update();
        if (n == 0) {
            throw new ApiException(409, "state", "Someone else acted on this command first.");
        }
        sources.wake(UUID.fromString(f.agent().id()));
        security.audit(p, "machine.write.approve", f.machine() + "/" + f.tag(), true, ip, detail(r.id(), meta));
        return view(row(r.id(), org), p);
    }

    public Map<String, Object> reject(Principal p, String id, JsonObject b, String ip) throws ApiException {
        long org = p.org();
        Row r = row(uuid(id), org);
        if (!rowAllows(p, Permission.WRITES_APPROVE, r, agents(org))) {
            throw security.outside(p, Permission.WRITES_APPROVE, Json.str(r.meta(), "machine") + "/" + Json.str(r.meta(), "tag"));
        }
        String reason = text(b, "reason", 300);
        int n = db.sql("UPDATE {s}.bimba_agent_command SET state = 'rejected', error = ?, done_at = now() WHERE id = ? AND org_id = ? AND state = 'approval'")
                .params("Rejected by " + p.name() + (reason == null || reason.isBlank() ? "." : ": " + reason), r.id(), org).update();
        if (n == 0) {
            throw new ApiException(409, "state", "This command does not wait for approval any more (" + r.state() + ").");
        }
        Map<String, Object> d = detail(r.id(), r.meta());
        d.put("why", reason);
        security.audit(p, "machine.write.reject", Json.str(r.meta(), "machine") + "/" + Json.str(r.meta(), "tag"), true, ip, d);
        return view(row(r.id(), org), p);
    }

    /** the requester (or an approver) takes back a command that is not on its way yet */
    public Map<String, Object> cancel(Principal p, String id, String ip) throws ApiException {
        long org = p.org();
        Row r = row(uuid(id), org);
        if (!r.createdBy().equalsIgnoreCase(p.username()) && !rowAllows(p, Permission.WRITES_APPROVE, r, agents(org))) {
            throw new ApiException(403, "forbidden", "Only the person who asked for it, or an approver, can cancel this command.");
        }
        int n = db.sql("UPDATE {s}.bimba_agent_command SET state = 'cancelled', error = ?, done_at = now()"
                        + " WHERE id = ? AND org_id = ? AND state IN ('approval', 'pending')")
                .params("Cancelled by " + p.name() + ".", r.id(), org).update();
        if (n == 0) {
            throw new ApiException(409, "state", "sent".equals(r.state()) ? "The agent already has this command: it cannot be taken back."
                    : "This command is already " + r.state() + ".");
        }
        security.audit(p, "machine.write.cancel", Json.str(r.meta(), "machine") + "/" + Json.str(r.meta(), "tag"), true, ip, detail(r.id(), r.meta()));
        return view(row(r.id(), org), p);
    }

    // ================================================================== kill switch

    /** body {scope: org | site | agent, target (site name / agent id), reason}: stops commands there and cancels the waiting ones */
    public Map<String, Object> stop(Principal p, JsonObject b, String ip) throws ApiException {
        long org = p.org();
        if (!p.can(Permission.WRITES_SEND) && !p.can(Permission.WRITES_APPROVE) && !p.can(Permission.WRITES_MANAGE)) {
            throw new ApiException(403, "forbidden", "Your roles do not include machine commands.");
        }
        Scope sc = scope(b, org);
        if (!mayAct(p, sc, org, STOPPERS)) {
            throw security.outside(p, Permission.WRITES_SEND, "kill switch " + sc.label());
        }
        String reason = text(b, "reason", 300);
        JsonObject stop = new JsonObject();
        stop.addProperty("by", p.username());
        stop.addProperty("byName", p.name());
        stop.addProperty("at", Instant.now().toString());
        stop.addProperty("reason", reason == null ? "" : reason);
        JsonObject all = stops(org);
        switch (sc.kind()) {
            case "org" -> all.add("org", stop);
            case "site" -> child(all, "sites").add(sc.target(), stop);
            default -> child(all, "agents").add(sc.target(), stop);
        }
        save(org, all);
        // what waits in that scope will not go out
        List<UUID> agents = "org".equals(sc.kind()) ? null : agents(org).values().stream()
                .filter(a -> "agent".equals(sc.kind()) ? a.id().equals(sc.target()) : sc.target().equalsIgnoreCase(nz(a.site()).trim()))
                .map(a -> UUID.fromString(a.id())).toList();
        int cancelled = db.sql("UPDATE {s}.bimba_agent_command SET state = 'cancelled', error = ?, done_at = now()"
                        + " WHERE kind = 'write' AND org_id = ? AND state IN ('approval', 'pending')"
                        + (agents == null ? "" : " AND agent_id = ANY (?::uuid[])"))
                .params(agents == null ? new Object[]{"Stopped by " + p.name() + ".", org}
                        : new Object[]{"Stopped by " + p.name() + ".", org, "{" + String.join(",", agents.stream().map(UUID::toString).toList()) + "}"})
                .update();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("scope", sc.kind());
        d.put("target", sc.target());
        d.put("reason", reason);
        d.put("cancelled", cancelled);
        security.audit(p, "machine.write.stop", sc.label(), true, ip, d);
        log.warn("machine commands stopped {} by {}{}", sc.label(), p.username(), cancelled > 0 ? " (" + cancelled + " waiting cancelled)" : "");
        return Map.of("stops", Json.toJava(all.toString()), "cancelled", cancelled);
    }

    public Map<String, Object> resume(Principal p, JsonObject b, String ip) throws ApiException {
        long org = p.org();
        Scope sc = scope(b, org);
        if (!mayAct(p, sc, org, List.of(Permission.WRITES_MANAGE))) {
            throw security.outside(p, Permission.WRITES_MANAGE, "kill switch " + sc.label());
        }
        JsonObject all = stops(org);
        boolean was = switch (sc.kind()) {
            case "org" -> all.remove("org") != null;
            case "site" -> {
                JsonObject sites = obj(all, "sites");
                String key = sites == null ? null : sites.keySet().stream().filter(k -> k.equalsIgnoreCase(sc.target())).findFirst().orElse(null);
                yield key != null && sites.remove(key) != null;
            }
            default -> obj(all, "agents") != null && obj(all, "agents").remove(sc.target()) != null;
        };
        if (!was) {
            throw new ApiException(409, "not_stopped", "Machine commands are not stopped " + sc.label() + ".");
        }
        save(org, all);
        security.audit(p, "machine.write.resume", sc.label(), true, ip, Map.of("scope", sc.kind(), "target", sc.target()));
        log.info("machine commands resumed {} by {}", sc.label(), p.username());
        return Map.of("stops", Json.toJava(all.toString()));
    }

    private record Scope(String kind, String target, String label) {
    }

    private Scope scope(JsonObject b, long org) throws ApiException {
        String kind = AgentRegistry.str(b, "scope", 10);
        String target = AgentRegistry.str(b, "target", 120);
        if ("org".equals(kind)) {
            return new Scope("org", "", "for the whole organization");
        }
        if ("site".equals(kind) && target != null && !target.isBlank()) {
            return new Scope("site", target, "at site " + target);
        }
        if ("agent".equals(kind) && target != null) {
            Agent a = agents(org).get(target);
            if (a == null) {
                throw new ApiException(404, "not_found", "That site agent does not exist in this organization.");
            }
            return new Scope("agent", a.id(), "for site agent " + a.name());
        }
        throw new ApiException(400, "scope", "Stop where: the organization, a site or a site agent?");
    }

    JsonObject stops(long org) {
        return db.sql("SELECT value::text FROM {s}.bimba_gw_setting WHERE key = ?").param(KEY + org).query(String.class).optional()
                .map(v -> Json.GSON.fromJson(v, JsonObject.class)).orElseGet(JsonObject::new);
    }

    private void save(long org, JsonObject all) {
        db.sql("INSERT INTO {s}.bimba_gw_setting (key, value, updated_at) VALUES (?, ?::jsonb, now())"
                        + " ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = now()")
                .params(KEY + org, all.toString()).update();
    }

    // ================================================================== scopes

    /** the permissions that may stop machine commands (only writes.manage resumes) */
    private static final List<Permission> STOPPERS = List.of(Permission.WRITES_SEND, Permission.WRITES_APPROVE, Permission.WRITES_MANAGE);
    /** the permissions that show a machine and its commands */
    private static final List<Permission> SEERS = List.of(Permission.MACHINES_VIEW, Permission.WRITES_SEND, Permission.WRITES_APPROVE,
            Permission.WRITES_MANAGE);

    /** may the user see this machine (machines.view or any command permission, there) */
    private static boolean visible(Principal p, String agentId, Agent a, String machine) {
        String site = a == null ? null : a.site();
        return SEERS.stream().anyMatch(x -> p.allows(x, agentId, site, machine));
    }

    private void see(Principal p, Located l, Agent a) throws ApiException {
        if (!visible(p, Json.str(l.source(), "agentId"), a, l.machine())) {
            throw security.outside(p, Permission.MACHINES_VIEW, "machine " + l.machine());
        }
    }

    /** what the user may do on one machine */
    private static Map<String, Object> can(Principal p, String agentId, Agent a, String machine) {
        String site = a == null ? null : a.site();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("send", p.allows(Permission.WRITES_SEND, agentId, site, machine));
        m.put("approve", p.allows(Permission.WRITES_APPROVE, agentId, site, machine));
        m.put("resume", p.allows(Permission.WRITES_MANAGE, agentId, site, machine));
        return m;
    }

    private void here(Principal p, Permission perm, Found f) throws ApiException {
        if (!p.allows(perm, f.agent().id(), f.agent().site(), f.machine())) {
            throw security.outside(p, perm, f.machine() + "/" + f.tag());
        }
    }

    private static boolean rowAllows(Principal p, Permission perm, Row r, Map<String, Agent> agents) {
        Agent a = r.agentId() == null ? null : agents.get(r.agentId());
        return p.allows(perm, r.agentId(), a == null ? null : a.site(), Json.str(r.meta(), "machine"));
    }

    private static boolean rowVisible(Principal p, Row r, Map<String, Agent> agents) {
        return SEERS.stream().anyMatch(x -> rowAllows(p, x, r, agents));
    }

    /** a site-wide stop needs the permission for the whole site (or everywhere), not just for some of its agents */
    private static boolean stopSite(Principal p, String site, List<Permission> perms) {
        return perms.stream().anyMatch(x -> p.can(x) && (p.scope(x) == null || com.bimba.setu.security.Scope.has(p.scope(x).sites(), site.trim())));
    }

    private static boolean stopAgent(Principal p, Agent a, List<Permission> perms) {
        return perms.stream().anyMatch(x -> p.allows(x, a.id(), a.site(), null));
    }

    /** may the user stop (or resume) commands at this place */
    private boolean mayAct(Principal p, Scope sc, long org, List<Permission> perms) {
        return switch (sc.kind()) {
            case "org" -> perms.stream().anyMatch(p::everywhere);
            case "site" -> stopSite(p, sc.target(), perms);
            default -> {
                Agent a = agents(org).get(sc.target());
                yield a != null && stopAgent(p, a, perms);
            }
        };
    }

    // ================================================================== history

    /** the newest commands first, all or one state's (state=approval: those waiting): {commands:[..], waiting: n} */
    public Map<String, Object> list(Principal p, int limit, String state) throws ApiException {
        long org = p.org();
        List<Map<String, Object>> out = new ArrayList<>();
        boolean one = state != null && !state.isBlank();
        int n = Math.max(1, Math.min(limit, 500));
        Map<String, Agent> agents = agents(org);
        boolean all = SEERS.stream().anyMatch(p::everywhere);
        // a scoped user's commands are picked from more rows, so their newest still fill the page
        for (Row r : rows("WHERE org_id = ? AND kind = 'write'" + (one ? " AND state = ?" : "")
                        + " ORDER BY coalesce((request->'meta'->>'requestedAt')::timestamptz, created_at) DESC LIMIT ?",
                one ? new Object[]{org, state, all ? n : 2000} : new Object[]{org, all ? n : 2000})) {
            if (out.size() < n && rowVisible(p, r, agents)) {
                out.add(view(r, p));
            }
        }
        long waiting = all ? db.sql("SELECT count(*) FROM {s}.bimba_agent_command WHERE org_id = ? AND kind = 'write' AND state = 'approval'")
                .param(org).query(Long.class).single()
                : rows("WHERE org_id = ? AND kind = 'write' AND state = 'approval'", org).stream().filter(r -> rowVisible(p, r, agents)).count();
        return Map.of("commands", out, "waiting", waiting);
    }

    public Map<String, Object> get(Principal p, String id) throws ApiException {
        Row r = row(uuid(id), p.org());
        if (!rowVisible(p, r, agents(p.org()))) {
            throw new ApiException(404, "not_found", "That command does not exist.");
        }
        return view(r, p);
    }

    private Map<String, Object> view(Row r, Principal p) {
        JsonObject meta = r.meta();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id().toString());
        m.put("state", r.state());
        for (String k : List.of("machine", "tag", "label", "unit", "source", "sourceId", "kind", "agent", "reason", "requestedBy",
                "requestedByName", "requestedAt", "approvedBy", "approvedByName", "approvedAt")) {
            m.put(k, meta.has(k) ? Json.str(meta, k) : null);
        }
        m.put("agentId", r.agentId());
        m.put("value", meta.has("value") ? Json.toJava(meta.get("value").toString()) : null);
        m.put("valueBefore", meta.has("valueBefore") ? Json.toJava(meta.get("valueBefore").toString()) : null);
        m.put("approval", meta.has("approval") && meta.get("approval").getAsBoolean());
        m.put("result", r.result() == null ? null : Json.toJava(r.result()));
        m.put("error", r.error() == null || r.error().isEmpty() ? null : r.error());
        m.put("sentAt", r.sentAt() == null ? null : r.sentAt().toInstant().toString());
        m.put("doneAt", r.doneAt() == null ? null : r.doneAt().toInstant().toString());
        m.put("mine", r.createdBy().equalsIgnoreCase(p.username()));
        if ("approval".equals(r.state())) {
            m.put("approveBy", Instant.parse(Json.str(meta, "requestedAt")).plusSeconds(APPROVAL_MINUTES * 60L).toString());
        }
        return m;
    }

    // ================================================================== expiry + answers

    /**
     * Commands that ran out of time: not picked up within the time limit, no answer within a minute, or no approval
     * within 15 minutes. Each one is audited.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 20_000)
    public void expire() {
        List<Object[]> gone;
        try {
            gone = db.sql("UPDATE {s}.bimba_agent_command SET state = 'expired', error = CASE state"
                            + " WHEN 'pending' THEN 'The site agent did not pick up the command within " + AgentSources.WRITE_TTL_SECONDS
                            + " seconds, so it was not carried out. Is the agent online?'"
                            + " WHEN 'sent' THEN 'The site agent did not answer within " + ANSWER_SECONDS
                            + " seconds. The value may or may not have been written: check the machine.'"
                            + " ELSE 'Nobody approved the command within " + APPROVAL_MINUTES + " minutes.' END"
                            + " WHERE kind = 'write' AND (state = 'pending' AND created_at < now() - interval '" + AgentSources.WRITE_TTL_SECONDS + " seconds'"
                            + " OR state = 'sent' AND sent_at < now() - interval '" + ANSWER_SECONDS + " seconds'"
                            + " OR state = 'approval' AND created_at < now() - interval '" + APPROVAL_MINUTES + " minutes')"
                            + " RETURNING id, org_id, created_by, request->'meta', error")
                    .query((rs, n) -> new Object[]{rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)}).list();
        } catch (RuntimeException e) {
            log.debug("machine command expiry skipped: {}", e.getMessage());
            return;
        }
        for (Object[] g : gone) {
            JsonObject meta = g[3] == null ? new JsonObject() : Json.GSON.fromJson((String) g[3], JsonObject.class);
            Map<String, Object> d = detail(UUID.fromString((String) g[0]), meta);
            d.put("error", g[4]);
            security.audit((Long) g[1], (String) g[2], "machine.write.expired", Json.str(meta, "machine") + "/" + Json.str(meta, "tag"),
                    false, null, d);
            log.warn("machine command {} expired: {}", g[0], g[4]);
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void purge() {
        try {
            sources.purge();
        } catch (RuntimeException e) {
            log.debug("command purge skipped: {}", e.getMessage());
        }
    }

    /** the agent answered: the result goes to the audit log under the requester's name */
    void answered(UUID id) {
        try {
            Row r = rows("WHERE id = ?", id).stream().findFirst().orElse(null);
            if (r == null) {
                return;
            }
            Map<String, Object> d = detail(id, r.meta());
            JsonObject res = r.result() == null ? null : Json.GSON.fromJson(r.result(), JsonObject.class);
            if (res != null) {
                for (String k : List.of("before", "after", "verified", "detail", "target")) {
                    if (res.has(k)) {
                        d.put(k, Json.toJava(res.get(k).toString()));
                    }
                }
            }
            d.put("error", r.error() == null || r.error().isEmpty() ? null : r.error());
            security.audit(r.orgId(), r.createdBy(), "machine.write.result", Json.str(r.meta(), "machine") + "/" + Json.str(r.meta(), "tag"),
                    "done".equals(r.state()), null, d);
            log.info("machine command {} {}: {}", id, r.state(), "done".equals(r.state()) ? d.get("after") : r.error());
        } catch (RuntimeException e) {
            log.warn("machine command {} answered, but not audited: {}", id, e.getMessage());
        }
    }

    private static Map<String, Object> detail(UUID id, JsonObject meta) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("commandId", id.toString());
        d.put("agent", Json.str(meta, "agent"));
        d.put("source", Json.str(meta, "source"));
        d.put("value", meta.has("value") ? Json.toJava(meta.get("value").toString()) : null);
        d.put("unit", Json.str(meta, "unit"));
        d.put("reason", Json.str(meta, "reason"));
        d.put("requestedBy", Json.str(meta, "requestedBy"));
        if (meta.has("approvedBy")) {
            d.put("approvedBy", Json.str(meta, "approvedBy"));
        }
        return d;
    }

    // ================================================================== checks

    private record Found(JsonObject source, JsonObject t, String machine, String tag, Agent agent) {
    }

    private Found find(long org, String sourceId, String machine, String tag) throws ApiException {
        JsonObject s = sourceId == null ? null : sources.sourceOf(sourceId, org);
        if (s == null) {
            throw new ApiException(404, "not_found", "That data source does not exist in this organization.");
        }
        for (JsonElement te : s.getAsJsonArray("tags")) {
            if (!te.isJsonObject()) {
                continue;
            }
            JsonObject t = te.getAsJsonObject();
            String m = AgentSources.machineOf(s, t);
            String n = AgentSources.tagName(t);
            if (n.equals(tag) && (machine == null || machine.equalsIgnoreCase(m))) {
                if (!t.has("writable") || !t.get("writable").getAsBoolean()) {
                    throw new ApiException(403, "not_writable", "Tag " + m + " / " + n + " is not writable.");
                }
                Agent a = agents(org).get(Json.str(s, "agentId"));
                if (a == null) {
                    throw new ApiException(409, "agent", "The site agent of this data source is gone.");
                }
                return new Found(s, t, m, n, a);
            }
        }
        throw new ApiException(404, "not_found", "That tag does not exist on data source " + Json.str(s, "name") + ".");
    }

    /** the command may go out now: not stopped, source allows it, agent online, new enough and not refusing */
    private void check(Found f, long org) throws ApiException {
        String why = blocked(f.source(), f.agent(), stops(org));
        if (why != null) {
            throw new ApiException(409, "blocked", why);
        }
    }

    /** the value as the tag takes it, inside its limits */
    static JsonPrimitive value(Found f, JsonElement v) throws ApiException {
        return value(AgentSources.writeKind(Json.str(f.source(), "kind"), f.t()), f.t(), v);
    }

    static JsonPrimitive value(String takes, JsonObject t, JsonElement v) throws ApiException {
        String name = Json.str(t, "name");
        if (v == null || !v.isJsonPrimitive() || v.getAsString().isBlank()) {
            throw new ApiException(400, "value", "Enter the value to set.");
        }
        String raw = v.getAsString().trim();
        switch (takes) {
            case "boolean" -> {
                if (v.getAsJsonPrimitive().isBoolean()) {
                    return new JsonPrimitive(v.getAsBoolean());
                }
                return switch (raw.toLowerCase(Locale.ROOT)) {
                    case "true", "on", "1" -> new JsonPrimitive(true);
                    case "false", "off", "0" -> new JsonPrimitive(false);
                    default -> throw new ApiException(400, "value", name + " takes on or off.");
                };
            }
            case "text" -> {
                if (raw.length() > 200) {
                    throw new ApiException(400, "value", "At most 200 characters.");
                }
                return new JsonPrimitive(raw);
            }
            default -> {
                double d;
                try {
                    d = Double.parseDouble(raw);
                } catch (NumberFormatException e) {
                    throw new ApiException(400, "value", name + " takes a number.");
                }
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new ApiException(400, "value", name + " takes a number.");
                }
                double lo = t.get("writeMin").getAsDouble(), hi = t.get("writeMax").getAsDouble();
                if (d < lo || d > hi) {
                    String unit = Json.str(t, "unit");
                    throw new ApiException(400, "value", name + " may be set from " + num(lo) + " to " + num(hi)
                            + (unit.isBlank() ? "" : " " + unit) + ".");
                }
                return new JsonPrimitive(d);
            }
        }
    }

    private static String num(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ================================================================== agents + rows

    record Agent(String id, String name, String site, boolean online, String edgeVersion, String writes) {
    }

    private Map<String, Agent> agents(long org) {
        Map<String, Agent> m = new LinkedHashMap<>();
        db.sql("SELECT id, display_name, site, state = 'active' AND last_seen IS NOT NULL"
                        + " AND last_seen > now() - make_interval(secs => 3 * greatest(heartbeat_seconds, 10)), collector::text"
                        + " FROM {s}.bimba_agent WHERE org_id = ? ORDER BY display_name")
                .param(org)
                .query((rs, n) -> {
                    JsonObject c = rs.getString(5) == null ? new JsonObject() : Json.GSON.fromJson(rs.getString(5), JsonObject.class);
                    JsonObject edge = obj(c, "edge");
                    String version = edge == null || Json.str(edge, "version").isBlank() ? null : Json.str(edge, "version");
                    m.put(rs.getString(1), new Agent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), version,
                            Json.str(c, "writes")));
                    return null;
                }).list();
        return m;
    }

    private record Row(UUID id, long orgId, String agentId, String state, JsonObject meta, String result, String error, String createdBy,
                       Timestamp sentAt, Timestamp doneAt) {
    }

    private Row row(UUID id, long org) throws ApiException {
        return rows("WHERE id = ? AND org_id = ? AND kind = 'write'", id, org).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "That command does not exist."));
    }

    private List<Row> rows(String where, Object... params) {
        return db.sql("SELECT id, org_id, agent_id, state, request->'meta', result::text, error, created_by, sent_at, done_at"
                        + " FROM {s}.bimba_agent_command " + where)
                .params(params)
                .query((rs, n) -> new Row(UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5) == null ? new JsonObject() : Json.GSON.fromJson(rs.getString(5), JsonObject.class),
                        rs.getString(6), rs.getString(7), nz(rs.getString(8)), rs.getTimestamp(9), rs.getTimestamp(10)))
                .list();
    }

    // ================================================================== helpers

    /** version v ("1.9.0") is at least need ("1.9") */
    static boolean atLeast(String v, String need) {
        if (v == null || v.isBlank() || "dev".equals(v)) {
            return "dev".equals(v);
        }
        String[] a = v.split("[.-]"), b = need.split("\\.");
        for (int i = 0; i < b.length; i++) {
            int x;
            try {
                x = i < a.length ? Integer.parseInt(a[i]) : 0;
            } catch (NumberFormatException e) {
                return false;
            }
            int y = Integer.parseInt(b[i]);
            if (x != y) {
                return x > y;
            }
        }
        return true;
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static JsonObject child(JsonObject o, String k) {
        JsonObject c = obj(o, k);
        if (c == null) {
            c = new JsonObject();
            o.add(k, c);
        }
        return c;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String text(JsonObject b, String k, int max) {
        return AgentRegistry.str(b, k, max);
    }

    private static UUID uuid(String v) throws ApiException {
        try {
            return UUID.fromString(v);
        } catch (RuntimeException e) {
            throw new ApiException(404, "not_found", "That command does not exist.");
        }
    }
}
