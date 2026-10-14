package com.bimba.edge;

import com.bimba.setu.collect.OpcUaPki;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.LinkedHashMap;
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
 *      {"op":"write","id":..,"source":{kind,settings},"tag":{..},"value":..}   machine command (the agent service
 *       checks first that this computer allows commands)
 * out  {"type":"reading","m":{canonical message}}       every change of value or quality (docs/payload.md)
 *      {"type":"result","id":..,"ok":true,"result":{..}} / {"type":"result","id":..,"ok":false,"error":".."}
 *      {"type":"status","devices":[..]}                  every 10 s
 * </pre>
 * Logs go to stderr (the service appends them to agent.log). The process ends when stdin closes (service stopped).
 * Arguments: --pki &lt;dir&gt; (OPC UA client certificate), --host &lt;computer name&gt;, --opcda &lt;bimba-opcda.exe&gt; (the
 * Windows OPC DA bridge; without it OPC DA sources report a clear error).
 * The collecting itself is EdgeEngine (shared with the Linux site agent, which runs it in-process).
 */
public final class EdgeMain {
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final PrintWriter OUT = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8), 1 << 16), false);

    private final ExecutorService work = Executors.newFixedThreadPool(2, daemon("BIMBA-Edge-Cmd"));
    private final EdgeEngine engine = new EdgeEngine(m -> {
        JsonObject line = new JsonObject();
        line.addProperty("type", "reading");
        line.add("m", m);
        send(line);
    }, EdgeMain::log);

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        String host = opt.getOrDefault("host", OpcUaPki.hostName());
        if (opt.containsKey("opcda")) {
            System.setProperty("bimba.opcda.exe", opt.get("opcda"));
        }
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
            String op = EdgeEngine.str(msg, "op");
            switch (op == null ? "" : op) {
                case "config" -> engine.configure(msg);
                case "test", "browse", "scan", "read", "netscan", "write" -> work.execute(() -> command(op, msg));
                case "stop" -> {
                    engine.stop();
                    System.exit(0);
                }
                default -> log("unknown op " + op);
            }
        }
        // stdin closed: the service stopped or died
        engine.stop();
        System.exit(0);
    }

    private void status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "status");
        m.putAll(engine.status());
        send(m);
    }

    // ------------------------------------------------------------------ console commands

    private void command(String op, JsonObject msg) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("type", "result");
        reply.put("id", EdgeEngine.str(msg, "id"));
        try {
            reply.put("ok", true);
            reply.put("result", EdgeEngine.command(op, msg));
        } catch (Exception e) {
            reply.put("ok", false);
            reply.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        send(reply);
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
}
