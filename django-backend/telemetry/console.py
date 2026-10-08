"""OT/IT Bridge Console API (/api/console/...).

Reads and writes the BIMBA Gateway's own files in the shared config folder (compose mounts
./ignition/bimba at BIMBA_CONFIG_DIR), so a saved change is what the gateway actually runs:
  config.json          plant / edge id (delivery destinations moved to the database: gateway console -> Delivery;
                       they are shown here read-only and are edited only in the gateway console).
  demo-tags.json       tag tree: Modbus register / OPC UA node / expression per tag.
  console-sources.json connection settings of the Modbus devices / OPC UA servers (gateway + tests).
Secrets are never returned: passwords come back as null + "passwordSet", Event Streams tokens only as
${ENV_VAR} references (the value stays in .env). There is no login (POC, like the read API); writes are
protected against cross-site requests by Django's CSRF check (the cookie is set by GET config/).
"""
import json
import logging
import math
import os
import re
import threading
import time
from pathlib import Path

from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.csrf import ensure_csrf_cookie
from django.views.decorators.http import require_GET, require_http_methods, require_POST

from . import probes

log = logging.getLogger("telemetry.console")
_lock = threading.Lock()

MODBUS_SERVER = "BIMBA Modbus"               # driver name in the tag tree (BIMBA Gateway)
MODBUS_SERVERS = {MODBUS_SERVER, "modbus", "Ignition OPC UA Server"}   # also read Ignition tag exports
MODBUS_ITEM = re.compile(r"^ns=1;s=\[([^\]]+)\](.+)$")
OPC_NODE = re.compile(r"^(ns=\d+;)?[isgb]=.+$")
DEFAULT_SOURCES = {
    "modbus": {"PLC": {"host": "plc-sim", "port": 5020, "unitId": 1, "timeoutMs": 1500,
                       "zeroBased": False, "reverseWordOrder": False}},
    "opcua": {"METER": {"endpoint": "opc.tcp://opcua-sim:4840/scada/", "policy": "None", "mode": "None",
                        "auth": "Anonymous", "username": "", "password": "", "timeoutMs": 4000}},
}


# ---------------------------------------------------------------- files

def _dir():
    return Path(settings.BIMBA_CONFIG_DIR)


def _read(name, default=None):
    p = _dir() / name
    if not p.exists():
        if default is not None:
            return json.loads(json.dumps(default))
        raise FileNotFoundError(f"{p} not found (is ./ignition/bimba mounted into the django service?)")
    return json.loads(p.read_text(encoding="utf-8-sig"))


def _write(name, data):
    """Atomic write (temp file + rename) so the plugin never reads half a file; keeps one .bak."""
    p = _dir() / name
    if p.exists():
        (_dir() / f"{name}.bak").write_bytes(p.read_bytes())
    tmp = _dir() / f".{name}.tmp"
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    os.replace(tmp, p)


def _touch_config():
    """The plugin reloads (and re-imports demo-tags.json) when config.json's mtime changes."""
    p = _dir() / "config.json"
    now = time.time()
    os.utime(p, (now, max(now, p.stat().st_mtime + 1)))


# ---------------------------------------------------------------- tag mapping (demo-tags.json)

def _walk(node, prefix=""):
    for t in node.get("tags", []):
        if t.get("tagType") == "Folder":
            yield from _walk(t, f"{prefix}{t['name']}/")
        else:
            yield f"{prefix}{t['name']}", t


def _scale(t):
    if t.get("scaleMode") != "Linear":
        return 1
    raw = (t.get("rawHigh", 1) or 1) - (t.get("rawLow", 0) or 0)
    return ((t.get("scaledHigh", 1) or 0) - (t.get("scaledLow", 0) or 0)) / raw


