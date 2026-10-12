package com.bimba.setu.agents;

import com.bimba.setu.Json;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The site agent tag editor's unit conversion and engineering range (what the console saves). */
class AgentTagSettingsTest {

    private static JsonObject body(String tag) {
        return Json.GSON.fromJson("{\"tags\":[" + tag + "]}", JsonObject.class);
    }

    @Test
    void savesUnitAndRange() throws Exception {
        JsonArray out = AgentSources.tags(body("""
                {"name":"temp","address":"HR1","unit":"degC","sourceUnit":"degF","engLow":"0","engHigh":"120"}"""), "modbus");
        JsonObject t = out.get(0).getAsJsonObject();
        assertEquals("degF", Json.str(t, "sourceUnit"));
        assertEquals(0.0, t.get("engLow").getAsDouble());
        assertEquals(120.0, t.get("engHigh").getAsDouble(), "kept as numbers for the edge collector");

        JsonObject plain = AgentSources.tags(body("""
                {"name":"flow","address":"HR2","unit":"m3/h","sourceUnit":"","engLow":"","engHigh":""}"""), "modbus").get(0).getAsJsonObject();
        assertFalse(plain.has("sourceUnit") || plain.has("engLow") || plain.has("engHigh"), "empty fields are not stored");
    }

    @Test
    void refusesSettingsThatCannotWork() {
        ApiException wrong = assertThrows(ApiException.class, () -> AgentSources.tags(body("""
                {"name":"temp","address":"HR1","unit":"bar","sourceUnit":"degF"}"""), "modbus"));
        assertTrue(wrong.getMessage().contains("does not convert"), wrong.getMessage());
        assertThrows(ApiException.class, () -> AgentSources.tags(body("""
                {"name":"temp","address":"HR1","sourceUnit":"degF"}"""), "modbus"), "a device unit needs a unit to convert to");
        assertThrows(ApiException.class, () -> AgentSources.tags(body("""
                {"name":"temp","address":"HR1","engLow":"10","engHigh":"5"}"""), "modbus"));
        assertThrows(ApiException.class, () -> AgentSources.tags(body("""
                {"name":"temp","address":"HR1","engHigh":"lots"}"""), "modbus"));
    }
}
