package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A pipeline's reading filters (FR-RTE-05), on top of what it selects:
 * <ul>
 *   <li>dedupe: a reading it already carried is dropped: the same messageId (a site agent sending a batch again after
 *       a lost answer), or the same machine, tag and timestamp. The last {@link #REMEMBERED} readings are remembered.</li>
 *   <li>changeOnly: a reading is sent only when its value or quality differs from the last one sent for its tag;
 *       numbers that moved by at most the deadband count as unchanged (the comparison is with the last value sent, so
 *       a slow drift is still sent once it adds up).</li>
 * </ul>
 * Gap markers always pass. Both remember only in memory: after a restart the first reading of each tag is sent.
 */
final class Filters {
    static final int REMEMBERED = 100_000;

    private final boolean dedupe;
    private final boolean changeOnly;
    private final double deadband;
    private final Map<String, Boolean> seen = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > REMEMBERED;
        }
    };
    /** machine/tag -> {value, quality} last sent */
    private final Map<String, Object[]> last = new HashMap<>();
    private long duplicates;
    private long unchanged;

    Filters(boolean dedupe, boolean changeOnly, double deadband) {
        this.dedupe = dedupe;
        this.changeOnly = changeOnly;
        this.deadband = Math.max(0, deadband);
    }

    boolean any() {
        return dedupe || changeOnly;
    }

    /** dedupe: false when the reading was carried already (and remembers it otherwise) */
    synchronized boolean fresh(JsonObject m) {
        if (!dedupe || DeliveryEngine.isGap(m)) {
            return true;
        }
        String id = Json.str(m, "messageId");
        String at = Json.str(m, "machineId") + "/" + Json.str(m, "tagName") + "@" + Json.str(m, "timestamp");
        boolean dup = (!id.isEmpty() && seen.containsKey("#" + id)) || (!Json.str(m, "timestamp").isEmpty() && seen.containsKey(at));
        if (dup) {
            duplicates++;
            return false;
        }
        if (!id.isEmpty()) {
            seen.put("#" + id, Boolean.TRUE);
        }
        seen.put(at, Boolean.TRUE);
        return true;
    }

    /** changeOnly: false when value and quality are what was sent last for the tag (and remembers what is sent) */
    synchronized boolean changed(JsonObject m) {
        if (!changeOnly || DeliveryEngine.isGap(m)) {
            return true;
        }
        String key = Json.str(m, "machineId") + "/" + Json.str(m, "tagName");
        JsonElement v = m.get("tagValue");
        Object value = v == null || v.isJsonNull() ? null : v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? (Object) v.getAsDouble()
                : v.isJsonPrimitive() ? v.getAsString() : v.toString();
        String quality = Json.str(m, "quality");
        Object[] prev = last.get(key);
        if (prev != null && quality.equals(prev[1]) && same(prev[0], value)) {
            unchanged++;
            return false;
        }
        last.put(key, new Object[]{value, quality});
        return true;
    }

    private boolean same(Object a, Object b) {
        if (a instanceof Double x && b instanceof Double y) {
            return deadband > 0 ? Math.abs(x - y) <= deadband : x.equals(y);
        }
        return Objects.equals(a, b);
    }

    synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dedupe", dedupe);
        m.put("changeOnly", changeOnly);
        m.put("deadband", deadband);
        m.put("duplicatesDropped", duplicates);
        m.put("unchangedDropped", unchanged);
        return m;
    }
}
