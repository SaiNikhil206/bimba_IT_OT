package com.bimba.setu.agents;

import com.bimba.setu.Db;
import com.bimba.setu.Json;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enrollment tokens of one organization (table bimba_enroll_token, Django migration telemetry/0004). A token lets
 * the site agent installer register machines into that organization without a user's password. Only its SHA-256
 * is stored; the token itself is shown once when it is created. Same format as Django's telemetry.agents.create_token.
 */
@Service
public class EnrollTokens {
    public static final String PREFIX = "bimba_et_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Db db;

    public EnrollTokens(Db db) {
        this.db = db;
    }

    public List<Map<String, Object>> list(long orgId) {
        return db.sql("SELECT t.id, t.label, t.prefix, t.max_uses, t.uses, t.expires_at, t.revoked_at, t.created_at,"
                        + " t.last_used_at, COALESCE(NULLIF(u.email, ''), u.username), now()"
                        + " FROM {s}.bimba_enroll_token t LEFT JOIN {s}.auth_user u ON u.id = t.created_by_id"
                        + " WHERE t.org_id = ? ORDER BY t.created_at DESC")
                .param(orgId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("label", rs.getString(2));
                    m.put("prefix", rs.getString(3));
                    Integer max = (Integer) rs.getObject(4);
                    int uses = rs.getInt(5);
                    Timestamp expires = rs.getTimestamp(6);
                    Timestamp revoked = rs.getTimestamp(7);
                    Timestamp now = rs.getTimestamp(11);
                    m.put("maxUses", max);
                    m.put("uses", uses);
                    m.put("expiresAt", iso(expires));
                    m.put("revokedAt", iso(revoked));
                    m.put("createdAt", iso(rs.getTimestamp(8)));
                    m.put("lastUsedAt", iso(rs.getTimestamp(9)));
                    m.put("createdBy", rs.getString(10));
                    m.put("oneTime", max != null && max == 1 && ONE_TIME_LABEL.equals(rs.getString(2)));
                    m.put("now", iso(now));
                    m.put("status", revoked != null ? "revoked" : expires != null && !expires.after(now) ? "expired"
                            : max != null && uses >= max ? "used up" : "active");
                    return m;
                })
                .list();
    }

    /** A token for one computer that expires after {@value #ONE_TIME_MINUTES} minutes (Add a computer's Copy token). */
    public static final int ONE_TIME_MINUTES = 3;
    public static final String ONE_TIME_LABEL = "One-time (Add a computer)";

    public Map<String, Object> createOneTime(long orgId, long userId, String actor, String ip) {
        return create(orgId, userId, actor, ONE_TIME_LABEL, 1, null, ONE_TIME_MINUTES, ip);
    }

    public Map<String, Object> create(long orgId, long userId, String actor, String label, Integer maxUses, Integer days, String ip) {
        return create(orgId, userId, actor, label, maxUses, days, null, ip);
    }

    /** Returns the new row plus {@code token}, the only time the plaintext exists. minutes wins over days; neither = never expires. */
    private Map<String, Object> create(long orgId, long userId, String actor, String label, Integer maxUses, Integer days, Integer minutes, String ip) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        String l = label == null ? "" : label.trim();
        if (l.length() > 200) {
            l = l.substring(0, 200);
        }
        long id = db.sql("INSERT INTO {s}.bimba_enroll_token (org_id, label, token_hash, prefix, max_uses, uses, expires_at,"
                        + " revoked_at, created_at, last_used_at, created_by_id)"
                        + " VALUES (?, ?, ?, ?, ?, 0, CASE WHEN ?::int IS NOT NULL THEN now() + make_interval(mins => ?::int)"
                        + " WHEN ?::int IS NOT NULL THEN now() + make_interval(days => ?::int) END,"
                        + " NULL, now(), NULL, ?) RETURNING id")
                .params(orgId, l, AgentRegistry.sha256(token), token.substring(0, 16), maxUses, minutes, minutes, days, days, userId)
                .query(Long.class).single();
        event(orgId, "token-created", actor, ip, Map.of("tokenId", id, "label", l, "prefix", token.substring(0, 16)));
        Map<String, Object> m = new LinkedHashMap<>(list(orgId).stream().filter(r -> r.get("id").equals(id)).findFirst().orElseThrow());
        m.put("token", token);
        return m;
    }

    /** Revokes an active token of the organization. Returns its label (or prefix), null when there was none. */
    public String revoke(long orgId, long tokenId, String actor, String ip) {
        String name = db.sql("UPDATE {s}.bimba_enroll_token SET revoked_at = now() WHERE id = ? AND org_id = ? AND revoked_at IS NULL"
                        + " RETURNING COALESCE(NULLIF(label, ''), prefix || '…')")
                .params(tokenId, orgId).query(String.class).optional().orElse(null);
        if (name != null) {
            event(orgId, "token-revoked", actor, ip, Map.of("tokenId", tokenId));
        }
        return name;
    }

    private void event(long orgId, String kind, String actor, String ip, Map<String, Object> detail) {
        db.sql("INSERT INTO {s}.bimba_agent_event (org_id, agent_id, kind, actor, remote_ip, detail, at)"
                        + " VALUES (?, NULL, ?, ?, ?, ?::jsonb, now())")
                .params(orgId, kind, "gateway: " + actor, ip == null ? "" : ip, Json.GSON.toJson(detail)).update();
    }

    private static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }
}
