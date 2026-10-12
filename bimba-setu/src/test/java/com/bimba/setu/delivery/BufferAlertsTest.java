package com.bimba.setu.delivery;

import com.bimba.setu.SetuService;
import com.bimba.setu.Json;
import com.bimba.setu.canonical.Quarantine;
import com.bimba.setu.canonical.QuarantineSettings;
import com.bimba.setu.config.DestinationStore;
import com.bimba.setu.config.PipelineService;
import com.bimba.setu.config.SecretBox;
import com.bimba.setu.security.Mailer;
import com.bimba.setu.security.OrgService;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-RES-05: a pipeline's buffer alerts go to its own recipients and (unless it turns that off) the organization's. */
class BufferAlertsTest {
    private static final DeliveryEngine.BufferAlert HIGH = new DeliveryEngine.BufferAlert(5, 7, "Plant data", "main", true, 72,
            370L * 1_048_576, 512L * 1_048_576);

    private final Mailer mailer = mock(Mailer.class);
    private final DestinationStore store = mock(DestinationStore.class);
    private final SecretBox box = mock(SecretBox.class);
    private final OrgService orgs = mock(OrgService.class);

    private QuarantineSettings settings(File dir, String orgWebhook) throws Exception {
        when(orgs.get(7L)).thenReturn(new OrgService.Org(7, "u7", "northwind", "Northwind Water"));
        when(box.open(anyString())).thenAnswer(a -> a.getArgument(0));
        when(box.seal(anyString())).thenAnswer(a -> "sealed:" + a.getArgument(0));
        QuarantineSettings s = new QuarantineSettings(null, new Quarantine(dir, 200, 10_000), orgs, mailer, box, "https://gw.example");
        java.lang.reflect.Method use = QuarantineSettings.class.getDeclaredMethod("use", Map.class);
        use.setAccessible(true);
        use.invoke(s, Map.of(7L, new QuarantineSettings.Settings(30, List.of("ops@example.com", "plant@example.com"), orgWebhook, "", 1, 15)));
        return s;
    }

    private static JsonObject pipeline(String bufferAlerts) {
        JsonObject p = Json.GSON.fromJson("{\"id\":5,\"uid\":\"pl-1\",\"name\":\"Plant data\""
                + (bufferAlerts == null ? "" : ",\"bufferAlerts\":" + bufferAlerts) + "}", JsonObject.class);
        return p;
    }

    private BufferAlerts alerts(QuarantineSettings s) {
        return new BufferAlerts(mock(SetuService.class), s, orgs, store);
    }

