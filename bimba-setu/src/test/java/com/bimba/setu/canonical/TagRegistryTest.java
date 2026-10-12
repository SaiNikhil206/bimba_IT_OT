package com.bimba.setu.canonical;

import com.bimba.setu.Json;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-CAN-04: the tag registry applied to readings, and its CSV form. */
class TagRegistryTest {

    private static JsonObject reading(Object value, String unit) {
        JsonObject m = Json.GSON.fromJson("""
                {"schemaVersion":"1.1","messageId":"0b6c2d6e-3a54-4a8a-9c0e-6f1d2f3a4b5c","plantId":"PLANT01","machineId":"PUMP-P1",
                 "timestamp":"2026-10-07T10:15:30.250Z","tagName":"hr2","dataType":"number","quality":"GOOD",
                 "source":{"protocol":"modbus","system":"gw","address":"PLC/HR2"}}""", JsonObject.class);
        m.add("tagValue", Json.GSON.toJsonTree(value));
        if (value instanceof Boolean) {
            m.addProperty("dataType", "boolean");
        } else if (value instanceof String) {
            m.addProperty("dataType", "string");
        }
        m.addProperty("unit", unit);
        return m;
    }

    private static TagRegistry.Entry entry(String tagName, String machineId, String type, String unit) {
        return new TagRegistry.Entry("PUMP-P1", "hr2", tagName, machineId, "PLANT-BLR-01", type, unit, true, "Inlet flow");
    }

    @Test
    void renamesConvertsAndDescribes() {
        JsonObject in = reading(36.0, "m3/h");
        JsonObject out = TagRegistry.applyEntry(in, entry("InletFlow", "PUMP-P1", "Float8", "L/min"));
        assertEquals("InletFlow", Json.str(out, "tagName"));
        assertEquals("PLANT-BLR-01", Json.str(out, "plantId"));
        assertEquals(600.0, out.get("tagValue").getAsDouble(), 1e-9, "36 m3/h = 600 L/min");
        assertEquals("L/min", Json.str(out, "unit"));
        assertEquals("Float8", Json.str(out, "dataTypeName"));
        assertTrue(out.get("writable").getAsBoolean());
        assertEquals("Inlet flow", Json.str(out, "description"));
        assertEquals("PUMP-P1/hr2", Json.str(out.getAsJsonObject("source"), "tag"), "the tag as collected is kept");
        assertEquals(com.bimba.setu.collect.Canonical.VERSION, Json.str(out, "schemaVersion"));
        assertNull(MessageCheck.check(out), () -> String.valueOf(MessageCheck.check(out)));
        assertEquals("hr2", Json.str(in, "tagName"), "the reading itself is not changed");

        JsonObject v2 = CanonicalV2.message(out);
        assertEquals("Float8", Json.str(v2, "data_type"), "2.0 carries the registry's type");
        assertNull(MessageCheck.check(v2));
        assertEquals("Float8", Json.str(CanonicalV2.toInternal(v2), "dataTypeName"));
    }

    @Test
    void dataTypes() {
        JsonObject i = TagRegistry.applyEntry(reading(41.6, ""), entry(null, null, "Int4", null));
        assertEquals(42, i.get("tagValue").getAsLong());
        assertNull(MessageCheck.check(i));
        JsonObject b = TagRegistry.applyEntry(reading(1.0, ""), entry(null, null, "Boolean", null));
        assertEquals(true, b.get("tagValue").getAsBoolean());
        assertEquals("boolean", Json.str(b, "dataType"));
        JsonObject s = TagRegistry.applyEntry(reading(42.5, ""), entry(null, null, "String", null));
        assertEquals("42.5", s.get("tagValue").getAsString());

        JsonObject wrong = TagRegistry.applyEntry(reading("RUNNING", ""), entry(null, null, "Int2", null));
        assertEquals("BAD", Json.str(wrong, "quality"), "a value that does not convert is delivered as BAD");
        assertTrue(Json.str(wrong, "qualityDetail").contains("not a number"));
        assertNull(MessageCheck.check(wrong));
        JsonObject big = TagRegistry.applyEntry(reading(70_000.0, ""), entry(null, null, "Int2", null));
        assertEquals("BAD", Json.str(big, "quality"), "70000 does not fit 16 bits");

        JsonObject unit = TagRegistry.applyEntry(reading(5.0, "bar"), entry(null, null, null, "degC"));
        assertEquals("BAD", Json.str(unit, "quality"), "bar does not convert to degC");
    }

