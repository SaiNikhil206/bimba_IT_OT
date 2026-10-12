package com.bimba.setu.collect;

import com.bimba.setu.Json;
import com.bimba.setu.collect.Canonical.Quality;
import com.bimba.setu.config.TagModel;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-CAN-02: unit conversion, engineering range -> UNCERTAIN, quality mapping, source time. */
class EngineeringTest {

    private static TagModel.Tag tag(String unit, String sourceUnit, Double low, Double high) {
        return new TagModel.Tag("M1", "t", "M1/t", TagModel.Kind.MODBUS, "PLC", "HR1", null, unit, 0, 1, 0, 1, false,
                sourceUnit, low, high);
    }

    @Test
    void units() {
        assertEquals(100, Units.convert(212, "degF", "degC"), 1e-9);
        assertEquals(-40, Units.convert(-40, "°C", "°F"), 1e-9);
        assertEquals(0, Units.convert(273.15, "K", "degC"), 1e-9);
        assertEquals(1.0, Units.convert(14.503773773, "psi", "bar"), 1e-6);
        assertEquals(1000.0 / 60, Units.convert(1, "m3/h", "L/min"), 1e-9);
        assertEquals(3.6, Units.convert(1, "kWh", "MJ"), 1e-9);
        assertEquals(0.5, Units.convert(5000, "ppm", "%"), 1e-9);
        assertEquals(2.5, Units.convert(2500, "W", "kw"), 1e-9, "case is ignored when unambiguous");
        assertFalse(Units.known("mw"), "mW / MW: case decides, so mw is not guessed");
        assertFalse(Units.convertible("degC", "bar"));
        assertTrue(Units.problem("degC", "bar").contains("does not convert"));
        assertTrue(Units.problem("furlong", "m").contains("unknown unit"));
        assertThrows(IllegalArgumentException.class, () -> Units.convert(1, "degC", "bar"));
    }

    @Test
    void conversionAndRange() {
        TagModel.Tag t = tag("degC", "degF", 0.0, 120.0);
        Driver.Reading r = Collector.engineering(t, Driver.Reading.ok(212.0));
        assertEquals(Quality.GOOD, r.quality());
        assertEquals(100.0, (Double) r.value(), 1e-12);

        Driver.Reading hot = Collector.engineering(t, Driver.Reading.ok(300.0));      // 148.9 degC
        assertEquals(Quality.UNCERTAIN, hot.quality(), "outside the engineering range");
        assertEquals(148.888888889, (Double) hot.value(), 1e-9, "the value is still delivered, rounded to 12 digits");
        assertTrue(hot.detail().contains("0..120"), hot.detail());

        TagModel.Tag open = tag("bar", null, null, 10.0);
        assertEquals(Quality.GOOD, Collector.engineering(open, Driver.Reading.ok(-5.0)).quality(), "no low bound");
        assertEquals(Quality.UNCERTAIN, Collector.engineering(open, Driver.Reading.ok(10.5)).quality());
    }

    @Test
    void quality() {
        TagModel.Tag t = tag("bar", null, null, null);
        Instant at = Instant.parse("2026-10-09T08:00:00Z");
        Driver.Reading device = Collector.engineering(t, Driver.Reading.uncertain(4.2, "OPC UA status Uncertain_LastUsableValue", at));
        assertEquals(Quality.UNCERTAIN, device.quality(), "the device's own doubt is kept");
        assertEquals(at, device.sourceTime());

        Driver.Reading nan = Collector.engineering(t, Driver.Reading.ok(Double.NaN));
        assertEquals(Quality.BAD, nan.quality(), "NaN is not a GOOD value");
        assertNull(nan.value());
        assertEquals(Quality.BAD, Collector.engineering(t, Driver.Reading.ok(null)).quality(), "GOOD without a value is BAD");
        assertEquals(Quality.BAD, Collector.engineering(t, Driver.Reading.bad("timeout")).quality());

        TagModel.Tag flag = new TagModel.Tag("M1", "on", "M1/on", TagModel.Kind.MODBUS, "PLC", "HR2", "Boolean", null,
                0, 1, 0, 1, false, null, null, null);
        assertEquals(true, Collector.engineering(flag, Driver.Reading.ok(1.0)).value());
        assertEquals("RUN", Collector.engineering(t, Driver.Reading.ok("RUN")).value());
    }

    @Test
    void opcUaStatusInWords() {
        String u = OpcUaDriver.explain(new org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode(
                org.eclipse.milo.opcua.stack.core.StatusCodes.Uncertain_SensorNotAccurate));
        assertTrue(u.startsWith("Uncertain_SensorNotAccurate: "), u);
        assertTrue(u.toLowerCase().contains("sensor"), u);
        assertTrue(u.endsWith("(OPC UA 0x40930000)"), u);
        String b = OpcUaDriver.explain(new org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode(
                org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_NodeIdUnknown));
        assertTrue(b.startsWith("Bad_NodeIdUnknown: "), b);
        String odd = OpcUaDriver.explain(new org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode(0x80FF0000L));
        assertTrue(odd.contains("0x80FF0000") && odd.contains("bad"), odd);
    }

    @Test
    void tagTreeEngineeringSettings() {
        String tree = """
                {"name":"BIMBA","tags":[{"name":"OVEN-1","tags":[
                  {"name":"temp","valueSource":"opc","opcServer":"BIMBA Modbus","opcItemPath":"ns=1;s=[PLC]HR1",
                   "engUnit":"degC","sourceUnit":"degF","engLow":0,"engHigh":250},
                  {"name":"wrong","valueSource":"opc","opcServer":"BIMBA Modbus","opcItemPath":"ns=1;s=[PLC]HR2",
                   "engUnit":"bar","sourceUnit":"degF"},
                  {"name":"upside_down","valueSource":"opc","opcServer":"BIMBA Modbus","opcItemPath":"ns=1;s=[PLC]HR3",
                   "engLow":10,"engHigh":5}]}]}
                """;
        List<String> problems = new ArrayList<>();
        List<TagModel.Tag> tags = TagModel.parse(Json.GSON.fromJson(tree, JsonObject.class), problems);
        assertEquals(1, tags.size(), "tags whose engineering settings cannot work are not collected: " + problems);
        assertEquals(2, problems.size());
        TagModel.Tag temp = tags.get(0);
        assertTrue(temp.converts());
        assertEquals(250.0, temp.engHigh());
    }
}
