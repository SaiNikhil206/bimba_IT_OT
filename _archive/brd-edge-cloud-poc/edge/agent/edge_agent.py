"""BIMBA edge agent (Model B: hybrid edge-to-cloud).

Extracts OT data (FR-1) from Modbus/SCADA, OPC UA, legacy SCADA XML, SCADA SQL
databases, raw-JSON MQTT and Sparkplug B, maps every value to the canonical
JSON schema (FR-3), evaluates alarm limits, and publishes to the local edge
broker. The broker then does store-and-forward to the cloud over TLS (NFR-1/2).
"""
import asyncio
import fnmatch
import json
import logging
import os
import re
import time
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

import aiohttp
import aiomqtt
import asyncpg
import yaml
from asyncua import Client as OpcUaClient
from pymodbus.client import AsyncModbusTcpClient

import sparkplug_b_pb2 as spb

logging.basicConfig(level=logging.INFO, format="%(asctime)s edge-agent %(levelname)s %(message)s")
for noisy in ("asyncua", "pymodbus"):
    logging.getLogger(noisy).setLevel(logging.WARNING)
log = logging.getLogger("edge-agent")

OID_NAMESPACE = uuid.UUID("6f1c2a52-8a0c-4b7e-9d8e-b1b1ba0e0001")


def utcnow():
    return datetime.now(timezone.utc)


def iso(ts: datetime):
    return ts.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def snake(name: str):
    return re.sub(r"[^0-9a-zA-Z]+", "_", name).strip("_").lower()


# ================================================================ state / canonical

class State:
    """Small JSON file for things that must survive an agent restart."""

    def __init__(self, path):
        self.path = path
        try:
            with open(path, encoding="utf-8") as fh:
                self.data = json.load(fh)
        except (FileNotFoundError, json.JSONDecodeError):
            self.data = {}

    def get(self, key, default=None):
        return self.data.get(key, default)

    def set(self, key, value):
        self.data[key] = value
        tmp = self.path + ".tmp"
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(self.data, fh)
        os.replace(tmp, self.path)


class Canonical:
    """Builds canonical BIMBA messages and hands them to the publisher."""

    def __init__(self, cfg, bus):
        self.plant = cfg["plant_id"]
        self.edge = cfg["edge_id"]
        self.prefix = cfg.get("topic_prefix", "bimba/v1")
        self.session = str(uuid.uuid4())
        self.seq = 0
        self.bus = bus

    def emit(self, *, machine, tag, value, ts=None, category="telemetry", unit=None,
             quality="GOOD", protocol, system, address=None, message_id=None):
        self.seq += 1
        if isinstance(value, bool):
            dtype = "boolean"
        elif isinstance(value, (int, float)):
            dtype = "number"
        elif isinstance(value, (dict, list)):
            dtype = "object"
        elif value is None:
            dtype = "null"
        else:
            dtype = "string"
        msg = {
            "schemaVersion": "1.0",
            "messageId": message_id or str(uuid.uuid4()),
            "plantId": self.plant,
            "machineId": machine,
            "timestamp": iso(ts or utcnow()),
            "tagName": tag,
            "tagValue": value,
            "dataType": dtype,
            "unit": unit,
            "quality": quality,
            "category": category,
            "source": {"protocol": protocol, "system": system, "address": address},
            "edge": {"edgeId": self.edge, "session": self.session, "seq": self.seq},
        }
        topic = f"{self.prefix}/{self.plant}/{category}/{machine}/{tag}"
        self.bus.publish(topic, json.dumps(msg, separators=(",", ":")))


# ================================================================ alarms

