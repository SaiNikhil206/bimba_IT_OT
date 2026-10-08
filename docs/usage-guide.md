# BIMBA: usage guide

How to run, operate, change and troubleshoot the POC day to day. For the background on each
part, see the other files in `docs/`.

```
BIMBA Gateway ──► Boomi Event Streams (primary) ──► Boomi ES listener ──┐
              └─► BIMBA Ingest /api/ingest/ (failover) ──────────────────┼─► Shared PostgreSQL DB (schema bimba) ─► BIMBA (REST + WS) ─► BIMBA live UI :3080
              └─► Kafka (mirror copy, independent)                        │
```

---

## 1. One-time setup

Prerequisite: Docker Desktop running. Run all commands from the project root in PowerShell.

1. **Create `.env`**: copy `.env.example` to `.env` and fill in the values below. Keep secrets
   in this file only. Don't put them in `config.json`, and don't paste them into chat or tickets.

   | Variable | Purpose |
   |---|---|
   | `BIMBA_DB_HOST/PORT/NAME/USER/PASSWORD/SCHEMA` | Shared PostgreSQL (`bimba_chromalab`, schema `bimba`). Required: the stack won't start without them. `.\scripts\set-database.ps1` prompts for them, with a hidden password prompt. |
   | `BOOMI_ES_PRODUCE_TOKEN` | Event Streams token the plugin uses to **produce**. Optional: if it's missing, the plugin uses `BOOMI_ES_CONSUME_TOKEN`. |
   | `BOOMI_ES_CONSUME_URL` / `BOOMI_ES_CONSUME_TOKEN` | Only needed by the Django consumer (`django-es-consumer`) |
   | `BOOMI_INSTALL_TOKEN`, `BOOMI_ATOMNAME`, `BOOMI_ENVIRONMENTID` | Only needed for a local Docker Atom (`--profile boomi`) |

2. **Start the stack** (the gateway builds in a JDK container, so no local Java is needed):
   ```bash
   docker compose --profile kafka up -d --build
   ```

3. **Stop the Django ES consumer.** It shares subscription `BIMBA-SCADA` with the deployed Boomi
   listener, so only one of the two may run (see §5):
   ```bash
   docker compose stop django-es-consumer
   ```

Device connections (Modbus `PLC` → `plc-sim:5020`, OPC UA `METER` → `opc.tcp://opcua-sim:4840/scada/`)
come from `ignition/bimba/console-sources.json`; no manual setup step.

## 2. Everyday use

| URL | Use it for |
|---|---|
| http://localhost:3080 | **Live UI**: machines, tag values, trends, which end system stored each batch, plugin health |
| http://localhost:8090 | BIMBA Gateway console (`admin` / `admin`) |
| http://localhost:8090/system/bimba-status | Gateway status JSON: routes, active destination, last errors, buffer size |
| http://localhost:8000/admin | Django admin (`admin` / `admin`): browse the stored tables |
| http://localhost:8085 | Kafka UI: topic `bimba.PLANT01.telemetry` |

Start and stop:
```bash
docker compose --profile kafka up -d
```
```bash
docker compose --profile kafka stop
```
Logs for one service (`gateway`, `django`, `ui`, `kafka` …):
```bash
docker compose logs -f --tail 100 gateway
```

### Reading the BIMBA live UI
* **KPIs** (top): machine and tag counts, messages per second, and total stored rows.
* **Machine grid**: the latest value and quality per tag. Click a numeric tag to open its trend.
* **Trend**: history of the selected tag. Choose the time window at the top right of the panel.
* **Received by end system**: messages per minute, split by the path that stored them:
  `boomi-es` means the primary path is working, and `bimba-rest` means the plugin is in failover.
* **Incoming batches**: the most recent batches, with seq range, duplicates and lag.
* **Gateway**: the state of each destination (Up / Down / Idle) and its last error,
  and whether the plugin is buffering to disk.

**Healthy state:** new batches arrive as `boomi-es`, the `boomi-eventstreams` destination is **Up**,
and the buffer is 0. When the batches arrive as `bimba-rest` instead, check the plugin panel for the
Event Streams error (for example 401, which means a bad token).

## 3. Common tasks

### Change the Event Streams token
1. Edit `BOOMI_ES_PRODUCE_TOKEN` in `.env`.
2. Recreate the gateway so it picks up the new value:
   ```bash
   docker compose --profile kafka up -d gateway
   ```
3. Within about 15 s, `boomi-eventstreams` should show **Up**, and new batches arrive as `boomi-es`.

`config.json` references the token as `"${BOOMI_ES_PRODUCE_TOKEN}"`. Any config value can use
the `${ENV_VAR}` form, and that's how secrets stay out of the file.

### Change plugin behaviour (`ignition/bimba/config.json`)
The plugin **hot-reloads** this file within about 10 s, so no restart is needed. Useful keys:

