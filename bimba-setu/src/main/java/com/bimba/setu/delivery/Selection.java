package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which readings a pipeline takes: its machines (none listed = all), then tag patterns. A pattern is matched
 * (case-insensitive, * = any text, ? = one character) against "machine/tag", or against the tag name alone when the
 * pattern has no "/". No include pattern = every tag; an exclude pattern always wins.
 */
public final class Selection {
    private final Set<String> machines;
    private final List<Pattern> include;
    private final List<Pattern> exclude;

    public Selection(List<String> machines, List<String> include, List<String> exclude) {
        this.machines = new HashSet<>(machines == null ? List.of() : machines);
        this.include = compile(include);
        this.exclude = compile(exclude);
    }

    public boolean all() {
        return machines.isEmpty() && include.isEmpty() && exclude.isEmpty();
    }

    /**
     * A reading renamed by the tag registry is matched by either name: as delivered, or as collected (source.tag
     * "machine/tag"); an exclude pattern on either name wins.
     */
    public boolean matches(JsonObject m) {
        String machine = Json.str(m, "machineId");
        String tag = Json.str(m, "tagName");
        String collected = m.has("source") && m.get("source").isJsonObject() ? Json.str(m.getAsJsonObject("source"), "tag") : "";
        int slash = collected.indexOf('/');
        if (slash < 0 || collected.equals(machine + "/" + tag)) {
            return matches(machine, tag);
        }
        String cm = collected.substring(0, slash);
        String ct = collected.substring(slash + 1);
        return (taken(machine, tag) || taken(cm, ct)) && !excluded(machine, tag) && !excluded(cm, ct);
    }

    /** the machine is one the pipeline carries (tag patterns aside: what gap markers of a machine need) */
    public boolean machine(String machine) {
        return machines.isEmpty() || machines.contains(machine);
    }

    public boolean matches(String machine, String tag) {
        return taken(machine, tag) && !excluded(machine, tag);
    }

    /** one of the machines, and an include pattern (if any) hits */
    private boolean taken(String machine, String tag) {
        if (!machines.isEmpty() && !machines.contains(machine)) {
            return false;
        }
        String path = machine + "/" + tag;
        return include.isEmpty() || include.stream().anyMatch(p -> hit(p, path, tag));
    }

    private boolean excluded(String machine, String tag) {
        String path = machine + "/" + tag;
        return exclude.stream().anyMatch(p -> hit(p, path, tag));
    }

    private static boolean hit(Pattern p, String path, String tag) {
        return p.pattern().contains("/") ? p.matcher(path).matches() : p.matcher(tag).matches();
    }

    private static List<Pattern> compile(List<String> globs) {
        return (globs == null ? List.<String>of() : globs).stream().map(String::trim).filter(g -> !g.isEmpty()).map(Selection::glob).toList();
    }

    /** "PUMP-*" -> ^\QPUMP-\E.*$ ; the "/" stays literal so hit() can tell path patterns from tag patterns */
    static Pattern glob(String g) {
        StringBuilder re = new StringBuilder();
        StringBuilder lit = new StringBuilder();
        for (char c : g.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!lit.isEmpty()) {
                    re.append(Pattern.quote(lit.toString()));
                    lit.setLength(0);
                }
                re.append(c == '*' ? ".*" : ".");
            } else {
                lit.append(c);
            }
        }
        if (!lit.isEmpty()) {
            re.append(Pattern.quote(lit.toString()));
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
