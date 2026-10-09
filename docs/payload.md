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
| `schemaVersion` | `major.minor`, see *Versions* below. The BIMBA Gateway writes `1.1` |
| `tagValue` / `dataType` | number, boolean, string, or null (BAD quality, NaN, no value). `dataType` always matches the value |
| `unit` | the tag's engineering unit. When a tag names its device's unit (`sourceUnit`), values are converted to `unit` first (degF → degC, psi → bar, ...) |
| `quality` | `GOOD` / `UNCERTAIN` / `BAD`. UNCERTAIN = there is a value but it is doubted: the device said so (OPC UA `Uncertain_*`) or it is outside the tag's engineering range (`engLow` / `engHigh`). `qualityDetail` says why when not GOOD |
| `timestamp` | When the value was read (UTC, ISO-8601 with `Z`) |
| `sourceTimestamp` | Since 1.1, optional: the device's own time for the value, when the protocol reports one (OPC UA) |
| `ingestTime` | Since 1.1: when the gateway accepted the message (after its schema check) |
| `edge.session` + `edge.seq` | Gap-free counter per session (a new session starts on module start or config reload), used for loss audits |

## Versions (FR-CAN-05)

The format of one message is the JSON Schema `bimba-gateway/src/main/resources/schemas/telemetry-v1.schema.json`
(every 1.x version). Rules:

* **Minor version** (1.0 → 1.1): only optional fields or new values are added. Every consumer of 1.0 reads 1.1
  unchanged, so **consumers must ignore fields they do not know**. The schema allows unknown fields for this reason.
* **Major version** (1.x → 2.0): a field is renamed, removed, retyped or made required. It gets its own schema file
  (`telemetry-v2.schema.json`), the gateway accepts both majors while senders move over, and existing consumers keep
  receiving the previous major until they are moved.
* The gateway checks every message before routing (FR-RTE-01). Messages that fail go to the **quarantine** with
  their raw text and a reason code (`SCHEMA_INVALID`, `UNSUPPORTED_VERSION`, `MALFORMED_JSON`, `UNKNOWN_MACHINE`),
  where they can be inspected, replayed after a fix, or discarded (gateway console → Quarantine).

| Version | Change |
|---|---|
| 1.0 | first format (site agents with older edge collectors still send it; still valid) |
| 1.1 | `sourceTimestamp`, `ingestTime`; quality `UNCERTAIN` in use; unit conversion to the tag's unit |
| 2.0 | the BRD canonical envelope (FR-CAN-01): snake_case names, `data_type` Double / Boolean / String, `source.path` + `source.interface`, batches as `records` |

### Format 2.0 (BRD canonical envelope)

Schema: `bimba-gateway/src/main/resources/schemas/telemetry-v2.schema.json`. Required: `schema_version`, `message_id`,
`plant_id`, `machine_id`, `tag_name`, `tag_value`, `timestamp`, `quality`.

```json
{
  "schema_version": "2.0",
  "message_id": "0b6c2d6e-3a54-4a8a-9c0e-6f1d2f3a4b5c",
  "plant_id": "PLANT01",
  "machine_id": "PUMP-P1",
  "tag_name": "flow",
  "tag_value": 42.7,
  "data_type": "Double",
  "unit": "m3/h",
  "quality": "GOOD",
  "timestamp": "2026-10-07T10:15:30.250Z",
  "source_timestamp": "2026-10-07T10:15:30.100Z",
  "ingest_time": "2026-10-07T10:15:30.262Z",
  "category": "telemetry",
  "source": { "path": "DIRECT", "protocol": "MODBUS_TCP", "address": "PLC/HR2", "interface": "BIMBA_GATEWAY", "system": "gw01" },
  "edge": { "edge_id": "gw01", "session": "…", "seq": 1842 }
}
```

| 1.x | 2.0 |
|---|---|
| `schemaVersion`, `messageId`, `plantId`, `machineId`, `tagName`, `tagValue`, `qualityDetail`, `sourceTimestamp`, `ingestTime` | the same names in snake_case |
| `dataType` number / boolean / string / null | `data_type` Double / Boolean / String / null (Float4, Int4, … are read too) |
| `source.protocol` opcua / modbus / expression | `source.protocol` OPCUA / MODBUS_TCP / EXPRESSION, plus `source.path` (DIRECT = BIMBA adapters, IGNITION) and `source.interface` (BIMBA_GATEWAY, BIMBA_SITE_AGENT, or N1_MQTT / N2_REST / N3_OPCUA from Ignition) |
| batch `{schemaVersion, batchId, plantId, edgeId, …, messages:[…]}` | batch `{schema_version, batch_id, plant_id, edge_id, …, records:[…]}` |
| — | `attributes`: free key / values, passed on unchanged |

**Who gets which version.** Each destination has a *Message format* setting (console → Destinations): **2.0** or
**1.x**. New destinations start on 2.0; destinations saved before 2.0 existed, the BIMBA database, BIMBA Ingest and the
Boomi services stay on 1.x, so their receivers keep working. Kafka headers and Event Streams properties follow the
destination's version (`batch_id`, `schema_version`, … for 2.0). Templates accept both spellings (`{machine_id}` =
`{machineId}`).

**What the gateway accepts.** Site agents and other senders may send 1.x or 2.x readings, also mixed in one request,
as `{"messages":[…]}` or `{"records":[…]}`. Each is checked against its own schema; inside, the gateway keeps one
layout, so pipelines, selections and the BIMBA database work the same for both.

Expected response (REST/MQTT): any **2xx** means accepted. **400/413/422** means rejected as invalid
(the batch is parked, not retried). Anything else, or a timeout, means the destination is
down: the plugin fails over or buffers.

BIMBA Ingest's response (and Boomi's, when it returns the function result):
`{"status":"ok","via":"bimba-rest","received":2,"inserted":2,"duplicates":0,"rejected":0}`