class AlarmEngine:
    def __init__(self, canonical, state):
        self.canonical = canonical
        self.state = state
        self.active = {tuple(k) for k in state.get("active_alarms", [])}

    def evaluate(self, *, machine, tag, value, cfg, ts, protocol, system):
        if not cfg or value is None:
            return
        db = cfg.get("deadband", 0)

        def transition(kind, bad, good, limit, message):
            key = (machine, tag, kind)
            if key not in self.active and bad:
                self.active.add(key)
                self._emit(key, "ACTIVE", value, limit, message, ts, protocol, system)
            elif key in self.active and good:
                self.active.discard(key)
                self._emit(key, "CLEARED", value, limit, message, ts, protocol, system)

        label = f"{machine} {tag}"
        numeric = isinstance(value, (int, float)) and not isinstance(value, bool)
        v = float(value) if numeric or isinstance(value, bool) else value
        if numeric and "high" in cfg:
            transition("HIGH", v > cfg["high"], v <= cfg["high"] - db, cfg["high"], f"{label} high")
        if numeric and "low" in cfg:
            transition("LOW", v < cfg["low"], v >= cfg["low"] + db, cfg["low"], f"{label} low")
        if "on_value" in cfg:
            on = cfg["on_value"]
            hit = (v == float(on)) if isinstance(on, (int, float)) and isinstance(v, float) else str(value) == str(on)
            transition("STATE", hit, not hit, on, cfg.get("message", f"{label} alarm"))

    def _emit(self, key, state, value, limit, message, ts, protocol, system):
        machine, tag, kind = key
        log.info("ALARM %-7s %-5s %s value=%s", state, kind, message, value)
        self.canonical.emit(machine=machine, tag=tag, category="alarm", ts=ts, protocol=protocol, system=system,
                            value={"kind": kind, "state": state, "value": value, "limit": limit, "message": message})
        self.state.set("active_alarms", sorted(list(k) for k in self.active))


# ================================================================ comm status

class StatusBoard:
    """Publishes per-source communication status on change + heartbeat."""

    HEARTBEAT_S = 30

    def __init__(self, canonical):
        self.canonical = canonical
        self.sources = {}  # name -> dict

    def register(self, name, machine, protocol, endpoint):
        self.sources[name] = {"machine": machine, "protocol": protocol, "endpoint": endpoint,
                              "online": None, "error": None, "last_ok": None, "sent": 0.0}

    def update(self, name, ok, error=None):
        s = self.sources[name]
        if ok:
            s["last_ok"] = utcnow()
        changed = s["online"] != ok
        s["online"], s["error"] = ok, (None if ok else error)
        if changed:
            (log.info if ok else log.warning)("%s %s%s", name, "online" if ok else "OFFLINE",
                                               "" if ok else f": {error}")
        if changed or time.monotonic() - s["sent"] > self.HEARTBEAT_S:
            s["sent"] = time.monotonic()
            self.canonical.emit(
                machine=s["machine"], tag="comm_status", category="status", protocol=s["protocol"], system=name,
                quality="GOOD" if ok else "BAD",
                value={"source": name, "status": "online" if ok else "offline", "endpoint": s["endpoint"],
                       "lastOk": iso(s["last_ok"]) if s["last_ok"] else None, "error": s["error"]})


# ================================================================ MQTT bus (edge broker)

