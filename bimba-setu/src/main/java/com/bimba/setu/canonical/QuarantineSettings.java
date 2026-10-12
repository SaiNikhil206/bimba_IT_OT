package com.bimba.setu.canonical;

import com.bimba.setu.Db;
import com.bimba.setu.Json;
import com.bimba.setu.config.SecretBox;
import com.bimba.setu.security.Mailer;
import com.bimba.setu.security.OrgService;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Per organization (bimba_gw_setting "quarantine.org.&lt;id&gt;"): how long quarantined readings are kept, and who is told
 * when readings are quarantined (e-mail recipients and / or a webhook). Every minute the new entries of each
 * organization are counted; at least minRecords new readings send one alert, then at most one per quietMinutes (what
 * arrives meanwhile goes into the next one). Every hour entries older than the organization's retention are removed.
 * <p>
 * Webhook: POST application/json {event: "bimba.quarantine", organization, records, entries, reasons, machines, from,
 * to, url}; with a secret, header X-BIMBA-Signature: sha256=&lt;hex HMAC-SHA256 of the body&gt;.
 */
@Service
public class QuarantineSettings {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Quarantine");
    static final String KEY = "quarantine.org.";
    public static final int DEFAULT_RETENTION_DAYS = 30;
    private static final String MASK = "********";
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s]{2,}");
    private static final int MAX_EMAILS = 10;

    /** an organization's settings; webhookSecret sealed (SecretBox) */
    public record Settings(int retentionDays, List<String> emails, String webhookUrl, String webhookSecret, int minRecords,
                           int quietMinutes) {
        static final Settings DEFAULT = new Settings(DEFAULT_RETENTION_DAYS, List.of(), "", "", 1, 15);

        boolean alerts() {
            return !emails.isEmpty() || !webhookUrl.isEmpty();
        }

        public Recipients recipients() {
            return new Recipients(emails, webhookUrl, webhookSecret);
        }
    }

    /** who an alert goes to: e-mail addresses and / or a webhook (secret sealed, SecretBox) */
    public record Recipients(List<String> emails, String webhookUrl, String webhookSecret) {
        public static final Recipients NONE = new Recipients(List.of(), "", "");

        public boolean any() {
            return !emails.isEmpty() || !webhookUrl.isEmpty();
        }

        /** the same without these e-mail addresses (they were told already) */
        public Recipients without(java.util.Collection<String> told) {
            return new Recipients(emails.stream().filter(e -> !told.contains(e)).toList(), webhookUrl, webhookSecret);
        }
    }

    /** what has not been reported yet */
    private static final class State {
        String cursor;                                   // newest entry id already counted
        long lastSent;
        final List<Quarantine.Meta> pending = new ArrayList<>();
    }

    private final Db db;
    private final Quarantine quarantine;
    private final OrgService orgs;
    private final Mailer mailer;
    private final SecretBox secrets;
    private final String publicUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Long, State> states = new ConcurrentHashMap<>();
    private volatile Map<Long, Settings> cache;

    public QuarantineSettings(Db db, Quarantine quarantine, OrgService orgs, Mailer mailer, SecretBox secrets,
                              @Value("${bimba.public-url:http://localhost:8090}") String publicUrl) {
        this.db = db;
        this.quarantine = quarantine;
        this.orgs = orgs;
        this.mailer = mailer;
        this.secrets = secrets;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    // ------------------------------------------------------------------ settings

    private Map<Long, Settings> all() {
        Map<Long, Settings> c = cache;
        if (c != null) {
            return c;
        }
        Map<Long, Settings> m = new ConcurrentHashMap<>();
        db.sql("SELECT key, value::text FROM {s}.bimba_gw_setting WHERE key LIKE 'quarantine.org.%'").query((rs, n) -> {
            try {
                m.put(Long.parseLong(rs.getString(1).substring(KEY.length())), parse(Json.GSON.fromJson(rs.getString(2), JsonObject.class)));
            } catch (RuntimeException e) {
                log.warn("unreadable setting {}: {}", rs.getString(1), e.getMessage());
            }
            return null;
        }).list();
        cache = m;
        return m;
    }

    /** settings as given instead of from the database (tests) */
    void use(Map<Long, Settings> settings) {
        cache = new ConcurrentHashMap<>(settings);
    }

    public Settings settings(long orgId) {
        try {
            return all().getOrDefault(orgId, Settings.DEFAULT);
        } catch (RuntimeException e) {                   // database away: defaults (keep 30 days, no alerts)
            return Settings.DEFAULT;
        }
    }

    private static Settings parse(JsonObject o) {
        List<String> emails = new ArrayList<>();
        if (o.has("emails") && o.get("emails").isJsonArray()) {
            o.getAsJsonArray("emails").forEach(e -> emails.add(e.getAsString()));
        }
        return new Settings(o.has("retentionDays") ? o.get("retentionDays").getAsInt() : DEFAULT_RETENTION_DAYS, List.copyOf(emails),
                Json.str(o, "webhookUrl"), Json.str(o, "webhookSecret"), o.has("minRecords") ? o.get("minRecords").getAsInt() : 1,
                o.has("quietMinutes") ? o.get("quietMinutes").getAsInt() : 15);
    }

    /** for the console: the secret masked */
    public Map<String, Object> view(long orgId) {
        Settings s = settings(orgId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("retentionDays", s.retentionDays());
        m.put("emails", s.emails());
        m.put("webhookUrl", s.webhookUrl());
        m.put("webhookSecret", s.webhookSecret().isEmpty() ? "" : MASK);
        m.put("minRecords", s.minRecords());
        m.put("quietMinutes", s.quietMinutes());
        m.put("mailConfigured", mailer.configured());
        return m;
    }

    public Map<String, Object> save(long orgId, JsonObject in) throws ApiException {
        Settings old = settings(orgId);
        int retention = number(in, "retentionDays", DEFAULT_RETENTION_DAYS, 1, 365, "Keep quarantined readings for 1 to 365 days.");
        Recipients to = recipients(in, old.webhookSecret(), "");
        int min = number(in, "minRecords", 1, 1, 100_000, "Alert after 1 to 100000 readings.");
        int quiet = number(in, "quietMinutes", 15, 1, 1440, "Alerts at most every 1 to 1440 minutes.");
        JsonObject o = new JsonObject();
        o.addProperty("retentionDays", retention);
        JsonArray list = new JsonArray();
        to.emails().forEach(list::add);
        o.add("emails", list);
        o.addProperty("webhookUrl", to.webhookUrl());
        o.addProperty("webhookSecret", to.webhookSecret());
        o.addProperty("minRecords", min);
        o.addProperty("quietMinutes", quiet);
        db.sql("INSERT INTO {s}.bimba_gw_setting (key, value, updated_at) VALUES (?, ?::jsonb, now())"
                        + " ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = now()")
                .params(KEY + orgId, Json.GSON.toJson(o)).update();
        cache = null;
        return view(orgId);
    }

    /**
     * Checked recipients from a form {emails (list or "a, b"), webhookUrl, webhookSecret}: a masked secret keeps
     * oldSealed, an empty one removes it, a new one is sealed. Errors name field (or emails / webhookUrl when empty).
     */
    public Recipients recipients(JsonObject in, String oldSealed, String field) throws ApiException {
        Set<String> emails = new LinkedHashSet<>();
        JsonElement e = in.get("emails");
        List<String> raw = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> raw.add(x.isJsonPrimitive() ? x.getAsString() : ""));
        } else if (e != null && e.isJsonPrimitive()) {
            raw.addAll(List.of(e.getAsString().split("[,;\\s]+")));
        }
        for (String r : raw) {
            String a = r.trim();
            if (a.isEmpty()) {
                continue;
            }
            if (!EMAIL.matcher(a).matches()) {
                throw new ApiException(400, field.isEmpty() ? "emails" : field, "\"" + a + "\" is not an e-mail address.");
            }
            emails.add(a.toLowerCase());
        }
        if (emails.size() > MAX_EMAILS) {
            throw new ApiException(400, field.isEmpty() ? "emails" : field, "At most " + MAX_EMAILS + " e-mail recipients.");
        }
        String url = Json.str(in, "webhookUrl").trim();
        if (!url.isEmpty()) {
            URI u;
            try {
                u = URI.create(url);
            } catch (IllegalArgumentException ex) {
                u = null;
            }
            if (u == null || u.getHost() == null || !Set.of("http", "https").contains(String.valueOf(u.getScheme()).toLowerCase())
                    || url.length() > 500) {
                throw new ApiException(400, field.isEmpty() ? "webhookUrl" : field, "The webhook must be an http:// or https:// address.");
            }
        }
        String secret = Json.str(in, "webhookSecret");
        String sealed = MASK.equals(secret) ? oldSealed : secret.isBlank() ? "" : secrets.seal(secret.trim());
        return new Recipients(List.copyOf(emails), url, sealed);
    }

    /** a sealed secret as the console shows it */
    public static String masked(String sealed) {
        return sealed == null || sealed.isEmpty() ? "" : MASK;
    }

    private static int number(JsonObject in, String k, int dflt, int min, int max, String msg) throws ApiException {
        String v = Json.str(in, k).trim();
        if (v.isEmpty()) {
            return dflt;
        }
        try {
            int n = (int) Double.parseDouble(v);
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // below
        }
        throw new ApiException(400, k, msg);
    }

    // ------------------------------------------------------------------ retention

    @Scheduled(initialDelay = 120_000, fixedDelay = 3_600_000)
    public void retention() {
        try {
            Map<String, Long> owners = orgs.owners();
            Long def = orgs.defaultOrgId();
            long now = System.currentTimeMillis();
            int n = quarantine.removeIf(m -> {
                Long org = Quarantine.orgOf(m, owners, def);
                int days = org == null ? DEFAULT_RETENTION_DAYS : settings(org).retentionDays();
                return m.at().toEpochMilli() < now - days * 86_400_000L;
            });
            if (n > 0) {
                log.info("removed {} quarantine entr{} past their organization's retention", n, n == 1 ? "y" : "ies");
            }
        } catch (RuntimeException e) {
            log.warn("quarantine retention skipped: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ alerts

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void alerts() {
        try {
            check(System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("quarantine alerts skipped: {}", e.getMessage());
        }
    }

    /** one round: count each organization's new entries, send what is due */
    void check(long now) {
        Map<Long, Settings> all = all();
        List<Quarantine.Meta> entries = quarantine.all();
        String newest = entries.isEmpty() ? "" : entries.get(entries.size() - 1).id();
        if (all.values().stream().noneMatch(Settings::alerts)) {
            return;
        }
        Map<String, Long> owners = orgs.owners();
        Long def = orgs.defaultOrgId();
        for (Map.Entry<Long, Settings> e : all.entrySet()) {
            Settings s = e.getValue();
            if (!s.alerts()) {
                states.remove(e.getKey());
                continue;
            }
            State st = states.computeIfAbsent(e.getKey(), k -> new State());
            synchronized (st) {
                if (st.cursor == null) {
                    st.cursor = newest;                  // only what arrives from now on is reported
                    continue;
                }
                Predicate<Quarantine.Meta> mine = Quarantine.ownedBy(e.getKey(), owners, def);
                String cursor = st.cursor;
                entries.stream().filter(m -> m.id().compareTo(cursor) > 0 && mine.test(m)).forEach(st.pending::add);
                st.cursor = newest.compareTo(cursor) > 0 ? newest : cursor;
                int records = st.pending.stream().mapToInt(Quarantine.Meta::count).sum();
                if (records >= s.minRecords() && now - st.lastSent >= s.quietMinutes() * 60_000L) {
                    List<Quarantine.Meta> batch = List.copyOf(st.pending);
                    st.pending.clear();
                    st.lastSent = now;
                    send(e.getKey(), s, batch, false);
                }
            }
        }
    }

    /**
     * Another alert to the organization's recipients (the same e-mail addresses and webhook as quarantine alerts). The
     * webhook gets {event, organization, sentAt, url} plus the details; returns what was sent (empty when the
     * organization has no recipients).
     */
    public Map<String, Object> notify(long orgId, String event, String subject, String text, JsonObject details, String page) {
        return notify(orgId, settings(orgId).recipients(), event, subject, text, details, page);
    }

    /** the same to other recipients: e.g. a pipeline's own when its disk buffer fills up (FR-RES-05) */
    public Map<String, Object> notify(long orgId, Recipients s, String event, String subject, String text, JsonObject details, String page) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!s.any()) {
            return result;
        }
        OrgService.Org org;
        try {
            org = orgs.get(orgId);
        } catch (ApiException e) {
            return result;
        }
        JsonObject b = details.deepCopy();
        b.addProperty("event", event);
        JsonObject o = new JsonObject();
        o.addProperty("slug", org.slug());
        o.addProperty("name", org.name());
        b.add("organization", o);
        b.addProperty("sentAt", Instant.now().toString());
        b.addProperty("url", publicUrl + "/" + org.slug() + "/" + page);
        String subj = "BIMBA Setu: " + subject + " (" + org.name() + ")";
        if (!s.emails().isEmpty()) {
            String body = text + "\n\nOpen it: " + Json.str(b, "url") + "\n";
            for (String to : s.emails()) {
                mailer.send(to, subj, body, subj);
            }
            result.put("email", mailer.configured() ? "sent to " + s.emails().size() + " recipient(s)"
                    : "not sent: SMTP is not set up on this Setu server (BIMBA_SMTP_HOST)");
        }
        if (!s.webhookUrl().isEmpty()) {
            result.put("webhook", post(s, b));
        }
        log.info("{} alert for {}: {}", event, org.slug(), result);
        return result;
    }

    /** sends a test alert with the saved settings: {email: queued | not configured | ..., webhook: HTTP 200 | error} */
    public Map<String, Object> test(long orgId) {
        Settings s = settings(orgId);
        return send(orgId, s, List.of(), true);
    }

    private Map<String, Object> send(long orgId, Settings s, List<Quarantine.Meta> batch, boolean test) {
        Map<String, Object> result = new LinkedHashMap<>();
        OrgService.Org org;
        try {
            org = orgs.get(orgId);
        } catch (ApiException e) {
            return result;
        }
        JsonObject body = body(org, batch, test);
        int records = body.get("records").getAsInt();
        String subject = test ? "BIMBA Setu: test alert for " + org.name()
                : "BIMBA Setu: " + records + " reading" + (records == 1 ? "" : "s") + " quarantined (" + org.name() + ")";
        if (!s.emails().isEmpty()) {
            String text = text(org, body, test);
            for (String to : s.emails()) {
                mailer.send(to, subject, text, subject);
            }
            result.put("email", mailer.configured() ? "sent to " + s.emails().size() + " recipient(s)"
                    : "not sent: SMTP is not set up on this Setu server (BIMBA_SMTP_HOST)");
        }
        if (!s.webhookUrl().isEmpty()) {
            result.put("webhook", post(s.recipients(), body));
        }
        if (!test) {
            log.info("quarantine alert for {}: {} reading(s) {}", org.slug(), records, result);
        }
        return result;
    }

    private JsonObject body(OrgService.Org org, List<Quarantine.Meta> batch, boolean test) {
        JsonObject b = new JsonObject();
        b.addProperty("event", test ? "bimba.quarantine.test" : "bimba.quarantine");
        JsonObject o = new JsonObject();
        o.addProperty("slug", org.slug());
        o.addProperty("name", org.name());
        b.add("organization", o);
        b.addProperty("records", batch.stream().mapToInt(Quarantine.Meta::count).sum());
        b.addProperty("entries", batch.size());
        Map<String, Integer> reasons = new TreeMap<>();
        Set<String> machines = new LinkedHashSet<>();
        for (Quarantine.Meta m : batch) {
            reasons.merge(m.reason().name(), m.count(), Integer::sum);
            machines.addAll(m.machines());
        }
        b.add("reasons", Json.GSON.toJsonTree(reasons));
        b.add("machines", Json.GSON.toJsonTree(machines.stream().limit(50).toList()));
        if (!batch.isEmpty()) {
            b.addProperty("from", batch.get(0).at().toString());
            b.addProperty("to", batch.get(batch.size() - 1).at().toString());
            b.addProperty("example", batch.get(batch.size() - 1).detail());
        }
        b.addProperty("sentAt", Instant.now().toString());
        b.addProperty("url", publicUrl + "/" + org.slug() + "/quarantine");
        return b;
    }

    private static String text(OrgService.Org org, JsonObject b, boolean test) {
        StringBuilder sb = new StringBuilder();
        if (test) {
            sb.append("This is a test of the quarantine alerts of ").append(org.name()).append(". Nothing was quarantined.\n\n");
        } else {
            sb.append(b.get("records").getAsInt()).append(" reading(s) of ").append(org.name())
                    .append(" did not match the BIMBA message format and were quarantined. They are not delivered until they are replayed.\n\n");
            b.getAsJsonObject("reasons").entrySet().forEach(r -> sb.append("  ").append(r.getKey()).append(": ").append(r.getValue()).append('\n'));
            if (!b.getAsJsonArray("machines").isEmpty()) {
                List<String> ms = new ArrayList<>();
                b.getAsJsonArray("machines").forEach(x -> ms.add(x.getAsString()));
                sb.append("\nMachines: ").append(String.join(", ", ms)).append('\n');
            }
            if (b.has("example")) {
                sb.append("Example: ").append(Json.str(b, "example")).append('\n');
            }
        }
        sb.append("\nReview them: ").append(Json.str(b, "url")).append('\n');
        return sb.toString();
    }

    private String post(Recipients s, JsonObject body) {
        String json = Json.GSON.toJson(body);
        try {
            HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(s.webhookUrl())).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("User-Agent", "BIMBA-Setu")
                    .header("X-BIMBA-Event", Json.str(body, "event"))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (!s.webhookSecret().isEmpty()) {
                r.header("X-BIMBA-Signature", "sha256=" + sign(secrets.open(s.webhookSecret()), json));
            }
            HttpResponse<Void> resp = http.send(r.build(), HttpResponse.BodyHandlers.discarding());
            return "HTTP " + resp.statusCode() + (resp.statusCode() / 100 == 2 ? "" : " (refused)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            log.warn("alert webhook {} failed: {}", s.webhookUrl(), e.toString());
            return "failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** hex HMAC-SHA256 of the body with the webhook secret */
    static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
