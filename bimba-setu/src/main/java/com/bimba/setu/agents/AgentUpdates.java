package com.bimba.setu.agents;

import com.bimba.setu.Db;
import com.bimba.setu.Json;
import com.bimba.setu.security.SecurityService;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic site agent updates: a newer installer is run over the old one, by the computer itself.
 * <p>
 * Setu offers the update in the heartbeat answer ("update": version, kind, SHA-256, size, download path) when the
 * installer in site-agent/dist that matches how the agent was installed (exe, msi, deb or tar; {@link InstallerFile})
 * is newer than the agent. The agent downloads it over its own connection (client certificate), checks size and
 * SHA-256 and hands it to its updater, which runs with the rights an installer needs (Linux: a root systemd unit;
 * Windows: a LocalSystem service). The updater checks the file again against {@code GET /system/bimba-agent-update/release}
 * on its own (the agent service could be tampered with), refuses older versions and, for a code-signed Windows agent,
 * installers from another publisher, then runs the installer over the old one. The registration, certificate, data
 * sources and disk buffer stay. The update counts as done when the agent reports the new version.
 * <p>
 * The agent reports {@code updates} with every heartbeat: kind, supported (it has an updater), allowed (the computer's
 * own switch: {@code bimba-site-agent updates on|off}), state (idle, downloading, staged, installing, failed), version,
 * error. Agents before 1.10 report nothing and are updated by hand once.
 * <p>
 * Per organization (bimba_gw_setting "updates.org.&lt;id&gt;"): mode automatic | manual | off, an optional daily
 * window (from, to, time zone) and how many computers update at once. Per agent ("update.agent.&lt;id&gt;"): hold,
 * the current update (target, state, attempts, who started it), the agent's last report and a short history.
 * An automatic update that fails is tried again after an hour, three times per version; "Update now" in the console
 * starts one in any mode (not on a held computer).
 */
