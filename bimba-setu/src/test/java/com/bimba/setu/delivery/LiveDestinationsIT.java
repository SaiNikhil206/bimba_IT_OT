package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonObject;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery to real servers, run by hand against throwaway containers (skipped in the normal build): each test runs
 * only when its BIMBA_IT_* variable names the server, e.g. BIMBA_IT_RABBIT=amqp://it-rabbit:5672/%2F.
 */
class LiveDestinationsIT {
    private static String env(String name) {
        return System.getenv(name);
    }

    // ------------------------------------------------------------------ the customer's database (JDBC)

    private static byte[] readings(double flow, String unit) {
        return ("{\"schemaVersion\":\"1.3\",\"batchId\":\"b\",\"plantId\":\"P1\",\"edgeId\":\"gw\",\"createdAt\":\"2026-10-09T08:00:00Z\",\"count\":2,\"messages\":["
                + "{\"messageId\":\"m1\",\"plantId\":\"P1\",\"machineId\":\"PUMP-1\",\"tagName\":\"Flow\",\"tagValue\":" + flow + ",\"dataType\":\"number\","
                + "\"unit\":\"" + unit + "\",\"quality\":\"GOOD\",\"timestamp\":\"2026-10-09T08:00:00.250Z\"},"
                + "{\"messageId\":\"m2\",\"plantId\":\"P1\",\"machineId\":\"PUMP-1\",\"tagName\":\"Mode\",\"tagValue\":\"auto\",\"dataType\":\"string\","
                + "\"unit\":\"\",\"quality\":\"GOOD\",\"timestamp\":\"2026-10-09T08:00:01Z\"}]}").getBytes(StandardCharsets.UTF_8);
    }

    private static java.sql.Connection connect(String url) throws Exception {
        java.sql.SQLException last = null;
        for (int i = 0; i < 60; i++) {                                      // the server may still be starting
            try {
                return java.sql.DriverManager.getConnection(url.startsWith("jdbc:mysql:") ? "jdbc:mariadb:" + url.substring(11) : url,
                        env("BIMBA_IT_USER"), env("BIMBA_IT_PASS"));
            } catch (java.sql.SQLException e) {
                last = e;
                Thread.sleep(2000);
            }
        }
        throw last;
    }

    /** create the table, then insert, upsert, skip, refuse and test through the JDBC destination */
    private static void database(String url, String table, String ddl) throws Exception {
        try (java.sql.Connection c = connect(url); java.sql.Statement s = c.createStatement()) {
            try {
                s.execute("DROP TABLE " + table);
            } catch (java.sql.SQLException ignored) {
                // not there yet
            }
            s.execute(ddl);
        }
        BimbaConfig.Destination d = new BimbaConfig.Destination();
        d.name = "db";
        d.transport = "jdbc";
        d.jdbcUrl = url;
        d.username = env("BIMBA_IT_USER");
        d.password = env("BIMBA_IT_PASS");
        d.table = table;
        d.writeMode = "upsert";
        d.timeoutMs = 10_000;
        JdbcDestination.check(d);
        try (JdbcDestination dest = new JdbcDestination(d)) {
            String test = dest.test();
            assertTrue(test.contains("has every column and a unique key on machine_id, tag, ts"), test);
            dest.send(readings(1.5, "m3/h"));
            dest.send(readings(2.5, "m3/h"));                                // sent again, corrected: updated, not doubled
        }
        d.writeMode = "ignore";
        try (JdbcDestination dest = new JdbcDestination(d)) {
            dest.send(readings(9.5, "m3/h"));                                // already stored: skipped
        }
        try (java.sql.Connection c = connect(url); java.sql.Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery("SELECT tag, value_num, value_text, ts FROM " + table + " ORDER BY tag")) {
            java.util.Calendar utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            assertTrue(rs.next());
            assertEquals("Flow", rs.getString(1));
            assertEquals(2.5, rs.getDouble(2), 1e-9, "upsert updated it, ignore left it");
            assertEquals("2026-10-09T08:00:00.250Z", rs.getTimestamp(4, utc).toInstant().toString(), "the time, in UTC, to the millisecond");
            assertTrue(rs.next());
            assertEquals("Mode", rs.getString(1));
            assertEquals(0, rs.getDouble(2));
            assertTrue(rs.wasNull(), "a text reading has no number");
            assertEquals("auto", rs.getString(3));
            assertTrue(!rs.next(), "two rows: nothing stored twice");
        }
        d.writeMode = "insert";
        try (JdbcDestination dest = new JdbcDestination(d)) {
            Destination.RejectedException e = assertThrows(Destination.RejectedException.class, () -> dest.send(readings(1.0, "m3/hour")));
            assertTrue(e.getMessage().contains("refused the batch"), e.getMessage());
        }
        d.password = "wrong";
        try (JdbcDestination dest = new JdbcDestination(d)) {
            java.io.IOException e = assertThrows(java.io.IOException.class, dest::test);
            assertTrue(!(e instanceof Destination.RetryLaterException), e.getMessage());
        }
    }

