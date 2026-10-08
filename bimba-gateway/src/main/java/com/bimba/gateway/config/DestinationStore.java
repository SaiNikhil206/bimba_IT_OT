package com.bimba.gateway.config;

import com.bimba.gateway.Db;
import com.bimba.gateway.Json;
import com.bimba.gateway.security.SecurityService.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Where destinations and pipelines live: the shared database. Both belong to one organization (org_id); the console
 * only ever reads and changes the ones of the signed-in user's organization, the delivery engine runs every organization's.
 * <ul>
 *   <li>bimba_gw_destination: one row per destination (a connection: address, sign-in), settings as JSON with every
 *       secret encrypted by {@link SecretBox}. A destination alone receives nothing.</li>
 *   <li>bimba_gw_pipeline: which machines' data goes to which destinations (primary, secondaries, mirrors), by id.</li>
 * </ul>
 * The gateway keeps a copy of both in data/destinations.json, so it keeps delivering when it starts while the database
 * is down. Upgrades: destinations still in config.json are imported once (and removed from the file); the former
 * per-destination roles become one pipeline, "Plant data", once.
 */
@Component
public class DestinationStore {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Destinations");
    private static final String IMPORTED = "destinations.imported";
    private static final String MIGRATED = "pipelines.migrated";
    /** header names and Kafka properties whose values are secrets */
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(authorization|api[-_.]?key|token|secret|password|passwd|jaas|credential).*");

    private final Db db;
    private final SecretBox box;
    private final com.bimba.gateway.security.OrgService orgs;
    private final File dataDir;
    private final File cacheFile;
    private volatile String loadedVersion;
    private volatile String source = "not loaded";
    private volatile List<String> problems = List.of();
    private volatile Runnable onChange;
    private volatile Map<String, Long> machineOwners = Map.of();
    private volatile Long defaultOrgId;

    public DestinationStore(Db db, SecretBox box, com.bimba.gateway.security.OrgService orgs, @Value("${bimba.data-dir:data}") File dataDir) {
        this.db = db;
        this.box = box;
        this.orgs = orgs;
        this.dataDir = dataDir;
        this.cacheFile = new File(dataDir, "destinations.json");
    }

    // ------------------------------------------------------------------ what the delivery engine runs

    /**
     * The active pipelines with their destinations (secrets decrypted): from the database, else from the saved copy,
     * else (never imported yet and the database down) from the roles of the destinations still in config.json.
     */
    public synchronized List<BimbaConfig.Pipeline> runtime(File configFile, List<BimbaConfig.Destination> fromFile) {
        List<JsonObject> dests;
        List<JsonObject> pipes;
        try {
            importOnce(configFile);
            migrateOnce();
            dests = list();
            pipes = pipelines();
            machineOwners = Map.copyOf(orgs.owners());
            defaultOrgId = orgs.defaultOrgId();
            loadedVersion = version();
            source = "database";
            writeCache(dests, pipes);
        } catch (DataAccessException e) {
            loadedVersion = null;
            JsonObject cache = readCache();
            if (cache != null) {
                source = "saved copy (database not reachable)";
                dests = objects(cache.getAsJsonArray("destinations"));
                pipes = objects(cache.getAsJsonArray("pipelines"));
                Map<String, Long> owners = new HashMap<>();
                if (cache.has("machineOwners") && cache.get("machineOwners").isJsonObject()) {
                    cache.getAsJsonObject("machineOwners").entrySet().forEach(en -> owners.put(en.getKey(), en.getValue().getAsLong()));
                }
                machineOwners = Map.copyOf(owners);
                defaultOrgId = cache.has("defaultOrgId") && !cache.get("defaultOrgId").isJsonNull() ? cache.get("defaultOrgId").getAsLong() : null;
                log.warn("database not reachable: delivering with the saved pipelines {}", cacheFile);
            } else {
                List<JsonObject> file = new ArrayList<>();
                fromFile.forEach(d -> file.add(Json.GSON.toJsonTree(d).getAsJsonObject()));
                dests = file;
                JsonObject legacy = legacyPipeline(dests);
                pipes = legacy == null ? List.of() : List.of(legacy);
                source = fromFile.isEmpty() ? "none (database not reachable)" : "config.json (database not reachable)";
                machineOwners = Map.of();
                defaultOrgId = null;
                log.warn("database not reachable and no saved pipelines: {}", source);
            }
        }
        return build(dests, pipes);
    }