def _point(path, t):
    machine, _, tag = path.partition("/")
    p = {"path": path, "machine": machine, "tag": tag.replace("/", "_"), "dataType": t.get("dataType"),
         "unit": t.get("engUnit") or "", "kind": "other", "device": None}
    item, server = t.get("opcItemPath") or "", t.get("opcServer") or ""
    if t.get("valueSource") == "expr":
        p.update(kind="expression", expression=t.get("expression"))
    elif t.get("valueSource") == "opc" and server in MODBUS_SERVERS and MODBUS_ITEM.match(item):
        device, address = MODBUS_ITEM.match(item).groups()
        p.update(kind="modbus", device=device, address=address, scale=_scale(t))
    elif t.get("valueSource") == "opc":
        p.update(kind="opcua", device=server, node=item)
    return p


def _tags():
    cfg = _read("config.json")
    name = cfg.get("provisionTagsFile") or "demo-tags.json"
    return name, _read(name)


def _points():
    name, tree = _tags()
    return [_point(path, t) for path, t in _walk(tree)]


def _set_scale(t, scale):
    for k in ("scaleMode", "rawLow", "rawHigh", "scaledLow", "scaledHigh"):
        t.pop(k, None)
    if abs(scale - 1) < 1e-12:
        return
    inv = 1 / scale
    raw = round(inv) if inv >= 1 and abs(inv - round(inv)) < 1e-9 else 1
    high = round(scale * raw, 12)
    t.update(scaleMode="Linear", rawLow=0, rawHigh=raw, scaledLow=0, scaledHigh=int(high) if high == int(high) else high)


# ---------------------------------------------------------------- destinations (read-only, gateway's table)

GATEWAY_DELIVERY = "Destinations are managed in the BIMBA Gateway console (Delivery), not here any more."


def _dest(cfg, transport):
    """First destination of a transport in delivery order. The BIMBA Gateway keeps them in bimba_gw_destination
    (secrets encrypted with a key only the gateway has); cfg is unused and kept for the callers."""
    from .models import GatewayDestination
    for row in GatewayDestination.objects.order_by("position", "id"):
        d = dict(row.config or {}, name=row.name)
        if d.get("transport") == transport:
            return d
    return None


def _plain(v):
    """a stored secret Django can use: ${ENV} references and plain values, not the gateway's encrypted ones"""
    return "" if str(v or "").startswith("enc:") else (v or "")


def _sasl(jaas):
    m = re.search(r'username="([^"]*)"', jaas or "")
    return m.group(1) if m else ""


def _kafka_view(d):
    k = (d or {}).get("kafka") or {}
    return {
        "exists": d is not None,
        "name": (d or {}).get("name", "kafka"),
        "enabled": bool((d or {}).get("enabled", False)),
        "bootstrapServers": (d or {}).get("bootstrapServers", ""),
        "topic": (d or {}).get("topic", "bimba.{plantId}.telemetry"),
        "kafkaMode": (d or {}).get("kafkaMode", "batch"),
        "securityProtocol": (d or {}).get("securityProtocol") or k.get("security.protocol", "PLAINTEXT"),
        "saslMechanism": (d or {}).get("saslMechanism") or k.get("sasl.mechanism", "PLAIN"),
        "username": (d or {}).get("username") or _sasl(k.get("sasl.jaas.config")),
        "password": None,
        "passwordSet": bool((d or {}).get("password")) or bool(re.search(r'password="[^"]+"', k.get("sasl.jaas.config") or "")),
        "timeoutMs": (d or {}).get("timeoutMs", 5000),
        "retryAfterSeconds": (d or {}).get("retryAfterSeconds", 15),
    }


def _kafka_password(d):
    if (d or {}).get("password"):
        return _plain(d["password"])
    m = re.search(r'password="([^"]*)"', ((d or {}).get("kafka") or {}).get("sasl.jaas.config") or "")
    return m.group(1) if m else ""


