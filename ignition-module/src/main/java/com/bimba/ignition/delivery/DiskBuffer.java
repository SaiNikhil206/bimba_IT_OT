package com.bimba.ignition.delivery;

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

    /** Moves a batch the end system rejected as invalid (HTTP 400/413/422) out of the replay queue. */
    public void reject(File f) {
        try {
            Files.move(f.toPath(), rejectedDir.resolve(f.getName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("could not move rejected batch {}", f, e);
        }
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
