package com.bimba.setu.collect;

import com.bimba.setu.config.TagModel;
import com.google.gson.JsonObject;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.client.security.ClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ViewDescription;
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil;

import java.net.URI;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/**
 * OPC UA client (Eclipse Milo). Reads all of the device's nodes in one Read request per cycle.
 * <p>
 * Connection settings: endpoint (opc.tcp://), policy (None, Basic256Sha256, Aes128_Sha256_RsaOaep,
 * Aes256_Sha256_RsaPss, or Auto = the strongest the server offers), mode (Sign | SignAndEncrypt; Auto picks
 * SignAndEncrypt), auth (Anonymous | Username) + username/password, serverCert (SHA-256 of the server's
 * certificate: once set, any other certificate is refused), timeoutMs.
 * <p>
 * Signed/encrypted policies use this application's own certificate ({@link OpcUaPki}); the server must trust it once
 * (in its list of rejected / quarantined client certificates).
 */
public final class OpcUaDriver implements Driver {
    /** strongest first; "Auto" takes the first one the server offers */
    private static final List<SecurityPolicy> PREFERRED = List.of(SecurityPolicy.Aes256_Sha256_RsaPss,
            SecurityPolicy.Basic256Sha256, SecurityPolicy.Aes128_Sha256_RsaOaep, SecurityPolicy.None);

    private final String endpoint;
    private final String policy;
    private final String mode;
    private final String auth;
    private final String username;
    private final String password;
    private final String pinnedServerCert;
    private final int timeoutMs;
    private final Map<String, NodeId> nodeIds = new HashMap<>();
    private OpcUaClient client;
    private volatile String serverCertSha256;
    private volatile String chosenSecurity;

    public OpcUaDriver(JsonObject conn) {
        endpoint = str(conn, "endpoint", "");
        policy = str(conn, "policy", "None");
        mode = str(conn, "mode", "Auto");
        auth = str(conn, "auth", "Anonymous");
        username = str(conn, "username", "");
        password = str(conn, "password", "");
        pinnedServerCert = str(conn, "serverCert", "").replace(":", "").toLowerCase(Locale.ROOT);
        timeoutMs = (int) Math.max(500, conn != null && conn.has("timeoutMs") ? conn.get("timeoutMs").getAsDouble() : 4000);
    }

    @Override
    public String describe() {
        return endpoint + (chosenSecurity == null ? "" : " (" + chosenSecurity + ")");
    }

