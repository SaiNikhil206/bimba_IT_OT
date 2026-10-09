package com.bimba.gateway.collect;

/**
 * The canonical BIMBA telemetry message (docs/payload.md): the schema version this code writes and the quality codes.
 * <p>
 * Versions are "major.minor". A minor version only adds optional fields or values, so every consumer of 1.0 reads 1.1
 * unchanged (consumers must ignore fields they do not know). A field that is renamed, removed, retyped or made required
 * needs a new major version, its own JSON Schema (gateway: schemas/telemetry-v&lt;major&gt;.schema.json) and a way for
 * existing consumers to keep receiving the previous major.
 * <ul>
 *   <li>1.0: the first format.</li>
 *   <li>1.1: sourceTimestamp (the device's own time, when the protocol reports one), ingestTime (when the gateway
 *       accepted the message), quality UNCERTAIN in use, units converted to the tag's unit.</li>
 * </ul>
 */
public final class Canonical {
    public static final String VERSION = "1.1";

    /** GOOD = usable; UNCERTAIN = a value, but the device or a range check doubts it; BAD = no usable value */
    public enum Quality { GOOD, UNCERTAIN, BAD }

    private Canonical() {
    }
}
