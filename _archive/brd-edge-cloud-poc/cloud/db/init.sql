-- BIMBA cloud data store (TimescaleDB / PostgreSQL 16)
-- Populated from canonical JSON messages (docs/canonical-schema.json).
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ------------------------------------------------------------------ master data
CREATE TABLE tags (
    plant_id    TEXT NOT NULL,
    machine_id  TEXT NOT NULL,
    tag         TEXT NOT NULL,
    unit        TEXT,
    category    TEXT,
    data_type   TEXT,
    protocol    TEXT,
    source      TEXT,
    first_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plant_id, machine_id, tag)
);

-- Communication status of every OT source, as reported by the edge
CREATE TABLE sources (
    plant_id    TEXT NOT NULL,
    name        TEXT NOT NULL,
    machine_id  TEXT,
    protocol    TEXT,
    endpoint    TEXT,
    status      TEXT,
    last_ok     TIMESTAMPTZ,
    last_error  TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plant_id, name)
);

-- Edge-to-cloud WAN bridge state (from the broker bridge notifications)
CREATE TABLE edge_links (
    plant_id    TEXT PRIMARY KEY,
    state       TEXT NOT NULL,          -- UP | DOWN
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE edge_link_events (
    time        TIMESTAMPTZ NOT NULL DEFAULT now(),
    plant_id    TEXT NOT NULL,
    state       TEXT NOT NULL
);

-- ------------------------------------------------------------------ time series
CREATE TABLE measurements (
    time        TIMESTAMPTZ NOT NULL,
    plant_id    TEXT NOT NULL,
    machine_id  TEXT NOT NULL,
    tag         TEXT NOT NULL,
    value       DOUBLE PRECISION,       -- numeric / boolean (1/0) values
    value_text  TEXT,                   -- string states, e.g. RUNNING / FAULT
    quality     TEXT NOT NULL,
    category    TEXT NOT NULL,
    message_id  UUID NOT NULL
);
SELECT create_hypertable('measurements', by_range('time', INTERVAL '1 day'));
CREATE UNIQUE INDEX measurements_msg_uq ON measurements (message_id, time);   -- idempotent ingest
CREATE INDEX measurements_tag_idx ON measurements (plant_id, machine_id, tag, time DESC);
SELECT add_retention_policy('measurements', INTERVAL '30 days');

CREATE MATERIALIZED VIEW measurements_1m
WITH (timescaledb.continuous) AS
SELECT time_bucket('1 minute', time) AS bucket, plant_id, machine_id, tag,
       avg(value) AS avg_value, min(value) AS min_value, max(value) AS max_value, count(*) AS samples
FROM measurements
GROUP BY bucket, plant_id, machine_id, tag
WITH NO DATA;
SELECT add_continuous_aggregate_policy('measurements_1m',
    start_offset => INTERVAL '2 hours', end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute');

-- ------------------------------------------------------------------ events
CREATE TABLE alarms (
    time        TIMESTAMPTZ NOT NULL,
    plant_id    TEXT NOT NULL,
    machine_id  TEXT NOT NULL,
    tag         TEXT NOT NULL,
    kind        TEXT NOT NULL,          -- HIGH | LOW | STATE
    state       TEXT NOT NULL,          -- ACTIVE | CLEARED
    value       TEXT,
    limit_value TEXT,
    message     TEXT,
    message_id  UUID PRIMARY KEY
);
CREATE INDEX alarms_time_idx ON alarms (time DESC);

-- Operator actions from legacy SCADA (HRM / shift adherence use case)
CREATE TABLE operator_logs (
    time          TIMESTAMPTZ NOT NULL,
    plant_id      TEXT NOT NULL,
    machine_id    TEXT NOT NULL,
    log_id        BIGINT,
    operator_id   TEXT,
    operator_name TEXT,
    shift         TEXT,
    action        TEXT,
    object        TEXT,
    details       JSONB,
    message_id    UUID PRIMARY KEY
);
CREATE INDEX operator_logs_time_idx ON operator_logs (time DESC);

-- ------------------------------------------------------------------ delivery audit
-- One row per canonical message: proves zero data loss (seq gaps) and latency.
CREATE TABLE delivery_log (
    event_time   TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    plant_id     TEXT NOT NULL,
    edge_id      TEXT NOT NULL,
    edge_session UUID NOT NULL,
    seq          BIGINT NOT NULL,
    category     TEXT NOT NULL,
    message_id   UUID NOT NULL
);
SELECT create_hypertable('delivery_log', by_range('event_time', INTERVAL '1 day'));
CREATE UNIQUE INDEX delivery_log_uq ON delivery_log (edge_session, seq, event_time);
CREATE INDEX delivery_log_received_idx ON delivery_log (received_at DESC);
SELECT add_retention_policy('delivery_log', INTERVAL '7 days');

-- Messages delivered to enterprise targets by Boomi (mock ERP / HRM / data lake)
CREATE TABLE enterprise_inbox (
    id          BIGSERIAL PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    target      TEXT NOT NULL,          -- erp | hrm | datalake | ...
    endpoint    TEXT NOT NULL,
    payload     JSONB
);

-- ------------------------------------------------------------------ views
CREATE VIEW latest_values AS
SELECT t.plant_id, t.machine_id, t.tag, t.unit, t.category, t.protocol, t.source,
       l.time, l.value, l.value_text, l.quality
FROM tags t
LEFT JOIN LATERAL (
    SELECT m.time, m.value, m.value_text, m.quality
    FROM measurements m
    WHERE m.plant_id = t.plant_id AND m.machine_id = t.machine_id AND m.tag = t.tag
    ORDER BY m.time DESC
    LIMIT 1
) l ON true
WHERE t.category IN ('telemetry', 'equipment_status');

CREATE VIEW active_alarms AS
SELECT * FROM (
    SELECT DISTINCT ON (plant_id, machine_id, tag, kind) *
    FROM alarms
    ORDER BY plant_id, machine_id, tag, kind, time DESC
) a
WHERE state = 'ACTIVE';

-- Zero-data-loss check: every edge session numbers its messages 1..N
CREATE VIEW delivery_gaps AS
SELECT plant_id, edge_id, edge_session,
       min(seq) AS first_seq, max(seq) AS last_seq,
       count(*) AS received,
       max(seq) - min(seq) + 1 - count(*) AS missing,
       min(event_time) AS started, max(received_at) AS last_received
FROM delivery_log
GROUP BY plant_id, edge_id, edge_session;
