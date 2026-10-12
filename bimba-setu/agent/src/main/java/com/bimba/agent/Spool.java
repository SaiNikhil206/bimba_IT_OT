package com.bimba.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Disk buffer for readings: one JSON message per line, files of up to 1000 messages or {@link #MAX_FILE_CHARS}
 * (written at least every second), oldest uploaded first. A file is one upload, so it must stay under Setu's request
 * limit (256 KB); a file that is still too big (older agents wrote up to 1000 messages) is split, not discarded.
 * Capped (default 1 GB, agent.json spoolMaxMb): beyond that the oldest files are dropped (logged).
 * Same layout as the Windows agent's spool.
 */
final class Spool {
    private static final int MAX_PER_FILE = 1000;
    /** well under Setu's 256 KB per request, leaving room for the {"messages":[…]} wrapper */
    static final int MAX_FILE_CHARS = 200 * 1024;
    /** refused files kept for inspection in rejected/ (oldest removed beyond this) */
    private static final int MAX_REJECTED_FILES = 200;
    private final Path dir;
    private final long maxBytes;
    private StringBuilder current = new StringBuilder();
    private int count;
    private long firstAt;
    private long seq;
    private long dropped;

    Spool(Path dir, long maxBytes) throws IOException {
        this.dir = dir;
        this.maxBytes = maxBytes;
        Files2.privateDir(dir);
        // a crash may leave a temporary file: it is complete up to its last line, so keep it
        for (Path tmp : list("*.tmp")) {
            try {
                Files.move(tmp, tmp.resolveSibling(tmp.getFileName().toString().replaceFirst("\\.tmp$", ".jsonl")));
            } catch (IOException ignored) {
                // leave it
            }
        }
    }

    synchronized void add(String message) {
        if (count > 0 && current.length() + message.length() + 1 > MAX_FILE_CHARS) {
            flushLocked();
        }
        if (count == 0) {
            firstAt = System.currentTimeMillis();
        }
        current.append(message).append('\n');
        if (++count >= MAX_PER_FILE || current.length() >= MAX_FILE_CHARS) {
            flushLocked();
        }
    }

    synchronized void flushIfDue() {
        if (count > 0 && System.currentTimeMillis() - firstAt >= 1000) {
            flushLocked();
        }
    }

    synchronized void flush() {
        if (count > 0) {
            flushLocked();
        }
    }

    private void flushLocked() {
        String name = String.format("%019d-%06d", System.currentTimeMillis() * 10_000L, ++seq);
        Path tmp = dir.resolve(name + ".tmp");
        try {
            Files.writeString(tmp, current, StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(name + ".jsonl"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.error("disk buffer: cannot write " + tmp + ": " + e.getMessage() + " (" + count + " readings lost)");
        }
        current = new StringBuilder();
        count = 0;
        trim();
    }

    private void trim() {
        try {
            List<Path> files = list("*.jsonl");
            long total = 0;
            for (Path f : files) {
                total += Files.size(f);
            }
            int i = 0;
            while (total > maxBytes && i < files.size() - 1) {
                total -= Files.size(files.get(i));
                Files.deleteIfExists(files.get(i));
                dropped++;
                i++;
            }
            if (i > 0) {
                Log.warn("disk buffer over " + (maxBytes >> 20) + " MB: dropped the " + i + " oldest file(s) of readings");
            }
        } catch (IOException e) {
            Log.warn("disk buffer: " + e.getMessage());
        }
    }

    /** oldest buffered file, or null */
    Path oldest() {
        List<Path> files = list("*.jsonl");
        return files.isEmpty() ? null : files.get(0);
    }

    void done(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Log.warn("disk buffer: " + e.getMessage());
        }
    }

    /**
     * Replaces {@code file} by two files with its first and second half, sorted at its place (name + "a" / "b"), so
     * they are uploaded next and in order. False when it has a single message (it cannot get smaller).
     */
    boolean split(Path file, List<String> lines) {
        if (lines.size() < 2) {
            return false;
        }
        String base = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
        int half = lines.size() / 2;
        try {
            writeAtomic(dir.resolve(base + "a"), lines.subList(0, half));
            writeAtomic(dir.resolve(base + "b"), lines.subList(half, lines.size()));
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            Log.warn("disk buffer: cannot split " + file.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    private static void writeAtomic(Path base, List<String> lines) throws IOException {
        Path tmp = base.resolveSibling(base.getFileName() + ".tmp");
        Files.writeString(tmp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, base.resolveSibling(base.getFileName() + ".jsonl"), StandardCopyOption.ATOMIC_MOVE);
    }

    void reject(Path file) {
        try {
            Path rejected = dir.resolve("rejected");
            Files.createDirectories(rejected);
            Files.move(file, rejected.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            try (Stream<Path> s = Files.list(rejected)) {
                List<Path> kept = s.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
                for (int i = 0; i < kept.size() - MAX_REJECTED_FILES; i++) {
                    Files.deleteIfExists(kept.get(i));
                }
            }
        } catch (IOException e) {
            Log.warn("disk buffer: " + e.getMessage());
            done(file);
        }
    }

    Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            List<Path> files = list("*.jsonl");
            long bytes = 0;
            for (Path f : files) {
                bytes += Files.size(f);
            }
            int inMemory;
            synchronized (this) {
                inMemory = count;
            }
            m.put("files", files.size());
            m.put("bytes", bytes);
            m.put("inMemory", inMemory);
            m.put("droppedFiles", dropped);
        } catch (IOException e) {
            // empty status
        }
        return m;
    }

    private List<Path> list(String glob) {
        try (Stream<Path> s = Files.list(dir)) {
            String suffix = glob.substring(1);
            return s.filter(p -> p.getFileName().toString().endsWith(suffix) && Files.isRegularFile(p))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