class EdgeBus:
    """One connection to the edge broker for subscriptions and canonical publishes."""

    def __init__(self, cfg):
        self.host, self.port = cfg["host"], cfg.get("port", 1883)
        self.client_id = cfg.get("client_id", "bimba-edge-agent")
        self.queue = asyncio.Queue(maxsize=cfg.get("max_buffer", 500_000))
        self.handlers = []  # (topic filter, coroutine)
        self.client = None
        self._inflight = None
        self.dropped = 0

    def subscribe(self, topic_filter, handler):
        self.handlers.append((topic_filter, handler))

    def publish(self, topic, payload):
        try:
            self.queue.put_nowait((topic, payload))
        except asyncio.QueueFull:
            self.dropped += 1

    async def raw_publish(self, topic, payload, qos=0):
        if self.client is not None:
            await self.client.publish(topic, payload, qos=qos)

    async def run(self):
        while True:
            try:
                async with aiomqtt.Client(self.host, self.port, identifier=self.client_id,
                                          keepalive=20, clean_session=True) as client:
                    self.client = client
                    log.info("connected to edge broker %s:%d", self.host, self.port)
                    for flt, _ in self.handlers:
                        await client.subscribe(flt, qos=1)
                    drain = asyncio.create_task(self._drain(client))
                    try:
                        async for msg in client.messages:
                            for flt, handler in self.handlers:
                                if msg.topic.matches(flt):
                                    try:
                                        await handler(msg)
                                    except Exception:
                                        log.exception("handler for %s failed", msg.topic.value)
                    finally:
                        drain.cancel()
            except aiomqtt.MqttError as exc:
                log.warning("edge broker connection lost: %s (buffered=%d)", exc, self.queue.qsize())
            self.client = None
            await asyncio.sleep(2)

    async def _drain(self, client):
        while True:
            if self._inflight is None:
                self._inflight = await self.queue.get()
            topic, payload = self._inflight
            await client.publish(topic, payload, qos=1)
            self._inflight = None


# ================================================================ polled sources

class PolledSource:
    protocol = "?"

    def __init__(self, cfg, defaults):
        self.cfg = cfg
        self.name = cfg["name"]
        self.machine = cfg.get("machine_id", self.name)
        self.interval = float(cfg.get("poll_interval_s", defaults.get("poll_interval_s", 2)))
        self.tags = cfg.get("tags", [])

    endpoint = ""

    async def read(self):
        """Return list of (tag_cfg, value, ts, quality, address)."""
        raise NotImplementedError

    async def reset(self):
        pass


class ModbusSource(PolledSource):
    protocol = "modbus"

    def __init__(self, cfg, defaults):
        super().__init__(cfg, defaults)
        self.host, self.port, self.unit = cfg["host"], cfg.get("port", 502), cfg.get("unit_id", 1)
        self.endpoint = f"modbus://{self.host}:{self.port}/{self.unit}"
        self.client = None

    async def read(self):
        if self.client is None or not self.client.connected:
            self.client = AsyncModbusTcpClient(self.host, port=self.port, timeout=3)
            if not await self.client.connect():
                raise ConnectionError(f"cannot connect to {self.endpoint}")
        ts, out = utcnow(), []
        for tag in self.tags:
            addr, kind = tag["address"], tag.get("type", "holding")
            fn = {"coil": self.client.read_coils, "discrete": self.client.read_discrete_inputs,
                  "input": self.client.read_input_registers}.get(kind, self.client.read_holding_registers)
            rr = await fn(addr, 1, slave=self.unit)
            if rr.isError():
                raise IOError(f"{tag['name']}: {rr}")
            if kind in ("coil", "discrete"):
                value = bool(rr.bits[0])
            else:
                value = round(rr.registers[0] * tag.get("scale", 1) + tag.get("offset", 0), 4)
            prefix = {"coil": "C", "discrete": "DI", "input": "IR"}.get(kind, "HR")
            out.append((tag, value, ts, "GOOD", f"{prefix}{addr}"))
        return out

    async def reset(self):
        if self.client is not None:
            self.client.close()
        self.client = None


class OpcUaSource(PolledSource):
    protocol = "opcua"

    def __init__(self, cfg, defaults):
        super().__init__(cfg, defaults)
        self.endpoint = cfg["url"]
        self.client = None
        self.nodes = []

    async def read(self):
        if self.client is None:
            client = OpcUaClient(self.endpoint, timeout=4)
            await client.connect()
            self.client = client
            self.nodes = [client.get_node(t["node"]) for t in self.tags]
        raw = await self.client.read_values(self.nodes)
        ts = utcnow()
        return [(t, v, ts, "GOOD", t["node"]) for t, v in zip(self.tags, raw)]

    async def reset(self):
        if self.client is not None:
            try:
                await self.client.disconnect()
            except Exception:
                pass
        self.client = None


