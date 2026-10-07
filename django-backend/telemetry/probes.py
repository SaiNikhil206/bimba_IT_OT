"""Real connection tests for the OT/IT Bridge Console (/api/console/test/<kind>/).

Each probe talks the actual protocol to the endpoint the user typed in (not the saved one),
so a configuration can be checked before it is saved to the plugin:
  modbus       Modbus TCP read (FC1/FC2/FC3/FC4) of every register mapped to the device
  opcua        endpoint discovery + (security None) session and a read of every mapped node
  kafka        broker metadata, topic partitions and end offsets
  eventstreams authenticated request to the topic REST URL (no message is produced)
"""
import asyncio
import os
import re
import socket
import struct
import time
import urllib.error
import urllib.parse
import urllib.request

ENV_REF = re.compile(r"^\$\{(BOOMI_ES_[A-Z0-9_]+)\}$")


class ProbeError(Exception):
    pass


# ---------------------------------------------------------------- Modbus TCP

# Ignition Modbus address: optional "<unit>." prefix, area, 1-based number (e.g. HR2, 1.C1, IR10)
MODBUS_ADDR = re.compile(r"^(?:(\d+)\.)?(HR|IR|C|DI)(\d+)$", re.I)
AREA_FC = {"C": 1, "DI": 2, "HR": 3, "IR": 4}


def parse_modbus_address(addr, zero_based):
    m = MODBUS_ADDR.match((addr or "").strip())
    if not m:
        raise ProbeError(f"unsupported Modbus address {addr!r} (use HR<n>, IR<n>, C<n> or DI<n>)")
    unit, area, n = m.group(1), m.group(2).upper(), int(m.group(3))
    offset = n if zero_based else n - 1
    if offset < 0 or offset > 65535:
        raise ProbeError(f"address {addr!r} is out of range")
    return (int(unit) if unit else None), area, offset


def _recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ProbeError("connection closed by the device")
        buf += chunk
    return buf


def _modbus_read(sock, tid, unit, fc, offset):
    sock.sendall(struct.pack(">HHHBBHH", tid, 0, 6, unit, fc, offset, 1))
    _, _, length, _ = struct.unpack(">HHHB", _recv_exact(sock, 7))
    pdu = _recv_exact(sock, length - 1)
    if pdu[0] & 0x80:
        names = {1: "illegal function", 2: "illegal data address", 3: "illegal data value", 4: "device failure"}
        raise ProbeError(f"exception {pdu[1]} ({names.get(pdu[1], 'unknown')})")
    data = pdu[2:2 + pdu[1]]
    if fc in (1, 2):
        return data[0] & 1
    return struct.unpack(">H", data[:2])[0]


def modbus(conn, points):
    host, port = conn.get("host") or "", int(conn.get("port") or 502)
    unit_default = int(conn.get("unitId") or 1)
    timeout = max(0.2, int(conn.get("timeoutMs") or 1500) / 1000)
    zero_based = bool(conn.get("zeroBased"))
    if not host:
        raise ProbeError("host is empty")
    started = time.monotonic()
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
    except OSError as exc:
        raise ProbeError(f"cannot connect to {host}:{port}: {exc}") from None
    reads, ok = [], 0
    with sock:
        sock.settimeout(timeout)
        for i, p in enumerate(points, start=1):
            row = {"path": p.get("path"), "address": p.get("address")}
            try:
                unit, area, offset = parse_modbus_address(p.get("address"), zero_based)
                raw = _modbus_read(sock, i, unit or unit_default, AREA_FC[area], offset)
                row.update(raw=raw, rawHex=f"0x{raw:04X}" if area in ("HR", "IR") else str(raw))
                ok += 1
            except (ProbeError, OSError) as exc:
                row["error"] = str(exc)
                if isinstance(exc, OSError):
                    break  # the socket is unusable after a timeout
            reads.append(row)
    ms = round((time.monotonic() - started) * 1000)
    message = f"Connected to {host}:{port}. Read {ok} of {len(points)} registers from unit {unit_default} in {ms} ms."
    return {"ok": ok == len(points) and ok > 0, "message": message, "reads": reads}


# ---------------------------------------------------------------- OPC UA

def opcua(conn, points):
    return asyncio.run(_opcua(conn, points))


