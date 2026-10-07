package com.bimba.ignition.collect;

import com.bimba.ignition.config.BimbaConfig;
import com.bimba.ignition.delivery.DeliveryEngine;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.browsing.Results;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.tags.browsing.NodeDescription;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeEvent;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeListener;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Discovers tags under the configured roots, subscribes to them and turns every value
 * change into a canonical BIMBA message.
 *
 * Path convention: {root}/{machineId}/{tag}[/{subtag}...]
 *   [default]BIMBA/PUMP-P1/motor_temp  ->  machineId=PUMP-P1, tagName=motor_temp
 */
public class TagCollector {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Collector");

    private final GatewayTagManager tags;
    private final BimbaConfig cfg;
    private final DeliveryEngine delivery;
    private final String session = UUID.randomUUID().toString();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong events = new AtomicLong();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private volatile Instant lastDiscovery;

    private record Subscription(TagPath path, String root, String machineId, String tagName,
                                TagChangeListener listener, String[] unit) {
    }

    public TagCollector(GatewayTagManager tags, BimbaConfig cfg, DeliveryEngine delivery) {
        this.tags = tags;
        this.cfg = cfg;
        this.delivery = delivery;
    }

    /** Browses all roots and subscribes to tags not yet subscribed. Safe to call repeatedly. */
    public void discover() {
        List<TagPath> found = new ArrayList<>();
        for (String root : cfg.tagRoots) {
            try {
                browse(TagPathParser.parse(root), found, 0);
            } catch (Exception e) {
                log.warn("cannot browse root {}: {}", root, e.getMessage());
            }
        }
        List<TagPath> newPaths = new ArrayList<>();
        List<TagChangeListener> newListeners = new ArrayList<>();
        for (TagPath p : found) {
            String full = p.toStringFull();
            if (subscriptions.containsKey(full)) {
                continue;
            }
            Subscription s = describe(p);
            if (s == null) {
                continue;
            }
            subscriptions.put(full, s);
            newPaths.add(p);
            newListeners.add(s.listener());
        }
        if (!newPaths.isEmpty()) {
            loadUnits(newPaths);
            try {
                tags.subscribeAsync(newPaths, newListeners).get(30, TimeUnit.SECONDS);
                log.info("subscribed to {} new tags ({} total)", newPaths.size(), subscriptions.size());
            } catch (Exception e) {
                log.error("subscribe failed", e);
                newPaths.forEach(p -> subscriptions.remove(p.toStringFull()));
            }
        }
        lastDiscovery = Instant.now();
    }

    private void browse(TagPath path, List<TagPath> out, int depth) throws Exception {
        if (depth > 12) {
            return;
        }
        Results<NodeDescription> results = tags.browseAsync(path, new BrowseFilter()).get(30, TimeUnit.SECONDS);
        if (results == null || results.getResults() == null) {
            return;
        }
        for (NodeDescription node : results.getResults()) {
            TagObjectType type = node.getObjectType();
            if (type == TagObjectType.AtomicTag) {
                out.add(node.getFullPath());
            }
            if (node.hasChildren() && (type == TagObjectType.Folder || type == TagObjectType.UdtInstance)) {
                browse(node.getFullPath(), out, depth + 1);
            }
        }
    }