class ScadaXmlSource(PolledSource):
    """Legacy SCADA historian snapshot served as XML over HTTP."""
    protocol = "scada_xml"

    def __init__(self, cfg, defaults):
        super().__init__(cfg, defaults)
        self.endpoint = cfg["url"]
        self.by_point = {t["point"]: t for t in self.tags}
        self.session = None

    async def read(self):
        if self.session is None:
            self.session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=5))
        async with self.session.get(self.endpoint) as resp:
            resp.raise_for_status()
            root = ET.fromstring(await resp.text())
        ts = datetime.fromisoformat(root.get("time")) if root.get("time") else utcnow()
        out = []
        for point in root.iter("Point"):
            tag = self.by_point.get(point.get("id"))
            if tag is None:
                continue
            text = (point.text or "").strip()
            try:
                value = float(text)
            except ValueError:
                value = text
            out.append((tag, value, ts, point.get("quality", "GOOD").upper(), point.get("id")))
        return out

    async def reset(self):
        if self.session is not None:
            await self.session.close()
        self.session = None


class SqlSource(PolledSource):
    """Incremental SQL poll (the Python equivalent of a JDBC poller)."""
    protocol = "sql"

    def __init__(self, cfg, defaults, state):
        super().__init__(cfg, defaults)
        self.dsn = cfg["dsn"]
        self.endpoint = re.sub(r"//[^@]*@", "//", self.dsn)  # hide credentials
        self.state = state
        self.key = cfg.get("key_column", "id")
        self.time_col = cfg.get("time_column")
        self.last = state.get("sql_last_keys", {}).get(self.name, 0)
        self.conn = None
        self.tag_cfg = {"name": cfg.get("tag_name", "record"), "category": cfg.get("category", "telemetry")}

    async def read(self):
        if self.conn is None or self.conn.is_closed():
            self.conn = await asyncpg.connect(self.dsn, timeout=5)
        rows = await self.conn.fetch(self.cfg["query"], self.last)
        out = []
        for r in rows:
            rec = {k: (json.loads(v) if k == "details" and isinstance(v, str) else v) for k, v in dict(r).items()}
            ts = rec.get(self.time_col) if self.time_col else utcnow()
            payload = {k: (iso(v) if isinstance(v, datetime) else v) for k, v in rec.items()}
            # deterministic id -> the cloud side can de-duplicate replays
            mid = str(uuid.uuid5(OID_NAMESPACE, f"{self.name}:{rec[self.key]}"))
            out.append((self.tag_cfg, payload, ts, "GOOD", f"{self.key}={rec[self.key]}", mid))
            self.last = rec[self.key]
        if rows:
            keys = self.state.get("sql_last_keys", {})
            keys[self.name] = self.last
            self.state.set("sql_last_keys", keys)
        return out

    async def reset(self):
        if self.conn is not None:
            try:
                await self.conn.close()
            except Exception:
                pass
        self.conn = None


async def poll_forever(src, ctx):
    canonical, alarms, status = ctx
    while True:
        started = time.monotonic()
        try:
            readings = await asyncio.wait_for(src.read(), timeout=max(5.0, src.interval * 3))
            for item in readings:
                tag, value, ts, quality, address = item[:5]
                machine = tag.get("machine_id", src.machine)
                canonical.emit(machine=machine, tag=tag["name"], value=value, ts=ts,
                               category=tag.get("category", "telemetry"), unit=tag.get("unit"),
                               quality=quality, protocol=src.protocol, system=src.name, address=address,
                               message_id=item[5] if len(item) > 5 else None)
                alarms.evaluate(machine=machine, tag=tag["name"], value=value, cfg=tag.get("alarm"),
                                ts=ts, protocol=src.protocol, system=src.name)
            status.update(src.name, True)
        except Exception as exc:
            status.update(src.name, False, f"{type(exc).__name__}: {exc}"[:300])
            await src.reset()
        await asyncio.sleep(max(0.0, src.interval - (time.monotonic() - started)))


