package com.bimba.setu.agents;

import com.bimba.setu.Json;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Four-eyes on safety configuration (BRD 4.5): what needs a second person, what is held back, and how approval applies it. */
class SafetyConfigTest {

    /** tags as the console saves them (AgentSources.tags: validated, cleaned) */
    private static JsonArray tags(String... tags) throws ApiException {
        return AgentSources.tags(Json.GSON.fromJson("{\"tags\":[" + String.join(",", tags) + "]}", JsonObject.class), "modbus");
    }

    private static JsonObject settings(boolean writes) {
        JsonObject s = new JsonObject();
        s.addProperty("host", "10.0.0.5");
        s.addProperty("writes", writes);
        return s;
    }

    private static final String SPEED = """
            {"name":"speed","address":"HR1","unit":"rpm","writable":true,"writeMin":"0","writeMax":"1500"}""";
    private static final String SPEED_WIDE = """
            {"name":"speed","address":"HR1","unit":"rpm","writable":true,"writeMin":"0","writeMax":"3000"}""";
    private static final String SPEED_READ = """
            {"name":"speed","address":"HR1","unit":"rpm"}""";
    private static final String TEMP = """
            {"name":"temp","address":"IR4","unit":"degC"}""";

    @Test
    void aNewSourceWithCommandsStartsWithoutThemUntilApproved() throws Exception {
        JsonObject st = settings(true);
        JsonArray t = tags(SPEED, TEMP);
        List<JsonObject> changes = SafetyConfig.diff(null, new JsonArray(), st, t, "Press-1");
        assertEquals(2, changes.size(), "the writes switch and the writable tag");
        assertEquals("writes", Json.str(changes.get(0), "kind"));
        assertEquals("Press-1", Json.str(changes.get(1), "machine"));
        assertEquals("speed", Json.str(changes.get(1), "tag"));

        SafetyConfig.hold(null, new JsonArray(), st, t, changes);
        assertFalse(SafetyConfig.writesOn(st), "saved with commands off");
        JsonObject speed = t.get(0).getAsJsonObject();
        assertFalse(speed.has("writable") || speed.has("writeMin") || speed.has("writeMax"), "saved not writable");
        assertEquals("HR1", Json.str(speed, "address"), "the rest of the tag is saved");

        SafetyConfig.apply(st, t, changes);
        assertTrue(SafetyConfig.writesOn(st));
        assertTrue(SafetyConfig.writable(speed));
        assertEquals(1500.0, speed.get("writeMax").getAsDouble(), "approved: as proposed");
    }

    @Test
    void wideningALimitIsHeldBackUntilApproved() throws Exception {
        JsonArray stored = tags(SPEED);
        JsonArray t = tags(SPEED_WIDE);
        List<JsonObject> changes = SafetyConfig.diff(settings(true), stored, settings(true), t, "Press-1");
        assertEquals(1, changes.size());
        assertTrue(SafetyConfig.describe(changes).contains("writeMax 1500 → 3000"), SafetyConfig.describe(changes));

        SafetyConfig.hold(settings(true), stored, settings(true), t, changes);
        assertEquals(1500.0, t.get(0).getAsJsonObject().get("writeMax").getAsDouble(), "the stored limit stays");

        assertNull(SafetyConfig.conflict(settings(true), t, changes));
        SafetyConfig.apply(settings(true), t, changes);
        assertEquals(3000.0, t.get(0).getAsJsonObject().get("writeMax").getAsDouble());
    }

    @Test
    void everyKindOfSafetyChangeCounts() throws Exception {
        JsonArray stored = tags(SPEED);
        // switching commands off, the writable flag off, approval on, a new address while writable: all need a second person
        assertEquals(1, SafetyConfig.diff(settings(true), stored, settings(false), tags(SPEED), "P").size());
        assertEquals(1, SafetyConfig.diff(settings(true), stored, settings(true), tags(SPEED_READ), "P").size());
        assertEquals(1, SafetyConfig.diff(settings(true), stored, settings(true), tags(SPEED.replace("\"writable\":true", "\"writable\":true,\"writeApproval\":true")), "P").size());
        assertEquals(1, SafetyConfig.diff(settings(true), stored, settings(true), tags(SPEED.replace("HR1", "HR9")), "P").size());
        assertEquals(1, SafetyConfig.diff(settings(true), stored, settings(true), tags(SPEED.replace("rpm", "rps")), "P").size(),
                "the unit changes what a command writes");
        JsonObject text = settings(true);
        text.addProperty("writes", "true");
        assertTrue(SafetyConfig.writesOn(text), "a checkbox sent as text");
    }

