package com.bimba.setu.canonical;

import com.bimba.setu.Json;
import com.bimba.setu.config.SecretBox;
import com.bimba.setu.security.Mailer;
import com.bimba.setu.security.OrgService;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Quarantine alerts (e-mail, signed webhook, threshold, quiet period) and retention per organization. */
class QuarantineSettingsTest {

    private static QuarantineSettings settings(Quarantine q, Mailer mailer) throws Exception {
        OrgService orgs = mock(OrgService.class);
        when(orgs.owners()).thenReturn(Map.of("PUMP-P1", 7L));
        when(orgs.defaultOrgId()).thenReturn(1L);
        when(orgs.get(7L)).thenReturn(new OrgService.Org(7, "u7", "northwind", "Northwind Water"));
        SecretBox box = mock(SecretBox.class);
        when(box.open(anyString())).thenAnswer(a -> a.getArgument(0));
        return new QuarantineSettings(null, q, orgs, mailer, box, "https://gw.example");
    }

    private static void bad(Ingest ingest, int n) {
        for (int i = 0; i < n; i++) {
            JsonObject m = new JsonObject();
            m.addProperty("schemaVersion", "1.2");
            m.addProperty("machineId", "PUMP-P1");
            ingest.fromCollector(m, x -> { });
        }
    }

    @Test
    void alertsAfterTheThresholdThenRespectTheQuietPeriod(@TempDir File dir) throws Exception {
        List<String[]> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            calls.add(new String[]{new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    ex.getRequestHeaders().getFirst("X-BIMBA-Signature")});
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        try {
            Quarantine q = new Quarantine(dir, 200, 10_000);
            Ingest ingest = new Ingest(q);
            Mailer mailer = mock(Mailer.class);
            QuarantineSettings s = settings(q, mailer);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            s.use(Map.of(7L, new QuarantineSettings.Settings(30, List.of("ops@example.com"), url, "s3cret", 3, 15)));

            long t = 1_000_000;
            s.check(t);                                   // first round: starts counting from now
            bad(ingest, 2);
            s.check(t += 60_000);
            assertEquals(0, calls.size(), "2 readings: below the threshold of 3");
            bad(ingest, 1);
            s.check(t += 60_000);
            assertEquals(1, calls.size());
            JsonObject body = Json.GSON.fromJson(calls.get(0)[0], JsonObject.class);
            assertEquals(3, body.get("records").getAsInt());
            assertEquals("northwind", body.getAsJsonObject("organization").get("slug").getAsString());
            assertEquals(3, body.getAsJsonObject("reasons").get("SCHEMA_INVALID").getAsInt());
            assertEquals("https://gw.example/northwind/quarantine", Json.str(body, "url"));
            assertEquals("sha256=" + QuarantineSettings.sign("s3cret", calls.get(0)[0]), calls.get(0)[1], "signed with the secret");
            verify(mailer, times(1)).send(eq("ops@example.com"), anyString(), anyString(), anyString());

            bad(ingest, 5);
            s.check(t += 60_000);
            assertEquals(1, calls.size(), "within the 15 quiet minutes: collected for later");
            s.check(t + 15 * 60_000);
            assertEquals(2, calls.size());
            assertEquals(5, Json.GSON.fromJson(calls.get(1)[0], JsonObject.class).get("records").getAsInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void otherOrganizationsAreNotTold(@TempDir File dir) throws Exception {
        Quarantine q = new Quarantine(dir, 200, 10_000);
        Mailer mailer = mock(Mailer.class);
        QuarantineSettings s = settings(q, mailer);
        s.use(Map.of(7L, new QuarantineSettings.Settings(30, List.of("ops@example.com"), "", "", 1, 1)));
        s.check(0);
        q.put(Quarantine.Origin.agent(UUID.randomUUID(), 8), MessageCheck.Reason.UNKNOWN_MACHINE,
                List.of(new Quarantine.Item("{}", "not mine", "OTHER", "t")));
        s.check(60_000);
        verify(mailer, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void retentionPerOrganization(@TempDir File dir) throws Exception {
        Quarantine q = new Quarantine(dir, 200, 10_000);
        Ingest ingest = new Ingest(q);
        bad(ingest, 1);                                   // PUMP-P1 -> organization 7
        q.put(Quarantine.Origin.agent(UUID.randomUUID(), 8), MessageCheck.Reason.MALFORMED_JSON, List.of(new Quarantine.Item("x", "y", null, null)));
        // make both look 3 days old
        for (File f : dir.toPath().resolve("quarantine").toFile().listFiles((d, n) -> n.endsWith(".meta"))) {
            JsonObject m = Json.GSON.fromJson(Files.readString(f.toPath()), JsonObject.class);
            m.addProperty("at", Instant.now().minusSeconds(3 * 86_400).toString());
            Files.writeString(f.toPath(), Json.GSON.toJson(m));
        }
        Quarantine old = new Quarantine(dir, 200, 10_000);
        QuarantineSettings s = settings(old, mock(Mailer.class));
        s.use(Map.of(7L, new QuarantineSettings.Settings(2, List.of(), "", "", 1, 15)));     // org 8 keeps the default 30 days
        s.retention();
        assertEquals(1, old.all().size());
        assertTrue(old.all().get(0).origin().orgId() == 8L, "only organization 7's entry was past its 2 days");
    }
}