# ================================================================ subscribed sources

def dig(obj, path):
    for part in path.split("."):
        if not isinstance(obj, dict):
            return None
        obj = obj.get(part)
    return obj


class MqttJsonSource:
    protocol = "mqtt_json"

    def __init__(self, cfg, ctx):
        self.cfg, self.name = cfg, cfg["name"]
        self.machine = cfg.get("machine_id", self.name)
        self.canonical, self.alarms, self.status = ctx
        self.endpoint = f"mqtt://edge-mqtt/{cfg['topic']}"
        self.last_seen = 0.0

    async def on_message(self, msg):
        data = json.loads(msg.payload)
        raw_ts = dig(data, self.cfg.get("timestamp_field", "")) if self.cfg.get("timestamp_field") else None
        ts = datetime.fromisoformat(raw_ts.replace("Z", "+00:00")) if raw_ts else utcnow()
        for tag in self.cfg["tags"]:
            value = dig(data, tag["path"])
            machine = tag.get("machine_id", self.machine)
            self.canonical.emit(machine=machine, tag=tag["name"], value=value, ts=ts,
                                category=tag.get("category", "telemetry"), unit=tag.get("unit"),
                                quality="GOOD" if value is not None else "BAD",
                                protocol=self.protocol, system=self.name, address=tag["path"])
            self.alarms.evaluate(machine=machine, tag=tag["name"], value=value, cfg=tag.get("alarm"),
                                 ts=ts, protocol=self.protocol, system=self.name)
        self.last_seen = time.monotonic()
        self.status.update(self.name, True)

    def check_stale(self):
        if time.monotonic() - self.last_seen > self.cfg.get("stale_after_s", 15):
            self.status.update(self.name, False, "no data received")


class SparkplugSource:
    """Sparkplug B host-side decoder: BIRTH/DEATH/DATA, alias mapping, rebirth requests."""
    protocol = "sparkplug_b"

    def __init__(self, cfg, ctx, bus):
        self.cfg, self.name = cfg, cfg["name"]
        self.group = cfg["group_id"]
        self.canonical, self.alarms, self.status = ctx
        self.bus = bus
        self.endpoint = f"mqtt://edge-mqtt/spBv1.0/{self.group}/#"
        self.aliases = {}          # (node, device) -> {alias: name}
        self.rebirth_sent = {}     # node -> monotonic
        self.last_seen = 0.0
        self.topic_filter = f"spBv1.0/{self.group}/#"

    def _category(self, metric):
        for pattern, cat in (self.cfg.get("categories") or {}).items():
            if fnmatch.fnmatch(metric, pattern):
                return cat
        return "telemetry"

    @staticmethod
    def _value(m):
        kind = m.WhichOneof("value")
        if m.is_null or kind is None:
            return None
        v = getattr(m, kind)
        return round(v, 6) if kind == "float_value" else v

    async def _request_rebirth(self, node):
        if time.monotonic() - self.rebirth_sent.get(node, 0) < 10:
            return
        self.rebirth_sent[node] = time.monotonic()
        p = spb.Payload()
        p.timestamp = int(time.time() * 1000)
        m = p.metrics.add()
        m.name, m.datatype, m.boolean_value = "Node Control/Rebirth", 11, True
        log.info("Sparkplug: unknown aliases from %s, requesting rebirth", node)
        await self.bus.raw_publish(f"spBv1.0/{self.group}/NCMD/{node}", p.SerializeToString())

    async def on_message(self, msg):
        parts = msg.topic.value.split("/")
        if len(parts) < 4:
            return
        mtype, node = parts[2], parts[3]
        device = parts[4] if len(parts) > 4 else None
        if mtype in ("NCMD", "DCMD", "STATE"):
            return
        payload = spb.Payload()
        payload.ParseFromString(msg.payload)

        if mtype in ("NDEATH", "DDEATH"):
            if mtype == "NDEATH":
                for key in [k for k in self.aliases if k[0] == node]:
                    self.aliases.pop(key)
            self.status.update(self.name, False, f"{mtype} from {node}/{device or '*'}")
            return

        key = (node, device)
        if mtype in ("NBIRTH", "DBIRTH"):
            self.aliases[key] = {m.alias: m.name for m in payload.metrics if m.HasField("alias")}
        elif key not in self.aliases:
            await self._request_rebirth(node)
            return
        if device is None:  # node-level metrics (bdSeq, Node Control/*) are not plant data
            self.last_seen = time.monotonic()
            return

        alias_map = self.aliases.get(key, {})
        machine = (self.cfg.get("devices") or {}).get(device, device)
        base_ts = payload.timestamp
        for m in payload.metrics:
            name = m.name or alias_map.get(m.alias)
            if name is None:
                await self._request_rebirth(node)
                continue
            value = self._value(m)
            ts = datetime.fromtimestamp((m.timestamp or base_ts) / 1000, tz=timezone.utc)
            tag = snake(name)
            self.canonical.emit(machine=machine, tag=tag, value=value, ts=ts, category=self._category(name),
                                unit=(self.cfg.get("units") or {}).get(name), protocol=self.protocol,
                                system=self.name, address=f"{node}/{device}:{name}")
            self.alarms.evaluate(machine=machine, tag=tag, value=value, cfg=(self.cfg.get("alarms") or {}).get(name),
                                 ts=ts, protocol=self.protocol, system=self.name)
        self.last_seen = time.monotonic()
        self.status.update(self.name, True)

    def check_stale(self):
        if time.monotonic() - self.last_seen > self.cfg.get("stale_after_s", 15):
            self.status.update(self.name, False, "no Sparkplug data received")


