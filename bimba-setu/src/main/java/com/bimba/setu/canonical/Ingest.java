package com.bimba.setu.canonical;

import com.bimba.setu.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The way into delivery for every canonical message, from Setu's own collector and from site agents: each one is
 * checked against its version's JSON Schema ({@link MessageCheck}); valid ones get ingestTime and go on, the others go
 * to the {@link Quarantine} with their raw text and a reason code. Replaying a quarantine entry runs its records
 * through the same checks again.
 */
@Service
public class Ingest {
    private final Quarantine quarantine;
    /** applied to every valid reading before delivery (null = none, e.g. in tests) */
    private final TagRegistry registry;

    @org.springframework.beans.factory.annotation.Autowired
    public Ingest(Quarantine quarantine, TagRegistry registry) {
        this.quarantine = quarantine;
        this.registry = registry;
    }

    public Ingest(Quarantine quarantine) {
        this(quarantine, null);
    }

    /** the reading with its tag registry entry applied (FR-CAN-04); org null = the owner of its machine */
    private JsonObject registered(JsonObject m, Long org) {
        return registry == null ? m : registry.apply(m, org);
    }

    public Quarantine quarantine() {
        return quarantine;
    }

    /** one reading of Setu's collector (its sink: must not block for long) */
    public void fromCollector(JsonObject m, Consumer<JsonObject> deliver) {
        MessageCheck.Problem p = MessageCheck.check(m);
        if (p != null) {
            quarantine.put(Quarantine.Origin.gateway(), p.reason(),
                    List.of(new Quarantine.Item(Json.GSON.toJson(m), p.detail(), field(m, "machineId", "machine_id"), field(m, "tagName", "tag_name"))));
            return;
        }
        JsonObject r = registered(m, null);
        r.addProperty("ingestTime", Instant.now().toString());
        deliver.accept(r);
    }

    /** what became of a site agent's readings */
    public record AgentResult(List<JsonObject> accepted, int rejected, List<String> unknownMachines) {
    }

    /**
     * A site agent's readings: each must pass its schema and report one of the agent's own machines; those that do are
     * stamped (source.agentId, ingestTime) and delivered, the others are quarantined per reason.
     */
    public AgentResult fromAgent(UUID agentId, long orgId, JsonArray messages, Set<String> machines, Consumer<JsonObject> deliver) {
        List<JsonObject> accepted = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        Map<MessageCheck.Reason, List<Quarantine.Item>> refused = new EnumMap<>(MessageCheck.Reason.class);
        String now = Instant.now().toString();
        for (JsonElement e : messages) {
            MessageCheck.Problem p = MessageCheck.check(e);
            JsonObject m = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
            if (p == null) {
                m = internal(m);                       // 2.x -> Setu's 1.x layout; the raw record stays as it came
            }
            String machine = m == null ? null : field(m, "machineId", "machine_id");
            if (p == null && !machines.contains(machine)) {
                p = new MessageCheck.Problem(MessageCheck.Reason.UNKNOWN_MACHINE,
                        "machine \"" + machine + "\" is not one of this site agent's machines (its data sources)");
                if (unknown.size() < 5 && !unknown.contains(machine)) {
                    unknown.add(machine);
                }
            }
            if (p != null) {
                refused.computeIfAbsent(p.reason(), k -> new ArrayList<>()).add(new Quarantine.Item(
                        e == null ? "null" : Json.GSON.toJson(e), p.detail(), machine, m == null ? null : field(m, "tagName", "tag_name")));
                continue;
            }
            m = registered(m, orgId);                  // after the ownership check, which is about the machine as collected
            JsonObject src = m.has("source") && m.get("source").isJsonObject() ? m.getAsJsonObject("source") : new JsonObject();
            src.addProperty("agentId", agentId.toString());
            m.add("source", src);
            m.addProperty("ingestTime", now);
            deliver.accept(m);
            accepted.add(m);
        }
        Quarantine.Origin origin = Quarantine.Origin.agent(agentId, orgId);
        int rejected = 0;
        for (Map.Entry<MessageCheck.Reason, List<Quarantine.Item>> r : refused.entrySet()) {
            quarantine.put(origin, r.getKey(), r.getValue());
            rejected += r.getValue().size();
        }
        return new AgentResult(accepted, rejected, unknown);
    }

