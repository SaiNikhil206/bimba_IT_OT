package com.bimba.setu.agents;

import com.bimba.setu.Json;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Machine commands: which tags can be marked writable, the values they take, the kill switch and agent versions. */
class MachineCommandsTest {

    private static JsonArray tags(String kind, String tag) throws ApiException {
        return AgentSources.tags(Json.GSON.fromJson("{\"settings\":{\"payload\":\"value\"},\"tags\":[" + tag + "]}", JsonObject.class), kind);
    }

    @Test
    void writableTagsNeedLimitsAndAWritableAddress() throws Exception {
        JsonObject t = tags("modbus", """
                {"name":"setpoint","address":"HR5","unit":"bar","writable":true,"writeMin":"0","writeMax":"10","writeApproval":true}""")
                .get(0).getAsJsonObject();
        assertTrue(t.get("writable").getAsBoolean());
        assertEquals(10.0, t.get("writeMax").getAsDouble());
        assertTrue(t.get("writeApproval").getAsBoolean());

        JsonObject coil = tags("modbus", """
                {"name":"pump","address":"C3","writable":true}""").get(0).getAsJsonObject();
        assertEquals("boolean", AgentSources.writeKind("modbus", coil), "a coil is on / off: no limits needed");

        JsonObject readOnly = tags("modbus", """
                {"name":"level","address":"HR1","writeMin":"0","writeMax":"10"}""").get(0).getAsJsonObject();
        assertFalse(readOnly.has("writable") || readOnly.has("writeMin"), "limits of a tag that is not writable are not kept");

        ApiException noLimits = assertThrows(ApiException.class, () -> tags("opcua", """
                {"name":"sp","address":"ns=2;s=SP","writable":true}"""));
        assertTrue(noLimits.getMessage().contains("lowest and highest"), noLimits.getMessage());
        assertThrows(ApiException.class, () -> tags("modbus", """
                {"name":"level","address":"IR1","writable":true,"writeMin":"0","writeMax":"1"}"""), "input registers are read only");
        assertThrows(ApiException.class, () -> tags("dnp3", """
                {"name":"ai","address":"AI0","writable":true,"writeMin":"0","writeMax":"1"}"""), "DNP3 stays read only");
        assertThrows(ApiException.class, () -> tags("opcua", """
                {"name":"sp","address":"ns=2;s=SP","writable":true,"writeMin":"5","writeMax":"1"}"""));

        JsonObject mqtt = tags("mqtt", """
                {"name":"sp","address":"plant/sp","writable":true,"writeMin":"0","writeMax":"9","writeAddress":"plant/sp/set"}""")
                .get(0).getAsJsonObject();
        assertEquals("plant/sp/set", Json.str(mqtt, "writeAddress"));
        assertThrows(ApiException.class, () -> tags("mqtt", """
                {"name":"sp","address":"plant/sp","writable":true,"writeMin":"0","writeMax":"9","writeAddress":"plant/+/set"}"""));
    }

    @Test
    void valuesAreCheckedAgainstTheTag() throws Exception {
        JsonObject t = Json.GSON.fromJson("{\"name\":\"setpoint\",\"unit\":\"bar\",\"writeMin\":0,\"writeMax\":10}", JsonObject.class);
        assertEquals(new JsonPrimitive(7.5), MachineCommands.value("number", t, new JsonPrimitive("7.5")));
        ApiException out = assertThrows(ApiException.class, () -> MachineCommands.value("number", t, new JsonPrimitive(11)));
        assertEquals("setpoint may be set from 0 to 10 bar.", out.getMessage());
        assertThrows(ApiException.class, () -> MachineCommands.value("number", t, new JsonPrimitive("lots")));
        assertThrows(ApiException.class, () -> MachineCommands.value("number", t, new JsonPrimitive("NaN")));
        assertThrows(ApiException.class, () -> MachineCommands.value("number", t, null));
        assertEquals(new JsonPrimitive(true), MachineCommands.value("boolean", t, new JsonPrimitive("on")));
        assertEquals(new JsonPrimitive(false), MachineCommands.value("boolean", t, new JsonPrimitive(false)));
        assertThrows(ApiException.class, () -> MachineCommands.value("boolean", t, new JsonPrimitive("maybe")));
        assertEquals(new JsonPrimitive("AUTO"), MachineCommands.value("text", t, new JsonPrimitive(" AUTO ")));
    }

    @Test
    void killSwitchScopes() {
        MachineCommands.Agent pump = new MachineCommands.Agent("a1", "Pump house PC", "North plant", true, "1.9.0", "allowed");
        MachineCommands.Agent other = new MachineCommands.Agent("a2", "Office PC", "South", true, "1.9.0", "allowed");
        JsonObject none = new JsonObject();
        assertNull(MachineCommands.stoppedWhy(none, pump));

        JsonObject site = Json.GSON.fromJson("{\"sites\":{\"north plant\":{\"byName\":\"Sam\",\"at\":\"t\",\"reason\":\"maintenance\"}}}", JsonObject.class);
        String why = MachineCommands.stoppedWhy(site, pump);
        assertTrue(why.contains("at site north plant") && why.contains("maintenance"), why);
        assertNull(MachineCommands.stoppedWhy(site, other));

        JsonObject agent = Json.GSON.fromJson("{\"agents\":{\"a2\":{\"byName\":\"Sam\",\"at\":\"t\"}}}", JsonObject.class);
        assertNull(MachineCommands.stoppedWhy(agent, pump));
        assertTrue(MachineCommands.stoppedWhy(agent, other).contains("for site agent Office PC"));

        JsonObject org = Json.GSON.fromJson("{\"org\":{\"byName\":\"Sam\",\"at\":\"t\",\"reason\":\"\"}}", JsonObject.class);
        assertTrue(MachineCommands.stoppedWhy(org, other).contains("for the whole organization"));
    }

    @Test
    void agentVersions() {
        assertTrue(MachineCommands.atLeast("1.9.0", "1.9"));
        assertTrue(MachineCommands.atLeast("1.10.2", "1.9"));
        assertTrue(MachineCommands.atLeast("2.0", "1.9"));
        assertFalse(MachineCommands.atLeast("1.8.0", "1.9"));
        assertFalse(MachineCommands.atLeast(null, "1.9"));
        assertTrue(MachineCommands.atLeast("dev", "1.9"), "a developer build");
    }
}