    @Test
    void whatDoesNotDecideCommandsIsSavedAtOnce() throws Exception {
        JsonArray stored = tags(SPEED, TEMP);
        // a read-only tag's address and unit, a removed writable tag, a renamed source: no request
        assertTrue(SafetyConfig.diff(settings(true), stored, settings(true), tags(SPEED, TEMP.replace("IR4", "IR5").replace("degC", "K")), "P").isEmpty());
        assertTrue(SafetyConfig.diff(settings(true), stored, settings(true), tags(TEMP), "P").isEmpty());
        assertTrue(SafetyConfig.diff(settings(true), stored, settings(true), tags(SPEED, TEMP), "Press-1 renamed").isEmpty());
        assertTrue(SafetyConfig.diff(settings(false), tags(SPEED_READ), settings(false), tags(SPEED_READ.replace("HR1", "HR2")), "P").isEmpty());
    }

    @Test
    void aTagThatWasNotWritableKeepsItsOtherEditsWhileWaiting() throws Exception {
        JsonArray stored = tags(SPEED_READ);
        JsonArray t = tags(SPEED.replace("HR1", "HR7"));
        List<JsonObject> changes = SafetyConfig.diff(settings(true), stored, settings(true), t, "P");
        SafetyConfig.hold(settings(true), stored, settings(true), t, changes);
        JsonObject speed = t.get(0).getAsJsonObject();
        assertFalse(SafetyConfig.writable(speed));
        assertEquals("HR7", Json.str(speed, "address"), "free while it is not writable");
    }

    @Test
    void aWritableTagKeepsItsAddressUntilApproved() throws Exception {
        JsonArray stored = tags(SPEED);
        JsonArray t = tags(SPEED.replace("HR1", "HR7"));
        List<JsonObject> changes = SafetyConfig.diff(settings(true), stored, settings(true), t, "P");
        SafetyConfig.hold(settings(true), stored, settings(true), t, changes);
        assertEquals("HR1", Json.str(t.get(0).getAsJsonObject(), "address"), "commands keep going where they were approved to");
        SafetyConfig.apply(settings(true), t, changes);
        assertEquals("HR7", Json.str(t.get(0).getAsJsonObject(), "address"));
    }

    @Test
    void approvalChecksTheSourceAsItIsNow() throws Exception {
        JsonArray stored = tags(SPEED);
        List<JsonObject> wider = SafetyConfig.diff(settings(true), stored, settings(true), tags(SPEED_WIDE), "P");
        // meanwhile another approved request made the tag read only
        JsonArray now = tags(SPEED_READ);
        assertNotNull(SafetyConfig.conflict(settings(true), now, wider));
        assertNotNull(SafetyConfig.conflict(settings(true), tags(TEMP), wider), "the tag was removed");
        List<JsonObject> on = SafetyConfig.diff(settings(false), stored, settings(true), tags(SPEED), "P");
        assertNotNull(SafetyConfig.conflict(settings(true), stored, on), "the switch is already on");
        assertNull(SafetyConfig.conflict(settings(false), stored, on));
    }

    @Test
    void theProposerNeverApproves() {
        ApiException own = assertThrows(ApiException.class, () -> SafetyConfig.notOwn("sam", "SAM"));
        assertEquals(403, own.status);
        assertThrows(ApiException.class, () -> SafetyConfig.notOwn("", "alex"), "a request without a proposer is never approved");
        assertDoesNotThrow(() -> SafetyConfig.notOwn("sam", "alex"));
    }
}
