# BIMBA IT/OT Agent: POC

An **installable plugin for the SCADA system** (an Ignition gateway module) that streams
tag data to two end systems: **Boomi Event Streams (primary)**, consumed by a Boomi
process, and **BIMBA Ingest (failover, Python Django)**, over REST or MQTT. It can also **copy every
batch to Kafka or any other IT receiver** (webhook, MQTT, Confluent/MSK/Event Hubs),
each as an independent route with its own disk buffer. See [docs/it-receivers.md](docs/it-receivers.md). Both end systems store it in the **shared PostgreSQL DB** (schema `bimba`), and the
**BIMBA live UI** (React) shows it live.

```
Modbus SCADA sim ─┐
OPC UA meter sim ─┴─► Ignition tags [default]BIMBA/<machine>/<tag>
                                │ on change
                                ▼
                    BIMBA IT/OT Agent (.modl)
                    canonical JSON · batches · failover + failback · disk store-and-forward
                     │                        │                          │
             primary │ HTTPS + env token      │ failover: REST / MQTT    │ mirror route
                     ▼                        ▼                          ▼
          Boomi Event Streams           BIMBA Ingest                   Kafka ──► IT consumers
          topic BIMBA / sub BIMBA-SCADA (/api/ingest/)
                     │                        │
          Boomi ES listener                   │
          via 'boomi-es'                      │ via 'bimba-rest' / 'bimba-mqtt'
                     └───────────┬────────────┘
                                 ▼  SELECT bimba.bimba_ingest(batch, via)
                      Shared PostgreSQL DB (schema bimba)
                                 │ NOTIFY bimba_live
                                 ▼
                      BIMBA (REST + WS)   /api/*  ·  /ws/live/
                                 │ WebSocket
                                 ▼
                      BIMBA live UI  :3080
```

## Quick start
Prerequisite: Docker Desktop. For day-to-day operation, see the **[usage guide](docs/usage-guide.md)**.

```bash
.\scripts\build-module.ps1
```
```bash
docker compose --profile kafka up -d kafka kafka-ui
```
```bash
docker compose up -d --build
```

| URL | What |
|---|---|
| http://localhost:3080 | **BIMBA live UI** |
| http://localhost:3080/console | **OT/IT Bridge Console**: devices, Modbus/OPC UA tag mapping, Kafka + Event Streams destinations (saved to the plugin config), real connection tests, live data flow. No login (POC) |
| http://localhost:8088 | Ignition gateway (`admin` / `password`) |
| http://localhost:8088/system/bimba-status | Plugin status (JSON) |
| http://localhost:8000/admin | Django admin (`admin` / `admin`) |
| http://localhost:8000/api/ | BIMBA REST API, WebSocket `/ws/live/` (`machines/`, `tags/<id>/history/`, `deliveries/`, `summary/`) |
| `103.120.176.105:6433` | Shared PostgreSQL `bimba_chromalab`, schema `bimba` (see [docs/database.md](docs/database.md)); set in `.env` with `scripts/set-database.ps1` |
| `localhost:1883` | MQTT broker, only for the MQTT transport (`docker compose --profile mqtt up -d`) |
| http://localhost:8085 | Kafka UI (`docker compose --profile kafka up -d`); bootstrap `localhost:29092` |

One-time step on a fresh Ignition volume: create the Modbus device `PLC` → `plc-sim:5020`
(and optionally the OPC UA connection `METER`). See [docs/ignition-setup.md](docs/ignition-setup.md).

Boomi Event Streams: create a topic + environment token, paste the topic's REST URL and
token into `ignition/bimba/config.json` (destination `boomi-eventstreams`), start the Atom
(`.env` + `docker compose --profile boomi up -d`) and build the listener process, following
[docs/boomi-setup.md](docs/boomi-setup.md). Until Event Streams accepts data, the plugin fails
over to BIMBA Ingest automatically.

## Components

