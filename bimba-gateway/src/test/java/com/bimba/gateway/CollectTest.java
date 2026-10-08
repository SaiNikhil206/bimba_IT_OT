package com.bimba.gateway;

import com.bimba.gateway.collect.Expression;
import com.bimba.gateway.config.TagModel;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expression tags (LINE-SIM) and the tag tree that Settings edits. */
class CollectTest {

    @Test
    void expressions() {
        assertEquals(7.0, Expression.parse("1 + 2 * 3").evaluate());
        assertEquals(true, Expression.parse("2 < 3 && 'a' = 'a'").evaluate());
        assertEquals("IDLE", Expression.parse("if(1 > 2, 'RUNNING', 'IDLE')").evaluate());
        double speed = (Double) Expression.parse("240 + 15 * sin(toMillis(now(1000)) / 60000.0)").evaluate();
        assertTrue(speed >= 225 && speed <= 255, "LINE-SIM speed stays in its band: " + speed);
        Object running = Expression.parse("getSecond(now(1000)) < 50").evaluate();
        assertTrue(running instanceof Boolean);
        assertThrows(IllegalArgumentException.class, () -> Expression.parse("nosuch(1)"));
        assertThrows(IllegalArgumentException.class, () -> Expression.parse("(1 + 2"));
    }

    @Test
    void tagTree() {
        String tree = """
                {"name":"BIMBA","tagType":"Folder","tags":[
                  {"name":"PUMP-P1","tagType":"Folder","tags":[
                    {"name":"inlet_flow","tagType":"AtomicTag","valueSource":"opc","opcServer":"BIMBA Modbus",
                     "opcItemPath":"ns=1;s=[PLC]hr2","engUnit":"m3/h","scaleMode":"Linear","rawLow":0,"rawHigh":10,"scaledLow":0,"scaledHigh":1},
                    {"name":"running","tagType":"AtomicTag","valueSource":"opc","opcServer":"Ignition OPC UA Server","opcItemPath":"ns=1;s=[PLC]C1"}]},
                  {"name":"SUBSTN-M1","tagType":"Folder","tags":[
                    {"name":"bus voltage","tagType":"AtomicTag","valueSource":"opc","opcServer":"METER","opcItemPath":"ns=2;s=Meter.Voltage"}]},
                  {"name":"LINE-SIM","tagType":"Folder","tags":[
                    {"name":"speed","tagType":"AtomicTag","valueSource":"expr","expression":"240"}]},
                  {"name":"loose","tagType":"AtomicTag","valueSource":"opc","opcServer":"METER","opcItemPath":"ns=2;s=X"}]}
                """;
        List<String> problems = new ArrayList<>();
        List<TagModel.Tag> tags = TagModel.parse(Json.GSON.fromJson(tree, JsonObject.class), problems);
        assertEquals(4, tags.size());
        assertEquals(1, problems.size(), "a tag outside a machine folder is reported, not collected");

        TagModel.Tag flow = tags.get(0);
        assertEquals("PUMP-P1", flow.machine());
        assertEquals(TagModel.Kind.MODBUS, flow.kind());
        assertEquals("PLC", flow.device());
        assertEquals("HR2", flow.address());
        assertEquals(52.3, flow.scale(523), 1e-9);
        assertEquals(TagModel.Kind.MODBUS, tags.get(1).kind(), "Ignition tag exports still work");
        assertEquals(TagModel.Kind.OPCUA, tags.get(2).kind());
        assertEquals("bus_voltage", tags.get(2).name());
        assertEquals(TagModel.Kind.EXPRESSION, tags.get(3).kind());
    }
}
