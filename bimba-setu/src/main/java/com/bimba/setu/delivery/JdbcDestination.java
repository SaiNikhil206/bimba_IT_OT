package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Readings into a table of the customer's own database through JDBC (FR-END-05): PostgreSQL, Microsoft SQL Server,
 * MySQL / MariaDB and Oracle. "columns" maps each column to a field like a template ({@code {"machine_id":
 * "{machineId}", "ts": "{timestamp}", "value_num": "{tagValueNumber}"}}); the key columns identify a reading.
 * <ul>
 *   <li>insert: plain INSERT;</li>
 *   <li>ignore: readings already stored (same key) are skipped, so a batch sent again stores nothing twice;</li>
 *   <li>upsert (default): readings already stored are updated (ON CONFLICT / ON DUPLICATE KEY / MERGE).</li>
 * </ul>
 * ignore and upsert need a primary key or unique index on the key columns. Every batch is one transaction of JDBC
 * batches (all or nothing). Times ({timestamp}, {sourceTimestamp}, {ingestTime}, {aggregate.from|to}) are written as
 * timestamps in UTC; a data error (SQLSTATE 22 / 23: a value that does not fit the column, a duplicate with insert)
 * refuses the batch, everything else (connection, sign-in, missing table) is retried with the backoff.
 */
