# Payload sent by the BIMBA IT/OT Agent

**Boomi Event Streams** (`transport: eventstreams`, primary): the batch below is sent as the
`payload` string of an Event Streams message:

```json
{"messages":[{"payload":"{\"schemaVersion\":\"1.0\",\"batchId\":\"c983b2f5-…\",\"messages\":[…]}",
              "properties":{"schemaVersion":"1.0","batchId":"c983b2f5-…","plantId":"PLANT01",
                            "edgeId":"ignition-gw01","source":"bimba-it-ot-agent","count":"14"}}]}
```
With `esMode: "message"`, each tag value is its own Event Streams message (payload = one canonical
message; properties add `messageId`, `machineId`, `tagName`). The Boomi listener receives the payload as
its document, so it sees exactly the JSON below.

**REST / MQTT** (BIMBA Ingest, or the Boomi alternatives): one HTTP POST body or one MQTT message per batch:

```json
{
  "schemaVersion": "1.0",
  "batchId": "c983b2f5-400b-4b2f-b50d-c4ef5bfa43e7",
  "plantId": "PLANT01",
  "edgeId": "ignition-gw01",
  "source": "ignition",
  "createdAt": "2026-09-28T17:21:51.201Z",
  "count": 2,
  "messages": [
    {
      "schemaVersion": "1.0",
      "messageId": "5f0c8e3a-2d4b-4e0b-9a51-0b8f6f7d9a10",
      "plantId": "PLANT01",
      "machineId": "TANK-T1",
      "timestamp": "2026-09-28T17:21:50.998Z",
      "tagName": "level",
      "tagValue": 60.74,
      "dataType": "number",
      "unit": "%",
      "quality": "GOOD",
      "category": "telemetry",
      "source": { "protocol": "ignition", "system": "ignition-gw01", "address": "[default]BIMBA/TANK-T1/level" },
      "edge": { "edgeId": "ignition-gw01", "session": "a0fa2d9b-628d-42ba-a6a5-140ffcda6129", "seq": 3026 }
    },
    {
      "schemaVersion": "1.0",
      "messageId": "0e7f5b8c-6a0d-4a8e-8f6b-3f1f2b8f1c22",
      "plantId": "PLANT01",
      "machineId": "SUBSTN-M1",
      "timestamp": "2026-09-28T17:21:51.100Z",
      "tagName": "bus_voltage",
      "tagValue": null,
      "dataType": "null",
      "unit": "V",
      "quality": "BAD",
      "qualityDetail": "<Ignition QualityCode, e.g. Bad_...>",
      "category": "telemetry",
      "source": { "protocol": "ignition", "system": "ignition-gw01", "address": "[default]BIMBA/SUBSTN-M1/bus_voltage" },
      "edge": { "edgeId": "ignition-gw01", "session": "a0fa2d9b-628d-42ba-a6a5-140ffcda6129", "seq": 3027 }
    }
  ]
}
```

| Field | Notes |
|---|---|
| `messageId` | Unique per value change. End systems **de-duplicate** on it (delivery is at-least-once) |
| `tagValue` / `dataType` | number, boolean, string, or null (bad quality / NaN) |
| `quality` | `GOOD` / `UNCERTAIN` / `BAD`. `qualityDetail` carries Ignition's quality code when not good |
| `timestamp` | Source timestamp from Ignition (UTC) |
| `edge.session` + `edge.seq` | Gap-free counter per session (a new session starts on module start or config reload), used for loss audits |

Expected response (REST/MQTT): any **2xx** means accepted. **400/413/422** means rejected as invalid
(the batch is parked, not retried). Anything else, or a timeout, means the destination is
down: the plugin fails over or buffers.

BIMBA Ingest's response (and Boomi's, when it returns the function result):
`{"status":"ok","via":"bimba-rest","received":2,"inserted":2,"duplicates":0,"rejected":0}`
