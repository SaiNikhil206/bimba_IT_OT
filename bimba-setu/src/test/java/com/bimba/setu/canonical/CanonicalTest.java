package com.bimba.setu.canonical;

import com.bimba.setu.Json;
import com.bimba.setu.collect.Canonical;
import com.bimba.setu.collect.Collector;
import com.bimba.setu.config.TagModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-RTE-01 (schema check before routing), FR-CAN-03 (quarantine with raw payload + reason), FR-CAN-05 (versions). */
class CanonicalTest {

    /** a message as site agents with the 1.0 edge collector send it */
    private static JsonObject v10(String machine) {
        return Json.GSON.fromJson("""
                {"schemaVersion":"1.0","messageId":"0b6c2d6e-3a54-4a8a-9c0e-6f1d2f3a4b5c","plantId":"PLANT01","machineId":"%s",
                 "timestamp":"2026-10-07T10:15:30.250Z","tagName":"flow","tagValue":42.7,"dataType":"number","unit":null,
                 "quality":"GOOD","category":"telemetry","source":{"protocol":"opcua","system":"scada-01","address":"ns=2;s=F"},
                 "edge":{"edgeId":"scada-01","session":"s","seq":7}}""".formatted(machine), JsonObject.class);
    }

    private static MessageCheck.Reason reason(JsonObject m) {
        MessageCheck.Problem p = MessageCheck.check(m);
        return p == null ? null : p.reason();
    }

    @Test
    void schemaVersions() {
        assertNull(MessageCheck.check(v10("PUMP-P1")), "1.0 messages of existing agents stay valid");
        JsonObject newer = v10("PUMP-P1");
        newer.addProperty("schemaVersion", "1.7");
        newer.addProperty("someFutureField", "x");
        newer.addProperty("sourceTimestamp", "2026-10-07T10:15:30.100Z");
        assertNull(MessageCheck.check(newer), "a later 1.x with fields this Setu server does not know is accepted");
        JsonObject v2 = v10("PUMP-P1");
        v2.addProperty("schemaVersion", "3.0");
        assertEquals(MessageCheck.Reason.UNSUPPORTED_VERSION, reason(v2));
    }

    @Test
    void schemaRules() {
        JsonObject noMachine = v10("PUMP-P1");
        noMachine.remove("machineId");
        assertEquals(MessageCheck.Reason.SCHEMA_INVALID, reason(noMachine));
        JsonObject quality = v10("PUMP-P1");
        quality.addProperty("quality", "OK");
        assertEquals(MessageCheck.Reason.SCHEMA_INVALID, reason(quality));
        JsonObject local = v10("PUMP-P1");
        local.addProperty("timestamp", "2026-10-07T15:45:30.250+05:30");
        assertEquals(MessageCheck.Reason.SCHEMA_INVALID, reason(local), "timestamps must be UTC");
        JsonObject garbage = v10("PUMP-P1");
        garbage.addProperty("timestamp", "yesterday");
        assertEquals(MessageCheck.Reason.SCHEMA_INVALID, reason(garbage));
        JsonObject type = v10("PUMP-P1");
        type.addProperty("tagValue", "42.7");
        MessageCheck.Problem p = MessageCheck.check(type);
        assertEquals(MessageCheck.Reason.SCHEMA_INVALID, p.reason(), "dataType number needs a number");
        assertTrue(p.detail().contains("tagValue"), p.detail());
        JsonObject uncertain = v10("PUMP-P1");
        uncertain.addProperty("quality", "UNCERTAIN");
        assertNull(MessageCheck.check(uncertain));
        assertEquals(MessageCheck.Reason.MALFORMED_JSON, MessageCheck.check(Json.GSON.fromJson("[1,2]", JsonArray.class)).reason());
    }

    @Test
    void collectorMessagesMatchTheSchema() throws Exception {
        TagModel.Tag t = new TagModel.Tag("LINE-SIM", "speed", "LINE-SIM/speed", TagModel.Kind.EXPRESSION, null, "240", null,
                "rpm", 0, 1, 0, 1, false, null, null, null);
        List<JsonObject> out = new CopyOnWriteArrayList<>();
        Collector c = new Collector("PLANT01", "gw", 100, List.of(t), new JsonObject(), out::add);
        c.start();
        for (int i = 0; i < 50 && out.isEmpty(); i++) {
            Thread.sleep(50);
        }
        c.stop();
        assertFalse(out.isEmpty());
        JsonObject m = out.get(0);
        assertEquals(Canonical.VERSION, Json.str(m, "schemaVersion"));
        assertNull(MessageCheck.check(m), () -> String.valueOf(MessageCheck.check(m)));
    }

