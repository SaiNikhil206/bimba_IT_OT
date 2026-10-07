-- bimba_ingest(batch, via): the single write path for BOTH end systems.
--
--   BIMBA Ingest (Django) : SELECT bimba_ingest(%s::jsonb, 'bimba-rest')
--   Boomi  : SELECT bimba_ingest(?::jsonb, 'boomi-es')       (Event Streams listener -> Database connector)
--
-- Accepts a batch envelope {"batchId", "plantId", "edgeId", "messages": [...]},
-- a bare array of canonical messages, or one canonical message.
-- De-duplicates on messageId, maintains latest values, records the batch, and
-- emits NOTIFY bimba_live so the React UI updates regardless of delivery path.
--
-- SET search_path FROM CURRENT pins the function to the schema it was installed in
-- (DB_SCHEMA), so callers such as Boomi resolve the same tables whatever their own search_path.
CREATE OR REPLACE FUNCTION bimba_ingest(p_batch jsonb, p_via text DEFAULT 'unknown')
RETURNS jsonb
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
DECLARE
    v_is_obj   boolean := jsonb_typeof(p_batch) = 'object';
    v_msgs     jsonb;
    v_total    int;
    v_valid    int;
    v_inserted int;
    v_first    bigint;
    v_last     bigint;
    v_tmin     timestamptz;
    v_tmax     timestamptz;
    v_updates  jsonb;
    v_chunk    jsonb;
