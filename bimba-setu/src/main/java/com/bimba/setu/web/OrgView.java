package com.bimba.setu.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Setu status as one organization may see it: its machines, the devices that serve them, its pipelines with
 * their routes and destinations, and the problems that name them. Setu-wide internals (queue, configuration file,
 * other organizations' counters) are left out; platform admins get the whole status.
 */
final class OrgView {
    private OrgView() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> status(Map<String, Object> all, List<String> machines, Set<String> pipelineNames,
                                      Set<String> destinationNames, long orgId) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("module", "version", "startedAt", "configError", "destinationSource", "plantId", "edgeId")) {
            if (all.containsKey(k)) {
                m.put(k, all.get(k));
            }
        }
        Set<String> own = new HashSet<>(machines);
        if (all.get("collector") instanceof Map<?, ?> c) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("machines", machines);
            List<Object> tags = new ArrayList<>();
            if (c.get("tags") instanceof List<?> t) {
                t.stream().filter(x -> x instanceof Map<?, ?> tm && own.contains(String.valueOf(tm.get("machine")))).forEach(tags::add);
            }
            col.put("tags", tags);
            col.put("subscribedTags", tags.size());
            List<Object> devices = new ArrayList<>();
            if (c.get("devices") instanceof List<?> ds) {
                for (Object d : ds) {
                    if (d instanceof Map<?, ?> dm && dm.get("machines") instanceof List<?> dms && dms.stream().anyMatch(x -> own.contains(String.valueOf(x)))) {
                        devices.add(d);
                    }
                }
            }
            col.put("devices", devices);
            m.put("collector", col);
        } else {
            m.put("collector", null);
        }
        if (all.get("delivery") instanceof Map<?, ?> d) {
            Map<String, Object> del = new LinkedHashMap<>();
            List<Map<String, Object>> pipes = new ArrayList<>();
            if (d.get("pipelines") instanceof List<?> ps) {
                for (Object p : ps) {
                    Map<String, Object> pm = (Map<String, Object>) p;
                    if (pm.get("orgId") instanceof Number n && n.longValue() == orgId) {
                        pipes.add(pm);
                    }
                }
            }
            Set<Object> ids = new HashSet<>();
            pipes.forEach(p -> ids.add(((Number) p.get("id")).longValue()));
            del.put("pipelines", pipes);
            del.put("routes", only(d.get("routes"), ids));
            del.put("destinations", only(d.get("destinations"), ids));
            del.put("activeDestination", pipes.isEmpty() ? null : pipes.get(0).get("activeDestination"));
            del.put("bufferedBatches", pipes.stream().mapToLong(p -> ((Number) p.getOrDefault("bufferedBatches", 0)).longValue()).sum());
            // readings delivered by the main routes (primary or a secondary); mirror copies are not counted again
            long delivered = 0;
            for (Object r : (List<Object>) del.get("routes")) {
                if (r instanceof Map<?, ?> rm && "main".equals(rm.get("kind")) && rm.get("destinations") instanceof List<?> rds) {
                    for (Object x : rds) {
                        if (x instanceof Map<?, ?> xm && xm.get("messages") instanceof Number n) {
                            delivered += n.longValue();
                        }
                    }
                }
            }
            del.put("messagesDelivered", delivered);
            m.put("delivery", del);
        } else {
            m.put("delivery", null);
        }
        List<String> problems = new ArrayList<>();
        if (all.get("destinationProblems") instanceof List<?> l) {
            for (Object o : l) {
                String p = String.valueOf(o);
                if (pipelineNames.stream().anyMatch(n -> p.startsWith("pipeline " + n + ":") || p.startsWith("pipeline " + n + " "))
                        || destinationNames.stream().anyMatch(n -> p.startsWith(n + ":"))) {
                    problems.add(p);
                }
            }
        }
        m.put("destinationProblems", problems);
        return m;
    }

    private static List<Object> only(Object list, Set<Object> pipelineIds) {
        List<Object> out = new ArrayList<>();
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> om && om.get("pipelineId") instanceof Number n && pipelineIds.contains(n.longValue())) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}
