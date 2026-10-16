package com.bimba.setu.security;

import com.bimba.setu.Db;
import com.bimba.setu.SetuService;
import com.bimba.setu.Json;
import com.bimba.setu.agents.FailureLimiter;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bimba.setu.agents.AgentSources;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sign-in, sessions, users, roles, profile and audit log of the Setu console.
 * <ul>
 *   <li>Accounts are Django users (auth_user), passwords in Django's PBKDF2 format. People sign in with their e-mail
 *       address (unique, case-insensitive); for Setu accounts the internal username is the same e-mail.</li>
 *   <li>Multi-organization: one account can belong to several organizations and has Setu roles per organization.
 *       A session works in one organization at a time (switchOrganization); permissions, users, roles, the audit log and
 *       all Setu data the console shows are those of that organization ({@link OrgService}).</li>
 *   <li>Access needs a Setu role in an organization (or Django superuser = platform admin, who has every permission
 *       in every organization).</li>
 *   <li>Lockout after {@code maxFailedLogins} wrong passwords, forced change of temporary passwords,
 *       sessions in the database with idle and absolute timeouts.</li>
 *   <li>Nobody can grant a permission they do not have themselves, and only platform admins can change
 *       platform admin accounts, so user management cannot be used to escalate.</li>
 * </ul>
 * Spring Security (web/SecurityConfig) runs the filter chain; this class holds the rules and the data.
 */
@Service
public class SecurityService {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Security");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    public static final String ADMIN_ROLE = "Administrator";

    private final SetuService setu;
    private final Db db;
    private final OrgService orgs;
    private final Grants grants;
    private final ObjectProvider<AgentSources> sources;
    private final FailureLimiter ipFailures = new FailureLimiter();

    public SecurityService(SetuService setu, Db db, OrgService orgs, Grants grants, ObjectProvider<AgentSources> sources) {
        this.setu = setu;
        this.db = db;
        this.orgs = orgs;
        this.grants = grants;
        this.sources = sources;
    }

    /** HTTP status + message (+ machine-readable code) for the API. */
    public static final class ApiException extends Exception {
        public final int status;
        public final String code;

        public ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /**
     * The signed-in user of one request (the Spring Security principal). orgId = the organization the session works in
     * (null only for a platform admin while no organization exists); permissions are the user's in that organization,
     * scopes = where the scoped ones hold when the user has them only through limited role assignments.
     */
    public record Principal(long userId, String username, String name, String firstName, String lastName, String email,
                            boolean superuser, Set<String> permissions, Map<String, Scope> scopes, boolean mustChangePassword, String sessionId,
                            Long orgId, String orgSlug, String orgName, Long avatarVersion) {
        /** held somewhere in the organization */
        public boolean can(Permission p) {
            return permissions.contains(p.key);
        }

        /** held for the whole organization */
        public boolean everywhere(Permission p) {
            return can(p) && !scopes.containsKey(p.key);
        }

        /** where it holds: null = everywhere (when held at all) */
        public Scope scope(Permission p) {
            return scopes.get(p.key);
        }

        /** held for this place: a site agent (its id and site) and, for machine things, one of its machines */
        public boolean allows(Permission p, String agentId, String site, String machine) {
            Scope s = scopes.get(p.key);
            return can(p) && (s == null || s.covers(agentId, site, machine));
        }

        /** the organization every organization-scoped call works in */
        public long org() throws ApiException {
            if (orgId == null) {
                throw new ApiException(409, "no_org", "There is no organization yet. A platform admin creates one under Organizations.");
            }
            return orgId;
        }

        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", userId);
            m.put("username", username);
            m.put("name", name);
            m.put("firstName", firstName);
            m.put("lastName", lastName);
            m.put("email", email);
            m.put("platformAdmin", superuser);
            m.put("permissions", permissions);
            Map<String, Object> sc = new LinkedHashMap<>();
            scopes.forEach((k, v) -> sc.put(k, v.json()));
            m.put("scopes", sc);
            m.put("mustChangePassword", mustChangePassword);
            m.put("avatarUrl", avatarUrl(userId, avatarVersion));
            if (orgId == null) {
                m.put("org", null);
            } else {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", orgId);
                o.put("slug", orgSlug);
                o.put("name", orgName);
                m.put("org", o);
            }
            return m;
        }
    }

    public record Login(String token, Principal principal) {
    }

    private BimbaConfig.Security sec() {
        BimbaConfig c = setu.config();
        return c == null ? new BimbaConfig.Security() : c.security;
    }

    // ------------------------------------------------------------------ sign-in / sessions

