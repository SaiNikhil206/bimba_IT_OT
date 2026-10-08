package com.bimba.gateway.agents;

import com.bimba.gateway.Db;
import com.bimba.gateway.GatewayService;
import com.bimba.gateway.Json;
import com.bimba.gateway.config.BimbaConfig;
import com.bimba.gateway.security.Passwords;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Registry of machines running the BIMBA Site Agent, kept in the shared PostgreSQL (tables created by the
 * Django migration telemetry/0004_site_agents). The organization is always decided here from the credentials
 * (enrollment token or organization user), never taken from the agent, so a machine can only ever appear in the
 * organization whose credentials installed it.
 */
@Service
public class AgentRegistry {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Agents");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GatewayService gateway;
    private final Db db;
    public final FailureLimiter failures = new FailureLimiter();

    public AgentRegistry(GatewayService gateway, Db db) {
        this.gateway = gateway;
        this.db = db;
    }

    /** HTTP status + message for the agent / console API. */
    public static class ApiError extends Exception {
        public final int status;
        public final Map<String, Object> extra = new LinkedHashMap<>();

        public ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public BimbaConfig.Agents settings() {
        BimbaConfig c = gateway.config();
        return c == null ? new BimbaConfig.Agents() : c.agents;
    }

    private record Credential(long orgId, String orgSlug, String orgName, String via, String actor) {
    }

    // ------------------------------------------------------------------ enroll

