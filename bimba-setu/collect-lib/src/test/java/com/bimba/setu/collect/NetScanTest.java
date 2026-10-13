package com.bimba.setu.collect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The network scan against small fake devices on localhost (one per protocol) and an in-memory MQTT broker. */
class NetScanTest {
    private static final List<ServerSocket> servers = new ArrayList<>();
    private static Server broker;
    private static int modbus, iec104, eip, dnp3, pg, mysql, mssql, mqtt, silent;

    /** a fake device: reads {@code request} bytes, writes the answer, closes */
    private interface Device {
        void talk(DataInputStream in, OutputStream out) throws IOException;
    }

    private static int fake(Device d) throws IOException {
        ServerSocket ss = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        servers.add(ss);
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    Thread one = new Thread(() -> {
                        try (s) {
                            s.setSoTimeout(3000);
                            d.talk(new DataInputStream(s.getInputStream()), s.getOutputStream());
                        } catch (IOException ignored) {
                            // the scan's port check connects and closes at once
                        }
                    });
                    one.setDaemon(true);
                    one.start();
                } catch (IOException closed) {
                    // stopping
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    private static byte[] read(DataInputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    @BeforeAll
    static void start() throws Exception {
        modbus = fake((in, out) -> {
            byte[] q = read(in, 11);
            ByteArrayOutputStream pdu = new ByteArrayOutputStream();
            pdu.write(new byte[]{0x2B, 0x0E, 1, 1, 0, 0, 3});
            for (String[] o : new String[][]{{"0", "BIMBA"}, {"1", "PLC-SIM"}, {"2", "1.2"}}) {
                pdu.write(Integer.parseInt(o[0]));
                pdu.write(o[1].length());
                pdu.write(o[1].getBytes(StandardCharsets.US_ASCII));
            }
            int len = pdu.size() + 1;
            out.write(new byte[]{q[0], q[1], 0, 0, (byte) (len >> 8), (byte) len, q[6]});
            pdu.writeTo(out);
        });
        iec104 = fake((in, out) -> {
            byte[] q = read(in, 6);
            if ((q[2] & 0xFF) == 0x43) {
                out.write(new byte[]{0x68, 4, (byte) 0x83, 0, 0, 0});
            }
        });
        eip = fake((in, out) -> {
            read(in, 24);
            byte[] name = "1756-L83E/B".getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream item = new ByteArrayOutputStream();
            item.write(new byte[]{1, 0});                              // encapsulation version
            item.write(new byte[16]);                                  // socket address
            item.write(new byte[]{1, 0, 14, 0, 0x2A, 0, 33, 11, 0, 0, 1, 2, 3, 4});   // vendor 1, type 14, code, rev 33.11, status, serial
            item.write(name.length);
            item.write(name);
            item.write(3);                                             // state
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(new byte[]{1, 0, 0x0C, 0, (byte) item.size(), 0});
            item.writeTo(body);
            byte[] head = new byte[24];
            head[0] = 0x63;
            head[2] = (byte) body.size();
            out.write(head);
            body.writeTo(out);
        });
        dnp3 = fake((in, out) -> {
            read(in, 10);
            byte[] r = {0x05, 0x64, 5, 0x0B, 0, 0, 10, 0, 0, 0};        // link status from outstation 10 to master 0
            int crc = Dnp3Driver.crc(r, 0, 8);
            r[8] = (byte) crc;
            r[9] = (byte) (crc >> 8);
            out.write(r);
        });
        pg = fake((in, out) -> {
            read(in, 8);
            out.write('N');
        });
        mysql = fake((in, out) -> {
            byte[] ver = "5.5.5-10.11.6-MariaDB-1:10.11.6+maria~ubu2204".getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream p = new ByteArrayOutputStream();
            p.write(0x0A);
            p.write(ver);
            p.write(0);
            p.write(new byte[20]);
            out.write(new byte[]{(byte) p.size(), 0, 0, 0});
            p.writeTo(out);
        });
        mssql = fake((in, out) -> {
            byte[] h = read(in, 8);
            read(in, ((h[2] & 0xFF) << 8 | h[3] & 0xFF) - 8);
            byte[] payload = {0, 0, 11, 0, 6, 1, 0, 17, 0, 1, (byte) 0xFF, 15, 0, 0x10, (byte) 0xD2, 0, 0, 2};   // 15.0.4306
            out.write(new byte[]{0x04, 1, 0, (byte) (8 + payload.length), 0, 0, 1, 0});
            out.write(payload);
        });
        silent = fake((in, out) -> read(in, 1));
        try (ServerSocket s = new ServerSocket(0)) {
            mqtt = s.getLocalPort();
        }
        Properties p = new Properties();
        p.setProperty("host", "127.0.0.1");
        p.setProperty("port", String.valueOf(mqtt));
        p.setProperty("allow_anonymous", "true");
        p.setProperty("persistence_enabled", "false");
        broker = new Server();
        broker.startServer(new MemoryConfig(p));
        NetScan.mqtt("127.0.0.1", "localhost", mqtt, 10_000);       // the in-memory broker's first connection is slow
    }

    @AfterAll
    static void stop() throws IOException {
        for (ServerSocket s : servers) {
            s.close();
        }
        broker.stopServer();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> find(List<Map<String, Object>> found, String kind) {
        return found.stream().filter(f -> kind.equals(f.get("kind"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " in " + found));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findsEveryProtocolOnLocalhost() throws Exception {
        JsonObject req = JsonParser.parseString("""
                {"protocols":["modbus","iec104","eip","dnp3","sql","mqtt"],"ownNetworks":false,"subnets":["127.0.0.1"],
                 "timeoutMs":800,"ports":{"modbus":[%d],"iec104":[%d],"eip":[%d],"dnp3":[%d],"sql":[%d,%d,%d],"mqtt":[%d]}}"""
                .formatted(modbus, iec104, eip, dnp3, pg, mysql, mssql, mqtt)).getAsJsonObject();
        // the SQL type is told apart by its standard port; the fakes run elsewhere, so ask each one directly too
        Map<String, Object> r = NetScan.run(req);
        List<Map<String, Object>> found = (List<Map<String, Object>>) r.get("found");
        assertEquals(1, r.get("addresses"));

        Map<String, Object> mb = find(found, "modbus");
        assertEquals(true, mb.get("confirmed"));
        assertEquals("BIMBA PLC-SIM 1.2", mb.get("product"));
        assertEquals("localhost", mb.get("host"));
        assertEquals(Map.of("host", "localhost", "port", modbus, "unitId", 1), mb.get("settings"));

        assertEquals(true, find(found, "iec104").get("confirmed"));
        Map<String, Object> e = find(found, "eip");
        assertEquals("1756-L83E/B", e.get("product"));
        assertTrue(String.valueOf(e.get("detail")).contains("Rockwell Automation, controller, revision 33.11"), String.valueOf(e.get("detail")));
        Map<String, Object> d = find(found, "dnp3");
        assertEquals(10, ((Map<String, Object>) d.get("settings")).get("outstation"));
        Map<String, Object> m = find(found, "mqtt");
        assertEquals(true, m.get("confirmed"));
        assertEquals("anonymous sign-in allowed", m.get("detail"));
        assertEquals("mqtt://localhost:" + mqtt, ((Map<String, Object>) m.get("settings")).get("url"));
        assertEquals(3, found.stream().filter(f -> "sql".equals(f.get("kind"))).count(), "every open SQL port is listed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAddressWithAPortIsAskedAsEveryType() throws Exception {
        JsonObject req = JsonParser.parseString("""
                {"protocols":["modbus","iec104","mqtt"],"ownNetworks":false,"subnets":["127.0.0.1:%d"],"timeoutMs":500}"""
                .formatted(modbus)).getAsJsonObject();
        List<Map<String, Object>> found = (List<Map<String, Object>>) NetScan.run(req).get("found");
        assertEquals(1, found.size(), String.valueOf(found));
        assertEquals("modbus", found.get(0).get("kind"));
        assertEquals(true, found.get(0).get("confirmed"));
        assertEquals(modbus, ((Map<String, Object>) found.get(0).get("settings")).get("port"));
    }

    @Test
    void namesTheDatabases() throws Exception {
        assertEquals("PostgreSQL", NetScan.sqlAs(5432, "127.0.0.1", pg).product());
        NetScan.Found maria = NetScan.sqlAs(3306, "127.0.0.1", mysql);
        assertEquals("MariaDB 10.11.6", maria.product());
        assertEquals("mysql", maria.settings().get("engine"));
        NetScan.Found ms = NetScan.sqlAs(1433, "127.0.0.1", mssql);
        assertEquals("SQL Server 2019", ms.product());
        assertEquals("version 15.0.4306", ms.detail());
    }

    @Test
    void anOpenPortThatSaysNothingIsListedUnconfirmed() throws Exception {
        NetScan.Found f;
        try {
            f = NetScan.identify("modbus", "127.0.0.1", "localhost", silent, 500);
        } catch (IOException timeout) {
            f = NetScan.unknown("modbus", "localhost", silent);
        }
        assertFalse(f.confirmed());
        assertEquals("localhost", f.settings().get("host"));
    }

    @Test
    void networksAreCappedAndCounted() {
        assertEquals("192.168.10.0/24", NetScan.network("192.168.10.77", 24));
        Set<String> hosts = new LinkedHashSet<>();
        assertFalse(NetScan.addRange(hosts, "10.0.0.0/24"));
        assertEquals(254, hosts.size(), "network and broadcast addresses are left out");
        assertEquals("10.0.0.1", hosts.iterator().next());
        hosts.clear();
        assertTrue(NetScan.addRange(hosts, "10.0.0.0/22") | NetScan.addRange(hosts, "10.1.0.0/24"), "the 1024 address cap is reported");
        assertEquals(NetScan.MAX_HOSTS, hosts.size());
        assertThrows(IllegalArgumentException.class, () -> NetScan.addRange(new LinkedHashSet<>(), "10.0.0.0/16"));
    }
}
