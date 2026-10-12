# Boomi setup: Event Streams (primary end system)

The BIMBA IT/OT Agent publishes every batch **straight into a Boomi Event Streams topic**,
using the Event Streams *produce REST API*. A Boomi process **listens** on the topic
and stores each message in PostgreSQL by calling the same SQL function BIMBA Ingest uses,
`bimba_ingest()`. If Event Streams refuses or doesn't answer, the plugin fails over to
BIMBA Ingest and retries Event Streams every `retryAfterSeconds` (failback).

```
BIMBA Setu
   │  POST https://usa-east-web.eventstreams.boomi.com/rest/singlemsg/<account>/<env>/BIMBA
   │  Authorization: Bearer <environment token>   x-msg-props-batchId / plantId / edgeId / count
   │  body: <batch JSON>
   ▼
Boomi Event Streams  topic: BIMBA                   (durable, replayable, fan-out)
   │  subscription: bimba-db-writer                 (+ any other subscriptions: ERP, data lake, ...)
   ▼
Boomi process "BIMBA ES → PostgreSQL"  (Event Streams connector: Listen)
   ▼
Database connector:  SELECT bimba_ingest(?::jsonb, 'boomi-es')
   ▼
PostgreSQL ──NOTIFY──► BIMBA (REST + WS) ──► BIMBA live UI  (chart shows "Boomi · Event Streams")
```

Why this split works: the plugin only needs outbound HTTPS to Boomi's cloud, with no
inbound listener on the Atom. Event Streams buffers if the database process is
stopped. Other systems can subscribe to the same topic without touching the plant.

## 1. Create the topic and token (Boomi platform → Event Streams)
1. Select the environment the Atom is attached to, for example `BIMBA-POC`.
2. **Create a topic**. This POC uses `BIMBA`.
3. **Create a subscription** on it, for example `bimba-db-writer`. Optionally configure
   a dead-letter topic for messages the process can't store.
4. **Environment Tokens → create a token** with *produce* permission for the plugin.
   The listener process uses a token with *consume* permission.
5. On the topic, click **View REST** and copy the **REST URL**. Either tab works:
   * **Single message** (`…/rest/singlemsg/<account>/<environment>/<topic>`): the plugin sends the
     payload as the request body (`application/json`) with properties as `x-msg-props-*` headers.
     This is the URL used in this POC:
     `https://usa-east-web.eventstreams.boomi.com/rest/singlemsg/easystepinitservicesllc-4ZNYGO/d98ab861-8bc0-4a65-a6f2-a8dcc47b4e94/BIMBA`
   * **Multiple messages**: the plugin sends `{"messages":[{"payload":…,"properties":…}]}`.

   The plugin detects `/singlemsg/` automatically (`"esApi": "auto"`; force it with `"single"` / `"multi"`).
   With the single-message URL and `esMode: "message"`, every tag value is a separate request, so keep
   `batch` mode unless you really need per-tag messages (rate limit: 60,000 requests / 5 min per IP).

