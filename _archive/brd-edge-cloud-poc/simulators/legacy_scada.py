"""Legacy SCADA system for the boiler house.

* Historian snapshot as XML over HTTP (the "legacy SCADA XML" payload):
      GET http://legacy-scada:8080/historian/snapshot.xml
* Operator action journal in its SQL database (read over JDBC / SQL):
      legacy-scada-db:5432/legacy  table operator_log
"""
import asyncio
import json
import logging
import math
import os
import random
import time
from datetime import datetime, timezone
from xml.sax.saxutils import escape

import asyncpg
from aiohttp import web

logging.basicConfig(level=logging.INFO, format="%(asctime)s legacy-scada %(message)s")
log = logging.getLogger()

DSN = os.environ.get("LEGACY_DB_DSN", "postgresql://scada:scada@legacy-scada-db:5432/legacy")

POINTS = {  # id -> (description, units)
    "BLR1.STEAM_PRESS": ("Boiler 1 steam pressure", "bar"),
    "BLR1.STEAM_TEMP": ("Boiler 1 steam temperature", "degC"),
    "BLR1.DRUM_LEVEL": ("Boiler 1 drum level", "%"),
    "BLR1.FUEL_FLOW": ("Boiler 1 gas flow", "Nm3/h"),
    "BLR1.BURNER_ON": ("Boiler 1 burner on", ""),
    "BLR1.STEAM_SP": ("Boiler 1 pressure setpoint", "bar"),
}

OPERATORS = [("OP1001", "A. Rao"), ("OP1002", "M. Singh"), ("OP1003", "K. Iyer"),
             ("OP1004", "S. Patel"), ("OP1005", "J. Thomas"), ("OP1006", "P. Nair")]


def shift_of(dt):
    h = dt.hour
    return "A" if 6 <= h < 14 else ("B" if 14 <= h < 22 else "C")


class Boiler:
    def __init__(self):
        self.sp = 10.0
        self.press = 9.5
        self.burner = True

    def step(self):
        if self.press > self.sp + 0.4:
            self.burner = False
        elif self.press < self.sp - 0.4:
            self.burner = True
        self.press += (0.05 if self.burner else -0.04) + random.gauss(0, 0.01)
        return {
            "BLR1.STEAM_PRESS": round(self.press, 3),
            "BLR1.STEAM_TEMP": round(170 + (self.press - 10) * 4 + random.gauss(0, 0.3), 2),
            "BLR1.DRUM_LEVEL": round(50 + 5 * math.sin(time.time() / 90) + random.gauss(0, 0.4), 2),
            "BLR1.FUEL_FLOW": round((310 + random.gauss(0, 5)) if self.burner else 25 + random.gauss(0, 1), 1),
            "BLR1.BURNER_ON": int(self.burner),
            "BLR1.STEAM_SP": self.sp,
        }


boiler = Boiler()
snapshot = {}


async def simulate():
    global snapshot
    while True:
        snapshot = boiler.step()
        await asyncio.sleep(1)


async def snapshot_xml(_request):
    ts = datetime.now(timezone.utc).isoformat(timespec="seconds")
    lines = [f'<?xml version="1.0" encoding="UTF-8"?>',
             f'<ScadaSnapshot system="BH-SCADA" station="BOILER-HOUSE" time="{ts}">']
    for pid, value in snapshot.items():
        desc, units = POINTS[pid]
        quality = "GOOD" if random.random() > 0.002 else "UNCERTAIN"
        lines.append(f'  <Point id="{pid}" desc="{escape(desc)}" units="{units}" quality="{quality}">{value}</Point>')
    lines.append("</ScadaSnapshot>")
    return web.Response(text="\n".join(lines), content_type="application/xml")


async def operator_journal(pool):
    await pool.execute("""
        CREATE TABLE IF NOT EXISTS operator_log (
            id            SERIAL PRIMARY KEY,
            ts            TIMESTAMPTZ NOT NULL DEFAULT now(),
            operator_id   TEXT NOT NULL,
            operator_name TEXT NOT NULL,
            shift         TEXT NOT NULL,
            action        TEXT NOT NULL,
            object        TEXT,
            details       JSONB
        )""")
    on_duty = random.choice(OPERATORS)
    await log_action(pool, on_duty, "LOGIN", "HMI-BH-01", {"station": "HMI-BH-01"})
    current_shift = shift_of(datetime.now())
    while True:
        await asyncio.sleep(random.uniform(15, 45))
        now = datetime.now()
        if shift_of(now) != current_shift:
            nxt = random.choice([o for o in OPERATORS if o != on_duty])
            await log_action(pool, on_duty, "SHIFT_HANDOVER", "HMI-BH-01", {"handed_to": nxt[0]})
            await log_action(pool, on_duty, "LOGOUT", "HMI-BH-01", {})
            on_duty, current_shift = nxt, shift_of(now)
            await log_action(pool, on_duty, "LOGIN", "HMI-BH-01", {"station": "HMI-BH-01"})
            continue
        action = random.choices(["ALARM_ACK", "SETPOINT_CHANGE", "MODE_CHANGE", "ROUND_CHECK", "COMMENT"],
                                weights=[5, 2, 1, 3, 1])[0]
        if action == "SETPOINT_CHANGE":
            old = boiler.sp
            boiler.sp = round(min(11.5, max(8.5, old + random.choice([-0.5, 0.5]))), 1)
            await log_action(pool, on_duty, action, "BLR1.STEAM_SP", {"old": old, "new": boiler.sp, "units": "bar"})
        elif action == "ALARM_ACK":
            await log_action(pool, on_duty, action, random.choice(list(POINTS)), {"alarm": "HI", "comment": "acknowledged"})
        elif action == "MODE_CHANGE":
            await log_action(pool, on_duty, action, "BLR1", {"from": "AUTO", "to": "AUTO", "reason": "mode verified"})
        elif action == "ROUND_CHECK":
            await log_action(pool, on_duty, action, "BOILER-HOUSE", {"result": "OK"})
        else:
            await log_action(pool, on_duty, action, "BLR1", {"text": "Burner flame stable"})


async def log_action(pool, operator, action, obj, details):
    now = datetime.now(timezone.utc)
    await pool.execute(
        "INSERT INTO operator_log (ts, operator_id, operator_name, shift, action, object, details) "
        "VALUES ($1, $2, $3, $4, $5, $6, $7)",
        now, operator[0], operator[1], shift_of(datetime.now()), action, obj, json.dumps(details))
    log.info("operator %s %s %s", operator[0], action, obj)


async def connect_db():
    while True:
        try:
            return await asyncpg.create_pool(DSN, min_size=1, max_size=2)
        except Exception as exc:
            log.info("waiting for legacy db: %s", exc)
            await asyncio.sleep(2)


async def main():
    pool = await connect_db()
    asyncio.create_task(simulate())
    asyncio.create_task(operator_journal(pool))
    app = web.Application()
    app.router.add_get("/historian/snapshot.xml", snapshot_xml)
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", 8080).start()
    log.info("legacy SCADA XML on :8080/historian/snapshot.xml")
    await asyncio.Event().wait()


if __name__ == "__main__":
    asyncio.run(main())
