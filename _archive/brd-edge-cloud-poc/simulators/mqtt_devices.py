"""Simulated MQTT field devices publishing to the plant (edge) broker.

1. FILLER-01 - bottle filler on packaging line 1, publishes *raw JSON*
   topic: plant01/line1/filler01/telemetry   (every 2 s)

2. PRESS-01 - hydraulic press behind Sparkplug B edge node EN1
   topics: spBv1.0/PLANT01/{NBIRTH,NDEATH}/EN1
           spBv1.0/PLANT01/{DBIRTH,DDATA}/EN1/PRESS-01   (DDATA every 1 s)
   Honors "Node Control/Rebirth" NCMD so a host that missed the BIRTH
   can request one.
"""
import asyncio
import json
import logging
import math
import os
import random
import time
from datetime import datetime, timezone

import aiomqtt

import sparkplug_b_pb2 as spb

logging.basicConfig(level=logging.INFO, format="%(asctime)s mqtt-devices %(message)s")
log = logging.getLogger()

HOST = os.environ.get("MQTT_HOST", "edge-mqtt")
PORT = int(os.environ.get("MQTT_PORT", "1883"))

# Sparkplug B datatypes
UINT64, DOUBLE, BOOLEAN, STRING = 8, 10, 11, 12


def now_ms():
    return int(time.time() * 1000)


# --------------------------------------------------------------- raw JSON filler

async def filler():
    good = bad = 0
    state = "RUNNING"
    state_until = time.time() + random.uniform(120, 300)
    while True:
        try:
            async with aiomqtt.Client(HOST, PORT, identifier="filler01") as client:
                log.info("FILLER-01 connected to %s:%d", HOST, PORT)
                while True:
                    t = time.time()
                    if t > state_until:
                        state = random.choice(["RUNNING", "RUNNING", "IDLE", "BLOCKED"]) if state == "RUNNING" else "RUNNING"
                        state_until = t + (random.uniform(120, 300) if state == "RUNNING" else random.uniform(20, 60))
                        log.info("FILLER-01 state -> %s", state)
                    speed = 0.0 if state != "RUNNING" else 240 + 15 * math.sin(t / 60) + random.gauss(0, 3)
                    made = speed / 60 * 2
                    rejects = made * max(0.0, random.gauss(0.015, 0.006))
                    good += made - rejects
                    bad += rejects
                    msg = {
                        "deviceId": "FILLER-01",
                        "ts": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
                        "data": {
                            "state": state,
                            "speed_bpm": round(speed, 1),
                            "good_count": int(good),
                            "reject_count": int(bad),
                            "fill_volume_ml": round(500 + random.gauss(0, 1.2), 2) if state == "RUNNING" else None,
                        },
                    }
                    await client.publish("plant01/line1/filler01/telemetry", json.dumps(msg), qos=1)
                    await asyncio.sleep(2)
        except aiomqtt.MqttError as exc:
            log.warning("FILLER-01 mqtt error: %s", exc)
            await asyncio.sleep(3)


# --------------------------------------------------------------- Sparkplug B press

GROUP, NODE, DEVICE = "PLANT01", "EN1", "PRESS-01"
T_NBIRTH = f"spBv1.0/{GROUP}/NBIRTH/{NODE}"
T_NDEATH = f"spBv1.0/{GROUP}/NDEATH/{NODE}"
T_NCMD = f"spBv1.0/{GROUP}/NCMD/{NODE}"
T_DBIRTH = f"spBv1.0/{GROUP}/DBIRTH/{NODE}/{DEVICE}"
T_DDATA = f"spBv1.0/{GROUP}/DDATA/{NODE}/{DEVICE}"

# name -> (alias, datatype)
PRESS_METRICS = {
    "Hydraulic/Pressure": (1, DOUBLE),
    "Hydraulic/OilTemp": (2, DOUBLE),
    "Ram/Position": (3, DOUBLE),
    "Cycle/Count": (4, UINT64),
    "Status/Running": (5, BOOLEAN),
    "Status/State": (6, STRING),
}


