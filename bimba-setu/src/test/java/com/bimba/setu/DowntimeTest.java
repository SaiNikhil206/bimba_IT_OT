package com.bimba.setu;

import com.bimba.setu.canonical.MessageCheck;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-RES-03: the time Setu itself did not run is marked on its machines at the next start */
class DowntimeTest {

    @Test
    void stoppedAndCrashedGatewaysAreMarked(@TempDir File dir) {
        Downtime d = new Downtime(dir);
        assertNull(d.read(), "first start: nothing to mark");
        d.beat(true);
        Downtime.Last last = d.read();
        assertTrue(last.stopped());
        Instant now = last.at().plusSeconds(600);
        List<JsonObject> m = Downtime.markers("P1", "gw", List.of("PUMP-1", "TANK-2"), last, now);
        assertEquals(4, m.size(), "lost + restored for each machine");
        JsonObject lost = m.get(0);
        JsonObject back = m.get(1);
        assertEquals("lost", lost.get("tagValue").getAsString());
        assertEquals(last.at().toString(), lost.get("timestamp").getAsString(), "lost at the moment it stopped");
        assertEquals("Setu was stopped", lost.getAsJsonObject("gap").get("reason").getAsString());
        assertEquals(600.0, back.getAsJsonObject("gap").get("seconds").getAsDouble());
        assertEquals("gateway gw", back.getAsJsonObject("gap").get("device").getAsString());
        m.forEach(x -> assertNull(MessageCheck.check(x), x.toString()));

        d.beat(false);                                                     // alive, then no clean stop
        Downtime.Last crash = d.read();
        assertTrue(!crash.stopped());
        assertTrue(Downtime.markers("P1", "gw", List.of("M"), crash, crash.at().plusSeconds(60)).get(0)
                .getAsJsonObject("gap").get("reason").getAsString().contains("unexpectedly"));
        assertTrue(Downtime.markers("P1", "gw", List.of("M"), crash, crash.at().plusSeconds(10)).isEmpty(), "a quick restart is not a gap");
    }
}