    @Test
    void textValuesAreConvertedToo() {
        JsonObject t = TagRegistry.applyEntry(reading("212", "degF"), entry(null, null, "Float8", "degC"));
        assertEquals(100.0, t.get("tagValue").getAsDouble(), 1e-9, "\"212\" degF = 100 degC, not 212 degC");
        assertEquals("GOOD", Json.str(t, "quality"));
        assertEquals("degC", Json.str(t, "unit"));
        assertNull(MessageCheck.check(t));
        JsonObject asText = TagRegistry.applyEntry(reading("212", "degF"), entry(null, null, "String", "degC"));
        assertEquals("100", asText.get("tagValue").getAsString(), "converted, then delivered as text");
        assertEquals("string", Json.str(asText, "dataType"));
        JsonObject noUnitType = TagRegistry.applyEntry(reading("212", "degF"), entry(null, null, null, "degC"));
        assertEquals(100.0, noUnitType.get("tagValue").getAsDouble(), 1e-9);
        assertNull(MessageCheck.check(noUnitType));

        JsonObject word = TagRegistry.applyEntry(reading("RUNNING", "degF"), entry(null, null, null, "degC"));
        assertEquals("BAD", Json.str(word, "quality"), "text that is no number cannot change its unit");
        assertTrue(Json.str(word, "qualityDetail").contains("not a number"));
        assertNull(MessageCheck.check(word));
        JsonObject flag = TagRegistry.applyEntry(reading(true, "degF"), entry(null, null, null, "degC"));
        assertEquals(true, flag.get("tagValue").getAsBoolean(), "a Boolean is left alone");
    }

    @Test
    void numbersThatDoNotFit() {
        JsonObject huge = TagRegistry.applyEntry(reading(1e30, ""), entry(null, null, "Int8", null));
        assertEquals("BAD", Json.str(huge, "quality"), "1e30 does not fit 64 bits (it was capped at Long.MAX_VALUE)");
        JsonObject min = TagRegistry.applyEntry(reading(-9.223372036854775808E18, ""), entry(null, null, "Int8", null));
        assertEquals(Long.MIN_VALUE, min.get("tagValue").getAsLong());
        JsonObject top = TagRegistry.applyEntry(reading(2_147_483_647.4, ""), entry(null, null, "Int4", null));
        assertEquals(2_147_483_647L, top.get("tagValue").getAsLong());
        assertEquals("BAD", Json.str(TagRegistry.applyEntry(reading(2_147_483_647.5, ""), entry(null, null, "Int4", null)), "quality"));
        assertEquals("BAD", Json.str(TagRegistry.applyEntry(reading("Infinity", ""), entry(null, null, "Float8", null)), "quality"));

        JsonObject f = TagRegistry.applyEntry(reading(0.1, ""), entry(null, null, "Float4", null));
        assertEquals("0.1", f.get("tagValue").toString(), "Float4 without float noise (0.10000000149)");
        assertEquals("BAD", Json.str(TagRegistry.applyEntry(reading(1e40, ""), entry(null, null, "Float4", null)), "quality"));
    }

    @Test
    void twoTagsMustNotShareTheDeliveredName() throws Exception {
        TagRegistry.Known known = new TagRegistry.Known(List.of("PUMP-P1"),
                java.util.Map.of("PUMP-P1/hr1", "", "PUMP-P1/hr2", "", "PUMP-P1/hr3", ""));
        TagRegistry.Entry toHr1 = new TagRegistry.Entry("PUMP-P1", "hr2", "hr1", null, null, null, null, null, null);
        ApiExceptionAssert.badRequest(() -> TagRegistry.targets(List.of(toHr1), java.util.Map.of(), known), "already a collected tag");

        TagRegistry.Entry hr1Away = new TagRegistry.Entry("PUMP-P1", "hr1", "Flow", null, null, null, null, null, null);
        TagRegistry.targets(List.of(toHr1, hr1Away), java.util.Map.of(), known);           // hr1 itself is renamed: no clash

        TagRegistry.Entry alsoFlow = new TagRegistry.Entry("PUMP-P1", "hr3", "Flow", null, null, null, null, null, null);
        ApiExceptionAssert.badRequest(() -> TagRegistry.targets(List.of(hr1Away, alsoFlow), java.util.Map.of(), known), "would both be delivered as PUMP-P1/Flow");

        TagRegistry.targets(List.of(toHr1), java.util.Map.of(toHr1.key(), toHr1), known);  // saved before hr1 was collected: other changes go on
        TagRegistry.Entry unitOnly = new TagRegistry.Entry("PUMP-P1", "hr1", null, null, null, null, "bar", null, null);
        TagRegistry.targets(List.of(unitOnly), java.util.Map.of(), known);                 // keeps its name: its own tag is no clash
    }