class Press:
    def __init__(self):
        self.cycles = 0
        self.oil = 45.0
        self.fault_until = 0.0

    def values(self):
        t = time.time()
        if self.fault_until < t and random.random() < 0.002:
            self.fault_until = t + 40
            log.info("PRESS-01 FAULT")
        faulted = t < self.fault_until
        phase = (t % 6) / 6  # 6 s press cycle
        running = not faulted
        pressure = (180 * math.sin(math.pi * phase) + 20 + random.gauss(0, 2)) if running else 5 + random.gauss(0, 0.5)
        position = (250 * math.sin(math.pi * phase)) if running else 0.0
        self.oil += ((58 if running else 40) - self.oil) / 120 + random.gauss(0, 0.05)
        if running and int(t) % 6 == 0:
            self.cycles += 1
        return {
            "Hydraulic/Pressure": round(max(0.0, pressure), 2),
            "Hydraulic/OilTemp": round(self.oil, 2),
            "Ram/Position": round(position, 1),
            "Cycle/Count": self.cycles,
            "Status/Running": running,
            "Status/State": "FAULT" if faulted else "RUNNING",
        }


def set_value(metric, datatype, value):
    metric.datatype = datatype
    if datatype == DOUBLE:
        metric.double_value = float(value)
    elif datatype == UINT64:
        metric.long_value = int(value)
    elif datatype == BOOLEAN:
        metric.boolean_value = bool(value)
    else:
        metric.string_value = str(value)


class SparkplugNode:
    def __init__(self):
        self.seq = 0
        self.bd_seq = 0
        self.press = Press()

    def _payload(self):
        p = spb.Payload()
        p.timestamp = now_ms()
        p.seq = self.seq
        self.seq = (self.seq + 1) % 256
        return p

    def death_payload(self):
        p = spb.Payload()
        p.timestamp = now_ms()
        m = p.metrics.add()
        m.name = "bdSeq"
        set_value(m, UINT64, self.bd_seq)
        return p.SerializeToString()

    async def birth(self, client):
        self.seq = 0
        nb = self._payload()
        m = nb.metrics.add()
        m.name = "bdSeq"
        set_value(m, UINT64, self.bd_seq)
        m = nb.metrics.add()
        m.name = "Node Control/Rebirth"
        set_value(m, BOOLEAN, False)
        await client.publish(T_NBIRTH, nb.SerializeToString(), qos=0)

        db = self._payload()
        for name, value in self.press.values().items():
            alias, dtype = PRESS_METRICS[name]
            m = db.metrics.add()
            m.name, m.alias, m.timestamp = name, alias, db.timestamp
            set_value(m, dtype, value)
        await client.publish(T_DBIRTH, db.SerializeToString(), qos=0)
        log.info("Sparkplug NBIRTH/DBIRTH sent (bdSeq=%d)", self.bd_seq)

    async def data(self, client):
        dd = self._payload()
        for name, value in self.press.values().items():
            alias, dtype = PRESS_METRICS[name]
            m = dd.metrics.add()
            m.alias, m.timestamp = alias, dd.timestamp  # report by alias, as Sparkplug recommends
            set_value(m, dtype, value)
        await client.publish(T_DDATA, dd.SerializeToString(), qos=0)

    async def run(self):
        while True:
            will = aiomqtt.Will(T_NDEATH, self.death_payload(), qos=1)
            try:
                async with aiomqtt.Client(HOST, PORT, identifier="sparkplug-en1", will=will, clean_session=True) as client:
                    await client.subscribe(T_NCMD, qos=0)
                    await self.birth(client)
                    cmd_task = asyncio.create_task(self.commands(client))
                    try:
                        while True:
                            await self.data(client)
                            await asyncio.sleep(1)
                    finally:
                        cmd_task.cancel()
            except aiomqtt.MqttError as exc:
                log.warning("Sparkplug node mqtt error: %s", exc)
                self.bd_seq = (self.bd_seq + 1) % 256
                await asyncio.sleep(3)

    async def commands(self, client):
        async for msg in client.messages:
            p = spb.Payload()
            p.ParseFromString(msg.payload)
            if any(m.name == "Node Control/Rebirth" and m.boolean_value for m in p.metrics):
                log.info("Rebirth requested by host")
                await self.birth(client)


async def main():
    await asyncio.gather(filler(), SparkplugNode().run())


if __name__ == "__main__":
    asyncio.run(main())