@Service
public class AgentUpdates {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Agents");
    static final String ORG_KEY = "updates.org.";
    static final String AGENT_KEY = "update.agent.";
    static final Set<String> MODES = Set.of("automatic", "manual", "off");
    /** states of an update that is under way */
    static final Set<String> ACTIVE = Set.of("offered", "downloading", "staged", "installing");
    static final Set<String> KINDS = Set.of("exe", "msi", "deb", "tar");
    /** no new version this long after the update started: failed */
    static final Duration TIMEOUT = Duration.ofMinutes(20);
    /** an offer the agent did not take up (agent busy, answer lost) is repeated this long */
    static final Duration OFFER_FOR = Duration.ofMinutes(10);
    static final Duration RETRY_AFTER = Duration.ofHours(1);
    static final int MAX_ATTEMPTS = 3;
    static final int HISTORY = 10;
    private static final Pattern TIME = Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)");

    private final Db db;
    private final InstallerFile installers;
    private final AgentRegistry registry;
    private final SecurityService security;

    public AgentUpdates(Db db, InstallerFile installers, AgentRegistry registry, SecurityService security) {
        this.db = db;
        this.installers = installers;
        this.registry = registry;
        this.security = security;
    }

    // ================================================================== releases

    /** kind -> {version, sha256, size, fileName, signed?} of the installers that are there and carry a version */
    public Map<String, Map<String, Object>> release() {
        Map<String, Object> info = installers.info();
        String windows = version(info.get("release"));
        String linux = version(info.get("releaseLinux"));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String kind : List.of("exe", "msi", "deb", "tar")) {
            String v = kind.equals("exe") || kind.equals("msi") ? windows : linux;
            if (v != null && info.get(kind) instanceof Map<?, ?> f && Boolean.TRUE.equals(f.get("available"))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version", v);
                m.put("sha256", f.get("sha256"));
                m.put("size", f.get("size"));
                m.put("fileName", f.get("fileName"));
                if (f.get("signed") != null) {
                    m.put("signed", f.get("signed"));
                }
                out.put(kind, m);
            }
        }
        return out;
    }

    private static String version(Object release) {
        return release instanceof Map<?, ?> r && r.get("version") instanceof String v && !v.isBlank() ? v.trim() : null;
    }

    /** the installer file an agent downloads (served unchanged: its SHA-256 is the published one) */
    public Path file(String kind) {
        return installers.path(kind);
    }

    /**
     * "1.10.0" &gt; "1.9.2"; a pre-release ("1.10.0~rc1", "1.10.0-rc1") is older than its release. Blank = oldest.
     */
    public static int compare(String a, String b) {
        String[] x = parts(a);
        String[] y = parts(b);
        String[] nx = x[0].isEmpty() ? new String[0] : x[0].split("\\.");
        String[] ny = y[0].isEmpty() ? new String[0] : y[0].split("\\.");
        for (int i = 0; i < Math.max(nx.length, ny.length); i++) {
            long p = i < nx.length ? num(nx[i]) : 0;
            long q = i < ny.length ? num(ny[i]) : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        if (x[1].isEmpty() != y[1].isEmpty()) {
            return x[1].isEmpty() ? 1 : -1;
        }
        return Integer.signum(x[1].compareTo(y[1]));
    }

    /** {numeric part, pre-release suffix} */
    private static String[] parts(String v) {
        Matcher m = Pattern.compile("^\\s*v?(\\d+(?:\\.\\d+)*)(?:[~+-]?(.*))?$").matcher(v == null ? "" : v);
        return m.matches() ? new String[]{m.group(1), m.group(2) == null ? "" : m.group(2).trim()} : new String[]{"", ""};
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ================================================================== policy

    /** {mode, window: {from, to, zone} | null, maxAtOnce} */
    public JsonObject policy(long orgId) {
        JsonObject p = db.sql("SELECT value::text FROM {s}.bimba_gw_setting WHERE key = ?").param(ORG_KEY + orgId).query(String.class)
                .optional().map(v -> Json.GSON.fromJson(v, JsonObject.class)).orElseGet(JsonObject::new);
        JsonObject out = new JsonObject();
        String mode = Json.str(p, "mode");
        out.addProperty("mode", MODES.contains(mode) ? mode : "automatic");
        out.add("window", p.has("window") && p.get("window").isJsonObject() ? p.get("window") : null);
        out.addProperty("maxAtOnce", p.has("maxAtOnce") && p.get("maxAtOnce").isJsonPrimitive() ? p.get("maxAtOnce").getAsInt() : 5);
        if (p.has("updatedBy")) {
            out.add("updatedBy", p.get("updatedBy"));
            out.add("updatedAt", p.get("updatedAt"));
        }
        return out;
    }

    /** body {mode, window: {from: "HH:mm", to: "HH:mm", zone} | null, maxAtOnce 1-100} */
    public JsonObject savePolicy(long orgId, JsonObject b, String actor) throws ApiException {
        String mode = Json.str(b, "mode");
        if (!MODES.contains(mode)) {
            throw new ApiException(400, "mode", "Choose Automatic, Ask me or Off.");
        }
        JsonObject p = new JsonObject();
        p.addProperty("mode", mode);
        JsonElement w = b.get("window");
        if (w != null && w.isJsonObject()) {
            JsonObject win = w.getAsJsonObject();
            String from = Json.str(win, "from").trim();
            String to = Json.str(win, "to").trim();
            String zone = Json.str(win, "zone").trim();
            if (!TIME.matcher(from).matches() || !TIME.matcher(to).matches()) {
                throw new ApiException(400, "window", "Enter the window as hours and minutes, for example 02:00 to 05:00.");
            }
            if (from.equals(to)) {
                throw new ApiException(400, "window", "The window needs different start and end times.");
            }
            try {
                ZoneId.of(zone.isEmpty() ? "UTC" : zone);
            } catch (RuntimeException e) {
                throw new ApiException(400, "window", "Unknown time zone " + zone + ".");
            }
            JsonObject clean = new JsonObject();
            clean.addProperty("from", LocalTime.parse(pad(from)).toString());
            clean.addProperty("to", LocalTime.parse(pad(to)).toString());
            clean.addProperty("zone", zone.isEmpty() ? "UTC" : zone);
            p.add("window", clean);
        }
        int max = b.has("maxAtOnce") && b.get("maxAtOnce").isJsonPrimitive() ? b.get("maxAtOnce").getAsInt() : 5;
        if (max < 1 || max > 100) {
            throw new ApiException(400, "maxAtOnce", "Computers updating at once: 1 to 100.");
        }
        p.addProperty("maxAtOnce", max);
        p.addProperty("updatedBy", actor);
        p.addProperty("updatedAt", Instant.now().toString());
        db.sql("INSERT INTO {s}.bimba_gw_setting (key, value, updated_at) VALUES (?, ?::jsonb, now())"
                        + " ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = now()")
                .params(ORG_KEY + orgId, p.toString()).update();
        return policy(orgId);
    }

    private static String pad(String hhmm) {
        return hhmm.length() == 4 ? "0" + hhmm : hhmm;
    }

    /** true when now is inside the policy's window (no window = any time); a window may pass midnight */
    static boolean inWindow(JsonObject policy, Instant now) {
        JsonElement w = policy.get("window");
        if (w == null || !w.isJsonObject()) {
            return true;
        }
        try {
            JsonObject win = w.getAsJsonObject();
            LocalTime from = LocalTime.parse(Json.str(win, "from"));
            LocalTime to = LocalTime.parse(Json.str(win, "to"));
            String z = Json.str(win, "zone");
            LocalTime t = ZonedDateTime.ofInstant(now, ZoneId.of(z.isEmpty() ? "UTC" : z)).toLocalTime();
            return from.isBefore(to) ? !t.isBefore(from) && t.isBefore(to) : !t.isBefore(from) || t.isBefore(to);
        } catch (java.time.DateTimeException e) {
            return true;
        }
    }

    // ================================================================== per agent state

    private JsonObject state(UUID agent) {
        return db.sql("SELECT value::text FROM {s}.bimba_gw_setting WHERE key = ?").param(AGENT_KEY + agent).query(String.class)
                .optional().map(v -> Json.GSON.fromJson(v, JsonObject.class)).orElseGet(JsonObject::new);
    }

    private void save(UUID agent, JsonObject st) {
        db.sql("INSERT INTO {s}.bimba_gw_setting (key, value, updated_at) VALUES (?, ?::jsonb, now())"
                        + " ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = now()")
                .params(AGENT_KEY + agent, st.toString()).update();
    }

    /** computers of the organization with an update under way (the policy's "at once") */
    private int active(long orgId) {
        return db.sql("SELECT count(*) FROM {s}.bimba_gw_setting WHERE key LIKE 'update.agent.%' AND value->>'org' = ?"
                        + " AND value->>'state' IN ('offered', 'downloading', 'staged', 'installing')")
                .param(String.valueOf(orgId)).query(Long.class).single().intValue();
    }

    // ================================================================== heartbeat

    /**
     * Called with every heartbeat of an active agent: notes what the agent reports, finishes or fails the update
     * under way and decides whether to offer one. Returns the offer for the heartbeat answer, or null.
     */
    public Map<String, Object> onHeartbeat(String agentId, JsonObject body) {
        try {
            UUID agent = UUID.fromString(agentId);
            Long orgId = db.sql("SELECT org_id FROM {s}.bimba_agent WHERE id = ?").param(agent).query(Long.class).optional().orElse(null);
            if (orgId == null) {
                return null;
            }
            JsonElement u = body == null ? null : body.get("updates");
            return decide(agent, orgId, AgentRegistry.str(body, "agentVersion", 32), u != null && u.isJsonObject() ? u.getAsJsonObject() : null,
                    Instant.now());
        } catch (RuntimeException e) {
            log.warn("site agent {}: update check failed: {}", agentId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> decide(UUID agent, long orgId, String version, JsonObject reported, Instant now) {
        JsonObject st = state(agent);
        String before = st.toString();
        st.addProperty("org", String.valueOf(orgId));
        if (version != null && !version.isBlank()) {
            st.addProperty("version", version);
        }
        version = Json.str(st, "version");
        if (reported != null) {
            JsonObject r = new JsonObject();
            for (String k : List.of("kind", "supported", "why", "allowed", "state", "version", "error", "at", "signed")) {
                if (reported.has(k) && reported.get(k).isJsonPrimitive()) {
                    r.add(k, reported.get(k));
                }
            }
            st.add("agent", r);
        }
        JsonObject r = st.has("agent") && st.get("agent").isJsonObject() ? st.getAsJsonObject("agent") : null;
        String target = Json.str(st, "target");
        String state = Json.str(st, "state");
        Map<String, Object> offer = null;

        if (ACTIVE.contains(state)) {
            Instant started = instant(st, "startedAt", now);
            String agentState = r == null ? "" : Json.str(r, "state");
            boolean forTarget = r != null && target.equals(Json.str(r, "version"));
            if (compare(version, target) >= 0) {
                finish(agent, orgId, st, "done", null, now);
            } else if (forTarget && "failed".equals(agentState)) {
                String err = Json.str(r, "error");
                finish(agent, orgId, st, "failed", err.isEmpty() ? "The computer could not install the update." : err, now);
            } else if (Duration.between(started, now).compareTo(TIMEOUT) > 0) {
                finish(agent, orgId, st, "failed", "The computer did not report version " + target + " within "
                        + TIMEOUT.toMinutes() + " minutes.", now);
            } else {
                if (forTarget && Set.of("downloading", "staged", "installing").contains(agentState)) {
                    st.addProperty("state", agentState);
                } else if ("offered".equals(state) && Duration.between(started, now).compareTo(OFFER_FOR) < 0) {
                    offer = offerFor(Json.str(st, "kind"), target);     // not taken up yet: offer it again
                }
            }
        }
        state = Json.str(st, "state");
        if (!ACTIVE.contains(state) && r != null) {
            offer = maybeStart(agent, orgId, st, version, r, now);
        }
        if (!st.toString().equals(before)) {
            save(agent, st);
        }
        return offer;
    }

    /** a new update, when the policy (or a console request) wants one and the computer can take it */
    private Map<String, Object> maybeStart(UUID agent, long orgId, JsonObject st, String version, JsonObject r, Instant now) {
        String kind = Json.str(r, "kind");
        Map<String, Object> rel = release().get(kind);
        JsonObject requested = st.has("requested") && st.get("requested").isJsonObject() ? st.getAsJsonObject("requested") : null;
        if (rel == null || !bool(r, "supported") || !bool(r, "allowed") || bool(st, "hold")
                || compare((String) rel.get("version"), version) <= 0) {
            if (requested != null && (rel == null || compare((String) rel.get("version"), version) <= 0)) {
                st.remove("requested");                       // nothing newer to install any more
            }
            return null;
        }
        String target = (String) rel.get("version");
        String by;
        if (requested != null) {
            by = Json.str(requested, "by");
            st.remove("requested");
            st.addProperty("attempts", 0);                   // asked for in the console: a fresh count
        } else {
            JsonObject policy = policy(orgId);
            if (!"automatic".equals(Json.str(policy, "mode")) || !inWindow(policy, now)) {
                return null;
            }
            if (target.equals(Json.str(st, "target")) && "failed".equals(Json.str(st, "state"))) {
                if (integer(st, "attempts") >= MAX_ATTEMPTS
                        || Duration.between(instant(st, "at", now), now).compareTo(RETRY_AFTER) < 0) {
                    return null;
                }
            }
            if (active(orgId) >= policy.get("maxAtOnce").getAsInt()) {
                return null;
            }
            by = "automatic";
        }
        int attempts = target.equals(Json.str(st, "target")) ? integer(st, "attempts") + 1 : 1;
        st.addProperty("target", target);
        st.addProperty("kind", kind);
        st.addProperty("from", version);
        st.addProperty("state", "offered");
        st.addProperty("startedAt", now.toString());
        st.addProperty("at", now.toString());
        st.addProperty("by", by);
        st.addProperty("attempts", attempts);
        st.remove("error");
        String name = registry.displayName(agent);
        registry.event(orgId, agent, "update-started", "automatic".equals(by) ? "setu (automatic update)" : "gateway: " + by, null,
                Map.of("from", version, "to", target, "kind", kind, "attempt", attempts));
        security.audit(orgId, "automatic".equals(by) ? "setu" : by, "agent.update.start", name, true, "",
                Map.of("agentId", agent.toString(), "from", version, "to", target, "kind", kind, "attempt", attempts));
        log.info("site agent {} ({}): update {} -> {} ({}) started by {}", name, agent, version, target, kind, by);
        return offerFor(kind, target);
    }

    private void finish(UUID agent, long orgId, JsonObject st, String outcome, String error, Instant now) {
        String target = Json.str(st, "target");
        String from = Json.str(st, "from");
        st.addProperty("state", outcome);
        st.addProperty("at", now.toString());
        if (error == null) {
            st.remove("error");
        } else {
            st.addProperty("error", error);
        }
        JsonObject h = new JsonObject();
        h.addProperty("from", from);
        h.addProperty("to", target);
        h.addProperty("outcome", outcome);
        h.addProperty("by", Json.str(st, "by"));
        h.addProperty("startedAt", Json.str(st, "startedAt"));
        h.addProperty("at", now.toString());
        if (error != null) {
            h.addProperty("error", error);
        }
        JsonArray history = st.has("history") && st.get("history").isJsonArray() ? st.getAsJsonArray("history") : new JsonArray();
        JsonArray next = new JsonArray();
        next.add(h);
        for (int i = 0; i < history.size() && next.size() < HISTORY; i++) {
            next.add(history.get(i));
        }
        st.add("history", next);
        String name = registry.displayName(agent);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("agentId", agent.toString());
        d.put("from", from);
        d.put("to", target);
        if (error != null) {
            d.put("error", error);
        }
        registry.event(orgId, agent, "done".equals(outcome) ? "updated" : "update-failed", "setu", null, d);
        security.audit(orgId, "setu", "done".equals(outcome) ? "agent.update.done" : "agent.update.failed", name, "done".equals(outcome), "", d);
        if ("done".equals(outcome)) {
            log.info("site agent {} ({}) updated {} -> {}", name, agent, from, target);
        } else {
            log.warn("site agent {} ({}): update {} -> {} failed: {}", name, agent, from, target, error);
        }
    }

    private Map<String, Object> offerFor(String kind, String target) {
        Map<String, Object> rel = release().get(kind);
        if (rel == null || !target.equals(rel.get("version"))) {
            return null;                                     // the installer was replaced meanwhile: the next check decides
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", target);
        m.put("kind", kind);
        m.put("sha256", rel.get("sha256"));
        m.put("size", rel.get("size"));
        m.put("fileName", rel.get("fileName"));
        m.put("path", "/system/bimba-agent-update/download?kind=" + kind);
        return m;
    }

    /** updates that stopped (the agent went quiet) fail after the timeout, so they free their place in "at once" */
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    void sweep() {
        try {
            Instant now = Instant.now();
            List<String[]> rows = db.sql("SELECT key, value::text FROM {s}.bimba_gw_setting WHERE key LIKE 'update.agent.%'"
                            + " AND value->>'state' IN ('offered', 'downloading', 'staged', 'installing')")
                    .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).list();
            for (String[] row : rows) {
                JsonObject st = Json.GSON.fromJson(row[1], JsonObject.class);
                if (Duration.between(instant(st, "startedAt", now), now).compareTo(TIMEOUT) > 0) {
                    UUID agent = UUID.fromString(row[0].substring(AGENT_KEY.length()));
                    finish(agent, Long.parseLong(Json.str(st, "org")), st, "failed", "The computer did not report version "
                            + Json.str(st, "target") + " within " + TIMEOUT.toMinutes() + " minutes.", now);
                    save(agent, st);
                }
            }
        } catch (RuntimeException e) {
            log.debug("update sweep: {}", e.getMessage());
        }
    }

    // ================================================================== console

    /** agent id -> its update line for the site agent list and page */
    public Map<String, Map<String, Object>> summary(long orgId) {
        Map<String, Map<String, Object>> rel = release();
        Map<String, Map<String, Object>> out = new HashMap<>();
        db.sql("SELECT key, value::text FROM {s}.bimba_gw_setting WHERE key LIKE 'update.agent.%' AND value->>'org' = ?")
                .param(String.valueOf(orgId))
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).list()
                .forEach(row -> out.put(row[0].substring(AGENT_KEY.length()), view(Json.GSON.fromJson(row[1], JsonObject.class), rel, false)));
        return out;
    }

    /** agentVersion: what the agent list knows, for an agent that has not reported since Setu started */
    public Map<String, Object> forAgent(UUID agent, String agentVersion) {
        JsonObject st = state(agent);
        if (Json.str(st, "version").isEmpty() && agentVersion != null) {
            st.addProperty("version", agentVersion);
        }
        return view(st, release(), true);
    }

    /** the update line of an agent with no update state yet (it has not reported since automatic updates came) */
    public Map<String, Object> unreported(String agentVersion) {
        JsonObject st = new JsonObject();
        st.addProperty("version", agentVersion == null ? "" : agentVersion);
        return view(st, release(), false);
    }

    /** what the console shows; agents that never reported (before 1.10) get {reported: false} */
    private static Map<String, Object> view(JsonObject st, Map<String, Map<String, Object>> rel, boolean withHistory) {
        Map<String, Object> m = new LinkedHashMap<>();
        JsonObject r = st.has("agent") && st.get("agent").isJsonObject() ? st.getAsJsonObject("agent") : null;
        m.put("reported", r != null);
        String kind = r == null ? "" : Json.str(r, "kind");
        m.put("kind", kind.isEmpty() ? null : kind);
        m.put("supported", r != null && bool(r, "supported"));
        m.put("why", r == null || Json.str(r, "why").isEmpty() ? null : Json.str(r, "why"));
        m.put("allowed", r == null || bool(r, "allowed"));
        m.put("hold", bool(st, "hold"));
        if (bool(st, "hold")) {
            m.put("holdBy", Json.str(st, "holdBy"));
            m.put("holdAt", Json.str(st, "holdAt"));
        }
        Map<String, Object> latest = rel.get(kind);
        if (latest == null && r == null) {
            // an agent before 1.10 does not say how it was installed: the newest installer tells whether it is behind
            latest = rel.values().stream().max((x, y) -> compare((String) x.get("version"), (String) y.get("version"))).orElse(null);
        }
        String version = Json.str(st, "version");
        m.put("latest", latest == null ? null : latest.get("version"));
        m.put("available", latest != null && compare((String) latest.get("version"), version) > 0 ? latest.get("version") : null);
        String state = Json.str(st, "state");
        m.put("state", state.isEmpty() ? null : state);
        m.put("target", Json.str(st, "target").isEmpty() ? null : Json.str(st, "target"));
        m.put("from", Json.str(st, "from").isEmpty() ? null : Json.str(st, "from"));
        m.put("by", Json.str(st, "by").isEmpty() ? null : Json.str(st, "by"));
        m.put("attempts", integer(st, "attempts"));
        m.put("startedAt", Json.str(st, "startedAt").isEmpty() ? null : Json.str(st, "startedAt"));
        m.put("at", Json.str(st, "at").isEmpty() ? null : Json.str(st, "at"));
        m.put("error", Json.str(st, "error").isEmpty() ? null : Json.str(st, "error"));
        m.put("requested", st.has("requested"));
        boolean retryLeft = "failed".equals(state) && integer(st, "attempts") < MAX_ATTEMPTS;
        m.put("retries", retryLeft);
        if (withHistory) {
            m.put("history", st.has("history") ? Json.toJava(st.get("history").toString()) : List.of());
        }
        return m;
    }

    /**
     * Console actions on one agent: now (start at its next heartbeat, whatever the mode), hold (no updates until
     * released), release.
     */
    public Map<String, Object> act(String agentId, long orgId, String action, String actor) throws ApiException {
        UUID agent = agentOf(agentId, orgId);
        JsonObject st = state(agent);
        st.addProperty("org", String.valueOf(orgId));
        switch (action == null ? "" : action) {
            case "now" -> {
                if (bool(st, "hold")) {
                    throw new ApiException(409, "hold", "Updates are on hold for this computer. Release the hold first.");
                }
                if (ACTIVE.contains(Json.str(st, "state"))) {
                    throw new ApiException(409, "busy", "An update is already under way on this computer.");
                }
                JsonObject r = st.has("agent") && st.get("agent").isJsonObject() ? st.getAsJsonObject("agent") : null;
                if (r == null) {
                    throw new ApiException(409, "old", "This agent is older than 1.10 and cannot update itself. Run the newer installer on the computer once.");
                }
                if (!bool(r, "supported")) {
                    throw new ApiException(409, "unsupported", "This computer cannot update itself: "
                            + (Json.str(r, "why").isEmpty() ? "it has no updater." : Json.str(r, "why")));
                }
                if (!bool(r, "allowed")) {
                    throw new ApiException(409, "refused", "Updates are switched off on the computer itself (bimba-site-agent updates on).");
                }
                Map<String, Object> rel = release().get(Json.str(r, "kind"));
                if (rel == null || compare((String) rel.get("version"), Json.str(st, "version")) <= 0) {
                    throw new ApiException(409, "current", "There is no newer installer for this computer on this Setu server.");
                }
                JsonObject req = new JsonObject();
                req.addProperty("by", actor);
                req.addProperty("at", Instant.now().toString());
                req.addProperty("version", (String) rel.get("version"));
                st.add("requested", req);
            }
            case "hold" -> {
                st.addProperty("hold", true);
                st.addProperty("holdBy", actor);
                st.addProperty("holdAt", Instant.now().toString());
                st.remove("requested");
            }
            case "release" -> {
                st.remove("hold");
                st.remove("holdBy");
                st.remove("holdAt");
                if ("failed".equals(Json.str(st, "state"))) {
                    st.addProperty("attempts", 0);           // a fresh start for automatic updates
                }
            }
            default -> throw new ApiException(400, "action", "action must be now, hold or release");
        }
        save(agent, st);
        return forAgent(agent, null);
    }

    private UUID agentOf(String agentId, long orgId) throws ApiException {
        UUID id;
        try {
            id = UUID.fromString(agentId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(404, "not_found", "Unknown site agent.");
        }
        long n = db.sql("SELECT count(*) FROM {s}.bimba_agent WHERE id = ? AND org_id = ?").params(id, orgId).query(Long.class).single();
        if (n == 0) {
            throw new ApiException(404, "not_found", "Unknown site agent.");
        }
        return id;
    }

    /** counts for the site agent list: up to date, updating, can update, need a manual install */
    public Map<String, Object> overview(long orgId, List<Map<String, Object>> agents) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policy", Json.toJava(policy(orgId).toString()));
        Map<String, Object> rel = new LinkedHashMap<>();
        release().forEach((k, v) -> rel.put(k, Map.of("version", v.get("version"), "size", v.get("size"))));
        m.put("release", rel);
        int updating = 0, behind = 0, manual = 0, held = 0;
        List<String> failed = new ArrayList<>();
        for (Map<String, Object> a : agents) {
            if (!(a.get("update") instanceof Map<?, ?> u)) {
                continue;
            }
            if (u.get("state") instanceof String s && ACTIVE.contains(s)) {
                updating++;
            } else if (u.get("available") != null) {
                if (Boolean.TRUE.equals(u.get("hold"))) {
                    held++;
                } else if (Boolean.TRUE.equals(u.get("supported")) && Boolean.TRUE.equals(u.get("allowed"))) {
                    behind++;
                } else {
                    manual++;
                }
            }
            if ("failed".equals(u.get("state")) && u.get("available") != null) {
                failed.add(String.valueOf(a.get("name")));
            }
        }
        m.put("counts", Map.of("updating", updating, "waiting", behind, "manual", manual, "held", held, "failed", failed.size()));
        m.put("failed", failed);
        return m;
    }

    // ================================================================== helpers

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static int integer(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static Instant instant(JsonObject o, String k, Instant fallback) {
        try {
            String v = Json.str(o, k);
            return v.isEmpty() ? fallback : Instant.parse(v);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
