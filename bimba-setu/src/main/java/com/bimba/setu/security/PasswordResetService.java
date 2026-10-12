package com.bimba.setu.security;

import com.bimba.setu.Db;
import com.bimba.setu.SetuService;
import com.bimba.setu.agents.FailureLimiter;
import com.bimba.setu.config.BimbaConfig;
import com.bimba.setu.security.SecurityService.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Forgot password?" of the Setu console.
 * <ul>
 *   <li>request: for an active account that can use the console, a one-time link is e-mailed (valid {@link #MINUTES}
 *       minutes; a new link replaces the previous one). The answer is the same whether the address has an account or
 *       not, and is given before the mail is sent, so it cannot be used to find accounts.</li>
 *   <li>Only the SHA-256 of the token is stored (bimba_gw_password_reset); the link works once.</li>
 *   <li>reset: the new password must meet the policy; the account is unlocked, the "must change" flag cleared and
 *       every open session of the account ends.</li>
 *   <li>Limits: 5 requests per client address and 3 links per account in 15 minutes; 20 wrong / expired tokens per
 *       client address in 15 minutes.</li>
 * </ul>
 */
@Service
public class PasswordResetService {
    public static final int MINUTES = 30;
    private static final long WINDOW = 15 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Db db;
    private final SetuService setu;
    private final SecurityService security;
    private final OrgService orgs;
    private final Mailer mailer;
    private final String publicUrl;
    private final FailureLimiter perIp = new FailureLimiter();
    private final FailureLimiter perAccount = new FailureLimiter();
    private final FailureLimiter badTokens = new FailureLimiter();

    public PasswordResetService(Db db, SetuService setu, SecurityService security, OrgService orgs, Mailer mailer,
                                @Value("${bimba.public-url:http://localhost:8090}") String publicUrl) {
        this.db = db;
        this.setu = setu;
        this.security = security;
        this.orgs = orgs;
        this.mailer = mailer;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    /** Always answers the same; sends a link only to an active account that can sign in to the console. */
    public void request(String email, String ip) throws ApiException {
        String address = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (address.isEmpty()) {
            throw new ApiException(400, "missing", "Enter your e-mail address.");
        }
        if (perIp.blocked(ip, 5, WINDOW)) {
            throw new ApiException(429, "rate_limited", "Too many requests from this address. Try again in 15 minutes.");
        }
        perIp.fail(ip, WINDOW);
        Object[] u = db.sql("SELECT u.id, u.email, u.first_name, u.is_active, u.is_superuser, p.last_org_id FROM {s}.auth_user u"
                        + " LEFT JOIN {s}.bimba_gw_user_profile p ON p.user_id = u.id WHERE u.email <> '' AND lower(u.email) = ?")
                .param(address)
                .query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5),
                        rs.getObject(6, Long.class)})
                .optional().orElse(null);
        if (u == null) {
            security.audit(address, "password.reset_request", address, false, ip, Map.of("reason", "no such account"));
            return;
        }
        long id = (Long) u[0];
        Long org = (Long) u[5];
        if (!(Boolean) u[3] || orgs.access(id, (Boolean) u[4]).isEmpty() && !(Boolean) u[4]) {
            security.audit(org, address, "password.reset_request", address, false, ip, Map.of("reason", (Boolean) u[3] ? "no console access" : "account disabled"));
            return;
        }
        if (perAccount.blocked(String.valueOf(id), 3, WINDOW)) {
            security.audit(org, address, "password.reset_request", address, false, ip, Map.of("reason", "too many links for this account"));
            return;
        }
        perAccount.fail(String.valueOf(id), WINDOW);
        String token = randomToken();
        db.tx(() -> {
            // a new link replaces the ones sent before
            db.sql("UPDATE {s}.bimba_gw_password_reset SET used_at = now() WHERE user_id = ? AND used_at IS NULL").param(id).update();
            db.sql("INSERT INTO {s}.bimba_gw_password_reset (id, user_id, created_at, expires_at, ip)"
                            + " VALUES (?, ?, now(), now() + (? * interval '1 minute'), ?)")
                    .params(SecurityService.sha256(token), id, MINUTES, ip).update();
            return null;
        });
        String link = publicUrl + "/reset-password?token=" + token;
        String first = u[2] == null || ((String) u[2]).isBlank() ? "" : " " + u[2];
        mailer.send((String) u[1], "Reset your BIMBA Setu password",
                "Hello" + first + ",\n\n"
                        + "Someone (hopefully you) asked to reset the password of your BIMBA Setu account " + u[1] + ".\n\n"
                        + "Choose a new password here (the link works once, for " + MINUTES + " minutes):\n" + link + "\n\n"
                        + "If you did not ask for this, ignore this e-mail: your password stays as it is.\n\n"
                        + "Request from " + ip + ".\n-- BIMBA Setu\n",
                "Reset link for " + u[1] + " (valid " + MINUTES + " min): " + link);
        security.audit(org, address, "password.reset_request", address, true, ip, Map.of("mail", mailer.configured() ? "sent" : "SMTP not set up: link in the Setu log"));
    }

    /** The account a valid link belongs to (shown on the reset page); 400 when it expired or was used. */
    public Map<String, Object> check(String token, String ip) throws ApiException {
        Object[] r = open(token, ip);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", r[1]);
        m.put("expiresAt", r[2]);
        m.put("policy", security.policy());
        return m;
    }

    /** Sets the new password with a valid link; the link is used up and every session of the account ends. */
    public void reset(String token, String password, String ip) throws ApiException {
        Object[] r = open(token, ip);
        long id = (Long) r[0];
        String email = (String) r[1];
        BimbaConfig c = setu.config();
        List<String> problems = Passwords.check(password, SecurityService.localPart(email), c == null ? new BimbaConfig.Security() : c.security);
        if (!problems.isEmpty()) {
            throw new ApiException(400, "policy", "The new password needs: " + String.join("; ", problems) + ".");
        }
        String hash = Passwords.hash(password);
        String key = SecurityService.sha256(token);
        int done = db.tx(() -> {
            // used_at IS NULL in the WHERE: two submits of the same link cannot both change the password
            int n = db.sql("UPDATE {s}.bimba_gw_password_reset SET used_at = now() WHERE id = ? AND used_at IS NULL AND expires_at > now()")
                    .param(key).update();
            if (n == 0) {
                return 0;
            }
            db.sql("UPDATE {s}.auth_user SET password = ? WHERE id = ?").params(hash, id).update();
            db.sql("INSERT INTO {s}.bimba_gw_user_profile (user_id, must_change_password, password_changed_at, failed_logins) VALUES (?, false, now(), 0)"
                    + " ON CONFLICT (user_id) DO UPDATE SET must_change_password = false, password_changed_at = now(), failed_logins = 0,"
                    + " locked_until = NULL").param(id).update();
            db.sql("UPDATE {s}.bimba_gw_session SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL").param(id).update();
            return 1;
        });
        if (done == 0) {
            throw new ApiException(400, "expired", "This link has expired or was already used. Ask for a new one.");
        }
        Long org = db.sql("SELECT last_org_id FROM {s}.bimba_gw_user_profile WHERE user_id = ?").param(id).query(Long.class).optional().orElse(null);
        security.audit(org, email, "password.reset", email, true, ip, Map.of("via", "e-mailed link"));
    }

    /** {userId, email, expiresAt} of an unused, unexpired link */
    private Object[] open(String token, String ip) throws ApiException {
        if (badTokens.blocked(ip, 20, WINDOW)) {
            throw new ApiException(429, "rate_limited", "Too many attempts from this address. Try again in 15 minutes.");
        }
        Object[] r = token == null || token.isBlank() || token.length() > 100 ? null
                : db.sql("SELECT u.id, u.email, r.expires_at FROM {s}.bimba_gw_password_reset r JOIN {s}.auth_user u ON u.id = r.user_id"
                        + " WHERE r.id = ? AND r.used_at IS NULL AND r.expires_at > now() AND u.is_active")
                .param(SecurityService.sha256(token.trim()))
                .query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant().toString()})
                .optional().orElse(null);
        if (r == null) {
            badTokens.fail(ip, WINDOW);
            throw new ApiException(400, "expired", "This link has expired or was already used. Ask for a new one.");
        }
        return r;
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
