import hmac
import json
import logging
import urllib.request
import uuid
from datetime import timedelta

from django.conf import settings
from django.db import DatabaseError, connection
from django.http import JsonResponse
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_GET, require_POST

from .ingest import ingest_batch
from .models import IngestBatch, Machine, Measurement, Tag

log = logging.getLogger("telemetry")
VIAS = ["boomi-es", "bimba-rest", "boomi-rest", "boomi-mqtt", "bimba-mqtt", "bimba-gateway"]


def _authorized(request):
    supplied = request.headers.get("X-API-Key") or ""
    auth = request.headers.get("Authorization", "")
    if auth.lower().startswith("bearer "):
        supplied = auth[7:]
    return hmac.compare_digest(supplied, settings.INGEST_API_KEY)


@csrf_exempt
@require_POST
def ingest(request):
    """BIMBA Ingest (failover end system): the BIMBA IT/OT Agent POSTs canonical batches here."""
    if not _authorized(request):
        return JsonResponse({"error": "invalid API key"}, status=401)
    if request.headers.get("X-BIMBA-Probe"):
        # standby health check from the plugin: key is valid, now check the DB is usable; store nothing
        try:
            with connection.cursor() as cur:
                cur.execute("SELECT 1")
        except DatabaseError as exc:
            return JsonResponse({"error": "database unavailable", "detail": str(exc)[:200]}, status=503)
        return JsonResponse({"probe": "ok"})
    try:
        payload = json.loads(request.body)
    except ValueError:
        return JsonResponse({"error": "body is not valid JSON"}, status=400)
    try:
        result = ingest_batch(payload, "bimba-rest")
    except DatabaseError as exc:
        # 503 tells the plugin to retry / buffer instead of dropping the batch
        log.exception("ingest failed")
        return JsonResponse({"error": "database unavailable", "detail": str(exc)[:200]}, status=503)
    return JsonResponse(result)


def tag_json(t):
    return {
        "tagId": t.id, "tag": t.name, "unit": t.unit, "dataType": t.data_type, "sourcePath": t.source_path,
        "num": t.last_value_num, "text": t.last_value_text, "quality": t.last_quality,
        "time": t.last_time.isoformat() if t.last_time else None, "via": t.last_via,
    }


def machines_snapshot():
    out = []
    for m in Machine.objects.prefetch_related("tags"):
        out.append({"plantId": m.plant_id, "machineId": m.machine_id,
                    "lastSeen": m.last_seen.isoformat() if m.last_seen else None,
                    "tags": [tag_json(t) for t in m.tags.all()]})
    return out


@require_GET
def machines(request):
    return JsonResponse({"machines": machines_snapshot()})


@require_GET
def tag_history(request, tag_id):
    minutes = min(int(request.GET.get("minutes", 15)), 24 * 60)
    since = timezone.now() - timedelta(minutes=minutes)
    try:
        tag = Tag.objects.select_related("machine").get(pk=tag_id)
    except Tag.DoesNotExist:
        return JsonResponse({"error": "unknown tag"}, status=404)
    # downsample to <= ~600 points (epoch buckets: the shared server is PostgreSQL 10, no date_bin)
    bucket_s = max(1, (minutes * 60) // 600)
    with connection.cursor() as cur:
        cur.execute(
            """SELECT to_timestamp(floor(extract(epoch FROM time) / %s) * %s) AS t,
                      avg(value_num), max(value_text)
               FROM bimba_measurement WHERE tag_id = %s AND time >= %s
               GROUP BY 1 ORDER BY 1""", [bucket_s, bucket_s, tag_id, since])
        points = [{"time": t.isoformat(), "num": n, "text": s} for t, n, s in cur.fetchall()]
    return JsonResponse({"tagId": tag.id, "machineId": tag.machine.machine_id, "tag": tag.name,
                         "unit": tag.unit, "bucketSeconds": bucket_s, "points": points})


@require_GET
def deliveries(request):
    """Which end system received the data: per-minute counts by path + recent batches."""
    minutes = min(int(request.GET.get("minutes", 30)), 24 * 60)
    since = timezone.now() - timedelta(minutes=minutes)
    with connection.cursor() as cur:
        cur.execute(
            """SELECT date_trunc('minute', received_at) AS m, via, sum(message_count), sum(duplicate_count)
               FROM bimba_ingest_batch WHERE received_at >= %s GROUP BY 1, 2 ORDER BY 1""", [since])
        series = {}
        for m, via, count, dups in cur.fetchall():
            row = series.setdefault(m.isoformat(), {"minute": m.isoformat(), **{v: 0 for v in VIAS}, "duplicates": 0})
            row[via] = row.get(via, 0) + int(count)
            row["duplicates"] += int(dups)
    recent = [{
        "id": b.id, "batchId": str(b.batch_id) if b.batch_id else None, "via": b.via, "edgeId": b.edge_id,
        "receivedAt": b.received_at.isoformat(), "messages": b.message_count, "inserted": b.inserted_count,
        "duplicates": b.duplicate_count, "firstSeq": b.first_seq, "lastSeq": b.last_seq,
        "lagSeconds": (b.received_at - b.event_time_max).total_seconds() if b.event_time_max else None,
    } for b in IngestBatch.objects.all()[:25]]
    return JsonResponse({"series": list(series.values()), "recent": recent})


@require_GET
def summary(request):
    now = timezone.now()
    last = {}
    with connection.cursor() as cur:
        cur.execute("SELECT via, max(received_at) FROM bimba_ingest_batch GROUP BY via")
        last = {via: t.isoformat() for via, t in cur.fetchall()}
        cur.execute("""SELECT via, sum(message_count) FROM bimba_ingest_batch
                       WHERE received_at > now() - interval '5 minutes' GROUP BY via""")
        share = {via: int(n) for via, n in cur.fetchall()}
        cur.execute("""SELECT count(*) FROM bimba_measurement WHERE received_at > now() - interval '60 seconds'""")
        per_min = cur.fetchone()[0]
    return JsonResponse({
        "serverTime": now.isoformat(),
        "machines": Machine.objects.count(),
        "tags": Tag.objects.count(),
        "measurements": Measurement.objects.count(),
        "messagesPerSecond": round(per_min / 60, 2),
        "lastBatchByVia": last,
        "last5MinByVia": share,
    })


@require_GET
def plugin_status(request):
    """Proxies the BIMBA Gateway's status page (destination health, buffer size)."""
    try:
        with urllib.request.urlopen(settings.PLUGIN_STATUS_URL, timeout=3) as resp:
            return JsonResponse({"reachable": True, **json.loads(resp.read())})
    except Exception as exc:  # noqa: BLE001 - report any failure to the UI
        return JsonResponse({"reachable": False, "error": str(exc)[:200]})


@require_GET
def message_lookup(request):
    """Where and when PostgreSQL stored each message (by canonical messageId), for the packet inspector.
    ?ids=<uuid>,<uuid>,... (max 200). Ids not in the result are not stored (yet)."""
    ids = []
    for raw in request.GET.get("ids", "").split(",")[:200]:
        try:
            ids.append(uuid.UUID(raw.strip()))
        except ValueError:
            continue
    rows = Measurement.objects.filter(message_id__in=ids).values_list("message_id", "received_via", "received_at")
    return JsonResponse({"messages": {str(m): {"via": via, "receivedAt": at.isoformat()} for m, via, at in rows}})


@require_GET
def health(request):
    return JsonResponse({"status": "ok", "time": timezone.now().isoformat()})