    public Map<String, Object> enroll(JsonObject req, String remoteIp) throws ApiError {
        BimbaConfig.Agents s = settings();
        JsonObject auth = obj(req, "auth");
        JsonObject machine = obj(req, "machine");
        if (auth == null || machine == null) {
            throw new ApiError(400, "auth and machine are required");
        }
        String fingerprint = str(machine, "fingerprint", 64);
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new ApiError(400, "machine.fingerprint must be 64 lowercase hex characters");
        }
        String hostname = str(machine, "hostname", 255);
        if (hostname == null || hostname.isBlank()) {
            throw new ApiError(400, "machine.hostname is required");
        }
        String name = str(req, "displayName", 200);
        String displayName = name == null || name.isBlank() ? hostname : name;
        String site = str(req, "site", 200);
        // an organization user's password is checked before the transaction (PBKDF2 takes a moment)
        Credential userCred = "token".equals(str(auth, "type", 16)) ? null : passwordCredential(auth);
        try {
            Map<String, Object> out = db.tx(() -> {
                Credential cred = userCred != null ? userCred : tokenCredential(str(auth, "token", 200));
                String secret = randomSecret();
                Object[] row = db.sql("INSERT INTO {s}.bimba_agent (id, org_id, fingerprint, display_name, hostname, site, os_name,"
                                + " agent_version, primary_ip, remote_ip, scada, details, secret_hash, enrolled_at, enrolled_via,"
                                + " enrolled_by, heartbeat_seconds, last_seen, state, revoked_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, now(), ?, ?, ?, NULL, 'active', NULL)"
                                + " ON CONFLICT (org_id, fingerprint) DO UPDATE SET display_name = EXCLUDED.display_name,"
                                + " hostname = EXCLUDED.hostname, site = EXCLUDED.site, os_name = EXCLUDED.os_name,"
                                + " agent_version = EXCLUDED.agent_version, primary_ip = EXCLUDED.primary_ip,"
                                + " remote_ip = EXCLUDED.remote_ip, scada = EXCLUDED.scada, details = EXCLUDED.details,"
                                + " secret_hash = EXCLUDED.secret_hash, enrolled_at = now(), enrolled_via = EXCLUDED.enrolled_via,"
                                + " enrolled_by = EXCLUDED.enrolled_by, heartbeat_seconds = EXCLUDED.heartbeat_seconds,"
                                + " last_seen = NULL, state = 'active', revoked_at = NULL, cert_sha256 = '', cert_prev_sha256 = '',"
                                + " cert_issued_at = NULL, cert_not_after = NULL"
                                + " RETURNING id, (xmax::text <> '0') AS updated")
                        .params(UUID.randomUUID(), cred.orgId(), fingerprint, displayName, hostname, site == null ? "" : site,
                                nz(osName(machine)), nz(str(machine, "agentVersion", 32)), nz(str(machine, "primaryIp", 64)),
                                nz(remoteIp), scada(machine).toString(), machine.toString(), sha256(secret), cred.via(), cred.actor(),
                                s.heartbeatSeconds)
                        .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getBoolean(2)}).single();
                UUID agentId = (UUID) row[0];
                boolean reenrolled = (Boolean) row[1];
                event(cred.orgId(), agentId, reenrolled ? "re-enrolled" : "enrolled", cred.actor(), remoteIp,
                        Map.of("via", cred.via(), "hostname", hostname));
                log.info("site agent {} {} for org {} ({} via {}, from {})", hostname, reenrolled ? "re-enrolled" : "enrolled",
                        cred.orgSlug(), agentId, cred.via(), remoteIp);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("agentId", agentId.toString());
                m.put("agentSecret", secret);
                m.put("org", Map.of("slug", cred.orgSlug(), "name", cred.orgName()));
                m.put("displayName", displayName);
                m.put("heartbeatSeconds", s.heartbeatSeconds);
                m.put("reenrolled", reenrolled);
                m.put("certificates", s.issueCertificates && AgentCa.ready());
                return m;
            });
            failures.clear(remoteIp);
            return out;
        } catch (ApiError e) {
            if (e.status == 401) {
                failures.fail(remoteIp, s.failureWindowMinutes * 60_000L);
                log.warn("site agent enrollment refused from {}: {}", remoteIp, e.getMessage());
            }
            throw e;
        }
    }

    private Credential tokenCredential(String token) throws ApiError {
        if (token == null || token.isBlank()) {
            throw new ApiError(400, "enrollment token is required");
        }
        // one statement: only a token that is active right now is used, and its use is counted atomically
        return db.sql("UPDATE {s}.bimba_enroll_token tk SET uses = tk.uses + 1, last_used_at = now()"
                        + " FROM {s}.bimba_org o WHERE o.id = tk.org_id AND tk.token_hash = ?"
                        + " AND tk.revoked_at IS NULL AND (tk.expires_at IS NULL OR tk.expires_at > now())"
                        + " AND (tk.max_uses IS NULL OR tk.uses < tk.max_uses)"
                        + " RETURNING o.id, o.slug, o.name, tk.prefix, tk.label")
                .param(sha256(token.trim()))
                .query((rs, n) -> {
                    String label = rs.getString(5);
                    return new Credential(rs.getLong(1), rs.getString(2), rs.getString(3), "token",
                            "token " + rs.getString(4) + "…" + (label == null || label.isBlank() ? "" : " (" + label + ")"));
                })
                .optional()
                .orElseThrow(() -> new ApiError(401, "The enrollment token is not valid, has expired or was used up."));
    }

    /** organization user = e-mail address + password ("email"; installers before 1.1 send it as "username") */
    private Credential passwordCredential(JsonObject auth) throws ApiError {
        String email = str(auth, "email", 254);
        if (email == null || email.isBlank()) {
            email = str(auth, "username", 254);
        }
        String password = auth.has("password") && auth.get("password").isJsonPrimitive() ? auth.get("password").getAsString() : null;
        String wantedOrg = str(auth, "org", 64);
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            throw new ApiError(400, "e-mail address and password are required");
        }
        String user = email.trim().toLowerCase(java.util.Locale.ROOT);
        Object[] row = db.sql("SELECT id, password, is_active FROM {s}.auth_user WHERE email <> '' AND lower(email) = ?").param(user)
                .query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getBoolean(3)}).optional().orElse(null);
        // hash even for unknown users so response time does not reveal which accounts exist
        boolean match = Passwords.matches(password, row == null ? Passwords.DUMMY : (String) row[1]);
        if (row == null || !match || !(Boolean) row[2]) {
            throw new ApiError(401, "Wrong e-mail address or password.");
        }
        List<Credential> orgs = db.sql("SELECT o.id, o.slug, o.name FROM {s}.bimba_org_member m JOIN {s}.bimba_org o ON o.id = m.org_id"
                        + " WHERE m.user_id = ? ORDER BY o.name").param(row[0])
                .query((rs, n) -> new Credential(rs.getLong(1), rs.getString(2), rs.getString(3), "password", user)).list();
        if (orgs.isEmpty()) {
            throw new ApiError(403, "This user is not a member of any organization.");
        }
        if (wantedOrg != null && !wantedOrg.isBlank()) {
            return orgs.stream().filter(o -> o.orgSlug().equals(wantedOrg.trim())).findFirst()
                    .orElseThrow(() -> new ApiError(403, "This user is not a member of organization " + wantedOrg + "."));
        }
        if (orgs.size() > 1) {
            // only the signed-in user's own organizations are listed
            ApiError e = new ApiError(409, "Choose the organization this machine belongs to.");
            List<Map<String, String>> list = new ArrayList<>();
            orgs.forEach(o -> list.add(Map.of("slug", o.orgSlug(), "name", o.orgName())));
            e.extra.put("orgs", list);
            throw e;
        }
        return orgs.get(0);
    }

    // ------------------------------------------------------------------ heartbeat

    /** Who is calling: the agent's client certificate (mutual TLS) or, until it has one, its secret. */
    public record Caller(String agentId, String secret, X509Certificate cert, boolean secure) {
    }

    record Agent(UUID id, long orgId, String orgSlug, int heartbeatSeconds, String certSha256,
                         Timestamp certNotAfter, boolean viaCertificate) {
    }

    /**
     * Checks the caller. An agent with a certificate must use it (its secret is then refused); the previous credential
     * (old certificate, or the secret right after the first certificate) stays valid until the new certificate is first
     * used, so a lost answer cannot lock the machine out.
     */
    Agent authenticate(Caller c) throws ApiError {
        String sha = null;
        UUID id = null;
        if (c.cert() != null) {
            sha = certSha256(c.cert());
            id = AgentCa.agentIdOf(c.cert());
        }
        if (id == null) {
            try {
                id = UUID.fromString(c.agentId() == null ? "" : c.agentId().trim());
            } catch (IllegalArgumentException e) {
                throw new ApiError(401, "unknown agent");
            }
        } else if (c.agentId() != null && !c.agentId().isBlank() && !id.toString().equalsIgnoreCase(c.agentId().trim())) {
            throw new ApiError(401, "the client certificate belongs to another agent");
        }
        Object[] row = db.sql("SELECT a.org_id, a.secret_hash, a.state, a.heartbeat_seconds, a.cert_sha256, a.cert_prev_sha256,"
                        + " a.cert_not_after, o.slug FROM {s}.bimba_agent a JOIN {s}.bimba_org o ON o.id = a.org_id WHERE a.id = ?").param(id)
                .query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7), rs.getString(8)}).optional().orElse(null);
        if (row == null) {
            throw new ApiError(401, "unknown agent or wrong credentials");
        }
        String current = nz((String) row[4]);
        String prev = nz((String) row[5]);
        boolean viaCert;
        if (sha != null) {
            if (sha.equals(current)) {
                if (!prev.isEmpty()) {
                    // the renewed certificate is in use: the previous credential is no longer accepted
                    db.sql("UPDATE {s}.bimba_agent SET cert_prev_sha256 = '' WHERE id = ? AND cert_sha256 = ?").params(id, sha).update();
                }
            } else if (!sha.equals(prev)) {
                throw new ApiError(401, "this certificate is no longer valid for the agent (renewed or re-installed)");
            }
            viaCert = true;
        } else {
            String secret = c.secret();
            if (secret == null || secret.isBlank()) {
                throw new ApiError(401, current.isEmpty() ? "agent secret required"
                        : "this agent signs in with its certificate (connect over HTTPS)");
            }
            if (!current.isEmpty() && !"secret".equals(prev)) {
                throw new ApiError(401, "this agent has a certificate; its secret is no longer accepted");
            }
            if (!MessageDigest.isEqual(((String) row[1]).getBytes(StandardCharsets.US_ASCII),
                    sha256(secret.trim()).getBytes(StandardCharsets.US_ASCII))) {
                throw new ApiError(401, "unknown agent or wrong agent secret");
            }
            viaCert = false;
        }
        String state = (String) row[2];
        if (!"active".equals(state)) {
            ApiError e = new ApiError(410, "This agent was " + state + ". Reinstall it to register the machine again.");
            e.extra.put("state", state);
            throw e;
        }
        return new Agent(id, (Long) row[0], (String) row[7], (Integer) row[3], current, (Timestamp) row[6], viaCert);
    }

    public Map<String, Object> heartbeat(Caller caller, JsonObject body, String remoteIp) throws ApiError {
        Agent a = authenticate(caller);
        UUID id = a.id();
        long orgId = a.orgId();
        if ("uninstalled".equals(str(body, "status", 16))) {
            db.sql("UPDATE {s}.bimba_agent SET state = 'uninstalled', last_seen = now(), remote_ip = ? WHERE id = ?")
                    .params(nz(remoteIp), id).update();
            event(orgId, id, "uninstalled", "agent", remoteIp, Map.of());
            return Map.of("ok", true, "state", "uninstalled");
        }
        JsonObject collector = obj(body, "collector");
        if (collector != null) {
            // edge collector status (sources connected, readings sent, buffer): shown on the machine's page
            db.sql("UPDATE {s}.bimba_agent SET collector = ?::jsonb WHERE id = ?").params(collector.toString(), id).update();
        }
        JsonObject machine = obj(body, "machine");
        if (machine == null) {
            db.sql("UPDATE {s}.bimba_agent SET last_seen = now(), remote_ip = ? WHERE id = ?").params(nz(remoteIp), id).update();
        } else {
            db.sql("UPDATE {s}.bimba_agent SET last_seen = now(), remote_ip = ?, hostname = COALESCE(NULLIF(?, ''), hostname),"
                            + " os_name = ?, agent_version = ?, primary_ip = ?, scada = ?::jsonb, details = ?::jsonb WHERE id = ?")
                    .params(nz(remoteIp), nz(str(machine, "hostname", 255)), nz(osName(machine)), nz(str(machine, "agentVersion", 32)),
                            nz(str(machine, "primaryIp", 64)), scada(machine).toString(), machine.toString(), id)
                    .update();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("agentId", id.toString());
        out.put("heartbeatSeconds", a.heartbeatSeconds());
        // an agent without a certificate is asked to get one (only over HTTPS: mutual TLS needs it)
        out.put("certificateWanted", a.certSha256().isEmpty() && caller.secure() && settings().issueCertificates && AgentCa.ready());
        if (a.certNotAfter() != null) {
            out.put("certificateNotAfter", a.certNotAfter().toInstant().toString());
        }
        return out;
    }

    // ------------------------------------------------------------------ client certificates (mutual TLS)

    /**
     * Issues the agent's first certificate (caller = secret) or renews it (caller = current certificate).
     * body {csr: base64 DER PKCS#10}. Answer {certificate: base64 DER, notAfter}.
     */
    public Map<String, Object> certificate(Caller caller, JsonObject body, String remoteIp) throws ApiError {
        BimbaConfig.Agents s = settings();
        if (!s.issueCertificates || !AgentCa.ready()) {
            throw new ApiError(404, "This gateway does not issue agent certificates.");
        }
        if (!caller.secure()) {
            throw new ApiError(403, "certificates are issued over HTTPS only");
        }
        Agent a = authenticate(caller);
        String csr = str(body, "csr", 16_384);
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(csr == null ? "" : csr);
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "csr must be base64");
        }
        if (der.length == 0) {
            throw new ApiError(400, "csr is required");
        }
        int days = Math.max(2, Math.min(825, s.certificateDays));
        X509Certificate cert = AgentCa.issue(der, a.id(), a.orgSlug(), days);
        String sha = certSha256(cert);
        // what the caller used stays valid until the new certificate is first used
        String prev = a.viaCertificate() ? certSha256(caller.cert()) : "secret";
        db.sql("UPDATE {s}.bimba_agent SET cert_sha256 = ?, cert_prev_sha256 = ?, cert_issued_at = now(), cert_not_after = ?"
                        + " WHERE id = ? AND state = 'active'")
                .params(sha, prev, Timestamp.from(cert.getNotAfter().toInstant()), a.id()).update();
        boolean renewal = !a.certSha256().isEmpty();
        event(a.orgId(), a.id(), renewal ? "certificate-renewed" : "certificate-issued", "agent", remoteIp,
                Map.of("sha256", sha, "notAfter", cert.getNotAfter().toInstant().toString()));
        log.info("site agent {} {} a client certificate (valid until {})", a.id(), renewal ? "renewed" : "received",
                cert.getNotAfter().toInstant());
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("certificate", Base64.getEncoder().encodeToString(cert.getEncoded()));
            m.put("notAfter", cert.getNotAfter().toInstant().toString());
            m.put("issuer", AgentCa.certificate().getSubjectX500Principal().getName());
            return m;
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new ApiError(500, e.getMessage());
        }
    }

    public static String certSha256(X509Certificate c) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ console: list + revoke

    /** the site agents of one organization */
    public List<Map<String, Object>> list(long orgId) {
        return db.sql("SELECT a.id, o.slug, o.name, a.display_name, a.hostname, a.site, a.os_name, a.primary_ip, a.remote_ip,"
                        + " a.scada::text, a.agent_version, a.state, a.last_seen, a.enrolled_at, a.enrolled_via, a.enrolled_by,"
                        + " a.heartbeat_seconds, now(), a.cert_sha256, a.cert_not_after"
                        + " FROM {s}.bimba_agent a JOIN {s}.bimba_org o ON o.id = a.org_id"
                        + " WHERE a.org_id = ? ORDER BY a.display_name")
                .param(orgId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("orgSlug", rs.getString(2));
                    m.put("org", rs.getString(3));
                    m.put("name", rs.getString(4));
                    m.put("hostname", rs.getString(5));
                    m.put("site", rs.getString(6));
                    m.put("os", rs.getString(7));
                    m.put("ip", rs.getString(8));
                    m.put("remoteIp", rs.getString(9));
                    m.put("scada", Json.toJava(rs.getString(10)));
                    m.put("agentVersion", rs.getString(11));
                    Timestamp seen = rs.getTimestamp(13);
                    Timestamp now = rs.getTimestamp(18);
                    String state = rs.getString(12);
                    m.put("status", !"active".equals(state) ? state : seen == null ? "pending"
                            : now.getTime() - seen.getTime() <= 3_000L * Math.max(rs.getInt(17), 10) ? "online" : "offline");
                    m.put("lastSeen", seen == null ? null : seen.toInstant().toString());
                    m.put("enrolledAt", rs.getTimestamp(14).toInstant().toString());
                    m.put("enrolledVia", rs.getString(15));
                    m.put("enrolledBy", rs.getString(16));
                    Timestamp certEnd = rs.getTimestamp(20);
                    m.put("signIn", nz(rs.getString(19)).isEmpty() ? "secret" : "certificate");
                    m.put("certificateNotAfter", certEnd == null ? null : certEnd.toInstant().toString());
                    return m;
                })
                .list();
    }

    /** Blocks an agent of the organization: its next heartbeat gets 410 and its service stops. Returns the agent's name. */
    public String revoke(String agentId, long orgId, String actor, String remoteIp) throws ApiError {
        UUID id;
        try {
            id = UUID.fromString(agentId);
        } catch (IllegalArgumentException e) {
            throw new ApiError(404, "agent not found");
        }
        Object[] row = db.sql("UPDATE {s}.bimba_agent SET state = 'revoked', revoked_at = now()"
                        + " WHERE id = ? AND org_id = ? AND state = 'active' RETURNING org_id, display_name").params(id, orgId)
                .query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}).optional()
                .orElseThrow(() -> new ApiError(404, "agent not found or not active"));
        event((Long) row[0], id, "revoked", "gateway: " + actor, remoteIp, Map.of());
        return (String) row[1];
    }

    // ------------------------------------------------------------------ helpers

    private void event(long orgId, UUID agentId, String kind, String actor, String ip, Map<String, Object> detail) {
        db.sql("INSERT INTO {s}.bimba_agent_event (org_id, agent_id, kind, actor, remote_ip, detail, at)"
                        + " VALUES (?, ?, ?, ?, ?, ?::jsonb, now())")
                .params(orgId, agentId, kind, actor.length() > 200 ? actor.substring(0, 200) : actor, nz(ip), Json.GSON.toJson(detail))
                .update();
    }

    private static String osName(JsonObject machine) {
        JsonObject os = obj(machine, "os");
        if (os == null) {
            return null;
        }
        String name = str(os, "name", 150);
        String version = str(os, "version", 40);
        String full = (nz(name) + (version == null ? "" : " " + version)).trim();
        return full.length() > 200 ? full.substring(0, 200) : full;
    }

    private static JsonArray scada(JsonObject machine) {
        JsonElement e = machine.get("scada");
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static String str(JsonObject o, String key, int max) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        String v = e.getAsString().trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    public static String sha256(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "bimba_as_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