    private List<BimbaConfig.Pipeline> build(List<JsonObject> dests, List<JsonObject> pipes) {
        List<String> issues = new ArrayList<>();
        Map<String, JsonObject> byRef = new HashMap<>();
        for (JsonObject row : dests) {
            JsonObject d = row.deepCopy();
            secrets(d, v -> {
                try {
                    return box.open(v);
                } catch (IllegalStateException e) {
                    issues.add(Json.str(row, "name") + ": " + e.getMessage());
                    return "";
                }
            });
            if (row.has("id")) {
                byRef.put("#" + row.get("id").getAsLong(), d);
            }
            byRef.put(org(row) + ":" + Json.str(row, "name").toLowerCase(Locale.ROOT), d);
        }
        // every connection needs its own MQTT / Kafka client id: a destination used by several active pipelines, or
        // destinations of different organizations with the same name, get the pipeline id added to theirs
        Map<String, Integer> uses = new HashMap<>();
        for (JsonObject p : pipes) {
            if (p.get("active").getAsBoolean()) {
                refs(p).forEach(r -> {
                    JsonObject d = byRef.get(r.startsWith("#") ? r : org(p) + ":" + r);
                    if (d != null) {
                        uses.merge(clientId(d), 1, Integer::sum);
                    }
                });
            }
        }
        List<BimbaConfig.Pipeline> out = new ArrayList<>();
        for (JsonObject p : pipes) {
            if (!p.get("active").getAsBoolean()) {
                continue;
            }
            BimbaConfig.Pipeline pl = new BimbaConfig.Pipeline();
            pl.id = p.has("id") ? p.get("id").getAsLong() : 0;
            pl.orgId = org(p);
            pl.name = Json.str(p, "name");
            pl.legacyBuffer = p.has("legacyBuffer") && p.get("legacyBuffer").getAsBoolean();
            JsonObject m = p.has("machines") && p.get("machines").isJsonObject() ? p.getAsJsonObject("machines") : new JsonObject();
            if ("selected".equals(Json.str(m, "mode")) && m.has("names")) {
                m.getAsJsonArray("names").forEach(n -> pl.machines.add(n.getAsString()));
            }
            JsonObject t = p.has("tags") && p.get("tags").isJsonObject() ? p.getAsJsonObject("tags") : new JsonObject();
            if ("patterns".equals(Json.str(t, "mode"))) {
                array(t, "include").forEach(e -> pl.tagInclude.add(e.getAsString()));
                array(t, "exclude").forEach(e -> pl.tagExclude.add(e.getAsString()));
            }
            JsonObject r = p.has("rate") && p.get("rate").isJsonObject() ? p.getAsJsonObject("rate") : new JsonObject();
            if (!Json.str(r, "mode").isEmpty()) {
                pl.rateMode = Json.str(r, "mode");
                pl.rateSeconds = r.has("seconds") ? r.get("seconds").getAsInt() : 60;
            }
            pl.primary = destination(p.get("primary"), "primary", pl, byRef, uses, issues);
            for (JsonElement e : array(p, "secondary")) {
                BimbaConfig.Destination d = destination(e, "failover", pl, byRef, uses, issues);
                if (d != null) {
                    pl.secondary.add(d);
                }
            }
            for (JsonElement e : array(p, "mirrors")) {
                BimbaConfig.Destination d = destination(e, "mirror", pl, byRef, uses, issues);
                if (d != null) {
                    pl.mirrors.add(d);
                }
            }
            if (pl.primary == null) {
                issues.add("pipeline " + pl.name + " has no primary destination and delivers nothing");
                continue;
            }
            out.add(pl);
        }
        issues.forEach(i -> log.warn("{}", i));
        problems = List.copyOf(issues);
        return out;
    }

    private static BimbaConfig.Destination destination(JsonElement ref, String role, BimbaConfig.Pipeline p, Map<String, JsonObject> byRef,
                                                       Map<String, Integer> uses, List<String> issues) {
        if (ref == null || ref.isJsonNull()) {
            return null;
        }
        String key = key(ref);
        JsonObject d = byRef.get(key.startsWith("#") ? key : p.orgId + ":" + key);
        if (d == null) {
            issues.add("pipeline " + p.name + ": destination " + ref + " no longer exists");
            return null;
        }
        if (d.has("orgId") && org(d) != p.orgId) {
            issues.add("pipeline " + p.name + ": destination " + ref + " belongs to another organization and is not used");
            return null;
        }
        BimbaConfig.Destination c = Json.GSON.fromJson(d, BimbaConfig.Destination.class);
        c.role = role;
        c.enabled = true;
        if (uses.getOrDefault(clientId(d), 0) > 1) {
            c.clientId = clientId(d) + "-p" + p.id;
        }
        return c;
    }

