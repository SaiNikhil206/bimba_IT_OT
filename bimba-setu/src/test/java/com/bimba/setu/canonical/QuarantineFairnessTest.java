package com.bimba.setu.canonical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A full quarantine removes the oldest entry of the organization using the most, not the oldest of anyone. */
class QuarantineFairnessTest {

    private static List<Quarantine.Item> one(String machine) {
        return List.of(new Quarantine.Item("{\"bad\":true}", "test", machine, "t"));
    }

    @Test
    void aNoisyAgentOnlyPushesOutItsOwnOrganizationsEntries(@TempDir File dir) throws Exception {
        Quarantine q = new Quarantine(dir, 200, 10);
        Quarantine.Origin quiet = Quarantine.Origin.agent(UUID.randomUUID(), 1);
        Quarantine.Origin noisy = Quarantine.Origin.agent(UUID.randomUUID(), 2);
        q.put(quiet, MessageCheck.Reason.SCHEMA_INVALID, one("QUIET-1"));
        q.put(Quarantine.Origin.gateway(), MessageCheck.Reason.SCHEMA_INVALID, one("PUMP-P1"));
        for (int i = 0; i < 50; i++) {
            q.put(noisy, MessageCheck.Reason.SCHEMA_INVALID, one("NOISY-1"));
        }
        assertEquals(10, q.all().size());
        assertEquals(1, q.count(m -> m.origin().orgId() != null && m.origin().orgId() == 1)[0], "the quiet organization keeps its entry");
        assertEquals(1, q.count(m -> m.origin().orgId() == null)[0], "Setu's own entry is kept");
        assertEquals(8, q.count(m -> m.origin().orgId() != null && m.origin().orgId() == 2)[0]);
    }

    @Test
    void usageIsRebuiltFromDisk(@TempDir File dir) throws Exception {
        Quarantine first = new Quarantine(dir, 200, 3);
        Quarantine.Origin a = Quarantine.Origin.agent(UUID.randomUUID(), 1);
        Quarantine.Origin b = Quarantine.Origin.agent(UUID.randomUUID(), 2);
        first.put(a, MessageCheck.Reason.SCHEMA_INVALID, one("A"));
        first.put(b, MessageCheck.Reason.SCHEMA_INVALID, one("B"));
        first.put(b, MessageCheck.Reason.SCHEMA_INVALID, one("B"));
        Quarantine again = new Quarantine(dir, 200, 3);         // after a restart
        again.put(b, MessageCheck.Reason.SCHEMA_INVALID, one("B"));
        assertEquals(1, again.count(m -> m.origin().orgId() == 1)[0]);
        assertEquals(2, again.count(m -> m.origin().orgId() == 2)[0]);
    }
}
