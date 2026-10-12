package com.bimba.setu;

import com.bimba.setu.collect.Canonical;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Gap markers for the time Setu itself was not running (FR-RES-03): its own collector reads nothing then, and
 * no device-level marker can say so. Every 10 s Setu notes that it is alive (data/gateway-alive.json), and
 * when it stops it notes when. At the next start, a gap longer than {@link #MIN_GAP_SECONDS} gives every machine of
 * its tag tree a "lost" marker (timestamped when it stopped, or last noted alive after a crash) and a "restored" one.
 * Site agents keep their own readings while Setu is away, so their machines are not marked.
 */
public final class Downtime {
    private static final Logger log = LoggerFactory.getLogger("BIMBA");
    static final long MIN_GAP_SECONDS = 15;

    /** when Setu last ran, and whether it stopped normally then (else: crashed, at most 10 s later) */
    public record Last(Instant at, boolean stopped) {
    }

    private final File file;

    public Downtime(File dataDir) {
        this.file = new File(dataDir, "gateway-alive.json");
    }

    public Last read() {
        try {
            if (!file.isFile()) {
                return null;
            }
            JsonObject o = Json.GSON.fromJson(Files.readString(file.toPath()), JsonObject.class);
            return o == null || !o.has("at") ? null : new Last(Instant.parse(o.get("at").getAsString()), o.has("stopped") && o.get("stopped").getAsBoolean());
        } catch (Exception e) {
            log.warn("could not read {}: {}", file, e.getMessage());
            return null;
        }
    }

    /** notes "alive now" (stopped = Setu is shutting down) */
    public void beat(boolean stopped) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", Instant.now().toString());
            o.addProperty("stopped", stopped);
            Files.createDirectories(file.toPath().getParent());
            java.nio.file.Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
            Files.writeString(tmp, Json.GSON.toJson(o));
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("could not write {}: {}", file, e.getMessage());
        }
    }

    /** the lost + restored markers of each machine for the time since last; none for a short restart */
    public static List<JsonObject> markers(String plantId, String edgeId, Collection<String> machines, Last last, Instant now) {
        List<JsonObject> out = new ArrayList<>();
        if (last == null || now.getEpochSecond() - last.at().getEpochSecond() <= MIN_GAP_SECONDS) {
            return out;
        }
        String reason = last.stopped() ? "Setu was stopped" : "Setu stopped unexpectedly (last known alive)";
        for (String machine : machines) {
            JsonObject src = new JsonObject();
            src.addProperty("protocol", "gateway");
            src.addProperty("system", edgeId);
            src.addProperty("address", edgeId);
            out.add(Canonical.gapMarker(plantId, machine, "start", last.at(), last.at(), null, "gateway " + edgeId, reason, src));
            out.add(Canonical.gapMarker(plantId, machine, "end", now, last.at(), now, "gateway " + edgeId, null, src.deepCopy()));
        }
        return out;
    }
}
