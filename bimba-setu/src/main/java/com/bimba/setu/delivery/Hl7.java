package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HL7 v2 messages made from readings (FR-END-03, FR-RTE-03): a template of segments, one per line, with {field}
 * placeholders (every template field, plus the hl7.* ones below). The OBX lines (with NTE lines right after them) are
 * written once per reading; the other lines once per message, filled from its first reading. Inserted values are
 * escaped (| ^ ~ \ &amp; and line breaks), the template's own text is not.
 * <ul>
 *   <li>{hl7.controlId}: MSH-10, the same for the same readings of the same batch, so a receiver can drop a message
 *       that was sent again after a lost acknowledgement;</li>
 *   <li>{hl7.now}: when the batch was made; {hl7.time}: when the reading was read (HL7 date/time, UTC);</li>
 *   <li>{hl7.setId}: 1, 2, ... per OBX; {hl7.valueType}: NM for numbers, else ST; {hl7.value}: the value (empty when
 *       there is none); {hl7.status}: OBX-11, F for GOOD, R for UNCERTAIN, X for BAD (no result).</li>
 * </ul>
 * mode machine = one message per machine per batch (an OBX per reading), reading = one message per reading.
 */
public final class Hl7 {
    /** ORU^R01 2.5: the machine is the order (OBR-4) and the equipment (OBX-18), each reading an observation */
    public static final String DEFAULT = String.join("\n",
            "MSH|^~\\&|BIMBA|{plantId}|RECEIVER|FACILITY|{hl7.now}||ORU^R01^ORU_R01|{hl7.controlId}|P|2.5",
            "OBR|1||{hl7.controlId}|{machineId}^{machineId}^BIMBA|||{hl7.time}",
            "OBX|{hl7.setId}|{hl7.valueType}|{tagName}^{tagName}^BIMBA||{hl7.value}|{unit}|||||{hl7.status}|||{hl7.time}||||{machineId}");
    static final List<String> SPECIAL = List.of("hl7.controlId", "hl7.now", "hl7.time", "hl7.setId", "hl7.valueType", "hl7.value", "hl7.status");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.]*)\\}");
    private static final DateTimeFormatter DTM = DateTimeFormatter.ofPattern("yyyyMMddHHmmss.SSSZ", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** one message: its control id (MSH-10, what the acknowledgement names) and its segments joined by CR */
    public record Message(String controlId, String text) {
    }

    private final List<String> head;
    private final List<String> each;
    private final List<String> tail;

    private Hl7(List<String> head, List<String> each, List<String> tail) {
        this.head = head;
        this.each = each;
        this.tail = tail;
    }

    /** the default template for an empty text; IllegalArgumentException with a readable reason when it is not usable */
    public static Hl7 compile(String text) {
        String t = text == null || text.isBlank() ? DEFAULT : text;
        List<String> lines = new ArrayList<>();
        for (String l : t.split("\\r\\n|\\r|\\n")) {
            if (!l.isBlank()) {
                lines.add(l.strip());
            }
        }
        if (lines.isEmpty() || !lines.get(0).startsWith("MSH|")) {
            throw new IllegalArgumentException("An HL7 template starts with the MSH segment (MSH|^~\\&|...).");
        }
        List<String> unknown = new ArrayList<>();
        for (String l : lines) {
            if (!l.matches("[A-Z][A-Z0-9]{2}\\|.*")) {
                throw new IllegalArgumentException("\"" + cut(l) + "\" is not an HL7 segment (three letters or digits, then |).");
            }
            Matcher m = PLACEHOLDER.matcher(l);
            while (m.find()) {
                String n = m.group(1);
                if (!(n.startsWith("hl7.") ? SPECIAL.contains(n) : Template.known(n)) && !unknown.contains("{" + n + "}")) {
                    unknown.add("{" + n + "}");
                }
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown field" + (unknown.size() > 1 ? "s " : " ") + String.join(", ", unknown)
                    + ". Available: the template fields and " + String.join(", ", SPECIAL.stream().map(s -> "{" + s + "}").toList()) + ".");
        }
        int first = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("OBX|")) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            throw new IllegalArgumentException("The template needs an OBX segment: it is written once for every reading.");
        }
        int end = first + 1;
        while (end < lines.size() && (lines.get(end).startsWith("OBX|") || lines.get(end).startsWith("NTE|"))) {
            end++;
        }
        return new Hl7(List.copyOf(lines.subList(0, first)), List.copyOf(lines.subList(first, end)), List.copyOf(lines.subList(end, lines.size())));
    }

    /** the messages of a batch envelope (its readings in "messages") */
    public List<Message> render(JsonObject env, String mode) {
        List<JsonObject> all = new ArrayList<>();
        env.getAsJsonArray("messages").forEach(e -> all.add(e.getAsJsonObject()));
        Map<String, List<JsonObject>> groups = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            JsonObject m = all.get(i);
            String key = "reading".equalsIgnoreCase(mode) ? "#" + i + "/" + Json.str(m, "messageId") : "m/" + Json.str(m, "machineId");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }
        List<Message> out = new ArrayList<>();
        String now = time(Json.str(env, "createdAt"));
        for (Map.Entry<String, List<JsonObject>> g : groups.entrySet()) {
            String id = controlId(Json.str(env, "batchId") + "|" + g.getKey());
            JsonObject first = g.getValue().get(0);
            List<String> segs = new ArrayList<>();
            head.forEach(l -> segs.add(fill(l, first, env, id, now, 0)));
            int set = 0;
            for (JsonObject m : g.getValue()) {
                set++;
                for (String l : each) {
                    segs.add(fill(l, m, env, id, now, set));
                }
            }
            tail.forEach(l -> segs.add(fill(l, first, env, id, now, 0)));
            out.add(new Message(id, String.join("\r", segs) + "\r"));
        }
        return out;
    }

    private static String fill(String line, JsonObject m, JsonObject env, String controlId, String now, int setId) {
        Matcher p = PLACEHOLDER.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (p.find()) {
            String v = switch (p.group(1)) {
                case "hl7.controlId" -> controlId;
                case "hl7.now" -> now;
                case "hl7.time" -> time(Json.str(m, "timestamp"));
                case "hl7.setId" -> String.valueOf(Math.max(1, setId));
                case "hl7.valueType" -> isNumber(m) ? "NM" : "ST";
                case "hl7.value" -> escape(valueText(m));
                case "hl7.status" -> switch (Json.str(m, "quality").toUpperCase(Locale.ROOT)) {
                    case "GOOD" -> "F";
                    case "UNCERTAIN" -> "R";
                    default -> "X";
                };
                default -> {
                    JsonElement e = Template.value(p.group(1), m, env);
                    yield escape(e.isJsonNull() ? "" : e.isJsonPrimitive() ? Formats.plain(e.getAsJsonPrimitive()) : e.toString());
                }
            };
            p.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        p.appendTail(sb);
        return sb.toString();
    }

    private static boolean isNumber(JsonObject m) {
        JsonElement v = m.get("tagValue");
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber();
    }

    private static String valueText(JsonObject m) {
        JsonElement v = m.get("tagValue");
        if (v == null || v.isJsonNull()) {
            return "";
        }
        return v.isJsonPrimitive() ? Formats.plain(v.getAsJsonPrimitive()) : v.toString();
    }

    /** an ISO time as HL7 DTM (yyyyMMddHHmmss.SSS+0000); empty when it is not a time */
    static String time(String iso) {
        try {
            return DTM.format(Instant.parse(iso));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** HL7 escape sequences for the delimiters and line breaks in a value */
    static String escape(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\E\\");
                case '|' -> sb.append("\\F\\");
                case '^' -> sb.append("\\S\\");
                case '&' -> sb.append("\\T\\");
                case '~' -> sb.append("\\R\\");
                case '\r' -> sb.append("\\X0D\\");
                case '\n' -> sb.append("\\X0A\\");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 20 hex characters (MSH-10 fits 20 in HL7 2.5), the same for the same input */
    static String controlId(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 10).toUpperCase(Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
