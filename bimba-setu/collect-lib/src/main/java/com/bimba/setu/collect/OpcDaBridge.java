package com.bimba.setu.collect;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The OPC DA (classic) bridge process: site-agent/opcda, bimba-opcda.exe, a 32-bit Windows program that speaks COM/DCOM
 * to OPC DA servers. JSON lines: one request per line with an id, answers in any order (see its Program.cs).
 * Started on first use and again after it died. Where it is: system property bimba.opcda.exe or BIMBA_OPCDA_EXE
 * (the Windows site agent passes --opcda to the edge collector). Elsewhere (Linux, Setu container) OPC DA sources
 * get a clear error instead.
 */
public final class OpcDaBridge {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.OpcDa");
    private static final Gson GSON = new Gson();
    private static final OpcDaBridge INSTANCE = new OpcDaBridge();

    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private Process process;
    private BufferedWriter in;
    private volatile JsonObject hello;
    private int starts;
    private long nextStart;

    public static OpcDaBridge get() {
        return INSTANCE;
    }

    public static String exe() {
        String p = System.getProperty("bimba.opcda.exe");
        if (p == null || p.isBlank()) {
            p = System.getenv("BIMBA_OPCDA_EXE");
        }
        return p == null || p.isBlank() ? null : p.trim();
    }

    /** {type:hello, version, bits} of the running bridge, or null */
    public JsonObject hello() {
        return hello;
    }

    /** sends one request and waits for its answer's result; throws with the bridge's message when it failed */
    public JsonElement call(JsonObject request, long timeoutMs) throws Exception {
        String id = UUID.randomUUID().toString();
        JsonObject req = request.deepCopy();
        req.addProperty("id", id);
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            synchronized (this) {
                ensureRunning();
                in.write(GSON.toJson(req));
                in.write('\n');
                in.flush();
            }
            JsonObject answer;
            try {
                answer = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new Exception("the OPC DA server did not answer within " + timeoutMs / 1000 + " s");
            }
            if (answer.has("ok") && answer.get("ok").getAsBoolean()) {
                return answer.get("result");
            }
            String error = answer.has("error") && answer.get("error").isJsonPrimitive() ? answer.get("error").getAsString() : "OPC DA request failed";
            throw new Exception(error);
        } finally {
            pending.remove(id);
        }
    }

    /** fire and forget (release a connection) */
    public void post(JsonObject request) {
        synchronized (this) {
            if (process == null || !process.isAlive()) {
                return;
            }
            try {
                JsonObject req = request.deepCopy();
                req.addProperty("id", UUID.randomUUID().toString());
                in.write(GSON.toJson(req));
                in.write('\n');
                in.flush();
            } catch (IOException ignored) {
                // bridge gone: nothing to release
            }
        }
    }

    private void ensureRunning() throws Exception {
        if (process != null && process.isAlive()) {
            return;
        }
        String exe = exe();
        if (exe == null) {
            throw new Exception("OPC DA (classic) is a Windows (COM/DCOM) technology: it is read by the Windows site agent 1.6 or later. "
                    + "On Linux, use the server's OPC UA endpoint if it has one.");
        }
        if (!new File(exe).isFile()) {
            throw new Exception("the OPC DA bridge " + exe + " is missing: reinstall the site agent (1.6 or later)");
        }
        if (System.currentTimeMillis() < nextStart) {
            throw new Exception("the OPC DA bridge stopped; starting it again in a few seconds");
        }
        starts++;
        nextStart = System.currentTimeMillis() + Math.min(60_000, 2000L * starts);
        Process p = new ProcessBuilder(exe).start();
        process = p;
        in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        Thread out = new Thread(() -> readOut(p), "BIMBA-OpcDa-Out");
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(() -> readErr(p), "BIMBA-OpcDa-Err");
        err.setDaemon(true);
        err.start();
        log.info("OPC DA bridge started (pid {})", p.pid());
    }

    private void readOut(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    JsonObject m = JsonParser.parseString(line).getAsJsonObject();
                    String type = m.has("type") ? m.get("type").getAsString() : "";
                    if ("hello".equals(type)) {
                        hello = m;
                        starts = 0;
                    } else if ("result".equals(type) && m.has("id")) {
                        CompletableFuture<JsonObject> f = pending.get(m.get("id").getAsString());
                        if (f != null) {
                            f.complete(m);
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("OPC DA bridge: ignored output {}", line.length() > 200 ? line.substring(0, 200) : line);
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
        hello = null;
        log.warn("OPC DA bridge stopped (exit {})", p.isAlive() ? "?" : String.valueOf(p.exitValue()));
        JsonObject gone = new JsonObject();
        gone.addProperty("ok", false);
        gone.addProperty("error", "the OPC DA bridge stopped");
        pending.values().forEach(f -> f.complete(gone));
    }

    private static void readErr(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    log.info(line);
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }
}
