"""BIMBA cloud ingest: canonical MQTT messages -> TimescaleDB.

Subscribes to bimba/v1/# on the cloud broker with a persistent session (QoS 1),
so messages queue at the broker while this service is down. Writes are
idempotent (keyed on messageId), which makes at-least-once delivery safe.
"""
import asyncio
import json
import logging
import os
import time
import uuid
from datetime import datetime, timezone

import aiomqtt
import asyncpg

logging.basicConfig(level=logging.INFO, format="%(asctime)s ingest %(levelname)s %(message)s")
log = logging.getLogger("ingest")

MQTT_HOST = os.environ.get("MQTT_HOST", "cloud-mqtt")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "1883"))
TOPIC = os.environ.get("MQTT_TOPIC", "bimba/v1/#")
DSN = os.environ.get("DB_DSN", "postgresql://scada:scada@db:5432/scada")
BATCH, FLUSH_S = 1000, 1.0


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def numeric(v):
    return float(v) if isinstance(v, (bool, int, float)) else None


class Writer:
    def __init__(self, pool):
        self.pool = pool
        self.count = 0
        self.last_report = time.monotonic()

    async def write(self, batch):
        delivery, meas, tags, alarms, oplogs, sources, links = [], [], {}, [], [], {}, []
        for topic, raw in batch:
            if topic.startswith("bimba/v1/_bridge/"):
                plant = topic.split("/")[3]
                links.append((plant, "UP" if raw.strip() == b"1" else "DOWN"))
                continue
            try:
                m = json.loads(raw)
                t = ts(m["timestamp"])
                mid = uuid.UUID(m["messageId"])
                plant, machine, tag, cat = m["plantId"], m["machineId"], m["tagName"], m["category"]
                val = m.get("tagValue")
            except (ValueError, KeyError, TypeError) as exc:
                log.warning("rejected message on %s: %s", topic, exc)
                continue
            edge = m.get("edge") or {}
            if edge.get("session") and edge.get("seq") is not None:
                delivery.append((t, plant, edge.get("edgeId", "?"), uuid.UUID(edge["session"]), edge["seq"], cat, mid))

            src = m.get("source") or {}
            if cat in ("telemetry", "equipment_status"):
                num = numeric(val)
                if isinstance(val, bool):
                    text = str(val).lower()
                elif num is None and val is not None:
                    text = str(val)
                else:
                    text = None
                meas.append((t, plant, machine, tag, num, text, m.get("quality", "GOOD"), cat, mid))
                tags[(plant, machine, tag)] = (plant, machine, tag, m.get("unit"), cat, m.get("dataType"),
                                               src.get("protocol"), src.get("system"))
            elif cat == "alarm" and isinstance(val, dict):
                alarms.append((t, plant, machine, tag, val.get("kind"), val.get("state"),
                               None if val.get("value") is None else str(val["value"]),
                               None if val.get("limit") is None else str(val["limit"]), val.get("message"), mid))
            elif cat == "operator_log" and isinstance(val, dict):
                oplogs.append((t, plant, machine, val.get("id"), val.get("operator_id"), val.get("operator_name"),
                               val.get("shift"), val.get("action"), val.get("object"),
                               json.dumps(val.get("details")), mid))
            elif cat == "status" and isinstance(val, dict):
                sources[(plant, val.get("source"))] = (
                    plant, val.get("source"), machine, src.get("protocol"), val.get("endpoint"), val.get("status"),
                    ts(val["lastOk"]) if val.get("lastOk") else None, val.get("error"), t)

        async with self.pool.acquire() as con, con.transaction():
            if delivery:
                await con.executemany(
                    "INSERT INTO delivery_log (event_time, plant_id, edge_id, edge_session, seq, category, message_id) "
                    "VALUES ($1,$2,$3,$4,$5,$6,$7) ON CONFLICT DO NOTHING", delivery)
            if tags:
                await con.executemany(
                    "INSERT INTO tags (plant_id, machine_id, tag, unit, category, data_type, protocol, source) "
                    "VALUES ($1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT (plant_id, machine_id, tag) DO UPDATE SET "
                    "unit=EXCLUDED.unit, category=EXCLUDED.category, data_type=EXCLUDED.data_type, "
                    "protocol=EXCLUDED.protocol, source=EXCLUDED.source", list(tags.values()))
            if meas:
                await con.executemany(
                    "INSERT INTO measurements (time, plant_id, machine_id, tag, value, value_text, quality, category, "
                    "message_id) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) ON CONFLICT DO NOTHING", meas)
            if alarms:
                await con.executemany(
                    "INSERT INTO alarms (time, plant_id, machine_id, tag, kind, state, value, limit_value, message, "
                    "message_id) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) ON CONFLICT DO NOTHING", alarms)
            if oplogs:
                await con.executemany(
                    "INSERT INTO operator_logs (time, plant_id, machine_id, log_id, operator_id, operator_name, shift, "
                    "action, object, details, message_id) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10::jsonb,$11) "
                    "ON CONFLICT DO NOTHING", oplogs)
            if sources:
                await con.executemany(
                    "INSERT INTO sources (plant_id, name, machine_id, protocol, endpoint, status, last_ok, last_error, "
                    "updated_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) ON CONFLICT (plant_id, name) DO UPDATE SET "
                    "machine_id=EXCLUDED.machine_id, protocol=EXCLUDED.protocol, endpoint=EXCLUDED.endpoint, "
                    "status=EXCLUDED.status, last_ok=COALESCE(EXCLUDED.last_ok, sources.last_ok), "
                    "last_error=EXCLUDED.last_error, updated_at=EXCLUDED.updated_at "
                    "WHERE sources.updated_at <= EXCLUDED.updated_at", list(sources.values()))
            for plant, state in links:
                await con.execute(
                    "INSERT INTO edge_links (plant_id, state, changed_at) VALUES ($1,$2,now()) "
                    "ON CONFLICT (plant_id) DO UPDATE SET state=EXCLUDED.state, changed_at=now()", plant, state)
                await con.execute("INSERT INTO edge_link_events (plant_id, state) VALUES ($1,$2)", plant, state)
                log.info("WAN bridge %s is %s", plant, state)

        self.count += len(batch)
        if time.monotonic() - self.last_report > 30:
            log.info("ingested %d messages in last %.0fs", self.count, time.monotonic() - self.last_report)
            self.count, self.last_report = 0, time.monotonic()


