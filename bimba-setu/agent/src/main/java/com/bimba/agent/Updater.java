package com.bimba.agent;

import com.bimba.agent.SetuClient.Json;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic updates: Setu offers a newer installer in the heartbeat answer and it is run over the installed agent.
 * <p>
 * The service (user bimba-agent, no privileges) only downloads: over its own connection (client certificate) into
 * /var/lib/bimba-site-agent/update, checks size and SHA-256 and writes update/ready.json. The systemd path unit
 * bimba-site-agent-update.path sees that file and starts bimba-site-agent-update.service, which runs
 * {@code bimba-site-agent apply-update} as root. That step trusts nothing the service wrote: it copies the package into
 * its own folder (/var/lib/bimba-site-agent-update, root only), compares its SHA-256 and version with what Setu
 * publishes (GET /system/bimba-agent-update/release, Setu address and certificate pin from its own updater.json written
 * at install time), refuses older versions, then installs it the way the agent was installed: {@code dpkg -i} for the
 * .deb, install.sh from the .tar.gz. Both keep the registration and restart the service, which then reports the new
 * version. The result goes to last.json (readable by the service, reported to Setu).
 * <p>
 * {@code sudo bimba-site-agent updates off} switches updates off on this computer, whatever Setu says.
 */
final class Updater {
    static final String READY = "ready.json";
    static final String SETTINGS = "updater.json";
    static final String LAST = "last.json";
    static final String PATH_UNIT = Paths.SERVICE + "-update.path";
    private static final long MAX_PACKAGE = 1L << 30;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String[] UNIT_DIRS = {"/etc/systemd/system", "/lib/systemd/system", "/usr/lib/systemd/system"};

    /** the running service's updater (its report goes with every heartbeat) */
    static volatile Updater current;

    private final AgentConfig cfg;
    private final Supplier<SetuClient> client;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile String state = "idle";
    private volatile String target;
    private volatile String error;
    private volatile String at;

    Updater(AgentConfig cfg, Supplier<SetuClient> client) {
        this.cfg = cfg;
        this.client = client;
    }

    // ================================================================== the service

    /** removes downloads the running version made obsolete (after an update, or one that was refused) */
    void start() {
        current = this;
        try {
            Path ready = Paths.updateDir().resolve(READY);
            if (!Files.exists(ready, LinkOption.NOFOLLOW_LINKS)) {
                for (String kind : List.of("deb", "tar")) {
                    Files.deleteIfExists(Paths.updateDir().resolve(packageName(kind)));
                    Files.deleteIfExists(Paths.updateDir().resolve(packageName(kind) + ".part"));
                }
            }
        } catch (IOException e) {
            Log.warn("update folder: " + e.getMessage());
        }
    }

    /**
     * {kind, supported, why, allowed, state, version, error, at}: sent with every heartbeat. state = idle | downloading
     * | staged | installing | failed (for version).
     */
    Map<String, Object> report() {
        Map<String, Object> m = new LinkedHashMap<>();
        String kind = kind();
        JsonObject settings = settings();
        m.put("kind", kind);
        String why = whyNot(kind, settings);
        m.put("supported", why == null);
        m.put("why", why);
        m.put("allowed", settings == null || !settings.has("allowUpdates") || settings.get("allowUpdates").getAsBoolean());
        String s = state;
        String v = target;
        String err = error;
        String when = at;
        if (!busy.get()) {
            JsonObject last = read(Paths.updaterDir().resolve(LAST));
            if (Files.exists(Paths.updateDir().resolve(READY), LinkOption.NOFOLLOW_LINKS)) {
                s = "staged";
            } else if (last != null && v != null && v.equals(Json.str(last, "version"))
                    && Set.of("installing", "failed").contains(Json.str(last, "state"))) {
                s = Json.str(last, "state");
                err = Json.str(last, "error");
                when = Json.str(last, "at");
            }
        }
        m.put("state", s);
        m.put("version", v);
        m.put("error", "failed".equals(s) ? err : null);
        m.put("at", when);
        return m;
    }