| Folder | What it is |
|---|---|
| `ignition-module/` | **BIMBA IT/OT Agent**, the SCADA plugin (Java, Ignition SDK 8.1). Subscribes to tags, builds canonical JSON, batches, delivers to Boomi Event Streams / REST / MQTT with primary→failover, disk buffer, `/system/bimba-status` |
| `django-backend/…/es_consume.py` | Event Streams **consumer**: consume REST API (batch, long poll) → spool → `bimba_ingest(…,'boomi-es')` (service `django-es-consumer`) |
| `docs/it-receivers.md` | Kafka and other IT receivers (mirror routes, Confluent/MSK/Event Hubs/webhook/Splunk examples) |
| `tools/eventstreams-mock/` | **Test-only** stand-in for the Event Streams produce REST API (profile `es-mock`) |
| `ignition/bimba/` | Plugin `config.json` (destinations, auth, batching) + demo tag export |
| `django-backend/` | **BIMBA Ingest** (end system #2, Django) + **BIMBA (REST + WS)**: `POST /api/ingest/` (API key), MQTT ingest command, read API, WebSocket `/ws/live/`, admin |
| `django-backend/telemetry/sql/bimba_ingest.sql` | **Shared write path**: the one SQL function both Boomi and BIMBA Ingest call (dedupe, latest values, NOTIFY) |
| `react-ui/` | **BIMBA live UI** (React + Vite), served by nginx on :3080 |
| `simulators/` | Modbus SCADA sim (Modbus TCP PLC) + OPC UA meter that Ignition reads (stand-ins for real field devices) |
| `docs/` | Ignition install, Boomi process, payload format |

## Data model (PostgreSQL)
| Table | Content |
|---|---|
| `bimba_machine` | plant / machine (from the Ignition folder) |
| `bimba_tag` | tag master + **latest value, quality, time, which end system stored it** |
| `bimba_measurement` | full history, unique `message_id` (idempotent), plugin `seq`, `received_via` |
| `bimba_ingest_batch` | one row per batch per end system: counts, duplicates, seq range |

## Verified in this POC
| Test | Result |
|---|---|
| Module install on Ignition 8.1.55 | Loads, imports 23 demo tags, subscribes to 18 |
| Event Streams destination (against the local stand-in) | Batches produced in the documented `messages[]` format with Bearer token; `batch` and `message` modes |
| Event Streams 503 / wrong token (401) | Fails over to BIMBA Ingest within one batch; fails back ≤ 15 s after recovery; error visible in status page |
| Full loop plugin → Event Streams (single-message produce) → consumer (batch consume) → PostgreSQL | 320 messages in 32 batches stored as `boomi-es`, seq 417/417 |
| Database down 22 s while consuming (messages already acked by Event Streams) | Held in spool, consumption paused, replayed on recovery: seq 974/974, no gaps |
| Real Boomi endpoint reachable from the plugin | `usa-east-web.eventstreams.boomi.com` answers (401 until the token is entered) |
| Kafka mirror route | Records with key `edgeId` + headers; Kafka down 40 s → main route unaffected (286 rows stored), Kafka route buffered 37 batches and replayed: seq 1–3,324, 0 missing, 0 duplicates |
| Event Streams payload stored as `boomi-es` | `bimba_ingest()` accepts both payload shapes (batch envelope, single message) |
| REST and MQTT transports | Both deliver (plugin → BIMBA Ingest REST, plugin → broker → subscriber) |
| Duplicate batch | Stored once (`duplicates` counted, no double rows) |
| Both end systems down 45 s | 18 batches buffered to disk, **672 messages replayed**, seq 1–4,466 with **0 gaps** |
| Live UI | WebSocket updates within ~1 s of storage, whichever end system stored the data |

## Not done yet / production gaps
* **Not yet run against your real Boomi Event Streams tenant.** The plugin was verified
  against a local stand-in that follows Boomi's documented produce API. Confirm with your
  topic URL and token.
* The Boomi listener **"BIMBA ES Listener to PostgreSQL"** is deployed to `1_ESI_QA` and
  writes to the shared database `bimba_chromalab` (schema `bimba`). See
  [docs/boomi-setup.md](docs/boomi-setup.md) §3 Option B and [docs/database.md](docs/database.md).
  `django-es-consumer` is stopped because it shares the subscription.
* The plugin's Event Streams destination still returns **401**: enter a valid produce token
  with `.\scripts\set-eventstreams.ps1`. Until then data reaches the DB via BIMBA Ingest (failover, stored as `bimba-rest`).
* The shared DB runs PostgreSQL 10, so Django is pinned to the end-of-life 4.0 release
  (see [docs/database.md](docs/database.md)).
* The module is unsigned (dev flag), and its config lives in a JSON file with credentials
  in plain text. Production needs a signed module, a Gateway config page, and secrets in
  Ignition's encrypted settings.
* The BIMBA REST read API and WebSocket are unauthenticated (POC). Only `/api/ingest/` needs a key.
* Plain HTTP / MQTT without TLS inside the Docker network. Use HTTPS and MQTTS across real networks.
* No retention or partitioning on `bimba_measurement`. Add a partition/retention policy for real volumes.
* `SUBSTN-M1` stays BAD until the OPC UA connection `METER` is created in Ignition.

The earlier broker-based edge/cloud POC is kept in `_archive/brd-edge-cloud-poc/`.