def _es_view(d):
    token = ((d or {}).get("auth") or {}).get("token") or ""
    ref = probes.ENV_REF.match(token)
    env_name = ref.group(1) if ref else None
    return {
        "exists": d is not None,
        "name": (d or {}).get("name", "boomi-eventstreams"),
        "role": (d or {}).get("role", "primary"),
        "enabled": bool((d or {}).get("enabled", False)),
        "url": (d or {}).get("url", ""),
        "token": token if ref else "",
        "literalToken": False,
        "tokenEnvSet": bool(env_name and os.environ.get(env_name, "").strip()),
        "esMode": (d or {}).get("esMode", "batch"),
        "esApi": (d or {}).get("esApi", "auto"),
        "timeoutMs": (d or {}).get("timeoutMs", 5000),
        "retryAfterSeconds": (d or {}).get("retryAfterSeconds", 15),
    }


# ---------------------------------------------------------------- helpers

def _json_body(request):
    try:
        return json.loads(request.body or b"{}")
    except ValueError:
        return None


def _bad(msg, status=400):
    return JsonResponse({"error": msg}, status=status)


def _int(v, name, lo, hi):
    try:
        n = int(v)
    except (TypeError, ValueError):
        raise ValueError(f"{name} must be a whole number")
    if not lo <= n <= hi:
        raise ValueError(f"{name} must be between {lo} and {hi}")
    return n


def _masked_sources(src):
    out = json.loads(json.dumps(src))
    for c in out.get("opcua", {}).values():
        c["passwordSet"] = bool(c.get("password"))
        c["password"] = None
    return out


# ---------------------------------------------------------------- read

@require_GET
@ensure_csrf_cookie
def config(request):
    try:
        cfg = _read("config.json")
        points = _points()
        sources = _read("console-sources.json", DEFAULT_SOURCES)
    except (OSError, ValueError) as exc:
        return JsonResponse({"error": str(exc)}, status=503)
    # every device the tag mapping uses gets a (default) connection entry
    for p in points:
        if p["kind"] in ("modbus", "opcua") and p["device"] not in sources.setdefault(p["kind"], {}):
            base = DEFAULT_SOURCES[p["kind"]]
            sources[p["kind"]][p["device"]] = json.loads(json.dumps(next(iter(base.values()))))
    return JsonResponse({
        "plantId": cfg.get("plantId"), "edgeId": cfg.get("edgeId"),
        "points": points,
        "sources": _masked_sources(sources),
        "kafka": _kafka_view(_dest(cfg, "kafka")),
        "eventstreams": _es_view(_dest(cfg, "eventstreams")),
    })


# ---------------------------------------------------------------- write

def _merge_password(new, old):
    return old if new is None else new