    /** Sign-in with the account's e-mail address (case-insensitive) and password. */
    public Login login(String email, String password, String ip, String userAgent) throws ApiException {
        BimbaConfig.Security s = sec();
        long window = 15 * 60_000L;
        if (ipFailures.blocked(ip, 20, window)) {
            throw new ApiException(429, "rate_limited", "Too many failed sign-ins from this address. Try again in 15 minutes.");
        }
        String user = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (user.isEmpty() || password == null || password.isEmpty()) {
            throw new ApiException(400, "missing", "Enter your e-mail address and password.");
        }
        Object[] row = db.sql("SELECT u.id, u.password, u.is_active, u.is_superuser,"
                        + " (p.locked_until IS NOT NULL AND p.locked_until > now()),"
                        + " CEIL(EXTRACT(EPOCH FROM (p.locked_until - now())) / 60), p.last_org_id"
                        + " FROM {s}.auth_user u LEFT JOIN {s}.bimba_gw_user_profile p ON p.user_id = u.id"
                        + " WHERE u.email <> '' AND lower(u.email) = ?")
                .param(user)
                .query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5), rs.getInt(6),
                        rs.getObject(7, Long.class)})
                .optional().orElse(null);
        // checked outside any transaction (PBKDF2 takes a moment) and also for unknown users
        boolean match = Passwords.matches(password, row == null ? Passwords.DUMMY : (String) row[1]);
        if (row == null) {
            ipFailures.fail(ip, window);
            audit(user, "login", user, false, ip, Map.of("reason", "unknown user"));
            throw new ApiException(401, "bad_credentials", "Wrong e-mail address or password.");
        }
        long id = (Long) row[0];
        if ((Boolean) row[4]) {
            audit(user, "login", user, false, ip, Map.of("reason", "account locked"));
            throw new ApiException(423, "locked", "This account is locked after too many wrong passwords. Try again in "
                    + Math.max(1, (Integer) row[5]) + " minute(s) or ask an administrator to unlock it.");
        }
        if (!match) {
            ipFailures.fail(ip, window);
            int failed = db.sql("INSERT INTO {s}.bimba_gw_user_profile (user_id, failed_logins) VALUES (?, 1)"
                            + " ON CONFLICT (user_id) DO UPDATE SET failed_logins = {s}.bimba_gw_user_profile.failed_logins + 1"
                            + " RETURNING failed_logins")
                    .param(id).query(Integer.class).single();
            if (failed >= s.maxFailedLogins) {
                db.sql("UPDATE {s}.bimba_gw_user_profile SET failed_logins = 0, locked_until = now() + (? * interval '1 minute')"
                        + " WHERE user_id = ?").params(s.lockMinutes, id).update();
                audit(user, "account.locked", user, false, ip, Map.of("minutes", s.lockMinutes, "after", failed));
                log.warn("account {} locked for {} min after {} wrong passwords (last from {})", user, s.lockMinutes, failed, ip);
                throw new ApiException(423, "locked", "Too many wrong passwords. The account is locked for " + s.lockMinutes + " minutes.");
            }
            audit(user, "login", user, false, ip, Map.of("reason", "wrong password", "attempt", failed));
            int left = s.maxFailedLogins - failed;
            throw new ApiException(401, "bad_credentials", "Wrong e-mail address or password."
                    + (left <= 2 ? " " + left + " attempt(s) left before the account is locked." : ""));
        }
        if (!(Boolean) row[2]) {
            audit(user, "login", user, false, ip, Map.of("reason", "account disabled"));
            throw new ApiException(403, "disabled", "This account is disabled. Ask an administrator.");
        }
        boolean superuser = (Boolean) row[3];
        List<OrgService.Access> access = orgs.access(id, superuser);
        if (access.isEmpty() && !superuser) {
            audit(user, "login", user, false, ip, Map.of("reason", "no Setu role in any organization"));
            throw new ApiException(403, "no_access", "This account has no role in any organization on this Setu server. Ask an administrator for access.");
        }
        // the organization used last, else the first one (by name)
        Long last = (Long) row[6];
        Long orgId = access.stream().map(a -> a.org().id()).filter(o -> o.equals(last)).findFirst()
                .orElse(access.isEmpty() ? null : access.get(0).org().id());
        String token = randomToken();
        db.tx(() -> {
            db.sql("INSERT INTO {s}.bimba_gw_user_profile (user_id, failed_logins, last_login_ip, last_org_id) VALUES (?, 0, ?, ?)"
                            + " ON CONFLICT (user_id) DO UPDATE SET failed_logins = 0, locked_until = NULL, last_login_ip = EXCLUDED.last_login_ip,"
                            + " last_org_id = EXCLUDED.last_org_id")
                    .params(id, ip, orgId).update();
            db.sql("UPDATE {s}.auth_user SET last_login = now() WHERE id = ?").param(id).update();
            db.sql("INSERT INTO {s}.bimba_gw_session (id, user_id, org_id, created_at, last_seen_at, expires_at, ip, user_agent)"
                            + " VALUES (?, ?, ?, now(), now(), now() + (? * interval '1 hour'), ?, ?)")
                    .params(sha256(token), id, orgId, s.maxSessionHours, ip, cut(userAgent, 300)).update();
            return null;
        });
        ipFailures.clear(ip);
        audit(orgId, user, "login", user, true, ip, Map.of("organizations", access.size()));
        Principal p = authenticate(token, ip, true);
        if (p == null) {
            throw new ApiException(500, "session", "Could not start the session.");
        }
        return new Login(token, p);
    }

    /**
     * The session's user, or null when the token is unknown, revoked, idle too long or past its maximum age.
     * activity=false for the web app's automatic refreshes, so an open but unattended page still times out.
     */
    public Principal authenticate(String token, String ip, boolean activity) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return null;
        }
        BimbaConfig.Security s = sec();
        String id = sha256(token);
        Object[] row = db.sql("SELECT u.id, u.username, u.first_name, u.last_name, u.email, u.is_superuser, u.is_active,"
                        + " (s.revoked_at IS NULL AND s.expires_at > now()), EXTRACT(EPOCH FROM (now() - s.last_seen_at)),"
                        + " COALESCE(p.must_change_password, false), o.id, o.slug, o.name,"
                        + " CASE WHEN p.avatar IS NULL THEN NULL ELSE (EXTRACT(EPOCH FROM p.avatar_updated_at) * 1000)::bigint END"
                        + " FROM {s}.bimba_gw_session s JOIN {s}.auth_user u ON u.id = s.user_id"
                        + " LEFT JOIN {s}.bimba_gw_user_profile p ON p.user_id = u.id"
                        + " LEFT JOIN {s}.bimba_org o ON o.id = s.org_id WHERE s.id = ?")
                .param(id)
                .query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8), rs.getDouble(9), rs.getBoolean(10),
                        rs.getObject(11, Long.class), rs.getString(12), rs.getString(13), rs.getObject(14, Long.class)})
                .optional().orElse(null);
        if (row == null || !(Boolean) row[6] || !(Boolean) row[7]) {
            return null;
        }
        String username = (String) row[1];
        if ((Double) row[8] > s.idleMinutes * 60.0) {
            db.sql("UPDATE {s}.bimba_gw_session SET revoked_at = now() WHERE id = ?").param(id).update();
            audit((Long) row[10], username, "session.timeout", username, true, ip, Map.of("idleMinutes", s.idleMinutes));
            return null;
        }
        if (activity && (Double) row[8] > 20) {   // fewer writes: activity is recorded at most every 20 s
            db.sql("UPDATE {s}.bimba_gw_session SET last_seen_at = now(), ip = ? WHERE id = ?").params(ip, id).update();
        }
        long userId = (Long) row[0];
        boolean superuser = (Boolean) row[5];
        Long orgId = (Long) row[10];
        String orgSlug = (String) row[11];
        String orgName = (String) row[12];
        Grants.Access granted = orgId == null ? (superuser ? grants.access(userId, true, 0) : Grants.Access.NONE) : grants.access(userId, superuser, orgId);
        Set<String> perms = granted.permissions();
        if (perms.isEmpty() || orgId == null) {
            // removed from this organization (or it was deleted): continue in another organization of the user
            List<OrgService.Access> access = orgs.access(userId, superuser);
            if (!access.isEmpty()) {
                OrgService.Org next = access.get(0).org();
                db.sql("UPDATE {s}.bimba_gw_session SET org_id = ? WHERE id = ?").params(next.id(), id).update();
                if (orgId != null) {
                    audit(next.id(), username, "org.moved", next.name(), true, ip, Map.of("reason", "no access to the previous organization any more"));
                }
                orgId = next.id();
                orgSlug = next.slug();
                orgName = next.name();
                granted = grants.access(userId, superuser, orgId);
                perms = granted.permissions();
            }
        }
        if (perms.isEmpty()) {
            return null;                      // no role in any organization any more: the session ends
        }
        String first = (String) row[2];
        String last = (String) row[3];
        String name = (first + " " + last).trim();
        return new Principal(userId, username, name.isEmpty() ? username : name, first, last, (String) row[4], superuser,
                perms, granted.scopes(), (Boolean) row[9], id, orgId, orgSlug, orgName, (Long) row[13]);
    }

    /** The organizations the signed-in user can switch to, with their roles in each. */
    public List<Map<String, Object>> organizations(Principal p) {
        return orgs.access(p.userId(), p.superuser()).stream().map(OrgService.Access::json).toList();
    }

    /** Moves the session to another organization of the user; the next calls use their permissions there. */
    public void switchOrganization(Principal p, long orgId, String ip) throws ApiException {
        OrgService.Access to = orgs.access(p.userId(), p.superuser()).stream().filter(a -> a.org().id() == orgId).findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "You are not a member of that organization."));
        db.tx(() -> {
            db.sql("UPDATE {s}.bimba_gw_session SET org_id = ? WHERE id = ?").params(orgId, p.sessionId()).update();
            db.sql("INSERT INTO {s}.bimba_gw_user_profile (user_id, last_org_id) VALUES (?, ?)"
                    + " ON CONFLICT (user_id) DO UPDATE SET last_org_id = EXCLUDED.last_org_id").params(p.userId(), orgId).update();
            return null;
        });
        audit(orgId, p.username(), "org.switch", to.org().name(), true, ip, Map.of("from", p.orgName() == null ? "-" : p.orgName()));
    }

    public void logout(Principal p, String ip) {
        db.sql("UPDATE {s}.bimba_gw_session SET revoked_at = now() WHERE id = ?").param(p.sessionId()).update();
        audit(p, "logout", p.username(), true, ip, Map.of());
    }

    // ------------------------------------------------------------------ profile

    /** Name only: the e-mail address is the sign-in and is changed by an administrator (Users). */
    public void updateProfile(Principal p, JsonObject body, String ip) throws ApiException {
        db.sql("UPDATE {s}.auth_user SET first_name = ?, last_name = ? WHERE id = ?")
                .params(text(body, "firstName", 150), text(body, "lastName", 150), p.userId()).update();
        audit(p, "profile.update", p.username(), true, ip, Map.of());
    }

    // ------------------------------------------------------------------ profile photo

    public static final int AVATAR_MAX_BYTES = 512 * 1024;

    /** the photo's address, versioned so browsers may cache it; null without a photo */
    static String avatarUrl(long userId, Long version) {
        return version == null ? null : "/api/gw/avatars/" + userId + "?v=" + version;
    }

    /** PNG, JPEG or WebP (checked by content, not by name), at most 512 KB. SVG is refused: it can carry script. */
    public void setAvatar(Principal p, byte[] image, String ip) throws ApiException {
        if (image == null || image.length == 0) {
            throw new ApiException(400, "empty", "Choose a picture.");
        }
        if (image.length > AVATAR_MAX_BYTES) {
            throw new ApiException(413, "too_large", "The photo can be at most 512 KB.");
        }
        String type = imageType(image);
        if (type == null) {
            throw new ApiException(400, "type", "Use a PNG, JPEG or WebP picture.");
        }
        db.sql("INSERT INTO {s}.bimba_gw_user_profile (user_id, avatar, avatar_type, avatar_updated_at) VALUES (?, ?, ?, now())"
                        + " ON CONFLICT (user_id) DO UPDATE SET avatar = EXCLUDED.avatar, avatar_type = EXCLUDED.avatar_type, avatar_updated_at = now()")
                .params(p.userId(), image, type).update();
        audit(p, "profile.photo", p.username(), true, ip, Map.of("bytes", image.length, "type", type));
    }

    public void removeAvatar(Principal p, String ip) {
        db.sql("UPDATE {s}.bimba_gw_user_profile SET avatar = NULL, avatar_type = '', avatar_updated_at = now() WHERE user_id = ?")
                .param(p.userId()).update();
        audit(p, "profile.photo", p.username(), true, ip, Map.of("removed", true));
    }

    /** {bytes, content type} of a user's photo for someone who may see it: themselves, members of the same organization, platform admins. */
    public Object[] avatar(Principal viewer, long userId) throws ApiException {
        boolean allowed = viewer.userId() == userId || viewer.superuser()
                || viewer.orgId() != null && db.sql("SELECT count(*) FROM {s}.bimba_org_member WHERE org_id = ? AND user_id = ?")
                .params(viewer.orgId(), userId).query(Long.class).single() > 0;
        Object[] r = !allowed ? null : db.sql("SELECT avatar, avatar_type FROM {s}.bimba_gw_user_profile WHERE user_id = ? AND avatar IS NOT NULL")
                .param(userId).query((rs, n) -> new Object[]{rs.getBytes(1), rs.getString(2)}).optional().orElse(null);
        if (r == null) {
            throw new ApiException(404, "not_found", "No photo.");
        }
        return r;
    }

    static String imageType(byte[] b) {
        if (b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    // ------------------------------------------------------------------ password

    public void changePassword(Principal p, String current, String next, String ip) throws ApiException {
        String stored = db.sql("SELECT password FROM {s}.auth_user WHERE id = ?").param(p.userId()).query(String.class).single();
        if (!Passwords.matches(current == null ? "" : current, stored)) {
            audit(p, "password.change", p.username(), false, ip, Map.of("reason", "wrong current password"));
            throw new ApiException(400, "current", "The current password is not correct.");
        }
        List<String> problems = Passwords.check(next, localPart(p.email()), sec());
        if (next != null && next.equals(current)) {
            problems.add("must differ from the current password");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(400, "policy", "The new password needs: " + String.join("; ", problems) + ".");
        }
        String hash = Passwords.hash(next);
        db.tx(() -> {
            db.sql("UPDATE {s}.auth_user SET password = ? WHERE id = ?").params(hash, p.userId()).update();
            db.sql("INSERT INTO {s}.bimba_gw_user_profile (user_id, must_change_password, password_changed_at) VALUES (?, false, now())"
                    + " ON CONFLICT (user_id) DO UPDATE SET must_change_password = false, password_changed_at = now()").param(p.userId()).update();
            // a changed password signs out the account's other browsers
            db.sql("UPDATE {s}.bimba_gw_session SET revoked_at = now() WHERE user_id = ? AND id <> ? AND revoked_at IS NULL")
                    .params(p.userId(), p.sessionId()).update();
            return null;
        });
        audit(p, "password.change", p.username(), true, ip, Map.of());
    }

    public List<Map<String, Object>> sessions(Principal p) {
        return db.sql("SELECT id, created_at, last_seen_at, expires_at, ip, user_agent FROM {s}.bimba_gw_session"
                        + " WHERE user_id = ? AND revoked_at IS NULL AND expires_at > now()"
                        + " AND last_seen_at > now() - (? * interval '1 minute') ORDER BY last_seen_at DESC")
                .params(p.userId(), sec().idleMinutes)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1).substring(0, 16));
                    m.put("current", rs.getString(1).equals(p.sessionId()));
                    m.put("createdAt", iso(rs.getTimestamp(2)));
                    m.put("lastSeenAt", iso(rs.getTimestamp(3)));
                    m.put("expiresAt", iso(rs.getTimestamp(4)));
                    m.put("ip", rs.getString(5));
                    m.put("userAgent", rs.getString(6));
                    return m;
                }).list();
    }

    /** Ends the user's own session(s): one by its short id, or all except the current one (id = null). */
    public int endSessions(Principal p, String shortId, String ip) {
        int n = shortId == null
                ? db.sql("UPDATE {s}.bimba_gw_session SET revoked_at = now() WHERE user_id = ? AND id <> ? AND revoked_at IS NULL")
                        .params(p.userId(), p.sessionId()).update()
                : db.sql("UPDATE {s}.bimba_gw_session SET revoked_at = now() WHERE user_id = ? AND id LIKE ? AND revoked_at IS NULL")
                        .params(p.userId(), shortId.replaceAll("[^0-9a-f]", "") + "%").update();
        audit(p, "session.end", p.username(), true, ip, Map.of("sessions", n, "scope", shortId == null ? "others" : "one"));
        return n;
    }

    // ------------------------------------------------------------------ users (members of the session's organization)

    /**
     * The members of the session's organization with their roles there. otherOrganizations = how many other
     * organizations the account also belongs to (their names only for platform admins); accountEditable = whether
     * this actor may change the account itself (name, e-mail, enabled, password), which a shared account only allows
     * platform admins: an organization's admins must not take over an account another organization also uses.
     */
    public List<Map<String, Object>> users(Principal actor) throws ApiException {
        long org = actor.org();
        // accounts made outside Setu (Django) have no profile yet; the profile holds the user's public id
        db.sql("INSERT INTO {s}.bimba_gw_user_profile (user_id) SELECT m.user_id FROM {s}.bimba_org_member m WHERE m.org_id = ?"
                + " ON CONFLICT (user_id) DO NOTHING").param(org).update();
        List<Map<String, Object>> list = db.sql("SELECT u.id, u.username, u.first_name, u.last_name, u.email, u.is_active, u.is_superuser,"
                        + " u.last_login, u.date_joined, COALESCE(p.must_change_password, false),"
                        + " (p.locked_until IS NOT NULL AND p.locked_until > now()), p.last_login_ip,"
                        + " COALESCE((SELECT json_agg(json_build_object('id', r.id, 'name', r.name) ORDER BY r.name) FROM {s}.bimba_gw_user_role ur"
                        + "   JOIN {s}.bimba_gw_role r ON r.id = ur.role_id WHERE ur.user_id = u.id AND ur.org_id = m.org_id)::text, '[]'),"
                        + " (SELECT count(*) FROM {s}.bimba_gw_session s WHERE s.user_id = u.id AND s.revoked_at IS NULL AND s.expires_at > now()"
                        + "   AND s.last_seen_at > now() - (? * interval '1 minute')),"
                        + " COALESCE((SELECT json_agg(o.name ORDER BY o.name) FROM {s}.bimba_org_member m2 JOIN {s}.bimba_org o ON o.id = m2.org_id"
                        + "   WHERE m2.user_id = u.id AND m2.org_id <> m.org_id)::text, '[]'),"
                        + " CASE WHEN p.avatar IS NULL THEN NULL ELSE (EXTRACT(EPOCH FROM p.avatar_updated_at) * 1000)::bigint END, p.uid"
                        + " FROM {s}.bimba_org_member m JOIN {s}.auth_user u ON u.id = m.user_id"
                        + " LEFT JOIN {s}.bimba_gw_user_profile p ON p.user_id = u.id WHERE m.org_id = ? ORDER BY lower(u.email), u.username")
                .params(sec().idleMinutes, org)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("uid", rs.getString(17));
                    m.put("username", rs.getString(2));
                    m.put("firstName", rs.getString(3));
                    m.put("lastName", rs.getString(4));
                    m.put("email", rs.getString(5));
                    m.put("active", rs.getBoolean(6));
                    m.put("platformAdmin", rs.getBoolean(7));
                    m.put("lastLogin", iso(rs.getTimestamp(8)));
                    m.put("joined", iso(rs.getTimestamp(9)));
                    m.put("mustChangePassword", rs.getBoolean(10));
                    m.put("locked", rs.getBoolean(11));
                    m.put("lastLoginIp", rs.getString(12));
                    m.put("roles", Json.toJava(rs.getString(13)));
                    m.put("activeSessions", rs.getLong(14));
                    List<?> others = (List<?>) Json.toJava(rs.getString(15));
                    m.put("otherOrganizations", others.size());
                    if (actor.superuser()) {
                        m.put("otherOrganizationNames", others);
                    }
                    m.put("accountEditable", actor.superuser() || (others.isEmpty() && !rs.getBoolean(7)));
                    m.put("avatarUrl", avatarUrl(rs.getLong(1), rs.getObject(16, Long.class)));
                    return m;
                }).list();
        addAccess(list, org);
        return list;
    }

    /**
     * Each member's roles with their scopes, and what they may do in the organization: every permission with where it
     * holds (null = everywhere) and the roles that give it; dutyConflicts = operational permissions next to the Auditor role.
     */
    private void addAccess(List<Map<String, Object>> members, long org) {
        Map<Long, List<Grants.Assignment>> all = grants.assignments(org);
        List<Scope> scopes = new ArrayList<>();
        all.values().forEach(l -> l.forEach(a -> scopes.add(a.scope())));
        Map<String, String> agentNames = agentNames(org, scopes);
        for (Map<String, Object> m : members) {
            List<Grants.Assignment> as = all.getOrDefault((Long) m.get("id"), List.of());
            List<Map<String, Object>> roles = new ArrayList<>();
            for (Grants.Assignment a : as) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", a.roleId());
                r.put("name", a.roleName());
                r.put("scope", a.scope() == null ? null : a.scope().json());
                r.put("scopeText", a.scope() == null ? null : scopeText(a.scope(), agentNames));
                roles.add(r);
            }
            m.put("roles", roles);
            boolean platform = Boolean.TRUE.equals(m.get("platformAdmin"));
            Grants.Access access = platform ? grants.access((Long) m.get("id"), true, org) : Grants.combine(as);
            List<Map<String, Object>> perms = new ArrayList<>();
            for (String k : access.permissions()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("key", k);
                Scope sc = access.scope(k);
                x.put("scope", sc == null ? null : sc.json());
                x.put("scopeText", sc == null ? null : scopeText(sc, agentNames));
                x.put("via", platform ? List.of("Platform admin") : as.stream().filter(a -> a.effective().contains(k)).map(Grants.Assignment::roleName).toList());
                perms.add(x);
            }
            m.put("permissions", perms);
            m.put("dutyConflicts", platform ? List.of() : List.copyOf(Grants.auditorConflicts(as)));
        }
    }

    /**
     * Adds a member to the session's organization: a new account (temporary password), or with existing=true an account
     * that already exists (e.g. a member of another organization), which then has roles in both.
     */
    public Map<String, Object> createUser(Principal actor, JsonObject body, String ip) throws ApiException {
        long org = actor.org();
        String email = text(body, "email", 254).toLowerCase(Locale.ROOT);
        Map<Long, Scope> roles = assignmentsOf(body, org);
        checkGrantable(actor, roles, Map.of(), org);
        checkDuties(roles, org);
        if (body.has("existing") && body.get("existing").getAsBoolean()) {
            Long uid = db.sql("SELECT id FROM {s}.auth_user WHERE email <> '' AND lower(email) = ?").param(email)
                    .query(Long.class).optional().orElse(null);
            if (uid == null) {
                throw new ApiException(404, "not_found", "There is no account with the e-mail address " + email + ". Create a new user instead.");
            }
            long members = db.sql("SELECT count(*) FROM {s}.bimba_org_member WHERE org_id = ? AND user_id = ?").params(org, uid)
                    .query(Long.class).single();
            if (members > 0) {
                throw new ApiException(409, "member", email + " is already a member of " + actor.orgName() + ".");
            }
            db.tx(() -> {
                setRoles(uid, org, roles);
                return null;
            });
            audit(actor, "user.add", email, true, ip, Map.of("roles", describe(roles, org)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", uid);
            out.put("email", email);
            out.put("added", true);
            return out;
        }
        checkEmail(email, null);
        String username = email;                         // the internal username of a Setu account is its e-mail
        String temp = Passwords.temporary();
        String hash = Passwords.hash(temp);
        long id;
        try {
            id = db.tx(() -> {
                long uid = db.sql("INSERT INTO {s}.auth_user (password, last_login, is_superuser, username, first_name, last_name,"
                                + " email, is_staff, is_active, date_joined) VALUES (?, NULL, false, ?, ?, ?, ?, false, true, now()) RETURNING id")
                        .params(hash, username, text(body, "firstName", 150), text(body, "lastName", 150), email)
                        .query(Long.class).single();
                db.sql("INSERT INTO {s}.bimba_gw_user_profile (user_id, must_change_password, last_org_id) VALUES (?, true, ?)")
                        .params(uid, org).update();
                setRoles(uid, org, roles);
                return uid;
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "exists", "An account with the e-mail address " + email + " already exists. To give it access to "
                    + actor.orgName() + ", add it as an existing account.");
        }
        audit(actor, "user.create", email, true, ip, Map.of("roles", describe(roles, org)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("email", email);
        out.put("temporaryPassword", temp);
        return out;
    }

    public void updateUser(Principal actor, long id, JsonObject body, String ip) throws ApiException {
        long org = actor.org();
        Member target = member(org, id);
        guardPlatformAdmin(actor, target.platformAdmin());
        String first = body.has("firstName") ? text(body, "firstName", 150) : target.firstName();
        String last = body.has("lastName") ? text(body, "lastName", 150) : target.lastName();
        String email = body.has("email") ? text(body, "email", 254).toLowerCase(Locale.ROOT) : target.email();
        boolean emailChanged = !email.equalsIgnoreCase(target.email());
        if (emailChanged) {
            checkEmail(email, id);
        }
        boolean active = !body.has("active") || body.get("active").getAsBoolean();
        boolean accountChanged = !first.equals(target.firstName()) || !last.equals(target.lastName()) || emailChanged
                || active != target.active();
        if (accountChanged) {
            requireAccount(actor, target);
        }
        if (!active && actor.userId() == id) {
            throw new ApiException(400, "self", "You cannot disable your own account.");
        }
        Map<Long, Scope> current = currentOf(id, org);
        // without roles in the request the member keeps theirs
        Map<Long, Scope> roles = body.has("roles") || body.has("roleIds") ? assignmentsOf(body, org) : current;
        List<String> before = describe(current, org);
        List<String> after = describe(roles, org);
        // grants checked only for assignments being added or changed, so an admin can still edit someone with roles they cannot grant
        if (actor.userId() == id && !actor.superuser()) {
            checkSelfGrant(current, roles, org);
            if (!Grants.combine(withScopes(roles)).can(Permission.USERS_MANAGE.key)) {
                throw new ApiException(400, "self", "You cannot remove your own user management permission.");
            }
        }
        checkGrantable(actor, roles, current, org);
        checkDuties(roles, org);
        db.tx(() -> {
            if (accountChanged) {
                db.sql("UPDATE {s}.auth_user SET first_name = ?, last_name = ?, email = ?, is_active = ? WHERE id = ?")
                        .params(first, last, email, active, id).update();
            }
            if (emailChanged) {
                // a new sign-in address: the internal username follows it, and open sessions end
                db.sql("UPDATE {s}.auth_user SET username = ? WHERE id = ?").params(email, id).update();
                db.sql("UPDATE {s}.bimba_gw_session SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL").param(id).update();
            }
            setRoles(id, org, roles);
            if (!active) {
                db.sql("UPDATE {s}.bimba_gw_session SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL").param(id).update();
            }
            return null;
        });
        Map<String, Object> detail = new LinkedHashMap<>();
        if (!before.equals(after)) {
            detail.put("rolesBefore", before);
            detail.put("rolesAfter", after);
        }
        if (active != target.active()) {
            detail.put("active", active);
        }
        if (emailChanged) {
            detail.put("emailBefore", target.email());
            detail.put("emailAfter", email);
        }
        audit(actor, "user.update", target.username(), true, ip, detail);
    }

    /** Takes the member out of the session's organization (their roles here end; the account and other memberships stay). */
    public void removeMember(Principal actor, long id, String ip) throws ApiException {
        long org = actor.org();
        Member target = member(org, id);
        guardPlatformAdmin(actor, target.platformAdmin());
        if (actor.userId() == id) {
            throw new ApiException(400, "self", "You cannot remove yourself from the organization.");
        }
        List<String> had = describe(currentOf(id, org), org);
        db.tx(() -> {
            db.sql("DELETE FROM {s}.bimba_gw_user_role WHERE user_id = ? AND org_id = ?").params(id, org).update();
            grants.saveScopes(org, id, Map.of());
            db.sql("DELETE FROM {s}.bimba_org_member WHERE user_id = ? AND org_id = ?").params(id, org).update();
            return null;
        });
        // their sessions working in this organization move to another of their organizations at the next request
        audit(actor, "user.remove", target.username(), true, ip, Map.of("roles", had));
    }

    public String resetPassword(Principal actor, long id, String ip) throws ApiException {
        Member target = member(actor.org(), id);
        guardPlatformAdmin(actor, target.platformAdmin());
        requireAccount(actor, target);
        if (actor.userId() == id) {
            throw new ApiException(400, "self", "Change your own password on your profile page.");
        }
        String temp = Passwords.temporary();
        String hash = Passwords.hash(temp);
        db.tx(() -> {
            db.sql("UPDATE {s}.auth_user SET password = ? WHERE id = ?").params(hash, id).update();
            db.sql("INSERT INTO {s}.bimba_gw_user_profile (user_id, must_change_password, failed_logins) VALUES (?, true, 0)"
                    + " ON CONFLICT (user_id) DO UPDATE SET must_change_password = true, failed_logins = 0, locked_until = NULL").param(id).update();
            db.sql("UPDATE {s}.bimba_gw_session SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL").param(id).update();
            return null;
        });
        audit(actor, "user.reset_password", target.username(), true, ip, Map.of());
        return temp;
    }

    public void unlock(Principal actor, long id, String ip) throws ApiException {
        Member target = member(actor.org(), id);
        guardPlatformAdmin(actor, target.platformAdmin());
        db.sql("UPDATE {s}.bimba_gw_user_profile SET failed_logins = 0, locked_until = NULL WHERE user_id = ?").param(id).update();
        audit(actor, "user.unlock", target.username(), true, ip, Map.of());
    }

    public int signOutUser(Principal actor, long id, String ip) throws ApiException {
        Member target = member(actor.org(), id);
        guardPlatformAdmin(actor, target.platformAdmin());
        requireAccount(actor, target);
        // the actor's own current session stays open when they sign themselves out elsewhere
        String keep = actor.userId() == id ? actor.sessionId() : "";
        int n = db.sql("UPDATE {s}.bimba_gw_session SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL AND id <> ?")
                .params(id, keep).update();
        audit(actor, "user.sign_out", target.username(), true, ip, Map.of("sessions", n));
        return n;
    }

    /** a member of the organization as user management sees it; otherOrgs = memberships elsewhere */
    private record Member(String username, boolean platformAdmin, boolean active, String firstName, String lastName, String email,
                          long otherOrgs) {
    }

    /** The member of this organization; any other account is "not found" (other organizations' members stay invisible). */
    private Member member(long org, long id) throws ApiException {
        return db.sql("SELECT u.username, u.is_superuser, u.is_active, u.first_name, u.last_name, u.email,"
                        + " (SELECT count(*) FROM {s}.bimba_org_member m2 WHERE m2.user_id = u.id AND m2.org_id <> m.org_id)"
                        + " FROM {s}.bimba_org_member m JOIN {s}.auth_user u ON u.id = m.user_id WHERE m.org_id = ? AND u.id = ?")
                .params(org, id)
                .query((rs, n) -> new Member(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getLong(7)))
                .optional().orElseThrow(() -> new ApiException(404, "not_found", "User not found in this organization."));
    }

    private static void guardPlatformAdmin(Principal actor, boolean targetIsPlatformAdmin) throws ApiException {
        if (targetIsPlatformAdmin && !actor.superuser()) {
            throw new ApiException(403, "platform_admin", "Only platform administrators can change a platform administrator's account.");
        }
    }

    /** the account itself (name, e-mail, enabled, password, sessions) of someone who is also in other organizations: platform admins only */
    private static void requireAccount(Principal actor, Member target) throws ApiException {
        if (target.otherOrgs() > 0 && !actor.superuser()) {
            throw new ApiException(403, "shared_account", target.username() + " also belongs to another organization, so only a platform admin can"
                    + " change the account itself (name, e-mail, password, enabled). You can change their roles here or remove them from "
                    + actor.orgName() + ".");
        }
    }

    // ------------------------------------------------------------------ roles (built-in = shared, custom = this organization's)

    /**
     * The roles this organization can use. persona = the BRD persona a built-in role stands for; holders (for those who
     * may see members) = who has the role here and where.
     */
    public List<Map<String, Object>> roles(Principal actor) throws ApiException {
        long org = actor.org();
        Map<Long, List<Map<String, Object>>> holders = new LinkedHashMap<>();
        boolean seeMembers = actor.can(Permission.USERS_VIEW) || actor.can(Permission.USERS_MANAGE);
        if (seeMembers) {
            Map<Long, List<Grants.Assignment>> all = grants.assignments(org);
            List<Scope> scopes = new ArrayList<>();
            all.values().forEach(l -> l.forEach(a -> scopes.add(a.scope())));
            Map<String, String> agentNames = agentNames(org, scopes);
            db.sql("SELECT u.id, u.email, trim(u.first_name || ' ' || u.last_name) FROM {s}.bimba_org_member m JOIN {s}.auth_user u ON u.id = m.user_id"
                            + " WHERE m.org_id = ? ORDER BY lower(u.email)").param(org)
                    .query((rs, n) -> {
                        for (Grants.Assignment a : all.getOrDefault(rs.getLong(1), List.of())) {
                            Map<String, Object> h = new LinkedHashMap<>();
                            h.put("id", rs.getLong(1));
                            h.put("email", rs.getString(2));
                            h.put("name", rs.getString(3).isEmpty() ? rs.getString(2) : rs.getString(3));
                            h.put("scopeText", a.scope() == null ? null : scopeText(a.scope(), agentNames));
                            holders.computeIfAbsent(a.roleId(), k -> new ArrayList<>()).add(h);
                        }
                        return null;
                    }).list();
        }
        return db.sql("SELECT r.id, r.name, r.description, r.permissions::text, r.builtin, r.org_id IS NULL,"
                        + " (SELECT count(*) FROM {s}.bimba_gw_user_role ur WHERE ur.role_id = r.id AND ur.org_id = ?), r.uid FROM {s}.bimba_gw_role r"
                        + " WHERE r.org_id IS NULL OR r.org_id = ? ORDER BY r.builtin DESC, r.org_id IS NOT NULL, lower(r.name)")
                .params(org, org)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("uid", rs.getString(8));
                    m.put("name", rs.getString(2));
                    m.put("description", rs.getString(3));
                    boolean locked = rs.getBoolean(5) && ADMIN_ROLE.equals(rs.getString(2));
                    boolean shared = rs.getBoolean(6);
                    m.put("permissions", locked ? Arrays.stream(Permission.values()).map(p -> p.key).toList() : Json.toJava(rs.getString(4)));
                    m.put("builtin", rs.getBoolean(5));
                    m.put("locked", locked);
                    m.put("shared", shared);
                    m.put("editable", !locked && (!shared || actor.superuser()));
                    m.put("members", rs.getLong(7));
                    RoleCatalog.Persona persona = shared ? RoleCatalog.persona(rs.getString(2), rs.getBoolean(5)) : null;
                    m.put("persona", persona == null ? null : RoleCatalog.json(persona));
                    m.put("auditor", rs.getBoolean(5) && shared && RoleCatalog.AUDITOR_ROLE.equalsIgnoreCase(rs.getString(2)));
                    if (seeMembers) {
                        m.put("holders", holders.getOrDefault(rs.getLong(1), List.of()));
                    }
                    return m;
                }).list();
    }

    public Map<String, Object> saveRole(Principal actor, Long id, JsonObject body, String ip) throws ApiException {
        long org = actor.org();
        String name = text(body, "name", 80);
        if (name.isEmpty()) {
            throw new ApiException(400, "name", "Give the role a name.");
        }
        String description = text(body, "description", 300);
        List<String> perms = new ArrayList<>();
        Set<String> had = id == null ? Set.of() : db.sql("SELECT permissions::text FROM {s}.bimba_gw_role WHERE id = ? AND (org_id IS NULL OR org_id = ?)")
                .params(id, org).query(String.class).optional().map(Grants::keys).orElse(Set.of());
        if (body.has("permissions") && body.get("permissions").isJsonArray()) {
            for (JsonElement e : body.getAsJsonArray("permissions")) {
                Permission p = Permission.of(e.getAsString());
                if (p == null) {
                    throw new ApiException(400, "permission", "Unknown permission " + e.getAsString());
                }
                // only what is being added is checked, so a role can still be edited by someone who lacks part of it
                if (!had.contains(p.key) && !actor.can(p)) {
                    throw new ApiException(403, "grant", "You cannot grant a permission you do not have: " + p.label);
                }
                if (!had.contains(p.key) && p.scoped && !actor.everywhere(p)) {
                    throw new ApiException(403, "grant_scope", "You hold \"" + p.label + "\" only for some sites, site agents or machines, so you cannot"
                            + " add it to a role (a role holds wherever it is assigned).");
                }
                if (!perms.contains(p.key)) {
                    perms.add(p.key);
                }
            }
        }
        // the rest of the permissions sort like the catalog
        perms.sort(java.util.Comparator.comparingInt(k -> Permission.of(k).ordinal()));
        // names are unique among the roles this organization sees (the shared built-in ones and its own)
        long clash = db.sql("SELECT count(*) FROM {s}.bimba_gw_role WHERE lower(name) = lower(?) AND (org_id IS NULL OR org_id = ?) AND id <> ?")
                .params(name, org, id == null ? -1L : id).query(Long.class).single();
        if (clash > 0) {
            throw new ApiException(409, "exists", "A role called " + name + " already exists.");
        }
        String permsJson = Json.GSON.toJson(perms);
        try {
            if (id == null) {
                long newId = db.sql("INSERT INTO {s}.bimba_gw_role (org_id, name, description, permissions, builtin, created_at)"
                                + " VALUES (?, ?, ?, ?::jsonb, false, now()) RETURNING id")
                        .params(org, name, description, permsJson).query(Long.class).single();
                audit(actor, "role.create", name, true, ip, Map.of("permissions", perms));
                return Map.of("id", newId);
            }
            Object[] old = db.sql("SELECT name, builtin, permissions::text, org_id IS NULL FROM {s}.bimba_gw_role WHERE id = ?"
                            + " AND (org_id IS NULL OR org_id = ?)").params(id, org)
                    .query((rs, n) -> new Object[]{rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getBoolean(4)}).optional().orElse(null);
            if (old == null) {
                throw new ApiException(404, "not_found", "Role not found.");
            }
            if ((Boolean) old[1] && ADMIN_ROLE.equals(old[0])) {
                throw new ApiException(400, "locked", "The built-in Administrator role always has every permission and cannot be changed.");
            }
            if ((Boolean) old[3] && !actor.superuser()) {
                throw new ApiException(403, "shared_role", "Built-in roles are shared by every organization, so only a platform admin can change them."
                        + " Create a role of your own instead.");
            }
            if ((Boolean) old[1] && !old[0].equals(name)) {
                throw new ApiException(400, "builtin", "Built-in roles keep their name.");
            }
            checkRoleDuties(id, (String) old[0], (Boolean) old[1] && (Boolean) old[3], perms);
            db.sql("UPDATE {s}.bimba_gw_role SET name = ?, description = ?, permissions = ?::jsonb WHERE id = ?")
                    .params(name, description, permsJson, id).update();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("permissionsBefore", Json.toJava((String) old[2]));
            detail.put("permissionsAfter", perms);
            if ((Boolean) old[3]) {
                detail.put("scope", "every organization");
            }
            audit(actor, "role.update", name, true, ip, detail);
            return Map.of("id", id);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "exists", "A role called " + name + " already exists.");
        }
    }

    public void deleteRole(Principal actor, long id, String ip) throws ApiException {
        long org = actor.org();
        Object[] old = db.sql("SELECT name, builtin, org_id IS NULL FROM {s}.bimba_gw_role WHERE id = ? AND (org_id IS NULL OR org_id = ?)")
                .params(id, org)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)}).optional().orElse(null);
        if (old == null) {
            throw new ApiException(404, "not_found", "Role not found.");
        }
        if ((Boolean) old[1]) {
            throw new ApiException(400, "builtin", "Built-in roles cannot be deleted.");
        }
        if ((Boolean) old[2] && !actor.superuser()) {
            throw new ApiException(403, "shared_role", "This role is shared by every organization; only a platform admin can delete it.");
        }
        int members = db.tx(() -> {
            int n = db.sql("DELETE FROM {s}.bimba_gw_user_role WHERE role_id = ?").param(id).update();
            db.sql("DELETE FROM {s}.bimba_gw_role WHERE id = ?").param(id).update();
            return n;
        });
        audit(actor, "role.delete", (String) old[0], true, ip, Map.of("removedFromUsers", members));
    }

    /**
     * The role assignments of the request, each a role this organization can use, with its scope (null = the whole
     * organization): roles [{id, scope?}] or, from older consoles, roleIds [id]. A scope on a role without scoped
     * permissions would change nothing, so it is dropped; the site agents of a scope must be the organization's.
     */
    private Map<Long, Scope> assignmentsOf(JsonObject body, long org) throws ApiException {
        Map<Long, Scope> out = new LinkedHashMap<>();
        if (body.has("roles") && body.get("roles").isJsonArray()) {
            for (JsonElement e : body.getAsJsonArray("roles")) {
                if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
                    JsonElement se = e.getAsJsonObject().get("scope");
                    Scope sc = Scope.parse(se);
                    // "only some places" with none chosen must not turn into the whole organization
                    if (se != null && se.isJsonObject() && sc == null) {
                        throw new ApiException(400, "scope", "Choose at least one site, site agent or machine for each role given only for some places.");
                    }
                    out.putIfAbsent(e.getAsJsonObject().get("id").getAsLong(), sc);
                } else if (e.isJsonPrimitive()) {
                    out.putIfAbsent(e.getAsLong(), null);
                }
            }
        } else if (body.has("roleIds") && body.get("roleIds").isJsonArray()) {
            for (JsonElement e : body.getAsJsonArray("roleIds")) {
                out.putIfAbsent(e.getAsLong(), null);
            }
        }
        if (out.isEmpty()) {
            return out;
        }
        long known = db.sql("SELECT count(*) FROM {s}.bimba_gw_role WHERE id = ANY (?::bigint[]) AND (org_id IS NULL OR org_id = ?)")
                .params(out.keySet().toArray(new Long[0]), org).query(Long.class).single();
        if (known != out.size()) {
            throw new ApiException(400, "role", "Unknown role.");
        }
        Map<Long, Grants.Assignment> defs = grants.roles(out.keySet());
        Map<String, String> agents = null;
        for (Map.Entry<Long, Scope> e : out.entrySet()) {
            Scope sc = e.getValue();
            if (sc == null) {
                continue;
            }
            if (defs.get(e.getKey()).effective().stream().map(Permission::of).noneMatch(p -> p != null && p.scoped)) {
                e.setValue(null);
                continue;
            }
            if (agents == null) {
                agents = grants.agentSites(org);
            }
            for (String a : sc.agents()) {
                if (!agents.containsKey(a)) {
                    throw new ApiException(400, "scope", "The scope names a site agent that is not in this organization.");
                }
            }
        }
        return out;
    }

    /** the member's role assignments in the organization: role id -> scope (null = everywhere) */
    private Map<Long, Scope> currentOf(long userId, long org) {
        Map<Long, Scope> out = new LinkedHashMap<>();
        grants.assignments(userId, org).forEach(a -> out.put(a.roleId(), a.scope()));
        return out;
    }

    private List<Grants.Assignment> withScopes(Map<Long, Scope> roles) {
        Map<Long, Grants.Assignment> defs = grants.roles(roles.keySet());
        List<Grants.Assignment> out = new ArrayList<>();
        roles.forEach((id, sc) -> {
            Grants.Assignment a = defs.get(id);
            if (a != null) {
                out.add(a.withScope(sc));
            }
        });
        return out;
    }

    /** "Supervisor (sites: Plant A; machines: Press 1)" per assignment, for the audit log */
    private List<String> describe(Map<Long, Scope> roles, long org) {
        Map<String, String> agentNames = agentNames(org, roles.values());
        List<String> out = new ArrayList<>();
        for (Grants.Assignment a : withScopes(roles)) {
            out.add(a.roleName() + (a.scope() == null ? "" : " (" + scopeText(a.scope(), agentNames) + ")"));
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** the display names of the organization's site agents, when any scope names agents */
    private Map<String, String> agentNames(long org, java.util.Collection<Scope> scopes) {
        Map<String, String> names = new LinkedHashMap<>();
        if (scopes.stream().anyMatch(sc -> sc != null && !sc.agents().isEmpty())) {
            db.sql("SELECT id::text, display_name FROM {s}.bimba_agent WHERE org_id = ?").param(org)
                    .query((rs, n) -> names.put(rs.getString(1), rs.getString(2))).list();
        }
        return names;
    }

    static String scopeText(Scope sc, Map<String, String> agentNames) {
        List<String> parts = new ArrayList<>();
        if (!sc.sites().isEmpty()) {
            parts.add("sites: " + String.join(", ", sc.sites()));
        }
        if (!sc.agents().isEmpty()) {
            parts.add("site agents: " + String.join(", ", sc.agents().stream().map(a -> agentNames.getOrDefault(a, a)).toList()));
        }
        if (!sc.machines().isEmpty()) {
            parts.add("machines: " + String.join(", ", sc.machines()));
        }
        return String.join("; ", parts);
    }

    /**
     * Nobody can hand out a permission they do not have (no escalation through user management); someone who holds a
     * scoped permission only for some places can give it only for places inside their own scope. Only assignments being
     * added or changed are checked.
     */
    private void checkGrantable(Principal actor, Map<Long, Scope> next, Map<Long, Scope> current, long org) throws ApiException {
        Map<Long, Grants.Assignment> defs = grants.roles(next.keySet());
        for (Map.Entry<Long, Scope> e : next.entrySet()) {
            if (current.containsKey(e.getKey()) && java.util.Objects.equals(current.get(e.getKey()), e.getValue())) {
                continue;
            }
            Grants.Assignment a = defs.get(e.getKey());
            for (String k : a.effective()) {
                Permission p = Permission.of(k);
                if (!actor.can(p)) {
                    throw new ApiException(403, "grant", "You cannot assign a role that grants a permission you do not have: " + p.label);
                }
                Scope mine = p.scoped ? actor.scope(p) : null;
                if (mine != null && (e.getValue() == null || !within(e.getValue(), mine, org))) {
                    denied(actor, p, a.roleName(), "assigning a role beyond the actor's own scope");
                    throw new ApiException(403, "grant_scope", "You hold \"" + p.label + "\" only for some sites, site agents or machines, so you can give the role "
                            + a.roleName() + " only for places inside your own scope.");
                }
            }
        }
    }

    /**
     * Duty separation (BRD 4.5): nobody adds a role to themselves or widens where one of their roles holds, even a role
     * whose permissions they already have through another one. Another member who manages users has to do it, so nobody
     * makes themselves an approver. Taking roles away is fine (except one's own user management, checked separately).
     */
    private void checkSelfGrant(Map<Long, Scope> current, Map<Long, Scope> next, long org) throws ApiException {
        Map<Long, Grants.Assignment> defs = grants.roles(next.keySet());
        for (Map.Entry<Long, Scope> e : next.entrySet()) {
            boolean added = !current.containsKey(e.getKey());
            Scope was = current.get(e.getKey());
            boolean wider = !added && was != null && (e.getValue() == null || !within(e.getValue(), was, org));
            if (added || wider) {
                throw new ApiException(403, "self_grant", "Duty separation: you cannot " + (added ? "give yourself the role " : "widen where you hold the role ")
                        + defs.get(e.getKey()).roleName() + ". Another member who manages users has to do that.");
            }
        }
    }

    /** Duty separation (BRD 4.5): an Auditor holds no operational permission in the same organization. */
    private void checkDuties(Map<Long, Scope> roles, long org) throws ApiException {
        Set<String> c = Grants.auditorConflicts(withScopes(roles));
        if (!c.isEmpty()) {
            throw new ApiException(409, "duty", "Duty separation: an Auditor holds no operational permission in the same organization. Remove the "
                    + RoleCatalog.AUDITOR_ROLE + " role, or the roles that give: " + labels(c) + ".");
        }
    }

    /**
     * Duty separation when a role's permissions change: the built-in Auditor role takes no operational permission, and
     * no member anywhere may end up with an operational permission next to the Auditor role.
     */
    private void checkRoleDuties(long roleId, String name, boolean builtinShared, List<String> perms) throws ApiException {
        if (builtinShared && RoleCatalog.AUDITOR_ROLE.equalsIgnoreCase(name)) {
            List<String> op = perms.stream().filter(Grants.OPERATIONAL::contains).toList();
            if (!op.isEmpty()) {
                throw new ApiException(409, "duty", "Duty separation: the Auditor role holds no operational permission, so it cannot get: " + labels(op) + ".");
            }
        }
        List<String> who = new ArrayList<>();
        for (Long org : db.sql("SELECT DISTINCT org_id FROM {s}.bimba_gw_user_role WHERE role_id = ?").param(roleId).query(Long.class).list()) {
            Map<Long, List<Grants.Assignment>> all = grants.assignments(org);
            for (Map.Entry<Long, List<Grants.Assignment>> e : all.entrySet()) {
                List<Grants.Assignment> next = e.getValue().stream()
                        .map(a -> a.roleId() == roleId ? new Grants.Assignment(a.roleId(), a.roleName(), a.builtin(), new LinkedHashSet<>(perms), false, a.scope()) : a)
                        .toList();
                if (!Grants.auditorConflicts(next).isEmpty()) {
                    who.add(db.sql("SELECT email FROM {s}.auth_user WHERE id = ?").param(e.getKey()).query(String.class).single());
                }
            }
        }
        if (!who.isEmpty()) {
            throw new ApiException(409, "duty", "Duty separation: " + String.join(", ", who.stream().limit(3).toList())
                    + (who.size() > 3 ? " and " + (who.size() - 3) + " more" : "") + " hold this role next to the Auditor role, and an Auditor"
                    + " holds no operational permission. Change their roles first.");
        }
    }

    // ------------------------------------------------------------------ duty separation + access review

    private static Map<String, Object> rule(String id, String title, String detail, boolean enforced) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("detail", detail);
        m.put("enforced", enforced);
        return m;
    }

    /**
     * The duty separation rules (BRD 4.5): what Setu enforces, what is still to come, and the members who break a rule
     * today (possible only for data changed outside Setu, or from before the rule).
     */
    public Map<String, Object> duties(Principal actor) throws ApiException {
        List<Map<String, Object>> rules = List.of(
                rule("own-command", "No approving your own command",
                        "A machine command that needs approval waits for a second person; the one who asked for it cannot approve it.", true),
                rule("self-grant", "No giving yourself access",
                        "Nobody adds a role to themselves or widens where their roles hold, even with permissions they already have. Another"
                                + " member who manages users has to, so nobody can make themselves an approver.", true),
                rule("grant-limit", "No granting beyond your own access",
                        "Roles, permissions and scopes can only be given by someone who holds them, and only within their own scope.", true),
                rule("auditor", "Auditors stay independent",
                        "A member with the Auditor role holds no operational permission in the same organization: no machine commands, data"
                                + " sources, site agents, pipelines, destinations, quarantine or tag registry changes.", true),
                rule("scope", "Everyone acts only in their scope",
                        "Site agent, machine and command permissions given for some sites, site agents or machines hold only there,"
                                + " approvals included. Refusals are written to the audit log.", true),
                rule("safety-config", "Four-eyes on writable tags and limits",
                        "A change to a data source's Allow machine commands switch, or to a tag's writable flag, write limits, approval"
                                + " setting (and, while it is writable, its address, data type, scaling or unit) becomes a change request. It"
                                + " takes effect only after another person with Approve machine commands for that site agent and machine"
                                + " approves it; whoever proposed it cannot.", true),
                rule("break-glass", "Break-glass access",
                        "Planned: time-boxed emergency access with a reason, an alert to administrators and automatic expiry.", false),
                rule("step-up", "Fresh sign-in for sensitive actions",
                        "Planned with single sign-on and multi-factor sign-in: approvals, the kill switch and role changes ask again.", false));
        List<Map<String, Object>> violations = new ArrayList<>();
        if (actor.can(Permission.USERS_VIEW) || actor.can(Permission.USERS_MANAGE) || actor.can(Permission.ROLES_MANAGE)) {
            for (Map<String, Object> u : users(actor)) {
                List<?> c = (List<?>) u.get("dutyConflicts");
                if (!c.isEmpty()) {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("rule", "auditor");
                    v.put("userId", u.get("id"));
                    v.put("uid", u.get("uid"));
                    v.put("email", u.get("email"));
                    v.put("permissions", c);
                    violations.add(v);
                }
            }
        }
        List<Map<String, Object>> personas = new ArrayList<>();
        for (RoleCatalog.Persona p : RoleCatalog.PERSONAS) {
            Map<String, Object> m = RoleCatalog.json(p);
            m.put("role", p.role());
            personas.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rules", rules);
        m.put("violations", violations);
        m.put("personas", personas);
        m.put("personasNotShipped", RoleCatalog.NOT_SHIPPED);
        return m;
    }

    /** the places a role assignment can be limited to: the organization's sites, site agents and machines */
    public Map<String, Object> places(Principal actor) throws ApiException {
        long org = actor.org();
        List<Map<String, Object>> agents = db.sql("SELECT id::text, display_name, coalesce(site, '') FROM {s}.bimba_agent"
                        + " WHERE org_id = ? AND state = 'active' ORDER BY lower(display_name)").param(org)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("name", rs.getString(2));
                    m.put("site", rs.getString(3));
                    return m;
                }).list();
        List<String> sites = agents.stream().map(a -> ((String) a.get("site")).trim()).filter(x -> !x.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(() -> new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER))).stream().toList();
        List<Map<String, Object>> machines = new ArrayList<>();
        sources.getObject().machineAgents(org).forEach((name, agent) -> machines.add(Map.of("name", name, "agentId", agent == null ? "" : agent)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sites", sites);
        m.put("agents", agents);
        m.put("machines", machines);
        return m;
    }

    /** the access review (BRD FR-RBAC-09) as CSV: every member, their roles and scopes, what they may do, last sign-in */
    public String accessReviewCsv(Principal actor) throws ApiException {
        StringBuilder b = new StringBuilder("Organization,E-mail,Name,Enabled,Platform admin,Last sign-in,Roles,Permissions,Duty separation\r\n");
        for (Map<String, Object> u : users(actor)) {
            List<String> roles = new ArrayList<>();
            for (Object o : (List<?>) u.get("roles")) {
                Map<?, ?> r = (Map<?, ?>) o;
                roles.add(r.get("name") + (r.get("scopeText") == null ? "" : " (" + r.get("scopeText") + ")"));
            }
            List<String> perms = new ArrayList<>();
            for (Object o : (List<?>) u.get("permissions")) {
                Map<?, ?> x = (Map<?, ?>) o;
                perms.add(x.get("key") + (x.get("scopeText") == null ? "" : " (" + x.get("scopeText") + ")"));
            }
            String name = (u.get("firstName") + " " + u.get("lastName")).trim();
            List<?> conflicts = (List<?>) u.get("dutyConflicts");
            b.append(String.join(",", csv(actor.orgName()), csv((String) u.get("email")), csv(name),
                    Boolean.TRUE.equals(u.get("active")) ? "yes" : "no", Boolean.TRUE.equals(u.get("platformAdmin")) ? "yes" : "no",
                    csv(u.get("lastLogin") == null ? "" : (String) u.get("lastLogin")), csv(String.join("; ", roles)), csv(String.join("; ", perms)),
                    csv(conflicts.isEmpty() ? "" : "Auditor with: " + String.join("; ", conflicts.stream().map(String::valueOf).toList()))))
                    .append("\r\n");
        }
        return b.toString();
    }

    /** a CSV field; a leading = + - @ is neutralised so spreadsheets do not run it as a formula */
    private static String csv(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String labels(java.util.Collection<String> keys) {
        return String.join("; ", keys.stream().map(k -> Permission.of(k) == null ? k : Permission.of(k).label).toList());
    }

    /** is every place of inner also in outer (a site agent through its site, a machine through its agent) */
    private boolean within(Scope inner, Scope outer, long org) {
        Map<String, String> sites = grants.agentSites(org);
        for (String site : inner.sites()) {
            if (!Scope.has(outer.sites(), site)) {
                return false;
            }
        }
        for (String a : inner.agents()) {
            if (!outer.covers(a, sites.get(a), null)) {
                return false;
            }
        }
        if (!inner.machines().isEmpty()) {
            Map<String, String> machineAgents = sources.getObject().machineAgents(org);
            for (String m : inner.machines()) {
                String a = machineAgents.get(m);
                if (!outer.covers(a, a == null ? null : sites.get(a), m)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The user's roles in one organization with their scopes, and their membership there for BIMBA Operations and the
     * site agent installer: "admin" (may create enrollment tokens) when the roles allow enrolling site agents, else "member".
     */
    private void setRoles(long userId, long org, Map<Long, Scope> roles) {
        db.sql("DELETE FROM {s}.bimba_gw_user_role WHERE user_id = ? AND org_id = ?").params(userId, org).update();
        for (Long r : roles.keySet()) {
            db.sql("INSERT INTO {s}.bimba_gw_user_role (user_id, org_id, role_id) VALUES (?, ?, ?)").params(userId, org, r).update();
        }
        grants.saveScopes(org, userId, roles);
        String member = Grants.combine(withScopes(roles)).can(Permission.AGENTS_ENROLL.key) ? "admin" : "member";
        db.sql("INSERT INTO {s}.bimba_org_member (org_id, user_id, role) VALUES (?, ?, ?)"
                + " ON CONFLICT (org_id, user_id) DO UPDATE SET role = EXCLUDED.role").params(org, userId, member).update();
    }

    // ------------------------------------------------------------------ scoped checks

    /**
     * For calls about one site agent: the user holds the permission there (by the agent's site or id). An agent the
     * organization does not have passes, so the call itself answers "not found".
     */
    public void require(Principal p, Permission perm, String agentId) throws ApiException {
        if (!p.can(perm)) {
            throw new ApiException(403, "forbidden", "Your roles do not allow: " + perm.label + ".");
        }
        if (!allowsAgent(p, perm, agentId)) {
            throw outside(p, perm, "site agent " + agentId);
        }
    }

    /** does the user hold the permission for this site agent (an unknown agent counts as allowed: it answers "not found") */
    public boolean allowsAgent(Principal p, Permission perm, String agentId) throws ApiException {
        Scope sc = p.scope(perm);
        if (!p.can(perm) || sc == null) {
            return p.can(perm);
        }
        String site = grants.siteOf(p.org(), agentId);
        return site == null || sc.covers(agentId, site, null);
    }

    /** the refusal of a scoped call, written to the audit log (BRD FR-RBAC-11: denied decisions are audited too) */
    public ApiException outside(Principal p, Permission perm, String target) {
        denied(p, perm, target, "outside the scope of the user's roles");
        return new ApiException(403, "scope", "Your roles allow \"" + perm.label + "\" only for some sites, site agents or machines, not for this one.");
    }

    private void denied(Principal p, Permission perm, String target, String reason) {
        audit(p, "access.denied", target, false, "", Map.of("permission", perm.key, "reason", reason));
    }

    // ------------------------------------------------------------------ audit

    /** an event in the actor's current organization */
    public void audit(Principal p, String action, String target, boolean success, String ip, Map<String, ?> detail) {
        audit(p.orgId(), p.username(), action, target, success, ip, detail);
    }

    /** an event outside any organization (e.g. a refused sign-in) */
    public void audit(String actor, String action, String target, boolean success, String ip, Map<String, ?> detail) {
        audit((Long) null, actor, action, target, success, ip, detail);
    }

    public void audit(Long orgId, String actor, String action, String target, boolean success, String ip, Map<String, ?> detail) {
        try {
            db.sql("INSERT INTO {s}.bimba_gw_audit (org_id, at, actor, action, target, success, ip, detail)"
                            + " VALUES (?, now(), ?, ?, ?, ?, ?, ?::jsonb)")
                    .params(orgId, cut(actor, 150), action, cut(target, 200), success, cut(ip, 64),
                            Json.GSON.toJson(detail == null ? Map.of() : detail))
                    .update();
        } catch (DataAccessException e) {
            log.warn("audit write failed ({} {} {}): {}", actor, action, target, e.getMessage());
        }
    }

    /** The organization's events, newest first; platform admins can ask for every organization's (everyOrg). */
    public List<Map<String, Object>> audit(Principal actor, String query, Long beforeId, boolean everyOrg) throws ApiException {
        boolean all = everyOrg && actor.superuser();
        long org = all ? 0 : actor.org();
        String q = query == null ? "" : query.trim();
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return db.sql("SELECT a.id, a.at, a.actor, a.action, a.target, a.success, a.ip, a.detail::text, a.org_id, o.name"
                        + " FROM {s}.bimba_gw_audit a LEFT JOIN {s}.bimba_org o ON o.id = a.org_id"
                        + " WHERE (? OR a.org_id = ?) AND (a.actor ILIKE ? OR a.action ILIKE ? OR a.target ILIKE ? OR a.ip ILIKE ?)"
                        + " AND a.id < ? ORDER BY a.id DESC LIMIT 100")
                .params(all, org, like, like, like, like, beforeId == null ? Long.MAX_VALUE : beforeId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("at", iso(rs.getTimestamp(2)));
                    m.put("actor", rs.getString(3));
                    m.put("action", rs.getString(4));
                    m.put("target", rs.getString(5));
                    m.put("success", rs.getBoolean(6));
                    m.put("ip", rs.getString(7));
                    m.put("detail", Json.toJava(rs.getString(8)));
                    m.put("orgId", rs.getObject(9, Long.class));
                    m.put("org", rs.getString(10));
                    return m;
                }).list();
    }

    public Map<String, Object> policy() {
        BimbaConfig.Security s = sec();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minPasswordLength", s.minPasswordLength);
        m.put("minCharacterClasses", s.minCharacterClasses);
        m.put("maxFailedLogins", s.maxFailedLogins);
        m.put("lockMinutes", s.lockMinutes);
        m.put("idleMinutes", s.idleMinutes);
        m.put("maxSessionHours", s.maxSessionHours);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    /** a sign-in address: valid, at most 150 characters (it is also the username), not used by another account */
    private void checkEmail(String email, Long exceptUserId) throws ApiException {
        if (email.isEmpty() || !EMAIL.matcher(email).matches()) {
            throw new ApiException(400, "email", "Enter a valid e-mail address: it is the user's sign-in.");
        }
        if (email.length() > 150) {
            throw new ApiException(400, "email", "Sign-in e-mail addresses can have up to 150 characters.");
        }
        long used = db.sql("SELECT count(*) FROM {s}.auth_user WHERE (lower(email) = ? OR lower(username) = ?) AND id <> ?")
                .params(email, email, exceptUserId == null ? -1L : exceptUserId).query(Long.class).single();
        if (used > 0) {
            throw new ApiException(409, "exists", "Another account already signs in with " + email + ".");
        }
    }

    /** the part of an e-mail address before the @ (what a password must not contain) */
    static String localPart(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private static String text(JsonObject o, String k, int max) {
        JsonElement e = o == null ? null : o.get(k);
        String v = e == null || e.isJsonNull() ? "" : e.getAsString().trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String cut(String v, int max) {
        return v == null ? "" : v.length() > max ? v.substring(0, max) : v;
    }

    private static String iso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    static String sha256(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