    private Subscription describe(TagPath p) {
        String full = p.toStringFull();
        String root = cfg.tagRoots.stream()
                .filter(r -> full.toLowerCase(Locale.ROOT).startsWith(r.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
        if (root == null) {
            return null;
        }
        String rel = full.substring(root.length()).replaceAll("^/+", "");
        String[] parts = rel.split("/");
        String machine = parts.length > 1 ? parts[0] : cfg.defaultMachineId;
        StringBuilder name = new StringBuilder();
        for (int i = parts.length > 1 ? 1 : 0; i < parts.length; i++) {
            if (name.length() > 0) {
                name.append('_');
            }
            name.append(parts[i]);
        }
        String tagName = name.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        String[] unit = new String[1];
        TagChangeListener listener = event -> onChange(event, machine, tagName, full, unit);
        return new Subscription(p, root, machine, tagName, listener, unit);
    }

    private void loadUnits(List<TagPath> paths) {
        try {
            List<TagPath> unitPaths = new ArrayList<>();
            for (TagPath p : paths) {
                unitPaths.add(TagPathParser.parse(p.toStringFull() + ".EngUnit"));
            }
            List<QualifiedValue> values = tags.readAsync(unitPaths).get(15, TimeUnit.SECONDS);
            for (int i = 0; i < paths.size(); i++) {
                Object v = values.get(i).getValue();
                Subscription s = subscriptions.get(paths.get(i).toStringFull());
                if (s != null && v != null && !v.toString().isBlank()) {
                    s.unit()[0] = v.toString();
                }
            }
        } catch (Exception e) {
            log.debug("could not read engineering units", e);
        }
    }

    private void onChange(TagChangeEvent event, String machine, String tagName, String path, String[] unit) {
        QualifiedValue qv = event.getValue();
        if (qv == null) {
            return;
        }
        events.incrementAndGet();
        JsonObject m = new JsonObject();
        m.addProperty("schemaVersion", "1.0");
        m.addProperty("messageId", UUID.randomUUID().toString());
        m.addProperty("plantId", cfg.plantId);
        m.addProperty("machineId", machine);
        Date ts = qv.getTimestamp();
        m.addProperty("timestamp", (ts == null ? Instant.now() : ts.toInstant()).toString());
        m.addProperty("tagName", tagName);
        Object v = qv.getValue();
        if (v instanceof Boolean b) {
            m.addProperty("tagValue", b);
            m.addProperty("dataType", "boolean");
        } else if (v instanceof Number n && !(n instanceof Double d && (d.isNaN() || d.isInfinite()))
                && !(n instanceof Float f && (f.isNaN() || f.isInfinite()))) {
            m.addProperty("tagValue", n);
            m.addProperty("dataType", "number");
        } else if (v == null || v instanceof Number) {
            m.add("tagValue", com.google.gson.JsonNull.INSTANCE);
            m.addProperty("dataType", "null");
        } else if (v instanceof Date d) {
            m.addProperty("tagValue", d.toInstant().toString());
            m.addProperty("dataType", "string");
        } else {
            m.addProperty("tagValue", v.toString());
            m.addProperty("dataType", "string");
        }
        m.addProperty("unit", unit[0]);
        m.addProperty("quality", quality(qv.getQuality()));
        if (qv.getQuality() != null && !qv.getQuality().isGood()) {
            m.addProperty("qualityDetail", qv.getQuality().toString());
        }
        m.addProperty("category", "telemetry");
        JsonObject src = new JsonObject();
        src.addProperty("protocol", "ignition");
        src.addProperty("system", cfg.edgeId);
        src.addProperty("address", path);
        m.add("source", src);
        JsonObject edge = new JsonObject();
        edge.addProperty("edgeId", cfg.edgeId);
        edge.addProperty("session", session);
        edge.addProperty("seq", seq.incrementAndGet());
        m.add("edge", edge);
        delivery.offer(m);
    }

    private static String quality(QualityCode q) {
        if (q == null) {
            return "BAD";
        }
        return q.isGood() ? "GOOD" : (q.isUncertain() ? "UNCERTAIN" : "BAD");
    }

    public void stop() {
        if (subscriptions.isEmpty()) {
            return;
        }
        List<TagPath> paths = new ArrayList<>();
        List<TagChangeListener> listeners = new ArrayList<>();
        subscriptions.values().forEach(s -> {
            paths.add(s.path());
            listeners.add(s.listener());
        });
        try {
            tags.unsubscribeAsync(paths, listeners).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("unsubscribe failed", e);
        }
        subscriptions.clear();
    }

    public Map<String, Object> status() {
        return Map.of(
                "session", session,
                "subscribedTags", subscriptions.size(),
                "tagEvents", events.get(),
                "lastSeq", seq.get(),
                "lastDiscovery", lastDiscovery == null ? "" : lastDiscovery.toString(),
                "machines", subscriptions.values().stream().map(Subscription::machineId).distinct().sorted().toList());
    }
}
