package com.bimba.gateway.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Store-and-forward buffer: one file per undelivered batch, replayed oldest first.
 * Survives gateway restarts. Oldest files are discarded when maxBytes is exceeded.
 * Batches an end system refused as invalid go to rejected/ with a .meta file (when, by whom, why); the console lists
 * them and can send them again (back into the queue) or discard them.
 */
public class DiskBuffer {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Buffer");

    private final Path dir;
    private final Path rejectedDir;
    private final long maxBytes;
    private final AtomicLong counter = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public DiskBuffer(File dir, int maxMegabytes) throws IOException {
        this.dir = dir.toPath();
        this.rejectedDir = this.dir.resolve("rejected");
        this.maxBytes = maxMegabytes * 1024L * 1024L;
        Files.createDirectories(this.rejectedDir);
    }

    public synchronized void write(byte[] batch) {
        try {
            String name = String.format("%016d-%06d.json", System.currentTimeMillis(), counter.incrementAndGet() % 1_000_000);
            Path tmp = dir.resolve(name + ".tmp");
            Files.write(tmp, batch);
            try {
                Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            enforceLimit();
        } catch (IOException e) {
            log.error("could not buffer batch to disk, {} bytes lost", batch.length, e);
            dropped.incrementAndGet();
        }
    }

    public synchronized Optional<File> oldest() {
        File[] files = files();
        return files.length == 0 ? Optional.empty() : Optional.of(files[0]);
    }

    public void delete(File f) {
        if (!f.delete()) {
            log.warn("could not delete delivered buffer file {}", f);
        }
    }

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("\\d{16}-\\d{6}\\.json");

    /** Moves a batch the end system rejected as invalid (HTTP 400/413/422) out of the replay queue. */
    public void reject(File f, String destination, String reason, int count) {
        try {
            Files.move(f.toPath(), rejectedDir.resolve(f.getName()), StandardCopyOption.REPLACE_EXISTING);
            meta(f.getName(), destination, reason, count);
        } catch (IOException e) {
            log.warn("could not move rejected batch {}", f, e);
        }
    }

    /** Keeps a batch refused on first delivery (it was never on disk). */
    public synchronized void reject(byte[] batch, String destination, String reason, int count) {
        String name = String.format("%016d-%06d.json", System.currentTimeMillis(), counter.incrementAndGet() % 1_000_000);
        try {
            Files.write(rejectedDir.resolve(name), batch);
            meta(name, destination, reason, count);
        } catch (IOException e) {
            log.error("could not keep a rejected batch, {} bytes lost", batch.length, e);
            dropped.incrementAndGet();
        }
    }

    private void meta(String name, String destination, String reason, int count) throws IOException {
        com.google.gson.JsonObject m = new com.google.gson.JsonObject();
        m.addProperty("at", java.time.Instant.now().toString());
        m.addProperty("destination", destination);
        m.addProperty("reason", reason == null ? "" : reason.substring(0, Math.min(1000, reason.length())));
        m.addProperty("count", count);
        Files.writeString(rejectedDir.resolve(name + ".meta"), com.bimba.gateway.Json.GSON.toJson(m));
    }

    /** rejected batches, newest first: file, at, bytes, destination, reason, count */
    public java.util.List<java.util.Map<String, Object>> rejected() {
        File[] files = rejectedDir.toFile().listFiles((d, n) -> NAME.matcher(n).matches());
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (files == null) {
            return out;
        }
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        for (File f : files) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("file", f.getName());
            m.put("bytes", f.length());
            m.put("at", java.time.Instant.ofEpochMilli(f.lastModified()).toString());
            File meta = rejectedDir.resolve(f.getName() + ".meta").toFile();
            if (meta.isFile()) {
                try {
                    com.google.gson.JsonObject j = com.bimba.gateway.Json.GSON.fromJson(Files.readString(meta.toPath()), com.google.gson.JsonObject.class);
                    j.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()
                            ? e.getValue().getAsLong() : e.getValue().getAsString()));
                } catch (IOException | RuntimeException ignored) {
                    // a batch without its note is still listed
                }
            }
            out.add(m);
        }
        return out;
    }

    public int rejectedCount() {
        String[] n = rejectedDir.toFile().list((d, x) -> NAME.matcher(x).matches());
        return n == null ? 0 : n.length;
    }

    /** the text of a rejected batch (at most maxBytes) */
    public String rejectedText(String name, int maxBytes) throws IOException {
        byte[] b = Files.readAllBytes(rejectedFile(name));
        return new String(b, 0, Math.min(b.length, maxBytes), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** back into the replay queue: it is sent again with the next replay */
    public synchronized void resend(String name) throws IOException {
        Path f = rejectedFile(name);
        Files.move(f, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(rejectedDir.resolve(name + ".meta"));
    }

    public synchronized void discard(String name) throws IOException {
        Files.delete(rejectedFile(name));
        Files.deleteIfExists(rejectedDir.resolve(name + ".meta"));
    }

    private Path rejectedFile(String name) throws IOException {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IOException("not a rejected batch: " + name);       // also keeps paths inside the folder
        }
        Path f = rejectedDir.resolve(name);
        if (!Files.isRegularFile(f)) {
            throw new java.io.FileNotFoundException("no rejected batch " + name);
        }
        return f;
    }

    public int count() {
        return files().length;
    }

    public long bytes() {
        return Arrays.stream(files()).mapToLong(File::length).sum();
    }

    public long dropped() {
        return dropped.get();
    }

    private File[] files() {
        File[] files = dir.toFile().listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private void enforceLimit() {
        File[] files = files();
        long total = Arrays.stream(files).mapToLong(File::length).sum();
        int i = 0;
        while (total > maxBytes && i < files.length) {
            total -= files[i].length();
            if (files[i].delete()) {
                dropped.incrementAndGet();
                log.warn("buffer full ({} MB): discarded oldest batch {}", maxBytes / 1024 / 1024, files[i].getName());
            }
            i++;
        }
    }
}
