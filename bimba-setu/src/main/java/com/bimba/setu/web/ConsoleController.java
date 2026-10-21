package com.bimba.setu.web;

import com.bimba.setu.BimbaSetuApplication;
import com.bimba.setu.SetuService;
import com.bimba.setu.Json;
import com.bimba.setu.agents.AgentRegistry;
import com.bimba.setu.agents.AgentSources;
import com.bimba.setu.agents.EnrollTokens;
import com.bimba.setu.agents.InstallerFile;
import com.bimba.setu.canonical.Ingest;
import com.bimba.setu.canonical.Quarantine;
import com.bimba.setu.canonical.QuarantineSettings;
import com.bimba.setu.canonical.TagRegistry;
import com.bimba.setu.config.DestinationService;
import com.bimba.setu.config.DestinationStore;
import com.bimba.setu.config.PipelineService;
import com.bimba.setu.security.OrgService;
import com.bimba.setu.security.Permission;
import com.bimba.setu.security.SecurityService;
import com.bimba.setu.security.SecurityService.ApiException;
import com.bimba.setu.security.SecurityService.Principal;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON API of the Setu console (/api/gw/...), used by the React app in ../../ui.
 * Spring Security (SecurityConfig) requires a signed-in user for everything except session and login;
 * every other call names the permission it needs. Everything is scoped to the organization the session works in
 * (Principal.org()): one organization never sees or changes another's destinations, pipelines, agents, users, roles
 * or audit entries. /orgs and /machines are for platform admins only (authority platform.admin).
 */
@RestController
@RequestMapping("/api/gw")
public class ConsoleController {
    private final SetuService setu;
    private final AgentRegistry agents;
    private final SecurityService security;
    private final OrgService orgs;
    private final DestinationService destinations;
    private final DestinationStore store;
    private final PipelineService pipelines;
    private final com.bimba.setu.security.PasswordResetService resets;
    private final EnrollTokens tokens;
    private final InstallerFile installer;
    private final AgentSources sources;
    private final Ingest ingest;
    private final TagRegistry registry;
    private final QuarantineSettings quarantineSettings;
    private final com.bimba.setu.delivery.BufferAlerts bufferAlerts;
    private final ContactInfo contact;
    private final com.bimba.setu.agents.AgentUpdates updates;

    public ConsoleController(SetuService setu, AgentRegistry agents, SecurityService security, OrgService orgs,
                             DestinationService destinations, DestinationStore store, PipelineService pipelines,
                             com.bimba.setu.security.PasswordResetService resets, EnrollTokens tokens, InstallerFile installer,
                             AgentSources sources, Ingest ingest, TagRegistry registry, QuarantineSettings quarantineSettings,
                             com.bimba.setu.delivery.BufferAlerts bufferAlerts, com.bimba.setu.agents.AgentUpdates updates,
                             ContactInfo contact) {
        this.contact = contact;
        this.bufferAlerts = bufferAlerts;
        this.updates = updates;
        this.sources = sources;
        this.ingest = ingest;
        this.registry = registry;
        this.quarantineSettings = quarantineSettings;
        this.tokens = tokens;
        this.installer = installer;
        this.setu = setu;
        this.agents = agents;
        this.security = security;
        this.orgs = orgs;
        this.destinations = destinations;
        this.store = store;
        this.pipelines = pipelines;
        this.resets = resets;
    }

    // ------------------------------------------------------------------ session

