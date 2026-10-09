package com.bimba.edge;

import com.bimba.gateway.collect.Collector;
import com.bimba.gateway.collect.Driver;
import com.bimba.gateway.collect.ModbusDriver;
import com.bimba.gateway.collect.OpcUaDriver;
import com.bimba.gateway.collect.OpcUaPki;
import com.bimba.gateway.config.TagModel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * BIMBA edge collector: the data-collection process of the BIMBA Site Agent on a SCADA machine. The agent service
 * (bimba-site-agent.exe) starts it and talks JSON lines over stdin/stdout:
 * <pre>
 * in   {"op":"config","plantId":..,"edgeId":..,"sources":[{id,name,kind,settings,tags:[{name,address,unit,dataType,machine,
 *       rawLow,rawHigh,scaledLow,scaledHigh,sourceUnit,engLow,engHigh}],pollMs,enabled}]}
 *      (a tag's machine: it reports as that machine instead of the source's name, so one connection feeds several machines)
 *      {"op":"test"|"browse"|"scan"|"read","id":..,"source":{kind,settings},"nodeId":..,"limit":..,"tags":[..]}
 * out  {"type":"reading","m":{canonical message}}       every change of value or quality (docs/payload.md)
 *      {"type":"result","id":..,"ok":true,"result":{..}} / {"type":"result","id":..,"ok":false,"error":".."}
 *      {"type":"status","devices":[..]}                  every 10 s
 * </pre>
 * Logs go to stderr (the service appends them to agent.log). The process ends when stdin closes (service stopped).
 * Arguments: --pki &lt;dir&gt; (OPC UA client certificate), --host &lt;computer name&gt;.
 */
