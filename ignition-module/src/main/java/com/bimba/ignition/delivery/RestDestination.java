package com.bimba.ignition.delivery;

import com.bimba.ignition.config.BimbaConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** HTTPS/HTTP POST of the batch envelope (Boomi Web Services Server listener or BIMBA Ingest /api/ingest/). */
public class RestDestination extends Destination {
    private final HttpClient http;

    public RestDestination(BimbaConfig.Destination cfg) {
        super(cfg);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.timeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public void send(byte[] batch) throws Exception {
        HttpResponse<String> resp = http.send(request(batch).build(), HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code >= 200 && code < 300) {
            return;
        }
        String detail = detail(resp);
        if (code == 400 || code == 413 || code == 422) {
            throw new RejectedException(detail);
        }
        throw new java.io.IOException(detail);
    }

    @Override
    public boolean canProbe() {
        return cfg.probe;
    }

    /** Same URL, headers and auth as a real batch, so a wrong API key fails the probe too. */
    @Override
    public void probe() throws Exception {
        HttpRequest req = request("{\"probe\":true}".getBytes(StandardCharsets.UTF_8))
                .header("X-BIMBA-Probe", "1")
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new java.io.IOException("probe " + detail(resp));
        }
    }

    private HttpRequest.Builder request(byte[] body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(cfg.url))
                .timeout(Duration.ofMillis(cfg.timeoutMs))
                .header("Content-Type", "application/json")
                .header("User-Agent", "BIMBA-IT-OT-Agent/1.4")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (Map.Entry<String, String> h : cfg.headers.entrySet()) {
            req.header(h.getKey(), h.getValue());
        }
        if (cfg.auth != null && "basic".equalsIgnoreCase(cfg.auth.type)) {
            String token = Base64.getEncoder().encodeToString(
                    (cfg.auth.username + ":" + cfg.auth.password).getBytes(StandardCharsets.UTF_8));
            req.header("Authorization", "Basic " + token);
        } else if (cfg.auth != null && "bearer".equalsIgnoreCase(cfg.auth.type)) {
            req.header("Authorization", "Bearer " + cfg.auth.token);
        }
        return req;
    }

    private static String detail(HttpResponse<String> resp) {
        String body = resp.body() == null ? "" : resp.body();
        return "HTTP " + resp.statusCode() + " " + body.substring(0, Math.min(200, body.length()));
    }

    @Override
    public String endpoint() {
        return cfg.url;
    }
}
