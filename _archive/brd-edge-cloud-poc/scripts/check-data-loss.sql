-- Zero-data-loss audit. Run:
--   docker compose exec -T db psql -U scada -d scada -f - < scripts/check-data-loss.sql

\echo '== Per edge session: every message is numbered 1..N at the edge =='
SELECT edge_id, edge_session, first_seq, last_seq, received, missing,
       started, last_received
FROM delivery_gaps ORDER BY started;

\echo '== WAN link events =='
SELECT time, plant_id, state FROM edge_link_events ORDER BY time DESC LIMIT 10;

\echo '== Backlog replay: messages that arrived > 10 s after they were produced =='
SELECT date_trunc('minute', event_time) AS produced_minute,
       count(*) AS late_messages,
       round(max(extract(epoch FROM received_at - event_time))::numeric, 1) AS max_delay_s
FROM delivery_log
WHERE received_at - event_time > interval '10 seconds'
GROUP BY 1 ORDER BY 1;
