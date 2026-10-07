# BIMBA IT/OT Integration Framework – POC

This proof of concept for the *BIMBA IT OT Integration Framework* BRD reads plant data
from simulated SCADA/OT systems over five protocols. It normalizes everything into one
canonical JSON model and forwards it from edge to cloud over TLS, with disk-backed
store-and-forward. The cloud side stores it in TimescaleDB, shows it in Grafana and
hands it to Boomi for enterprise routing.

No physical SCADA hardware is needed. Every device is a Docker container that behaves
like the real thing: Modbus TCP PLC, OPC UA server, Sparkplug B edge node and legacy
SCADA historian. A real device only needs a host/port change in `edge/agent/config.yaml`.

## Architecture (Model B – hybrid edge-to-cloud)

```
 ┌──────────────── PLANT network (Purdue L0–L3) ────────────────┐        ┌──────────────── CLOUD network ─────────────────────┐
 │ plc-sim        Modbus TCP PLC (pump station)   :5020 ─┐      │        │                                                    │
 │ opcua-sim      OPC UA server (substation meter) :4840 ─┤      │        │  cloud-mqtt ──► ingest ──► TimescaleDB ──► Grafana │
 │ legacy-scada   SCADA XML historian (REST)       :8080 ─┤      │  WAN   │   :8883 mTLS    │                            :3000   │
 │ legacy-scada-db SCADA SQL DB (operator journal)       ─┼─►edge-agent ─► edge-mqtt ══TLS══►│   :1883 ──────►  Boomi Atom ──► Event Streams     │
 │ mqtt-devices   raw JSON filler + Sparkplug B press ────┘  normalize   disk queue │ bridge │                   (profile)   ──► ERP / HRM mock  │
 │                                                           canonical   store&fwd  │ (out)  │                                    :8090          │
 └───────────────────────────────────────────────────────────────────────────────────┘        └────────────────────────────────────────────────────┘
```

| Layer | Component | Role |
|---|---|---|
| OT devices | `plc-sim`, `opcua-sim`, `mqtt-devices`, `legacy-scada`, `legacy-scada-db` | Simulated plant with realistic dynamics and faults: pump trips, voltage sags, press faults, filler stoppages, operator actions |
| Edge | `edge-agent` | Protocol extraction (FR-1), canonical mapping (FR-3), alarm limits, per-source comm status |
| Edge | `edge-mqtt` (Mosquitto) | Local broker (FR-2); persistent disk queue; **outbound-only** mutual-TLS bridge (NFR-1/2) |
| Cloud | `cloud-mqtt` (Mosquitto) | Stand-in for the cloud ingestion endpoint (Boomi Event Streams / managed MQTT) |
| Cloud | `ingest` → `db` → `grafana` | Historian + enterprise dashboards + zero-loss audit |
| Cloud | `boomi-atom` (profile `boomi`) | Boomi runtime: routes canonical events to ERP / HRM / Event Streams (FR-4) |
| Cloud | `enterprise-mock` | Mock ERP/HRM/data-lake REST endpoints that Boomi delivers to |

## Quick start

Prerequisite: Docker Desktop.

```powershell
docker compose up -d --build
```

| URL | What |
|---|---|
| http://localhost:3000 | Grafana: **BIMBA IT/OT Overview** (anonymous view, or admin/admin) |
| http://localhost:8080/historian/snapshot.xml | Legacy SCADA XML payload |
| http://localhost:8090/inbox | What Boomi delivered to the mock ERP/HRM |
| `localhost:5020` | Modbus TCP PLC (test with Modbus Poll / QModMaster) |
| `opc.tcp://localhost:4840/scada/` | OPC UA server (browse with UaExpert) |
| `localhost:1883` | Edge (plant) MQTT broker (MQTT Explorer: raw + Sparkplug + canonical) |
| `localhost:1884` | Cloud MQTT broker, internal listener (canonical only) |
| `localhost:5433` | TimescaleDB `scada` / `scada`, database `scada` |

Start Boomi too (see [docs/boomi-integration.md](docs/boomi-integration.md)):

```powershell
copy .env.example .env      # then paste your Atom installer token into .env
docker compose --profile boomi up -d
```

## Demo script: zero data loss on WAN failure (NFR-2)

```powershell
.\scripts\wan.ps1 down      # cut plant <-> cloud. Dashboard: WAN bridge DOWN (~20 s), cloud data stops
                            # ...wait a minute: the edge keeps collecting and queues to disk
.\scripts\wan.ps1 up        # link restored: the backlog flushes and the latency spike shows the replay
Get-Content scripts\check-data-loss.sql | docker compose exec -T db psql -U scada -d scada -f -
```

Every canonical message carries `edge.session` + `edge.seq` (gap-free). The
`delivery_gaps` view compares what the edge sent with what the cloud received.
**Result in this POC:** 60 s outage, ~1,080 messages queued at the edge and replayed,
2,754 of 2,754 delivered, `missing = 0`.

## Canonical model (FR-3)

Topic: `bimba/v1/{plantId}/{category}/{machineId}/{tagName}` · schema: [docs/canonical-schema.json](docs/canonical-schema.json)

