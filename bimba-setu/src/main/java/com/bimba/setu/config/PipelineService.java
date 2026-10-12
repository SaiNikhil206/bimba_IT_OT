package com.bimba.setu.config;

import com.bimba.setu.SetuService;
import com.bimba.setu.Json;
import com.bimba.setu.canonical.QuarantineSettings;
import com.bimba.setu.canonical.TagRegistry;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pipelines as Setu console manages them: which machines' data (all, or the ones named) goes to one primary
 * destination, to secondaries in order while the primary is down, and as a copy to every mirror. A draft (inactive)
 * pipeline sends nothing; an active one needs a primary. Every change applies at once.
 */
@Service
public class PipelineService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("BIMBA.Pipelines");
    private final SetuService setu;
    private final DestinationStore store;
    private final com.bimba.setu.security.OrgService orgs;
    private final java.io.File dataDir;
    private final QuarantineSettings alerts;
    /** for the names the registry delivers tags under (null = none, e.g. in tests) */
    private final TagRegistry registry;

    public PipelineService(SetuService setu, DestinationStore store, com.bimba.setu.security.OrgService orgs,
                           @org.springframework.beans.factory.annotation.Value("${bimba.data-dir:data}") java.io.File dataDir,
                           QuarantineSettings alerts, TagRegistry registry) {
        this.setu = setu;
        this.store = store;
        this.orgs = orgs;
        this.dataDir = dataDir;
        this.alerts = alerts;
        this.registry = registry;
    }

    /**
     * The organization's pipelines with their live status, plus what the form offers: its destinations and its machines
     * of the tag tree (other organizations' machines are not shown and cannot be selected).
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> list(long orgId) {
        Map<String, Object> status = setu.status();
        Map<Long, Object> live = new LinkedHashMap<>();
        if (status.get("delivery") instanceof Map<?, ?> d && d.get("pipelines") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> p = (Map<String, Object>) o;
                live.put(((Number) p.get("id")).longValue(), p);
            }
        }
        List<Map<String, Object>> pipelines = new ArrayList<>();
        for (JsonObject p : store.pipelines(orgId)) {
            JsonObject shown = p.deepCopy();
            if (shown.has("bufferAlerts") && shown.get("bufferAlerts").isJsonObject()) {
                JsonObject ba = shown.getAsJsonObject("bufferAlerts");
                ba.addProperty("webhookSecret", QuarantineSettings.masked(Json.str(ba, "webhookSecret")));
            }
            Map<String, Object> m = (Map<String, Object>) Json.toJava(Json.GSON.toJson(shown));
            m.put("id", p.get("id").getAsLong());
            m.put("primary", p.has("primary") && !p.get("primary").isJsonNull() ? p.get("primary").getAsLong() : null);
            m.put("secondary", ids(p, "secondary"));
            m.put("mirrors", ids(p, "mirrors"));
            m.put("status", live.get(p.get("id").getAsLong()));
            pipelines.add(m);
        }
        List<Map<String, Object>> destinations = new ArrayList<>();
        for (JsonObject d : store.list(orgId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.get("id").getAsLong());
            m.put("name", Json.str(d, "name"));
            m.put("service", Json.str(d, "service"));
            m.put("transport", Json.str(d, "transport"));
            m.put("url", Json.str(d, "url"));
            m.put("brokerUrl", Json.str(d, "brokerUrl"));
            m.put("bootstrapServers", Json.str(d, "bootstrapServers"));
            m.put("topic", Json.str(d, "topic"));
            destinations.add(m);
        }
        List<String> machines = List.of();
        List<Object> tags = List.of();
        if (status.get("collector") instanceof Map<?, ?> c && c.get("machines") instanceof List<?> all) {
            machines = orgs.machinesOf(orgId, all.stream().map(String::valueOf).toList());
            java.util.Set<String> own = new java.util.HashSet<>(machines);
            if (c.get("tags") instanceof List<?> t) {
                Map<String, TagRegistry.Entry> renamed = new java.util.HashMap<>();
                if (registry != null) {
                    registry.entries(orgId).stream().filter(TagRegistry.Entry::renames).forEach(e -> renamed.put(e.machine() + "/" + e.tag(), e));
                }
                tags = t.stream().filter(x -> x instanceof Map<?, ?> tm && own.contains(String.valueOf(tm.get("machine"))))
                        .map(x -> delivered((Map<String, Object>) x, renamed)).toList();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pipelines", pipelines);
        m.put("destinations", destinations);
        m.put("machines", machines);
        m.put("tags", tags);
        // for the buffer sizing formula (FR-RES-05): Setu's default size per route and the free disk space
        BimbaConfig c = setu.config();
        m.put("bufferDefaultMegabytes", c == null ? new BimbaConfig.Buffer().maxMegabytes : c.buffer.maxMegabytes);
        m.put("diskFreeBytes", dataDir.getUsableSpace());
        m.put("source", status.get("destinationSource"));
        m.put("problems", status.get("destinationProblems"));
        return m;
    }

    /** Adds (id null) or replaces a pipeline; returns {id, name}. */
    public synchronized Map<String, Object> save(long orgId, Long id, JsonObject in, String by) throws ApiException {
        List<JsonObject> existing = store.pipelines(orgId);
        JsonObject old = null;
        if (id != null) {
            old = existing.stream().filter(p -> p.get("id").getAsLong() == id).findFirst()
                    .orElseThrow(() -> new ApiException(404, "not_found", "This pipeline no longer exists. Reload the page."));
        }
        JsonObject p = build(orgId, in, old);
        String name = Json.str(p, "name");
        for (JsonObject e : existing) {
            if (Json.str(e, "name").equalsIgnoreCase(name) && (id == null || e.get("id").getAsLong() != id)) {
                throw new ApiException(409, "name", "A pipeline named " + name + " already exists.");
            }
        }
        long saved;
        if (id == null) {
            saved = store.insertPipeline(orgId, p, by);
        } else {
            store.updatePipeline(orgId, id, p, by);
            saved = id;
        }
        setu.reloadNow();
        return Map.of("id", saved, "name", name);
    }

    /** Activates (needs a primary) or pauses a pipeline; returns its name. */
    public synchronized String setActive(long orgId, long id, boolean active, String by) throws ApiException {
        JsonObject p = store.pipelines(orgId).stream().filter(x -> x.get("id").getAsLong() == id).findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "This pipeline no longer exists. Reload the page."));
        if (active && (!p.has("primary") || p.get("primary").isJsonNull())) {
            throw new ApiException(400, "primary", "Choose a primary destination before activating " + Json.str(p, "name") + ".");
        }
        store.setPipelineActive(orgId, id, active, by);
        setu.reloadNow();
        return Json.str(p, "name");
    }

    /** Returns the name of the deleted pipeline. */
    public synchronized String delete(long orgId, long id) throws ApiException {
        String name = store.pipelines(orgId).stream().filter(x -> x.get("id").getAsLong() == id).map(x -> Json.str(x, "name")).findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "This pipeline no longer exists. Reload the page."));
        store.deletePipeline(orgId, id);
        setu.reloadNow();
        removeBuffers(id);
        return name;
    }

    /** a deleted pipeline's disk buffers: removed when they hold no batch; kept (and logged) when data is still waiting */
    private void removeBuffers(long id) {
        java.nio.file.Path dir = new java.io.File(dataDir, "buffer/pipelines/" + id).toPath();
        if (!java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> all = java.nio.file.Files.walk(dir)) {
            long batches = all.filter(f -> f.getFileName().toString().endsWith(".json")).count();
            if (batches > 0) {
                log.warn("pipeline {} deleted with {} undelivered or refused batch(es) still in {}; they are kept", id, batches, dir);
                return;
            }
        } catch (java.io.IOException e) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> all = java.nio.file.Files.walk(dir)) {
            all.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (java.io.IOException e) {
            log.warn("could not remove {}: {}", dir, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ validation

    /** Whether the pipeline belongs to the organization (its rejected batches are only shown to and handled by it). */
    public boolean owns(long orgId, long id) {
        return store.pipelines(orgId).stream().anyMatch(p -> p.get("id").getAsLong() == id);
    }

    private JsonObject build(long orgId, JsonObject in, JsonObject old) throws ApiException {
        String name = Json.str(in, "name").trim();
        if (name.isEmpty() || name.length() > 80) {
            throw new ApiException(400, "name", "Give the pipeline a name (up to 80 characters).");
        }
        String description = Json.str(in, "description").trim();
        if (description.length() > 300) {
            throw new ApiException(400, "description", "The description can have up to 300 characters.");
        }
        boolean active = in.has("active") && in.get("active").getAsBoolean();

        JsonObject machines = new JsonObject();
        JsonObject m = in.has("machines") && in.get("machines").isJsonObject() ? in.getAsJsonObject("machines") : new JsonObject();
        if ("selected".equals(Json.str(m, "mode"))) {
            Set<String> names = new LinkedHashSet<>();
            if (m.has("names") && m.get("names").isJsonArray()) {
                m.getAsJsonArray("names").forEach(e -> {
                    String n = e.getAsString().trim();
                    if (!n.isEmpty()) {
                        names.add(n);
                    }
                });
            }
            if (names.isEmpty()) {
                throw new ApiException(400, "machines", "Choose at least one machine, or send all machines.");
            }
            Map<String, Long> owners = orgs.owners();
            Long defaultOrg = orgs.defaultOrgId();
            for (String n : names) {
                if (!com.bimba.setu.security.OrgService.owns(orgId, n, owners, defaultOrg)) {
                    throw new ApiException(400, "machines", n + " is not a machine of this organization.");
                }
            }
            machines.addProperty("mode", "selected");
            JsonArray a = new JsonArray();
            names.forEach(a::add);
            machines.add("names", a);
        } else {
            machines.addProperty("mode", "all");
        }

        JsonObject tags = new JsonObject();
        JsonObject t = in.has("tags") && in.get("tags").isJsonObject() ? in.getAsJsonObject("tags") : new JsonObject();
        if ("patterns".equals(Json.str(t, "mode"))) {
            JsonArray include = patterns(t, "include");
            JsonArray exclude = patterns(t, "exclude");
            if (include.isEmpty() && exclude.isEmpty()) {
                throw new ApiException(400, "tags", "Enter at least one tag pattern, or send all tags.");
            }
            tags.addProperty("mode", "patterns");
            tags.add("include", include);
            tags.add("exclude", exclude);
        } else {
            tags.addProperty("mode", "all");
        }

        JsonObject rate = new JsonObject();
        JsonObject r = in.has("rate") && in.get("rate").isJsonObject() ? in.getAsJsonObject("rate") : new JsonObject();
        String mode = Json.str(r, "mode").isEmpty() ? "change" : Json.str(r, "mode");
        if (!Set.of("change", "sample", "average", "min", "max", "count").contains(mode)) {
            throw new ApiException(400, "rate", "Rate must be every change, a snapshot, or the average, minimum, maximum or count of a window.");
        }
        rate.addProperty("mode", mode);
        if (!"change".equals(mode)) {
            int seconds;
            try {
                seconds = (int) Double.parseDouble(Json.str(r, "seconds"));
            } catch (NumberFormatException e) {
                seconds = 0;
            }
            if (seconds < 1 || seconds > 86_400) {
                throw new ApiException(400, "rate", "The interval must be 1 to 86400 seconds.");
            }
            rate.addProperty("seconds", seconds);
        }

        // batching (FR-CAN-06): readings per envelope and the longest wait; empty = Setu's defaults
        JsonObject batch = new JsonObject();
        JsonObject bi = in.has("batch") && in.get("batch").isJsonObject() ? in.getAsJsonObject("batch") : new JsonObject();
        Integer maxMessages = optionalInt(bi, "maxMessages", 1, 10_000, "Readings per batch: 1 to 10000 (empty = Setu's default).");
        Integer maxWaitMs = optionalInt(bi, "maxWaitMs", 50, 600_000, "Longest wait: 50 to 600000 ms (empty = Setu's default).");
        if (maxMessages != null) {
            batch.addProperty("maxMessages", maxMessages);
        }
        if (maxWaitMs != null) {
            batch.addProperty("maxWaitMs", maxWaitMs);
        }

        // FR-RES-05: the size of each of its routes' disk buffers (from the sizing formula); empty = Setu's
        JsonObject buffer = new JsonObject();
        JsonObject bu = in.has("buffer") && in.get("buffer").isJsonObject() ? in.getAsJsonObject("buffer") : new JsonObject();
        Integer bufferMb = optionalInt(bu, "maxMegabytes", 1, 102_400, "Disk buffer: 1 to 102400 MB per route (empty = Setu's default).",
                "buffer");
        if (bufferMb != null) {
            buffer.addProperty("maxMegabytes", bufferMb);
        }

        // FR-RES-05: who is told when one of its disk buffers is 70 % full (and back): its own e-mail addresses and / or
        // webhook, and (default) the organization's alert recipients of the Quarantine page
        JsonObject ba = in.has("bufferAlerts") && in.get("bufferAlerts").isJsonObject() ? in.getAsJsonObject("bufferAlerts") : new JsonObject();
        JsonObject oldBa = old != null && old.has("bufferAlerts") && old.get("bufferAlerts").isJsonObject() ? old.getAsJsonObject("bufferAlerts")
                : new JsonObject();
        QuarantineSettings.Recipients to = alerts.recipients(ba, Json.str(oldBa, "webhookSecret"), "bufferAlerts");
        boolean toOrganization = !ba.has("organization") || ba.get("organization").isJsonNull() || ba.get("organization").getAsBoolean();
        JsonObject bufferAlerts = new JsonObject();
        if (to.any() || !toOrganization) {
            JsonArray emails = new JsonArray();
            to.emails().forEach(emails::add);
            bufferAlerts.add("emails", emails);
            bufferAlerts.addProperty("webhookUrl", to.webhookUrl());
            bufferAlerts.addProperty("webhookSecret", to.webhookSecret());
            bufferAlerts.addProperty("organization", toOrganization);
        }

        // FR-RTE-05: drop duplicates, send only changed values (numbers: beyond the deadband)
        JsonObject filters = new JsonObject();
        JsonObject fi = in.has("filters") && in.get("filters").isJsonObject() ? in.getAsJsonObject("filters") : new JsonObject();
        boolean dedupe = fi.has("dedupe") && fi.get("dedupe").getAsBoolean();
        boolean changeOnly = fi.has("changeOnly") && fi.get("changeOnly").getAsBoolean();
        double deadband = 0;
        if (changeOnly && !Json.str(fi, "deadband").isBlank()) {
            try {
                deadband = Double.parseDouble(Json.str(fi, "deadband").trim());
            } catch (NumberFormatException e) {
                deadband = -1;
            }
            if (deadband < 0 || Double.isNaN(deadband) || Double.isInfinite(deadband)) {
                throw new ApiException(400, "filters", "The deadband is a number of 0 or more (0 = every change is sent).");
            }
        }
        if (dedupe) {
            filters.addProperty("dedupe", true);
        }
        if (changeOnly) {
            filters.addProperty("changeOnly", true);
            filters.addProperty("deadband", deadband);
        }
        // every change: send unchanged tags again every N s (a keep-alive, so steady and dead tags can be told apart)
        JsonObject refresh = new JsonObject();
        JsonObject rin = in.has("refresh") && in.get("refresh").isJsonObject() ? in.getAsJsonObject("refresh") : new JsonObject();
        Integer refreshSeconds = "change".equals(mode) ? optionalInt(rin, "seconds", 1, 86_400,
                "Send unchanged values again every 1 to 86400 seconds (empty = never).", "refresh") : null;
        if (refreshSeconds != null) {
            refresh.addProperty("seconds", refreshSeconds);
        }
        boolean gapMarkers = !in.has("gapMarkers") || in.get("gapMarkers").isJsonNull() || in.get("gapMarkers").getAsBoolean();

        Set<Long> known = new LinkedHashSet<>();
        store.list(orgId).forEach(d -> known.add(d.get("id").getAsLong()));     // only this organization's destinations
        Set<Long> used = new LinkedHashSet<>();
        Long primary = null;
        if (in.has("primary") && !in.get("primary").isJsonNull() && !Json.str(in, "primary").isBlank()) {
            primary = destination(in.get("primary"), known, used, "primary");
        }
        if (active && primary == null) {
            throw new ApiException(400, "primary", "An active pipeline needs a primary destination. Choose one, or save it as a draft.");
        }
        JsonArray secondary = new JsonArray();
        for (JsonElement e : array(in, "secondary")) {
            secondary.add(destination(e, known, used, "secondary"));
        }
        if (primary == null && !secondary.isEmpty()) {
            throw new ApiException(400, "primary", "Choose a primary destination; secondaries are only used while it is down.");
        }
        JsonArray mirrors = new JsonArray();
        for (JsonElement e : array(in, "mirrors")) {
            mirrors.add(destination(e, known, used, "mirror"));
        }

        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        p.addProperty("description", description);
        p.addProperty("active", active);
        p.add("machines", machines);
        p.add("tags", tags);
        p.add("rate", rate);
        if (!batch.isEmpty()) {
            p.add("batch", batch);
        }
        if (!buffer.isEmpty()) {
            p.add("buffer", buffer);
        }
        if (!bufferAlerts.isEmpty()) {
            p.add("bufferAlerts", bufferAlerts);
        }
        if (!filters.isEmpty()) {
            p.add("filters", filters);
        }
        p.addProperty("gapMarkers", gapMarkers);
        if (!refresh.isEmpty()) {
            p.add("refresh", refresh);
        }
        if (primary != null) {
            p.addProperty("primary", primary);
        }
        p.add("secondary", secondary);
        p.add("mirrors", mirrors);
        if (old != null && old.has("legacyBuffer")) {
            p.add("legacyBuffer", old.get("legacyBuffer"));      // keeps using the buffers it already has
        }
        return p;
    }

    /** a whole number in [min, max], null when empty */
    private static Integer optionalInt(JsonObject o, String key, int min, int max, String msg) throws ApiException {
        return optionalInt(o, key, min, max, msg, "batch");
    }

    private static Integer optionalInt(JsonObject o, String key, int min, int max, String msg, String field) throws ApiException {
        String v = Json.str(o, key).trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            double d = Double.parseDouble(v);
            if (d == Math.rint(d) && d >= min && d <= max) {
                return (int) d;
            }
        } catch (NumberFormatException ignored) {
            // below
        }
        throw new ApiException(400, field, msg);
    }

    private static long destination(JsonElement e, Set<Long> known, Set<Long> used, String role) throws ApiException {
        long id;
        try {
            id = e.getAsLong();
        } catch (RuntimeException ex) {
            throw new ApiException(400, role, "Unknown " + role + " destination.");
        }
        if (!known.contains(id)) {
            throw new ApiException(400, role, "A chosen " + role + " destination no longer exists. Reload the page.");
        }
        if (!used.add(id)) {
            throw new ApiException(400, role, "A destination can be used only once in a pipeline (as primary, secondary or mirror).");
        }
        return id;
    }

    /** glob patterns, one per entry: * and ? wildcards, "machine/tag" or just "tag" */
    private static JsonArray patterns(JsonObject t, String key) throws ApiException {
        JsonArray out = new JsonArray();
        for (JsonElement e : array(t, key)) {
            String g = e.getAsString().trim();
            if (g.isEmpty()) {
                continue;
            }
            if (g.length() > 200 || g.chars().filter(c -> c == '/').count() > 1) {
                throw new ApiException(400, "tags", "Tag pattern \"" + g + "\": use machine/tag or tag, with * and ? as wildcards.");
            }
            out.add(g);
        }
        return out;
    }

    /** a collected tag, plus deliveredMachine / deliveredTag when the registry renames it (pipelines match either name) */
    static Object delivered(Map<String, Object> tag, Map<String, TagRegistry.Entry> renamed) {
        TagRegistry.Entry e = renamed.get(tag.get("machine") + "/" + tag.get("tag"));
        if (e == null) {
            return tag;
        }
        Map<String, Object> m = new LinkedHashMap<>(tag);
        m.put("deliveredMachine", e.machineId() != null ? e.machineId() : e.machine());
        m.put("deliveredTag", e.tagName() != null ? e.tagName() : e.tag());
        return m;
    }

    private static List<Long> ids(JsonObject p, String key) {
        List<Long> out = new ArrayList<>();
        array(p, key).forEach(e -> out.add(e.getAsLong()));
        return out;
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }
}
