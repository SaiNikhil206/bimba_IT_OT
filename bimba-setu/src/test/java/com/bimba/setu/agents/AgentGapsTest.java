package com.bimba.setu.agents;

import com.bimba.setu.Json;
import com.bimba.setu.canonical.CanonicalV2;
import com.bimba.setu.canonical.MessageCheck;
import com.bimba.setu.collect.Canonical;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-RES-03 for site agents that go silent: the markers are valid messages in both formats */
class AgentGapsTest {

    @Test
    void silentAgentMarkers() {
        Instant seen = Instant.parse("2026-10-09T08:00:00Z");
        AgentGaps.Agent a = new AgentGaps.Agent(UUID.randomUUID(), "line-2-pc", seen, 60, seen.plusSeconds(200));
        assertTrue(a.silent(), "200 s > 3 heartbeats of 60 s");
        assertFalse(new AgentGaps.Agent(a.id(), a.name(), seen, 60, seen.plusSeconds(170)).silent());

        JsonObject start = AgentGaps.marker("P1", "gw", a, "PUMP-1", "start", seen, null);
        assertNull(MessageCheck.check(start));
        assertEquals(Canonical.GAP_TAG, Json.str(start, "tagName"));
        assertEquals("lost", Json.str(start, "tagValue"));
        assertEquals("2026-10-09T08:00:00Z", Json.str(start.getAsJsonObject("gap"), "from"));
        assertEquals(a.id().toString(), Json.str(start.getAsJsonObject("source"), "agentId"));

        JsonObject end = AgentGaps.marker("P1", "gw", a, "PUMP-1", "end", seen, seen.plusSeconds(300));
        assertNull(MessageCheck.check(end));
        assertEquals(300.0, end.getAsJsonObject("gap").get("seconds").getAsDouble());
        assertEquals("GOOD", Json.str(end, "quality"));

        JsonObject v2 = CanonicalV2.message(end);
        assertNull(MessageCheck.check(v2), "valid as 2.0 too");
        assertEquals("2026-10-09T08:00:00Z", Json.str(v2.getAsJsonObject("gap"), "from"));
    }
}