    /** the heartbeat answer's "update": download and stage it (once per version, in the background) */
    void offer(JsonObject update) {
        String v = Json.str(update, "version");
        String kind = Json.str(update, "kind");
        if (v == null || compare(v, AgentMain.version()) <= 0) {
            return;
        }
        String mine = kind();
        JsonObject settings = settings();
        if (whyNot(mine, settings) != null || (settings != null && settings.has("allowUpdates") && !settings.get("allowUpdates").getAsBoolean())) {
            return;                                    // the report tells Setu why
        }
        if (!mine.equals(kind)) {
            fail(v, "Setu offered the " + kind + " installer; this computer was installed from the " + mine + " package.");
            return;
        }
        if (v.equals(target) && ("downloading".equals(state) || Files.exists(Paths.updateDir().resolve(READY), LinkOption.NOFOLLOW_LINKS))) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        target = v;
        state = "downloading";
        error = null;
        at = Instant.now().toString();
        Thread t = new Thread(() -> {
            try {
                download(update, mine);
            } catch (Exception e) {
                fail(v, "download failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                busy.set(false);
            }
        }, "update-download");
        t.setDaemon(true);
        t.start();
    }

    private void download(JsonObject update, String kind) throws Exception {
        String v = Json.str(update, "version");
        String sha = Json.str(update, "sha256");
        long size = update.has("size") ? update.get("size").getAsLong() : -1;
        String path = Json.str(update, "path");
        if (sha == null || !sha.matches("[0-9a-fA-F]{64}") || path == null || !path.startsWith("/system/bimba-agent-update/")) {
            throw new IOException("Setu's offer is incomplete");
        }
        Log.info("update " + AgentMain.version() + " -> " + v + ": downloading the " + kind + " installer (" + (size / 1_048_576) + " MB)");
        Files2.privateDir(Paths.updateDir());
        Path file = Paths.updateDir().resolve(packageName(kind));
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.deleteIfExists(part);
        client.get().download(cfg, path, part);
        long got = Files.size(part);
        String hash = sha256(part);
        if ((size >= 0 && got != size) || !hash.equalsIgnoreCase(sha)) {
            Files.deleteIfExists(part);
            throw new IOException("the download does not match Setu's installer (size " + got + ", SHA-256 " + hash + ")");
        }
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        JsonObject ready = new JsonObject();
        ready.addProperty("version", v);
        ready.addProperty("kind", kind);
        ready.addProperty("sha256", hash.toLowerCase(Locale.ROOT));
        ready.addProperty("from", AgentMain.version());
        ready.addProperty("at", Instant.now().toString());
        Files2.writePrivate(Paths.updateDir().resolve(READY), GSON.toJson(ready));
        state = "staged";
        at = Instant.now().toString();
        Log.info("update " + v + " downloaded and checked; the updater installs it now (journalctl -u " + Paths.SERVICE + "-update)");
    }

    private void fail(String v, String why) {
        target = v;
        state = "failed";
        error = why;
        at = Instant.now().toString();
        Log.warn("update " + v + ": " + why);
    }

    // ================================================================== root: apply-update

    /** bimba-site-agent apply-update (root, started by bimba-site-agent-update.path) */
    static int apply() {
        if (!AgentMain.isRoot()) {
            System.err.println("error: run this as root (the updater service does).");
            return 5;
        }
        Path ready = Paths.updateDir().resolve(READY);
        if (!Files.exists(ready, LinkOption.NOFOLLOW_LINKS)) {
            System.out.println("no update waiting");
            return 0;
        }
        JsonObject r;
        try {
            if (!Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS) || Files.size(ready) > 4096) {
                throw new IOException(ready + " is not a small regular file");
            }
            r = JsonParser.parseString(new String(readNoFollow(ready, 4096), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            deleteQuietly(ready);
            return result(null, "failed", "the update request is unreadable: " + e.getMessage());
        }
        deleteQuietly(ready);                    // read once: the path unit must not start this again for it
        String version = Json.str(r, "version");
        String kind = Json.str(r, "kind");
        String sha = Json.str(r, "sha256");
        if (version == null || kind == null || sha == null || !Set.of("deb", "tar").contains(kind)) {
            return result(version, "failed", "the update request is incomplete");
        }
        Path staged = Paths.updateDir().resolve(packageName(kind));
        Path work = Paths.updaterDir().resolve("work");
        try {
            JsonObject settings = settings();
            if (settings == null) {
                throw new IOException("the updater has no Setu address (" + Paths.updaterDir().resolve(SETTINGS)
                        + "): run sudo bimba-site-agent updates on");
            }
            if (settings.has("allowUpdates") && !settings.get("allowUpdates").getAsBoolean()) {
                throw new IOException("updates are switched off on this computer (sudo bimba-site-agent updates on)");
            }
            if (!kind.equals(kind())) {
                throw new IOException("this computer was not installed from the " + kind + " package");
            }
            if (compare(version, AgentMain.version()) <= 0) {
                throw new IOException("version " + version + " is not newer than the installed " + AgentMain.version());
            }
            result(version, "installing", null);
            // the root-only copy is what gets checked and installed (the service's folder could change meanwhile)
            Files2.deleteTree(work);
            Files.createDirectories(work, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path pkg = work.resolve(packageName(kind));
            String hash = copyNoFollow(staged, pkg);
            if (!hash.equalsIgnoreCase(sha)) {
                throw new IOException("the package changed after the agent checked it");
            }
            JsonObject published = Json.obj(new SetuClient(Json.str(settings, "server"), Json.str(settings, "pinnedCertSha256"), null)
                    .release(), "files");
            JsonObject mine = Json.obj(published, kind);
            if (mine == null || !hash.equalsIgnoreCase(Json.str(mine, "sha256"))) {
                throw new IOException("Setu does not publish this " + kind + " package (SHA-256 " + hash + ")");
            }
            if (!version.equals(Json.str(mine, "version"))) {
                throw new IOException("Setu publishes version " + Json.str(mine, "version") + ", not " + version);
            }
            System.out.println("installing BIMBA Site Agent " + version + " over " + AgentMain.version() + " (" + kind + ", SHA-256 " + hash + ")");
            if ("deb".equals(kind)) {
                dpkg(pkg);
            } else {
                Path x = work.resolve("x");
                Files.createDirectories(x);
                run(List.of("tar", "-xzf", pkg.toString(), "-C", x.toString()), 300);
                run(List.of("sh", x.resolve("bimba-site-agent/install.sh").toString()), 600);
            }
            deleteQuietly(staged);
            Files2.deleteTree(work);
            return result(version, "done", null);
        } catch (Exception e) {
            deleteQuietly(staged);
            try {
                Files2.deleteTree(work);
            } catch (IOException ignored) {
                // left for the next update
            }
            return result(version, "failed", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** dpkg -i, waiting while another package tool holds the lock (unattended upgrades) */
    private static void dpkg(Path pkg) throws Exception {
        List<String> cmd = List.of("dpkg", "--force-confold", "--force-confdef", "-i", pkg.toString());
        for (int i = 0; ; i++) {
            try {
                run(cmd, 900);
                return;
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
                if (i >= 20 || !(m.contains("lock") || m.contains("another process"))) {
                    throw e;
                }
                System.out.println("dpkg is busy (another package installation); trying again in 30 s");
                TimeUnit.SECONDS.sleep(30);
            }
        }
    }

    private static void run(List<String> cmd, int timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
        Process p = pb.start();
        List<String> tail = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    System.out.println("  " + line);
                    synchronized (tail) {
                        tail.add(line);
                        if (tail.size() > 5) {
                            tail.remove(0);
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        });
        reader.start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(cmd.get(0) + " did not finish within " + timeoutSeconds + " s");
        }
        reader.join(5000);
        if (p.exitValue() != 0) {
            synchronized (tail) {
                throw new IOException(cmd.get(0) + " failed (exit " + p.exitValue() + "): " + String.join(" / ", tail));
            }
        }
    }

    /** last.json (0644: the service reports it) */
    private static int result(String version, String state, String error) {
        JsonObject j = new JsonObject();
        j.addProperty("version", version);
        j.addProperty("state", state);
        j.addProperty("error", error);
        j.addProperty("from", AgentMain.version());
        j.addProperty("at", Instant.now().toString());
        try {
            writePublic(Paths.updaterDir().resolve(LAST), GSON.toJson(j));
        } catch (IOException e) {
            System.err.println("could not write " + LAST + ": " + e.getMessage());
        }
        if ("failed".equals(state)) {
            System.err.println("update " + version + " failed: " + error);
            return 1;
        }
        System.out.println("update " + version + ": " + state);
        return 0;
    }

    // ================================================================== root: settings

    /**
     * The updater's own settings (root only, readable by the service): Setu address and certificate pin copied from the
     * registration when an administrator installs, plus the computer's on / off switch. overwrite = a new registration.
     */
    static void setup(boolean overwrite) throws IOException {
        if (!AgentMain.isRoot()) {
            return;
        }
        AgentConfig cfg = AgentConfig.tryLoad();
        JsonObject s = settings();
        if (cfg == null || (s != null && !overwrite && Json.str(s, "server") != null)) {
            return;
        }
        JsonObject out = s == null ? new JsonObject() : s;
        out.addProperty("server", cfg.server);
        out.addProperty("pinnedCertSha256", cfg.pinnedCertSha256);
        if (!out.has("allowUpdates")) {
            out.addProperty("allowUpdates", true);
        }
        out.addProperty("setAt", Instant.now().toString());
        save(out);
    }

    /** bimba-site-agent updates [on | off] */
    static int updates(String to) throws Exception {
        if (to != null && !AgentMain.isRoot()) {
            System.err.println("error: run this as root (sudo bimba-site-agent updates " + to + ").");
            return 5;
        }
        JsonObject s = settings();
        if (to == null) {
            boolean on = s == null || !s.has("allowUpdates") || s.get("allowUpdates").getAsBoolean();
            String why = whyNot(kind(), s);
            System.out.println((on ? "on" : "off") + ": automatic updates from Setu " + (on ? "are installed" : "are refused") + " on this computer"
                    + (why != null ? " (but this computer cannot update itself: " + why + ")" : ""));
            JsonObject last = read(Paths.updaterDir().resolve(LAST));
            if (last != null) {
                System.out.println("last update: " + Json.str(last, "version") + " " + Json.str(last, "state") + " at " + Json.str(last, "at")
                        + (Json.str(last, "error") != null ? ": " + Json.str(last, "error") : ""));
            }
            return 0;
        }
        if (!"on".equals(to) && !"off".equals(to)) {
            throw new IllegalArgumentException("updates on | off");
        }
        setup(false);
        s = settings();
        if (s == null) {
            s = new JsonObject();
        }
        s.addProperty("allowUpdates", "on".equals(to));
        save(s);
        System.out.println("Automatic updates " + ("on".equals(to) ? "allowed" : "refused") + " on this computer.");
        return 0;
    }

    private static void save(JsonObject s) throws IOException {
        Files.createDirectories(Paths.updaterDir());
        Files.setPosixFilePermissions(Paths.updaterDir(), PosixFilePermissions.fromString("rwxr-xr-x"));
        writePublic(Paths.updaterDir().resolve(SETTINGS), GSON.toJson(s));
    }

    // ================================================================== shared

    /** deb (dpkg knows the package), tar (install.sh left uninstall.sh), or null (container, copied by hand) */
    static String kind() {
        if (Files.isRegularFile(Path.of("/var/lib/dpkg/info/" + Paths.SERVICE + ".list"))) {
            return "deb";
        }
        if (Files.isRegularFile(Paths.home().resolve("uninstall.sh"))) {
            return "tar";
        }
        return null;
    }

    /** why this computer cannot update itself, or null */
    static String whyNot(String kind, JsonObject settings) {
        if (kind == null) {
            return "the agent was not installed from the .deb or .tar.gz package (container or copied by hand): update it the way it was installed";
        }
        if (!Platform.systemd()) {
            return "no systemd on this computer: run the newer installer by hand";
        }
        boolean unit = false;
        for (String d : UNIT_DIRS) {
            unit |= Files.isRegularFile(Path.of(d, PATH_UNIT));
        }
        if (!unit) {
            return "the updater (" + PATH_UNIT + ") is not installed: run the 1.10 installer by hand once";
        }
        if (settings == null || Json.str(settings, "server") == null) {
            return "the updater has no Setu address yet: run sudo bimba-site-agent updates on";
        }
        return null;
    }

    static JsonObject settings() {
        return read(Paths.updaterDir().resolve(SETTINGS));
    }

    static String packageName(String kind) {
        return "tar".equals(kind) ? "bimba-site-agent-linux.tar.gz" : "bimba-site-agent_all.deb";
    }

    private static JsonObject read(Path f) {
        try {
            return Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && Files.size(f) < 65_536
                    ? JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** written by root for everyone to read (never a secret): temporary file, then moved into place */
    private static void writePublic(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")));
        Files.writeString(tmp, content + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] readNoFollow(Path f, int max) throws IOException {
        try (var ch = Files.newByteChannel(f, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             InputStream in = Channels.newInputStream(ch)) {
            return in.readNBytes(max);
        }
    }

    /** copies a file the service wrote without following links; returns its SHA-256 */
    private static String copyNoFollow(Path from, Path to) throws Exception {
        if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("the downloaded package is missing");
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (var ch = Files.newByteChannel(from, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             InputStream in = Channels.newInputStream(ch);
             OutputStream out = Files.newOutputStream(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[65_536];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_PACKAGE) {
                    throw new IOException("the package is larger than 1 GB");
                }
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[65_536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static void deleteQuietly(Path f) {
        try {
            Files.deleteIfExists(f);
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /** "1.10.0" &gt; "1.9.2"; a pre-release ("1.10.0~rc1", "1.10.0-rc1") is older than its release (as Setu compares) */
    static int compare(String a, String b) {
        String[] x = parts(a);
        String[] y = parts(b);
        String[] nx = x[0].isEmpty() ? new String[0] : x[0].split("\\.");
        String[] ny = y[0].isEmpty() ? new String[0] : y[0].split("\\.");
        for (int i = 0; i < Math.max(nx.length, ny.length); i++) {
            long p = i < nx.length ? Long.parseLong(nx[i]) : 0;
            long q = i < ny.length ? Long.parseLong(ny[i]) : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        if (x[1].isEmpty() != y[1].isEmpty()) {
            return x[1].isEmpty() ? 1 : -1;
        }
        return Integer.signum(x[1].compareTo(y[1]));
    }

    private static String[] parts(String v) {
        Matcher m = Pattern.compile("^\\s*v?(\\d{1,9}(?:\\.\\d{1,9})*)(?:[~+-]?(.*))?$").matcher(v == null ? "" : v);
        return m.matches() ? new String[]{m.group(1), m.group(2) == null ? "" : m.group(2).trim()} : new String[]{"", ""};
    }
}
