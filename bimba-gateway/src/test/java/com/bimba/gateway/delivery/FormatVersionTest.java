package com.bimba.gateway.delivery;

import com.bimba.gateway.Json;
import com.bimba.gateway.canonical.MessageCheck;
import com.bimba.gateway.config.BimbaConfig;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-CAN-05: each destination receives the message format version it is set to; 1 stays exactly as before. */
class FormatVersionTest {
    private static final byte[] BATCH = """
            {"schemaVersion":"1.1","batchId":"b-1","plantId":"PLANT01","edgeId":"gw","source":"bimba-gateway",
             "createdAt":"2026-10-07T10:15:31Z","count":1,"messages":[
             {"schemaVersion":"1.1","messageId":"0b6c2d6e-3a54-4a8a-9c0e-6f1d2f3a4b5c","plantId":"PLANT01","machineId":"PUMP-P1",
              "timestamp":"2026-10-07T10:15:30.250Z","tagName":"flow","tagValue":42.7,"dataType":"number","unit":"m3/h",
              "quality":"GOOD","category":"telemetry","source":{"protocol":"modbus","system":"gw","address":"PLC/HR2"}}]}"""
            .getBytes(StandardCharsets.UTF_8);

    /** a destination that only shows what it would send */
    private static Destination dest(String version, String transport, String template) {
        BimbaConfig.Destination c = new BimbaConfig.Destination();
        c.name = "t";
        c.transport = transport;
        c.schemaVersion = version;
        c.template = template;
        return new Destination(c) {
            @Override
            public void send(byte[] batch) {
            }

            @Override
            public String endpoint() {
                return "test";
            }

            @Override
            public String test() {
                return "ok";
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void versionOneIsUnchanged() {
        assertSame(BATCH, dest("1", "rest", null).mappedEnvelope(BATCH), "format 1 sends the batch exactly as before");
        assertSame(BATCH, dest(null, "rest", null).mappedEnvelope(BATCH), "destinations saved before 2.0 have no version: 1");
        assertSame(BATCH, dest("2", "database", null).mappedEnvelope(BATCH), "the BIMBA database always takes 1.x");
    }

    @Test
    void versionTwoSendsTheBrdEnvelope() {
        Destination d = dest("2", "rest", null);
        JsonObject env = Json.GSON.fromJson(new String(d.mappedEnvelope(BATCH), StandardCharsets.UTF_8), JsonObject.class);
        assertEquals("2.0", Json.str(env, "schema_version"));
        assertEquals("b-1", Json.str(env, "batch_id"));
        JsonObject rec = env.getAsJsonArray("records").get(0).getAsJsonObject();
        assertEquals("PUMP-P1", Json.str(rec, "machine_id"));
        assertNull(MessageCheck.check(rec));
        assertEquals("batch_id", d.key("batchId"));
        assertEquals("2.0", d.version(env));

        // other body formats carry the 2.0 readings too
        Formats.Body nd = Formats.render("ndjson", BATCH, "", d.mapsReadings() ? d::layout : null);
        assertTrue(new String(nd.bytes(), StandardCharsets.UTF_8).contains("\"tag_value\":42.7"));
    }

    @Test
    void templatesTakeEitherName() {
        Destination d = dest("2", "rest", "{\"asset\":\"{machine_id}\",\"value\":\"{tagValue}\",\"ts\":\"{timestamp_ms}\"}");
        JsonObject env = Json.GSON.fromJson(new String(d.mappedEnvelope(BATCH), StandardCharsets.UTF_8), JsonObject.class);
        JsonObject rec = env.getAsJsonArray("records").get(0).getAsJsonObject();
        assertEquals("PUMP-P1", Json.str(rec, "asset"));
        assertEquals(42.7, rec.get("value").getAsDouble());
        assertEquals(1791368130250L, rec.get("ts").getAsLong());
    }
}
