package com.bimba.gateway.config;

import com.bimba.gateway.GatewayService;
import com.bimba.gateway.Json;
import com.bimba.gateway.security.SecurityService.ApiException;
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
 * Pipelines as the gateway console manages them: which machines' data (all, or the ones named) goes to one primary
 * destination, to secondaries in order while the primary is down, and as a copy to every mirror. A draft (inactive)
 * pipeline sends nothing; an active one needs a primary. Every change applies at once.
 */
@Service
public class PipelineService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("BIMBA.Pipelines");
    private final GatewayService gateway;
    private final DestinationStore store;
    private final com.bimba.gateway.security.OrgService orgs;
    private final java.io.File dataDir;

    public PipelineService(GatewayService gateway, DestinationStore store, com.bimba.gateway.security.OrgService orgs,
                           @org.springframework.beans.factory.annotation.Value("${bimba.data-dir:data}") java.io.File dataDir) {
        this.gateway = gateway;
        this.store = store;
        this.orgs = orgs;
        this.dataDir = dataDir;
    }

    /**
     * The organization's pipelines with their live status, plus what the form offers: its destinations and its machines
     * of the tag tree (other organizations' machines are not shown and cannot be selected).
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> list(long orgId) {
        Map<String, Object> status = gateway.status();
        Map<Long, Object> live = new LinkedHashMap<>();
        if (status.get("delivery") instanceof Map<?, ?> d && d.get("pipelines") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> p = (Map<String, Object>) o;
                live.put(((Number) p.get("id")).longValue(), p);
            }
        }
        List<Map<String, Object>> pipelines = new ArrayList<>();
        for (JsonObject p : store.pipelines(orgId)) {
            Map<String, Object> m = (Map<String, Object>) Json.toJava(Json.GSON.toJson(p));
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
                tags = t.stream().filter(x -> x instanceof Map<?, ?> tm && own.contains(String.valueOf(tm.get("machine"))))
                        .map(x -> (Object) x).toList();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pipelines", pipelines);
        m.put("destinations", destinations);
        m.put("machines", machines);
        m.put("tags", tags);
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
        gateway.reloadNow();
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
        gateway.reloadNow();
        return Json.str(p, "name");
    }

    /** Returns the name of the deleted pipeline. */
    public synchronized String delete(long orgId, long id) throws ApiException {
        String name = store.pipelines(orgId).stream().filter(x -> x.get("id").getAsLong() == id).map(x -> Json.str(x, "name")).findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "This pipeline no longer exists. Reload the page."));
        store.deletePipeline(orgId, id);
        gateway.reloadNow();
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
                if (!com.bimba.gateway.security.OrgService.owns(orgId, n, owners, defaultOrg)) {
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
        if (!Set.of("change", "sample", "average").contains(mode)) {
            throw new ApiException(400, "rate", "Rate must be every change, a snapshot or an average.");
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

    private static List<Long> ids(JsonObject p, String key) {
        List<Long> out = new ArrayList<>();
        array(p, key).forEach(e -> out.add(e.getAsLong()));
        return out;
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }
}
