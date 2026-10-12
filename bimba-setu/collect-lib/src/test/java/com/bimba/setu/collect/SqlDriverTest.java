package com.bimba.setu.collect;

import com.bimba.setu.collect.Canonical.Quality;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL source: the read-only query check, connection settings, quality and times (databases: agent/test-linux). */
class SqlDriverTest {
    @Test
    void readingQueriesOnly() {
        assertEquals("SELECT TagName AS tag, Value AS value FROM Live WHERE TagName IN ({tags})",
                SqlDriver.checkQuery("  SELECT TagName AS tag, Value AS value FROM Live WHERE TagName IN ({tags}) ;"));
        SqlDriver.checkQuery("WITH last AS (SELECT * FROM t) SELECT * FROM last");
        SqlDriver.checkQuery("select [Update] as tag, 'drop table x; delete' as value from t -- insert into y");
        SqlDriver.checkQuery("SELECT \"Into\" AS tag, v AS value FROM t /* ; exec */");
        SqlDriver.checkQuery("SELECT REPLACE(name, '_', '.') AS tag, val AS value FROM t");
        for (String bad : new String[]{"", "DELETE FROM t", "UPDATE t SET v = 1", "SELECT * INTO copy FROM t",
                "SELECT 1; DROP TABLE t", "WITH x AS (SELECT 1) DELETE FROM t", "EXEC xp_cmdshell 'dir'",
                "SELECT * FROM OPENROWSET('x', 'y')", "SELECT pg_read_file('/etc/passwd')", "SELECT * FROM t FOR UPDATE",
                "SELECT pg_sleep(10)", "TRUNCATE t", "SELECT LOAD_FILE('/etc/passwd')"}) {
            assertThrows(IllegalArgumentException.class, () -> SqlDriver.checkQuery(bad), bad);
        }
    }

    @Test
    void connectionFromSettingsOnly() {
        JsonObject s = new JsonObject();
        s.addProperty("engine", "sqlserver");
        s.addProperty("host", "historian01");
        s.addProperty("instance", "INSQL");
        s.addProperty("database", "Runtime");
        s.addProperty("username", "bimba_reader");
        s.addProperty("password", "x;encrypt=false");
        s.addProperty("tls", "trust");
        s.addProperty("layout", "rows");
        s.addProperty("query", "SELECT TagName AS tag, Value AS value FROM Live");
        SqlDriver d = new SqlDriver(s);
        assertEquals("jdbc:sqlserver://historian01\\INSQL", d.url(), "named instance: port from the SQL Server Browser");
        assertEquals("Runtime", d.properties().getProperty("databaseName"));
        assertEquals("true", d.properties().getProperty("trustServerCertificate"));
        assertEquals("x;encrypt=false", d.properties().getProperty("password"), "a property, never part of the URL");
        assertEquals("SQL Server historian01\\INSQL/Runtime", d.describe());

        s.addProperty("engine", "postgresql");
        s.addProperty("database", "scada data");
        s.addProperty("tls", "off");
        SqlDriver pg = new SqlDriver(s);
        assertEquals("jdbc:postgresql://historian01:5432/scada%20data", pg.url());
        assertEquals("disable", pg.properties().getProperty("sslmode"));
        assertEquals("always", pg.properties().getProperty("readOnlyMode"));

        s.addProperty("engine", "mysql");
        s.addProperty("port", 3307);
        SqlDriver my = new SqlDriver(s);
        assertEquals("jdbc:mariadb://historian01:3307/scada%20data", my.url());
        assertEquals("false", my.properties().getProperty("allowLocalInfile"));

        s.addProperty("host", "db;socketFactory=x");
        assertThrows(IllegalArgumentException.class, () -> new SqlDriver(s));
        s.addProperty("host", "db");
        s.addProperty("database", "x?socketFactory=y");
        assertThrows(IllegalArgumentException.class, () -> new SqlDriver(s));
    }

    @Test
    void qualityAndTimes() {
        assertEquals(Quality.GOOD, SqlDriver.quality(192));
        assertEquals(Quality.UNCERTAIN, SqlDriver.quality(68));
        assertEquals(Quality.BAD, SqlDriver.quality(0));
        assertEquals(Quality.GOOD, SqlDriver.quality("Good"));
        assertEquals(Quality.BAD, SqlDriver.quality("BAD_NOT_CONNECTED"));
        assertEquals(Quality.GOOD, SqlDriver.quality("192"));
        assertEquals(Quality.GOOD, SqlDriver.quality(true));
        assertEquals(Quality.UNCERTAIN, SqlDriver.quality("strange"));
        assertEquals(Instant.parse("2026-10-10T06:00:00Z"), SqlDriver.parseTime("2026-10-10 08:00:00", ZoneId.of("Europe/Berlin")));
        assertEquals(Instant.parse("2026-10-10T08:00:00Z"), SqlDriver.parseTime("2026-10-10T08:00:00Z", ZoneId.of("Europe/Berlin")));
        assertNull(SqlDriver.parseTime("yesterday", ZoneId.of("UTC")));
        assertTrue(SqlDriver.strip("SELECT 'a;b' AS x").indexOf(';') < 0);
    }
}