BEGIN
    IF p_batch IS NULL THEN
        RAISE EXCEPTION 'bimba_ingest: batch is null';
    END IF;

    v_msgs := CASE
        WHEN jsonb_typeof(p_batch) = 'array' THEN p_batch
        WHEN v_is_obj AND p_batch ? 'messages' THEN p_batch->'messages'
        WHEN v_is_obj AND p_batch ? 'messageId' THEN jsonb_build_array(p_batch)
        ELSE '[]'::jsonb END;
    v_total := jsonb_array_length(v_msgs);

    IF to_regclass('pg_temp._bimba_in') IS NOT NULL THEN
        DROP TABLE pg_temp._bimba_in;
    END IF;
    CREATE TEMP TABLE _bimba_in ON COMMIT DROP AS
    SELECT (x->>'messageId')::uuid        AS message_id,
           x->>'plantId'                  AS plant_id,
           x->>'machineId'                AS machine_id,
           x->>'tagName'                  AS tag_name,
           (x->>'timestamp')::timestamptz AS ts,
           x->'tagValue'                  AS tag_value,
           x->>'dataType'                 AS data_type,
           x->>'unit'                     AS unit,
           COALESCE(x->>'quality', 'GOOD') AS quality,
           x->'source'->>'address'        AS source_path,
           NULLIF(x->'edge'->>'seq', '')::bigint AS seq
    FROM jsonb_array_elements(v_msgs) AS x
    WHERE x ?& ARRAY['messageId', 'plantId', 'machineId', 'tagName', 'timestamp'];

    SELECT count(*), min(seq), max(seq), min(ts), max(ts)
      INTO v_valid, v_first, v_last, v_tmin, v_tmax FROM _bimba_in;

    INSERT INTO bimba_machine (plant_id, machine_id, first_seen, last_seen)
    SELECT plant_id, machine_id, now(), max(ts) FROM _bimba_in GROUP BY plant_id, machine_id
    ON CONFLICT (plant_id, machine_id)
    DO UPDATE SET last_seen = GREATEST(bimba_machine.last_seen, EXCLUDED.last_seen);

    INSERT INTO bimba_tag (machine_id, name, unit, data_type, source_path, first_seen)
    SELECT DISTINCT ON (mc.id, i.tag_name) mc.id, i.tag_name, i.unit, i.data_type, i.source_path, now()
    FROM _bimba_in i
    JOIN bimba_machine mc ON mc.plant_id = i.plant_id AND mc.machine_id = i.machine_id
    ORDER BY mc.id, i.tag_name, i.ts DESC
    ON CONFLICT (machine_id, name) DO UPDATE SET
        unit        = COALESCE(EXCLUDED.unit, bimba_tag.unit),
        data_type   = COALESCE(EXCLUDED.data_type, bimba_tag.data_type),
        source_path = COALESCE(EXCLUDED.source_path, bimba_tag.source_path);

    WITH ins AS (
        INSERT INTO bimba_measurement
            (tag_id, time, value_num, value_text, quality, message_id, seq, received_via, received_at)
        SELECT t.id, i.ts,
               CASE jsonb_typeof(i.tag_value)
                   WHEN 'number'  THEN (i.tag_value #>> '{}')::double precision
                   WHEN 'boolean' THEN CASE WHEN (i.tag_value #>> '{}')::boolean THEN 1 ELSE 0 END
               END,
               CASE jsonb_typeof(i.tag_value)
                   WHEN 'string'  THEN i.tag_value #>> '{}'
                   WHEN 'boolean' THEN i.tag_value #>> '{}'
                   WHEN 'object'  THEN i.tag_value::text
                   WHEN 'array'   THEN i.tag_value::text
               END,
               i.quality, i.message_id, i.seq, p_via, now()
        FROM _bimba_in i
        JOIN bimba_machine mc ON mc.plant_id = i.plant_id AND mc.machine_id = i.machine_id
        JOIN bimba_tag t ON t.machine_id = mc.id AND t.name = i.tag_name
        ON CONFLICT (message_id) DO NOTHING
        RETURNING tag_id, time, value_num, value_text, quality
    ), latest AS (
        SELECT DISTINCT ON (tag_id) * FROM ins ORDER BY tag_id, time DESC
    ), upd AS (
        UPDATE bimba_tag t
           SET last_value_num = l.value_num, last_value_text = l.value_text,
               last_quality = l.quality, last_time = l.time, last_via = p_via
          FROM latest l
         WHERE t.id = l.tag_id AND (t.last_time IS NULL OR l.time >= t.last_time)
        RETURNING t.id, t.machine_id, t.name, t.unit, l.value_num, l.value_text, l.quality, l.time
    )
    SELECT (SELECT count(*) FROM ins),
           COALESCE(jsonb_agg(jsonb_build_object(
               'tagId', u.id, 'plantId', mc.plant_id, 'machineId', mc.machine_id, 'tag', u.name,
               'unit', u.unit, 'num', u.value_num, 'text', u.value_text, 'quality', u.quality,
               'time', u.time)), '[]'::jsonb)
      INTO v_inserted, v_updates
      FROM upd u JOIN bimba_machine mc ON mc.id = u.machine_id;

    INSERT INTO bimba_ingest_batch
        (batch_id, plant_id, edge_id, via, received_at, message_count, inserted_count, duplicate_count,
         first_seq, last_seq, event_time_min, event_time_max)
    VALUES (CASE WHEN v_is_obj THEN NULLIF(p_batch->>'batchId', '')::uuid END,
            CASE WHEN v_is_obj THEN p_batch->>'plantId' END,
            CASE WHEN v_is_obj THEN p_batch->>'edgeId' END,
            p_via, now(), v_valid, v_inserted, v_valid - v_inserted, v_first, v_last, v_tmin, v_tmax);

    PERFORM pg_notify('bimba_live', jsonb_build_object(
        'type', 'batch', 'via', p_via, 'received', v_valid, 'inserted', v_inserted,
        'duplicates', v_valid - v_inserted, 'firstSeq', v_first, 'lastSeq', v_last,
        'edgeId', CASE WHEN v_is_obj THEN p_batch->>'edgeId' END, 'at', now())::text);

    -- NOTIFY payloads are limited to 8000 bytes: send latest values in chunks of 25
    FOR v_chunk IN
        SELECT jsonb_agg(e) FROM (
            SELECT e, (row_number() OVER () - 1) / 25 AS grp FROM jsonb_array_elements(v_updates) e
        ) s GROUP BY grp
    LOOP
        PERFORM pg_notify('bimba_live', jsonb_build_object('type', 'values', 'items', v_chunk)::text);
    END LOOP;

    RETURN jsonb_build_object('status', 'ok', 'via', p_via, 'received', v_valid, 'inserted', v_inserted,
                              'duplicates', v_valid - v_inserted, 'rejected', v_total - v_valid);
END;
$$;
