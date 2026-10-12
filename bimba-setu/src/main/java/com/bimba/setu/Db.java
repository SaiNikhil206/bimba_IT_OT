package com.bimba.setu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Access to the shared PostgreSQL (HikariCP pool from application.yml). Every BIMBA table lives in one schema
 * (BIMBA_DB_SCHEMA); queries write it as {s}. Schema-qualified names are used instead of search_path because the
 * transaction-mode pooler in front of the shared database does not keep session settings.
 */
@Component
public class Db {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Db");
    private static final Pattern SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final String schema;
    private final String url;
    private volatile Instant lastOk;
    private volatile String lastError;

    public Db(JdbcClient jdbc, PlatformTransactionManager tm, @Value("${bimba.db.schema:bimba}") String schema,
              @Value("${spring.datasource.url:}") String url) {
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("invalid database schema name " + schema);
        }
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.schema = "\"" + schema + "\"";
        // postgresql://host:port/name without the JDBC prefix and the driver options
        this.url = url.replaceFirst("^jdbc:", "").replaceFirst("\\?.*$", "");
    }

    /** the schema name, unquoted */
    public String schemaName() {
        return schema.replace("\"", "");
    }

    /** where the shared database is, for the console: postgresql://host:port/name (schema s) */
    public String describe() {
        return url + " (schema " + schemaName() + ")";
    }

    /** SQL with {s} replaced by the quoted schema. */
    public JdbcClient.StatementSpec sql(String sql) {
        return jdbc.sql(sql.replace("{s}", schema));
    }

    /** Work that may throw a checked exception. */
    public interface Work<T, E extends Exception> {
        T run() throws E;
    }

    /** Runs the work in one transaction; checked exceptions roll back and are rethrown unchanged. */
    @SuppressWarnings("unchecked")
    public <T, E extends Exception> T tx(Work<T, E> work) throws E {
        try {
            return tx.execute(status -> {
                try {
                    return work.run();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new Checked(e);
                }
            });
        } catch (Checked e) {
            throw (E) e.getCause();
        }
    }

    private static final class Checked extends RuntimeException {
        Checked(Exception cause) {
            super(cause);
        }
    }

    /** Reachability check for the status page (every 30 s, so the status call itself never waits on the database). */
    @Scheduled(initialDelay = 2_000, fixedDelay = 30_000)
    public void probe() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            if (lastError != null) {
                log.info("shared database reachable again");
            }
            lastOk = Instant.now();
            lastError = null;
        } catch (RuntimeException e) {
            if (lastError == null) {
                log.warn("shared database not reachable: {}", e.getMessage());
            }
            lastError = Instant.now() + " " + e.getMessage();
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", schema.replace("\"", ""));
        m.put("lastOk", lastOk == null ? null : lastOk.toString());
        m.put("lastError", lastError);
        return m;
    }
}