async def connect_db():
    for attempt in range(60):
        try:
            return await asyncpg.create_pool(DSN, min_size=1, max_size=4)
        except Exception as exc:
            log.info("waiting for database: %s", exc)
            await asyncio.sleep(2)
    raise SystemExit("database not reachable")


async def writer_loop(queue, writer):
    while True:
        batch = [await queue.get()]
        deadline = time.monotonic() + FLUSH_S
        while len(batch) < BATCH:
            try:
                batch.append(await asyncio.wait_for(queue.get(), timeout=max(0.0, deadline - time.monotonic())))
            except asyncio.TimeoutError:
                break
        while True:
            try:
                await writer.write(batch)
                break
            except (asyncpg.PostgresConnectionError, OSError) as exc:
                log.warning("db write failed, retrying: %s", exc)
                await asyncio.sleep(2)


async def main():
    pool = await connect_db()
    writer = Writer(pool)
    queue = asyncio.Queue(maxsize=50_000)
    asyncio.create_task(writer_loop(queue, writer))
    while True:
        try:
            async with aiomqtt.Client(MQTT_HOST, MQTT_PORT, identifier="bimba-cloud-ingest",
                                      clean_session=False, keepalive=30) as client:
                await client.subscribe(TOPIC, qos=1)
                log.info("subscribed to %s on %s:%d", TOPIC, MQTT_HOST, MQTT_PORT)
                async for msg in client.messages:
                    await queue.put((msg.topic.value, msg.payload))
        except aiomqtt.MqttError as exc:
            log.warning("mqtt connection lost: %s", exc)
            await asyncio.sleep(3)


if __name__ == "__main__":
    asyncio.run(main())