    /** the MQTT / Kafka client id a destination connects with */
    private static String clientId(JsonObject d) {
        String id = Json.str(d, "clientId");
        return id.isBlank() ? "bimba-" + Json.str(d, "name") : id;
    }

    /** owning organization of a row (0 = none: copies made before organizations) */
    private static long org(JsonObject o) {
        return o.has("orgId") && !o.get("orgId").isJsonNull() ? o.get("orgId").getAsLong() : 0;
    }

    /** destination reference in a pipeline: an id (database) or a name (pipelines made from older files) */
    private static String key(JsonElement ref) {
        return ref.getAsJsonPrimitive().isNumber() ? "#" + ref.getAsLong() : ref.getAsString().toLowerCase(Locale.ROOT);
    }

    private static List<String> refs(JsonObject p) {
        List<String> r = new ArrayList<>();
        if (p.has("primary") && !p.get("primary").isJsonNull()) {
            r.add(key(p.get("primary")));
        }
        array(p, "secondary").forEach(e -> r.add(key(e)));
        array(p, "mirrors").forEach(e -> r.add(key(e)));
        return r;
    }

    public String source() {
        return source;
    }

    public List<String> problems() {
        return problems;
    }

    /** machine -> organization as loaded with the pipelines (machines not listed belong to defaultOrgId()) */
    public Map<String, Long> machineOwners() {
        return machineOwners;
    }

    public Long defaultOrgId() {
        return defaultOrgId;
    }

