"""Live updates: PostgreSQL LISTEN bimba_live -> Channels group "live" -> WebSocket clients.

bimba_ingest() issues NOTIFY for every batch, so rows written by Boomi and by
Django both reach the React UI the same way.

A pooler in transaction mode (like the shared DB's port 6433) never forwards notifications,
so at startup the listener sends itself a probe NOTIFY. If the probe does not arrive, it polls
bimba_ingest_batch / bimba_tag once a second instead and sends the same events.
LIVE_MODE = auto (default) | listen | poll."""
import asyncio
import json
import logging
import uuid

import psycopg
from psycopg.conninfo import make_conninfo
from channels.db import database_sync_to_async
from channels.generic.websocket import AsyncJsonWebsocketConsumer
from channels.layers import get_channel_layer
from django.conf import settings

log = logging.getLogger("telemetry.live")
GROUP = "live"


def _dsn():
    db = settings.DATABASES["default"]
    # make_conninfo quotes values, so passwords with spaces or quotes work
    return make_conninfo(host=db["HOST"], port=db["PORT"], dbname=db["NAME"],
                         user=db["USER"], password=db["PASSWORD"])


async def _listen_works():
    """True when a NOTIFY sent on one connection reaches a LISTEN on another."""
    channel = f"bimba_probe_{uuid.uuid4().hex[:8]}"
    async with await psycopg.AsyncConnection.connect(_dsn(), autocommit=True) as listener,             await psycopg.AsyncConnection.connect(_dsn(), autocommit=True) as sender:
        await listener.execute(f"LISTEN {channel}")
        await sender.execute("SELECT pg_notify(%s, 'probe')", [channel])
        async for _ in listener.notifies(timeout=3, stop_after=1):
            return True
    return False


async def _send(layer, event):
    await layer.group_send(GROUP, {"type": "live.event", "payload": json.dumps(event, default=str)})


async def pg_poller():
    """Same events as bimba_ingest()'s NOTIFY, from polling (works through any pooler)."""
    layer = get_channel_layer()
    schema = settings.DB_SCHEMA
    q_tags = (f'SELECT t.id, m.plant_id, m.machine_id, t.name, t.unit, t.last_value_num, t.last_value_text, '
              f't.last_quality, t.last_time FROM "{schema}".bimba_tag t JOIN "{schema}".bimba_machine m ON m.id = t.machine_id')
    q_batches = (f'SELECT id, via, message_count, inserted_count, duplicate_count, first_seq, last_seq, edge_id, received_at '
                 f'FROM "{schema}".bimba_ingest_batch WHERE id > %s ORDER BY id LIMIT 500')
    seen, last_batch = {}, None
    log.info("live updates by polling every %.1f s", settings.LIVE_POLL_SECONDS)
    while True:
        try:
            async with await psycopg.AsyncConnection.connect(_dsn(), autocommit=True) as conn:
                if last_batch is None:
                    cur = await conn.execute(f'SELECT COALESCE(max(id), 0) FROM "{schema}".bimba_ingest_batch')
                    last_batch = (await cur.fetchone())[0]
                while True:
                    for (bid, via, count, inserted, dups, first, last, edge, at) in await (await conn.execute(q_batches, [last_batch])).fetchall():
                        last_batch = bid
                        await _send(layer, {"type": "batch", "via": via, "received": count, "inserted": inserted,
                                            "duplicates": dups, "firstSeq": first, "lastSeq": last, "edgeId": edge,
                                            "at": at.isoformat()})
                    changed = []
                    for (tid, plant, machine, name, unit, num, text, quality, time) in await (await conn.execute(q_tags)).fetchall():
                        sig = (num, text, quality, time)
                        if tid in seen and seen[tid] != sig:
                            changed.append({"tagId": tid, "plantId": plant, "machineId": machine, "tag": name, "unit": unit,
                                            "num": num, "text": text, "quality": quality,
                                            "time": time.isoformat() if time else None})
                        seen[tid] = sig
                    for i in range(0, len(changed), 25):
                        await _send(layer, {"type": "values", "items": changed[i:i + 25]})
                    await asyncio.sleep(settings.LIVE_POLL_SECONDS)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - keep polling alive
            log.warning("live poller error: %s (retrying)", exc)
            await asyncio.sleep(3)


async def pg_listener():
    mode = settings.LIVE_MODE
    while mode == "auto":
        try:
            mode = "listen" if await _listen_works() else "poll"
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - database not reachable yet
            log.warning("LISTEN self-test failed: %s (retrying)", exc)
            await asyncio.sleep(3)
    if mode == "poll":
        if settings.LIVE_MODE == "auto":
            log.warning("NOTIFY does not reach LISTEN on this database (transaction pooler?); falling back to polling")
        return await pg_poller()

    layer = get_channel_layer()
    while True:
        try:
            async with await psycopg.AsyncConnection.connect(_dsn(), autocommit=True) as conn:
                await conn.execute("LISTEN bimba_live")
                log.info("listening on bimba_live")
                async for note in conn.notifies():
                    await layer.group_send(GROUP, {"type": "live.event", "payload": note.payload})
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - keep the listener alive
            log.warning("pg listener error: %s (reconnecting)", exc)
            await asyncio.sleep(3)


class LiveConsumer(AsyncJsonWebsocketConsumer):
    async def connect(self):
        await self.channel_layer.group_add(GROUP, self.channel_name)
        await self.accept()
        from .views import machines_snapshot
        snapshot = await database_sync_to_async(machines_snapshot)()
        await self.send_json({"type": "snapshot", "machines": snapshot})

    async def disconnect(self, code):
        await self.channel_layer.group_discard(GROUP, self.channel_name)

    async def live_event(self, event):
        await self.send(text_data=event["payload"])

    async def receive_json(self, content, **kwargs):
        if content.get("type") == "ping":
            await self.send_json({"type": "pong"})
