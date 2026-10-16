package com.bimba.setu.security;

import com.bimba.setu.Json;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scoped role assignments, the permission move to version 2, and the Auditor duty separation rule. */
class GrantsTest {
    private static final String AGENT_A = "11111111-1111-1111-1111-111111111111";
    private static final String AGENT_B = "22222222-2222-2222-2222-222222222222";

    private static Scope scope(String json) {
        return Scope.parse(Json.GSON.fromJson(json, JsonObject.class));
    }

    private static Grants.Assignment role(String name, Scope s, String... perms) {
        return new Grants.Assignment(name.hashCode(), name, false, Set.of(perms), false, s);
    }

    @Test
    void emptyScopeMeansTheWholeOrganization() {
        assertNull(scope("{\"sites\": [], \"agents\": [\" \"]}"));
        assertNull(Scope.parse(null));
    }

    @Test
    void scopeMatchesBySiteAgentOrMachineIgnoringCase() {
        Scope s = scope("{\"sites\": [\"Plant A\"], \"agents\": [\"" + AGENT_B.toUpperCase() + "\"], \"machines\": [\"Press-1\"]}");
        assertTrue(s.covers(AGENT_A, "plant a", null));
        assertTrue(s.covers(AGENT_B, "Plant B", null));
        assertTrue(s.covers(AGENT_A, "Plant C", "press-1"));
        assertFalse(s.covers(AGENT_A, "Plant C", "Press-2"));
        assertFalse(s.covers(AGENT_A, "Plant C", null));
    }

    @Test
    void scopedAssignmentsLimitOnlyScopedPermissions() {
        Grants.Access a = Grants.combine(List.of(role("Operator at A", scope("{\"sites\": [\"Plant A\"]}"), "gateway.view", "writes.send")));
        assertTrue(a.can("gateway.view"));
        assertNull(a.scope("gateway.view"));                 // not a site thing: holds everywhere
        assertTrue(a.can("writes.send"));
        assertEquals(List.of("Plant A"), a.scope("writes.send").sites());
    }

    @Test
    void scopesOfSeveralAssignmentsAddUpAndAWideOneWins() {
        Grants.Access two = Grants.combine(List.of(
                role("At A", scope("{\"sites\": [\"Plant A\"]}"), "writes.send"),
                role("At B", scope("{\"agents\": [\"" + AGENT_B + "\"]}"), "writes.send")));
        assertTrue(two.scope("writes.send").covers(AGENT_A, "Plant A", null));
        assertTrue(two.scope("writes.send").covers(AGENT_B, "Plant Z", null));
        Grants.Access wide = Grants.combine(List.of(
                role("At A", scope("{\"sites\": [\"Plant A\"]}"), "writes.approve"),
                role("Everywhere", null, "writes.approve")));
        assertNull(wide.scope("writes.approve"));
    }

    @Test
    void auditorsHoldNoOperationalPermission() {
        Grants.Assignment auditor = new Grants.Assignment(4, "Auditor", true, Set.of("gateway.view", "audit.view"), false, null);
        assertTrue(Grants.auditorConflicts(List.of(auditor, role("Viewer", null, "gateway.view", "machines.view"))).isEmpty());
        assertEquals(Set.of("writes.send"), Grants.auditorConflicts(List.of(auditor, role("Operator", scope("{\"sites\": [\"A\"]}"), "writes.send"))));
        // a custom role called Auditor is not the built-in one
        Grants.Assignment custom = new Grants.Assignment(9, "Auditor", false, Set.of("audit.view"), false, null);
        assertTrue(Grants.auditorConflicts(List.of(custom, role("Operator", null, "writes.send"))).isEmpty());
    }

    @Test
    void version2SplitsTheOldPermissions() {
        assertEquals(List.of("gateway.view", "messages.view", "pipelines.manage", "destinations.manage", "quarantine.manage", "registry.manage",
                "agents.view", "agents.enroll", "agents.manage", "sources.manage", "machines.view"),
                RoleCatalog.upgraded(List.of("gateway.view", "agents.view", "agents.manage", "delivery.manage")));
        assertEquals(List.of("gateway.view", "machines.view", "users.view", "users.manage", "audit.view"),
                RoleCatalog.upgraded(List.of("gateway.view", "users.manage", "audit.view")));
        assertEquals(List.of(), RoleCatalog.upgraded(List.of()));
    }

    @Test
    void everyPersonaRoleUsesKnownPermissionsAndAuditorStaysIndependent() {
        for (RoleCatalog.Persona p : RoleCatalog.PERSONAS) {
            p.permissions().forEach(k -> assertTrue(Permission.of(k) != null, p.role() + ": " + k));
        }
        RoleCatalog.Persona aud = RoleCatalog.PERSONAS.stream().filter(p -> p.code().equals("Aud")).findFirst().orElseThrow();
        assertTrue(aud.permissions().stream().noneMatch(Grants.OPERATIONAL::contains));
    }
}
