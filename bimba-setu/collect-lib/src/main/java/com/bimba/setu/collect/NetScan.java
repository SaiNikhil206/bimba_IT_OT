package com.bimba.setu.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Scan the network" in the console: finds data sources the agent's computer can reach. Every address of the computer's
 * own networks (each at most the /24 around its address, plus localhost) and of the networks the request names gets one
 * TCP connection attempt per port of the wanted types; each open port is then asked who it is, with requests that only
 * read: Modbus Read Device Identification, OPC UA GetEndpoints, S7 connection setup, EtherNet/IP ListIdentity, DNP3
 * link status (self address), IEC 104 test frame, MQTT CONNECT / DISCONNECT, MySQL greeting, PostgreSQL SSLRequest,
 * SQL Server PRELOGIN. Nothing is written, no session stays open. The answer pre-fills the data source form.
 * <p>
 * Request {protocols: [kinds], ports: {kind: [ports]}, subnets: ["10.0.0.0/24", "10.0.0.5", "plc7", "plc7:5020"], ownNetworks,
 * timeoutMs}. Answer {found: [{kind, host, port, confirmed, product, detail, settings}], networks: [...], addresses,
 * truncated, ms}. At most {@value #MAX_HOSTS} addresses; finishes within about 30 seconds.
 */
public final class NetScan {
    public static final int MAX_HOSTS = 1024;
    private static final long CONNECT_BUDGET_MS = 20_000;
    /** connection attempts at the same time (well below the 1024 open files a service may get) */
    static final int WINDOW = 256;
    private static final long TOTAL_BUDGET_MS = 32_000;
    private static final Pattern HOST_PORT = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9.-]*):(\\d{1,5})$");
    private static final Pattern CIDR = Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){3})(?:/(\\d{1,2}))?$");

    /** the standard ports, when the request does not name them (Setu sends them from source-kinds.json) */
    static final Map<String, int[]> DEFAULT_PORTS = new LinkedHashMap<>();

    static {
        DEFAULT_PORTS.put("opcua", new int[]{4840, 4841, 48010, 49320, 53530, 62541});
        DEFAULT_PORTS.put("modbus", new int[]{502});
        DEFAULT_PORTS.put("mqtt", new int[]{1883, 8883});
        DEFAULT_PORTS.put("s7", new int[]{102});
        DEFAULT_PORTS.put("eip", new int[]{44818});
        DEFAULT_PORTS.put("dnp3", new int[]{20000});
        DEFAULT_PORTS.put("iec104", new int[]{2404});
        DEFAULT_PORTS.put("sql", new int[]{1433, 5432, 3306});
    }

    private NetScan() {
    }

    /** one data source that answered (or an open port of its type that did not say who it is: confirmed false) */
    record Found(String kind, String host, int port, boolean confirmed, String product, String detail, Map<String, Object> settings) {
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("host", host);
            m.put("port", port);
            m.put("confirmed", confirmed);
            m.put("product", product);
            m.put("detail", detail);
            m.put("settings", settings);
            return m;
        }
    }

    public static Map<String, Object> run(JsonObject msg) throws Exception {
        long started = System.currentTimeMillis();
        int timeout = msg.has("timeoutMs") ? Math.max(100, Math.min(3000, msg.get("timeoutMs").getAsInt())) : 500;
        // type -> ports, in the order asked
        Map<String, int[]> ports = new LinkedHashMap<>();
        JsonArray asked = msg.has("protocols") && msg.get("protocols").isJsonArray() ? msg.getAsJsonArray("protocols") : null;
        JsonObject given = msg.has("ports") && msg.get("ports").isJsonObject() ? msg.getAsJsonObject("ports") : new JsonObject();
        for (String kind : DEFAULT_PORTS.keySet()) {
            if (asked != null && !contains(asked, kind)) {
                continue;
            }
            int[] p = DEFAULT_PORTS.get(kind);
            if (given.has(kind) && given.get(kind).isJsonArray()) {
                p = given.getAsJsonArray(kind).asList().stream().mapToInt(JsonElement::getAsInt).filter(x -> x > 0 && x < 65536).toArray();
            }
            if (p.length > 0) {
                ports.put(kind, p);
            }
        }
        if (ports.isEmpty()) {
            throw new IllegalArgumentException("no type of data source to look for");
        }

        // addresses: localhost and the computer's own networks, then the ones asked for
        List<Map<String, Object>> networks = new ArrayList<>();
        Set<String> own = new LinkedHashSet<>();
        Set<String> hosts = new LinkedHashSet<>();
        boolean truncated = false;
        if (!msg.has("ownNetworks") || msg.get("ownNetworks").getAsBoolean()) {
            hosts.add("127.0.0.1");
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address a) || a.isLinkLocalAddress()) {
                        continue;
                    }
                    own.add(a.getHostAddress());
                    int bits = Math.max(24, ia.getNetworkPrefixLength());
                    String net = network(a.getHostAddress(), bits);
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("network", net);
                    n.put("via", ni.getDisplayName() + " " + a.getHostAddress());
                    networks.add(n);
                    truncated |= addRange(hosts, net);
                }
            }
        }
        Set<String> unresolved = ConcurrentHashMap.newKeySet();
        List<String[]> explicit = new ArrayList<>();                     // {host, port}
        if (msg.has("subnets") && msg.get("subnets").isJsonArray()) {
            for (JsonElement e : msg.getAsJsonArray("subnets")) {
                String t = e.getAsString().trim();
                if (t.isEmpty()) {
                    continue;
                }
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("network", t);
                n.put("via", "asked");
                networks.add(n);
                Matcher hp = HOST_PORT.matcher(t);
                if (hp.matches() && hosts.size() + explicit.size() < MAX_HOSTS) {
                    explicit.add(new String[]{hp.group(1), hp.group(2)});   // asked on this port, as every chosen type
                } else if (CIDR.matcher(t).matches()) {
                    truncated |= addRange(hosts, t);
                } else if (hosts.size() < MAX_HOSTS) {
                    hosts.add(t);                                       // a host name
                } else {
                    truncated = true;
                }
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(32, r -> {
            Thread t = new Thread(r, "netscan");
            t.setDaemon(true);
            return t;
        });
        try {
            // 1. which ports are open
            List<String[]> tries = new ArrayList<>();
            for (String h : hosts) {
                for (Map.Entry<String, int[]> e : ports.entrySet()) {
                    for (int p : e.getValue()) {
                        tries.add(new String[]{e.getKey(), h, String.valueOf(p)});
                    }
                }
            }
            for (String[] x : explicit) {
                for (String kind : ports.keySet()) {
                    tries.add(new String[]{kind, x[0], x[1]});
                }
            }
            boolean[] finished = {false};
            Map<String, String[]> open = new LinkedHashMap<>();             // kind|host|port, once
            for (String[] o : openPorts(tries, timeout, started + CONNECT_BUDGET_MS, unresolved, finished)) {
                open.putIfAbsent(String.join("|", o), o);
            }

            // 2. who answers on each open port (localhost wins over the computer's own address)
            Set<String> local = new TreeSet<>();
            open.values().stream().filter(o -> o[1].equals("127.0.0.1")).forEach(o -> local.add(o[0] + ":" + o[2]));
            ConcurrentLinkedQueue<Found> found = new ConcurrentLinkedQueue<>();
            List<Callable<Void>> asks = new ArrayList<>();
            int probe = Math.max(1500, Math.min(3000, timeout * 3));
            for (String[] o : open.values()) {
                if (own.contains(o[1]) && local.contains(o[0] + ":" + o[2])) {
                    continue;
                }
                asks.add(() -> {
                    String host = o[1].equals("127.0.0.1") ? "localhost" : o[1];
                    try {
                        found.add(identify(o[0], o[1], host, Integer.parseInt(o[2]), probe));
                    } catch (Exception ignored) {
                        found.add(unknown(o[0], host, Integer.parseInt(o[2])));
                    }
                    return null;
                });
            }
            long left = TOTAL_BUDGET_MS - (System.currentTimeMillis() - started);
            pool.invokeAll(asks, Math.max(1000, left), TimeUnit.MILLISECONDS);

            List<String> order = new ArrayList<>(ports.keySet());
            List<Found> list = new ArrayList<>(found);
            // an address asked with its port: only the types that answered there, or one line when none did
            for (String[] x : explicit) {
                String host = x[0].equals("127.0.0.1") ? "localhost" : x[0];
                int port = Integer.parseInt(x[1]);
                List<Found> here = list.stream().filter(f -> f.host().equals(host) && f.port() == port)
                        .sorted(java.util.Comparator.comparingInt(f -> order.indexOf(f.kind()))).toList();
                boolean any = here.stream().anyMatch(Found::confirmed);
                for (int i = 0; i < here.size(); i++) {
                    if (any ? !here.get(i).confirmed() : i > 0) {
                        list.remove(here.get(i));
                    }
                }
            }
            list.sort((a, b) -> {
                int c = Integer.compare(order.indexOf(a.kind()), order.indexOf(b.kind()));
                if (c == 0) {
                    c = Long.compare(sortKey(a.host()), sortKey(b.host()));
                }
                if (c == 0) {
                    c = a.host().compareTo(b.host());
                }
                return c != 0 ? c : Integer.compare(a.port(), b.port());
            });
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("found", list.stream().map(Found::json).toList());
            m.put("networks", networks);
            m.put("addresses", hosts.size());
            m.put("ports", ports.values().stream().mapToInt(p -> p.length).sum());
            m.put("unresolved", new TreeSet<>(unresolved));
            m.put("truncated", truncated);
            m.put("complete", finished[0]);
            m.put("ms", System.currentTimeMillis() - started);
            return m;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Which of host:port (each {kind, host, port}) accept a TCP connection: non-blocking connects, at most {@value #WINDOW}
     * at a time, each given {@code timeout} ms, all of it until {@code deadline}. finished[0] = every address was tried.
     */
    static List<String[]> openPorts(List<String[]> tries, int timeout, long deadline, Set<String> unresolved, boolean[] finished)
            throws IOException {
        List<String[]> open = new ArrayList<>();
        Map<SelectionKey, Long> pending = new HashMap<>();
        int next = 0;
        try (Selector sel = Selector.open()) {
            while ((next < tries.size() || !pending.isEmpty()) && System.currentTimeMillis() < deadline) {
                while (next < tries.size() && pending.size() < WINDOW) {
                    String[] t = tries.get(next++);
                    InetSocketAddress at = new InetSocketAddress(t[1], Integer.parseInt(t[2]));
                    if (at.isUnresolved()) {
                        unresolved.add(t[1]);
                        continue;
                    }
                    SocketChannel ch = SocketChannel.open();
                    try {
                        ch.configureBlocking(false);
                        if (ch.connect(at)) {
                            open.add(t);
                            ch.close();
                        } else {
                            pending.put(ch.register(sel, SelectionKey.OP_CONNECT, t), System.currentTimeMillis());
                        }
                    } catch (IOException unreachable) {
                        ch.close();
                    }
                }
                sel.select(50);
                for (SelectionKey k : sel.selectedKeys()) {
                    try {
                        if (((SocketChannel) k.channel()).finishConnect()) {
                            open.add((String[]) k.attachment());
                        }
                    } catch (IOException refused) {
                        // closed port or no route
                    }
                    pending.remove(k);
                    k.channel().close();
                }
                sel.selectedKeys().clear();
                long now = System.currentTimeMillis();
                for (Iterator<Map.Entry<SelectionKey, Long>> it = pending.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<SelectionKey, Long> e = it.next();
                    if (now - e.getValue() > timeout) {
                        e.getKey().channel().close();                // nothing answered in time
                        it.remove();
                    }
                }
            }
            finished[0] = next >= tries.size() && pending.isEmpty();
            for (SelectionKey k : pending.keySet()) {
                k.channel().close();
            }
        }
        return open;
    }

    // ------------------------------------------------------------------ who answers

    static Found identify(String kind, String address, String host, int port, int timeout) throws Exception {
        return switch (kind) {
            case "modbus" -> modbus(address, host, port, timeout);
            case "opcua" -> opcua(address, host, port, timeout);
            case "s7" -> s7(address, host, port, timeout);
            case "eip" -> eip(address, host, port, timeout);
            case "dnp3" -> dnp3(address, host, port, timeout);
            case "iec104" -> iec104(address, host, port, timeout);
            case "mqtt" -> mqtt(address, host, port, timeout);
            case "sql" -> sql(address, host, port, timeout);
            default -> unknown(kind, host, port);
        };
    }

    /** an open port of the type's that did not answer as expected */
    static Found unknown(String kind, String host, int port) {
        Map<String, Object> s = new LinkedHashMap<>();
        switch (kind) {
            case "opcua" -> s.put("endpoint", "opc.tcp://" + host + ":" + port);
            case "mqtt" -> s.put("url", (port == 8883 ? "mqtts://" : "mqtt://") + host + ":" + port);
            case "sql" -> {
                s.put("engine", port == 5432 ? "postgresql" : port == 3306 ? "mysql" : "sqlserver");
                s.put("host", host);
            }
            default -> {
                s.put("host", host);
                s.put("port", port);
            }
        }
        return new Found(kind, host, port, false, null, "port " + port + " is open; it did not say what it is", s);
    }

    /** Modbus TCP: Read Device Identification (function 43 / 14, basic) to unit 1; any Modbus answer confirms */
    static Found modbus(String address, String host, int port, int timeout) throws IOException {
        try (Socket s = open(address, port, timeout)) {
            s.getOutputStream().write(new byte[]{0, 1, 0, 0, 0, 5, 1, 0x2B, 0x0E, 1, 0});
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] head = new byte[7];
            in.readFully(head);
            int len = (head[4] & 0xFF) << 8 | head[5] & 0xFF;
            if (head[2] != 0 || head[3] != 0 || len < 2 || len > 260) {
                return unknown("modbus", host, port);
            }
            byte[] pdu = new byte[len - 1];
            in.readFully(pdu);
            String product = null;
            if ((pdu[0] & 0xFF) == 0x2B && pdu.length > 7) {
                Map<Integer, String> ids = new LinkedHashMap<>();
                int n = pdu[6] & 0xFF, at = 7;
                for (int i = 0; i < n && at + 2 <= pdu.length; i++) {
                    int id = pdu[at] & 0xFF, l = pdu[at + 1] & 0xFF;
                    if (at + 2 + l > pdu.length) {
                        break;
                    }
                    ids.put(id, new String(pdu, at + 2, l, StandardCharsets.ISO_8859_1).trim());
                    at += 2 + l;
                }
                product = String.join(" ", ids.values().stream().filter(x -> !x.isEmpty()).toList());
            }
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("host", host);
            st.put("port", port);
            st.put("unitId", 1);
            return new Found("modbus", host, port, true, product == null || product.isEmpty() ? null : product,
                    product == null || product.isEmpty() ? "answers Modbus TCP (unit 1)" : "answers Modbus TCP (unit 1, device identification)", st);
        }
    }

    /** OPC UA: GetEndpoints (what any client asks first, without a session): name, path, security, sign-in */
    static Found opcua(String address, String host, int port, int timeout) throws Exception {
        List<EndpointDescription> eps = DiscoveryClient.getEndpoints("opc.tcp://" + address + ":" + port).get(timeout + 1500L, TimeUnit.MILLISECONDS);
        if (eps.isEmpty()) {
            return unknown("opcua", host, port);
        }
        EndpointDescription first = eps.get(0);
        String path = "";
        try {
            String p = URI.create(first.getEndpointUrl()).getPath();
            path = p == null ? "" : p;
        } catch (RuntimeException ignored) {
            // keep the server's root
        }
        Set<String> security = new TreeSet<>();
        boolean anonymous = false;
        for (EndpointDescription e : eps) {
            SecurityPolicy sp = SecurityPolicy.fromUriSafe(e.getSecurityPolicyUri()).orElse(null);
            security.add(sp == null ? "?" : sp == SecurityPolicy.None ? "None" : sp.name());
            for (UserTokenPolicy t : e.getUserIdentityTokens() == null ? new UserTokenPolicy[0] : e.getUserIdentityTokens()) {
                anonymous |= t.getTokenType() == UserTokenType.Anonymous;
            }
        }
        String name = first.getServer() != null && first.getServer().getApplicationName() != null ? first.getServer().getApplicationName().getText() : null;
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("endpoint", "opc.tcp://" + host + ":" + port + path);
        st.put("policy", "Auto");
        st.put("auth", anonymous ? "Anonymous" : "Username");
        return new Found("opcua", host, port, true, name, "security " + String.join(", ", security)
                + "; sign-in " + (anonymous ? "anonymous allowed" : "user name needed"), st);
    }

    /** Siemens S7: an S7 connection (COTP + setup communication) as S7-1200/1500 (slot 1), S7-300 (slot 2), LOGO! */
    static Found s7(String address, String host, int port, int timeout) {
        String[][] tries = {{"S7_1500", "1"}, {"S7_300", "2"}, {"LOGO", "0"}, {"S7_1500", "0"}};
        for (String[] t : tries) {
            JsonObject s = new JsonObject();
            s.addProperty("host", address);
            s.addProperty("port", port);
            s.addProperty("cpu", t[0]);
            s.addProperty("rack", 0);
            s.addProperty("slot", Integer.parseInt(t[1]));
            s.addProperty("timeoutMs", timeout);
            try {
                Map<String, Object> r = S7Driver.test(s, List.of());
                Map<String, Object> st = new LinkedHashMap<>();
                st.put("host", host);
                st.put("cpu", t[0]);
                if (!"LOGO".equals(t[0])) {
                    st.put("rack", 0);
                    st.put("slot", Integer.parseInt(t[1]));
                }
                if (port != 102) {
                    st.put("port", port);
                }
                String what = "LOGO".equals(t[0]) ? "LOGO! 8" : "S7_300".equals(t[0]) ? "S7-300 (rack 0, slot 2)"
                        : "S7-1200 / 1500 or S7-400 (rack 0, slot " + t[1] + "): check the CPU";
                return new Found("s7", host, port, true, "Siemens S7", what + ", PDU " + r.get("pdu") + " bytes", st);
            } catch (Exception refused) {
                // try the next rack / slot
            }
        }
        Found f = unknown("s7", host, port);
        return new Found("s7", host, port, false, null, "port 102 (ISO-TSAP) is open; no S7 connection on rack 0 slot 0–2", f.settings());
    }

    /** EtherNet/IP: ListIdentity (the request RSLinx sends to find devices): product name, vendor, revision */
    static Found eip(String address, String host, int port, int timeout) throws IOException {
        try (Socket s = open(address, port, timeout)) {
            byte[] req = new byte[24];
            req[0] = 0x63;                                               // ListIdentity, no data, no session
            s.getOutputStream().write(req);
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] head = new byte[24];
            in.readFully(head);
            int len = (head[2] & 0xFF) | (head[3] & 0xFF) << 8;
            if ((head[0] & 0xFF) != 0x63 || len < 6 || len > 1024) {
                return unknown("eip", host, port);
            }
            byte[] body = new byte[len];
            in.readFully(body);
            ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
            String product = null, detail = "answers EtherNet/IP ListIdentity";
            if (b.getShort() > 0 && (b.getShort() & 0xFFFF) == 0x0C) {
                b.getShort();                                            // item length
                b.getShort();                                            // encapsulation version
                b.position(b.position() + 16);                           // socket address
                int vendor = b.getShort() & 0xFFFF, type = b.getShort() & 0xFFFF;
                b.getShort();                                            // product code
                int major = b.get() & 0xFF, minor = b.get() & 0xFF;
                b.getShort();                                            // status
                b.getInt();                                              // serial number
                int n = b.get() & 0xFF;
                if (n > 0 && b.remaining() >= n) {
                    byte[] name = new byte[n];
                    b.get(name);
                    product = new String(name, StandardCharsets.ISO_8859_1).trim();
                }
                detail = (vendor == 1 ? "Rockwell Automation" : "vendor " + vendor) + (type == 14 ? ", controller" : ", device type " + type)
                        + ", revision " + major + "." + minor;
            }
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("host", host);
            st.put("slot", 0);
            st.put("backplane", 1);
            if (port != 44818) {
                st.put("port", port);
            }
            return new Found("eip", host, port, true, product, detail, st);
        }
    }

    /** DNP3: link status request to the self address 0xFFFC (outstations that support it answer with their address) */
    static Found dnp3(String address, String host, int port, int timeout) throws IOException {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("host", host);
        if (port != 20000) {
            st.put("port", port);
        }
        try (Socket s = open(address, port, timeout)) {
            byte[] f = {0x05, 0x64, 5, (byte) 0xC9, (byte) 0xFC, (byte) 0xFF, 0, 0, 0, 0};    // master address 0
            int crc = Dnp3Driver.crc(f, 0, 8);
            f[8] = (byte) crc;
            f[9] = (byte) (crc >> 8);
            s.getOutputStream().write(f);
            byte[] r = new byte[10];
            new DataInputStream(s.getInputStream()).readFully(r);
            if (r[0] == 0x05 && r[1] == 0x64) {
                int outstation = (r[6] & 0xFF) | (r[7] & 0xFF) << 8;
                st.put("outstation", outstation);
                return new Found("dnp3", host, port, true, "DNP3 outstation " + outstation, "answers DNP3 link status", st);
            }
        } catch (IOException silent) {
            // most outstations only answer their own address
        }
        return new Found("dnp3", host, port, false, null,
                "DNP3 port " + port + " is open; enter the outstation's address (it answers only that one)", st);
    }

    /** IEC 60870-5-104: TESTFR act, answered with TESTFR con (no data transfer is started) */
    static Found iec104(String address, String host, int port, int timeout) throws IOException {
        try (Socket s = open(address, port, timeout)) {
            s.getOutputStream().write(new byte[]{0x68, 4, 0x43, 0, 0, 0});
            byte[] r = new byte[6];
            new DataInputStream(s.getInputStream()).readFully(r);
            if (r[0] != 0x68 || (r[2] & 0xFF) != 0x83) {
                return unknown("iec104", host, port);
            }
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("host", host);
            st.put("commonAddress", 1);
            if (port != 2404) {
                st.put("port", port);
            }
            return new Found("iec104", host, port, true, null, "answers IEC 104 test frames; check the common address", st);
        }
    }

    /** MQTT: CONNECT (clean session, random client id) then DISCONNECT; the CONNACK says whether sign-in is needed */
    static Found mqtt(String address, String host, int port, int timeout) throws IOException {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("url", (port == 8883 ? "mqtts://" : "mqtt://") + host + ":" + port);
        st.put("payload", "value");
        if (port == 8883) {
            return new Found("mqtt", host, port, false, null, "MQTT over TLS port is open (not checked further)", st);
        }
        try (Socket s = open(address, port, timeout)) {
            byte[] id = ("bimba-scan-" + Long.toHexString(System.nanoTime() & 0xFFFFFF)).getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream v = new ByteArrayOutputStream();
            v.write(new byte[]{0, 4, 'M', 'Q', 'T', 'T', 4, 0x02, 0, 10, 0, (byte) id.length});
            v.write(id);
            OutputStream out = s.getOutputStream();
            out.write(0x10);
            out.write(v.size());
            v.writeTo(out);
            byte[] ack = new byte[4];
            new DataInputStream(s.getInputStream()).readFully(ack);
            if ((ack[0] & 0xFF) != 0x20 || ack[1] != 2) {
                return unknown("mqtt", host, port);
            }
            out.write(new byte[]{(byte) 0xE0, 0});
            int rc = ack[3] & 0xFF;
            return new Found("mqtt", host, port, true, "MQTT broker",
                    rc == 0 ? "anonymous sign-in allowed" : rc == 4 || rc == 5 ? "needs a user name and password" : "answered CONNACK " + rc, st);
        }
    }

    /** SQL Server (PRELOGIN: its version), PostgreSQL (SSLRequest), MySQL / MariaDB (the server's greeting), by the port */
    static Found sql(String address, String host, int port, int timeout) throws IOException {
        return sql(port == 5432 ? "postgresql" : port == 3306 ? "mysql" : "sqlserver", address, host, port, timeout);
    }

    /** test hook: the database a standard port means, asked on another port */
    static Found sqlAs(int standardPort, String address, int port) throws IOException {
        return sql(standardPort == 5432 ? "postgresql" : standardPort == 3306 ? "mysql" : "sqlserver", address, "localhost", port, 1500);
    }

    private static Found sql(String engine, String address, String host, int port, int timeout) throws IOException {
        Map<String, Object> st = new LinkedHashMap<>();
        try (Socket s = open(address, port, timeout)) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String product, detail;
            if ("postgresql".equals(engine)) {
                out.write(new byte[]{0, 0, 0, 8, 0x04, (byte) 0xD2, 0x16, 0x2F});
                int r = in.read();
                if (r != 'S' && r != 'N') {
                    return unknown("sql", host, port);
                }
                product = "PostgreSQL";
                detail = r == 'S' ? "encryption offered" : "no encryption";
            } else if ("mysql".equals(engine)) {
                DataInputStream d = new DataInputStream(in);
                byte[] h = new byte[4];
                d.readFully(h);
                int len = (h[0] & 0xFF) | (h[1] & 0xFF) << 8 | (h[2] & 0xFF) << 16;
                byte[] p = new byte[Math.min(len, 512)];
                d.readFully(p);
                if (p.length > 0 && (p[0] & 0xFF) == 0xFF) {
                    product = "MySQL / MariaDB";
                    detail = "refuses this computer: " + new String(p, 3, Math.max(0, p.length - 3), StandardCharsets.UTF_8).replaceAll("^#\\w{5}", "").trim();
                } else if (p.length > 1 && p[0] == 0x0A) {
                    int end = 1;
                    while (end < p.length && p[end] != 0) {
                        end++;
                    }
                    String ver = new String(p, 1, end - 1, StandardCharsets.US_ASCII);
                    Matcher m = Pattern.compile("(\\d+\\.\\d+\\.\\d+)-MariaDB").matcher(ver);
                    product = m.find() ? "MariaDB " + m.group(1) : "MySQL " + ver.replaceAll("-.*$", "");
                    detail = "server version " + ver;
                } else {
                    return unknown("sql", host, port);
                }
            } else {
                // TDS PRELOGIN: VERSION + ENCRYPTION (not supported), terminator
                byte[] payload = {0, 0, 11, 0, 6, 1, 0, 17, 0, 1, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 2};
                ByteArrayOutputStream m = new ByteArrayOutputStream();
                m.write(new byte[]{0x12, 1, 0, (byte) (8 + payload.length), 0, 0, 1, 0});
                m.write(payload);
                out.write(m.toByteArray());
                DataInputStream d = new DataInputStream(in);
                byte[] h = new byte[8];
                d.readFully(h);
                int len = ((h[2] & 0xFF) << 8 | h[3] & 0xFF) - 8;
                if (h[0] != 0x04 || len < 6 || len > 4096) {
                    return unknown("sql", host, port);
                }
                byte[] p = new byte[len];
                d.readFully(p);
                product = "SQL Server";
                detail = "answers TDS";
                for (int at = 0; at + 5 <= p.length && (p[at] & 0xFF) != 0xFF; at += 5) {
                    int off = (p[at + 1] & 0xFF) << 8 | p[at + 2] & 0xFF;
                    if (p[at] == 0 && off + 4 <= p.length) {
                        int major = p[off] & 0xFF, minor = p[off + 1] & 0xFF, build = (p[off + 2] & 0xFF) << 8 | p[off + 3] & 0xFF;
                        product = "SQL Server " + switch (major) {
                            case 17 -> "2025";
                            case 16 -> "2022";
                            case 15 -> "2019";
                            case 14 -> "2017";
                            case 13 -> "2016";
                            case 12 -> "2014";
                            case 11 -> "2012";
                            default -> major + "." + minor;
                        };
                        detail = "version " + major + "." + minor + "." + build;
                    }
                }
            }
            st.put("engine", engine);
            st.put("host", host);
            if (port != (engine.equals("postgresql") ? 5432 : engine.equals("mysql") ? 3306 : 1433)) {
                st.put("port", port);
            }
            return new Found("sql", host, port, true, product, detail, st);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Socket open(String address, int port, int timeout) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(address, port), timeout);
            s.setSoTimeout(timeout);
            s.setTcpNoDelay(true);
            return s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    /** "10.1.2.3", 24 -> "10.1.2.0/24" */
    static String network(String ip, int bits) {
        long a = ipv4(ip), mask = bits == 0 ? 0 : 0xFFFFFFFFL << (32 - bits) & 0xFFFFFFFFL;
        return text(a & mask) + "/" + bits;
    }

    /** adds a network's host addresses (or the one address) up to MAX_HOSTS; true when some did not fit */
    static boolean addRange(Set<String> hosts, String cidr) {
        Matcher m = CIDR.matcher(cidr);
        if (!m.matches()) {
            return false;
        }
        int bits = m.group(2) == null ? 32 : Integer.parseInt(m.group(2));
        if (bits < 22 || bits > 32) {
            throw new IllegalArgumentException(cidr + ": networks from /22 to /32 only");
        }
        long base = ipv4(m.group(1)) & (0xFFFFFFFFL << (32 - bits) & 0xFFFFFFFFL);
        long size = 1L << (32 - bits);
        long first = size > 2 ? base + 1 : base, last = size > 2 ? base + size - 2 : base + size - 1;
        for (long a = first; a <= last; a++) {
            if (hosts.size() >= MAX_HOSTS) {
                return true;
            }
            hosts.add(text(a));
        }
        return false;
    }

    static long ipv4(String ip) {
        String[] p = ip.split("\\.");
        long a = 0;
        for (String x : p) {
            int v = Integer.parseInt(x);
            if (v > 255) {
                throw new IllegalArgumentException(ip + " is not an IPv4 address");
            }
            a = a << 8 | v;
        }
        return a;
    }

    static String text(long a) {
        return (a >> 24 & 255) + "." + (a >> 16 & 255) + "." + (a >> 8 & 255) + "." + (a & 255);
    }

    /** localhost first, then addresses in numeric order, then host names */
    private static long sortKey(String host) {
        if ("localhost".equals(host)) {
            return -1;
        }
        try {
            return CIDR.matcher(host).matches() ? ipv4(host) : Long.MAX_VALUE;
        } catch (RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean contains(JsonArray a, String v) {
        for (JsonElement e : a) {
            if (v.equals(e.getAsString())) {
                return true;
            }
        }
        return false;
    }
}