## 2. Point Setu at the topic
In the BIMBA Setu console (http://localhost:8090) open **Destinations → boomi-eventstreams**,
paste the topic's REST URL and the environment token (produce), and save. Destinations live in Setu database (secrets encrypted), not in `config.json`. Keep the token out of chat, email and git.
The destination's settings:

```json
{
  "name": "boomi-eventstreams",
  "role": "primary",
  "enabled": true,
  "transport": "eventstreams",
  "url": "<REST URL copied from View REST>",
  "esMode": "batch",
  "auth": { "type": "bearer", "token": "<environment token (produce)>" },
  "timeoutMs": 5000,
  "retryAfterSeconds": 15
}
```

| `esMode` | Event Streams message = | Use when |
|---|---|---|
| `batch` (default) | one plugin batch (up to 500 tag values, typically 5–200 KB) | storing to a database. Fewest requests and process executions |
| `message` | one tag value (canonical message). Properties also carry `messageId`, `machineId`, `tagName` | consumers that filter or route per machine/tag |

Limits from Boomi's Event Streams REST API docs:
- 5 MB per message
- 10 MB per request
- 60,000 requests per IP per 5 minutes

With 1-second batches the plugin sends roughly 300 requests per 5 minutes. A request
over 10 MB is parked in `buffer/rejected/` rather than retried, and the fix is to lower
`batch.maxMessages`.

The plugin's handling of Event Streams responses:
| Response | Plugin action |
|---|---|
| 200 `{"status":"success"}` | delivered |
| 401/403 (token), 404 (topic), 429 (rate limit), 5xx, timeout | Event Streams marked **Down**, fail over to BIMBA Ingest, retry after `retryAfterSeconds` |
| 400/413/422 | batch rejected as invalid → `buffer/rejected/` |

## 3. Store the topic in PostgreSQL: pick one consumer per subscription

Two consumers are available. Use **one** of them on subscription `BIMBA-SCADA`. If you
run both on the same subscription, they share the messages rather than each getting all
of them. To run both, give each its own subscription.

### Option A: Django Event Streams consumer (built, service `django-es-consumer`)
It polls the subscription through the **consume REST API** (batch endpoint, long poll,
up to 100 messages per call) and stores each batch with `bimba_ingest(…, 'boomi-es')`.

```bash
.\scripts\set-eventstreams-consumer.ps1
```
The script asks for the consume URL (Enter keeps
`https://usa-east-web.eventstreams.boomi.com/rest/consume/easystepinitservicesllc-4ZNYGO/d98ab861-8bc0-4a65-a6f2-a8dcc47b4e94/BIMBA/BIMBA-SCADA`)
and a **consume** token (hidden prompt). It writes both to `.env` and starts the consumer.
Follow it with `docker compose logs -f django-es-consumer`.

Event Streams **acknowledges a message as soon as it is delivered**, so the consumer
writes every response to a local spool (`es-spool` volume, fsync) before touching the
database. While the database is down it holds the spool and stops pulling, so the rest
stays in Event Streams. On recovery it replays the spool. Duplicates are ignored via
`messageId`. A payload the database rejects as invalid is parked as `*.rejected` in
the spool instead of blocking the stream.

### Option B: Boomi process "BIMBA ES Listener to PostgreSQL" (built, deployed)
The process is **deployed** to environment `1_ESI_QA` (folder **`BIMBA-SCADA-ESToPostgres`**)
and writes to the shared database `bimba_chromalab` (schema `bimba`, see
[database.md](database.md)). It listens on subscription **`BIMBA-SCADA`**, the same one
`django-es-consumer` reads, so **that service is stopped**. Don't start it again while the
Boomi listener is deployed, or the two will split the messages.

```
BIMBA ES Listener to PostgreSQL   (Start: Event Streams Listen, topic BIMBA / sub BIMBA-SCADA)
  └► Process Call ► [SUB] BIMBA Store Batch in PostgreSQL   (passthrough start)
        Try/Catch (1 retry)
          ├ Try:   Data Process: Base64 Encode + Search/Replace \s → ""
          │        Message: '{"payload":"'{1}'"}'                 (1 = current document)
          │        Database V2 (Standard Get):
          │          SELECT CAST(bimba.bimba_ingest(CAST(convert_from(decode($payload,'base64'),'UTF8')
          │                 AS jsonb), 'boomi-es') AS text) AS result
          │        Notify result ► Return Documents "Stored"
          └ Catch: Notify error ► Exception (fails the document → Event Streams redelivers)
```

| Component | Type | ID |
|---|---|---|
| BIMBA ES Listener to PostgreSQL | process (listener) | `d35043ef-7d61-4b96-9cb1-b40c0a436329` |
| [SUB] BIMBA Store Batch in PostgreSQL | process (subprocess) | `08ba07c0-cf7f-461d-8e49-8decbbdcd4e3` |
| BIMBA ES Connection | Event Streams connection (token `BIMBA`) | `ff3287b2-eee6-4ac3-a03b-5a2ae99b764c` |
| BIMBA ES Listen BIMBA-SCADA | Listen operation | `ec7ea60e-eaf4-43a5-8ea7-1dac064ce7ab` |
| BIMBA PostgreSQL Connection | Database V2 connection, `jdbc:postgresql://103.120.176.105:6433/bimba_chromalab` (password encrypted in Boomi) | `4301ca1b-f0d5-45fe-b16b-f4ade84a9ad2` |
| BIMBA PostgreSQL bimba_ingest | Database V2 Standard Get operation, object type `bimba_ingest_param` | `ed0d02be-7f84-4b3d-8d7e-aaedb3a9f006` |
| j.BIMBA.Ingest.REQ / .RESP | JSON profiles `{payload}` / `{result}` | `b6fa6538-…` / `aef9d461-…` |

Why Base64: the Database V2 connector binds `$payload` from a JSON parameter document,
so the raw batch JSON has to travel as a string value. Base64 avoids escaping quotes
natively (no script). Boomi's Base64 Encode wraps lines every 76 characters, which is
why the Search/Replace step strips whitespace. This was verified on the test Atom: the parameter
document is valid JSON and decodes back to the exact original batch. The SQL was checked
against this PostgreSQL: the first call inserts, and a redelivery counts duplicates only.

Why `bimba_ingest_param`: a Standard Get checks the input document's keys against the
columns of its object type. Without that you get `[1062] Keys Provided in Input Request is not
matching the Request Profile`. The object type is `bimba.bimba_ingest_param`, a zero-row view
whose only column is `payload`. Django's `install_sql` installs it from
`telemetry/sql/bimba_ingest_param.sql`.

Verified end to end on the QA Atom (`apibaseqa.easystepin.com`): a message produced to
topic `BIMBA` was stored in `bimba_chromalab` as `boomi-es` within 10 s.

Database errors **fail the document** so Event Streams redelivers it (Listen
`maxRetries` = 10, then dead-letter). Redelivery is safe: `bimba_ingest`
de-duplicates on `messageId`.

Optional extra subscriptions on the same topic, for example:
- `erp-status`: route `equipment_status`-like tags to SAP.
- `datalake`: forward batches to a data warehouse.

Each subscription gets every message independently.

## 4. Verify
* BIMBA live UI (http://localhost:3080): the chart shows **Boomi · Event Streams**
  bars, and the plugin panel shows `boomi-eventstreams` as **Up**.
* Event Streams UI: the topic shows produced messages, and the subscription backlog
  drains as the process runs.
* Stop the listener process: messages accumulate in the subscription (the plugin keeps
  delivering, because Event Streams still accepts them). Start it again: the backlog
  is stored.
* Use a wrong token or disable the topic: the plugin shows `boomi-eventstreams`
  **Down** with the HTTP error, and data flows to **BIMBA Ingest** (stored as `bimba-rest`).

## 5. Test without a Boomi tenant
`tools/eventstreams-mock` is a **test-only** stand-in for the produce REST API. It
checks the Bearer token, the `messages[]` format and the size limits, and it simulates
outages. It doesn't store data.

```bash
docker compose --profile es-mock up -d eventstreams-mock
```
Then set the destination's `url` to `http://eventstreams-mock:8095/rest/singlemsg/acct/env/BIMBA`
(single-message API) or `http://eventstreams-mock:8095/topics/bimba-telemetry` (multi-message API)
and its `token` to `test-env-token`. Inspect `http://localhost:8095/_received`. Simulate
an outage with `POST http://localhost:8095/_mode/fail` (and `/_mode/ok` to restore).

## Alternatives (still supported by the plugin)
* `boomi` (`transport: rest`): POST directly to an Atom **Web Services Server** listener
  (`http://boomi-atom:9090/ws/rest/...`, Basic auth). The process then runs the same
  Database shape with `'boomi-rest'`.
* `boomi-mqtt` (`transport: mqtt`): publish to `bimba/v1/PLANT01/boomi`. The process
  starts with an MQTT connector Listen, using `'boomi-mqtt'`.