public final class EdgeMain {
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final PrintWriter OUT = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8), 1 << 16), false);

    private final ExecutorService work = Executors.newFixedThreadPool(2, daemon("BIMBA-Edge-Cmd"));
    private Collector collector;

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        String host = opt.getOrDefault("host", OpcUaPki.hostName());
        if (opt.containsKey("pki")) {
            try {
                OpcUaPki.Identity id = OpcUaPki.configure(new File(opt.get("pki")), "BIMBA Site Agent " + host,
                        "urn:bimba:site-agent:" + host.toLowerCase(Locale.ROOT));
                log("OPC UA client certificate " + id.applicationUri() + " SHA-256 " + id.certificateSha256());
            } catch (Exception e) {
                log("OPC UA client certificate unavailable (only security None works): " + e);
            }
        }
        new EdgeMain().run();
    }

    private void run() throws Exception {
        ScheduledExecutorService status = Executors.newSingleThreadScheduledExecutor(daemon("BIMBA-Edge-Status"));
        status.scheduleWithFixedDelay(this::status, 10, 10, TimeUnit.SECONDS);
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("type", "hello");
        hello.put("version", EdgeMain.class.getPackage().getImplementationVersion());
        hello.put("java", System.getProperty("java.version"));
        hello.put("opcuaCertificate", OpcUaPki.identity().certificateSha256());
        hello.put("opcuaApplicationUri", OpcUaPki.identity().applicationUri());
        send(hello);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject msg;
            try {
                msg = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException e) {
                log("ignored a line that is not JSON: " + e.getMessage());
                continue;
            }
            String op = str(msg, "op");
            switch (op == null ? "" : op) {
                case "config" -> apply(msg);
                case "test", "browse", "scan", "read" -> work.execute(() -> command(op, msg));
                case "stop" -> {
                    stopCollector();
                    System.exit(0);
                }
                default -> log("unknown op " + op);
            }
        }
        // stdin closed: the service stopped or died
        stopCollector();
        System.exit(0);
    }

    // ------------------------------------------------------------------ config -> collector

    private synchronized void apply(JsonObject cfg) {
        stopCollector();
        List<TagModel.Tag> tags = new ArrayList<>();
        JsonObject sources = new JsonObject();
        JsonArray list = cfg.has("sources") && cfg.get("sources").isJsonArray() ? cfg.getAsJsonArray("sources") : new JsonArray();
        int count = 0;
        for (JsonElement e : list) {
            JsonObject s = e.getAsJsonObject();
            String kind = str(s, "kind");
            if (!("opcua".equals(kind) || "modbus".equals(kind)) || (s.has("enabled") && !s.get("enabled").getAsBoolean())) {
                continue;
            }
            String id = str(s, "id");
            String machine = str(s, "name");
            JsonObject settings = s.has("settings") && s.get("settings").isJsonObject() ? s.getAsJsonObject("settings").deepCopy() : new JsonObject();
            if (s.has("pollMs")) {
                settings.add("pollMs", s.get("pollMs"));
            }
            if (!sources.has(kind)) {
                sources.add(kind, new JsonObject());
            }
            sources.getAsJsonObject(kind).add(id, settings);
            for (JsonElement te : s.has("tags") && s.get("tags").isJsonArray() ? s.getAsJsonArray("tags") : new JsonArray()) {
                JsonObject t = te.getAsJsonObject();
                String own = str(t, "machine");
                tags.add(tag(own == null || own.isBlank() ? machine : own, id, kind, t));
            }
            count++;
        }
        if (tags.isEmpty()) {
            log("no tags to collect (" + count + " source(s))");
            return;
        }
        collector = new Collector(strOr(cfg, "plantId", "PLANT"), strOr(cfg, "edgeId", OpcUaPki.hostName()),
                cfg.has("pollMs") ? cfg.get("pollMs").getAsLong() : 1000, tags, sources, m -> {
            JsonObject line = new JsonObject();
            line.addProperty("type", "reading");
            line.add("m", m);
            send(line);
        });
        collector.start();
        log("collecting " + tags.size() + " tag(s) from " + count + " source(s)");
    }

    static TagModel.Tag tag(String machine, String sourceId, String kind, JsonObject t) {
        String name = strOr(t, "name", strOr(t, "address", "tag")).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        String address = strOr(t, "address", "");
        if ("modbus".equals(kind)) {
            address = address.trim().toUpperCase(Locale.ROOT);
        }
        boolean scaled = t.has("rawLow") && t.has("rawHigh") && t.has("scaledLow") && t.has("scaledHigh");
        return new TagModel.Tag(machine, name, machine + "/" + name, "modbus".equals(kind) ? TagModel.Kind.MODBUS : TagModel.Kind.OPCUA,
                sourceId, address, str(t, "dataType"), str(t, "unit"), num(t, "rawLow"), num(t, "rawHigh"), num(t, "scaledLow"),
                num(t, "scaledHigh"), scaled, str(t, "sourceUnit"), TagModel.optNum(t, "engLow"), TagModel.optNum(t, "engHigh"));
    }

    private synchronized void stopCollector() {
        if (collector != null) {
            collector.stop();
            collector = null;
        }
    }

    private void status() {
        Collector c;
        synchronized (this) {
            c = collector;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "status");
        if (c != null) {
            Map<String, Object> s = c.status();
            m.put("devices", s.get("devices"));
            m.put("tagEvents", s.get("tagEvents"));
        } else {
            m.put("devices", List.of());
        }
        send(m);
    }

    // ------------------------------------------------------------------ console commands

    private void command(String op, JsonObject msg) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("type", "result");
        reply.put("id", str(msg, "id"));
        try {
            JsonObject source = msg.has("source") && msg.get("source").isJsonObject() ? msg.getAsJsonObject("source") : new JsonObject();
            String kind = str(source, "kind");
            JsonObject settings = source.has("settings") && source.get("settings").isJsonObject() ? source.getAsJsonObject("settings") : new JsonObject();
            Object result;
            if ("opcua".equals(kind) && "test".equals(op)) {
                result = OpcUaDriver.test(settings);
            } else if ("opcua".equals(kind) && "browse".equals(op)) {
                result = OpcUaDriver.browse(settings, str(msg, "nodeId"), msg.has("limit") ? msg.get("limit").getAsInt() : 500);
            } else if ("opcua".equals(kind) && "scan".equals(op)) {
                result = OpcUaDriver.scan(settings, str(msg, "nodeId"), msg.has("limit") ? msg.get("limit").getAsInt() : 5000);
            } else if ("test".equals(op) || "read".equals(op)) {
                result = readOnce(kind, settings, msg);
            } else {
                throw new Exception(op + " is not available for " + kind + " sources");
            }
            reply.put("ok", true);
            reply.put("result", result);
        } catch (Exception e) {
            reply.put("ok", false);
            reply.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        send(reply);
    }

    /** connect (Modbus) or read the given tags once: {ok, values:[{name, address, value, good, detail}]} */
    private static Map<String, Object> readOnce(String kind, JsonObject settings, JsonObject msg) throws Exception {
        List<TagModel.Tag> tags = new ArrayList<>();
        for (JsonElement e : msg.has("tags") && msg.get("tags").isJsonArray() ? msg.getAsJsonArray("tags") : new JsonArray()) {
            tags.add(tag("test", "test", kind, e.getAsJsonObject()));
        }
        Driver d = switch (kind == null ? "" : kind) {
            case "modbus" -> new ModbusDriver(settings);
            case "opcua" -> new OpcUaDriver(settings);
            default -> throw new Exception("unsupported source kind " + kind);
        };
        try {
            List<Driver.Reading> values = d.read(tags);      // also connects: throws when the device cannot be reached
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < tags.size(); i++) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("name", tags.get(i).name());
                v.put("address", tags.get(i).address());
                v.put("value", values.get(i).value());
                v.put("good", values.get(i).good());
                v.put("quality", values.get(i).quality().name());
                v.put("detail", values.get(i).detail());
                out.add(v);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("target", d.describe());
            m.put("values", out);
            return m;
        } finally {
            d.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    static void send(Object o) {
        String json = GSON.toJson(o);
        synchronized (OUT) {
            OUT.write(json);
            OUT.write('\n');
            OUT.flush();
        }
    }

    static void log(String msg) {
        System.err.println("[edge] " + msg);
        System.err.flush();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String strOr(JsonObject o, String k, String d) {
        String v = str(o, k);
        return v == null || v.isBlank() ? d : v;
    }

    private static double num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
