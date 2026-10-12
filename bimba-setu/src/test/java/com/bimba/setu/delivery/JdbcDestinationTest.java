package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-END-05: readings into the customer's own table, batched, idempotent on machine / tag / timestamp */
class JdbcDestinationTest {
    private static final String TABLE = "CREATE TABLE readings (plant_id VARCHAR(20), machine_id VARCHAR(40) NOT NULL, tag VARCHAR(40) NOT NULL,"
            + " ts TIMESTAMP NOT NULL, value_num DOUBLE PRECISION, value_text VARCHAR(100), unit VARCHAR(5), quality VARCHAR(10),"
            + " message_id VARCHAR(40), PRIMARY KEY (machine_id, tag, ts))";

    private static byte[] batch(double flow, String unit) {
        JsonObject env = Json.GSON.fromJson("""
                {"schemaVersion":"1.3","batchId":"b","plantId":"P1","edgeId":"gw","createdAt":"2026-10-09T08:00:00Z","count":2,"messages":[
                 {"messageId":"m1","plantId":"P1","machineId":"PUMP-1","tagName":"Flow","tagValue":%s,"dataType":"number","unit":"%s","quality":"GOOD","timestamp":"2026-10-09T08:00:00.250Z"},
                 {"messageId":"m2","plantId":"P1","machineId":"PUMP-1","tagName":"Mode","tagValue":"auto","dataType":"string","unit":"","quality":"GOOD","timestamp":"2026-10-09T08:00:01Z"}]}"""
                .formatted(flow, unit), JsonObject.class);
        return Json.GSON.toJson(env).getBytes(StandardCharsets.UTF_8);
    }

