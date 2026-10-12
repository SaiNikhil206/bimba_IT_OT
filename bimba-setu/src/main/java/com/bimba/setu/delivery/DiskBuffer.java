package com.bimba.setu.delivery;

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
 * Survives Setu restarts. Oldest files are discarded when maxBytes is exceeded.
 * Batches an end system refused as invalid go to rejected/ with a .meta file (when, by whom, why); the console lists
 * them and can send them again (back into the queue) or discard them.
 * <p>
 * FR-RES-05: size and count are kept in memory (no folder listing per batch); when the buffer reaches
 * {@link #ALERT_PERCENT} of its size the usage listener is told once, and again when it is back below 60 %.
 */
public class DiskBuffer {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Buffer");

    private final Path dir;
    private final Path rejectedDir;
    private final long maxBytes;
    private final AtomicLong counter = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    /** what is waiting in the folder (not the rejected ones) */
    private final AtomicLong bytes = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger files = new java.util.concurrent.atomic.AtomicInteger();
    public static final int ALERT_PERCENT = 70;
    private static final int CLEAR_PERCENT = 60;
    private volatile boolean high;
    /** (high, percent): told when the usage reaches ALERT_PERCENT and when it is below CLEAR_PERCENT again */
    private volatile java.util.function.BiConsumer<Boolean, Integer> usage;

    public DiskBuffer(File dir, int maxMegabytes) throws IOException {
        this.dir = dir.toPath();
        this.rejectedDir = this.dir.resolve("rejected");
        this.maxBytes = maxMegabytes * 1024L * 1024L;
        Files.createDirectories(this.rejectedDir);
        File[] all = files();
        files.set(all.length);
        bytes.set(Arrays.stream(all).mapToLong(File::length).sum());
    }

    /** the usage listener; told at once when the buffer is already high */
    public void onUsage(java.util.function.BiConsumer<Boolean, Integer> listener) {
        this.usage = listener;
        high = false;
        checkUsage();
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** how full the buffer is, 0-100 */
    public int usedPercent() {
        return maxBytes <= 0 ? 0 : (int) Math.min(100, bytes.get() * 100 / maxBytes);
    }

    public boolean high() {
        return high;
    }

    private void checkUsage() {
        int p = usedPercent();
        java.util.function.BiConsumer<Boolean, Integer> l = usage;
        if (!high && p >= ALERT_PERCENT) {
            high = true;
            if (l != null) {
                l.accept(true, p);
            }
        } else if (high && p < CLEAR_PERCENT) {
            high = false;
            if (l != null) {
                l.accept(false, p);
            }
        }
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
            bytes.addAndGet(batch.length);
            files.incrementAndGet();
            enforceLimit();
            checkUsage();
        } catch (IOException e) {
            log.error("could not buffer batch to disk, {} bytes lost", batch.length, e);
            dropped.incrementAndGet();
        }
    }

    /** the n oldest waiting batches, oldest first */
    public synchronized java.util.List<File> oldest(int n) {
        File[] all = files();
        return java.util.List.of(all).subList(0, Math.min(Math.max(0, n), all.length));
    }

    public synchronized Optional<File> oldest() {
        File[] files = files();
        return files.length == 0 ? Optional.empty() : Optional.of(files[0]);
    }

    public synchronized void delete(File f) {
        long size = f.length();
        if (!f.delete()) {
            log.warn("could not delete delivered buffer file {}", f);
            return;
        }
        gone(size);
    }

    private void gone(long size) {
        bytes.addAndGet(-size);
        files.decrementAndGet();
        checkUsage();
    }

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("\\d{16}-\\d{6}\\.json");

    /** Moves a batch the end system rejected as invalid (HTTP 400/413/422) out of the replay queue. */
    public synchronized void reject(File f, String destination, String reason, int count) {
        try {
            long size = f.length();
            Files.move(f.toPath(), rejectedDir.resolve(f.getName()), StandardCopyOption.REPLACE_EXISTING);
            gone(size);
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
        Files.writeString(rejectedDir.resolve(name + ".meta"), com.bimba.setu.Json.GSON.toJson(m));
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
                    com.google.gson.JsonObject j = com.bimba.setu.Json.GSON.fromJson(Files.readString(meta.toPath()), com.google.gson.JsonObject.class);
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
        long size = Files.size(f);
        Files.move(f, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(rejectedDir.resolve(name + ".meta"));
        bytes.addAndGet(size);
        files.incrementAndGet();
        checkUsage();
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
        return Math.max(0, files.get());
    }

    public long bytes() {
        return Math.max(0, bytes.get());
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
        if (bytes.get() <= maxBytes) {
            return;
        }
        File[] all = files();
        long total = Arrays.stream(all).mapToLong(File::length).sum();
        int i = 0;
        while (total > maxBytes && i < all.length) {
            long size = all[i].length();
            total -= size;
            if (all[i].delete()) {
                dropped.incrementAndGet();
                log.warn("buffer full ({} MB): discarded oldest batch {}", maxBytes / 1024 / 1024, all[i].getName());
            }
            i++;
        }
        bytes.set(Math.max(0, total));
        files.set(files().length);
    }
}
