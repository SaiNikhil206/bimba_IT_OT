# BIMBA Setu

Our own SCADA edge server (`bimba-setu/`, **Java 21 + Spring Boot 4.1**, console in **React + Vite**, Docker service `setu`). It replaces the Ignition
gateway (Inductive Automation, licensed third-party software) and the BIMBA IT/OT Agent module that ran
inside it. No Ignition license or trial reset is needed.

```
Modbus TCP devices ─┐                 BIMBA Setu  :8090 http  ·  :8443 https
OPC UA servers ─────┼─► drivers ──► collector (poll every pollMs, send on change) ──► delivery engine
expressions ────────┘   (j2mod, Eclipse Milo)        canonical BIMBA JSON              batches · failover · disk buffer
                                                                                       │
                     Boomi Event Streams (primary) · BIMBA database, built in (failover) · Kafka (mirror)
                     /system/bimba-agent-enroll + -heartbeat  ◄── BIMBA Site Agents (docs/site-agents.md)
```

## What it does

| Part | Detail |
|---|---|
| **Field drivers** | Modbus TCP (holding/input registers as signed 16-bit, coils, discrete inputs; `HR2`, `IR5`, `C1`, `DI3`, optional `<unit>.` prefix; one- or zero-based), OPC UA (all nodes of a server in one Read per cycle; security policy None, Anonymous or Username), expressions (computed tags, Ignition-compatible subset: math, comparisons, `if`, `now`, `sin`, `getSecond`...). |
| **Collection** | Every device is read every `pollMs` (default 1000 ms) on its own thread. A message is sent only when a value or its quality changes. A device that cannot be reached turns all its tags BAD (with the reason in `qualityDetail`) and comes back by itself. |
| **Message check** | Every message (Setu's own and the site agents') is checked against the JSON Schema of its version before routing. Messages that fail go to the **Quarantine** (`<data>/quarantine`, console → Quarantine) with their raw text and a reason code; replay them after a fix, or discard them. Format and version rules: [payload.md](payload.md#versions-fr-can-05). Each destination receives format **2.0** (BRD canonical envelope, snake_case) or **1.x**, set per destination. |
| **Engineering values** | Linear scaling (`scaleMode`, `rawLow`…`scaledHigh`), then unit conversion from the device's unit (`sourceUnit`) to the tag's (`engUnit` / agent tag `unit`), then the engineering range (`engLow` / `engHigh`): outside it the value is delivered as UNCERTAIN. OPC UA `Uncertain_*` status codes are UNCERTAIN too, and the server's source timestamp is sent as `sourceTimestamp`. |
| **Tag registry** | Console → Tag registry (FR-CAN-04): per collected tag (machine / tag) its canonical tag name, machine, plant id, data type (Boolean, Int1…Int8, Float4, Float8, Double, String), unit, writable flag and description. Applied to every reading after the schema check, before delivery: names replaced (`source.tag` keeps the collected one), the value converted to the unit and type (a value that does not convert is delivered BAD with the reason). Tags without an entry are delivered as collected. CSV export / import (add and update, or replace). Stored per organization in `bimba_gw_setting` (`tag_registry.org.<id>`). |
| **Quarantine alerts and retention** | Console → Quarantine → *Alerts and retention*, per organization: e-mail recipients and / or a webhook (POST JSON; optional secret signs it as `X-BIMBA-Signature: sha256=<HMAC>`), alert after N readings, at most one alert per M minutes, and how many days quarantined readings are kept (default 30, removed hourly). *Send a test alert* checks the channels. |
| **Batching per pipeline** | Pipelines → *Batching*: readings per batch (1 = single-record envelopes) and longest wait; empty = `config.json` `batch` (FR-CAN-06). |
| **Output check** | Destinations → *Output check*: an optional JSON Schema every reading must match as the destination sends it (after its template and format, FR-RTE-03). A batch with a reading that does not match is not sent but kept as refused on the pipeline, to send again after the fix. |
| **Delivery** | Same engine as the former module: batching, Boomi Event Streams primary → failover (e.g. the built-in BIMBA database) with failback and standby probes, Kafka/webhook mirror routes, disk store-and-forward per route. |
| **Site agents** | Enrollment, heartbeats and the all-organization list for the BIMBA Site Agent installer. |
| **Console** | `http://<setu>:8090/` (or `https://…:8443`): sign-in, Overview (devices, delivery, certificate fingerprint), Site agents (all organizations), Users, Roles, Audit log, Profile. See *Users, roles and sign-in* below. `/system/bimba-status` is the JSON status that BIMBA Operations reads. |

## Configuration

All in the config folder (compose mounts `./ignition/bimba` at `/config`), hot-reloaded within ~5 s:

| File | Content | Edited in |
|---|---|---|
| `config.json` | plant/edge id, `pollMs`, batching, buffer, `agents` (destinations: database, see below) | by hand |
| `demo-tags.json` | tag tree: one folder per machine, one entry per tag (Modbus register, OPC UA node or expression, unit, scaling, optional `sourceUnit`, `engLow`, `engHigh`) | Settings → Modbus / OPC UA |
| `console-sources.json` | device connections: Modbus host/port/unit id/timeout/addressing, OPC UA endpoint/sign-in/timeout | Settings → Modbus / OPC UA |

The tag tree uses the Ignition tag-export layout, so an export from an existing Ignition installation can be
copied in as-is. Environment: `BIMBA_DB_*` (site agent registry + console sign-in), `BOOMI_ES_PRODUCE_TOKEN`
(for `${...}` references in `config.json`), `BIMBA_TLS_NAMES` (extra DNS names / IPs for the HTTPS certificate).

### Delivery: destinations and pipelines

Nothing about delivery is in a config file: it lives in the shared database and is managed in the Setu console
(sidebar **Delivery**; changes need `delivery.manage`, viewing needs `gateway.view`). The flow:

1. **Destinations** (`bimba_gw_destination`, Django migration 0006) are connections only: address, sign-in,
   **Test connection**. Adding one sends nothing; the list shows *No pipeline · no data* until a pipeline uses it.
2. **Pipelines** (`bimba_gw_pipeline`, migration 0007) decide which machines' data goes where:
   - **Machines**: all (also ones added to the tag tree later) or chosen machines;
   - **Primary**: one destination that gets every batch;
   - **Secondary**: optional, tried in this order while the primary is down (failback to the primary when it works);
   - **Mirrors**: optional, each gets its own copy with its own retry and disk buffer.
   - **Tags**: all, or patterns on `machine/tag` (or just `tag`) with `*` and `?`, plus exclude patterns
     (e.g. include `PUMP-*/Flow*`, `Temp*`, exclude `*/Debug*`); the form shows live which tags match;
   - **Rate**: *every change* (real time), *snapshot* every N s (current value of every tag, also unchanged ones;
     `sourceTimestamp` = when it was read) or a *window* every N s with one value per tag (FR-RTE-05): the *average*,
     *minimum* or *maximum* of the GOOD numbers (with `aggregate {fn, min, max, count, from, to}`; tags without a good
     number send their last value) or the *count* of readings the tag reported (any value and quality). Made messages
     get a new messageId and no `edge.seq`, so BIMBA Ingest's loss audit only counts real readings.
   - **Filters** (FR-RTE-05): *drop duplicates* (a reading the pipeline already carried: same messageId, or same
     machine + tag + timestamp, e.g. a site agent sending a batch again; the last 100 000 are remembered) and *only
     changed values* (sent only when value or quality differ from the last one sent for the tag; numbers within the
     optional *deadband* count as unchanged; with a window rate each window's value is compared). Both remember in
     memory; a configuration change keeps that memory (and open windows) for every pipeline whose rate and filters did
     not change, only a Setu restart starts fresh.
   - **Send unchanged values again** (every-change pipelines, optional): a tag whose last reading was sent N s ago is
     sent again (new messageId, `timestamp` = now, `sourceTimestamp` = when it was read, `aggregate.fn = repeat`), so a
     receiver can tell a steady value from a lost one.
   - **Gap markers** (FR-RES-03, on by default): see *Gap markers* below. They reach every pipeline that carries their
     machine, whatever its tag patterns, and are sent at once (not averaged); a pipeline can turn them off.
   - **Disk buffer** (FR-RES-05): the size of each of its routes' buffers, see *Disk buffer sizing* below.
   A pipeline is a **draft** (sends nothing) until it is **active**; an active pipeline needs a primary. A destination
   can serve several pipelines but appears once per pipeline. **Test destinations** in the pipeline form tests every
   chosen destination. A destination used by a pipeline cannot be removed (the pipelines are named).
3. **Live**: each active pipeline has its own routes and disk buffers (`data/buffer/pipelines/<id>/...`), so one
   pipeline or mirror being down never holds up another. Machines no active pipeline takes are not delivered
   (counted as `notRoutedMessages`). Overview and the Pipelines page show what each pipeline delivers to right now.

Service catalog for destinations:

| Category | Services |
|---|---|
| Boomi | Boomi Event Streams, Boomi Atom web service (Web Services Server listener) |
| Built into Setu | BIMBA database (see below) |
| Kafka | Apache Kafka, Confluent Cloud, Azure Event Hubs (Kafka endpoint), Amazon MSK (SASL/SCRAM), Redpanda |
| MQTT | MQTT broker (Mosquitto, EMQX, VerneMQ, HiveMQ…), Unified Namespace (one topic per tag), HiveMQ Cloud, RabbitMQ (MQTT plugin) |
| Queues (JMS, AMQP) | ActiveMQ Artemis / ActiveMQ (JMS over AMQP 1.0; also Red Hat AMQ, IBM MQ AMQP channel, Solace), Azure Service Bus, RabbitMQ (AMQP 0-9-1; also Amazon MQ, CloudAMQP, LavinMQ) |
| Files | SFTP server, FTP / FTPS server (CSV, JSON, NDJSON, XML or HL7 files) |
| Databases | PostgreSQL, Microsoft SQL Server, MySQL / MariaDB, Oracle (a table of the customer's own database) |
| Healthcare (HL7) | HL7 v2 over MLLP |
| Analytics & monitoring | Splunk HTTP Event Collector, Elasticsearch / OpenSearch (`_bulk`, document id = messageId), InfluxDB 2.x / Cloud (line protocol) |
| Generic | HTTP webhook (POST or PUT; one JSON document per batch, a JSON array of readings, one JSON line per reading, CSV or XML) |

- **REST** (FR-END-01): POST or PUT. A receiver that answers **429** or **503** is *busy*: with a `Retry-After`
  (seconds or an HTTP date, at most an hour) the destination waits exactly that long instead of its own backoff and
  shows *throttled*; without one the normal backoff applies. Meanwhile the route uses its next destination or keeps
  the batches on disk. Event Streams answers are handled the same way.
- **Output formats** (FR-RTE-03): besides JSON, the readings can go as **CSV** (one line per reading, RFC 4180 quoting;
  columns = the template's fields in their order, else the standard ones `plantId, machineId, tagName, timestamp,
  tagValue, dataType, unit, quality, qualityDetail, category, messageId, sourceTimestamp, ingestTime` and then any other
  field, nested ones as `source.protocol`; separator `,` `;` tab or `|`, optional header line), **XML**
  (`<batch batchId=".." plantId=".." count="..">` with one `<reading>` per reading; root and reading element names
  can be changed; template keys starting with `@` become attributes) and **HL7 v2** (see MLLP). The JSON template
  shapes CSV and XML too. The destination form shows *what is sent* for an example batch in every format.
- **Files: SFTP, FTP / FTPS** (FR-END-02): address `sftp://host:22/folder` or `ftp://host:21/folder` (`/folder` is
  absolute, `/~/folder` inside the sign-in folder; missing folders are created). SFTP signs in with a password or a
  private key (OpenSSH / PEM, optional passphrase; stored encrypted) and checks the server's **host key** against the
  pinned SHA256 fingerprint (Test connection shows it). FTP: plain, explicit TLS (AUTH TLS) or implicit FTPS, passive
  mode by default, the destination's certificates. Each file is written as `.<name>.part` and renamed when complete
  (a reader never sees half a file; a file of that name, from a retry, is replaced). **Name** pattern: `{plantId}
  {edgeId} {batchId} {count} {ext} {time:yyyyMMdd-HHmmss}` (UTC; default
  `bimba_{plantId}_{time:yyyyMMdd'T'HHmmss'Z'}_{batchId}.{ext}`; it must hold `{batchId}` or a time with seconds). The
  name uses the batch's (or window's) time, not the time of the attempt, so a retry gives the same name. **Batching
  window**: 0 = a file per batch, N s = the readings of each N-second window (aligned to the clock: 900 = :00, :15, …)
  are collected on Setu's disk (`data/files/pipelines/<id>/<route>/<destination>/window`, a batch counts as
  delivered once it is there) and sent as one file when the window ends; a window that cannot be sent marks the
  destination down and is tried again, also after a restart. **Archive**: N days = a copy of every file sent stays in
  `.../archive/<yyyy-MM-dd>/`, removed after N days.
- **HL7 v2 over MLLP** (FR-END-03): `mllp://host:port` (`mllps://` = TLS). Every batch becomes HL7 messages, by default
  ORU^R01 2.5 with the machine as OBR-4 / OBX-18 and one OBX per reading (value type NM / ST, OBX-11 F = GOOD,
  R = UNCERTAIN, X = BAD), one message per machine per batch or per reading. The **HL7 template** (one segment per
  line; OBX lines, with NTE lines after them, repeat per reading) takes every template field plus `{hl7.controlId}
  {hl7.now} {hl7.time} {hl7.setId} {hl7.valueType} {hl7.value} {hl7.status}`; values are escaped (`| ^ ~ \ &`).
  Each message is framed (VT … FS CR) and must be acknowledged before the next: **AA / CA** accepted; **AE / CE** the
  receiver refused the content → the batch is kept as refused (Review… / send again); **AR / CR**, no answer in time
  or a lost connection → tried again with the backoff. MSH-10 (control id) is the same when a batch is sent again, so
  the receiver can drop messages it already took. Test connection only connects (HL7 has no message that stores
  nothing); *Send test message* checks the acknowledgements.
- **JMS (AMQP 1.0)** and **AMQP 0-9-1** (FR-END-04): the **partitioning key is the machine id**. JMS: queue or topic,
  `amqp://` / `amqps://` / `failover:(…)`; one message per machine per batch (default; `JMSXGroupID` = machine id,
  so a broker keeps each machine's readings in order on one consumer), per reading, or per batch; persistent by
  default; every send waits for the broker. Properties `batchId plantId edgeId schemaVersion count machineId` (+
  `tagName messageId`). AMQP 0-9-1 (RabbitMQ): exchange + routing key with placeholders, e.g.
  `bimba.{plantId}.{machineId}` on `amq.topic` (dots in values become `_`); publisher confirms; a message no queue
  is bound for (mandatory) fails the send, so nothing disappears silently. **Kafka** per-reading records can use the
  machine id as the record key as well (*Record key*), so a machine's readings stay in one partition.
- **Customer database (JDBC)** (FR-END-05): PostgreSQL, SQL Server, MySQL / MariaDB (MariaDB driver, LGPL), Oracle.
  JDBC URL, user, password, table, **columns** (column → field like a template; default `plant_id machine_id tag ts
  value_num value_text unit quality message_id`; `{timestamp}` is written as a UTC timestamp, `{tagValueNumber}` =
  numbers with true/false as 1/0, `{tagValueText}` = every value as text), **key columns** (default `machine_id, tag,
  ts`) and **write mode**: *upsert* (default; `ON CONFLICT … DO UPDATE`, `ON DUPLICATE KEY UPDATE`, `MERGE`), *ignore*
  (readings already stored are skipped, so a batch sent again stores nothing twice) or *insert*. Each batch is one
  transaction of JDBC batches. A data error (SQLSTATE 22 / 23) refuses the batch; a connection, sign-in or missing
  table error is retried. Test connection checks the table, its columns and a primary key / unique index on the key
  columns (writes nothing). The table is the customer's; Setu does not create it.

- **BIMBA database (built in)**: Setu stores the readings itself, over its own connection to the shared
  PostgreSQL (`BIMBA_DB_*`), by calling the same `bimba_ingest()` SQL function as Boomi (drops repeated messageIds,
  latest values, `NOTIFY` for the live pages), so no separate receiver is needed. Optional: add it once (the catalog
  allows one) and use it as primary, secondary or mirror. Stored as delivered `bimba-gateway` ("BIMBA Setu · direct"
  in BIMBA Operations). Nothing to fill in; Advanced has timeout (a statement timeout per batch, default 10 s), retries
  and readings per batch. Always the BIMBA standard message. A batch with invalid data (SQLSTATE 22xxx) is refused like
  an HTTP 400; database down = failover / disk buffer as for any destination. The separate **BIMBA Ingest** receiver
  (Django `/api/ingest/`) is no longer in the catalog; destinations made from it still work and their form says how to
  replace them.
- **Message layout** (per destination): the BIMBA standard message — the form's *"What does the BIMBA standard message
  look like?"* shows an example (as this destination sends it: whole batch, array or per reading) and what every field
  means — or a **custom JSON template** per reading, e.g.
  `{"asset": "{machineId}", "value": "{tagValue}", "ts": "{timestampMs}"}`. A string that is exactly one placeholder
  keeps the type (number stays number); placeholders in longer text (and in keys) become text. Fields: `plantId edgeId
  machineId tagName path tagValue dataType unit quality qualityDetail timestamp timestampMs epochSeconds sourceTimestamp
  messageId batchId category schemaVersion source.* edge.* aggregate.*`. Unknown fields are refused on save; the form
  has a **Preview**. It applies wherever the destination sends a reading (inside the batch envelope, Kafka/Event
  Streams per-reading records, NDJSON / JSON array bodies, Splunk events, Elasticsearch documents); not to InfluxDB
  line protocol, nor to the BIMBA database (it stores the standard message).
- **MQTT one message per reading**: topic with placeholders, e.g. `bimba/{plantId}/{machineId}/{tagName}` (values have
  `/ + #` replaced by `_`) — the catalog entry **Unified Namespace (MQTT)** sets this up as a plain-JSON UNS.
- **Robust delivery** (per destination, Advanced):
  - *health check while on standby* for every type (on by default, every 30 s, no data sent): a broken secondary or
    a down mirror is seen before it is needed;
  - *retry wait doubles* after each failure in a row, from "try again after" up to "longest wait" (default 300 s);
  - *readings per request*: larger batches are split (receivers with a size limit); REST can *gzip* requests;
  - a batch a destination **refuses as invalid** (HTTP 400/413/422) goes to the next destination of the pipeline; when
    every destination refused it, it is kept aside (never dropped) with who refused it and why. Pipelines → *Review…*
    shows these batches, their content, and can **send them again** (after fixing the cause) or **discard** them;
  - counters (messages, failures, last success) are saved every 30 s and on stop (`data/delivery-stats.json`), so they
    survive restarts and configuration changes; deleting a pipeline removes its empty disk buffers.
- **Certificates (TLS)** per destination, PEM pasted or loaded: extra trusted authorities (self-signed / company PKI,
  added to the built-in ones) and a client certificate + private key for certificate sign-in (AWS IoT Core, Azure IoT
  Hub, company brokers). PKCS#8 or RSA PKCS#1 keys; the key is checked against the certificate when saved and stored
  encrypted; subjects and expiry dates are shown, the list warns 30 days before a certificate expires. Used for
  HTTPS, MQTT `ssl://` / `wss://` and Kafka SSL (as PEM key/trust stores).
- **HTTP proxy** (REST, Event Streams): `http://host:port` with optional user name / password (sent as
  Proxy-Authorization, also for HTTPS tunnels).
- **OAuth2 client credentials** (REST sign-in): token URL, client ID, client secret, scope, audience; the token is
  reused until 60 s before it expires and renewed once on a 401.
- **MQTT**: one message per reading is published in parallel (up to "messages in flight", default 100) instead of one
  at a time; **retained** keeps the last value of every topic for new subscribers (on by default for the Unified
  Namespace entry).
- **Send test message**: after asking, sends ONE reading (machine `BIMBA-TEST`, tag `connection_test`, category
  `test`) through the destination as the form shows it — the end-to-end check; the receiver stores it.
- **Test connection** checks address and sign-in without delivering anything: MQTT connects (own client id), Kafka
  describes the topic with an admin client (never auto-creates it), the BIMBA database checks its connection and that
  `bimba_ingest()` is installed, a REST receiver with the BIMBA health check answers it, Event Streams /
  Splunk / Elasticsearch / InfluxDB / webhooks get a request that does not store data (401/403 = credentials rejected).
- **Secrets** (tokens, passwords, API keys, secret headers and Kafka properties) are encrypted in the database with
  AES-256-GCM. The key is `BIMBA_SECRET_KEY` (any text) or, if unset, `data/secret.key`, created on first start in
  Setu's data volume. Keep that volume, or set `BIMBA_SECRET_KEY`, when you move Setu. Secrets are
  never sent to the browser; leaving a secret field empty keeps it. `${ENV_VAR}` references still work.
- Every change applies at once (queued data moves to the disk buffers during the switch) and is in the audit log
  (`destination.*`, `pipeline.create/update/activate/pause/delete`). Changes from another console arrive within 15 s.
- **Database down:** Setu delivers with its saved copy (`data/destinations.json`); the pages say so and allow
  changes again when the database is back.
- **Upgrades:** destinations still in `config.json` are imported once and removed from the file (copy in
  `data/config.json.before-import`). The former per-destination roles become one active pipeline, **Plant data**
  (all machines; first enabled primary → other primaries and failovers as secondaries; enabled mirrors), which keeps
  the disk buffers of that version; the roles are then removed from the destinations.
- BIMBA Operations → Settings lists the active pipelines' destinations and links to Setu; `/console` is read-only.
- **Disk buffer sizing** (FR-RES-05): every route (the main route and each mirror) has its own buffer, by default
  `buffer.maxMegabytes` of `config.json` (512 MB); a pipeline can set its own size. The documented formula, which the
  pipeline form fills in from what the pipeline measured (readings per minute, bytes per reading) or its settings:

  `size per route = tags × readings per tag per minute × 60 × bytes per reading × outage hours × 1.25`

  (1.25 = batch overhead and bursts; a BIMBA 1.x reading is about 600 bytes in a buffered batch). Example: 200 tags,
  one reading per tag per second (60/min), 600 bytes, 24 h → 200 × 60 × 60 × 600 × 24 × 1.25 bytes ≈ 12 360 MB
  per route; with a 60 s window (one reading per tag per minute) it is 1/60 of that, ≈ 206 MB. The form warns when all buffers together exceed the free disk
  space. **At 70 % full** a buffer raises an alert: Setu log, the Pipelines page, and the organization's
  alert recipients (the e-mail addresses and webhook of the Quarantine page's alert settings; webhook event
  `bimba.buffer.high`, then `bimba.buffer.normal` below 60 %). At 100 % the oldest batches are discarded (counted
  as `bufferDropped`).
- **In-order recovery** (FR-RES-01): while batches wait on a route's disk buffer, new batches go behind them, and the
  backlog is sent oldest first as fast as the destination takes it, so a receiver gets the data in the order it was made.
- **Reconnect backoff** (FR-RES-03): a device that cannot be reached is tried again after its poll interval, then
  after twice that, up to `retryMaxMs` of its connection settings (default 60 s); the Overview shows the next try.
- **Gap markers** (FR-RES-03): a device Setu cannot reach makes all its tags BAD (as before) and sends one
  marker per machine: tag `_connection`, category `gap`, value `lost`, quality BAD, `gap {state: start, from: the last
  good read, device, reason}`; when it answers again `restored`, GOOD, `gap {state: end, from, to, seconds}`. Site
  agents' edge collectors (same code) do the same for their devices; for a site agent that stops altogether (no
  heartbeat for 3 intervals, as the console calls it offline) Setu sends the markers for its machines
  (`source.agentId`; state kept in `data/agent-gaps.json`). When the **Setu itself** was not running for more than
  15 s (stopped, or crashed: it notes every 10 s that it is alive in `data/gateway-alive.json`), its own machines get a
  "lost" marker at the time it stopped and a "restored" one at the next start (device `gateway <edgeId>`). Receivers
  that store readings can filter on `category = gap`; the built-in **BIMBA database destination does not store
  markers** (they would show as a tag `_connection`; the BAD readings already show the gap there). Template fields: `gap.state gap.from gap.to gap.seconds gap.device gap.reason`.
- API: `GET/POST /api/gw/destinations`, `PUT/DELETE /api/gw/destinations/{id}`, `POST /api/gw/destinations/test`
  (`{original: id, destination}`), `POST /api/gw/destinations/send-test` (same body), `POST /api/gw/destinations/preview`
  (`{template}`); `GET /api/gw/pipelines/{id}/rejected`, `GET /api/gw/pipelines/{id}/rejected/{route}/{file}`,
  `POST /api/gw/pipelines/{id}/rejected` (`{action: resend | discard, items: [{route, file}]}`); `GET/POST /api/gw/pipelines`, `PUT/DELETE /api/gw/pipelines/{id}`,
  `POST /api/gw/pipelines/{id}/active` (`{active}`).

## Users, roles and sign-in

The console is the React app in `bimba-setu/ui` (built into the jar) with its JSON API `/api/gw/*` (`ConsoleController`).
Sign-in and every permission check run in Setu itself, so it keeps working when Django or BIMBA
Operations are down; only the shared PostgreSQL is needed.

| Topic | How it works |
|---|---|
| **Accounts** | Django-format accounts (`auth_user` in Setu schema `bimba_ignition`), passwords in Django's PBKDF2 format. **Sign-in is by e-mail address only** (case-insensitive, unique: index `auth_user_email_ci_uq`); the internal username of a Setu account is the same e-mail. Administrators add users by e-mail and can change a user's sign-in e-mail (this ends their sessions); users cannot change their own. The Site Agent installer (1.1+) also signs in with the organization e-mail. |
| **Access** | A user needs at least one Setu **role in an organization** (see Organizations below). Django superusers are *platform admins* and have every permission in every organization. Organization members without a role cannot sign in to Setu. |
| **Permissions** | `gateway.view` (overview) · `agents.view` / `agents.manage` (the organization's site agents, revoke) · `delivery.manage` (add, test, edit, remove destinations; create, activate, pause, delete pipelines) · `users.manage` (create, edit, disable, reset password, unlock, sign out, assign roles) · `roles.manage` · `audit.view` · `writes.send` / `writes.approve` / `writes.manage` (machine commands: send, approve a second person's, resume after a stop; see [site-agents.md](site-agents.md#machine-commands-writing-values)). The built-in Administrator role always has every permission, including ones added later. |
| **Built-in roles** | Administrator (everything; cannot be edited), Operator (overview + site agents incl. revoke), Viewer (read-only), Auditor (overview + audit log), Machine operator (sends machine commands), Supervisor (sends, approves, stops and resumes machine commands; both added by Setu at start). They are shared by every organization and only platform admins can change them. Custom roles belong to the organization that created them. |
| **No escalation** | Nobody can grant a role or permission they do not have; only platform admins can change platform admin accounts; you cannot disable yourself or remove your own user management. |
| **Password policy** | ≥ 12 characters, 3 of 4 character classes, not containing the user name, different from the current one. New and reset accounts get a temporary password and must choose their own at the first sign-in. |
| **Forgot password** | "Forgot password?" on the sign-in page e-mails a one-time link (`/reset-password?token=…`, valid 30 min; a new link replaces older ones; only its SHA-256 is stored in `bimba_gw_password_reset`). The answer is identical for unknown addresses and is given before the mail is sent. Setting the new password follows the policy, unlocks the account and ends all its sessions. Limits: 5 requests per IP and 3 links per account in 15 min, 20 bad links per IP. Mail: `BIMBA_SMTP_HOST/PORT/USER/PASSWORD/STARTTLS/SSL`, `BIMBA_MAIL_FROM`, links use `BIMBA_PUBLIC_URL` (never the request's Host). **Without `BIMBA_SMTP_HOST` the link is written to Setu log** — set SMTP before real use. |
| **Profile photo** | Profile → Photo: the browser crops the picture to a square, scales it to 256 × 256 and uploads a JPEG; the server accepts only PNG / JPEG / WebP by content (SVG refused), at most 512 KB (`bimba_gw_user_profile.avatar`). Visible to the user, members of the session's organization and platform admins (`/api/gw/avatars/{id}`). |
| **Lockout** | 5 wrong passwords in a row lock the account for 15 minutes (an admin can unlock it); 20 failures from one IP in 15 minutes block that IP. Unknown and known user names answer the same way. |
| **Sessions** | Random token in an `HttpOnly; SameSite=Strict` cookie (`Secure` over HTTPS), stored as SHA-256 in `bimba_gw_session`. Sign-out after 30 min without activity (automatic page refreshes do not count) and 12 h after sign-in. Changing the password, disabling the account or an admin "Sign out" ends the sessions. Users see and end their own sessions on the Profile page. |
| **Web hardening** | CSRF token header on every change, Content-Security-Policy without inline script, `X-Frame-Options: DENY`, `nosniff`, `no-referrer`, HSTS over HTTPS. |
| **Audit log** | Sign-ins (also refused ones, with the reason), lockouts, timeouts, sign-outs, password changes and every user, role and site-agent change, with user, IP and before/after details (`bimba_gw_audit`). |

Settings (optional) in `config.json`:

```json
"security": { "idleMinutes": 30, "maxSessionHours": 12, "maxFailedLogins": 5, "lockMinutes": 15,
              "minPasswordLength": 12, "minCharacterClasses": 3 }
```

The first administrator is any Django superuser (POC: `admin` / `admin` from docker-compose; change it).
Tables (Django migration `telemetry/0005_gateway_security`): `bimba_gw_role`, `bimba_gw_user_role`,
`bimba_gw_user_profile`, `bimba_gw_session`, `bimba_gw_audit`.

## Organizations (multi-tenant)

Everything in the console belongs to one **organization** (`bimba_org`): its members and their roles, custom roles,
destinations, pipelines, site agents and audit log. One account can be a member of several organizations, with a
different role in each, and switches between them with the organization menu at the top of the sidebar.

| Topic | How it works |
|---|---|
| **Working organization** | The session (`bimba_gw_session.org_id`) works in one organization at a time; permissions are the user's roles there. Sign-in opens the organization used last. Switching (`POST /api/gw/session/organization`) is audited in the target organization. |
| **Shared links** | An organization's pages start with its slug: `/northwind-water/pipelines`. Opening a link signs in first if needed and stays on that page, moves the session to that organization when the user is a member (otherwise it stays where it is, with a notice), and shows "You do not have access" when the user's roles there do not include the page. `/profile` and `/organizations` belong to no organization. Older addresses (`/pipelines`, `/pipelines/<uid>?org=<slug>`) move under the organization. A new organization's slug may not be one of the console's first path segments (`OrgService.RESERVED`, also checked by `bimba_org create`). |
| **Record addresses** | Organizations, roles, destinations, pipelines and users have a public id (`uid`, a random UUID; Django migration `0013_public_uids`, on `bimba_gw_user_profile` for users). `/northwind-water/pipelines/<uid>` opens that pipeline (`.../pipelines/new` a new one; `/organizations/<uid>` for platform admins); someone who may only look at it sees it highlighted in the list; a deleted record or another organization's goes back to the list with a notice. Organization slug for the tenant, UUID for records: renaming a record never breaks its links. The integer ids stay the primary keys and API ids. |
| **Isolation** | Every API call is scoped to the session's organization: other organizations' destinations, pipelines, rejected batches, site agents, users, custom roles and audit entries answer 404 or are not listed. A pipeline can only use its own organization's destinations and only select its own machines. |
| **Machines** | Each machine of the tag tree belongs to one organization (`bimba_gw_machine`); machines nobody assigned belong to the **default organization** (`bimba_gw_setting` `machines.default_org`), also ones added later. The delivery engine only gives a pipeline the readings of its organization's machines, so one organization never receives another's data. Overview shows the organization's machines, the devices serving them and its pipelines (platform admins see the whole gateway). |
| **Members** | Users (users.manage) lists the organization's members. *Add user* creates a new account or adds an **existing account** (for example someone from another organization) with roles here; *Remove* ends the roles here only. Removed while signed in, the session moves to the user's next organization (or ends when there is none). |
| **Shared accounts** | The account itself (name, e-mail, password, enabled, sign out everywhere) of someone who also belongs to another organization can only be changed by a platform admin; an organization admin can still change their roles here or remove them. |
| **Stale tabs** | The web app sends the organization it shows (`X-BIMBA-Org`). After another tab switched the session, a call from the old tab is refused (409 `org_changed`) and the tab reloads in the new organization. |
| **Platform admins** | *Organizations* page: create, rename, delete (empty ones only) organizations, choose the default organization, assign machines. *Audit log → All organizations* shows every organization's events. |
| **Site agents / BIMBA Operations** | Membership is `bimba_org_member`, which the site agent installer and Operations → Site agents already use; Setu keeps its `admin` / `member` flag in step (admin = the roles allow `agents.manage`). |

Upgrade: Django migrations `0008_multi_org` + `0009_multi_org_required` give existing destinations, pipelines and user
roles the organization `default` ("Default organization"), which also becomes the default for machines, so delivery
continues unchanged.

Demo (`manage.py bimba_org demo-multi-org`): organizations **Northwind Water** and **Contoso Energy** and the user
**sam**, Administrator in Northwind and Operator in Contoso (password printed once; also kept in the gitignored
`site-agent/demo-users.local.txt`). `bimba_org add-user <org> <user> --gateway-role Viewer` gives anyone a role in an organization.

## HTTPS

**Production: the customer's certificate** (from their own certificate authority or a public one). Mount the files
and point Setu at them, then restart:

| Variable | Value |
|---|---|
| `BIMBA_TLS_CERT` + `BIMBA_TLS_KEY` | PEM files: the certificate followed by its intermediates; the private key (PKCS#8 or RSA PKCS#1, not password-protected) |
| `BIMBA_TLS_PFX` + `BIMBA_TLS_PFX_PASSWORD` | or one PKCS#12 / PFX file |

```yaml
# docker-compose.yml → setu
    environment:
      BIMBA_TLS_CERT: /tls/gateway.crt
      BIMBA_TLS_KEY: /tls/gateway.key
    volumes:
      - ./gateway-tls:/tls:ro
```

The key must belong to the certificate and the certificate must not be expired; otherwise Setu logs the reason,
shows it in the console's download dialog, and keeps running on the self-signed certificate. Site agents then trust Setu like any web site (the machine's Windows must trust the issuer; domain machines usually get a company root CA
through Group Policy), so no fingerprint pinning is needed.

**Otherwise: self-signed.** On first start Setu creates a self-signed certificate (5 years, RSA 2048) in the
`setu-data` volume and prints its SHA-256 fingerprint in the log and in the console. Site agent installers show the
same fingerprint and pin it after the user confirms (`--trust-cert`, MSI `TRUSTCERT`).

**Agent client certificates (mutual TLS).** Separately, Setu runs a private certificate authority for site
agents (`/data/tls/agent-ca.p12`, RSA 3072, 20 years, created on first start; back up the `setu-data` volume, a lost
CA means every agent must be re-installed). The HTTPS port asks for a client certificate from this CA only and accepts
connections without one (browsers, agents still on their secret). See [site-agents.md](site-agents.md). A reverse proxy
that ends TLS in front of port 8443 would strip agent certificates; pass 8443 through as TCP instead, or set
`agents.issueCertificates` to false.

## Tech stack

| Layer | What | Where |
|---|---|---|
| Runtime | Java 21 (Eclipse Temurin), Spring Boot 4.1, embedded Tomcat 11 (HTTP 8090 + HTTPS 8443) | `build.gradle`, `BimbaSetuApplication`, `web/HttpsConnector` |
| REST API | Spring Web MVC `@RestController`s, one JSON error shape (`ApiErrors`) | `web/ConsoleController` (`/api/gw/*`), `web/SystemController` (`/system/*`) |
| Security | Spring Security 7: filter chain, `@PreAuthorize` per call, SPA CSRF (XSRF-TOKEN cookie → `X-XSRF-TOKEN` header), CSP / frame / referrer headers; our database sessions and rules | `web/SecurityConfig`, `web/SessionAuthFilter`, `security/SecurityService` |
| Database | Spring JDBC `JdbcClient` + HikariCP on the shared PostgreSQL, own schema `bimba_ignition` (`BIMBA_SETU_DB_SCHEMA` in .env, passed as `BIMBA_DB_SCHEMA`; Django migrations still define the tables, `migrate_gateway_schema`) | `Db`, `application.yml` |
| Field + delivery | j2mod (Modbus), Eclipse Milo (OPC UA), own expression evaluator; Kafka, Paho MQTT, HTTP | `collect/*`, `delivery/*` |
| Health | Spring Boot Actuator `/actuator/health` | `application.yml` |
| Console UI | React 19, React Router 7, Vite 8 (no UI framework; same calm style as BIMBA Operations) | `ui/` |
| Tests | JUnit 5: Django password compatibility, password policy, expressions, tag tree | `src/test/java` |

Setu starts and keeps collecting even when the shared database is down (Hikari `initialization-fail-timeout: -1`);
only sign-in, site agent enrollment and heartbeats need it.

## Develop

```bash
cd bimba-setu/ui
```
```bash
npm install
```
```bash
npm run dev
```

The dev server (http://localhost:5174) proxies `/api` and `/system` to Setu on :8090, so hot reload works against
the running Docker Setu service. Backend tests (no local JDK needed):

```bash
docker run --rm -v "${PWD}:/src" -v bimba-gradle9-cache:/home/gradle/.gradle -w /src gradle:9.2-jdk21 gradle test --no-daemon
```

## Run

```bash
docker compose up -d --build setu
```
```bash
docker compose logs -f setu
```

The legacy Ignition gateway and its BIMBA IT/OT Agent module have been removed from the repository (see git history).
Do not run both at the same time: both would read the devices and send every value twice.

## Not done yet
* OPC UA security policies other than None (needs the certificate trust exchange with the server).
* Modbus 32-bit / float registers (`HRF`, `HRI`...) and batch reads of neighbouring registers (one request per tag today).
* Devices and tags are edited in BIMBA Operations → Settings, not in the Setu console.
* No two-factor sign-in yet, and `/system/bimba-status` is still readable without signing in (BIMBA Operations reads it).
* One Setu server per plant; no clustering / redundancy.
* Output formats SAP IDoc / OData (FR-RTE-03) and reference mappings for SAP / Oracle / HRM (FR-END-06) are not built.
* JMS needs a broker with AMQP 1.0 (no vendor-specific JMS client jars are bundled).
* Live-tested (2026-10-09, throwaway containers, `LiveDestinationsIT`, skipped in the normal build): RabbitMQ 4,
  SQL Server 2022, MariaDB 11 (through a `jdbc:mysql:` address), Oracle Free 23 and an OpenSSH SFTP server (key
  sign-in, host-key pinning). Not live-tested: PostgreSQL as a customer database (Setu's own shared database
  was left alone), JMS against brokers other than ActiveMQ Artemis, FTP / FTPS against a real server, HL7 engines.
  To repeat a test, start the server and set its variable, e.g. `BIMBA_IT_RABBIT`, `BIMBA_IT_MSSQL`, `BIMBA_IT_MARIADB`,
  `BIMBA_IT_ORACLE`, `BIMBA_IT_SFTP` (+ `BIMBA_IT_SFTP_KEY`), with `BIMBA_IT_USER` / `BIMBA_IT_PASS`.

