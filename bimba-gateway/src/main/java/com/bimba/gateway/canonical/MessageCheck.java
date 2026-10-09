package com.bimba.gateway.canonical;

import com.bimba.gateway.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks one canonical message against the JSON Schema of its major version (resources schemas/telemetry-v&lt;major&gt;.schema.json)
 * before it is routed (FR-RTE-01). Every minor version of a major validates with that major's schema; a major this
 * gateway has no schema for is refused as UNSUPPORTED_VERSION. 1.x carries its version in schemaVersion (camelCase
 * fields), 2.x in schema_version (snake_case, {@link CanonicalV2}). A message without a version is checked against the
 * major its field names suggest (and fails, because the version is required).
 */
public final class MessageCheck {
    /** why a record went to quarantine */
    public enum Reason {
        /** not JSON, or not a JSON object */
        MALFORMED_JSON,
        /** a schemaVersion whose major version this gateway has no schema for */
        UNSUPPORTED_VERSION,
        /** does not match its version's JSON Schema (missing field, wrong type, timestamp not UTC, unknown quality, ...) */
        SCHEMA_INVALID,
        /** a site agent reported a machine that is not one of its own */
        UNKNOWN_MACHINE
    }

    public record Problem(Reason reason, String detail) {
    }

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)$");
    private static final Map<Integer, JsonSchema> SCHEMAS = Map.of(1, load("telemetry-v1.schema.json"),
            2, load("telemetry-v2.schema.json"));

    private MessageCheck() {
    }

    private static JsonSchema load(String name) {
        try (InputStream in = MessageCheck.class.getResourceAsStream("/schemas/" + name)) {
            if (in == null) {
                throw new IllegalStateException("schema " + name + " is missing from the gateway jar");
            }
            SchemaValidatorsConfig cfg = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, cfg);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** the major versions this gateway accepts */
    public static Set<Integer> majors() {
        return SCHEMAS.keySet();
    }

    /** the version text: schema_version (2.x) or schemaVersion (1.x), "" when there is none */
    private static String version(JsonObject m) {
        for (String k : new String[]{"schema_version", "schemaVersion"}) {
            JsonElement v = m.get(k);
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                return v.getAsString();
            }
        }
        return "";
    }

    /**
     * The major version a message claims; without a readable version, the one its field names suggest (2 for
     * snake_case, else 1), so it is checked against the schema that explains best what is wrong.
     */
    public static int major(JsonObject m) {
        Matcher x = VERSION.matcher(version(m));
        if (x.matches()) {
            try {
                return Integer.parseInt(x.group(1));
            } catch (NumberFormatException ex) {
                return -1;
            }
        }
        return m.has("schema_version") || m.has("plant_id") || m.has("tag_value") ? 2 : 1;
    }

    /** null when the message may be routed, else why not */
    public static Problem check(JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            return new Problem(Reason.MALFORMED_JSON, "a message must be a JSON object");
        }
        JsonObject m = e.getAsJsonObject();
        int major = major(m);
        if (!SCHEMAS.containsKey(major)) {
            return new Problem(Reason.UNSUPPORTED_VERSION, "schema version " + version(m)
                    + " is not supported (this gateway reads " + SCHEMAS.keySet().stream().sorted()
                    .map(k -> k + ".x").collect(Collectors.joining(", ")) + ")");
        }
        Set<ValidationMessage> errors = SCHEMAS.get(major).validate(Json.GSON.toJson(m), InputFormat.JSON);
        if (errors.isEmpty()) {
            return null;
        }
        String detail = errors.stream().map(ValidationMessage::getMessage).sorted().limit(5).collect(Collectors.joining("; "));
        return new Problem(Reason.SCHEMA_INVALID, errors.size() > 5 ? detail + "; and " + (errors.size() - 5) + " more" : detail);
    }
}
