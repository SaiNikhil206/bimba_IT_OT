package com.bimba.gateway.delivery;

import com.bimba.gateway.config.BimbaConfig;
import com.bimba.gateway.security.OrgService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pipeline only carries machines of its own organization; unassigned machines belong to the default organization. */
class OrgOwnershipTest {

    private static BimbaConfig cfg(Map<String, Long> owners, Long defaultOrg) {
        BimbaConfig c = new BimbaConfig();
        c.machineOwners = owners;
        c.defaultOrgId = defaultOrg;
        return c;
    }

    @Test
    void defaultOrganizationOwningEverythingNeedsNoCheck() {
        assertNull(DeliveryEngine.owner(cfg(Map.of(), 1L), 1));
        assertNull(DeliveryEngine.owner(cfg(Map.of("PUMP-P1", 1L), 1L), 1));
    }

    @Test
    void pipelinesFromBeforeOrganizationsTakeEveryMachine() {
        assertNull(DeliveryEngine.owner(cfg(Map.of("PUMP-P1", 2L), 1L), 0));
    }

    @Test
    void assignedMachinesGoOnlyToTheirOrganization() {
        BimbaConfig c = cfg(Map.of("PUMP-P1", 2L, "TANK-T1", 3L), 1L);
        Predicate<String> def = DeliveryEngine.owner(c, 1);
        Predicate<String> two = DeliveryEngine.owner(c, 2);
        Predicate<String> three = DeliveryEngine.owner(c, 3);
        assertFalse(def.test("PUMP-P1"));
        assertTrue(def.test("LINE-SIM"));            // unassigned -> default organization
        assertTrue(two.test("PUMP-P1"));
        assertFalse(two.test("TANK-T1"));
        assertFalse(two.test("LINE-SIM"));
        assertTrue(three.test("TANK-T1"));
    }

    @Test
    void withoutAnyOrganizationNothingIsOwnedByANewOne() {
        assertFalse(OrgService.owns(5, "PUMP-P1", Map.of(), null));
        assertTrue(OrgService.owns(5, "PUMP-P1", Map.of("PUMP-P1", 5L), null));
    }
}