    @Test
    void ownRecipientsAndTheOrganizationsEachAddressOnce(@TempDir File dir) throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.add(ex.getRequestURI().getPath() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            QuarantineSettings s = settings(dir, base + "/org");
            when(store.pipelines(7L)).thenReturn(List.of(pipeline("{\"emails\":[\"integration@example.com\",\"ops@example.com\"],"
                    + "\"webhookUrl\":\"" + base + "/pipe\",\"webhookSecret\":\"\",\"organization\":true}")));
            alerts(s).send(HIGH);

            verify(mailer, times(1)).send(eq("integration@example.com"), anyString(), anyString(), anyString());
            verify(mailer, times(1)).send(eq("ops@example.com"), anyString(), anyString(), anyString());     // on both lists: once
            verify(mailer, times(1)).send(eq("plant@example.com"), anyString(), anyString(), anyString());
            assertEquals(2, calls.size(), "the pipeline's webhook and the organization's");
            String pipe = calls.stream().filter(c -> c.startsWith("/pipe ")).findFirst().orElseThrow().substring(6);
            JsonObject body = Json.GSON.fromJson(pipe, JsonObject.class);
            assertEquals("bimba.buffer.high", Json.str(body, "event"));
            assertEquals(72, body.get("percent").getAsInt());
            assertEquals("https://gw.example/northwind/pipelines/pl-1", Json.str(body, "url"), "links to the pipeline");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void onlyItsOwnWhenTheOrganizationIsTurnedOff(@TempDir File dir) throws Exception {
        QuarantineSettings s = settings(dir, "");
        when(store.pipelines(7L)).thenReturn(List.of(pipeline("{\"emails\":[\"integration@example.com\"],\"webhookUrl\":\"\","
                + "\"webhookSecret\":\"\",\"organization\":false}")));
        alerts(s).send(HIGH);
        verify(mailer, times(1)).send(eq("integration@example.com"), anyString(), anyString(), anyString());
        verify(mailer, never()).send(eq("ops@example.com"), anyString(), anyString(), anyString());
    }

    @Test
    void withoutOwnRecipientsOrTheDatabaseTheOrganizationIsTold(@TempDir File dir) throws Exception {
        QuarantineSettings s = settings(dir, "");
        when(store.pipelines(7L)).thenReturn(List.of(pipeline(null)));          // saved before pipelines had their own
        alerts(s).send(HIGH);
        verify(mailer, times(1)).send(eq("ops@example.com"), anyString(), anyString(), anyString());

        when(store.pipelines(7L)).thenThrow(new RuntimeException("database away"));
        alerts(s).send(HIGH);
        verify(mailer, times(2)).send(eq("ops@example.com"), anyString(), anyString(), anyString());
    }

    @Test
    void testAlertReportsEachRecipientGroup(@TempDir File dir) throws Exception {
        QuarantineSettings s = settings(dir, "");
        when(mailer.configured()).thenReturn(true);
        when(store.pipelines(7L)).thenReturn(List.of(pipeline("{\"emails\":[\"integration@example.com\"],\"webhookUrl\":\"\","
                + "\"webhookSecret\":\"\",\"organization\":true}")));
        Map<String, Object> r = alerts(s).test(7L, 5L);
        assertEquals("sent to 1 recipient(s)", r.get("email"));
        assertEquals("sent to 2 recipient(s)", r.get("organizationEmail"));
        assertThrows(ApiException.class, () -> alerts(s).test(7L, 99L));
    }

    @Test
    void pipelineFormChecksSealsAndMasks(@TempDir File dir) throws Exception {
        QuarantineSettings s = settings(dir, "");
        SetuService setu = mock(SetuService.class);
        when(setu.status()).thenReturn(Map.of());
        when(store.pipelines(7L)).thenReturn(List.of());
        when(store.list(7L)).thenReturn(List.of(Json.GSON.fromJson("{\"id\":3,\"name\":\"Boomi\"}", JsonObject.class)));
        PipelineService ps = new PipelineService(setu, store, orgs, dir, s, null);

        JsonObject in = Json.GSON.fromJson("{\"name\":\"Plant data\",\"active\":true,\"primary\":3,"
                + "\"bufferAlerts\":{\"emails\":\"Integration@Example.com, oncall@example.com\",\"webhookUrl\":\"https://hooks.example/b\","
                + "\"webhookSecret\":\"k3y\",\"organization\":false}}", JsonObject.class);
        ps.save(7L, null, in, "sam");
        ArgumentCaptor<JsonObject> saved = ArgumentCaptor.forClass(JsonObject.class);
        verify(store).insertPipeline(eq(7L), saved.capture(), eq("sam"));
        JsonObject ba = saved.getValue().getAsJsonObject("bufferAlerts");
        assertEquals("[\"integration@example.com\",\"oncall@example.com\"]", ba.get("emails").toString());
        assertEquals("sealed:k3y", Json.str(ba, "webhookSecret"));
        assertFalse(ba.get("organization").getAsBoolean());

        JsonObject stored = saved.getValue().deepCopy();
        stored.addProperty("id", 5);
        when(store.pipelines(7L)).thenReturn(List.of(stored));
        @SuppressWarnings("unchecked")
        Map<String, Object> shown = (Map<String, Object>) ((List<Map<String, Object>>) ps.list(7L).get("pipelines")).get(0).get("bufferAlerts");
        assertEquals("********", shown.get("webhookSecret"), "the console never sees the secret");

        in.getAsJsonObject("bufferAlerts").addProperty("webhookSecret", "********");      // unchanged on edit
        ps.save(7L, 5L, in, "sam");
        ArgumentCaptor<JsonObject> updated = ArgumentCaptor.forClass(JsonObject.class);
        verify(store).updatePipeline(eq(7L), anyLong(), updated.capture(), eq("sam"));
        assertEquals("sealed:k3y", Json.str(updated.getValue().getAsJsonObject("bufferAlerts"), "webhookSecret"));

        in.getAsJsonObject("bufferAlerts").addProperty("emails", "not-an-address");
        ApiException e = assertThrows(ApiException.class, () -> ps.save(7L, 5L, in, "sam"));
        assertTrue(e.getMessage().contains("not an e-mail address"));
    }
}
