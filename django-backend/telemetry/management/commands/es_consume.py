"""Consume BIMBA batches from Boomi Event Streams and store them in PostgreSQL.

    python manage.py es_consume

Uses the Event Streams *consume* REST API (batch endpoint, long polling):
    GET https://<region>-web.eventstreams.boomi.com/rest/consume/batch/<account>/<env>/<topic>/<subscription>
        ?max_messages=100&timeout_ms=10000          Authorization: Bearer <environment token>

Event Streams acknowledges messages as soon as they are delivered, so every response
is first written to a local spool file (fsync), then stored with bimba_ingest(...,
'boomi-es'), then the spool file is removed. After a crash or a DB outage the spool is
replayed on start; bimba_ingest de-duplicates on messageId, so replays are safe.

Environment:
    ES_CONSUME_URL     the subscription's consume URL (single or batch form; batch is used)
    ES_CONSUME_TOKEN   environment token with consume permission
    ES_SPOOL_DIR       default /data/es-spool
    ES_MAX_MESSAGES    default 100 (API maximum)
    ES_TIMEOUT_MS      long-poll wait, default 10000
"""
import json
import logging
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

from django.core.management.base import BaseCommand
from django.db import DatabaseError, InterfaceError, OperationalError, close_old_connections

from telemetry.ingest import ingest_batch

log = logging.getLogger("telemetry.es")
VIA = "boomi-es"


def batch_url(url):
    """Accepts the single-message consume URL shown in 'View REST' and returns the batch form."""
    if "/rest/consume/batch/" in url:
        return url
    return url.replace("/rest/consume/", "/rest/consume/batch/", 1)


def extract_payloads(body):
    """The batch endpoint returns a JSON array; each element's payload is a plain string.
    Field naming is handled defensively (payload/data/message/value, or a bare string)."""
    items = body if isinstance(body, list) else body.get("messages", body.get("data", [])) if isinstance(body, dict) else []
    out = []
    for item in items:
        raw = item
        if isinstance(item, dict):
            for key in ("payload", "data", "message", "value", "body"):
                if key in item:
                    raw = item[key]
                    break
        if isinstance(raw, (dict, list)):
            out.append(raw)
            continue
        try:
            out.append(json.loads(raw))
        except (TypeError, ValueError):
            log.warning("skipping non-JSON Event Streams message: %.120s", raw)
    return out


class Command(BaseCommand):
    help = "Consume BIMBA batches from Boomi Event Streams (REST) and store them"

    def handle(self, *args, **options):
        url = os.environ.get("ES_CONSUME_URL", "").strip()
        token = os.environ.get("ES_CONSUME_TOKEN", "").strip()
        spool = Path(os.environ.get("ES_SPOOL_DIR", "/data/es-spool"))
        spool.mkdir(parents=True, exist_ok=True)
        max_messages = int(os.environ.get("ES_MAX_MESSAGES", "100"))
        timeout_ms = int(os.environ.get("ES_TIMEOUT_MS", "10000"))

        if not url or not token or token.startswith("CHANGE_ME"):
            log.warning("ES_CONSUME_URL / ES_CONSUME_TOKEN not set - Event Streams consumer idle "
                        "(run scripts/set-eventstreams-consumer.ps1)")
            while True:
                time.sleep(3600)

        endpoint = batch_url(url) + "?" + urllib.parse.urlencode(
            {"max_messages": max_messages, "timeout_ms": timeout_ms})
        log.info("consuming %s (spool %s)", endpoint.split("?")[0], spool)
        self.drain_spool(spool)

        backoff = 2
        while True:
            try:
                req = urllib.request.Request(endpoint, headers={
                    "Authorization": f"Bearer {token}", "Accept": "application/json",
                    "User-Agent": "BIMBA-Django-ES-Consumer/1.0"})
                with urllib.request.urlopen(req, timeout=timeout_ms / 1000 + 20) as resp:
                    raw = resp.read()
                    status = resp.status
                backoff = 2
            except urllib.error.HTTPError as exc:
                detail = exc.read()[:200].decode("utf-8", "replace")
                log.warning("Event Streams HTTP %s: %s (retry in %ss)", exc.code, " ".join(detail.split()), backoff)
                time.sleep(backoff)
                backoff = min(backoff * 2, 60)
                continue
            except OSError as exc:
                log.warning("Event Streams unreachable: %s (retry in %ss)", exc, backoff)
                time.sleep(backoff)
                backoff = min(backoff * 2, 60)
                continue

            if status == 204 or not raw.strip():
                continue
            # messages are acknowledged now: persist before anything else
            f = spool / f"{time.time_ns()}-{uuid.uuid4().hex[:8]}.json"
            with open(f, "wb") as fh:
                fh.write(raw)
                fh.flush()
                os.fsync(fh.fileno())
            self.store_file(f)

    def drain_spool(self, spool):
        pending = sorted(spool.glob("*.json"))
        if pending:
            log.info("replaying %d spooled Event Streams responses", len(pending))
        for f in pending:
            self.store_file(f)

    def store_file(self, f):
        try:
            body = json.loads(f.read_bytes())
        except ValueError:
            log.error("spool file %s is not JSON; moved aside", f.name)
            f.rename(f.with_suffix(".bad"))
            return
        payloads = extract_payloads(body)
        totals = {"received": 0, "inserted": 0, "duplicates": 0}
        for i, p in enumerate(payloads):
            while True:
                try:
                    close_old_connections()
                    r = ingest_batch(p, VIA)
                    for k in totals:
                        totals[k] += r.get(k, 0)
                    break
                except (OperationalError, InterfaceError) as exc:
                    # DB down: keep the spool file and retry (earlier payloads are de-duplicated on replay)
                    log.warning("database unavailable, keeping %s in spool: %s", f.name, exc)
                    time.sleep(3)
                except DatabaseError as exc:
                    # the payload itself is invalid: park it, never block the stream
                    bad = f.with_name(f"{f.stem}-{i}.rejected")
                    bad.write_text(json.dumps(p), encoding="utf-8")
                    log.error("rejected Event Streams message -> %s: %s", bad.name, exc)
                    break
        log.info("stored %d Event Streams messages: %s", len(payloads), totals)
        f.unlink()
