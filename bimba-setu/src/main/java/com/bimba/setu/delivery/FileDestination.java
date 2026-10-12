package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Files on an SFTP or FTP / FTPS server (FR-END-02): CSV, JSON, NDJSON, XML or HL7, named by a pattern, written under
 * a temporary name and renamed when complete (a reader never sees half a file), optionally archived locally.
 * <ul>
 *   <li>windowSeconds 0: every batch is one file, sent at once;</li>
 *   <li>windowSeconds N: the readings of each N-second window (aligned to the clock: every 15 minutes = :00, :15, ...)
 *       are collected on disk and sent as one file when the window ends. A batch counts as delivered once it is on disk;
 *       a window that cannot be sent marks the destination down (the route then keeps the next batches in its buffer or
 *       uses its next destination) and is tried again until it is sent; windows survive restarts.</li>
 * </ul>
 * File names are the same when a file is sent again (they use the batch or window, not the clock of the attempt),
 * so a retry after a lost answer replaces the file instead of adding a second one.
 */
public class FileDestination extends Destination {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Delivery");
    private static final Pattern NAME_PART = Pattern.compile("\\{([a-zA-Z]+)(?::([^}]*))?\\}");
    static final List<String> NAME_FIELDS = List.of("plantId", "edgeId", "batchId", "count", "ext", "time");

    private final String plantId;
    private final Remote remote;
    private final File windows;
    private final File archive;
    private final long windowMs;
    private Thread timer;
    private volatile boolean running;
    private long lastCleanup;

    /** the server side: SFTP or FTP / FTPS */
    interface Remote extends AutoCloseable {
        /** writes the file under a temporary name in the folder, then renames it (replacing a file of that name) */
        void put(String name, byte[] content) throws Exception;

        String test() throws Exception;

        @Override
        void close();
    }

    public FileDestination(BimbaConfig.Destination cfg, String plantId, File workDir) {
        super(cfg);
        this.plantId = plantId;
        this.remote = "sftp".equalsIgnoreCase(cfg.transport) ? new SftpRemote(cfg) : new FtpRemote(cfg);
        boolean windowed = workDir != null && cfg.windowSeconds > 0;
        this.windowMs = windowed ? cfg.windowSeconds * 1000L : 0;
        this.windows = workDir == null ? null : new File(workDir, "window");
        this.archive = workDir == null || cfg.archiveDays <= 0 ? null : new File(workDir, "archive");
        if (windowed || archive != null) {
            running = true;
            timer = new Thread(this::tick, "BIMBA-Files-" + cfg.name);
            timer.setDaemon(true);
            timer.start();
        }
    }

    // ------------------------------------------------------------------ sending

    @Override
    public synchronized void send(byte[] batch) throws Exception {
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        if (windowMs <= 0) {
            upload(env, Json.str(env, "createdAt"));
            return;
        }
        // an ended window that could not be sent yet goes first: if it still cannot, this batch stays with the route
        sendDue(System.currentTimeMillis());
        stage(env, System.currentTimeMillis());
    }