    /** Called when another console (or a gateway that started while the database was down) changed the list. */
    public void onChange(Runnable r) {
        this.onChange = r;
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
    void watch() {
        Runnable r = onChange;
        if (r == null) {
            return;
        }
        String v;
        try {
            v = version();
        } catch (DataAccessException e) {
            return;                                   // keep delivering with what is loaded
        }
        if (!v.equals(loadedVersion)) {
            log.info("destinations or pipelines changed in the database, reloading");
            r.run();
        }
    }

    private String version() {
        return db.sql("SELECT (SELECT count(*) || '|' || coalesce(max(id), 0) || '|' || coalesce(max(updated_at)::text, '') FROM {s}.bimba_gw_destination)"
                + " || '/' || (SELECT count(*) || '|' || coalesce(max(id), 0) || '|' || coalesce(max(updated_at)::text, '') FROM {s}.bimba_gw_pipeline)"
                + " || '/' || (SELECT count(*) || '|' || coalesce(max(assigned_at)::text, '') FROM {s}.bimba_gw_machine)"
                + " || '/' || coalesce((SELECT value::text FROM {s}.bimba_gw_setting WHERE key = 'machines.default_org'), '')")
                .query(String.class).single();
    }

    // ------------------------------------------------------------------ destinations (secrets stay encrypted)

    /** Every organization's destinations, as stored (secrets encrypted), with id and orgId (what the engine runs). */
    public List<JsonObject> list() {
        return destinations(null);
    }

    /** The organization's destinations, as stored (secrets encrypted), with id and orgId. */
    public List<JsonObject> list(long orgId) {
        return destinations(orgId);
    }

    private List<JsonObject> destinations(Long orgId) {
        return db.sql("SELECT id, name, service, config::text, org_id, uid FROM {s}.bimba_gw_destination WHERE (? OR org_id = ?)"
                        + " ORDER BY lower(name), id")
                .params(orgId == null, orgId == null ? 0L : orgId)
                .query((rs, n) -> {
                    JsonObject d = JsonParser.parseString(rs.getString(4)).getAsJsonObject();
                    d.addProperty("id", rs.getLong(1));
                    d.addProperty("name", rs.getString(2));
                    if (!rs.getString(3).isEmpty()) {
                        d.addProperty("service", rs.getString(3));
                    }
                    d.addProperty("orgId", rs.getLong(5));
                    d.addProperty("uid", rs.getString(6));
                    return d;
                }).list();
    }

    public long insert(long orgId, JsonObject d, String by) {
        return db.sql("INSERT INTO {s}.bimba_gw_destination (org_id, name, service, position, config, created_at, updated_at, updated_by)"
                        + " VALUES (?, ?, ?, (SELECT coalesce(max(position), -1) + 1 FROM {s}.bimba_gw_destination), ?::jsonb, now(), now(), ?) RETURNING id")
                .params(orgId, Json.str(d, "name"), Json.str(d, "service"), Json.GSON.toJson(stored(d)), by).query(Long.class).single();
    }

    public void update(long orgId, long id, JsonObject d, String by) throws ApiException {
        int n = db.sql("UPDATE {s}.bimba_gw_destination SET name = ?, service = ?, config = ?::jsonb, updated_at = now(), updated_by = ?"
                        + " WHERE id = ? AND org_id = ?")
                .params(Json.str(d, "name"), Json.str(d, "service"), Json.GSON.toJson(stored(d)), by, id, orgId).update();
        if (n == 0) {
            throw notFound("destination", id);
        }
    }

    public void delete(long orgId, long id) throws ApiException {
        if (db.sql("DELETE FROM {s}.bimba_gw_destination WHERE id = ? AND org_id = ?").params(id, orgId).update() == 0) {
            throw notFound("destination", id);
        }
    }

    /** the config column: the destination without its row fields */
    private static JsonObject stored(JsonObject d) {
        JsonObject c = d.deepCopy();
        c.remove("id");
        c.remove("orgId");
        c.remove("uid");
        return c;
    }

    // ------------------------------------------------------------------ pipelines

    /** Every organization's pipelines: {id, orgId, name, description, active, machines, primary, secondary, mirrors}. */
    public List<JsonObject> pipelines() {
        return pipelineRows(null);
    }

    /** The organization's pipelines (destination references are ids of its own destinations). */
    public List<JsonObject> pipelines(long orgId) {
        return pipelineRows(orgId);
    }

    private List<JsonObject> pipelineRows(Long orgId) {
        return db.sql("SELECT id, name, description, active, config::text, org_id, uid FROM {s}.bimba_gw_pipeline WHERE (? OR org_id = ?)"
                        + " ORDER BY position, id")
                .params(orgId == null, orgId == null ? 0L : orgId)
                .query((rs, n) -> {
                    JsonObject p = JsonParser.parseString(rs.getString(5)).getAsJsonObject();
                    p.addProperty("id", rs.getLong(1));
                    p.addProperty("orgId", rs.getLong(6));
                    p.addProperty("name", rs.getString(2));
                    p.addProperty("description", rs.getString(3));
                    p.addProperty("active", rs.getBoolean(4));
                    p.addProperty("uid", rs.getString(7));
                    return p;
                }).list();
    }

    public long insertPipeline(long orgId, JsonObject p, String by) {
        return db.sql("INSERT INTO {s}.bimba_gw_pipeline (org_id, name, description, active, position, config, created_at, updated_at, updated_by)"
                        + " VALUES (?, ?, ?, ?, (SELECT coalesce(max(position), -1) + 1 FROM {s}.bimba_gw_pipeline), ?::jsonb, now(), now(), ?) RETURNING id")
                .params(orgId, Json.str(p, "name"), Json.str(p, "description"), p.get("active").getAsBoolean(),
                        Json.GSON.toJson(pipelineConfig(p)), by)
                .query(Long.class).single();
    }

    public void updatePipeline(long orgId, long id, JsonObject p, String by) throws ApiException {
        int n = db.sql("UPDATE {s}.bimba_gw_pipeline SET name = ?, description = ?, active = ?, config = ?::jsonb, updated_at = now(),"
                        + " updated_by = ? WHERE id = ? AND org_id = ?")
                .params(Json.str(p, "name"), Json.str(p, "description"), p.get("active").getAsBoolean(), Json.GSON.toJson(pipelineConfig(p)), by,
                        id, orgId)
                .update();
        if (n == 0) {
            throw notFound("pipeline", id);
        }
    }

    public void setPipelineActive(long orgId, long id, boolean active, String by) throws ApiException {
        int n = db.sql("UPDATE {s}.bimba_gw_pipeline SET active = ?, updated_at = now(), updated_by = ? WHERE id = ? AND org_id = ?")
                .params(active, by, id, orgId).update();
        if (n == 0) {
            throw notFound("pipeline", id);
        }
    }

    public void deletePipeline(long orgId, long id) throws ApiException {
        if (db.sql("DELETE FROM {s}.bimba_gw_pipeline WHERE id = ? AND org_id = ?").params(id, orgId).update() == 0) {
            throw notFound("pipeline", id);
        }
    }

    private static JsonObject pipelineConfig(JsonObject p) {
        JsonObject c = new JsonObject();
        for (String k : List.of("machines", "tags", "rate", "primary", "secondary", "mirrors", "legacyBuffer")) {
            if (p.has(k)) {
                c.add(k, p.get(k));
            }
        }
        return c;
    }

    private static ApiException notFound(String what, long id) {
        return new ApiException(404, "not_found", "There is no " + what + " with id " + id + ". Reload the page.");
    }

    // ------------------------------------------------------------------ secrets

    /**
     * Applies f to every secret value of a destination: auth password / token / OAuth2 client secret, MQTT/Kafka password,
     * proxy password, TLS private key, secret headers + Kafka properties.
     */
    public static void secrets(JsonObject d, UnaryOperator<String> f) {
        apply(obj(d, "auth"), "password", f);
        apply(obj(d, "auth"), "token", f);
        apply(obj(d, "auth"), "clientSecret", f);
        apply(d, "password", f);
        apply(d, "proxyPassword", f);
        apply(d, "tlsKey", f);
        for (String m : List.of("headers", "kafka")) {
            JsonObject map = obj(d, m);
            for (String k : List.copyOf(map.keySet())) {
                if (SECRET_KEY.matcher(k).matches()) {
                    apply(map, k, f);
                }
            }
        }
    }

    public JsonObject seal(JsonObject d) {
        secrets(d, box::seal);
        return d;
    }

    /** decrypted copy for a connection test; throws when a secret cannot be decrypted */
    public JsonObject open(JsonObject d) {
        JsonObject c = d.deepCopy();
        secrets(c, box::open);
        return c;
    }

    private static void apply(JsonObject o, String key, UnaryOperator<String> f) {
        String v = Json.str(o, key);
        if (!v.isEmpty()) {
            o.addProperty(key, f.apply(v));
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static List<JsonObject> objects(JsonArray a) {
        List<JsonObject> out = new ArrayList<>();
        if (a != null) {
            a.forEach(e -> out.add(e.getAsJsonObject()));
        }
        return out;
    }

    // ------------------------------------------------------------------ upgrades (once)

    private boolean done(String key) {
        return db.sql("SELECT count(*) FROM {s}.bimba_gw_setting WHERE key = ?").param(key).query(Long.class).single() > 0;
    }

    private void mark(String key, Map<String, ?> value) {
        db.sql("INSERT INTO {s}.bimba_gw_setting (key, value, updated_at) VALUES (?, ?::jsonb, now()) ON CONFLICT (key) DO NOTHING")
                .params(key, Json.GSON.toJson(value)).update();
    }

    /** destinations still in config.json -> database, then removed from the file */
    private void importOnce(File configFile) {
        if (done(IMPORTED)) {
            return;
        }
        JsonObject root;
        try {
            root = readJson(configFile);
        } catch (IOException | RuntimeException e) {
            log.warn("could not read {} to import its destinations: {}", configFile, e.getMessage());
            return;
        }
        JsonArray list = array(root, "destinations");
        int imported = db.tx(() -> {
            long existing = db.sql("SELECT count(*) FROM {s}.bimba_gw_destination").query(Long.class).single();
            int n = 0;
            if (existing == 0) {
                for (JsonElement e : list) {
                    if (e.isJsonObject() && !Json.str(e.getAsJsonObject(), "name").isBlank()) {
                        insert(orgs.ensureDefaultOrg(), seal(e.getAsJsonObject().deepCopy()), "config.json");
                        n++;
                    }
                }
            }
            mark(IMPORTED, Map.of("count", n, "from", configFile.getAbsolutePath()));
            return n;
        });
        log.info("imported {} destination(s) from {} into the database", imported, configFile);
        if (root.has("destinations")) {
            try {
                Files.createDirectories(dataDir.toPath());
                Files.copy(configFile.toPath(), new File(dataDir, "config.json.before-import").toPath(), StandardCopyOption.REPLACE_EXISTING);
                root.remove("destinations");
                writeAtomic(configFile.toPath(), Json.PRETTY.toJson(root) + "\n");
                log.info("removed the destinations from {} (copy of the old file: data/config.json.before-import)", configFile);
            } catch (IOException e) {
                log.warn("destinations imported, but {} could not be rewritten ({}); its destinations are ignored", configFile, e.getMessage());
            }
        }
    }

    /** the former roles (primary / failover / mirror + enabled) of the destinations -> one pipeline; roles removed */
    private void migrateOnce() {
        if (done(MIGRATED)) {
            return;
        }
        db.tx(() -> {
            long pipelines = db.sql("SELECT count(*) FROM {s}.bimba_gw_pipeline").query(Long.class).single();
            String made = "";
            if (pipelines == 0) {
                JsonObject p = legacyPipeline(list());
                if (p != null) {
                    insertPipeline(orgs.ensureDefaultOrg(), p, "upgrade");
                    made = Json.str(p, "name");
                }
            }
            db.sql("UPDATE {s}.bimba_gw_destination SET config = config - 'role' - 'enabled'").update();
            mark(MIGRATED, Map.of("pipeline", made));
            log.info("destinations no longer have roles; {}", made.isEmpty() ? "no pipeline was needed" : "created pipeline \"" + made + "\" from them");
            return null;
        });
    }

    /**
     * "Plant data": every machine, the first enabled primary as primary, the other enabled primaries and the failovers
     * as secondaries (in that order), the enabled mirrors as mirrors. Null when nothing was enabled.
     */
    static JsonObject legacyPipeline(List<JsonObject> dests) {
        List<JsonElement> primaries = new ArrayList<>();
        List<JsonElement> failovers = new ArrayList<>();
        JsonArray mirrors = new JsonArray();
        for (JsonObject d : dests) {
            if (d.has("enabled") && !d.get("enabled").getAsBoolean()) {
                continue;
            }
            JsonElement ref = d.has("id") ? d.get("id") : d.get("name");
            switch (Json.str(d, "role").isEmpty() ? "primary" : Json.str(d, "role").toLowerCase(Locale.ROOT)) {
                case "mirror" -> mirrors.add(ref);
                case "failover" -> failovers.add(ref);
                default -> primaries.add(ref);
            }
        }
        List<JsonElement> chain = new ArrayList<>(primaries);
        chain.addAll(failovers);
        if (chain.isEmpty() && mirrors.isEmpty()) {
            return null;
        }
        JsonObject p = new JsonObject();
        p.addProperty("name", "Plant data");
        p.addProperty("description", "Created from the destination roles of the previous version.");
        p.addProperty("active", !chain.isEmpty());
        JsonObject machines = new JsonObject();
        machines.addProperty("mode", "all");
        p.add("machines", machines);
        if (!chain.isEmpty()) {
            p.add("primary", chain.get(0));
        }
        JsonArray secondary = new JsonArray();
        chain.stream().skip(1).forEach(secondary::add);
        p.add("secondary", secondary);
        p.add("mirrors", mirrors);
        p.addProperty("legacyBuffer", true);
        return p;
    }

    // ------------------------------------------------------------------ saved copy

    private void writeCache(List<JsonObject> dests, List<JsonObject> pipes) {
        JsonObject c = new JsonObject();
        JsonArray d = new JsonArray();
        dests.forEach(d::add);
        JsonArray p = new JsonArray();
        pipes.forEach(p::add);
        c.add("destinations", d);
        c.add("pipelines", p);
        c.add("machineOwners", Json.GSON.toJsonTree(machineOwners));
        if (defaultOrgId != null) {
            c.addProperty("defaultOrgId", defaultOrgId);
        }
        try {
            Files.createDirectories(dataDir.toPath());
            writeAtomic(cacheFile.toPath(), Json.PRETTY.toJson(c));
        } catch (IOException e) {
            log.warn("could not save the pipelines to {}: {}", cacheFile, e.getMessage());
        }
    }

    /** {destinations, pipelines}; a copy from the version before pipelines (a plain list) gets its pipeline from the roles */
    private JsonObject readCache() {
        if (!cacheFile.isFile()) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8));
            if (e.isJsonObject()) {
                return e.getAsJsonObject();
            }
            JsonObject c = new JsonObject();
            c.add("destinations", e.getAsJsonArray());
            JsonArray p = new JsonArray();
            JsonObject legacy = legacyPipeline(objects(e.getAsJsonArray()));
            if (legacy != null) {
                p.add(legacy);
            }
            c.add("pipelines", p);
            return c;
        } catch (IOException | RuntimeException e) {
            log.warn("could not read the saved pipelines {}: {}", cacheFile, e.getMessage());
            return null;
        }
    }

    private static JsonObject readJson(File f) throws IOException {
        String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void writeAtomic(Path target, String text) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
