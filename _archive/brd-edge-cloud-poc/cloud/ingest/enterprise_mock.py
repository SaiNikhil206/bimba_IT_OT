"""Mock enterprise targets for the Boomi processes (ERP, HRM, data lake, ...).

Any POST to http://enterprise-mock:8090/<target>/<path> is accepted and stored
in the enterprise_inbox table, so the dashboard can show what Boomi delivered.
  e.g. POST /erp/production-status   (equipment status -> SAP/Oracle)
       POST /hrm/shift-events        (operator logs -> HRM)
       POST /datalake/telemetry      (telemetry batches)
GET /inbox?limit=50 lists the latest deliveries.
"""
import asyncio
import json
import logging
import os

import asyncpg
from aiohttp import web

logging.basicConfig(level=logging.INFO, format="%(asctime)s enterprise-mock %(message)s")
log = logging.getLogger()
DSN = os.environ.get("DB_DSN", "postgresql://scada:scada@db:5432/scada")


async def receive(request):
    target = request.match_info["target"]
    body = await request.read()
    try:
        payload = json.loads(body) if body else None
    except ValueError:
        payload = {"raw": body.decode("utf-8", "replace")}
    await request.app["pool"].execute(
        "INSERT INTO enterprise_inbox (target, endpoint, payload) VALUES ($1, $2, $3::jsonb)",
        target, request.path, json.dumps(payload))
    log.info("%s <- %d bytes on %s", target.upper(), len(body), request.path)
    return web.json_response({"status": "accepted", "target": target})


async def inbox(request):
    limit = min(int(request.query.get("limit", 50)), 500)
    rows = await request.app["pool"].fetch(
        "SELECT id, received_at, target, endpoint, payload FROM enterprise_inbox ORDER BY id DESC LIMIT $1", limit)
    return web.json_response([{**dict(r), "received_at": r["received_at"].isoformat(),
                               "payload": json.loads(r["payload"]) if r["payload"] else None} for r in rows])


async def main():
    while True:
        try:
            pool = await asyncpg.create_pool(DSN, min_size=1, max_size=2)
            break
        except Exception as exc:
            log.info("waiting for database: %s", exc)
            await asyncio.sleep(2)
    app = web.Application(client_max_size=20 * 1024 * 1024)
    app["pool"] = pool
    app.router.add_get("/inbox", inbox)
    app.router.add_post("/{target}/{path:.*}", receive)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", 8090).start()
    log.info("mock enterprise endpoints on :8090")
    await asyncio.Event().wait()


if __name__ == "__main__":
    asyncio.run(main())