```json
{"schemaVersion":"1.0","messageId":"d8a80714-…","plantId":"PLANT01","machineId":"SUBSTN-M1",
 "timestamp":"2026-09-28T16:39:06.385Z","tagName":"bus_voltage","tagValue":401.32,"dataType":"number",
 "unit":"V","quality":"GOOD","category":"telemetry",
 "source":{"protocol":"opcua","system":"substation_meter","address":"ns=2;s=Meter.Voltage"},
 "edge":{"edgeId":"plant01-edge","session":"93a437a7-…","seq":3974}}
```

| Category | Example sources | BRD target (matrix §6) |
|---|---|---|
| `equipment_status` | pump running/tripped, breaker, filler state, press state, burner | ERP production tracking |
| `telemetry` | levels, flows, pressures, power, press cycles | Event Streams / data lake / dashboards |
| `operator_log` | legacy SCADA operator journal | HRM shift adherence |
| `alarm` | limit/state alarms raised at the edge | notifications / CMMS |
| `status` | per-source communication health | integration monitoring |

## BRD traceability

| Req | How the POC meets it | Where |
|---|---|---|
| FR-1 Protocol extraction | Modbus TCP poll, OPC UA read, SCADA XML over REST, SCADA SQL incremental poll (JDBC-style), MQTT raw JSON subscribe, Sparkplug B host (BIRTH/DEATH/DATA, aliases, rebirth request) | `edge/agent/edge_agent.py` |
| FR-2 Broker agnosticism | Standard MQTT 3.1.1 everywhere; broker is config (`edge/agent/config.yaml`, Boomi environment extension). Mosquitto is packaged, and any broker can replace it | `edge/mosquitto`, `cloud/mosquitto` |
| FR-3 Normalization | Sparkplug B protobuf, raw JSON, legacy SCADA XML, Modbus registers, OPC UA nodes, SQL rows → one canonical schema | `docs/canonical-schema.json` |
| FR-4 Cloud handover | Canonical topics on the cloud broker, consumed by Boomi (→ Event Streams) and the ingest service | `docs/boomi-integration.md` |
| NFR-1 Security | Edge → cloud bridge is outbound-only, TLS 1.2+ with **mutual** certificate authentication; raw device topics never leave the plant | `certs/gen-certs.sh`, bridge config |
| NFR-2 Resiliency | Persistent sessions on both sides of the bridge; disk-persisted queue (2M msgs); idempotent cloud writes (dedupe on `messageId`); gap audit | `scripts/wan.ps1`, `delivery_gaps` |
| NFR-3 Scalability | Batched DB writes (1,000/txn), TimescaleDB hypertables + 1-min continuous aggregates, retention policies. Load tests at thousands of tags/s are a next step | `cloud/db/init.sql` |

## Repository layout

```
docker-compose.yml          all services, 3 networks (plant / wan / cloud)
simulators/                 virtual OT devices (Modbus PLC, OPC UA, MQTT JSON + Sparkplug B, legacy SCADA)
shared/sparkplug_b.proto    Sparkplug B payload definition (Tahu-compatible subset)
edge/agent/                 BIMBA edge agent + config.yaml (tag mapping, alarms)
edge/mosquitto/             plant broker + TLS bridge (store-and-forward)
cloud/mosquitto/            cloud broker (mTLS listener 8883, internal 1883)
cloud/ingest/               canonical → TimescaleDB, mock enterprise endpoints
cloud/db/init.sql           schema, hypertables, views (latest_values, active_alarms, delivery_gaps)
cloud/grafana/              provisioning + dashboard (regenerate: python cloud/grafana/build_dashboard.py)
certs/gen-certs.sh          POC CA, broker + edge client certificates
scripts/                    wan.ps1 (outage simulation), check-data-loss.sql
docs/                       canonical schema, Boomi integration guide
```

## Connecting real equipment

Edit `edge/agent/config.yaml`, then run `docker compose restart edge-agent`:
* **Modbus PLC/RTU:** set `host`, `port`, `unit_id`, and per tag `type`/`address`/`scale`.
* **OPC UA:** set `url` and per tag `node` (NodeId from UaExpert).
* **SCADA database:** set `dsn` and `query` (must accept the last key as `$1`).
* **MQTT / Sparkplug B:** point `mqtt.host` at the plant broker, or bridge the existing broker to `edge-mqtt`.

## POC limitations and next steps
* OPC UA and the cloud-internal MQTT listener run without security for simplicity. In
  production, enable OPC UA `Basic256Sha256` + certificates and MQTT authentication/ACLs.
* The ingest service acknowledges MQTT messages before its DB commit, so a crash in that
  window can drop a batch. The Boomi path and the edge→cloud path are not affected.
* The edge broker autosaves its queue every 5 s. A hard power loss at the edge can lose
  up to 5 s of queued messages. WAN outages lose nothing.
* Next: Boomi OPC UA custom connector (Model A), Kafka target, load test at NFR-3 scale,
  and write-back (setpoint) commands with approval workflow.