public class JdbcDestination extends Destination {
    static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}");
    static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}(\\.[A-Za-z_][A-Za-z0-9_$]{0,62}){0,2}");
    private static final Pattern WHOLE = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.]*)\\}");
    static final Set<String> TIME_FIELDS = Set.of("timestamp", "sourceTimestamp", "ingestTime", "aggregate.from", "aggregate.to", "gap.from", "gap.to");
    /** the default mapping (FR-END-05 keys machine_id, tag, ts) */
    public static final String DEFAULT_COLUMNS = """
            {
              "plant_id": "{plantId}",
              "machine_id": "{machineId}",
              "tag": "{tagName}",
              "ts": "{timestamp}",
              "value_num": "{tagValueNumber}",
              "value_text": "{tagValueText}",
              "unit": "{unit}",
              "quality": "{quality}",
              "message_id": "{messageId}"
            }""";
    public static final List<String> DEFAULT_KEYS = List.of("machine_id", "tag", "ts");

    enum Dialect { POSTGRES, SQLSERVER, MYSQL, ORACLE }

    private final Dialect dialect;
    private final Map<String, String> columns;
    private final List<String> keys;
    private final String sql;
    private HikariDataSource pool;
    /** column -> java.sql.Types of the table, read once (for typed nulls) */
    private Map<String, Integer> types;

    public JdbcDestination(BimbaConfig.Destination cfg) {
        this(cfg, dialect(cfg.jdbcUrl));
    }

    /** with the SQL of this dialect whatever the address says (tests run it on H2's compatibility modes) */
    JdbcDestination(BimbaConfig.Destination cfg, Dialect dialect) {
        super(cfg);
        this.dialect = dialect;
        this.columns = columns(cfg.columns);
        this.keys = cfg.keyColumns == null || cfg.keyColumns.isEmpty() ? DEFAULT_KEYS.stream().filter(columns::containsKey).toList() : cfg.keyColumns;
        this.sql = sql(dialect, cfg.table, new ArrayList<>(columns.keySet()), keys, mode(cfg.writeMode));
    }

    // ------------------------------------------------------------------ configuration

    static Dialect dialect(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:postgresql:")) {
            return Dialect.POSTGRES;
        }
        if (u.startsWith("jdbc:sqlserver:")) {
            return Dialect.SQLSERVER;
        }
        if (u.startsWith("jdbc:mysql:") || u.startsWith("jdbc:mariadb:")) {
            return Dialect.MYSQL;
        }
        if (u.startsWith("jdbc:oracle:")) {
            return Dialect.ORACLE;
        }
        throw new IllegalArgumentException("The address must start with jdbc:postgresql:, jdbc:sqlserver:, jdbc:mysql:, jdbc:mariadb:"
                + " or jdbc:oracle:thin:.");
    }

    static String mode(String m) {
        return m == null || m.isBlank() ? "upsert" : m.toLowerCase(Locale.ROOT);
    }

    /** column -> placeholder text; IllegalArgumentException with a readable reason when it is not usable */
    static Map<String, String> columns(String text) {
        String t = text == null || text.isBlank() ? DEFAULT_COLUMNS : text;
        JsonElement e;
        try {
            e = JsonParser.parseString(t);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("The column mapping is not valid JSON: " + ex.getMessage().replaceAll("^.*?: ", ""));
        }
        if (!e.isJsonObject() || e.getAsJsonObject().isEmpty()) {
            throw new IllegalArgumentException("The column mapping is a JSON object of column: field, like {\"machine_id\": \"{machineId}\"}.");
        }
        Map<String, String> out = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, JsonElement> c : e.getAsJsonObject().entrySet()) {
            if (!IDENT.matcher(c.getKey()).matches()) {
                throw new IllegalArgumentException("\"" + c.getKey() + "\" is not a column name (letters, digits and _, starting with a letter).");
            }
            if (!c.getValue().isJsonPrimitive() || !c.getValue().getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Column " + c.getKey() + ": write the field as text, like \"{tagName}\".");
            }
            String v = c.getValue().getAsString();
            Matcher m = WHOLE.matcher(v);
            while (m.find()) {
                if (!Template.known(m.group(1)) && !unknown.contains("{" + m.group(1) + "}")) {
                    unknown.add("{" + m.group(1) + "}");
                }
            }
            out.put(c.getKey(), v);
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown field" + (unknown.size() > 1 ? "s " : " ") + String.join(", ", unknown) + ".");
        }
        return out;
    }

    /** checks table, key columns and write mode against the mapping; IllegalArgumentException with the reason */
    public static void check(BimbaConfig.Destination d) {
        dialect(d.jdbcUrl);
        if (d.table == null || !TABLE.matcher(d.table.trim()).matches()) {
            throw new IllegalArgumentException("Table: a name like readings or plant.readings (letters, digits and _).");
        }
        Map<String, String> cols = columns(d.columns);
        String mode = mode(d.writeMode);
        if (!Set.of("insert", "ignore", "upsert").contains(mode)) {
            throw new IllegalArgumentException("Write mode must be insert, ignore or upsert.");
        }
        List<String> keys = d.keyColumns == null || d.keyColumns.isEmpty() ? DEFAULT_KEYS.stream().filter(cols::containsKey).toList() : d.keyColumns;
        for (String k : keys) {
            if (!cols.containsKey(k)) {
                throw new IllegalArgumentException("Key column " + k + " is not in the column mapping.");
            }
        }
        if (!"insert".equals(mode) && keys.isEmpty()) {
            throw new IllegalArgumentException("Skipping or updating stored readings needs key columns (e.g. machine_id, tag, ts).");
        }
    }

    /** the statement for one reading (run as a JDBC batch) */
    static String sql(Dialect d, String table, List<String> cols, List<String> keys, String mode) {
        String t = table.trim();
        String list = String.join(", ", cols);
        String marks = String.join(", ", cols.stream().map(c -> "?").toList());
        List<String> others = cols.stream().filter(c -> !keys.contains(c)).toList();
        boolean update = "upsert".equals(mode) && !others.isEmpty();
        if ("insert".equals(mode) || keys.isEmpty()) {
            return "INSERT INTO " + t + " (" + list + ") VALUES (" + marks + ")";
        }
        return switch (d) {
            case POSTGRES -> "INSERT INTO " + t + " (" + list + ") VALUES (" + marks + ") ON CONFLICT (" + String.join(", ", keys) + ") "
                    + (update ? "DO UPDATE SET " + String.join(", ", others.stream().map(c -> c + " = EXCLUDED." + c).toList()) : "DO NOTHING");
            case MYSQL -> update
                    ? "INSERT INTO " + t + " (" + list + ") VALUES (" + marks + ") ON DUPLICATE KEY UPDATE "
                    + String.join(", ", others.stream().map(c -> c + " = VALUES(" + c + ")").toList())
                    : "INSERT IGNORE INTO " + t + " (" + list + ") VALUES (" + marks + ")";
            case SQLSERVER, ORACLE -> {
                String src = d == Dialect.SQLSERVER
                        ? "(VALUES (" + marks + ")) AS s (" + list + ")"
                        : "(SELECT " + String.join(", ", cols.stream().map(c -> "? AS " + c).toList()) + " FROM dual) s";
                String on = String.join(" AND ", keys.stream().map(k -> "t." + k + " = s." + k).toList());
                String sb = "MERGE INTO " + t + (d == Dialect.SQLSERVER ? " AS t" : " t") + " USING " + src + " ON (" + on + ")";
                if (update) {
                    sb += " WHEN MATCHED THEN UPDATE SET " + String.join(", ", others.stream().map(c -> "t." + c + " = s." + c).toList());
                }
                sb += " WHEN NOT MATCHED THEN INSERT (" + list + ") VALUES (" + String.join(", ", cols.stream().map(c -> "s." + c).toList()) + ")";
                yield d == Dialect.SQLSERVER ? sb + ";" : sb;
            }
        };
    }

    // ------------------------------------------------------------------ sending

    @Override
    public synchronized void send(byte[] batch) throws Exception {
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        List<String> cols = new ArrayList<>(columns.keySet());
        try (Connection c = pool().getConnection()) {
            Map<String, Integer> ty = types(c);
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(Math.max(1, cfg.timeoutMs / 1000));
                for (JsonElement e : env.getAsJsonArray("messages")) {
                    JsonObject m = e.getAsJsonObject();
                    for (int i = 0; i < cols.size(); i++) {
                        bind(ps, i + 1, columns.get(cols.get(i)), m, env, ty.getOrDefault(cols.get(i).toLowerCase(Locale.ROOT), Types.VARCHAR), utc);
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // the connection is gone anyway
                }
                throw e;
            }
        } catch (SQLException e) {
            throw problem(e);
        }
    }

    /** one parameter: a whole placeholder keeps the value's type (times as UTC timestamps), text with placeholders is text */
    private static void bind(PreparedStatement ps, int i, String spec, JsonObject m, JsonObject env, int sqlType, Calendar utc) throws SQLException {
        Matcher whole = WHOLE.matcher(spec);
        if (!whole.matches()) {
            ps.setString(i, Template.text(spec, m, env, java.util.function.UnaryOperator.identity()));
            return;
        }
        JsonElement v = Template.value(whole.group(1), m, env);
        if (v == null || v.isJsonNull() || v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && v.getAsString().isEmpty() && sqlType != Types.VARCHAR
                && sqlType != Types.NVARCHAR && sqlType != Types.CHAR && sqlType != Types.LONGVARCHAR) {
            ps.setNull(i, sqlType);
            return;
        }
        if (!v.isJsonPrimitive()) {
            ps.setString(i, v.toString());
            return;
        }
        com.google.gson.JsonPrimitive p = v.getAsJsonPrimitive();
        if (TIME_FIELDS.contains(field(whole.group(1))) && p.isString()) {
            try {
                ps.setTimestamp(i, Timestamp.from(Instant.parse(p.getAsString())), utc);
                return;
            } catch (RuntimeException ignored) {
                // not a time after all: as text
            }
        }
        if (p.isBoolean()) {
            if (sqlType == Types.BOOLEAN || sqlType == Types.BIT) {
                ps.setBoolean(i, p.getAsBoolean());
            } else {
                ps.setString(i, String.valueOf(p.getAsBoolean()));
            }
        } else if (p.isNumber()) {
            java.math.BigDecimal d = p.getAsBigDecimal();
            if (sqlType == Types.VARCHAR || sqlType == Types.NVARCHAR || sqlType == Types.CHAR) {
                ps.setString(i, d.stripTrailingZeros().toPlainString());
            } else if (sqlType == Types.INTEGER || sqlType == Types.BIGINT || sqlType == Types.SMALLINT) {
                ps.setLong(i, d.longValue());
            } else {
                ps.setDouble(i, d.doubleValue());
            }
        } else {
            ps.setString(i, p.getAsString());
        }
    }

    /** the statement and the values of each reading */
    @Override
    protected String wire(byte[] batch) {
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        StringBuilder sb = new StringBuilder(sql).append("\n");
        int n = 0;
        for (JsonElement e : env.getAsJsonArray("messages")) {
            JsonObject m = e.getAsJsonObject();
            sb.append("\nreading ").append(++n).append(':');
            for (Map.Entry<String, String> c : columns.entrySet()) {
                Matcher whole = WHOLE.matcher(c.getValue());
                JsonElement v = whole.matches() ? Template.value(whole.group(1), m, env)
                        : new com.google.gson.JsonPrimitive(Template.text(c.getValue(), m, env, java.util.function.UnaryOperator.identity()));
                sb.append("\n  ").append(c.getKey()).append(" = ").append(v.isJsonNull() ? "NULL" : v.toString());
            }
        }
        return sb.toString();
    }

    /** a placeholder name in its 1.x spelling: {timestamp_ms} -> timestampMs */
    private static String field(String name) {
        if (name.indexOf('_') < 0) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("\\.")) {
            sb.append(sb.length() > 0 ? "." : "").append(com.bimba.setu.canonical.CanonicalV2.camel(part));
        }
        return sb.toString();
    }

    /** the table's column types (lower-case names), read once per connection pool */
    private Map<String, Integer> types(Connection c) throws SQLException {
        if (types != null) {
            return types;
        }
        Map<String, Integer> t = new LinkedHashMap<>();
        String[] parts = cfg.table.trim().split("\\.");
        String name = parts[parts.length - 1];
        String schema = parts.length > 1 ? parts[parts.length - 2] : null;
        DatabaseMetaData md = c.getMetaData();
        for (String n : List.of(name, name.toLowerCase(Locale.ROOT), name.toUpperCase(Locale.ROOT))) {
            String s = schema == null ? null : n.equals(name) ? schema : n.equals(name.toLowerCase(Locale.ROOT))
                    ? schema.toLowerCase(Locale.ROOT) : schema.toUpperCase(Locale.ROOT);
            try (ResultSet rs = dialect == Dialect.MYSQL ? md.getColumns(s, null, n, null) : md.getColumns(null, s, n, null)) {
                while (rs.next()) {
                    t.putIfAbsent(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rs.getInt("DATA_TYPE"));
                }
            }
            if (!t.isEmpty()) {
                break;
            }
        }
        types = t;
        return t;
    }

    private synchronized HikariDataSource pool() {
        if (pool == null) {
            HikariConfig h = new HikariConfig();
            h.setJdbcUrl(url());
            h.setUsername(cfg.username);
            h.setPassword(cfg.password);
            h.setMaximumPoolSize(2);
            h.setMinimumIdle(0);
            h.setConnectionTimeout(Math.max(250, cfg.timeoutMs));
            h.setValidationTimeout(Math.max(250, Math.min(cfg.timeoutMs, 5000)));
            h.setInitializationFailTimeout(-1);                       // a database that is down now is retried later
            h.setPoolName("bimba-" + cfg.name);
            pool = new HikariDataSource(h);
        }
        return pool;
    }

    /** jdbc:mysql: goes through the MariaDB driver (MySQL's own one is GPL) */
    private String url() {
        String u = cfg.jdbcUrl.trim();
        return u.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql:") ? "jdbc:mariadb:" + u.substring("jdbc:mysql:".length()) : u;
    }

    private Exception problem(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        SQLException next = e.getNextException();
        String msg = (e.getMessage() == null ? state : e.getMessage().lines().findFirst().orElse(""))
                + (next != null && next.getMessage() != null && !next.getMessage().equals(e.getMessage()) ? " / " + next.getMessage().lines().findFirst().orElse("") : "");
        if (next != null && next.getSQLState() != null) {
            state = next.getSQLState();
        }
        if (state.startsWith("22") || state.startsWith("23") || vendorDataError(e) || next != null && vendorDataError(next)) {
            int code = next != null && next.getErrorCode() != 0 ? next.getErrorCode() : e.getErrorCode();
            return new RejectedException("the database refused the batch (SQLSTATE " + state + (code != 0 ? ", error " + code : "") + "): " + cut(msg));
        }
        if (state.startsWith("28")) {
            return new java.io.IOException("The database rejected the user name or password: " + cut(msg), e);
        }
        types = null;                                                // the table may have been changed: read it again
        return new java.io.IOException("Database (SQLSTATE " + (state.isEmpty() ? "?" : state) + "): " + cut(msg), e);
    }

    /**
     * SQL Server (SQLSTATE S0001 for most errors) and Oracle (72000 for a value too large) name data errors by their
     * own numbers: a value that does not fit, cannot be converted, a NULL in a NOT NULL column, a duplicate key or a
     * failed constraint. Retrying those would fail the same way, so they refuse the batch like SQLSTATE 22 / 23.
     */
    private boolean vendorDataError(SQLException e) {
        int code = e.getErrorCode();
        return switch (dialect) {
            case SQLSERVER -> Set.of(2627, 2601, 2628, 8152, 515, 547, 245, 241, 242, 8114, 8115, 220, 232, 517).contains(code);
            case ORACLE -> Set.of(1, 1400, 1407, 1438, 1722, 1830, 1840, 1841, 1843, 1847, 1858, 1861, 2290, 2291, 2292, 12899).contains(code);
            default -> false;
        };
    }

    private static String cut(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /**
     * Connects and signs in, then checks the table: it exists, has every mapped column, and (ignore / upsert) a primary
     * key or unique index on exactly the key columns. Writes nothing.
     */
    @Override
    public String test() throws Exception {
        HikariDataSource p = pool();
        try (Connection c = p.getConnection()) {
            types = null;
            Map<String, Integer> t = types(c);
            if (t.isEmpty()) {
                throw new java.io.IOException("Connected, but there is no table " + cfg.table + " (or the user may not see it).");
            }
            List<String> missing = columns.keySet().stream().filter(k -> !t.containsKey(k.toLowerCase(Locale.ROOT))).toList();
            if (!missing.isEmpty()) {
                throw new java.io.IOException("Connected, but table " + cfg.table + " has no column" + (missing.size() > 1 ? "s " : " ")
                        + String.join(", ", missing) + ".");
            }
            String db = c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion().lines().findFirst().orElse("");
            String mode = mode(cfg.writeMode);
            if (!"insert".equals(mode) && !uniqueOn(c, keys)) {
                return "Connected to " + db.trim() + "; table " + cfg.table + " has every column. Warning: no primary key or unique index on "
                        + String.join(", ", keys) + " was found; " + mode + " needs one. Nothing was written.";
            }
            return "Connected to " + db.trim() + "; table " + cfg.table + " has every column" + ("insert".equals(mode) ? ""
                    : " and a unique key on " + String.join(", ", keys)) + ". Nothing was written.";
        } catch (SQLException e) {
            throw problem(e);
        } finally {
            close();
        }
    }

    /** a primary key or unique index made of exactly these columns */
    private boolean uniqueOn(Connection c, List<String> cols) throws SQLException {
        Set<String> want = new LinkedHashSet<>(cols.stream().map(x -> x.toLowerCase(Locale.ROOT)).toList());
        String[] parts = cfg.table.trim().split("\\.");
        String name = parts[parts.length - 1];
        String schema = parts.length > 1 ? parts[parts.length - 2] : null;
        DatabaseMetaData md = c.getMetaData();
        for (String n : List.of(name, name.toLowerCase(Locale.ROOT), name.toUpperCase(Locale.ROOT))) {
            String s = schema == null ? null : n.equals(name) ? schema : n.equals(name.toLowerCase(Locale.ROOT))
                    ? schema.toLowerCase(Locale.ROOT) : schema.toUpperCase(Locale.ROOT);
            Map<String, Set<String>> idx = new LinkedHashMap<>();
            try (ResultSet rs = dialect == Dialect.MYSQL ? md.getIndexInfo(s, null, n, true, false) : md.getIndexInfo(null, s, n, true, false)) {
                while (rs.next()) {
                    if (rs.getString("COLUMN_NAME") != null) {
                        idx.computeIfAbsent(String.valueOf(rs.getString("INDEX_NAME")), k -> new LinkedHashSet<>())
                                .add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                }
            }
            if (idx.values().stream().anyMatch(want::equals)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String endpoint() {
        return (cfg.jdbcUrl == null ? "" : cfg.jdbcUrl.replaceAll("(?i)(password=)[^;&]*", "$1****")) + " -> " + cfg.table;
    }

    @Override
    public synchronized void close() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
        types = null;
    }
}
