package com.bimba.gateway.delivery;

import com.bimba.gateway.Db;
import com.bimba.gateway.Json;
import com.bimba.gateway.config.BimbaConfig;
import com.google.gson.JsonObject;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * Built into the gateway: stores every batch in the BIMBA database itself, through the gateway's own connection to the
 * shared PostgreSQL. It calls the same SQL function Boomi and BIMBA Ingest call ({@code bimba_ingest}: drops repeated
 * readings by messageId, keeps the latest values, NOTIFY for the live pages), so no separate receiver is needed.
 * Readings are stored as delivered "bimba-gateway". Always the BIMBA standard message (a template does not apply).
 */
public class DatabaseDestination extends Destination {
    /** received_via / via of what this destination stores */
    public static final String VIA = "bimba-gateway";

    private final Db db;

    public DatabaseDestination(BimbaConfig.Destination cfg, Db db) {
        super(cfg);
        this.db = db;
    }

    @Override
    public void send(byte[] batch) throws Exception {
        String json = new String(batch, StandardCharsets.UTF_8);
        String answer;
        try {
            answer = db().tx(() -> {
                // per batch, inside its transaction (the pooler in front of the shared database keeps no session settings)
                db.sql("SELECT set_config('statement_timeout', ?, true)").param(cfg.timeoutMs + "ms").query(String.class).single();
                return db.sql("SELECT {s}.bimba_ingest(?::jsonb, ?)::text").param(json).param(VIA).query(String.class).single();
            });
        } catch (DataAccessException e) {
            SQLException sql = sqlCause(e);
            String state = sql == null || sql.getSQLState() == null ? "" : sql.getSQLState();
            if (state.startsWith("22")) {
                // data exception (a messageId that is no UUID, a timestamp that is no time ...): a retry would fail the same way
                throw new RejectedException("the BIMBA database refused the batch: " + reason(e));
            }
            throw new java.io.IOException(problem(state, e), e);
        }
        JsonObject r = Json.GSON.fromJson(answer, JsonObject.class);
        int received = r == null || !r.has("received") ? 0 : r.get("received").getAsInt();
        int rejected = r == null || !r.has("rejected") ? 0 : r.get("rejected").getAsInt();
        if (received == 0 && rejected > 0) {
            throw new RejectedException("none of the " + rejected + " readings has messageId, plantId, machineId, tagName and timestamp");
        }
        if (rejected > 0) {
            LoggerFactory.getLogger("BIMBA.Delivery").warn("destination {}: {} readings without messageId, plantId, machineId, tagName"
                    + " or timestamp were not stored", cfg.name, rejected);
        }
    }

    /** Connected, and the BIMBA tables and bimba_ingest are there. Stores nothing. */
    @Override
    public String test() throws Exception {
        try {
            int found = db().sql("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = ? AND p.proname = 'bimba_ingest'").param(db.schemaName()).query(Integer.class).single();
            if (found == 0) {
                throw new java.io.IOException("Connected, but bimba_ingest() is not installed in schema " + db.schemaName()
                        + ": run the Django migrations (docker compose up -d django).");
            }
        } catch (DataAccessException e) {
            SQLException sql = sqlCause(e);
            throw new java.io.IOException(problem(sql == null || sql.getSQLState() == null ? "" : sql.getSQLState(), e), e);
        }
        return "Connected to the BIMBA database " + db.describe() + "; bimba_ingest() is installed. Nothing was stored.";
    }

    private Db db() throws java.io.IOException {
        if (db == null) {
            throw new java.io.IOException("The BIMBA database is not configured in this gateway (BIMBA_DB_*).");
        }
        return db;
    }

    private String problem(String state, DataAccessException e) {
        if (state.equals("57014")) {
            return "The BIMBA database did not finish in " + cfg.timeoutMs + " ms (Timeout, Advanced).";
        }
        if (state.equals("42883") || state.equals("42P01")) {
            return "bimba_ingest() or the BIMBA tables are missing in schema " + db.schemaName() + ": run the Django migrations.";
        }
        if (state.startsWith("28")) {
            return "The BIMBA database refused the gateway's sign-in (BIMBA_DB_USER / BIMBA_DB_PASSWORD).";
        }
        if (state.startsWith("08") || e instanceof org.springframework.jdbc.CannotGetJdbcConnectionException) {
            return "The BIMBA database is not reachable: " + reason(e);
        }
        return "BIMBA database: " + reason(e);
    }

    private static SQLException sqlCause(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SQLException s) {
                return s;
            }
        }
        return null;
    }

    /** the database's own words (first line), not Spring's wrapper with the whole statement */
    private static String reason(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        m = m.lines().findFirst().orElse(m);
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    @Override
    public String endpoint() {
        return db == null ? "BIMBA database" : db.describe();
    }
}
