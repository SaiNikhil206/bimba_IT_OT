package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Boomi Event Streams via the Produce REST API.
 *
 * POST {topic REST URL from "View REST"}  Authorization: Bearer {environment token}
 *   single-message API (URL contains /singlemsg/): body = payload as-is, properties as x-msg-props-* headers
 *   multi-message API: {"messages":[{"payload":"...","properties":{...}}]}
 *   -> 200 {"status":"success","messageIds":"..."}
 *
 * esMode "batch"   (default): one Event Streams message per plugin batch (payload = batch envelope JSON)
 * esMode "message"          : one Event Streams message per tag value (payload = canonical message JSON)
 * Limits (Boomi docs): 5 MB per message, 10 MB per request, 60,000 requests / 5 min per IP.
 */
public class EventStreamsDestination extends Destination {
    private static final int MAX_REQUEST_BYTES = 9 * 1024 * 1024;  // stay under the 10 MB request limit
    private static final int MAX_MESSAGE_BYTES = 5 * 1024 * 1024;  // 5 MB per message

    private final HttpClient http;

    public EventStreamsDestination(BimbaConfig.Destination cfg) {
        super(cfg);
        this.http = httpClient();                  // timeout, own certificates, proxy
    }

    /** The topic's "View REST" URL: .../rest/singlemsg/... (single message) or the multi-message variant. */
    private boolean singleMessageApi() {
        if ("single".equalsIgnoreCase(cfg.esApi)) {
            return true;
        }
        if ("multi".equalsIgnoreCase(cfg.esApi)) {
            return false;
        }
        return cfg.url != null && cfg.url.contains("/singlemsg/");
    }

    @Override
    public void send(byte[] batch) throws Exception {
        String batchJson = new String(batch, StandardCharsets.UTF_8);
        JsonObject envelope = Json.GSON.fromJson(batchJson, JsonObject.class);
        boolean perTag = "message".equalsIgnoreCase(cfg.esMode);

        // (payload, properties) pairs to produce
        java.util.List<String> payloads = new java.util.ArrayList<>();
        java.util.List<JsonObject> props = new java.util.ArrayList<>();
        if (perTag) {
            for (JsonElement m : envelope.getAsJsonArray("messages")) {
                JsonObject msg = m.getAsJsonObject();
                JsonObject p = baseProperties(envelope);
                p.addProperty(key("messageId"), msg.get("messageId").getAsString());
                p.addProperty(key("machineId"), msg.get("machineId").getAsString());
                p.addProperty(key("tagName"), msg.get("tagName").getAsString());
                payloads.add(Json.GSON.toJson(layout(msg, envelope)));
                props.add(p);
            }
        } else {
            JsonObject p = baseProperties(envelope);
            p.addProperty("count", String.valueOf(envelope.get("count").getAsInt()));
            payloads.add(new String(mappedEnvelope(batch), StandardCharsets.UTF_8));
            props.add(p);
        }

        if (singleMessageApi()) {
            // Single-message API: body = the payload itself, properties as x-msg-props-* headers.
            // In esMode=message this is one request per tag value (mind the 60,000 req / 5 min limit).
            for (int i = 0; i < payloads.size(); i++) {
                byte[] body = payloads.get(i).getBytes(StandardCharsets.UTF_8);
                if (body.length > MAX_MESSAGE_BYTES) {
                    throw new RejectedException("Event Streams message of " + body.length
                            + " bytes exceeds the 5 MB limit; lower batch.maxMessages");
                }
                HttpRequest.Builder req = baseRequest(body);
                for (Map.Entry<String, JsonElement> e : props.get(i).entrySet()) {
                    req.header("x-msg-props-" + e.getKey(), e.getValue().getAsString());
                }
                check(http.send(req.build(), HttpResponse.BodyHandlers.ofString()));
            }
            return;
        }

        // Multi-message API: {"messages":[{"payload":"...","properties":{...}}]}
        JsonArray esMessages = new JsonArray();
        for (int i = 0; i < payloads.size(); i++) {
            esMessages.add(esMessage(payloads.get(i), props.get(i)));
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("messages", esMessages);
        byte[] bytes = Json.GSON.toJson(wrapper).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REQUEST_BYTES) {
            // a replayed oversized batch would never fit: park it instead of retrying forever
            throw new RejectedException("Event Streams request of " + bytes.length + " bytes exceeds the 10 MB limit;"
                    + " lower batch.maxMessages");
        }
        check(http.send(baseRequest(bytes).build(), HttpResponse.BodyHandlers.ofString()));
    }