    @GetMapping("/session")
    Map<String, Object> session(@AuthenticationPrincipal Principal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", p == null ? null : p.json());
        m.put("organizations", p == null ? List.of() : security.organizations(p));
        m.put("policy", security.policy());
        m.put("version", BimbaSetuApplication.VERSION);
        m.put("contact", contact.json());     // also signed out: the promotion page shows how to reach the vendor
        return m;
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) String body, HttpServletRequest req) throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        SecurityService.Login l = security.login(str(b, "email"), str(b, "password"), req.getRemoteAddr(), req.getHeader("User-Agent"));
        // session cookie (no Max-Age): the server enforces the idle and absolute timeouts
        ResponseCookie cookie = ResponseCookie.from(SessionAuthFilter.COOKIE, l.token())
                .httpOnly(true).secure(req.isSecure()).sameSite("Strict").path("/").build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("user", l.principal().json(), "organizations", security.organizations(l.principal())));
    }

    @PostMapping("/logout")
    ResponseEntity<Map<String, Object>> logout(@AuthenticationPrincipal Principal p, HttpServletRequest req) {
        security.logout(p, req.getRemoteAddr());
        ResponseCookie gone = ResponseCookie.from(SessionAuthFilter.COOKIE, "").httpOnly(true).sameSite("Strict").path("/").maxAge(0).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, gone.toString()).body(Map.of("ok", true));
    }

    // ------------------------------------------------------------------ forgot password (no session)

    /** body {email}: e-mails a one-time reset link when the address has a console account; the answer is always the same */
    @PostMapping("/password/forgot")
    Map<String, Object> forgotPassword(@RequestBody(required = false) String body, HttpServletRequest req) throws ApiException {
        resets.request(str(ApiErrors.parse(body), "email"), req.getRemoteAddr());
        return Map.of("ok", true, "minutes", com.bimba.setu.security.PasswordResetService.MINUTES);
    }

    @GetMapping("/password/reset")
    Map<String, Object> checkResetLink(@RequestParam(required = false) String token, HttpServletRequest req) throws ApiException {
        return resets.check(token, req.getRemoteAddr());
    }

    /** body {token, password} */
    @PostMapping("/password/reset")
    Map<String, Object> resetPassword(@RequestBody(required = false) String body, HttpServletRequest req) throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        resets.reset(str(b, "token"), str(b, "password"), req.getRemoteAddr());
        return Map.of("ok", true);
    }

    /** body {orgId}: the session works in that organization from now on (its permissions there apply). */
    @PostMapping("/session/organization")
    Map<String, Object> switchOrganization(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        if (!b.has("orgId") || !b.get("orgId").isJsonPrimitive()) {
            throw new ApiException(400, "orgId", "Choose an organization.");
        }
        security.switchOrganization(p, b.get("orgId").getAsLong(), req.getRemoteAddr());
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ Setu + site agents

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('gateway.view')")
    Map<String, Object> overview(@AuthenticationPrincipal Principal p) throws ApiException {
        long org = p.org();
        Map<String, Object> all = setu.status();
        Map<String, Object> m;
        if (p.superuser()) {
            m = new LinkedHashMap<>(all);     // platform admins: all of Setu
        } else {
            List<String> machines = List.of();
            if (all.get("collector") instanceof Map<?, ?> c && c.get("machines") instanceof List<?> l) {
                machines = orgs.machinesOf(org, l.stream().map(String::valueOf).toList());
            }
            Set<String> pipelineNames = new HashSet<>();
            store.pipelines(org).forEach(x -> pipelineNames.add(Json.str(x, "name")));
            Set<String> destinationNames = new HashSet<>();
            store.list(org).forEach(x -> destinationNames.add(Json.str(x, "name")));
            m = OrgView.status(all, machines, pipelineNames, destinationNames, org);
        }
        m.put("tlsFingerprint", Tls.fingerprint);
        m.put("scope", p.superuser() ? "gateway" : "organization");
        long[] q = ingest.quarantine().count(quarantineOf(p));
        m.put("quarantine", Map.of("entries", q[0], "records", q[1]));
        return m;
    }

    @GetMapping("/agents")
    @PreAuthorize("hasAuthority('agents.view')")
    Map<String, Object> agents(@AuthenticationPrincipal Principal p) throws ApiException {
        // with what each agent collects: its data sources and the machines they report as
        Map<String, Map<String, Object>> collects = sources.summary(p.org());
        Map<String, Map<String, Object>> updating = updates.summary(p.org());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> a : agents.list(p.org())) {
            // a role limited to some sites or site agents sees only those
            if (!p.allows(Permission.AGENTS_VIEW, (String) a.get("id"), (String) a.get("site"), null)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>(a);
            Map<String, Object> c = collects.get((String) a.get("id"));
            m.put("sources", c == null ? 0 : c.get("sources"));
            m.put("machines", c == null ? List.of() : c.get("machines"));
            Map<String, Object> u = updating.get((String) a.get("id"));
            m.put("update", u != null ? u : updates.unreported((String) a.get("agentVersion")));     // software version, automatic updates
            list.add(m);
        }
        return Map.of("agents", list);
    }

    @PostMapping("/agents/{id}/revoke")
    @PreAuthorize("hasAuthority('agents.manage')")
    Map<String, Object> revoke(@AuthenticationPrincipal Principal p, @PathVariable String id, HttpServletRequest req)
            throws AgentRegistry.ApiError, ApiException {
        security.require(p, Permission.AGENTS_MANAGE, id);
        String name = agents.revoke(id, p.org(), p.username(), req.getRemoteAddr());
        security.audit(p, "agent.revoke", name, true, req.getRemoteAddr(), Map.of("agentId", id));
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ site agent installer + enrollment tokens

    /** the installers offered for download, plus what the console needs to suggest Setu address */
    @GetMapping("/agents/installer")
    @PreAuthorize("hasAuthority('agents.view')")
    Map<String, Object> installerInfo() {
        Map<String, Object> m = new LinkedHashMap<>(installer.info());
        String port = System.getenv("BIMBA_HTTPS_PORT");
        m.put("httpsPort", Tls.fingerprint == null ? null : port == null || port.isBlank() ? 8443 : Integer.parseInt(port.trim()));
        m.put("tlsFingerprint", Tls.fingerprint);
        m.put("tls", Tls.describe());
        m.put("registrationEnabled", agents.settings().enabled);
        m.put("requireHttps", agents.settings().requireHttps);
        m.put("agentCertificates", agents.settings().issueCertificates && com.bimba.setu.agents.AgentCa.ready());
        m.put("certificateDays", agents.settings().certificateDays);
        return m;
    }

    /**
     * kind = exe (setup wizard) | msi (Intune / SCCM / Group Policy) | deb | tar (Linux / Raspberry Pi). An unsigned exe gets Setu address (and the
     * pin of a self-signed HTTPS certificate) built in; signed files are served unchanged. No file contains a secret:
     * the person installing still needs an enrollment token or an organization e-mail address and password.
     */
    @GetMapping("/agents/installer/download")
    @PreAuthorize("hasAuthority('agents.view')")
    ResponseEntity<byte[]> installerDownload(@AuthenticationPrincipal Principal p, @RequestParam(defaultValue = "exe") String kind,
                                             @RequestParam(required = false) String server, HttpServletRequest req) throws ApiException {
        if (!java.util.Set.of("exe", "msi", "deb", "tar").contains(kind)) {
            throw new ApiException(400, "kind", "kind must be exe, msi, deb or tar");
        }
        String url = null;
        if (server != null && !server.isBlank()) {
            url = InstallerFile.normalizeServer(server);
            if (url == null) {
                throw new ApiException(400, "server", "Enter the Setu address the SCADA computers use, for example https://setu.example.com:8443");
            }
        }
        byte[] file;
        try {
            // a trusted certificate needs no pin
            file = installer.download(kind, url, Tls.selfSigned ? InstallerFile.plainFingerprint(Tls.fingerprint) : null);
        } catch (java.io.IOException e) {
            throw new ApiException(404, "installer", "That installer is not available on this Setu server.");
        }
        String name = InstallerFile.fileName(kind);
        security.audit(p, "agent.installer.download", name, true, req.getRemoteAddr(),
                url == null ? Map.of("size", file.length) : Map.of("size", file.length, "server", url));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file);
    }

    @GetMapping("/agents/tokens")
    @PreAuthorize("hasAuthority('agents.enroll')")
    Map<String, Object> enrollTokens(@AuthenticationPrincipal Principal p) throws ApiException {
        return Map.of("tokens", tokens.list(p.org()));
    }

    /** body {label, maxUses?, days?}; the answer holds the token itself, which is never shown again */
    @PostMapping("/agents/tokens")
    @PreAuthorize("hasAuthority('agents.enroll')")
    Map<String, Object> createEnrollToken(@AuthenticationPrincipal Principal p, @RequestBody(required = false) String body,
                                          HttpServletRequest req) throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        String label = Json.str(b, "label");
        if (label == null || label.isBlank()) {
            throw new ApiException(400, "label", "Name the token, for example the site or the roll-out it is for.");
        }
        Integer maxUses = positive(b, "maxUses", 100_000, "Maximum uses");
        Integer days = positive(b, "days", 3650, "Valid for (days)");
        Map<String, Object> t = tokens.create(p.org(), p.userId(), p.username(), label, maxUses, days, req.getRemoteAddr());
        security.audit(p, "agent.token.create", label.trim(), true, req.getRemoteAddr(),
                Map.of("tokenId", t.get("id"), "prefix", t.get("prefix")));
        return t;
    }

    /**
     * Add a computer's Copy token: a token for one computer that expires after a few minutes, so a copied token cannot be
     * reused later or by someone else. Roll-outs to many computers use a named token (Administration → Enrollment tokens).
     */
    @PostMapping("/agents/tokens/one-time")
    @PreAuthorize("hasAuthority('agents.enroll')")
    Map<String, Object> oneTimeEnrollToken(@AuthenticationPrincipal Principal p, HttpServletRequest req) throws ApiException {
        Map<String, Object> t = tokens.createOneTime(p.org(), p.userId(), p.username(), req.getRemoteAddr());
        security.audit(p, "agent.token.create", EnrollTokens.ONE_TIME_LABEL, true, req.getRemoteAddr(),
                Map.of("tokenId", t.get("id"), "prefix", t.get("prefix"), "minutes", EnrollTokens.ONE_TIME_MINUTES));
        return t;
    }

    @PostMapping("/agents/tokens/{id}/revoke")
    @PreAuthorize("hasAuthority('agents.enroll')")
    Map<String, Object> revokeEnrollToken(@AuthenticationPrincipal Principal p, @PathVariable long id, HttpServletRequest req)
            throws ApiException {
        String name = tokens.revoke(p.org(), id, p.username(), req.getRemoteAddr());
        if (name == null) {
            throw new ApiException(404, "not_found", "That token does not exist or is already revoked.");
        }
        security.audit(p, "agent.token.revoke", name, true, req.getRemoteAddr(), Map.of("tokenId", id));
        return Map.of("ok", true);
    }

    /** an optional whole number 1..max (empty / missing = no limit) */
    private static Integer positive(JsonObject b, String key, int max, String label) throws ApiException {
        JsonElement e = b.get(key);
        if (e == null || e.isJsonNull() || (e.isJsonPrimitive() && e.getAsString().isBlank())) {
            return null;
        }
        try {
            int v = Integer.parseInt(e.getAsString().trim());
            if (v >= 1 && v <= max) {
                return v;
            }
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException ignored) {
            // falls through to the message
        }
        throw new ApiException(400, key, label + " must be a whole number from 1 to " + max + ", or empty for no limit.");
    }

    // ------------------------------------------------------------------ delivery: destinations (connections)

    @GetMapping("/destinations")
    @PreAuthorize("hasAuthority('gateway.view')")
    Map<String, Object> destinations(@AuthenticationPrincipal Principal p) throws ApiException {
        return destinations.list(p.org());
    }

    /** Connection test of the form as it is (saved or not); "original" = id of the saved destination whose secrets to use. */
    @PostMapping("/destinations/test")
    @PreAuthorize("hasAuthority('destinations.manage')")
    Map<String, Object> testDestination(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        Long original = b.has("original") && !b.get("original").isJsonNull() ? b.get("original").getAsLong() : null;
        JsonObject form = b.has("destination") && b.get("destination").isJsonObject() ? b.getAsJsonObject("destination") : new JsonObject();
        Map<String, Object> r = destinations.test(p.org(), original, form);
        security.audit(p, "destination.test", String.valueOf(str(form, "name")), Boolean.TRUE.equals(r.get("ok")),
                req.getRemoteAddr(), describe(form));
        return r;
    }

    /** sends ONE test reading through the destination as the form shows it (the receiver stores it) */
    @PostMapping("/destinations/send-test")
    @PreAuthorize("hasAuthority('destinations.manage')")
    Map<String, Object> sendTestReading(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        Long original = b.has("original") && !b.get("original").isJsonNull() ? b.get("original").getAsLong() : null;
        JsonObject form = b.has("destination") && b.get("destination").isJsonObject() ? b.getAsJsonObject("destination") : new JsonObject();
        Map<String, Object> r = destinations.sendTest(p.org(), original, form);
        security.audit(p, "destination.send-test", String.valueOf(str(form, "name")), Boolean.TRUE.equals(r.get("ok")),
                req.getRemoteAddr(), describe(form));
        return r;
    }

    /** a template applied to a sample reading (nothing is saved or sent) */
    @PostMapping("/destinations/preview")
    @PreAuthorize("hasAnyAuthority('destinations.manage', 'pipelines.manage')")
    Map<String, Object> previewTemplate(@RequestBody String body) throws ApiException {
        return destinations.preview(ApiErrors.parse(body));
    }

    @PostMapping("/destinations")
    @PreAuthorize("hasAuthority('destinations.manage')")
    ResponseEntity<Map<String, Object>> createDestination(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        Map<String, Object> r = destinations.save(p.org(), null, b, p.username());
        security.audit(p, "destination.create", String.valueOf(r.get("name")), true, req.getRemoteAddr(), describe(b));
        return ResponseEntity.status(201).body(r);
    }

    @PutMapping("/destinations/{id}")
    @PreAuthorize("hasAuthority('destinations.manage')")
    Map<String, Object> updateDestination(@AuthenticationPrincipal Principal p, @PathVariable long id, @RequestBody String body,
                                          HttpServletRequest req) throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        Map<String, Object> r = destinations.save(p.org(), id, b, p.username());
        security.audit(p, "destination.update", String.valueOf(r.get("name")), true, req.getRemoteAddr(), describe(b));
        return r;
    }

    @DeleteMapping("/destinations/{id}")
    @PreAuthorize("hasAuthority('destinations.manage')")
    Map<String, Object> deleteDestination(@AuthenticationPrincipal Principal p, @PathVariable long id, HttpServletRequest req)
            throws ApiException {
        String name = destinations.delete(p.org(), id);
        security.audit(p, "destination.delete", name, true, req.getRemoteAddr(), Map.of());
        return Map.of("ok", true);
    }

    /** audit detail of a destination change, without any secret */
    private static Map<String, Object> describe(JsonObject b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", String.valueOf(str(b, "service")));
        m.put("transport", String.valueOf(str(b, "transport")));
        return m;
    }

    // ------------------------------------------------------------------ delivery: pipelines (which data goes where)

    @GetMapping("/pipelines")
    @PreAuthorize("hasAuthority('gateway.view')")
    Map<String, Object> pipelines(@AuthenticationPrincipal Principal p) throws ApiException {
        return pipelines.list(p.org());
    }

    @PostMapping("/pipelines")
    @PreAuthorize("hasAuthority('pipelines.manage')")
    ResponseEntity<Map<String, Object>> createPipeline(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        Map<String, Object> r = pipelines.save(p.org(), null, b, p.username());
        security.audit(p, "pipeline.create", String.valueOf(r.get("name")), true, req.getRemoteAddr(), pipelineDetail(b));
        return ResponseEntity.status(201).body(r);
    }

    @PutMapping("/pipelines/{id}")
    @PreAuthorize("hasAuthority('pipelines.manage')")
    Map<String, Object> updatePipeline(@AuthenticationPrincipal Principal p, @PathVariable long id, @RequestBody String body,
                                       HttpServletRequest req) throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        Map<String, Object> r = pipelines.save(p.org(), id, b, p.username());
        security.audit(p, "pipeline.update", String.valueOf(r.get("name")), true, req.getRemoteAddr(), pipelineDetail(b));
        return r;
    }

    @PostMapping("/pipelines/{id}/active")
    @PreAuthorize("hasAuthority('pipelines.manage')")
    Map<String, Object> activatePipeline(@AuthenticationPrincipal Principal p, @PathVariable long id, @RequestBody String body,
                                         HttpServletRequest req) throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        boolean on = b.has("active") && b.get("active").getAsBoolean();
        String name = pipelines.setActive(p.org(), id, on, p.username());
        security.audit(p, on ? "pipeline.activate" : "pipeline.pause", name, true, req.getRemoteAddr(), Map.of());
        return Map.of("ok", true);
    }

    @DeleteMapping("/pipelines/{id}")
    @PreAuthorize("hasAuthority('pipelines.manage')")
    Map<String, Object> deletePipeline(@AuthenticationPrincipal Principal p, @PathVariable long id, HttpServletRequest req)
            throws ApiException {
        String name = pipelines.delete(p.org(), id);
        security.audit(p, "pipeline.delete", name, true, req.getRemoteAddr(), Map.of());
        return Map.of("ok", true);
    }

    /** sends a test buffer alert to the pipeline's saved recipients (its own, and the organization's when it asks for them) */
    @PostMapping("/pipelines/{id}/buffer-alerts/test")
    @PreAuthorize("hasAuthority('pipelines.manage')")
    Map<String, Object> testBufferAlert(@AuthenticationPrincipal Principal p, @PathVariable long id, HttpServletRequest req)
            throws ApiException {
        ownPipeline(p, id);
        Map<String, Object> out = bufferAlerts.test(p.org(), id);
        security.audit(p, "pipeline.alert.test", "buffer alert of pipeline " + id, true, req.getRemoteAddr(), out);
        return out;
    }

    // ------------------------------------------------------------------ rejected batches of a pipeline

    private com.bimba.setu.delivery.DeliveryEngine engine() throws ApiException {
        com.bimba.setu.delivery.DeliveryEngine e = setu.delivery();
        if (e == null) {
            throw new ApiException(503, "stopped", "Delivery is not running (see Overview).");
        }
        return e;
    }

    /** the pipeline must be one of the session's organization */
    private void ownPipeline(Principal p, long id) throws ApiException {
        if (!pipelines.owns(p.org(), id)) {
            throw new ApiException(404, "not_found", "This pipeline no longer exists. Reload the page.");
        }
    }

    @GetMapping("/pipelines/{id}/rejected")
    @PreAuthorize("hasAuthority('gateway.view')")
    Map<String, Object> rejected(@AuthenticationPrincipal Principal p, @PathVariable long id) throws ApiException {
        ownPipeline(p, id);
        try {
            return Map.of("rejected", engine().rejected(id));
        } catch (IllegalArgumentException e) {
            throw new ApiException(404, "inactive", "Rejected batches can be seen while the pipeline is active.");
        }
    }

    @GetMapping("/pipelines/{id}/rejected/{route}/{file}")
    @PreAuthorize("hasAuthority('messages.view')")
    Map<String, Object> rejectedBatch(@AuthenticationPrincipal Principal p, @PathVariable long id, @PathVariable int route,
                                      @PathVariable String file) throws ApiException {
        ownPipeline(p, id);
        try {
            return Map.of("text", engine().rejectedText(id, route, file));
        } catch (IllegalArgumentException | java.io.IOException e) {
            throw new ApiException(404, "not_found", "That rejected batch is not there any more.");
        }
    }

    /** body {items: [{route, file}], action: resend | discard} */
    @PostMapping("/pipelines/{id}/rejected")
    @PreAuthorize("hasAuthority('quarantine.manage')")
    Map<String, Object> handleRejected(@AuthenticationPrincipal Principal p, @PathVariable long id, @RequestBody String body,
                                       HttpServletRequest req) throws ApiException {
        ownPipeline(p, id);
        JsonObject b = ApiErrors.parse(body);
        String action = String.valueOf(str(b, "action"));
        if (!"resend".equals(action) && !"discard".equals(action)) {
            throw new ApiException(400, "action", "Choose resend or discard.");
        }
        int done = 0;
        List<String> failed = new ArrayList<>();
        com.bimba.setu.delivery.DeliveryEngine e = engine();
        if (b.has("items") && b.get("items").isJsonArray()) {
            for (JsonElement it : b.getAsJsonArray("items")) {
                JsonObject o = it.getAsJsonObject();
                String file = String.valueOf(str(o, "file"));
                try {
                    if ("resend".equals(action)) {
                        e.resend(id, o.get("route").getAsInt(), file);
                    } else {
                        e.discard(id, o.get("route").getAsInt(), file);
                    }
                    done++;
                } catch (Exception ex) {
                    failed.add(file);
                }
            }
        }
        security.audit(p, "pipeline.rejected." + action, String.valueOf(id), failed.isEmpty(), req.getRemoteAddr(),
                Map.of("batches", done, "failed", failed.size()));
        return Map.of("done", done, "failed", failed);
    }

    // ------------------------------------------------------------------ quarantine (records refused before routing)

    /**
     * The organization's quarantine entries: from its site agents, or naming its machines. Platform admins also see the
     * entries that belong to no organization (Setu's own records without a machine).
     */
    private java.util.function.Predicate<Quarantine.Meta> quarantineOf(Principal p) throws ApiException {
        java.util.function.Predicate<Quarantine.Meta> own = Quarantine.ownedBy(p.org(), orgs.owners(), orgs.defaultOrgId());
        boolean admin = p.superuser();
        return q -> q.origin().orgId() == null && q.machines().isEmpty() ? admin : own.test(q);
    }

    @GetMapping("/quarantine")
    @PreAuthorize("hasAuthority('gateway.view')")
    Map<String, Object> quarantine(@AuthenticationPrincipal Principal p) throws ApiException {
        Map<String, String> names = new java.util.HashMap<>();
        agents.list(p.org()).forEach(a -> names.put(String.valueOf(a.get("id")),
                String.valueOf(a.get("name") != null ? a.get("name") : a.get("hostname"))));
        var visible = quarantineOf(p);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Quarantine.Meta q : ingest.quarantine().list(visible, 500)) {
            Map<String, Object> m = q.json();
            m.put("agentName", q.origin().agentId() == null ? null : names.get(q.origin().agentId().toString()));
            list.add(m);
        }
        long[] n = ingest.quarantine().count(visible);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", list);
        out.put("totalEntries", n[0]);
        out.put("totalRecords", n[1]);
        out.put("schemaVersion", com.bimba.setu.collect.Canonical.VERSION);
        out.put("acceptedMajors", com.bimba.setu.canonical.MessageCheck.majors());
        return out;
    }

    @GetMapping("/quarantine/{id}")
    @PreAuthorize("hasAuthority('messages.view')")
    Map<String, Object> quarantined(@AuthenticationPrincipal Principal p, @PathVariable String id) throws ApiException {
        Quarantine.Entry e;
        try {
            e = ingest.quarantine().read(id, quarantineOf(p));
        } catch (java.io.IOException ex) {
            e = null;
        }
        if (e == null) {
            throw new ApiException(404, "not_found", "That quarantine entry is not there any more.");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Quarantine.Item it : e.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("machineId", it.machineId());
            m.put("tagName", it.tagName());
            m.put("detail", it.detail());
            m.put("raw", it.raw());
            items.add(m);
        }
        return Map.of("entry", e.meta().json(), "items", items);
    }

    /** body {ids: [..], action: replay | discard} */
    @PostMapping("/quarantine")
    @PreAuthorize("hasAuthority('quarantine.manage')")
    Map<String, Object> handleQuarantine(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        String action = String.valueOf(str(b, "action"));
        if (!"replay".equals(action) && !"discard".equals(action)) {
            throw new ApiException(400, "action", "Choose replay or discard.");
        }
        List<String> ids = new ArrayList<>();
        if (b.has("ids") && b.get("ids").isJsonArray()) {
            b.getAsJsonArray("ids").forEach(x -> ids.add(x.isJsonPrimitive() ? x.getAsString() : ""));
        }
        var visible = quarantineOf(p);
        Map<String, Object> out;
        if ("replay".equals(action)) {
            com.bimba.setu.delivery.DeliveryEngine e = engine();
            try {
                out = ingest.replay(ids, visible, sources::machinesOf, e::offer);
            } catch (java.io.IOException ex) {
                throw new ApiException(500, "io", "The quarantine could not be read: " + ex.getMessage());
            }
        } else {
            List<String> missing = new ArrayList<>();
            int done = 0;
            for (String id : ids) {
                if (ingest.quarantine().discard(id, visible)) {
                    done++;
                } else {
                    missing.add(id);
                }
            }
            out = new LinkedHashMap<>();
            out.put("discarded", done);
            out.put("missing", missing);
        }
        Map<String, Object> audit = new LinkedHashMap<>(out);
        audit.put("entries", ids.size());
        audit.remove("missing");
        security.audit(p, "quarantine." + action, ids.size() + " entr" + (ids.size() == 1 ? "y" : "ies"),
                ((List<?>) out.get("missing")).isEmpty(), req.getRemoteAddr(), audit);
        return out;
    }

    /** retention and alerts of the organization's quarantine */
    @GetMapping("/quarantine/settings")
    @PreAuthorize("hasAuthority('gateway.view')")
    Map<String, Object> quarantineSettings(@AuthenticationPrincipal Principal p) throws ApiException {
        return quarantineSettings.view(p.org());
    }

    @PutMapping("/quarantine/settings")
    @PreAuthorize("hasAuthority('quarantine.manage')")
    Map<String, Object> saveQuarantineSettings(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        Map<String, Object> out = quarantineSettings.save(p.org(), ApiErrors.parse(body));
        Map<String, Object> audit = new LinkedHashMap<>(out);
        audit.remove("webhookSecret");
        audit.remove("mailConfigured");
        security.audit(p, "quarantine.settings", "quarantine settings", true, req.getRemoteAddr(), audit);
        return out;
    }

    /** sends a test alert with the saved settings */
    @PostMapping("/quarantine/settings/test")
    @PreAuthorize("hasAuthority('quarantine.manage')")
    Map<String, Object> testQuarantineAlert(@AuthenticationPrincipal Principal p, HttpServletRequest req) throws ApiException {
        Map<String, Object> out = quarantineSettings.test(p.org());
        security.audit(p, "quarantine.alert.test", "quarantine alert", true, req.getRemoteAddr(), out);
        return out;
    }

    // ------------------------------------------------------------------ tag registry (FR-CAN-04)

    /** the organization's machines and its collected tags ("machine/tag" -> unit), for the registry */
    @SuppressWarnings("unchecked")
    private TagRegistry.Known known(Principal p, List<Map<String, Object>> tags) throws ApiException {
        Map<String, Object> st = setu.status();
        List<String> all = new ArrayList<>();
        Map<String, String> units = new LinkedHashMap<>();
        if (st.get("collector") instanceof Map<?, ?> c) {
            if (c.get("machines") instanceof List<?> l) {
                l.forEach(x -> all.add(String.valueOf(x)));
            }
            List<String> mine = orgs.machinesOf(p.org(), all);
            if (c.get("tags") instanceof List<?> l) {
                for (Object o : l) {
                    Map<String, Object> t = (Map<String, Object>) o;
                    String machine = String.valueOf(t.get("machine"));
                    if (mine.contains(machine)) {
                        String unit = t.get("unit") == null ? "" : String.valueOf(t.get("unit"));
                        units.put(machine + "/" + t.get("tag"), unit);
                        if (tags != null) {
                            tags.add(Map.of("machine", machine, "tag", String.valueOf(t.get("tag")), "unit", unit));
                        }
                    }
                }
            }
            return new TagRegistry.Known(mine, units);
        }
        return new TagRegistry.Known(List.of(), units);
    }

    @GetMapping("/registry")
    @PreAuthorize("hasAuthority('gateway.view')")
    Map<String, Object> registry(@AuthenticationPrincipal Principal p) throws ApiException {
        List<Map<String, Object>> tags = new ArrayList<>();
        TagRegistry.Known k = known(p, tags);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", registry.entries(p.org()).stream().map(TagRegistry.Entry::json).toList());
        out.put("tags", tags);
        out.put("machines", k.machines());
        out.put("types", TagRegistry.TYPES);
        return out;
    }

    /** body: one entry {machine, tag, tagName, machineId, plantId, dataType, unit, writable, description}; all empty = removed */
    @PutMapping("/registry/entry")
    @PreAuthorize("hasAuthority('registry.manage')")
    Map<String, Object> saveRegistryEntry(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        TagRegistry.Entry e = registry.entry(ApiErrors.parse(body));
        List<TagRegistry.Entry> list = new ArrayList<>(registry.entries(p.org()).stream()
                .filter(x -> !(x.machine().equals(e.machine()) && x.tag().equals(e.tag()))).toList());
        list.add(e);
        registry.save(p.org(), list, known(p, null));
        security.audit(p, "registry.save", e.machine() + "/" + e.tag(), true, req.getRemoteAddr(), e.json());
        return Map.of("ok", true, "removed", e.json().values().stream().skip(2).allMatch(java.util.Objects::isNull));
    }

    @DeleteMapping("/registry/entry")
    @PreAuthorize("hasAuthority('registry.manage')")
    Map<String, Object> deleteRegistryEntry(@AuthenticationPrincipal Principal p, @RequestParam String machine, @RequestParam String tag,
                                            HttpServletRequest req) throws ApiException {
        List<TagRegistry.Entry> list = registry.entries(p.org()).stream()
                .filter(x -> !(x.machine().equals(machine) && x.tag().equals(tag))).toList();
        registry.save(p.org(), list, known(p, null));
        security.audit(p, "registry.delete", machine + "/" + tag, true, req.getRemoteAddr(), Map.of());
        return Map.of("ok", true);
    }

    @GetMapping("/registry/export")
    @PreAuthorize("hasAuthority('gateway.view')")
    ResponseEntity<String> exportRegistry(@AuthenticationPrincipal Principal p) throws ApiException {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("bimba-tag-registry.csv").build().toString())
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(TagRegistry.csv(registry.entries(p.org())));
    }

    /** body {csv, mode: merge (default, rows replace the same tag's entry) | replace (the file is the whole registry)} */
    @PostMapping("/registry/import")
    @PreAuthorize("hasAuthority('registry.manage')")
    Map<String, Object> importRegistry(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        if (body != null && body.length() > 4 * 1024 * 1024) {
            throw new ApiException(413, "csv", "The file is larger than 4 MB.");
        }
        JsonObject b;
        try {
            b = Json.GSON.fromJson(body, JsonObject.class);
        } catch (RuntimeException e) {
            throw new ApiException(400, "body", "The request body must be a JSON object.");
        }
        List<TagRegistry.Entry> rows = registry.parseCsv(str(b, "csv"));
        boolean replace = "replace".equals(str(b, "mode"));
        Map<String, TagRegistry.Entry> merged = new LinkedHashMap<>();
        if (!replace) {
            registry.entries(p.org()).forEach(e -> merged.put(e.machine() + "/" + e.tag(), e));
        }
        rows.forEach(e -> merged.put(e.machine() + "/" + e.tag(), e));
        registry.save(p.org(), new ArrayList<>(merged.values()), known(p, null));
        security.audit(p, "registry.import", rows.size() + " rows", true, req.getRemoteAddr(), Map.of("rows", rows.size(), "mode", replace ? "replace" : "merge"));
        return Map.of("rows", rows.size(), "entries", registry.entries(p.org()).size());
    }

    private static Map<String, Object> pipelineDetail(JsonObject b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", b.has("active") && b.get("active").getAsBoolean());
        m.put("primary", b.has("primary") && !b.get("primary").isJsonNull() ? b.get("primary").toString() : null);
        m.put("secondary", b.has("secondary") ? b.get("secondary").toString() : "[]");
        m.put("mirrors", b.has("mirrors") ? b.get("mirrors").toString() : "[]");
        m.put("machines", b.has("machines") ? b.get("machines").toString() : "all");
        if (b.has("bufferAlerts") && b.get("bufferAlerts").isJsonObject()) {
            JsonObject ba = b.getAsJsonObject("bufferAlerts");
            String hook = str(ba, "webhookUrl");
            m.put("bufferAlerts", (ba.has("emails") ? ba.get("emails").toString() : "[]") + (hook == null || hook.isBlank() ? "" : " + webhook")
                    + (ba.has("organization") && !ba.get("organization").getAsBoolean() ? "" : " + organization"));
        }
        return m;
    }

    // ------------------------------------------------------------------ users (members of the organization)

    @GetMapping("/users")
    @PreAuthorize("hasAnyAuthority('users.view', 'users.manage')")
    Map<String, Object> users(@AuthenticationPrincipal Principal p) throws ApiException {
        return Map.of("users", security.users(p));
    }

    /** a new account {email, firstName, lastName, roleIds}, or {existing: true, email, roleIds} to add an account that already exists */
    @PostMapping("/users")
    @PreAuthorize("hasAuthority('users.manage')")
    ResponseEntity<Map<String, Object>> createUser(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        return ResponseEntity.status(201).body(security.createUser(p, ApiErrors.parse(body), req.getRemoteAddr()));
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('users.manage')")
    Map<String, Object> updateUser(@AuthenticationPrincipal Principal p, @PathVariable long id, @RequestBody String body,
                                   HttpServletRequest req) throws ApiException {
        security.updateUser(p, id, ApiErrors.parse(body), req.getRemoteAddr());
        return Map.of("ok", true);
    }

    /** removes the member from this organization (the account and its other organizations stay) */
    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasAuthority('users.manage')")
    Map<String, Object> removeUser(@AuthenticationPrincipal Principal p, @PathVariable long id, HttpServletRequest req) throws ApiException {
        security.removeMember(p, id, req.getRemoteAddr());
        return Map.of("ok", true);
    }

    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('users.manage')")
    Map<String, Object> resetPassword(@AuthenticationPrincipal Principal p, @PathVariable long id, HttpServletRequest req) throws ApiException {
        return Map.of("temporaryPassword", security.resetPassword(p, id, req.getRemoteAddr()));
    }

    @PostMapping("/users/{id}/unlock")
    @PreAuthorize("hasAuthority('users.manage')")
    Map<String, Object> unlock(@AuthenticationPrincipal Principal p, @PathVariable long id, HttpServletRequest req) throws ApiException {
        security.unlock(p, id, req.getRemoteAddr());
        return Map.of("ok", true);
    }

    @PostMapping("/users/{id}/sign-out")
    @PreAuthorize("hasAuthority('users.manage')")
    Map<String, Object> signOutUser(@AuthenticationPrincipal Principal p, @PathVariable long id, HttpServletRequest req) throws ApiException {
        return Map.of("ended", security.signOutUser(p, id, req.getRemoteAddr()));
    }

    // ------------------------------------------------------------------ roles

    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('users.view', 'users.manage', 'roles.manage')")
    Map<String, Object> roles(@AuthenticationPrincipal Principal p) throws ApiException {
        return Map.of("roles", security.roles(p), "permissions", Permission.catalog());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('roles.manage')")
    ResponseEntity<Map<String, Object>> createRole(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        return ResponseEntity.status(201).body(security.saveRole(p, null, ApiErrors.parse(body), req.getRemoteAddr()));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('roles.manage')")
    Map<String, Object> updateRole(@AuthenticationPrincipal Principal p, @PathVariable long id, @RequestBody String body,
                                   HttpServletRequest req) throws ApiException {
        return security.saveRole(p, id, ApiErrors.parse(body), req.getRemoteAddr());
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('roles.manage')")
    Map<String, Object> deleteRole(@AuthenticationPrincipal Principal p, @PathVariable long id, HttpServletRequest req) throws ApiException {
        security.deleteRole(p, id, req.getRemoteAddr());
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ duty separation + access review

    /** the duty separation rules, members who break one, and the BRD personas behind the built-in roles */
    @GetMapping("/access/duties")
    @PreAuthorize("hasAnyAuthority('users.view', 'users.manage', 'roles.manage')")
    Map<String, Object> duties(@AuthenticationPrincipal Principal p) throws ApiException {
        return security.duties(p);
    }

    /** what a role assignment can be limited to: sites, site agents, machines */
    @GetMapping("/access/places")
    @PreAuthorize("hasAnyAuthority('users.view', 'users.manage')")
    Map<String, Object> places(@AuthenticationPrincipal Principal p) throws ApiException {
        return security.places(p);
    }

    /** every member with their roles, scopes, permissions and last sign-in, as a spreadsheet (BRD FR-RBAC-09) */
    @GetMapping("/access/review.csv")
    @PreAuthorize("hasAnyAuthority('users.view', 'users.manage')")
    ResponseEntity<String> accessReview(@AuthenticationPrincipal Principal p, HttpServletRequest req) throws ApiException {
        String csv = security.accessReviewCsv(p);
        security.audit(p, "access.review.export", p.orgName(), true, req.getRemoteAddr(), Map.of());
        String name = "access-review-" + p.orgSlug() + "-" + java.time.LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body("﻿" + csv);      // a byte order mark, so Excel opens it as UTF-8
    }

    // ------------------------------------------------------------------ audit

    /** the organization's events; scope=all = every organization's (platform admins) */
    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('audit.view')")
    Map<String, Object> audit(@AuthenticationPrincipal Principal p, @RequestParam(required = false) String q,
                              @RequestParam(required = false) Long before, @RequestParam(required = false) String scope) throws ApiException {
        return Map.of("events", security.audit(p, q, before, "all".equals(scope)));
    }

    // ------------------------------------------------------------------ platform admins: organizations + machine owners

    @GetMapping("/orgs")
    @PreAuthorize("hasAuthority('platform.admin')")
    Map<String, Object> organizations() {
        Map<String, Long> owners = orgs.owners();
        Long def = orgs.defaultOrgId();
        List<Map<String, Object>> machines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (setu.status().get("collector") instanceof Map<?, ?> c && c.get("machines") instanceof List<?> l) {
            for (Object o : l) {
                String name = String.valueOf(o);
                seen.add(name);
                machines.add(machine(name, owners.get(name), def, true));
            }
        }
        // assigned machines that are not in the tag tree right now (removed, or collection stopped)
        owners.forEach((name, org) -> {
            if (!seen.contains(name)) {
                machines.add(machine(name, org, def, false));
            }
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organizations", orgs.overview());
        m.put("machines", machines);
        m.put("defaultOrgId", def);
        return m;
    }

    private static Map<String, Object> machine(String name, Long org, Long def, boolean inTagTree) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("orgId", org == null ? def : org);
        m.put("assigned", org != null);
        m.put("inTagTree", inTagTree);
        return m;
    }

    /** body {name, slug?} */
    @PostMapping("/orgs")
    @PreAuthorize("hasAuthority('platform.admin')")
    ResponseEntity<Map<String, Object>> createOrganization(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        OrgService.Org o = orgs.create(str(b, "name"), str(b, "slug"));
        security.audit(o.id(), p.username(), "org.create", o.name(), true, req.getRemoteAddr(), Map.of("slug", o.slug()));
        return ResponseEntity.status(201).body(o.json());
    }

    /** body {name} */
    @PutMapping("/orgs/{id}")
    @PreAuthorize("hasAuthority('platform.admin')")
    Map<String, Object> renameOrganization(@AuthenticationPrincipal Principal p, @PathVariable long id, @RequestBody String body,
                                           HttpServletRequest req) throws ApiException {
        String before = orgs.get(id).name();
        orgs.rename(id, str(ApiErrors.parse(body), "name"));
        security.audit(id, p.username(), "org.rename", orgs.get(id).name(), true, req.getRemoteAddr(), Map.of("before", before));
        return Map.of("ok", true);
    }

    @DeleteMapping("/orgs/{id}")
    @PreAuthorize("hasAuthority('platform.admin')")
    Map<String, Object> deleteOrganization(@AuthenticationPrincipal Principal p, @PathVariable long id, HttpServletRequest req)
            throws ApiException {
        OrgService.Org o = orgs.delete(id);
        security.audit((Long) null, p.username(), "org.delete", o.name(), true, req.getRemoteAddr(), Map.of("slug", o.slug(), "id", o.id()));
        return Map.of("ok", true);
    }

    /** body {orgId}: receives the machines nobody assigned (also ones added to the tag tree later) */
    @PutMapping("/orgs/default")
    @PreAuthorize("hasAuthority('platform.admin')")
    Map<String, Object> defaultOrganization(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        OrgService.Org o = orgs.get(b.get("orgId").getAsLong());
        orgs.setDefault(o.id());
        setu.reloadNow();
        security.audit(o.id(), p.username(), "org.default", o.name(), true, req.getRemoteAddr(), Map.of());
        return Map.of("ok", true);
    }

    /** body {machine, orgId}: the organization that gets the machine's readings (orgId null = the default organization) */
    @PutMapping("/machines")
    @PreAuthorize("hasAuthority('platform.admin')")
    Map<String, Object> assignMachine(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req)
            throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        String machine = str(b, "machine");
        Long orgId = b.has("orgId") && !b.get("orgId").isJsonNull() ? b.get("orgId").getAsLong() : null;
        Map<String, Long> owners = orgs.owners();
        Long def = orgs.defaultOrgId();
        Long before = owners.getOrDefault(machine, def);
        orgs.assign(machine, orgId, p.username());
        setu.reloadNow();
        Long after = orgId == null ? def : orgId;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", before == null ? "-" : orgs.get(before).name());
        detail.put("to", after == null ? "-" : orgs.get(after).name());
        // recorded in both organizations: the one that lost the machine and the one that got it
        if (before != null && !before.equals(after)) {
            security.audit(before, p.username(), "machine.assign", machine, true, req.getRemoteAddr(), detail);
        }
        security.audit(after, p.username(), "machine.assign", machine, true, req.getRemoteAddr(), detail);
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ profile (every signed-in user)

    @PutMapping("/profile")
    Map<String, Object> profile(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req) throws ApiException {
        security.updateProfile(p, ApiErrors.parse(body), req.getRemoteAddr());
        return Map.of("ok", true);
    }

    @PostMapping("/profile/password")
    Map<String, Object> password(@AuthenticationPrincipal Principal p, @RequestBody String body, HttpServletRequest req) throws ApiException {
        JsonObject b = ApiErrors.parse(body);
        security.changePassword(p, str(b, "current"), str(b, "next"), req.getRemoteAddr());
        return Map.of("ok", true);
    }

    /** the photo as the request body (PNG / JPEG / WebP, at most 512 KB) */
    @PutMapping(value = "/profile/avatar", consumes = "*/*")
    Map<String, Object> setAvatar(@AuthenticationPrincipal Principal p, HttpServletRequest req) throws ApiException, java.io.IOException {
        if (req.getContentLengthLong() > SecurityService.AVATAR_MAX_BYTES) {
            throw new ApiException(413, "too_large", "The photo can be at most 512 KB.");
        }
        byte[] image = req.getInputStream().readNBytes(SecurityService.AVATAR_MAX_BYTES + 1);
        security.setAvatar(p, image, req.getRemoteAddr());
        return Map.of("ok", true);
    }

    @DeleteMapping("/profile/avatar")
    Map<String, Object> removeAvatar(@AuthenticationPrincipal Principal p, HttpServletRequest req) {
        security.removeAvatar(p, req.getRemoteAddr());
        return Map.of("ok", true);
    }

    /** a user's photo: for themselves, members of the session's organization and platform admins */
    @GetMapping("/avatars/{id}")
    ResponseEntity<byte[]> avatar(@AuthenticationPrincipal Principal p, @PathVariable long id) throws ApiException {
        Object[] a = security.avatar(p, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, (String) a[1])
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")   // the URL carries the photo's version
                .body((byte[]) a[0]);
    }

    @GetMapping("/profile/sessions")
    Map<String, Object> sessions(@AuthenticationPrincipal Principal p) {
        return Map.of("sessions", security.sessions(p));
    }

    @DeleteMapping("/profile/sessions/{id}")
    Map<String, Object> endSession(@AuthenticationPrincipal Principal p, @PathVariable String id, HttpServletRequest req) {
        return Map.of("ended", security.endSessions(p, id, req.getRemoteAddr()));
    }

    @PostMapping("/profile/sessions/sign-out-others")
    Map<String, Object> endOtherSessions(@AuthenticationPrincipal Principal p, HttpServletRequest req) {
        return Map.of("ended", security.endSessions(p, null, req.getRemoteAddr()));
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