    /** appends the batch's readings to the file of the window "now" is in */
    void stage(JsonObject env, long now) throws IOException {
        long start = now - Math.floorMod(now, windowMs);
        Path f = windows.toPath().resolve("window-" + start + ".json");
        Files.createDirectories(windows.toPath());
        StringBuilder sb = new StringBuilder();
        if (!Files.exists(f)) {
            JsonObject head = env.deepCopy();
            head.remove("messages");
            head.remove("count");
            head.remove("part");
            sb.append(Json.GSON.toJson(head)).append('\n');          // first line: the batch fields
        }
        for (JsonElement m : env.getAsJsonArray("messages")) {
            sb.append(Json.GSON.toJson(m)).append('\n');
        }
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(f, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)));
            ch.force(true);                                           // delivered = on disk
        }
    }

    /** sends every window that ended by now, oldest first; throws (and stops) at the first that cannot be sent */
    synchronized void sendDue(long now) throws Exception {
        File[] files = windows == null ? null : windows.listFiles((d, n) -> n.matches("window-\\d+\\.json"));
        if (files == null || files.length == 0) {
            return;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        for (File f : files) {
            long start = Long.parseLong(f.getName().replaceAll("\\D", ""));
            if (start + windowMs > now) {
                continue;                                             // still open
            }
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            if (lines.size() > 1) {
                JsonObject env = Json.GSON.fromJson(lines.get(0), JsonObject.class);
                JsonArray all = new JsonArray();
                for (int i = 1; i < lines.size(); i++) {
                    if (!lines.get(i).isBlank()) {
                        all.add(Json.GSON.fromJson(lines.get(i), JsonObject.class));
                    }
                }
                String createdAt = Instant.ofEpochMilli(start).toString();
                env.addProperty("batchId", UUID.nameUUIDFromBytes((cfg.name + "|" + f.getName()).getBytes(StandardCharsets.UTF_8)).toString());
                env.addProperty("createdAt", createdAt);
                env.addProperty("count", all.size());
                env.add("messages", all);
                upload(env, createdAt);
            }
            Files.delete(f.toPath());
        }
    }

    /** the file of a batch (or window): rendered, named, sent, archived */
    private void upload(JsonObject env, String createdAt) throws Exception {
        byte[] content = render(env);
        String name = fileName(cfg.fileName, env, createdAt, plantId, extension());
        remote.put(name, content);
        if (archive != null) {
            try {
                Path day = archive.toPath().resolve(createdAt.length() >= 10 ? createdAt.substring(0, 10) : "undated");
                Files.createDirectories(day);
                Files.write(day.resolve(name), content);
            } catch (IOException e) {
                log.warn("destination {}: could not archive {}: {}", cfg.name, name, e.getMessage());
            }
        }
    }

    /** the file's content in the destination's format */
    byte[] render(JsonObject env) {
        String f = format();
        byte[] batch = Json.GSON.toJson(env).getBytes(StandardCharsets.UTF_8);
        return switch (f) {
            case "json" -> mappedEnvelope(batch);
            case "hl7" -> {
                StringBuilder sb = new StringBuilder();
                Hl7.compile(cfg.hl7Template).render(env, cfg.hl7Mode).forEach(m -> sb.append(m.text()).append('\n'));
                yield sb.toString().getBytes(StandardCharsets.UTF_8);
            }
            case "ndjson", "csv", "xml" -> Formats.render(f, batch, null, this::layout, Formats.Options.of(cfg, major == 2, template != null)).bytes();
            default -> throw new IllegalStateException("unknown file format " + f);
        };
    }

    private String format() {
        String f = cfg.format == null ? "" : cfg.format.toLowerCase(Locale.ROOT);
        return List.of("json", "ndjson", "csv", "xml", "hl7").contains(f) ? f : "json";
    }

    private String extension() {
        return format();
    }

    /**
     * The file name from the pattern: {plantId}, {edgeId}, {batchId}, {count}, {ext} and {time:pattern} (the batch's or
     * window's time, UTC, java.time pattern; {time} alone = yyyyMMdd'T'HHmmss'Z'). Only letters, digits and . _ - stay.
     */
    static String fileName(String pattern, JsonObject env, String createdAt, String plantId, String ext) {
        String p = pattern == null || pattern.isBlank() ? new BimbaConfig.Destination().fileName : pattern;
        Matcher m = NAME_PART.matcher(p);
        StringBuilder sb = new StringBuilder();
        Instant at;
        try {
            at = Instant.parse(createdAt);
        } catch (RuntimeException e) {
            at = Instant.now();
        }
        while (m.find()) {
            String v = switch (m.group(1)) {
                case "plantId" -> Json.str(env, "plantId").isEmpty() ? plantId : Json.str(env, "plantId");
                case "edgeId" -> Json.str(env, "edgeId");
                case "batchId" -> Json.str(env, "batchId");
                case "count" -> String.valueOf(env.has("messages") ? env.getAsJsonArray("messages").size() : 0);
                case "ext" -> ext;
                case "time" -> DateTimeFormatter.ofPattern(m.group(2) == null || m.group(2).isBlank() ? "yyyyMMdd'T'HHmmss'Z'" : m.group(2),
                        Locale.ROOT).withZone(ZoneOffset.UTC).format(at);
                default -> m.group(0);
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        String name = sb.toString().replaceAll("[^A-Za-z0-9._-]", "_");
        return name.startsWith(".") ? "_" + name : name;
    }

    /** IllegalArgumentException with a readable reason when the pattern cannot make file names */
    public static void checkFileName(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        if (pattern.contains("/") || pattern.contains("\\")) {
            throw new IllegalArgumentException("The file name cannot contain / or \\: put the folder in the address.");
        }
        Matcher m = NAME_PART.matcher(pattern);
        while (m.find()) {
            if (!NAME_FIELDS.contains(m.group(1))) {
                throw new IllegalArgumentException("Unknown field {" + m.group(1) + "} in the file name. Available: {plantId}, {edgeId},"
                        + " {batchId}, {count}, {ext}, {time:yyyyMMdd-HHmmss}.");
            }
            if ("time".equals(m.group(1)) && m.group(2) != null && !m.group(2).isBlank()) {
                try {
                    DateTimeFormatter.ofPattern(m.group(2), Locale.ROOT).withZone(ZoneOffset.UTC).format(Instant.now());
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("{time:" + m.group(2) + "} is not a date pattern: " + e.getMessage());
                }
            }
        }
        if (!pattern.contains("{batchId}") && !pattern.matches(".*\\{time:[^}]*(s|S)[^}]*\\}.*") && !pattern.contains("{time}")) {
            throw new IllegalArgumentException("The file name needs {batchId} or a {time:...} with seconds, so two files never get the same name.");
        }
    }

    // ------------------------------------------------------------------ the window timer

    /** every few seconds: the windows that ended; every hour: archive copies past archiveDays */
    private void tick() {
        while (running) {
            try {
                Thread.sleep(windowMs > 0 ? Math.min(5000, Math.max(500, windowMs / 4)) : 60_000);
                long now = System.currentTimeMillis();
                if (windowMs > 0 && isAvailable(now)) {
                    boolean wasDown = isDown();
                    try {
                        sendDue(now);
                        if (wasDown) {
                            markRecovered();
                        }
                    } catch (Exception e) {
                        String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                        if (!wasDown) {
                            log.warn("destination {}: an ended file window could not be sent ({}); trying again", cfg.name, msg);
                        }
                        markFailure(msg);
                    }
                }
                if (archive != null && now - lastCleanup > 3_600_000) {
                    lastCleanup = now;
                    cleanArchive(LocalDate.now(ZoneOffset.UTC).minusDays(cfg.archiveDays));
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                log.error("destination {}: file timer error", cfg.name, e);
            }
        }
    }

    /** removes archive days before the first day kept */
    void cleanArchive(LocalDate keepFrom) {
        File[] days = archive == null ? null : archive.listFiles(File::isDirectory);
        if (days == null) {
            return;
        }
        for (File d : days) {
            try {
                if (LocalDate.parse(d.getName()).isBefore(keepFrom)) {
                    File[] fs = d.listFiles();
                    if (fs != null) {
                        for (File f : fs) {
                            Files.deleteIfExists(f.toPath());
                        }
                    }
                    Files.deleteIfExists(d.toPath());
                }
            } catch (RuntimeException | IOException ignored) {
                // not a day folder, or in use: next hour
            }
        }
    }

    // ------------------------------------------------------------------ the rest

    /** the file name and content (HL7 segments one per line) */
    @Override
    protected String wire(byte[] batch) {
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        String name = fileName(cfg.fileName, env, Json.str(env, "createdAt"), plantId, extension());
        return "File " + name + (windowMs > 0 || cfg.windowSeconds > 0 ? " (one per " + cfg.windowSeconds + " s window)" : "") + ":\n\n"
                + new String(render(env), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Connects and signs in; reports whether the folder is there. Writes nothing. */
    @Override
    public String test() throws Exception {
        return remote.test();
    }

    @Override
    public String endpoint() {
        return cfg.url;
    }

    @Override
    public java.util.Map<String, Object> status() {
        java.util.Map<String, Object> m = super.status();
        if (windowMs > 0) {
            File[] open = windows.listFiles((d, n) -> n.matches("window-\\d+\\.json"));
            m.put("openWindows", open == null ? 0 : open.length);
        }
        return m;
    }

    @Override
    public void close() {
        running = false;
        if (timer != null) {
            timer.interrupt();
        }
        synchronized (this) {
            remote.close();
        }
    }

    /**
     * host, port (or the default) and folder of a sftp:// or ftp:// address: sftp://host/data/in = the folder /data/in,
     * sftp://host/~/in = "in" in the sign-in folder, no path = the sign-in folder itself ("")
     */
    record Address(String host, int port, String folder) {
        static Address of(String url, int defaultPort) {
            java.net.URI u = java.net.URI.create(url.trim());
            String path = (u.getPath() == null ? "" : u.getPath()).replaceAll("/+$", "");
            if (path.equals("/~") || path.startsWith("/~/")) {
                path = path.substring(Math.min(path.length(), 3));
            }
            return new Address(u.getHost(), u.getPort() > 0 ? u.getPort() : defaultPort, path);
        }

        String join(String name) {
            return folder.isEmpty() ? name : folder + "/" + name;
        }
    }

    static String partName(String name) {
        return "." + name + ".part";                                  // hidden on most servers while it is written
    }
}