    @Test
    void agentReadingsQuarantineAndReplay(@TempDir File dir) throws Exception {
        Quarantine q = new Quarantine(dir, 200, 10_000);
        Ingest ingest = new Ingest(q);
        UUID agent = UUID.randomUUID();
        JsonObject bad = v10("PUMP-P1");
        bad.addProperty("quality", "OK");
        JsonArray batch = new JsonArray();
        batch.add(v10("PUMP-P1"));
        batch.add(v10("NOT-MINE"));
        batch.add(bad);
        List<JsonObject> delivered = new ArrayList<>();
        Ingest.AgentResult r = ingest.fromAgent(agent, 7, batch, Set.of("PUMP-P1"), delivered::add);

        assertEquals(1, delivered.size());
        JsonObject ok = delivered.get(0);
        assertNotNull(Json.str(ok, "ingestTime"), "accepted messages get ingestTime");
        assertEquals(agent.toString(), Json.str(ok.getAsJsonObject("source"), "agentId"));
        assertEquals(2, r.rejected());
        assertEquals(List.of("NOT-MINE"), r.unknownMachines());

        List<Quarantine.Meta> all = q.list(x -> true, 100);
        assertEquals(2, all.size(), "one entry per reason");
        Quarantine.Meta unknown = all.stream().filter(x -> x.reason() == MessageCheck.Reason.UNKNOWN_MACHINE).findFirst().orElseThrow();
        Quarantine.Entry e = q.read(unknown.id(), x -> true);
        assertTrue(e.items().get(0).raw().contains("\"NOT-MINE\""), "the raw record is kept");
        assertFalse(e.items().get(0).raw().contains("agentId"), "as it came, before Setu stamped it");
        assertNull(q.read(unknown.id(), x -> x.origin().orgId() == 8), "other organizations do not see it");

        // the machine is added to the agent: replay delivers it; the schema-invalid one goes back to quarantine
        delivered.clear();
        Map<String, Object> rep = ingest.replay(all.stream().map(Quarantine.Meta::id).toList(), x -> true,
                a -> Set.of("PUMP-P1", "NOT-MINE"), delivered::add);
        assertEquals(1, rep.get("delivered"));
        assertEquals(1, rep.get("quarantined"));
        assertEquals(1, q.list(x -> true, 100).size());
        assertEquals(MessageCheck.Reason.SCHEMA_INVALID, q.list(x -> true, 100).get(0).reason());

        // kept across restarts
        Quarantine again = new Quarantine(dir, 200, 10_000);
        assertEquals(1, again.list(x -> true, 100).size());
        assertEquals(1L, again.count(x -> x.origin().orgId() == 7)[1]);
    }

    @Test
    void gatewayReadings(@TempDir File dir) throws Exception {
        Quarantine q = new Quarantine(dir, 200, 10_000);
        Ingest ingest = new Ingest(q);
        List<JsonObject> delivered = new ArrayList<>();
        JsonObject bad = v10("PUMP-P1");
        bad.remove("timestamp");
        ingest.fromCollector(v10("PUMP-P1"), delivered::add);
        ingest.fromCollector(bad, delivered::add);
        assertEquals(1, delivered.size());
        Quarantine.Meta m = new Quarantine(dir, 200, 10_000).list(x -> true, 10).get(0);   // read back after a restart
        assertEquals("gateway", m.origin().kind());
        assertNull(m.origin().agentId());
        assertNull(m.origin().orgId());
        assertEquals(List.of("PUMP-P1"), m.machines(), "the machine decides which organization sees it");
        assertTrue(m.detail().contains("timestamp"), m.detail());
    }

    @Test
    void unreadableBodyAndLimits(@TempDir File dir) throws Exception {
        Quarantine q = new Quarantine(dir, 200, 3);
        Ingest ingest = new Ingest(q);
        UUID agent = UUID.randomUUID();
        ingest.malformedFromAgent(agent, 1, "{\"messages\": [ {oops", "the request body is not JSON");
        Quarantine.Meta m = q.list(x -> true, 10).get(0);
        assertEquals(MessageCheck.Reason.MALFORMED_JSON, m.reason());
        assertEquals("{\"messages\": [ {oops", q.read(m.id(), x -> true).items().get(0).raw());
        for (int i = 0; i < 4; i++) {
            ingest.malformedFromAgent(agent, 1, "x" + i, "not JSON");
        }
        assertEquals(3, q.list(x -> true, 10).size(), "the oldest entries go when the quarantine is full");
        assertEquals(2L, q.status().get("dropped"));
    }
}
