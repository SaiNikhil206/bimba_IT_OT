package com.bimba.setu.delivery;

import com.bimba.setu.SetuService;
import com.bimba.setu.Json;
import com.bimba.setu.canonical.QuarantineSettings;
import com.bimba.setu.canonical.QuarantineSettings.Recipients;
import com.bimba.setu.config.DestinationStore;
import com.bimba.setu.security.OrgService;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FR-RES-05: when a route's disk buffer reaches 70 % of its size (and when it is back below 60 %), the people of the
 * pipeline are told: its own e-mail addresses and / or webhook (pipeline setting bufferAlerts) and, unless the pipeline
 * turns it off, the organization's alert recipients of the Quarantine page (an address on both lists gets one e-mail).
 * Setu log and the Pipelines page show it in any case. Sent on a thread of its own: the route never waits for mail.
 * <p>
 * Webhook body: {event: "bimba.buffer.high" | "bimba.buffer.normal" | "bimba.buffer.test", pipeline, route, percent,
 * bufferedMegabytes, maxMegabytes, organization, sentAt, url}; signed like the quarantine alerts when a secret is set.
 */
@Service
public class BufferAlerts {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Delivery");
    private final SetuService setu;
    private final QuarantineSettings alerts;
    private final OrgService orgs;
    private final DestinationStore store;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BIMBA-BufferAlerts");
        t.setDaemon(true);
        return t;
    });

    /** who a pipeline's buffer alerts go to */
    record Plan(String pipeline, String uid, Recipients own, boolean organization) {
    }

    public BufferAlerts(SetuService setu, QuarantineSettings alerts, OrgService orgs, DestinationStore store) {
        this.setu = setu;
        this.alerts = alerts;
        this.orgs = orgs;
        this.store = store;
    }

    @PostConstruct
    void init() {
        setu.setBufferAlerts(a -> sender.execute(() -> send(a)));
    }

    @PreDestroy
    void stop() {
        sender.shutdownNow();
    }

    void send(DeliveryEngine.BufferAlert a) {
        long org = a.orgId();
        if (org == 0) {
            Long d = orgs.defaultOrgId();                            // a pipeline from before organizations
            if (d == null) {
                return;
            }
            org = d;
        }
        Plan plan = plan(org, a.pipelineId());
        JsonObject details = new JsonObject();
        details.addProperty("pipeline", a.pipeline());
        details.addProperty("route", a.route());
        details.addProperty("percent", a.percent());
        details.addProperty("bufferedMegabytes", Math.round(a.bytes() / 1_048_576.0 * 10) / 10.0);
        details.addProperty("maxMegabytes", a.maxBytes() / 1_048_576);
        String size = String.format(Locale.ROOT, "%.1f of %d MB", a.bytes() / 1_048_576.0, a.maxBytes() / 1_048_576);
        if (a.high()) {
            deliver(org, plan, "bimba.buffer.high", "disk buffer of " + a.pipeline() + " is " + a.percent() + "% full",
                    "The disk buffer of route \"" + a.route() + "\" of pipeline " + a.pipeline() + " is " + a.percent() + "% full (" + size
                            + "): its destinations are not taking the data as fast as it comes. Nothing is lost yet; at 100% the oldest"
                            + " batches are discarded. Check the destinations, or give the pipeline a larger buffer.", details);
        } else {
            deliver(org, plan, "bimba.buffer.normal", "disk buffer of " + a.pipeline() + " is back to " + a.percent() + "%",
                    "The disk buffer of route \"" + a.route() + "\" of pipeline " + a.pipeline() + " is back to " + a.percent() + "% (" + size + ").",
                    details);
        }
    }

    /**
     * A test alert to the saved recipients of one of the organization's pipelines: {email, webhook, organizationEmail,
     * organizationWebhook} with what happened to each (empty when nobody would be told).
     */
    public Map<String, Object> test(long orgId, long pipelineId) throws ApiException {
        Plan plan = plan(orgId, pipelineId);
        if (plan.pipeline().isEmpty()) {
            throw new ApiException(404, "not_found", "This pipeline no longer exists. Reload the page.");
        }
        JsonObject details = new JsonObject();
        details.addProperty("pipeline", plan.pipeline());
        details.addProperty("test", true);
        return deliver(orgId, plan, "bimba.buffer.test", "test buffer alert for " + plan.pipeline(),
                "This is a test of the disk buffer alerts of pipeline " + plan.pipeline() + ". Nothing is wrong with its buffers.", details);
    }

    /** the pipeline's recipients; the database away or an unknown pipeline: only the organization's */
    Plan plan(long orgId, long pipelineId) {
        JsonObject p;
        try {
            p = store.pipelines(orgId).stream().filter(x -> x.get("id").getAsLong() == pipelineId).findFirst().orElse(null);
        } catch (RuntimeException e) {
            log.warn("buffer alert recipients of pipeline {} not readable ({}): telling the organization's", pipelineId, e.getMessage());
            p = null;
        }
        if (p == null) {
            return new Plan("", "", Recipients.NONE, true);
        }
        JsonObject ba = p.has("bufferAlerts") && p.get("bufferAlerts").isJsonObject() ? p.getAsJsonObject("bufferAlerts") : new JsonObject();
        List<String> emails = new ArrayList<>();
        if (ba.has("emails") && ba.get("emails").isJsonArray()) {
            ba.getAsJsonArray("emails").forEach(e -> emails.add(e.getAsString()));
        }
        boolean organization = !ba.has("organization") || ba.get("organization").getAsBoolean();
        return new Plan(Json.str(p, "name"), Json.str(p, "uid"),
                new Recipients(List.copyOf(emails), Json.str(ba, "webhookUrl"), Json.str(ba, "webhookSecret")), organization);
    }

    private Map<String, Object> deliver(long org, Plan plan, String event, String subject, String text, JsonObject details) {
        String page = plan.uid().isEmpty() ? "pipelines" : "pipelines/" + plan.uid();
        Map<String, Object> out = new LinkedHashMap<>(alerts.notify(org, plan.own(), event, subject, text, details, page));
        if (plan.organization()) {
            Recipients theirs = alerts.settings(org).recipients().without(plan.own().emails());
            if (theirs.webhookUrl().equals(plan.own().webhookUrl())) {
                theirs = new Recipients(theirs.emails(), "", "");             // the same webhook is called once
            }
            alerts.notify(org, theirs, event, subject, text, details, page)
                    .forEach((k, v) -> out.put("organization" + Character.toUpperCase(k.charAt(0)) + k.substring(1), v));
        }
        return out;
    }
}
