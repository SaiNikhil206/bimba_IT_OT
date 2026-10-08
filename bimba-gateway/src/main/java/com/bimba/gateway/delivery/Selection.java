package com.bimba.gateway.delivery;

import com.bimba.gateway.Json;
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

    public boolean matches(JsonObject m) {
        return matches(Json.str(m, "machineId"), Json.str(m, "tagName"));
    }

    public boolean matches(String machine, String tag) {
        if (!machines.isEmpty() && !machines.contains(machine)) {
            return false;
        }
        String path = machine + "/" + tag;
        if (!include.isEmpty() && include.stream().noneMatch(p -> hit(p, path, tag))) {
            return false;
        }
        return exclude.stream().noneMatch(p -> hit(p, path, tag));
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
