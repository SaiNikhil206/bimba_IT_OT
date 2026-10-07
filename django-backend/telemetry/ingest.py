"""Shared ingest entry point for the REST view and the MQTT subscriber."""
import json

from django.db import connection


def ingest_batch(payload, via):
    """payload: parsed JSON (envelope, list or single message). Returns the function's result dict."""
    with connection.cursor() as cur:
        cur.execute("SELECT bimba_ingest(%s::jsonb, %s)", [json.dumps(payload), via])
        result = cur.fetchone()[0]
    return json.loads(result) if isinstance(result, str) else result
