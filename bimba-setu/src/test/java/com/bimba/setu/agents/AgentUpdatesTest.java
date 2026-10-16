package com.bimba.setu.agents;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentUpdatesTest {

    @Test
    void versionsCompareNumerically() {
        assertTrue(AgentUpdates.compare("1.10.0", "1.9.2") > 0);
        assertTrue(AgentUpdates.compare("1.9.0", "1.10.0") < 0);
        assertTrue(AgentUpdates.compare("1.10", "1.10.0") == 0);
        assertTrue(AgentUpdates.compare("2.0.0", "1.99.99") > 0);
        assertTrue(AgentUpdates.compare("1.10.0", "") > 0);
        assertTrue(AgentUpdates.compare(null, "1.0.0") < 0);
    }

    @Test
    void preReleaseIsOlderThanItsRelease() {
        assertTrue(AgentUpdates.compare("1.10.0~rc1", "1.10.0") < 0);
        assertTrue(AgentUpdates.compare("1.10.0-rc2", "1.10.0-rc1") > 0);
        assertTrue(AgentUpdates.compare("1.10.0~rc1", "1.9.0") > 0);
        assertTrue(AgentUpdates.compare("1.10.1", "1.10.0~rc9") > 0);
    }

    @Test
    void windowMayPassMidnight() {
        JsonObject p = new JsonObject();
        assertTrue(AgentUpdates.inWindow(p, Instant.parse("2026-10-15T12:00:00Z")), "no window = any time");
        JsonObject w = new JsonObject();
        w.addProperty("from", "22:00");
        w.addProperty("to", "04:00");
        w.addProperty("zone", "UTC");
        p.add("window", w);
        assertTrue(AgentUpdates.inWindow(p, Instant.parse("2026-10-15T23:30:00Z")));
        assertTrue(AgentUpdates.inWindow(p, Instant.parse("2026-10-16T03:59:00Z")));
        assertFalse(AgentUpdates.inWindow(p, Instant.parse("2026-10-16T04:00:00Z")));
        assertFalse(AgentUpdates.inWindow(p, Instant.parse("2026-10-15T12:00:00Z")));
    }

    @Test
    void windowUsesItsTimeZone() {
        JsonObject p = new JsonObject();
        JsonObject w = new JsonObject();
        w.addProperty("from", "02:00");
        w.addProperty("to", "05:00");
        w.addProperty("zone", "Asia/Kolkata");          // UTC+05:30
        p.add("window", w);
        assertTrue(AgentUpdates.inWindow(p, Instant.parse("2026-10-14T21:00:00Z")));     // 02:30 in Kolkata
        assertFalse(AgentUpdates.inWindow(p, Instant.parse("2026-10-15T02:00:00Z")));    // 07:30 in Kolkata
    }
}
