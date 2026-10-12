package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Send unchanged values again every N seconds" of an every-change pipeline: readings only come when a value or its
 * quality changes, so a steady tag (and one the "only changed values" filter holds back) would otherwise never be
 * sent again, and a receiver could not tell a steady value from a dead one. A tag whose last reading was sent N s
 * ago is sent again: a copy with a new messageId, timestamp = now, sourceTimestamp = when the value was read,
 * aggregate {fn: "repeat", windowSeconds: N}, no edge.seq. Gap markers are not repeated.
 */
final class Refresher {
    private final long periodMs;
    /** machine/tag -> {the reading, when it was sent} */
    private final Map<String, Object[]> last = new LinkedHashMap<>();

    Refresher(int seconds) {
        this.periodMs = Math.max(1, seconds) * 1000L;
    }

    int seconds() {
        return (int) (periodMs / 1000);
    }

    /** readings the pipeline just sent */
    synchronized void sent(List<JsonObject> messages, long now) {
        for (JsonObject m : messages) {
            if (!DeliveryEngine.isGap(m)) {
                last.put(Json.str(m, "machineId") + "/" + Json.str(m, "tagName"), new Object[]{m, now});
            }
        }
    }

    /** copies of the readings due again by now */
    synchronized List<JsonObject> due(long now) {
        List<JsonObject> out = new ArrayList<>();
        String at = Instant.ofEpochMilli(now).toString();
        for (Object[] e : last.values()) {
            if (now - (long) e[1] < periodMs) {
                continue;
            }
            JsonObject m = (JsonObject) e[0];
            JsonObject c = m.deepCopy();
            c.addProperty("messageId", UUID.randomUUID().toString());
            if (Json.str(m, "sourceTimestamp").isEmpty()) {
                c.addProperty("sourceTimestamp", Json.str(m, "timestamp"));
            }
            c.addProperty("timestamp", at);
            if (c.has("edge") && c.get("edge").isJsonObject()) {
                c.getAsJsonObject("edge").remove("seq");
            }
            JsonObject agg = new JsonObject();
            agg.addProperty("fn", "repeat");
            agg.addProperty("windowSeconds", periodMs / 1000);
            c.add("aggregate", agg);
            out.add(c);
            e[1] = now;                                                // so it is not made twice before it is sent
        }
        return out;
    }
}
