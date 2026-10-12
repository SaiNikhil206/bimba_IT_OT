package com.bimba.agent;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Data sources this computer offers, suggested in the console's "Add data source": OPC UA servers listening on their
 * usual local ports (Ignition, KEPServerEX / Kepware Edge, CODESYS, WinCC OA, simulators). The Windows agent
 * also lists OPC DA servers; OPC DA does not exist on Linux.
 */
final class Discovery {
    private record Port(int port, String product, String path) {
    }

    private static final Port[] OPC_UA = {
            new Port(62541, "Ignition", ""),
            new Port(49320, "KEPServerEX / Kepware Edge", ""),
            new Port(4862, "WinCC OPC UA", ""),
            new Port(53530, "Prosys OPC UA Simulation Server", "/OPCUA/SimulationServer"),
            new Port(48010, "OPC UA server", ""),
            new Port(4840, "OPC UA server (CODESYS, WinCC OA, open62541, ...)", ""),
            new Port(4841, "OPC UA server", ""),
    };

    private Discovery() {
    }

    /** same shape as the Windows agent's: {opcua:[..], opcda:[]} */
    static Map<String, Object> all() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opcua", opcUaEndpoints());
        m.put("opcda", List.of());
        return m;
    }

    /** [{kind:"opcua", endpoint, product}] for every OPC UA port that accepts a connection on this computer */
    static List<Map<String, Object>> opcUaEndpoints() {
        List<CompletableFuture<Port>> checks = new ArrayList<>();
        for (Port p : OPC_UA) {
            checks.add(CompletableFuture.supplyAsync(() -> listening(p.port()) ? p : null));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (CompletableFuture<Port> c : checks) {
            try {
                Port p = c.get(3, TimeUnit.SECONDS);
                if (p != null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", "opcua");
                    m.put("endpoint", "opc.tcp://localhost:" + p.port() + p.path());
                    m.put("product", p.product());
                    out.add(m);
                }
            } catch (Exception ignored) {
                // not listening or too slow
            }
        }
        return out;
    }

    private static boolean listening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