async def _opcua(conn, points):
    from asyncua import Client, ua

    url = (conn.get("endpoint") or "").strip()
    if not url.startswith("opc.tcp://"):
        raise ProbeError("endpoint must start with opc.tcp://")
    timeout = max(1, int(conn.get("timeoutMs") or 4000) / 1000)
    want_policy, want_mode = conn.get("policy") or "None", conn.get("mode") or "None"

    try:
        endpoints = await asyncio.wait_for(Client(url, timeout=timeout).connect_and_get_server_endpoints(),
                                           timeout + 2)
    except Exception as exc:  # noqa: BLE001 - report any failure to the user
        raise ProbeError(f"cannot reach {url}: {type(exc).__name__} {exc}".strip()) from None

    # asyncua names the "None" mode None_ (None is a Python keyword)
    offered = sorted({(e.SecurityPolicyUri.rsplit("#", 1)[-1], e.SecurityMode.name.rstrip("_")) for e in endpoints})
    offered_txt = ", ".join(f"{p}/{m}" for p, m in offered)
    if (want_policy, want_mode) not in offered:
        return {"ok": False, "offered": offered_txt,
                "message": f"Server does not offer {want_policy}/{want_mode}. Offered: {offered_txt}."}
    if want_policy != "None":
        return {"ok": True, "offered": offered_txt,
                "message": f"Server offers {want_policy}/{want_mode}. The console opens test sessions only "
                           f"without security, so nodes were not read. Offered: {offered_txt}."}

    client = Client(url, timeout=timeout)
    if conn.get("auth") == "Username":
        client.set_user(conn.get("username") or "")
        client.set_password(conn.get("password") or "")
    reads, ok = [], 0
    try:
        async with client:
            for p in points:
                row = {"path": p.get("path"), "node": p.get("node")}
                try:
                    dv = await client.get_node(p.get("node")).read_data_value()
                    value = dv.Value.Value if dv.Value is not None else None
                    row.update(value=value if isinstance(value, (int, float, str, bool)) or value is None else str(value),
                               type=dv.Value.VariantType.name if dv.Value is not None else None,
                               status=dv.StatusCode.name if dv.StatusCode else "Good")
                    ok += 1
                except Exception as exc:  # noqa: BLE001
                    row["error"] = f"{type(exc).__name__}: {exc}"[:160]
                reads.append(row)
    except ua.UaError as exc:
        raise ProbeError(f"session failed: {exc}") from None
    except Exception as exc:  # noqa: BLE001
        raise ProbeError(f"session failed: {type(exc).__name__} {exc}") from None
    return {"ok": ok == len(points) and ok > 0, "offered": offered_txt, "reads": reads,
            "message": f"Session opened ({want_policy}, {want_mode}). {ok} of {len(points)} nodes readable."}


# ---------------------------------------------------------------- Kafka

def kafka_client_config(conn):
    """kafka-python settings from the console's Kafka form (same values the plugin gets)."""
    cfg = {
        "bootstrap_servers": [s.strip() for s in (conn.get("bootstrapServers") or "").split(",") if s.strip()],
        "security_protocol": conn.get("securityProtocol") or "PLAINTEXT",
        "request_timeout_ms": max(3000, int(conn.get("timeoutMs") or 5000)),
        "api_version_auto_timeout_ms": 4000,
        "client_id": "bimba-console",
    }
    if not cfg["bootstrap_servers"]:
        raise ProbeError("bootstrap servers are empty")
    if cfg["security_protocol"].startswith("SASL"):
        cfg.update(sasl_mechanism=conn.get("saslMechanism") or "PLAIN",
                   sasl_plain_username=conn.get("username") or "",
                   sasl_plain_password=conn.get("password") or "")
    return cfg


def kafka(conn, plant_id):
    from kafka import KafkaConsumer, TopicPartition
    from kafka.errors import KafkaError

    topic = (conn.get("topic") or "").replace("{plantId}", plant_id)
    try:
        consumer = KafkaConsumer(**kafka_client_config(conn), consumer_timeout_ms=1000)
    except KafkaError as exc:
        raise ProbeError(f"cannot connect: {type(exc).__name__} {exc}".strip()) from None
    try:
        brokers = sorted(f"{b.host}:{b.port}" for b in consumer._client.cluster.brokers())
        topics = consumer.topics()
        if topic not in topics:
            return {"ok": True, "brokers": brokers,
                    "message": f"Connected to {len(brokers)} broker(s). Topic {topic} does not exist yet; "
                               f"it is created on the first write if the cluster allows auto-create."}
        parts = sorted(consumer.partitions_for_topic(topic) or [])
        ends = consumer.end_offsets([TopicPartition(topic, p) for p in parts])
        total = sum(ends.values())
        return {"ok": True, "brokers": brokers, "partitions": len(parts), "endOffsets": total,
                "message": f"Connected to {len(brokers)} broker(s). Topic {topic} has {len(parts)} partition(s), "
                           f"{total:,} records written."}
    except KafkaError as exc:
        raise ProbeError(f"{type(exc).__name__} {exc}".strip()) from None
    finally:
        consumer.close()


# ---------------------------------------------------------------- Boomi Event Streams

def resolve_token(ref):
    m = ENV_REF.match((ref or "").strip())
    if not m:
        raise ProbeError("token must be an environment reference like ${BOOMI_ES_PRODUCE_TOKEN}")
    return m.group(1), (os.environ.get(m.group(1)) or "").strip()


def eventstreams(conn):
    url = (conn.get("url") or "").strip()
    if not url.startswith("https://"):
        raise ProbeError("topic REST URL must start with https://")
    name, token = resolve_token(conn.get("token"))
    if not token:
        raise ProbeError(f"{name} is not set in .env (the console and the gateway read it from there)")
    # GET is not a produce call: an accepted token gets 405/404, a rejected one 401/403. Nothing is published.
    req = urllib.request.Request(url, method="GET", headers={"Authorization": f"Bearer {token}"})
    timeout = max(2, int(conn.get("timeoutMs") or 5000) / 1000)
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            code = resp.status
    except urllib.error.HTTPError as exc:
        code = exc.code
    except OSError as exc:
        raise ProbeError(f"cannot reach {urllib.parse.urlsplit(url).netloc}: {exc}") from None
    ms = round((time.monotonic() - started) * 1000)
    if code in (401, 403):
        return {"ok": False, "status": code,
                "message": f"Reached Event Streams in {ms} ms, but the token in {name} was rejected (HTTP {code})."}
    return {"ok": True, "status": code,
            "message": f"Token in {name} accepted by Event Streams (HTTP {code} to a non-produce request, {ms} ms)."}