    /** a site agent's request body that is not a JSON object with messages[]: kept as it came */
    public void malformedFromAgent(UUID agentId, long orgId, String body, String why) {
        quarantine.put(Quarantine.Origin.agent(agentId, orgId), MessageCheck.Reason.MALFORMED_JSON,
                List.of(new Quarantine.Item(body, why, null, null)));
    }

    /**
     * Runs the records of quarantine entries through the checks again (after the cause was fixed): the ones that pass
     * are delivered, the others form a new entry with their current reason; the replayed entry is removed.
     * {delivered, quarantined, missing: [ids not found]}
     */
    public Map<String, Object> replay(List<String> ids, Predicate<Quarantine.Meta> visible,
                                      Function<UUID, Set<String>> machinesOfAgent, Consumer<JsonObject> deliver) throws IOException {
        int delivered = 0;
        int again = 0;
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            Quarantine.Entry entry = quarantine.read(id, visible);
            if (entry == null) {
                missing.add(id);
                continue;
            }
            Quarantine.Origin o = entry.meta().origin();
            JsonArray records = new JsonArray();
            List<Quarantine.Item> unreadable = new ArrayList<>();
            for (Quarantine.Item it : entry.items()) {
                JsonElement e = parse(it.raw());
                if (e == null) {
                    unreadable.add(it);
                } else if (batch(e) != null) {
                    batch(e).forEach(records::add);                                           // a whole request body
                } else {
                    records.add(e);
                }
            }
            if (!quarantine.discard(id, visible)) {
                missing.add(id);                         // replayed or discarded by someone else meanwhile
                continue;
            }
            if (!unreadable.isEmpty()) {
                quarantine.put(o, MessageCheck.Reason.MALFORMED_JSON, unreadable);
                again += unreadable.size();
            }
            if ("agent".equals(o.kind()) && o.agentId() != null) {
                AgentResult r = fromAgent(o.agentId(), o.orgId(), records, machinesOfAgent.apply(o.agentId()), deliver);
                delivered += r.accepted().size();
                again += r.rejected();
            } else {
                for (JsonElement e : records) {
                    MessageCheck.Problem p = MessageCheck.check(e);
                    if (p == null) {
                        JsonObject m = registered(internal(e.getAsJsonObject()), o.orgId());
                        m.addProperty("ingestTime", Instant.now().toString());
                        deliver.accept(m);
                        delivered++;
                    } else {
                        JsonObject m = e.isJsonObject() ? e.getAsJsonObject() : null;
                        quarantine.put(o, p.reason(), List.of(new Quarantine.Item(Json.GSON.toJson(e), p.detail(),
                                m == null ? null : field(m, "machineId", "machine_id"), m == null ? null : field(m, "tagName", "tag_name"))));
                        again++;
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("delivered", delivered);
        out.put("quarantined", again);
        out.put("missing", missing);
        return out;
    }

    /** the readings of a batch body: {messages:[..]} (1.x) or {records:[..]} (2.x batch form), else null */
    public static JsonArray batch(JsonElement body) {
        if (body == null || !body.isJsonObject()) {
            return null;
        }
        for (String k : new String[]{"messages", "records"}) {
            JsonElement a = body.getAsJsonObject().get(k);
            if (a != null && a.isJsonArray()) {
                return a.getAsJsonArray();
            }
        }
        return null;
    }

    /** a valid message in Setu's own (1.x) layout */
    private static JsonObject internal(JsonObject m) {
        return MessageCheck.major(m) == 2 ? CanonicalV2.toInternal(m) : m;
    }

    /** a string field under its 1.x or 2.x name, null when neither is there */
    private static String field(JsonObject m, String v1, String v2) {
        String s = Quarantine.opt(m, v1);
        return s != null ? s : Quarantine.opt(m, v2);
    }

    private static JsonElement parse(String raw) {
        try {
            return raw == null ? null : Json.GSON.fromJson(raw, JsonElement.class);
        } catch (JsonParseException e) {
            return null;
        }
    }
}
