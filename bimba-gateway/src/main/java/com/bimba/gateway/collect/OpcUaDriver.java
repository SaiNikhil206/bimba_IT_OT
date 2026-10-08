package com.bimba.gateway.collect;

import com.bimba.gateway.config.TagModel;
import com.google.gson.JsonObject;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/**
 * OPC UA client (Eclipse Milo). Reads all of the device's nodes in one Read request per cycle.
 * Security: policy None with Anonymous or Username sign-in. Signed/encrypted policies need a client
 * certificate trust exchange with the server, which is not implemented yet: they are reported as an error.
 */
final class OpcUaDriver implements Driver {
    private final String endpoint;
    private final String policy;
    private final String auth;
    private final String username;
    private final String password;
    private final int timeoutMs;
    private final Map<String, NodeId> nodeIds = new HashMap<>();
    private OpcUaClient client;

    OpcUaDriver(JsonObject conn) {
        endpoint = str(conn, "endpoint", "");
        policy = str(conn, "policy", "None");
        auth = str(conn, "auth", "Anonymous");
        username = str(conn, "username", "");
        password = str(conn, "password", "");
        timeoutMs = (int) Math.max(500, conn != null && conn.has("timeoutMs") ? conn.get("timeoutMs").getAsDouble() : 4000);
    }

    @Override
    public String describe() {
        return endpoint;
    }

    @Override
    public List<Reading> read(List<TagModel.Tag> tags) throws Exception {
        if (client == null) {
            connect();
        }
        List<NodeId> ids = new ArrayList<>(tags.size());
        List<Reading> parseErrors = new ArrayList<>(tags.size());
        for (TagModel.Tag t : tags) {
            try {
                ids.add(nodeIds.computeIfAbsent(t.address(), NodeId::parse));
                parseErrors.add(null);
            } catch (RuntimeException e) {
                ids.add(NodeId.NULL_VALUE);
                parseErrors.add(Reading.bad("invalid NodeId " + t.address()));
            }
        }
        List<DataValue> values;
        try {
            values = client.readValues(0.0, TimestampsToReturn.Neither, ids).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            close();
            throw new Exception("read from " + endpoint + " failed: " + rootMessage(e), e);
        }
        List<Reading> out = new ArrayList<>(tags.size());
        for (int i = 0; i < tags.size(); i++) {
            if (parseErrors.get(i) != null) {
                out.add(parseErrors.get(i));
                continue;
            }
            DataValue dv = values.get(i);
            if (dv.getStatusCode() != null && !dv.getStatusCode().isGood()) {
                out.add(Reading.bad("OPC UA status " + dv.getStatusCode()));
                continue;
            }
            Object v = dv.getValue().getValue();
            if (v instanceof Boolean || v instanceof String) {
                out.add(Reading.ok(v));
            } else if (v instanceof Number n) {
                out.add(Reading.ok(tags.get(i).scale(n.doubleValue())));
            } else if (v instanceof LocalizedText lt) {
                out.add(Reading.ok(lt.getText()));
            } else {
                out.add(Reading.ok(v == null ? null : v.toString()));
            }
        }
        return out;
    }

    private void connect() throws Exception {
        if (!endpoint.startsWith("opc.tcp://")) {
            throw new Exception("endpoint must start with opc.tcp://");
        }
        if (!"None".equals(policy)) {
            throw new Exception("security policy " + policy + " is not supported by the BIMBA Gateway yet (use None)");
        }
        String host = URI.create(endpoint).getHost();
        List<EndpointDescription> endpoints;
        try {
            endpoints = DiscoveryClient.getEndpoints(endpoint).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new Exception("cannot reach " + endpoint + ": " + rootMessage(e), e);
        }
        EndpointDescription chosen = endpoints.stream()
                .filter(e -> SecurityPolicy.None.getUri().equals(e.getSecurityPolicyUri())
                        && e.getSecurityMode() == MessageSecurityMode.None)
                .findFirst()
                .orElseThrow(() -> new Exception(endpoint + " offers no endpoint with security policy None"));
        // servers often advertise an internal host name: keep the host the user configured
        EndpointDescription target = EndpointUtil.updateUrl(chosen, host);
        IdentityProvider identity = "Username".equals(auth) ? new UsernameProvider(username, password) : new AnonymousProvider();
        OpcUaClient c = OpcUaClient.create(OpcUaClientConfig.builder()
                .setApplicationName(LocalizedText.english("BIMBA Gateway"))
                .setApplicationUri("urn:bimba:gateway")
                .setEndpoint(target)
                .setIdentityProvider(identity)
                .setRequestTimeout(uint(timeoutMs))
                .build());
        try {
            c.connect().get(timeoutMs * 2L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
                // not connected
            }
            throw new Exception("cannot open a session on " + endpoint + ": " + rootMessage(e), e);
        }
        client = c;
    }

    @Override
    public void close() {
        if (client != null) {
            try {
                client.disconnect().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // reconnect on next cycle
            }
            client = null;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String str(JsonObject o, String k, String d) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : d;
    }
}