    @Test
    void entriesAreCheckedAgainstTheOrganization() throws Exception {
        com.bimba.setu.security.OrgService orgs = org.mockito.Mockito.mock(com.bimba.setu.security.OrgService.class);
        org.mockito.Mockito.when(orgs.owners()).thenReturn(java.util.Map.of("PUMP-P1", 7L, "TANK-T1", 8L));
        TagRegistry r = new TagRegistry(null, orgs);
        TagRegistry.Known known = new TagRegistry.Known(List.of("PUMP-P1"), java.util.Map.of("PUMP-P1/hr2", "bar"));

        r.check(7, new TagRegistry.Entry("PUMP-P1", "hr2", "Pressure", null, "PLANT-1", "Float8", "psi", true, "Outlet"), known);
        ApiExceptionAssert.badRequest(() -> r.check(7, new TagRegistry.Entry("TANK-T1", "lvl", "Level", null, null, null, null, null, null), known),
                "not one of this organization's");
        ApiExceptionAssert.badRequest(() -> r.check(7, new TagRegistry.Entry("PUMP-P1", "hr2", null, "TANK-T1", null, null, null, null, null), known),
                "not one of this organization's machines");
        ApiExceptionAssert.badRequest(() -> r.check(7, new TagRegistry.Entry("PUMP-P1", "hr2", null, null, null, null, "degC", null, null), known),
                "does not convert");
        ApiExceptionAssert.badRequest(() -> r.check(7, new TagRegistry.Entry("PUMP-P1", "hr2", "bad\"name", null, null, null, null, null, null), known),
                "canonical tag name");
    }

    @Test
    void csv() throws Exception {
        TagRegistry r = new TagRegistry(null, null);
        List<TagRegistry.Entry> in = List.of(entry("Inlet, flow", "PUMP-P1", "Double", "m3/h"),
                new TagRegistry.Entry("PUMP-P1", "speed", null, null, null, null, null, false, "=1+1"));
        String text = TagRegistry.csv(in);
        assertTrue(text.startsWith("machine,tag,canonical_tag,machine_id,plant_id,data_type,unit,writable,description\r\n"));
        assertTrue(text.contains("\"Inlet, flow\""));
        assertTrue(text.contains("'=1+1"), "no spreadsheet formulas");
        assertEquals(in, r.parseCsv(text), "export -> import gives the same entries");

        List<TagRegistry.Entry> few = r.parseCsv("tag,machine,data_type\nflow,PUMP-P1,int4\n\n");
        assertEquals("Int4", few.get(0).dataType(), "columns in any order, types in any case");
        ApiExceptionAssert.badRequest(() -> r.parseCsv("machine,tag,data_type\nPUMP-P1,flow,Decimal\n"), "Row 2");
        ApiExceptionAssert.badRequest(() -> r.parseCsv("name,value\nx,y\n"), "first row");
        assertFalse(TagRegistry.rows("a,\"b\nc\",d\n").get(0).size() != 3, "quoted line breaks stay in the cell");
    }

    /** small helper: the call fails with a 400 whose message mentions the text */
    static final class ApiExceptionAssert {
        static void badRequest(org.junit.jupiter.api.function.Executable call, String mentions) {
            var e = assertThrows(com.bimba.setu.security.SecurityService.ApiException.class, call);
            assertTrue(e.getMessage().contains(mentions), e.getMessage());
        }
    }
}