| Key | Effect |
|---|---|
| `plantId`, `edgeId` | Identity stamped on every message |
| `tagRoots` | Tag folders to stream (default `[default]BIMBA`) |
| `batch.maxMessages` / `batch.maxWaitMs` | Batch size and time limits |
| `buffer.maxMegabytes` | Disk store-and-forward cap per route |
| `destinations[].enabled` | Turn a destination on or off |
| `destinations[].role` | `primary`, `failover`, or `mirror` (an independent copy with its own buffer) |

Only one `primary` and one `failover` should be enabled at a time. You can enable any number of mirrors.

### Add machines or tags
Add them in BIMBA Operations → Settings (saved to `ignition/bimba/demo-tags.json`, Ignition
tag-export layout under `[default]BIMBA/<machineId>/<tagName>`). The gateway picks up the change
and the new machine appears in the UI automatically. Values are sent on change.

### Send a copy to Kafka or another IT system
Kafka is already enabled as a mirror. For a webhook, Confluent, MSK, Event Hubs and so on, set
`enabled: true` on `it-webhook`, or add a new `"role": "mirror"` destination. For examples, see
[it-receivers.md](it-receivers.md).

### Switch the failover to MQTT
```bash
docker compose --profile kafka --profile mqtt up -d
```
Then, in `config.json`, disable `bimba-ingest` and enable `bimba-ingest-mqtt`.

### Rebuild after code changes
| Changed | Command |
|---|---|
| BIMBA Gateway (`bimba-gateway/`) | `docker compose up -d --build gateway` |
| BIMBA Ingest + REST/WS (`django-backend/`) | `docker compose up -d --build django` |
| BIMBA live UI (`react-ui/`) | `docker compose up -d --build ui` |

## 4. Querying the data
Everything is in schema **`bimba`** of `bimba_chromalab`. **Never modify schema `public`**: it
belongs to the Chromalab application.

```sql
-- latest value per tag
SELECT m.machine_id, t.name, t.last_value_num, t.last_value_text, t.last_quality, t.last_time, t.last_via
FROM bimba.bimba_tag t JOIN bimba.bimba_machine m ON m.id = t.machine_id
ORDER BY 1, 2;

-- which path stored data in the last hour
SELECT via, count(*), sum(message_count) FROM bimba.bimba_ingest_batch
WHERE received_at > now() - interval '1 hour' GROUP BY via;
```

REST API (read-only, unauthenticated in this POC), on http://localhost:8000/api/:

| Endpoint | Returns |
|---|---|
| `machines/` | Machines with their tags and latest values |
| `tags/<id>/history/?minutes=15` | Downsampled history (up to 24 h) |
| `deliveries/?minutes=30` | Per-minute counts by path, and the last 25 batches |
| `summary/` | KPIs, and the last batch per path |
| `plugin-status/` | Proxy of the plugin's status page |
| `health/` | Liveness check |
| `ingest/` (POST, header `X-API-Key`) | Failover ingest endpoint used by the plugin |

## 5. Who consumes the Event Streams subscription
Subscription `BIMBA-SCADA` should have **exactly one** consumer. Otherwise messages get split
between the two.

| Consumer | Status | Run it by |
|---|---|---|
| Boomi process **"BIMBA ES Listener to PostgreSQL"** (env `1_ESI_QA`) | **Deployed, in use** | Managed in Boomi |
| Django `django-es-consumer` | Standby | `docker compose stop django-es-consumer` while Boomi runs |

Note: `docker compose up -d` without a service name **starts `django-es-consumer` again**.
Stop it afterwards, as in §1 step 4. Both consumers store rows as `via = 'boomi-es'`.

## 6. Troubleshooting

| Symptom | Check / fix |
|---|---|
| `docker compose up` fails with "set BIMBA_DB_HOST" | Fill `BIMBA_DB_*` in `.env` (`.\scripts\set-database.ps1`) |
| Batches arrive as `bimba-rest`, not `boomi-es` | Plugin panel or `/system/bimba-status`: a **401** means a bad or expired token (§3). Timeouts point to network or proxy problems. |
| `boomi-es` batches stop, plugin says ES is Up | The Boomi listener isn't running (check Process Reporting in Boomi), or `django-es-consumer` is competing for the subscription |
| UI shows "Unreachable" for the gateway | `docker compose ps gateway`, then `docker compose logs gateway`. |
| Gateway buffer keeps growing | All destinations on the route are down. Data is safe on disk (volume `gateway-data`) and replays in order after recovery. |
| `SUBSTN-M1` tags are BAD | The OPC UA device `METER` is missing from `console-sources.json`, or `opcua-sim` isn't running |
| `PUMP-P1` / `TANK-T1` are BAD | The Modbus device `PLC` is missing, or `plc-sim` isn't running |
| UI not loading on :3080 | Port 3000 is used by another app, so the UI is deliberately on **3080**. Check `docker compose ps ui`. |
| Duplicates counted in batches | This is expected after a retry or replay: delivery is at-least-once, and `bimba_ingest()` de-duplicates on `messageId` |

## 7. POC limits (read before any production use)
* The BIMBA REST read API and WebSocket have no authentication. Traffic inside Docker is plain HTTP.
* The shared DB is PostgreSQL 10, so Django is pinned to the end-of-life 4.0 release.
* There is no retention or partitioning on `bimba_measurement`.