@require_http_methods(["PUT"])
def save_source(request, kind):
    """kind = modbus | opcua. Body: {device, connection:{...}, points:[{path, address|node, scale?, unit?}]}"""
    if kind not in ("modbus", "opcua"):
        return _bad("unknown source kind", 404)
    body = _json_body(request)
    if not body or not body.get("device"):
        return _bad("body must contain device, connection and points")
    device, conn, edits = body["device"], dict(body.get("connection") or {}), body.get("points") or []
    try:
        if kind == "modbus":
            if not str(conn.get("host") or "").strip():
                raise ValueError("Host is required")
            conn = {"host": str(conn["host"]).strip(), "port": _int(conn.get("port"), "Port", 1, 65535),
                    "unitId": _int(conn.get("unitId"), "Unit ID", 0, 255),
                    "timeoutMs": _int(conn.get("timeoutMs"), "Timeout", 100, 60000),
                    "zeroBased": bool(conn.get("zeroBased")), "reverseWordOrder": bool(conn.get("reverseWordOrder"))}
        else:
            if not str(conn.get("endpoint") or "").startswith("opc.tcp://"):
                raise ValueError("Endpoint URL must start with opc.tcp://")
            if conn.get("policy") not in ("None", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"):
                raise ValueError("unknown security policy")
            if conn.get("mode") not in ("None", "Sign", "SignAndEncrypt"):
                raise ValueError("unknown security mode")
            if (conn["policy"] == "None") != (conn["mode"] == "None"):
                raise ValueError("Security policy None goes with security mode None (and only with it)")
            conn = {"endpoint": conn["endpoint"].strip(), "policy": conn["policy"], "mode": conn["mode"],
                    "auth": conn.get("auth") if conn.get("auth") in ("Anonymous", "Username") else "Anonymous",
                    "username": str(conn.get("username") or ""), "password": conn.get("password"),
                    "timeoutMs": _int(conn.get("timeoutMs", 4000), "Timeout", 500, 60000)}
    except ValueError as exc:
        return _bad(str(exc))

    with _lock:
        try:
            sources = _read("console-sources.json", DEFAULT_SOURCES)
            tags_name, tree = _tags()
        except (OSError, ValueError) as exc:
            return JsonResponse({"error": str(exc)}, status=503)
        if kind == "opcua":
            old = sources.get("opcua", {}).get(device, {})
            conn["password"] = _merge_password(conn["password"], old.get("password", ""))
        by_path = dict(_walk(tree))
        changed = 0
        for e in edits:
            t = by_path.get(e.get("path"))
            if t is None:
                return _bad(f"unknown tag {e.get('path')}")
            before = json.loads(json.dumps(t))
            if kind == "modbus":
                try:
                    probes.parse_modbus_address(e.get("address"), False)
                    scale = float(e.get("scale"))
                    if not math.isfinite(scale) or scale <= 0:
                        raise ValueError
                except probes.ProbeError as exc:
                    return _bad(f"{e['path']}: {exc}")
                except (TypeError, ValueError):
                    return _bad(f"{e['path']}: scale must be a positive number")
                t["opcServer"] = MODBUS_SERVER
                t["opcItemPath"] = f"ns=1;s=[{device}]{e['address'].strip().upper()}"
                _set_scale(t, scale)
                if "unit" in e:
                    if e["unit"]:
                        t["engUnit"] = str(e["unit"])
                    else:
                        t.pop("engUnit", None)
            else:
                node = str(e.get("node") or "").strip()
                if not OPC_NODE.match(node):
                    return _bad(f"{e['path']}: NodeId must look like ns=2;s=Name (or i=, g=, b=)")
                t["opcServer"] = device
                t["opcItemPath"] = node
            changed += before != t  # compares values, so 1 == 1.0
        sources.setdefault(kind, {})[device] = conn
        _write("console-sources.json", sources)
        if changed:
            _write(tags_name, tree)
            _touch_config()
    log.info("console: %s %s saved (%d tags changed)", kind, device, changed)
    return JsonResponse({"saved": True, "tagsChanged": changed,
                         "message": (f"Saved. {changed} tag(s) changed; the BIMBA Gateway applies them "
                                     f"within about 10 s." if changed else "Saved. The BIMBA Gateway applies it within about 10 s.")})


@require_http_methods(["PUT"])
def save_kafka(request):
    return JsonResponse({"error": GATEWAY_DELIVERY, "code": "moved"}, status=410)


@require_http_methods(["PUT"])
def save_eventstreams(request):
    return JsonResponse({"error": GATEWAY_DELIVERY, "code": "moved"}, status=410)


# ---------------------------------------------------------------- tests

@require_POST
def test(request, kind):
    b = _json_body(request)
    if b is None:
        return _bad("body is not JSON")
    try:
        if kind == "modbus":
            result = probes.modbus(b.get("connection") or {}, b.get("points") or [])
        elif kind == "opcua":
            conn = dict(b.get("connection") or {})
            if conn.get("password") is None:
                saved = _read("console-sources.json", DEFAULT_SOURCES).get("opcua", {}).get(b.get("device"), {})
                conn["password"] = saved.get("password", "")
            result = probes.opcua(conn, b.get("points") or [])
        elif kind == "kafka":
            cfg = _read("config.json")
            if b.get("password") is None:
                b["password"] = _kafka_password(_dest(cfg, "kafka"))
            result = probes.kafka(b, cfg.get("plantId", ""))
        elif kind == "eventstreams":
            if b.get("token") is None:
                b["token"] = _plain(((_dest(None, "eventstreams") or {}).get("auth") or {}).get("token"))
            result = probes.eventstreams(b)
        else:
            return _bad("unknown test", 404)
    except probes.ProbeError as exc:
        result = {"ok": False, "message": str(exc)}
    except Exception as exc:  # noqa: BLE001 - a failed probe is a result, not a server error
        log.exception("console test %s failed", kind)
        result = {"ok": False, "message": f"{type(exc).__name__}: {exc}"[:300]}
    return JsonResponse(result)


# ---------------------------------------------------------------- Kafka tail (live flow)

_tail = {"key": None, "consumer": None}
_tail_lock = threading.Lock()


def _tail_consumer(conn):
    from kafka import KafkaConsumer
    key = json.dumps(conn, sort_keys=True)
    if _tail["key"] != key or _tail["consumer"] is None:
        if _tail["consumer"] is not None:
            _tail["consumer"].close()
        _tail.update(key=key, consumer=KafkaConsumer(**probes.kafka_client_config(conn), enable_auto_commit=False))
    return _tail["consumer"]


@require_GET
def kafka_tail(request):
    """Newest records on the Kafka mirror topic, filtered to one machine: what IT actually received."""
    from kafka import TopicPartition
    machine = request.GET.get("machine", "")
    depth = min(int(request.GET.get("depth", 40)), 200)
    try:
        cfg = _read("config.json")
    except (OSError, ValueError) as exc:
        return JsonResponse({"available": False, "error": str(exc)})
    d = _dest(cfg, "kafka")
    if d is None:   # destinations have no "enabled" any more: pipelines decide whether they get data
        return JsonResponse({"available": False, "error": "No Kafka destination is set up in the BIMBA Gateway."})
    view = _kafka_view(d)
    view["password"] = _kafka_password(d)
    topic = view["topic"].replace("{plantId}", cfg.get("plantId", ""))
    with _tail_lock:
        try:
            c = _tail_consumer(view)
            parts = c.partitions_for_topic(topic)
            if not parts:
                return JsonResponse({"available": False, "topic": topic, "error": f"Topic {topic} not found."})
            tps = [TopicPartition(topic, p) for p in sorted(parts)]
            c.assign(tps)
            ends, begins = c.end_offsets(tps), c.beginning_offsets(tps)
            for tp in tps:
                c.seek(tp, max(begins[tp], ends[tp] - depth))
            raw, deadline = [], time.monotonic() + 2.5
            while time.monotonic() < deadline and any(c.position(tp) < ends[tp] for tp in tps):
                for recs in c.poll(timeout_ms=300, max_records=500).values():
                    raw.extend(recs)
        except Exception as exc:  # noqa: BLE001
            if _tail["consumer"] is not None:
                _tail["consumer"].close()
            _tail.update(key=None, consumer=None)
            return JsonResponse({"available": False, "topic": topic, "error": f"{type(exc).__name__}: {exc}"[:300]})
    records = []
    for r in sorted(raw, key=lambda r: (r.timestamp, r.offset), reverse=True):
        try:
            body = json.loads(r.value)
        except (TypeError, ValueError):
            continue
        msgs = body.get("messages") if isinstance(body.get("messages"), list) else [body]
        mine = [m for m in msgs if not machine or m.get("machineId") == machine]
        if not mine:
            continue
        records.append({"partition": r.partition, "offset": r.offset, "timestamp": r.timestamp,
                        "key": r.key.decode("utf-8", "replace") if r.key else None,
                        "batchId": body.get("batchId"), "count": len(msgs), "messages": mine[:25]})
    return JsonResponse({"available": True, "topic": topic, "scanned": len(raw),
                         "endOffsets": {str(tp.partition): ends[tp] for tp in tps}, "records": records[:12]})
