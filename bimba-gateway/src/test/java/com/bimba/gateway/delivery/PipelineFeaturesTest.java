package com.bimba.gateway.delivery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineFeaturesTest {
    private static JsonObject reading(String machine, String tag, Object value, String quality, String ts) {
        String v = value == null ? "null" : value instanceof String s ? "\"" + s + "\"" : value.toString();
        String type = value instanceof Number ? "number" : value instanceof Boolean ? "boolean" : value == null ? "null" : "string";
        return JsonParser.parseString(("{\"messageId\":\"x\",\"plantId\":\"P1\",\"machineId\":\"%s\",\"tagName\":\"%s\",\"tagValue\":%s,"
                + "\"dataType\":\"%s\",\"unit\":\"C\",\"quality\":\"%s\",\"timestamp\":\"%s\",\"edge\":{\"edgeId\":\"gw\",\"seq\":7}}")
                .formatted(machine, tag, v, type, quality, ts)).getAsJsonObject();
    }

    // ------------------------------------------------------------------ selection

    @Test
    void selectionByMachineAndTagPatterns() {
        Selection s = new Selection(List.of(), List.of("PUMP-*/Flow*", "Temp?"), List.of("*/FlowTotal"));
        assertTrue(s.matches("PUMP-P1", "Flow"));
        assertTrue(s.matches("pump-p2", "flowrate"));                 // case-insensitive
        assertFalse(s.matches("PUMP-P1", "FlowTotal"));               // exclude wins
        assertTrue(s.matches("TANK-T1", "Temp1"));                    // tag-only pattern, any machine
        assertFalse(s.matches("TANK-T1", "Level"));
        assertFalse(new Selection(List.of("TANK-T1"), List.of(), List.of()).matches("PUMP-P1", "Flow"));
        assertTrue(new Selection(List.of(), List.of(), List.of()).all());
        assertTrue(new Selection(List.of(), List.of("a.b(c)"), List.of()).matches("M", "a.b(c)"));   // regex characters are literal
    }

    // ------------------------------------------------------------------ sampler

    @Test
    void averageWindowUsesGoodNumbersOnly() {
        Sampler s = new Sampler("average", 10, 0);
        s.add(List.of(reading("M", "T", 10.0, "GOOD", "2026-10-07T10:00:01Z"), reading("M", "T", 20.0, "GOOD", "2026-10-07T10:00:02Z"),
                reading("M", "T", 999.0, "BAD", "2026-10-07T10:00:03Z"), reading("M", "Mode", "auto", "GOOD", "2026-10-07T10:00:04Z")));
        assertTrue(s.due(9_999).isEmpty());
        List<JsonObject> out = s.due(10_000);
        assertEquals(2, out.size());
        JsonObject t = out.get(0);
        assertEquals(15.0, t.get("tagValue").getAsDouble());
        assertEquals("avg", t.getAsJsonObject("aggregate").get("fn").getAsString());
        assertEquals(10.0, t.getAsJsonObject("aggregate").get("min").getAsDouble());
        assertEquals(20.0, t.getAsJsonObject("aggregate").get("max").getAsDouble());
        assertEquals(2, t.getAsJsonObject("aggregate").get("count").getAsInt());
        assertFalse(t.getAsJsonObject("edge").has("seq"));
        assertEquals("last", out.get(1).getAsJsonObject("aggregate").get("fn").getAsString());
        assertTrue(s.due(20_000).isEmpty(), "a window without readings sends nothing");
    }

    @Test
    void sampleRepeatsTheCurrentValueOfEveryTag() {
        Sampler s = new Sampler("sample", 5, 0);
        s.add(List.of(reading("M", "A", 1.0, "GOOD", "2026-10-07T10:00:01Z"), reading("M", "B", true, "GOOD", "2026-10-07T10:00:01Z")));
        assertEquals(2, s.due(5_000).size());
        List<JsonObject> again = s.due(10_000);                       // no new readings: same values again
        assertEquals(2, again.size());
        assertEquals("2026-10-07T10:00:01Z", again.get(0).get("sourceTimestamp").getAsString());
        assertEquals("1970-01-01T00:00:10Z", again.get(0).get("timestamp").getAsString());
    }

    // ------------------------------------------------------------------ template

    @Test
    void templateKeepsTypesAndInterpolatesText() {
        Template t = Template.compile("{\"asset\":\"{machineId}\",\"value\":\"{tagValue}\",\"ts\":\"{timestampMs}\","
                + "\"label\":\"{machineId}.{tagName} [{unit}]\",\"{tagName}\":\"{quality}\",\"meta\":{\"edge\":\"{edgeId}\",\"seq\":\"{edge.seq}\"}}");
        JsonObject env = JsonParser.parseString("{\"batchId\":\"b\",\"edgeId\":\"gw\"}").getAsJsonObject();
        JsonObject r = t.render(reading("PUMP-P1", "Flow", 42.5, "GOOD", "2026-10-07T10:00:00.250Z"), env);
        assertEquals("PUMP-P1", r.get("asset").getAsString());
        assertTrue(r.get("value").getAsJsonPrimitive().isNumber());
        assertEquals(42.5, r.get("value").getAsDouble());
        assertEquals(1791367200250L, r.get("ts").getAsLong());
        assertEquals("PUMP-P1.Flow [C]", r.get("label").getAsString());
        assertEquals("GOOD", r.get("Flow").getAsString());
        assertEquals("gw", r.getAsJsonObject("meta").get("edge").getAsString());
        assertEquals(7, r.getAsJsonObject("meta").get("seq").getAsInt());
    }

    @Test
    void templateRefusesUnknownFieldsAndNonObjects() {
        assertThrows(IllegalArgumentException.class, () -> Template.compile("{\"v\":\"{tagvalue}\"}"));
        assertThrows(IllegalArgumentException.class, () -> Template.compile("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> Template.compile("{oops"));
        assertEquals(null, Template.compile("  "));
    }

    @Test
    void mqttTopicPerReading() {
        String topic = Template.text("bimba/P1/{machineId}/{tagName}", reading("LINE/1", "Speed+", 1.0, "GOOD", "2026-10-07T10:00:00Z"),
                null, v -> v.replace('/', '_').replace('+', '_').replace('#', '_'));
        assertEquals("bimba/P1/LINE_1/Speed_", topic);
    }

    @Test
    void jsonArrayFormatAppliesTheLayout() {
        String batch = "{\"batchId\":\"b\",\"edgeId\":\"gw\",\"messages\":[" + reading("M", "T", 1.0, "GOOD", "2026-10-07T10:00:00Z") + "]}";
        Template t = Template.compile("{\"v\":\"{tagValue}\"}");
        String body = new String(Formats.render("json-array", batch.getBytes(), "", t::render).bytes());
        assertEquals("[{\"v\":1.0}]", body);
    }
}