    private static String db(String name, String mode) throws Exception {
        String url = "jdbc:h2:mem:" + name + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.createStatement().execute(TABLE);
        }
        return url;
    }

    private static BimbaConfig.Destination cfg(String url, String mode) {
        BimbaConfig.Destination d = new BimbaConfig.Destination();
        d.name = "db";
        d.transport = "jdbc";
        d.jdbcUrl = url;
        d.username = "sa";
        d.password = "";
        d.table = "readings";
        d.writeMode = mode;
        d.timeoutMs = 5000;
        return d;
    }

    private static Object[] row(String url, String tag) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             ResultSet rs = c.createStatement().executeQuery("SELECT value_num, value_text, ts, unit FROM readings WHERE tag = '" + tag + "'")) {
            if (!rs.next()) {
                return null;
            }
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            return new Object[]{rs.getObject(1), rs.getString(2), rs.getTimestamp(3, utc).toInstant().toString(), rs.getString(4)};
        }
    }

    private static int count(String url) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM readings")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void upsertUpdatesAndIgnoreSkipsInEveryDialect() throws Exception {
        record Case(String mode, JdbcDestination.Dialect dialect) {
        }
        // (H2 cannot run Oracle's MERGE ... USING (SELECT ? AS c FROM dual): its untyped parameters; see oracleAndPostgresStatements)
        for (Case k : List.of(new Case("MySQL", JdbcDestination.Dialect.MYSQL), new Case("MSSQLServer", JdbcDestination.Dialect.SQLSERVER))) {
            String url = db("up" + k.mode(), k.mode());
            try (JdbcDestination d = new JdbcDestination(cfg(url, "upsert"), k.dialect())) {
                d.send(batch(1.5, "m3/h"));
                d.send(batch(2.5, "m3/h"));                             // the same readings again, the flow corrected
                assertEquals(2, count(url), k.mode() + ": stored once");
                Object[] flow = row(url, "Flow");
                assertEquals(2.5, ((Number) flow[0]).doubleValue(), k.mode() + ": updated");
                assertEquals("2.5", flow[1]);
                assertEquals("2026-10-09T08:00:00.250Z", flow[2], k.mode() + ": the time in UTC");
                Object[] mode = row(url, "Mode");
                assertNull(mode[0], "a text reading has no number");
                assertEquals("auto", mode[1]);
            }
            String url2 = db("ig" + k.mode(), k.mode());
            try (JdbcDestination d = new JdbcDestination(cfg(url2, "ignore"), k.dialect())) {
                d.send(batch(1.5, "m3/h"));
                d.send(batch(9.5, "m3/h"));
                assertEquals(2, count(url2));
                assertEquals(1.5, ((Number) row(url2, "Flow")[0]).doubleValue(), k.mode() + ": a reading stored before is left as it is");
            }
        }
    }

    @Test
    void oracleAndPostgresStatements() throws Exception {
        List<String> cols = List.of("machine_id", "tag", "ts", "value_num");
        List<String> keys = List.of("machine_id", "tag", "ts");
        assertEquals("INSERT INTO readings (machine_id, tag, ts, value_num) VALUES (?, ?, ?, ?) ON CONFLICT (machine_id, tag, ts)"
                + " DO UPDATE SET value_num = EXCLUDED.value_num", JdbcDestination.sql(JdbcDestination.Dialect.POSTGRES, "readings", cols, keys, "upsert"));
        assertTrue(JdbcDestination.sql(JdbcDestination.Dialect.POSTGRES, "readings", cols, keys, "ignore").endsWith("ON CONFLICT (machine_id, tag, ts) DO NOTHING"));
        assertEquals("INSERT INTO readings (machine_id, tag, ts, value_num) VALUES (?, ?, ?, ?)",
                JdbcDestination.sql(JdbcDestination.Dialect.POSTGRES, "readings", cols, keys, "insert"));
        assertEquals("MERGE INTO readings t USING (SELECT ? AS machine_id, ? AS tag, ? AS ts, ? AS value_num FROM dual) s"
                + " ON (t.machine_id = s.machine_id AND t.tag = s.tag AND t.ts = s.ts) WHEN MATCHED THEN UPDATE SET t.value_num = s.value_num"
                + " WHEN NOT MATCHED THEN INSERT (machine_id, tag, ts, value_num) VALUES (s.machine_id, s.tag, s.ts, s.value_num)",
                JdbcDestination.sql(JdbcDestination.Dialect.ORACLE, "readings", cols, keys, "upsert"));
        assertTrue(!JdbcDestination.sql(JdbcDestination.Dialect.ORACLE, "readings", cols, keys, "ignore").contains("WHEN MATCHED"),
                "ignore: only WHEN NOT MATCHED");
        assertTrue(!JdbcDestination.sql(JdbcDestination.Dialect.POSTGRES, "readings", keys, keys, "upsert").contains("UPDATE"),
                "only key columns: nothing to update, skip instead");
    }

    @Test
    void dataErrorsRefuseTheWholeBatch() throws Exception {
        String url = db("bad", "MySQL");
        try (JdbcDestination d = new JdbcDestination(cfg(url, "insert"), JdbcDestination.Dialect.MYSQL)) {
            Destination.RejectedException e = assertThrows(Destination.RejectedException.class, () -> d.send(batch(1.5, "m3/hour")));
            assertTrue(e.getMessage().contains("SQLSTATE 22"), e.getMessage());
            assertEquals(0, count(url), "one transaction: nothing of the batch is stored");
            d.send(batch(1.5, "m3/h"));
            assertThrows(Destination.RejectedException.class, () -> d.send(batch(1.5, "m3/h")), "insert of a stored reading: SQLSTATE 23");
        }
    }

    @Test
    void connectionTestChecksTableColumnsAndKey() throws Exception {
        String url = db("chk", "MySQL");
        try (JdbcDestination d = new JdbcDestination(cfg(url, "upsert"), JdbcDestination.Dialect.MYSQL)) {
            String ok = d.test();
            assertTrue(ok.contains("has every column and a unique key on machine_id, tag, ts"), ok);
        }
        BimbaConfig.Destination missing = cfg(url, "upsert");
        missing.table = "nowhere";
        try (JdbcDestination d = new JdbcDestination(missing, JdbcDestination.Dialect.MYSQL)) {
            assertTrue(assertThrows(java.io.IOException.class, d::test).getMessage().contains("there is no table nowhere"));
        }
        BimbaConfig.Destination extra = cfg(url, "upsert");
        extra.columns = "{\"machine_id\":\"{machineId}\",\"tag\":\"{tagName}\",\"ts\":\"{timestamp}\",\"site\":\"{plantId}\"}";
        try (JdbcDestination d = new JdbcDestination(extra, JdbcDestination.Dialect.MYSQL)) {
            assertTrue(assertThrows(java.io.IOException.class, d::test).getMessage().contains("has no column site"));
        }
        BimbaConfig.Destination noKey = cfg(url, "upsert");
        noKey.keyColumns = List.of("machine_id", "tag");
        try (JdbcDestination d = new JdbcDestination(noKey, JdbcDestination.Dialect.MYSQL)) {
            assertTrue(d.test().contains("Warning: no primary key or unique index on machine_id, tag"));
        }
    }

    @Test
    void mappingIsValidated() {
        BimbaConfig.Destination c = cfg("jdbc:postgresql://h/db", "upsert");
        JdbcDestination.check(c);
        c.columns = "{\"bad name\":\"{tagName}\"}";
        assertThrows(IllegalArgumentException.class, () -> JdbcDestination.check(c));
        c.columns = "{\"tag\":\"{nope}\"}";
        assertThrows(IllegalArgumentException.class, () -> JdbcDestination.check(c));
        c.columns = null;
        c.keyColumns = List.of("site");
        assertThrows(IllegalArgumentException.class, () -> JdbcDestination.check(c), "a key column that is not mapped");
        c.keyColumns = List.of();
        c.jdbcUrl = "jdbc:sqlite:x";
        assertThrows(IllegalArgumentException.class, () -> JdbcDestination.check(c));
        c.jdbcUrl = "jdbc:postgresql://h/db";
        c.table = "x; DROP TABLE y";
        assertThrows(IllegalArgumentException.class, () -> JdbcDestination.check(c));
    }

    @Test
    void previewShowsTheStatementAndValues() throws Exception {
        String url = db("pv", "MySQL");
        try (JdbcDestination d = new JdbcDestination(cfg(url, "upsert"), JdbcDestination.Dialect.MYSQL)) {
            String w = d.wire(batch(1.5, "m3/h"));
            assertTrue(w.startsWith("INSERT INTO readings (plant_id, machine_id, tag, ts"), w);
            assertTrue(w.contains("value_num = 1.5") && w.contains("value_text = \"auto\""), w);
        }
    }
}
