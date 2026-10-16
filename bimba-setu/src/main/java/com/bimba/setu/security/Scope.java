package com.bimba.setu.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a role assignment holds: some sites (the site name an agent reports), site agents (ids) and machines (names),
 * any of them; a place matches when its site, its agent or its machine is listed. A missing scope (null) means the
 * whole organization. Only {@link Permission#scoped} permissions are limited by it.
 */
public record Scope(List<String> sites, List<String> agents, List<String> machines) {
    static final int MAX_ENTRIES = 200;

    /** does this scope reach the place (agentId and site of the agent; machine may be null for agent-level things) */
    public boolean covers(String agentId, String site, String machine) {
        return has(agents, agentId) || has(sites, site == null ? null : site.trim()) || has(machines, machine);
    }

    public Scope union(Scope o) {
        return new Scope(merge(sites, o.sites), merge(agents, o.agents), merge(machines, o.machines));
    }

    public Map<String, Object> json() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sites", sites);
        m.put("agents", agents);
        m.put("machines", machines);
        return m;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("sites", array(sites));
        o.add("agents", array(agents));
        o.add("machines", array(machines));
        return o;
    }

    /** {sites, agents, machines} from the console or the store; null (the whole organization) when nothing is listed */
    public static Scope parse(JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        Scope s = new Scope(list(o, "sites", false), list(o, "agents", true), list(o, "machines", false));
        return s.sites.isEmpty() && s.agents.isEmpty() && s.machines.isEmpty() ? null : s;
    }

    private static List<String> list(JsonObject o, String k, boolean lower) {
        List<String> out = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray()) {
            for (JsonElement x : o.getAsJsonArray(k)) {
                if (!x.isJsonPrimitive()) {
                    continue;
                }
                String v = x.getAsString().trim();
                if (v.length() > 120) {
                    v = v.substring(0, 120);
                }
                if (lower) {
                    v = v.toLowerCase(Locale.ROOT);
                }
                if (!v.isEmpty() && !has(out, v) && out.size() < MAX_ENTRIES) {
                    out.add(v);
                }
            }
        }
        return List.copyOf(out);
    }

    public static boolean has(List<String> l, String v) {
        if (v == null || v.isEmpty()) {
            return false;
        }
        for (String x : l) {
            if (x.equalsIgnoreCase(v)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> merge(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        b.stream().filter(x -> !has(out, x)).forEach(out::add);
        return List.copyOf(out);
    }

    private static JsonArray array(List<String> l) {
        JsonArray a = new JsonArray();
        l.forEach(a::add);
        return a;
    }
}