async def watchdog(sources):
    while True:
        await asyncio.sleep(5)
        for s in sources:
            s.check_stale()


# ================================================================ main

async def main():
    with open(os.environ.get("CONFIG_FILE", "config.yaml"), encoding="utf-8") as fh:
        cfg = yaml.safe_load(fh)
    state = State(cfg.get("state_file", "/data/state.json"))
    bus = EdgeBus(cfg["mqtt"])
    canonical = Canonical(cfg, bus)
    alarms = AlarmEngine(canonical, state)
    status = StatusBoard(canonical)
    ctx = (canonical, alarms, status)
    defaults = cfg.get("defaults", {})

    polled, subscribed = [], []
    for s in cfg["sources"]:
        proto = s["protocol"]
        if proto == "modbus":
            src = ModbusSource(s, defaults)
        elif proto == "opcua":
            src = OpcUaSource(s, defaults)
        elif proto == "scada_xml":
            src = ScadaXmlSource(s, defaults)
        elif proto == "sql":
            src = SqlSource(s, defaults, state)
        elif proto == "mqtt_json":
            src = MqttJsonSource(s, ctx)
            bus.subscribe(s["topic"], src.on_message)
        elif proto == "sparkplug_b":
            src = SparkplugSource(s, ctx, bus)
            bus.subscribe(src.topic_filter, src.on_message)
        else:
            raise SystemExit(f"unknown protocol {proto!r} in source {s['name']}")
        (polled if isinstance(src, PolledSource) else subscribed).append(src)
        machine = s.get("machine_id") or ",".join((s.get("devices") or {}).values()) or s["name"]
        status.register(src.name, machine, proto, src.endpoint)

    log.info("plant %s edge %s session %s: %d polled + %d subscribed sources",
             canonical.plant, canonical.edge, canonical.session, len(polled), len(subscribed))
    await asyncio.gather(bus.run(), watchdog(subscribed), *(poll_forever(s, ctx) for s in polled))


if __name__ == "__main__":
    asyncio.run(main())