    private static final String COLUMNS = "(plant_id VARCHAR(20), machine_id VARCHAR(40) NOT NULL, tag VARCHAR(40) NOT NULL, ts %s NOT NULL,"
            + " value_num %s, value_text VARCHAR(100), unit VARCHAR(5), quality VARCHAR(10), message_id VARCHAR(40), PRIMARY KEY (machine_id, tag, ts))";

    @Test
    @EnabledIfEnvironmentVariable(named = "BIMBA_IT_MSSQL", matches = ".+")
    void sqlServer() throws Exception {
        database(env("BIMBA_IT_MSSQL"), "dbo.bimba_readings", "CREATE TABLE dbo.bimba_readings " + COLUMNS.formatted("DATETIME2(3)", "FLOAT"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BIMBA_IT_MARIADB", matches = ".+")
    void mariaDb() throws Exception {
        database(env("BIMBA_IT_MARIADB"), "bimba_readings", "CREATE TABLE bimba_readings " + COLUMNS.formatted("DATETIME(3)", "DOUBLE"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BIMBA_IT_ORACLE", matches = ".+")
    void oracle() throws Exception {
        database(env("BIMBA_IT_ORACLE"), "BIMBA_READINGS", "CREATE TABLE BIMBA_READINGS " + COLUMNS.replace("VARCHAR(", "VARCHAR2(")
                .formatted("TIMESTAMP(3)", "BINARY_DOUBLE"));
    }

    // ------------------------------------------------------------------ SFTP (OpenSSH server)

    @Test
    @EnabledIfEnvironmentVariable(named = "BIMBA_IT_SFTP", matches = ".+")
    void sftp(@org.junit.jupiter.api.io.TempDir java.io.File work) throws Exception {
        BimbaConfig.Destination d = new BimbaConfig.Destination();
        d.name = "sftp";
        d.transport = "sftp";
        d.url = env("BIMBA_IT_SFTP");
        d.username = env("BIMBA_IT_USER");
        d.password = env("BIMBA_IT_PASS");
        d.format = "csv";
        d.timeoutMs = 15_000;
        String first;
        try (FileDestination f = new FileDestination(d, "P1", null)) {
            first = f.test();                                                  // not pinned yet: shows the key
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("SHA256:[A-Za-z0-9+/]{43}").matcher(first);
        assertTrue(m.find(), first);
        d.hostKey = m.group();
        d.password = null;                                                     // now with the private key only
        d.sshKey = java.nio.file.Files.readString(java.nio.file.Path.of(env("BIMBA_IT_SFTP_KEY")));
        try (FileDestination f = new FileDestination(d, "P1", null)) {
            String test = f.test();
            assertTrue(test.contains("host key matches") && test.contains("is there"), test);
            f.send(QueueDestinationTest.batch());
            f.send(QueueDestinationTest.batch());                              // a retry replaces the file
        }
        d.windowSeconds = 60;
        d.format = "json";
        d.fileName = "w_{time:yyyyMMdd-HHmmss}_{count}.{ext}";
        try (FileDestination f = new FileDestination(d, "P1", work)) {
            long now = System.currentTimeMillis();
            f.stage(Json.GSON.fromJson(new String(QueueDestinationTest.batch(), StandardCharsets.UTF_8), JsonObject.class), now);
            f.sendDue(now + 61_000);
        }
        d.hostKey = "SHA256:" + "A".repeat(43);
        try (FileDestination f = new FileDestination(d, "P1", null)) {
            java.io.IOException e = assertThrows(java.io.IOException.class, f::test);
            assertTrue(e.getMessage().contains("not the one entered under Host key"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ RabbitMQ (AMQP 0-9-1)

    private static BimbaConfig.Destination rabbit(String exchange, String routingKey) {
        BimbaConfig.Destination d = new BimbaConfig.Destination();
        d.name = "rmq";
        d.transport = "amqp";
        d.url = env("BIMBA_IT_RABBIT");
        d.username = env("BIMBA_IT_USER");
        d.password = env("BIMBA_IT_PASS");
        d.exchange = exchange;
        d.routingKey = routingKey;
        d.messageMode = "machine";
        d.timeoutMs = 5000;
        return d;
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BIMBA_IT_RABBIT", matches = ".+")
    void rabbitmq() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setUri(env("BIMBA_IT_RABBIT"));
        f.setUsername(env("BIMBA_IT_USER"));
        f.setPassword(env("BIMBA_IT_PASS"));
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            String q = ch.queueDeclare("", false, true, true, null).getQueue();
            ch.queueBind(q, "amq.topic", "bimba.P1.#");
            try (AmqpDestination d = new AmqpDestination(rabbit("amq.topic", "bimba.{plantId}.{machineId}"), "P1")) {
                String test = d.test();
                assertTrue(test.contains("exchange amq.topic is there"), test);
                d.send(QueueDestinationTest.batch());
            }
            Map<String, JsonObject> byKey = new HashMap<>();
            Map<String, Object> headers = null;
            int mode = 0;
            for (int i = 0; i < 50 && byKey.size() < 2; i++) {
                GetResponse r = ch.basicGet(q, true);
                if (r == null) {
                    Thread.sleep(100);
                    continue;
                }
                byKey.put(r.getEnvelope().getRoutingKey(), Json.GSON.fromJson(new String(r.getBody(), StandardCharsets.UTF_8), JsonObject.class));
                headers = r.getProps().getHeaders();
                mode = r.getProps().getDeliveryMode();
            }
            assertEquals(java.util.Set.of("bimba.P1.PUMP_1", "bimba.P1.TANK-2"), byKey.keySet(), "one message per machine, keyed by it");
            assertEquals(2, byKey.get("bimba.P1.PUMP_1").getAsJsonArray("messages").size(), "both readings of PUMP.1 together");
            assertNotNull(headers);
            assertEquals("b-7", String.valueOf(headers.get("batchId")));
            assertEquals(2, mode, "persistent");
        }

        // a routing key no queue is bound for: the send fails instead of the data disappearing
        try (AmqpDestination d = new AmqpDestination(rabbit("amq.direct", "nobody.{machineId}"), "P1")) {
            java.io.IOException e = assertThrows(java.io.IOException.class, () -> d.send(QueueDestinationTest.batch()));
            assertTrue(e.getMessage().contains("no queue is bound"), e.getMessage());
        }
        try (AmqpDestination d = new AmqpDestination(rabbit("no-such-exchange", "k"), "P1")) {
            java.io.IOException e = assertThrows(java.io.IOException.class, d::test);
            assertTrue(e.getMessage().contains("NOT_FOUND"), e.getMessage());
        }
        BimbaConfig.Destination wrong = rabbit("amq.topic", "k");
        wrong.password = "wrong";
        try (AmqpDestination d = new AmqpDestination(wrong, "P1")) {
            Exception e = assertThrows(Exception.class, () -> d.send(QueueDestinationTest.batch()));
            assertTrue(String.valueOf(e.getMessage()).toLowerCase().contains("refused") || String.valueOf(e.getMessage()).contains("ACCESS_REFUSED"),
                    e.getMessage());
        }
    }
}
