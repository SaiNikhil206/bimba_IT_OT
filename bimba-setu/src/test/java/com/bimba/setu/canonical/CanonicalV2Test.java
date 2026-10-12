package com.bimba.setu.canonical;

import com.bimba.setu.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-CAN-01: the BRD canonical envelope as message format 2.0, next to 1.x (FR-CAN-05). */
class CanonicalV2Test {

    /** the BRD/FRD v0.2 section 8.1 example, as 2.0 */
    private static JsonObject brdExample() {
        return Json.GSON.fromJson("""
                {"schema_version":"2.0","message_id":"9f1c2a4e-5b7d-4c0a-8d1e-2f6a7b3c9e10","plant_id":"PLANT-BLR-01",
                 "machine_id":"LINE2-PRESS-07","tag_name":"Temperature","tag_value":74.82,"data_type":"Double","unit":"degC",
                 "quality":"GOOD","timestamp":"2026-10-06T09:15:42.318Z",
                 "source":{"path":"IGNITION","protocol":"OPCUA","address":"ns=2;s=[Line2]Press07/Temp","interface":"N1_MQTT"},
                 "ingest_time":"2026-10-06T09:15:42.402Z","attributes":{"area":"Forming","shift":"B"}}""", JsonObject.class);
    }

    private static JsonObject v1() {
        return Json.GSON.fromJson("""
                {"schemaVersion":"%s","messageId":"0b6c2d6e-3a54-4a8a-9c0e-6f1d2f3a4b5c","plantId":"PLANT01","machineId":"PUMP-P1",
                 "timestamp":"2026-10-07T10:15:30.250Z","sourceTimestamp":"2026-10-07T10:15:30.100Z","tagName":"flow","tagValue":42.7,
                 "dataType":"number","unit":"m3/h","quality":"UNCERTAIN","qualityDetail":"outside the engineering range 0..40",
                 "category":"telemetry","source":{"protocol":"modbus","system":"gw","address":"PLC/HR2"},
                 "edge":{"edgeId":"gw","session":"s","seq":7},"ingestTime":"2026-10-07T10:15:30.262Z"}"""
                .formatted(com.bimba.setu.collect.Canonical.VERSION), JsonObject.class);
    }

    @Test
    void brdExampleIsValid() {
        assertNull(MessageCheck.check(brdExample()));
        JsonObject wrong = brdExample();
        wrong.addProperty("tag_value", "hot");
        assertEquals(MessageCheck.Reason.SCHEMA_INVALID, MessageCheck.check(wrong).reason(), "Double needs a number");
        JsonObject missing = brdExample();
        missing.remove("plant_id");
        assertEquals(MessageCheck.Reason.SCHEMA_INVALID, MessageCheck.check(missing).reason());
        JsonObject mixed = brdExample();
        mixed.addProperty("schema_version", "1.1");          // a 1.x version with 2.0 field names
        assertEquals(MessageCheck.Reason.SCHEMA_INVALID, MessageCheck.check(mixed).reason());
    }

    @Test
    void outgoingMessage() {
        JsonObject out = CanonicalV2.message(v1());
        assertNull(MessageCheck.check(out), () -> String.valueOf(MessageCheck.check(out)));
        assertEquals("2.0", Json.str(out, "schema_version"));
        assertEquals("PUMP-P1", Json.str(out, "machine_id"));
        assertEquals(42.7, out.get("tag_value").getAsDouble());
        assertEquals("Double", Json.str(out, "data_type"));
        assertEquals("outside the engineering range 0..40", Json.str(out, "quality_detail"));
        assertEquals("2026-10-07T10:15:30.100Z", Json.str(out, "source_timestamp"));
        assertEquals("2026-10-07T10:15:30.262Z", Json.str(out, "ingest_time"));
        JsonObject src = out.getAsJsonObject("source");
        assertEquals("DIRECT", Json.str(src, "path"));
        assertEquals("MODBUS_TCP", Json.str(src, "protocol"));
        assertEquals("PLC/HR2", Json.str(src, "address"));
        assertEquals("BIMBA_GATEWAY", Json.str(src, "interface"));
        assertEquals("gw", Json.str(out.getAsJsonObject("edge"), "edge_id"));
        assertFalse(out.has("messageId") || out.has("schemaVersion"), "no 1.x names left");

        JsonObject agent = v1();
        agent.getAsJsonObject("source").addProperty("agentId", "a-1");
        assertEquals("BIMBA_SITE_AGENT", Json.str(CanonicalV2.message(agent).getAsJsonObject("source"), "interface"));
        JsonObject bad = v1();
        bad.add("tagValue", com.google.gson.JsonNull.INSTANCE);
        bad.addProperty("dataType", "null");
        bad.addProperty("quality", "BAD");
        assertNull(MessageCheck.check(CanonicalV2.message(bad)));
    }

    @Test
    void roundTrip() {
        JsonObject back = CanonicalV2.toInternal(CanonicalV2.message(v1()));
        JsonObject expected = v1();
        back.getAsJsonObject("source").remove("path");
        back.getAsJsonObject("source").remove("interface");
        assertEquals(expected, back, "1.x -> 2.0 -> 1.x keeps every field");

        JsonObject in = CanonicalV2.toInternal(brdExample());
        assertNull(MessageCheck.check(in), () -> String.valueOf(MessageCheck.check(in)));
        assertEquals("LINE2-PRESS-07", Json.str(in, "machineId"));
        assertEquals("number", Json.str(in, "dataType"));
        assertEquals("opcua", Json.str(in.getAsJsonObject("source"), "protocol"));
        assertEquals("IGNITION", Json.str(in.getAsJsonObject("source"), "path"));
        assertEquals("Forming", Json.str(in.getAsJsonObject("attributes"), "area"), "attributes are passed on as they are");
    }

    @Test
    void envelope() {
        JsonObject env = Json.GSON.fromJson("""
                {"schemaVersion":"1.1","batchId":"b-1","plantId":"PLANT01","edgeId":"gw","source":"bimba-gateway",
                 "createdAt":"2026-10-07T10:15:31Z","count":1,"messages":[]}""", JsonObject.class);
        JsonArray records = new JsonArray();
        records.add(CanonicalV2.message(v1()));
        JsonObject out = CanonicalV2.envelope(env, records);
        assertEquals("2.0", Json.str(out, "schema_version"));
        assertEquals("b-1", Json.str(out, "batch_id"));
        assertEquals("PLANT01", Json.str(out, "plant_id"));
        assertEquals(1, out.getAsJsonArray("records").size());
        assertFalse(out.has("messages"));
    }

    @Test
    void agentSendsTwoPointZero(@TempDir File dir) throws Exception {
        Ingest ingest = new Ingest(new Quarantine(dir, 200, 10_000));
        JsonObject body = new JsonObject();
        JsonArray records = new JsonArray();
        records.add(brdExample());
        records.add(CanonicalV2.message(v1()));
        body.add("records", records);
        List<JsonObject> delivered = new ArrayList<>();
        Ingest.AgentResult r = ingest.fromAgent(UUID.randomUUID(), 1, Ingest.batch(body), Set.of("LINE2-PRESS-07", "PUMP-P1"), delivered::add);
        assertEquals(2, r.accepted().size(), "1.x and 2.x readings may come in the same batch");
        for (JsonObject m : delivered) {
            assertTrue(m.has("machineId") && m.has("ingestTime"), "delivered in Setu's own layout: " + m);
            assertNull(MessageCheck.check(m));
        }
    }
}