    /** GET is not a produce call: an accepted token gets 404/405, a rejected one 401/403. Nothing is published. */
    @Override
    public String test() throws Exception {
        HttpRequest.Builder req = proxied(HttpRequest.newBuilder(URI.create(cfg.url)))
                .timeout(Duration.ofMillis(cfg.timeoutMs))
                .header("User-Agent", "BIMBA-IT-OT-Agent/1.4")
                .header("Authorization", "Bearer " + (cfg.auth != null ? cfg.auth.token : ""))
                .GET();
        HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        checkAccess(r, "Event Streams");
        return "Token accepted by Event Streams (HTTP " + r.statusCode() + " to a test request; nothing was published).";
    }

    private HttpRequest.Builder baseRequest(byte[] body) {
        HttpRequest.Builder req = proxied(HttpRequest.newBuilder(URI.create(cfg.url)))
                .timeout(Duration.ofMillis(cfg.timeoutMs))
                .header("Content-Type", "application/json")
                .header("User-Agent", "BIMBA-IT-OT-Agent/1.4")
                .header("Authorization", "Bearer " + (cfg.auth != null ? cfg.auth.token : ""))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        cfg.headers.forEach(req::header);
        return req;
    }

    private static void check(HttpResponse<String> resp) throws Exception {
        int code = resp.statusCode();
        String text = resp.body() == null ? "" : resp.body();
        if (text.contains("<html")) { // Boomi's gateway answers some errors with an HTML page
            java.util.regex.Matcher t = java.util.regex.Pattern.compile("(?is)<title>(.*?)</title>").matcher(text);
            text = t.find() ? t.group(1) : "";
        }
        text = text.replaceAll("\\s+", " ").trim();
        if (code >= 200 && code < 300) {
            // Event Streams answers 200 {"status":"success",...}; anything else in the body is a failure
            if (text.contains("\"status\"") && !text.replace(" ", "").contains("\"status\":\"success\"")) {
                throw new java.io.IOException("Event Streams: " + text.substring(0, Math.min(200, text.length())));
            }
            return;
        }
        throwIfBusy(resp, "Event Streams");          // 429 rate limit / 503: wait as long as its Retry-After says
        String detail = "Event Streams HTTP " + code + " " + text.substring(0, Math.min(200, text.length()));
        if (code == 400 || code == 413 || code == 422) {
            throw new RejectedException(detail);
        }
        throw new java.io.IOException(detail); // 401/403 (token), 404 (topic), 429 (rate limit), 5xx -> fail over
    }

    /** message properties; snake_case names for format 2 */
    private JsonObject baseProperties(JsonObject envelope) {
        JsonObject p = new JsonObject();
        p.addProperty(key("schemaVersion"), version(envelope));
        p.addProperty(key("batchId"), str(envelope, "batchId"));
        p.addProperty(key("plantId"), str(envelope, "plantId"));
        p.addProperty(key("edgeId"), str(envelope, "edgeId"));
        p.addProperty("source", "bimba-it-ot-agent");
        return p;
    }

    private static JsonObject esMessage(String payload, JsonObject properties) {
        JsonObject m = new JsonObject();
        m.addProperty("payload", payload);
        m.add("properties", properties);
        return m;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    @Override
    public String endpoint() {
        return cfg.url;
    }
}
