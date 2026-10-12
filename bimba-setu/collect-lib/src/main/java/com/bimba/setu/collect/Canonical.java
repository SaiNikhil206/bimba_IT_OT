package com.bimba.setu.collect;

/**
 * The canonical BIMBA telemetry message (docs/payload.md): the schema version this code writes and the quality codes.
 * <p>
 * Versions are "major.minor". A minor version only adds optional fields or values, so every consumer of 1.0 reads 1.1
 * unchanged (consumers must ignore fields they do not know). A field that is renamed, removed, retyped or made required
 * needs a new major version, its own JSON Schema (gateway: schemas/telemetry-v&lt;major&gt;.schema.json) and a way for
 * existing consumers to keep receiving the previous major.
 * <ul>
 *   <li>1.0: the first format.</li>
 *   <li>1.1: sourceTimestamp (the device's own time, when the protocol reports one), ingestTime (when Setu
 *       accepted the message), quality UNCERTAIN in use, units converted to the tag's unit.</li>
 *   <li>1.2: from the tag registry (FR-CAN-04): dataTypeName (Int4, Float8, Boolean, ...), writable, description;
 *       source.tag = the tag as collected when the registry renamed it.</li>
 *   <li>1.3: gap markers (FR-RES-03): messages of category "gap", tag {@link #GAP_TAG}, value "lost" / "restored",
 *       with gap {state: start | end, from, to, seconds, device, reason} when a device or site agent stops and starts
 *       answering.</li>
 * </ul>
 */
public final class Canonical {
    public static final String VERSION = "1.3";
    /** the tag name of gap markers (one per machine, category "gap") */
    public static final String GAP_TAG = "_connection";

    /** GOOD = usable; UNCERTAIN = a value, but the device or a range check doubts it; BAD = no usable value */
    public enum Quality { GOOD, UNCERTAIN, BAD }

    private Canonical() {
    }

    /**
     * A gap marker of one machine (1.3), timestamped at: state start (value "lost", BAD; from = the last good data)
     * or end (value "restored", GOOD; from / to / seconds = the time without data). device = what was lost ("modbus PLC", "site agent X", "gateway gw01"); source = the
     * message's source object (protocol, system, address, ...). Callers add edge when they have one.
     */
    public static com.google.gson.JsonObject gapMarker(String plantId, String machine, String state, java.time.Instant at,
                                                       java.time.Instant from, java.time.Instant to, String device, String reason,
                                                       com.google.gson.JsonObject source) {
        boolean start = "start".equals(state);
        com.google.gson.JsonObject m = new com.google.gson.JsonObject();
        m.addProperty("schemaVersion", VERSION);
        m.addProperty("messageId", java.util.UUID.randomUUID().toString());
        m.addProperty("plantId", plantId);
        m.addProperty("machineId", machine);
        m.addProperty("timestamp", at.toString());
        m.addProperty("tagName", GAP_TAG);
        m.addProperty("tagValue", start ? "lost" : "restored");
        m.addProperty("dataType", "string");
        m.addProperty("unit", "");
        m.addProperty("quality", start ? Quality.BAD.name() : Quality.GOOD.name());
        if (start && reason != null) {
            m.addProperty("qualityDetail", reason.length() > 1000 ? reason.substring(0, 1000) : reason);
        }
        m.addProperty("category", "gap");
        com.google.gson.JsonObject g = new com.google.gson.JsonObject();
        g.addProperty("state", start ? "start" : "end");
        if (from != null) {
            g.addProperty("from", from.toString());
        }
        if (!start && to != null) {
            g.addProperty("to", to.toString());
            if (from != null) {
                g.addProperty("seconds", Math.max(0, to.toEpochMilli() - from.toEpochMilli()) / 1000.0);
            }
        }
        g.addProperty("device", device);
        if (reason != null) {
            g.addProperty("reason", reason.length() > 500 ? reason.substring(0, 500) : reason);
        }
        m.add("gap", g);
        m.add("source", source == null ? new com.google.gson.JsonObject() : source);
        return m;
    }
}
