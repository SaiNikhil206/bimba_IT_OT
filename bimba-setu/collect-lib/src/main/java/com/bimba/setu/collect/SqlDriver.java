package com.bimba.setu.collect;

import com.bimba.setu.collect.Canonical.Quality;
import com.bimba.setu.config.TagModel;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A SQL database as a data source: a historian (AVEVA / Wonderware Historian, WinCC, FactoryTalk Historian SE, Ignition,
 * ...) or any SCADA that stores its values in SQL Server, PostgreSQL or MySQL / MariaDB. Each read cycle runs the
 * source's query once and takes the latest value of every tag from its result.
 * <p>
 * Settings: engine sqlserver | postgresql | mysql (MySQL and MariaDB), host, port (1433 / 5432 / 3306; SQL Server:
 * empty with an instance = found through the SQL Server Browser), instance (SQL Server named instance), database,
 * username / password, tls off | trust (encrypted, certificate not checked) | verify, timeoutMs (10000), query,
 * layout rows | columns, timeZone (of time columns without one, "UTC"), staleSeconds (0 = never).
 * <p>
 * Layout "rows", one row per tag: the query returns the columns tag and value, optionally time and quality (use AS),
 * e.g. {@code SELECT TagName AS tag, Value AS value, DateTime AS time, OPCQuality AS quality FROM Runtime.dbo.Live
 * WHERE TagName IN ({tags})}. {tags} becomes the source's tag addresses (as parameters). A tag's address is its name in
 * the tag column (case does not matter); with several rows for a tag the latest (by time) counts.
 * Layout "columns", one row: each tag's address is a column name, an optional column time is the row's time, e.g.
 * {@code SELECT TOP 1 * FROM LineData ORDER BY ts DESC}.
 * <p>
 * Quality column: OPC numbers (192+ GOOD, 64–191 UNCERTAIN, below BAD), GOOD / BAD / UNCERTAIN text or true / false.
 * Read only: one SELECT (or WITH ... SELECT) statement, no statements that change data ({@link #checkQuery}), the
 * connection is read only where the database supports it. Use a database login that can only read.
 */
public final class SqlDriver implements Driver {
    public static final Set<String> ENGINES = Set.of("sqlserver", "postgresql", "mysql");
    public static final Set<String> TLS = Set.of("off", "trust", "verify");
    public static final Pattern HOST = Pattern.compile("[A-Za-z0-9._:\\[\\]-]{1,255}");
    public static final Pattern INSTANCE = Pattern.compile("[A-Za-z0-9_$#-]{1,16}");
    public static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_ .$-]{1,128}");
    private static final Pattern WRITES = Pattern.compile("(?i)\\b(insert|update|delete|merge|upsert|drop|alter|create|truncate|grant|revoke"
            + "|exec|execute|call|into|copy|declare|backup|restore|shutdown|kill|dbcc|bulk|openrowset|opendatasource|openquery|lock"
            + "|xp_\\w+|sp_\\w+|pg_sleep\\w*|pg_read\\w*|pg_write\\w*|pg_terminate\\w*|lo_import|lo_export|dblink\\w*|load_file|sleep|benchmark)\\b");
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_QUERY = 20_000;

    /** the result of one query: readings by lower-case address, the columns and the row count */
    record Result(Map<String, Reading> readings, Map<String, String> names, List<String> columns, int rows) {
    }

    private final String engine;
    private final String host;
    private final Integer port;
    private final String instance;
    private final String database;
    private final String username;
    private final String password;
    private final String tls;
    private final String query;
    private final boolean rows;
    private final ZoneId zone;
    private final long staleMs;
    private final int timeoutMs;
    private Connection conn;

    public SqlDriver(JsonObject s) {
        engine = lower(S7Driver.str(s, "engine"), "sqlserver");
        if (!ENGINES.contains(engine)) {
            throw new IllegalArgumentException("SQL source: engine sqlserver, postgresql or mysql");
        }
        host = S7Driver.str(s, "host");
        if (host == null || !HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("SQL source without a valid host");
        }
        int p = S7Driver.num(s, "port", 0);
        instance = S7Driver.str(s, "instance");
        if (instance != null && !INSTANCE.matcher(instance).matches()) {
            throw new IllegalArgumentException("SQL Server instance name: letters, digits, _ $ # -");
        }
        port = p > 0 ? Integer.valueOf(p) : "sqlserver".equals(engine) && instance != null ? null : Integer.valueOf(defaultPort(engine));
        database = S7Driver.str(s, "database");
        if (database != null && !DATABASE.matcher(database).matches()) {
            throw new IllegalArgumentException("SQL database name: letters, digits, spaces and _ . $ -");
        }
        username = S7Driver.str(s, "username");
        password = s.has("password") && s.get("password").isJsonPrimitive() ? s.get("password").getAsString() : null;
        tls = lower(S7Driver.str(s, "tls"), "off");
        query = checkQuery(S7Driver.str(s, "query"));
        rows = !"columns".equals(lower(S7Driver.str(s, "layout"), "rows"));
        String z = S7Driver.str(s, "timeZone");
        ZoneId zz;
        try {
            zz = z == null ? ZoneOffset.UTC : ZoneId.of(z);
        } catch (DateTimeException e) {
            zz = ZoneOffset.UTC;
        }
        zone = zz;
        staleMs = Math.max(0, S7Driver.num(s, "staleSeconds", 0)) * 1000L;
        timeoutMs = Math.max(1000, S7Driver.num(s, "timeoutMs", 10_000));
    }

    static int defaultPort(String engine) {
        return switch (engine) {
            case "postgresql" -> 5432;
            case "mysql" -> 3306;
            default -> 1433;
        };
    }

    private static String lower(String v, String d) {
        return v == null ? d : v.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ the query

    /**
     * The query, checked: one SELECT or WITH statement (a trailing ";" is fine), no statement or function that writes,
     * runs code or reads files. Literals, quoted names and comments are not checked. Throws IllegalArgumentException
     * with a readable message.
     */
    public static String checkQuery(String q) {
        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("Enter the query that reads the values");
        }
        if (q.length() > MAX_QUERY) {
            throw new IllegalArgumentException("The query is too long (at most " + MAX_QUERY + " characters)");
        }
        String bare = strip(q).trim();
        while (bare.endsWith(";")) {
            bare = bare.substring(0, bare.length() - 1).trim();
        }
        if (bare.contains(";")) {
            throw new IllegalArgumentException("One statement only (no ';' between statements)");
        }
        if (!bare.matches("(?is)^(select|with)\\b.*")) {
            throw new IllegalArgumentException("The query must be a SELECT (or WITH … SELECT): the agent only reads");
        }
        java.util.regex.Matcher m = WRITES.matcher(bare);
        if (m.find()) {
            throw new IllegalArgumentException("The query may only read: \"" + m.group(1) + "\" is not allowed");
        }
        String t = q.trim();
        while (t.endsWith(";")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }

    /** the query without string literals, quoted names and comments (each replaced by a space) */
    static String strip(String q) {
        StringBuilder b = new StringBuilder(q.length());
        int i = 0, n = q.length();
        while (i < n) {
            char c = q.charAt(i);
            if (c == '-' && i + 1 < n && q.charAt(i + 1) == '-') {
                while (i < n && q.charAt(i) != '\n') {
                    i++;
                }
                b.append(' ');
            } else if (c == '/' && i + 1 < n && q.charAt(i + 1) == '*') {
                int end = q.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                b.append(' ');
            } else if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                i++;
                while (i < n) {
                    if (q.charAt(i) == close) {
                        if (i + 1 < n && q.charAt(i + 1) == close) {             // '' "" ]] escapes
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    if (c == '\'' && q.charAt(i) == '\\' && i + 1 < n) {           // MySQL backslash escapes
                        i++;
                    }
                    i++;
                }
                i++;
                b.append(' ');
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ Driver

    @Override
    public synchronized List<Reading> read(List<TagModel.Tag> tags) throws Exception {
        List<String> addresses = new ArrayList<>();
        tags.forEach(t -> addresses.add(t.address()));
        Result r = run(addresses);
        List<Reading> out = new ArrayList<>(tags.size());
        for (TagModel.Tag t : tags) {
            out.add(pick(r, t.address()));
        }
        return out;
    }

    private Reading pick(Result r, String address) {
        String key = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        Reading v = r.readings().get(key);
        if (v != null) {
            return v;
        }
        if (!rows) {
            return Reading.bad(r.rows() == 0 ? "the query returned no row" : "the query has no column " + address + " (columns: " + String.join(", ", r.columns()) + ")");
        }
        return Reading.bad("no row for " + address + " in the query's result");
    }

    /** runs the query once (connecting first when needed); {tags} becomes the given addresses */
    Result run(List<String> addresses) throws Exception {
        connect();
        String sql = query;
        List<String> params = new ArrayList<>();
        if (sql.contains("{tags}")) {
            Set<String> distinct = new LinkedHashSet<>();
            addresses.forEach(a -> {
                if (a != null && !a.isBlank()) {
                    distinct.add(a.trim());
                }
            });
            params.addAll(distinct);
            sql = sql.replace("{tags}", params.isEmpty() ? "NULL" : String.join(", ", java.util.Collections.nCopies(params.size(), "?")));
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(Math.max(1, timeoutMs / 1000));
            ps.setMaxRows(rows ? MAX_ROWS : 1);
            ps.setFetchSize(rows ? 1000 : 1);
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rows ? byRows(rs) : byColumns(rs);
            }
        } catch (SQLTimeoutException e) {
            throw new Exception("the query took longer than " + Math.max(1, timeoutMs / 1000) + " s (" + describe() + ")");
        } catch (SQLException e) {
            boolean broken = !(e.getSQLState() != null && e.getSQLState().startsWith("42"));     // 42xxx: the query itself
            if (broken) {
                close();
            }
            throw new Exception("query on " + describe() + " failed: " + message(e));
        }
    }

    private Result byRows(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        List<String> columns = columns(md);
        int tag = find(columns, "tag"), value = find(columns, "value"), time = find(columns, "time"), quality = find(columns, "quality");
        if (tag < 0 || value < 0) {
            throw new SQLException("the query must return the columns tag and value (use AS tag, AS value); it returns "
                    + String.join(", ", columns), "42BIM");
        }
        Map<String, Reading> out = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        int n = 0;
        while (rs.next()) {
            n++;
            String name = rs.getString(tag);
            if (name == null || name.isBlank()) {
                continue;
            }
            String key = name.trim().toLowerCase(Locale.ROOT);
            Instant at = time < 0 ? null : time(rs, time, md);
            Reading prev = out.get(key);
            if (prev != null && at != null && prev.sourceTime() != null && prev.sourceTime().isAfter(at)) {
                continue;                                                    // an older row of the same tag
            }
            out.put(key, reading(value(rs, value, md), at, quality < 0 ? null : rs.getObject(quality)));
            names.put(key, name.trim());
        }
        return new Result(out, names, columns, n);
    }

    private Result byColumns(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        List<String> columns = columns(md);
        Map<String, Reading> out = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        if (!rs.next()) {
            return new Result(out, names, columns, 0);
        }
        int time = find(columns, "time");
        Instant at = time < 0 ? null : time(rs, time, md);
        for (int i = 0; i < columns.size(); i++) {
            if (i + 1 == time) {                                             // find() is 1-based
                continue;
            }
            String key = columns.get(i).toLowerCase(Locale.ROOT);
            out.put(key, reading(value(rs, i + 1, md), at, null));
            names.put(key, columns.get(i));
        }
        return new Result(out, names, columns, 1);
    }

    private Reading reading(Object value, Instant at, Object quality) {
        if (value == null) {
            return new Reading(null, Quality.BAD, "NULL in the database", at);
        }
        Quality q = Quality.GOOD;
        String detail = null;
        if (quality != null) {
            q = quality(quality);
            if (q != Quality.GOOD) {
                detail = "quality " + quality;
            }
        }
        if (q == Quality.BAD) {
            return new Reading(null, Quality.BAD, detail, at);
        }
        if (staleMs > 0 && at != null && System.currentTimeMillis() - at.toEpochMilli() > staleMs) {
            q = Quality.UNCERTAIN;
            detail = (detail == null ? "" : detail + "; ") + "last value from " + at + " (older than " + staleMs / 1000 + " s)";
        }
        return new Reading(value, q, detail, at);
    }

    /** OPC numbers (192+ GOOD, 64–191 UNCERTAIN, below BAD), GOOD / BAD / UNCERTAIN text, true / false */
    static Quality quality(Object q) {
        if (q instanceof Boolean b) {
            return b ? Quality.GOOD : Quality.BAD;
        }
        if (q instanceof Number n) {
            double d = n.doubleValue();
            return d >= 192 ? Quality.GOOD : d >= 64 ? Quality.UNCERTAIN : Quality.BAD;
        }
        String s = q.toString().trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("good") || s.equals("ok") || s.equals("true")) {
            return Quality.GOOD;
        }
        if (s.startsWith("bad") || s.equals("false")) {
            return Quality.BAD;
        }
        if (s.startsWith("uncertain")) {
            return Quality.UNCERTAIN;
        }
        try {
            return quality(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return Quality.UNCERTAIN;
        }
    }

    private static List<String> columns(ResultSetMetaData md) throws SQLException {
        List<String> c = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            c.add(md.getColumnLabel(i));
        }
        return c;
    }

    /** 1-based index of the column (case does not matter), -1 when missing */
    private static int find(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(name)) {
                return i + 1;
            }
        }
        return -1;
    }

    /** a cell as Boolean, Double or String (times as ISO text) */
    private Object value(ResultSet rs, int i, ResultSetMetaData md) throws SQLException {
        int type = md.getColumnType(i);
        if (isTime(type, md.getColumnTypeName(i))) {
            Instant t = time(rs, i, md);
            return t == null ? null : t.toString();
        }
        Object o = rs.getObject(i);
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean) {
            return o;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof byte[] b) {
            return "0x" + HexFormat.of().formatHex(b, 0, Math.min(b.length, 64));
        }
        if (o instanceof java.sql.Clob c) {
            return c.getSubString(1, (int) Math.min(c.length(), 4000));
        }
        return o.toString();
    }

    private static boolean isTime(int type, String typeName) {
        String t = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
        return type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE || type == Types.DATE
                || t.startsWith("datetime") || t.startsWith("timestamp") || t.equals("smalldatetime");
    }

    /** a time cell: with a zone or offset as it is, without one in the source's time zone; numbers as epoch s / ms */
    private Instant time(ResultSet rs, int i, ResultSetMetaData md) throws SQLException {
        int type = md.getColumnType(i);
        String tn = md.getColumnTypeName(i) == null ? "" : md.getColumnTypeName(i).toLowerCase(Locale.ROOT);
        if (type == Types.TIMESTAMP_WITH_TIMEZONE || tn.contains("offset") || tn.equals("timestamptz") || tn.contains("with time zone")) {
            OffsetDateTime o = rs.getObject(i, OffsetDateTime.class);
            return o == null ? null : o.toInstant();
        }
        if (isTime(type, tn)) {
            LocalDateTime l = rs.getObject(i, LocalDateTime.class);
            return l == null ? null : l.atZone(zone).toInstant();
        }
        Object o = rs.getObject(i);
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return Instant.ofEpochMilli((long) (d > 1e11 ? d : d * 1000));
        }
        return o == null ? null : parseTime(o.toString(), zone);
    }

    static Instant parseTime(String s, ZoneId zone) {
        String t = s.trim().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(t).atZone(zone).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ connection

    /** the JDBC URL, built from the settings only (no free-form URL: driver options stay under our control) */
    String url() {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return switch (engine) {
            case "postgresql" -> "jdbc:postgresql://" + h + ":" + port + "/" + enc(database == null ? "postgres" : database);
            case "mysql" -> "jdbc:mariadb://" + h + ":" + port + (database == null ? "/" : "/" + enc(database));
            default -> "jdbc:sqlserver://" + h + (instance != null ? "\\" + instance : "") + (port != null ? ":" + port : "");
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    Properties properties() {
        Properties p = new Properties();
        if (username != null) {
            p.setProperty("user", username);
        }
        if (password != null) {
            p.setProperty("password", password);
        }
        int sec = Math.max(1, timeoutMs / 1000);
        switch (engine) {
            case "postgresql" -> {
                p.setProperty("sslmode", "off".equals(tls) ? "disable" : "trust".equals(tls) ? "require" : "verify-full");
                p.setProperty("connectTimeout", String.valueOf(sec));
                p.setProperty("socketTimeout", String.valueOf(sec * 3));
                p.setProperty("readOnly", "true");
                p.setProperty("readOnlyMode", "always");
                p.setProperty("ApplicationName", "BIMBA Site Agent");
            }
            case "mysql" -> {
                p.setProperty("sslMode", "off".equals(tls) ? "disable" : "trust".equals(tls) ? "trust" : "verify-full");
                p.setProperty("connectTimeout", String.valueOf(timeoutMs));
                p.setProperty("socketTimeout", String.valueOf(timeoutMs * 3));
                p.setProperty("allowLocalInfile", "false");
                p.setProperty("allowMultiQueries", "false");
                p.setProperty("assureReadOnly", "true");
            }
            default -> {
                if (database != null) {
                    p.setProperty("databaseName", database);
                }
                p.setProperty("encrypt", "off".equals(tls) ? "false" : "true");
                p.setProperty("trustServerCertificate", "trust".equals(tls) ? "true" : "false");
                p.setProperty("loginTimeout", String.valueOf(sec));
                p.setProperty("socketTimeout", String.valueOf(timeoutMs * 3));
                p.setProperty("applicationName", "BIMBA Site Agent");
                p.setProperty("applicationIntent", "ReadOnly");
            }
        }
        return p;
    }

    private void connect() throws Exception {
        if (conn != null) {
            return;
        }
        java.sql.Driver d = switch (engine) {
            case "postgresql" -> new org.postgresql.Driver();
            case "mysql" -> new org.mariadb.jdbc.Driver();
            default -> new com.microsoft.sqlserver.jdbc.SQLServerDriver();
        };
        try {
            Connection c = d.connect(url(), properties());
            if (c == null) {
                throw new SQLException("the driver did not accept the address");
            }
            try {
                c.setReadOnly(true);
            } catch (SQLException ignored) {
                // not supported: the query check and a read-only login still apply
            }
            conn = c;
        } catch (SQLException e) {
            throw new Exception("cannot connect to " + describe() + ": " + message(e));
        }
    }

    /** the database's message without driver noise */
    static String message(SQLException e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        m = m.replaceAll("\\s+", " ").trim();
        if (m.startsWith("(conn=")) {
            m = m.replaceFirst("^\\(conn=\\d+\\)\\s*", "");
        }
        m = m.replaceAll("\\s*ClientConnectionId:\\S+", "");                 // SQL Server's connection id
        return m.length() > 400 ? m.substring(0, 400) + "…" : m;
    }

    @Override
    public String describe() {
        String what = switch (engine) {
            case "postgresql" -> "PostgreSQL";
            case "mysql" -> "MySQL";
            default -> "SQL Server";
        };
        return what + " " + host + (instance != null ? "\\" + instance : "") + (port != null && port != defaultPort(engine) ? ":" + port : "")
                + (database != null ? "/" + database : "");
    }

    @Override
    public synchronized void close() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // gone
            }
        }
        conn = null;
    }

    // ------------------------------------------------------------------ console requests

    /** Test connection: runs the query once: {ok, target, rows, columns, values} */
    public static Map<String, Object> test(JsonObject settings, List<TagModel.Tag> tags) throws Exception {
        try (SqlDriver d = new SqlDriver(settings)) {
            List<String> addresses = new ArrayList<>();
            tags.forEach(t -> addresses.add(t.address()));
            Result r = d.run(addresses);
            List<Reading> values = new ArrayList<>();
            tags.forEach(t -> values.add(d.pick(r, t.address())));
            Map<String, Object> m = S7Driver.Plc.result(d.describe(), tags, values);
            m.put("rows", r.rows());
            m.put("columns", r.columns());
            return m;
        }
    }

    /** Browse: the tags (layout rows) or columns (layout columns) the query returns, with their values: {items:[..]} */
    public static Map<String, Object> browse(JsonObject settings, int limit) throws Exception {
        try (SqlDriver d = new SqlDriver(settings)) {
            Result r = d.run(List.of());
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map.Entry<String, Reading> e : r.readings().entrySet()) {
                if (items.size() >= limit) {
                    break;
                }
                String name = r.names().get(e.getKey());
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("nodeId", name);
                v.put("name", name);
                v.put("nodeClass", "Variable");
                v.put("tagName", name);
                v.put("value", e.getValue().value());
                v.put("good", e.getValue().good());
                items.add(v);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("items", items);
            if (d.query.contains("{tags}") && d.rows) {
                m.put("note", "The query only returns the tags it is given ({tags}), so nothing can be listed: add the tags by name.");
            } else if (r.readings().size() > limit) {
                m.put("note", "The first " + limit + " of " + r.readings().size() + " are listed.");
            }
            return m;
        }
    }
}