    /** SHA-256 (hex) of the certificate the server presented on the last connection, or null */
    public String serverCertSha256() {
        return serverCertSha256;
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
            values = client.readValues(0.0, TimestampsToReturn.Source, ids).get(timeoutMs, TimeUnit.MILLISECONDS);
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
            StatusCode sc = dv.getStatusCode();
            if (sc != null && sc.isBad()) {
                out.add(Reading.bad(explain(sc)));
                continue;
            }
            Object v = dv.getValue().getValue();
            Object value = v instanceof Number n ? (Object) n.doubleValue() : plain(v);
            Instant at = sourceTime(dv);
            // Uncertain_*: the server has a value but doubts it (e.g. last usable value, sensor not accurate)
            out.add(sc != null && sc.isUncertain() ? Reading.uncertain(value, explain(sc), at) : Reading.ok(value, at));
        }
        return out;
    }

    /**
     * A status code as people read it: "Uncertain_SensorNotAccurate: The value is uncertain because the sensor is not
     * accurate. (OPC UA 0x40930000)"; the name and text come from the OPC UA specification.
     */
    public static String explain(StatusCode sc) {
        long code = sc.getValue() & 0xFFFF0000L;             // without the info bits (overflow, limits)
        String hex = String.format("0x%08X", sc.getValue());
        return StatusCodes.lookup(code)
                .map(a -> a[0] + (a.length > 1 && a[1] != null && !a[1].isBlank() ? ": " + a[1].trim() : "") + " (OPC UA " + hex + ")")
                .orElse("OPC UA status " + hex + (sc.isUncertain() ? " (uncertain)" : sc.isBad() ? " (bad)" : ""));
    }

    /** the value's source timestamp, null when the server sends none */
    private static Instant sourceTime(DataValue dv) {
        DateTime t = dv.getSourceTime();
        return t == null || t.getUtcTime() <= 0 ? null : t.getJavaInstant();
    }

    /** Boolean, String, Number or null stay; everything else becomes text */
    private static Object plain(Object v) {
        if (v == null || v instanceof Boolean || v instanceof String || v instanceof Number) {
            return v;
        }
        if (v instanceof LocalizedText lt) {
            return lt.getText();
        }
        if (v instanceof DateTime dt) {
            return dt.getJavaInstant().toString();
        }
        if (v instanceof Object[] arr) {
            return Arrays.toString(arr);
        }
        return v.toString();
    }

    private void connect() throws Exception {
        client = open();
    }

    private OpcUaClient open() throws Exception {
        if (!endpoint.startsWith("opc.tcp://")) {
            throw new Exception("endpoint must start with opc.tcp://");
        }
        String host = URI.create(endpoint).getHost();
        List<EndpointDescription> endpoints;
        try {
            endpoints = DiscoveryClient.getEndpoints(endpoint).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new Exception("cannot reach " + endpoint + ": " + rootMessage(e), e);
        }
        EndpointDescription chosen = choose(endpoints);
        SecurityPolicy sp = SecurityPolicy.fromUri(chosen.getSecurityPolicyUri());
        chosenSecurity = sp == SecurityPolicy.None ? "no security" : sp.name() + " " + chosen.getSecurityMode();
        // servers often advertise an internal host name: keep the host the user configured
        EndpointDescription target = EndpointUtil.updateUrl(chosen, host);
        IdentityProvider identity = "Username".equals(auth) ? new UsernameProvider(username, password) : new AnonymousProvider();
        OpcUaPki.Identity id = OpcUaPki.identity();
        OpcUaClientConfigBuilder b = OpcUaClientConfig.builder()
                .setApplicationName(LocalizedText.english(id.applicationName()))
                .setApplicationUri(id.applicationUri())
                .setEndpoint(target)
                .setIdentityProvider(identity)
                .setRequestTimeout(uint(timeoutMs))
                .setCertificateValidator(new PinValidator());
        if (sp != SecurityPolicy.None) {
            if (id.certificate() == null) {
                throw new Exception("security policy " + sp.name() + " needs a client certificate, and none is configured");
            }
            b.setKeyPair(id.keyPair()).setCertificate(id.certificate()).setCertificateChain(new X509Certificate[]{id.certificate()});
        }
        OpcUaClient c = OpcUaClient.create(b.build());
        try {
            c.connect().get(timeoutMs * 2L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
                // not connected
            }
            throw new Exception("cannot open a session on " + endpoint + ": " + explain(e), e);
        }
        return c;
    }

    /** the endpoint matching policy + mode (Auto = strongest offered) */
    private EndpointDescription choose(List<EndpointDescription> endpoints) throws Exception {
        List<SecurityPolicy> wanted;
        if ("Auto".equalsIgnoreCase(policy) || policy.isEmpty()) {
            wanted = OpcUaPki.identity().certificate() == null ? List.of(SecurityPolicy.None) : PREFERRED;
        } else {
            SecurityPolicy p = Arrays.stream(SecurityPolicy.values()).filter(x -> x.name().equalsIgnoreCase(policy)).findFirst()
                    .orElseThrow(() -> new Exception("unknown security policy " + policy));
            wanted = List.of(p);
        }
        for (SecurityPolicy p : wanted) {
            List<EndpointDescription> same = endpoints.stream().filter(e -> p.getUri().equals(e.getSecurityPolicyUri())).toList();
            if (same.isEmpty()) {
                continue;
            }
            if (p == SecurityPolicy.None) {
                return same.get(0);
            }
            for (MessageSecurityMode m : modes()) {
                for (EndpointDescription e : same) {
                    if (e.getSecurityMode() == m) {
                        return e;
                    }
                }
            }
        }
        List<String> offered = endpoints.stream()
                .map(e -> SecurityPolicy.fromUriSafe(e.getSecurityPolicyUri()).map(Enum::name).orElse(e.getSecurityPolicyUri())
                        + " " + e.getSecurityMode()).distinct().toList();
        throw new Exception(endpoint + " offers no endpoint for security " + policy + " / " + mode + " (it offers: "
                + String.join(", ", offered) + ")");
    }

    private List<MessageSecurityMode> modes() {
        if ("Sign".equalsIgnoreCase(mode)) {
            return List.of(MessageSecurityMode.Sign);
        }
        if ("SignAndEncrypt".equalsIgnoreCase(mode)) {
            return List.of(MessageSecurityMode.SignAndEncrypt);
        }
        return List.of(MessageSecurityMode.SignAndEncrypt, MessageSecurityMode.Sign);
    }

    /** remembers the server certificate; refuses a different one once a fingerprint is pinned */
    private final class PinValidator implements ClientCertificateValidator {
        @Override
        public void validateCertificateChain(List<X509Certificate> chain) throws UaException {
            check(chain);
        }

        @Override
        public void validateCertificateChain(List<X509Certificate> chain, String applicationUri, String... validHostNames)
                throws UaException {
            check(chain);
        }

        private void check(List<X509Certificate> chain) throws UaException {
            if (chain == null || chain.isEmpty()) {
                return;
            }
            String sha = sha256(chain.get(0));
            serverCertSha256 = sha;
            if (!pinnedServerCert.isEmpty() && !pinnedServerCert.equals(sha)) {
                throw new UaException(StatusCodes.Bad_SecurityChecksFailed,
                        "the server's certificate changed (it is no longer the trusted one, SHA-256 " + sha + ")");
            }
        }
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

    // ------------------------------------------------------------------ console helpers: test + browse

    /** Opens a session and reads the server's product name: {ok, product, version, security, serverCertSha256}. */
    public static Map<String, Object> test(JsonObject conn) throws Exception {
        OpcUaDriver d = new OpcUaDriver(conn);
        OpcUaClient c = d.open();
        try {
            List<DataValue> v = c.readValues(0.0, TimestampsToReturn.Neither, List.of(
                    Identifiers.Server_ServerStatus_BuildInfo_ProductName,
                    Identifiers.Server_ServerStatus_BuildInfo_SoftwareVersion,
                    Identifiers.Server_ServerStatus_BuildInfo_ManufacturerName)).get(d.timeoutMs, TimeUnit.MILLISECONDS);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("product", plain(v.get(0).getValue().getValue()));
            m.put("version", plain(v.get(1).getValue().getValue()));
            m.put("manufacturer", plain(v.get(2).getValue().getValue()));
            m.put("security", d.chosenSecurity);
            m.put("serverCertSha256", d.serverCertSha256);
            return m;
        } finally {
            c.disconnect().get(2, TimeUnit.SECONDS);
        }
    }

    /**
     * Children of a node (null = the Objects folder): [{nodeId, name, nodeClass (Object|Variable), value, dataType}],
     * at most {@code limit}. Variables carry their current value so the console can show what a tag reads.
     */
    public static Map<String, Object> browse(JsonObject conn, String nodeId, int limit) throws Exception {
        OpcUaDriver d = new OpcUaDriver(conn);
        OpcUaClient c = d.open();
        try {
            NodeId start = nodeId == null || nodeId.isBlank() ? Identifiers.ObjectsFolder : NodeId.parse(nodeId);
            Children ch = d.children(c, start, limit);
            List<Map<String, Object>> items = new ArrayList<>();
            List<NodeId> variables = new ArrayList<>();
            List<Map<String, Object>> variableItems = new ArrayList<>();
            for (Child child : ch.items()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeId", child.id().toParseableString());
                m.put("name", child.name());
                m.put("nodeClass", child.variable() ? "Variable" : "Object");
                items.add(m);
                if (child.variable()) {
                    variables.add(child.id());
                    variableItems.add(m);
                }
            }
            if (!variables.isEmpty()) {
                List<DataValue> values = c.readValues(0.0, TimestampsToReturn.Neither, variables).get(d.timeoutMs, TimeUnit.MILLISECONDS);
                for (int i = 0; i < values.size(); i++) {
                    DataValue dv = values.get(i);
                    Object v = dv.getValue() == null ? null : dv.getValue().getValue();
                    variableItems.get(i).put("value", plain(v));
                    variableItems.get(i).put("dataType", v == null ? null : v instanceof Boolean ? "boolean"
                            : v instanceof Number ? "number" : "string");
                    variableItems.get(i).put("good", dv.getStatusCode() == null || dv.getStatusCode().isGood());
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("node", start.toParseableString());
            out.put("items", items);
            out.put("truncated", ch.truncated());
            out.put("security", d.chosenSecurity);
            out.put("serverCertSha256", d.serverCertSha256);
            return out;
        } finally {
            c.disconnect().get(2, TimeUnit.SECONDS);
        }
    }

    /**
     * The server's devices as machines: every folder directly under {@code rootNodeId} (null = the server's "Devices"
     * folder when it has one, as Ignition does, otherwise Objects) is one device, with every variable below it as a tag
     * named after its path inside the device. Folders in brackets inside a device ([Diagnostics], [Controls]) are the
     * server's own and skipped; Ignition's device folders themselves are "[name]" and lose the brackets.
     * {root, machines:[{nodeId, name, tags:[{name, address}], truncated}], truncated}, at most {@code maxTags} tags.
     */
    public static Map<String, Object> scan(JsonObject conn, String rootNodeId, int maxTags) throws Exception {
        OpcUaDriver d = new OpcUaDriver(conn);
        OpcUaClient c = d.open();
        try {
            NodeId root = Identifiers.ObjectsFolder;
            if (rootNodeId != null && !rootNodeId.isBlank()) {
                root = NodeId.parse(rootNodeId);
            } else {
                for (Child ch : d.children(c, Identifiers.ObjectsFolder, 1000).items()) {
                    if (!ch.variable() && "Devices".equalsIgnoreCase(ch.name())) {
                        root = ch.id();
                        break;
                    }
                }
            }
            int[] budget = {Math.max(1, maxTags)};
            boolean truncated = false;
            List<Map<String, Object>> machines = new ArrayList<>();
            for (Child dev : d.children(c, root, 1000).items()) {
                if (dev.variable() || dev.id().getNamespaceIndex().intValue() == 0) {
                    continue;                                   // the standard Server object and other namespace-0 nodes
                }
                if (budget[0] <= 0) {
                    truncated = true;
                    break;
                }
                List<Map<String, Object>> tags = new ArrayList<>();
                boolean full = d.collect(c, dev.id(), new ArrayList<>(), tags, new java.util.HashSet<>(), budget, 0);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeId", dev.id().toParseableString());
                m.put("name", dev.name().replaceAll("^\\[(.*)]$", "$1"));
                m.put("tags", tags);
                m.put("truncated", !full);
                truncated |= !full;
                machines.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("root", root.toParseableString());
            out.put("machines", machines);
            out.put("truncated", truncated);
            out.put("security", d.chosenSecurity);
            out.put("serverCertSha256", d.serverCertSha256);
            return out;
        } finally {
            c.disconnect().get(2, TimeUnit.SECONDS);
        }
    }

    /** adds the variables below {@code node} to {@code tags}; false when the tag budget ran out */
    private boolean collect(OpcUaClient c, NodeId node, List<String> path, List<Map<String, Object>> tags, java.util.Set<String> names,
                            int[] budget, int depth) throws Exception {
        if (depth > 12) {
            return true;
        }
        for (Child ch : children(c, node, 5000).items()) {
            if (ch.variable()) {
                if (budget[0] <= 0) {
                    return false;
                }
                List<String> parts = new ArrayList<>(path);
                parts.add(ch.name());
                String base = String.join("_", parts);
                String name = base;
                for (int i = 2; !names.add(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")); i++) {
                    name = base + "_" + i;
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", name);
                t.put("address", ch.id().toParseableString());
                tags.add(t);
                budget[0]--;
            } else if (!ch.name().matches("^\\[.*]$")) {
                List<String> sub = new ArrayList<>(path);
                sub.add(ch.name());
                if (!collect(c, ch.id(), sub, tags, names, budget, depth + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    private record Child(NodeId id, String name, boolean variable) {
    }

    private record Children(List<Child> items, boolean truncated) {
    }

    /** objects and variables directly below a node, at most {@code limit} */
    private Children children(OpcUaClient c, NodeId node, int limit) throws Exception {
        BrowseDescription bd = new BrowseDescription(node, BrowseDirection.Forward, Identifiers.HierarchicalReferences, true,
                uint(NodeClass.Object.getValue() | NodeClass.Variable.getValue()), uint(BrowseResultMask.All.getValue()));
        List<ReferenceDescription> refs = new ArrayList<>();
        BrowseResult r = c.browse(new ViewDescription(NodeId.NULL_VALUE, DateTime.MIN_VALUE, uint(0)), uint(Math.max(1, limit)),
                List.of(bd)).get(timeoutMs, TimeUnit.MILLISECONDS).getResults()[0];
        if (!r.getStatusCode().isGood()) {
            throw new Exception("browse failed: " + r.getStatusCode());
        }
        if (r.getReferences() != null) {
            refs.addAll(Arrays.asList(r.getReferences()));
        }
        ByteString cp = r.getContinuationPoint();
        while (cp != null && cp.isNotNull() && refs.size() < limit) {
            BrowseResult next = c.browseNext(false, List.of(cp)).get(timeoutMs, TimeUnit.MILLISECONDS).getResults()[0];
            if (next.getReferences() != null) {
                refs.addAll(Arrays.asList(next.getReferences()));
            }
            cp = next.getContinuationPoint();
        }
        boolean more = cp != null && cp.isNotNull();
        if (more) {
            c.browseNext(true, List.of(cp));              // release the server's continuation point
        }
        List<Child> items = new ArrayList<>();
        for (ReferenceDescription ref : refs) {
            NodeId id = ref.getNodeId().toNodeId(c.getNamespaceTable()).orElse(null);
            if (id == null || items.size() >= limit) {
                continue;
            }
            String name = ref.getDisplayName() != null && ref.getDisplayName().getText() != null
                    ? ref.getDisplayName().getText() : ref.getBrowseName().getName();
            items.add(new Child(id, name == null ? id.toParseableString() : name, ref.getNodeClass() == NodeClass.Variable));
        }
        return new Children(items, more || refs.size() > items.size());
    }

    // ------------------------------------------------------------------ helpers

    /** a readable reason, with the one fix users need most: trusting our certificate on the server */
    private static String explain(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof UaException ua) {
                long code = ua.getStatusCode().getValue();
                if (code == StatusCodes.Bad_SecurityChecksFailed || code == StatusCodes.Bad_CertificateUntrusted) {
                    return "the server does not trust this client's certificate yet (" + OpcUaPki.identity().applicationName()
                            + "). Trust it once in the server's OPC UA security settings (its list of rejected or quarantined"
                            + " client certificates), then try again.";
                }
                if (code == StatusCodes.Bad_UserAccessDenied || code == StatusCodes.Bad_IdentityTokenRejected
                        || code == StatusCodes.Bad_IdentityTokenInvalid) {
                    return "the server refused the sign-in (" + ua.getStatusCode() + "): check user name and password,"
                            + " or whether anonymous access is allowed";
                }
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return rootMessage(e);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    static String sha256(X509Certificate c) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k, String d) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : d;
    }
}
