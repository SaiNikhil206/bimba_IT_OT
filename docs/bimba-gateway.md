# BIMBA Gateway

Our own SCADA edge server (`bimba-gateway/`, **Java 21 + Spring Boot 4.1**, console in **React + Vite**, Docker service
`gateway`). It replaces the Ignition
gateway (Inductive Automation, licensed third-party software) and the BIMBA IT/OT Agent module that ran
inside it. No Ignition license or trial reset is needed.

```
Modbus TCP devices ─┐                 BIMBA Gateway  :8090 http  ·  :8443 https
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
| **Delivery** | Same engine as the former module: batching, Boomi Event Streams primary → failover (e.g. the built-in BIMBA database) with failback and standby probes, Kafka/webhook mirror routes, disk store-and-forward per route. |
| **Site agents** | Enrollment, heartbeats and the all-organization list for the BIMBA Site Agent installer. |
| **Console** | `http://<gateway>:8090/` (or `https://…:8443`): sign-in, Overview (devices, delivery, certificate fingerprint), Site agents (all organizations), Users, Roles, Audit log, Profile. See *Users, roles and sign-in* below. `/system/bimba-status` is the JSON status that BIMBA Operations reads. |

## Configuration

All in the config folder (compose mounts `./ignition/bimba` at `/config`), hot-reloaded within ~5 s:

| File | Content | Edited in |
|---|---|---|
| `config.json` | plant/edge id, `pollMs`, batching, buffer, `agents` (destinations: database, see below) | by hand |
| `demo-tags.json` | tag tree: one folder per machine, one entry per tag (Modbus register, OPC UA node or expression, unit, scaling) | Settings → Modbus / OPC UA |
| `console-sources.json` | device connections: Modbus host/port/unit id/timeout/addressing, OPC UA endpoint/sign-in/timeout | Settings → Modbus / OPC UA |

The tag tree uses the Ignition tag-export layout, so an export from an existing Ignition installation can be
copied in as-is. Environment: `BIMBA_DB_*` (site agent registry + console sign-in), `BOOMI_ES_PRODUCE_TOKEN`
(for `${...}` references in `config.json`), `BIMBA_TLS_NAMES` (extra DNS names / IPs for the HTTPS certificate).

### Delivery: destinations and pipelines

Nothing about delivery is in a config file: it lives in the shared database and is managed in the gateway console
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
     `sourceTimestamp` = when it was read) or *average* every N s (numbers: avg of GOOD readings with
     `aggregate {fn, min, max, count, from, to}`; other values: the last one). Made messages get a new messageId and no
     `edge.seq`, so BIMBA Ingest's loss audit only counts real readings.
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
| Built into the gateway | BIMBA database (see below) |
| Kafka | Apache Kafka, Confluent Cloud, Azure Event Hubs (Kafka endpoint), Amazon MSK (SASL/SCRAM), Redpanda |
| MQTT | MQTT broker (Mosquitto, EMQX, VerneMQ, HiveMQ…), Unified Namespace (one topic per tag), HiveMQ Cloud, RabbitMQ (MQTT plugin) |
| Analytics & monitoring | Splunk HTTP Event Collector, Elasticsearch / OpenSearch (`_bulk`, document id = messageId), InfluxDB 2.x / Cloud (line protocol) |
| Generic | HTTP webhook (one JSON document per batch, a JSON array of readings, or one JSON line per reading) |

- **BIMBA database (built in)**: the gateway stores the readings itself, over its own connection to the shared
  PostgreSQL (`BIMBA_DB_*`), by calling the same `bimba_ingest()` SQL function as Boomi (drops repeated messageIds,
  latest values, `NOTIFY` for the live pages), so no separate receiver is needed. Optional: add it once (the catalog
  allows one) and use it as primary, secondary or mirror. Stored as delivered `bimba-gateway` ("BIMBA Gateway · direct"
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
  the gateway's data volume. Keep that volume, or set `BIMBA_SECRET_KEY`, when you move the gateway. Secrets are
  never sent to the browser; leaving a secret field empty keeps it. `${ENV_VAR}` references still work.
- Every change applies at once (queued data moves to the disk buffers during the switch) and is in the audit log
  (`destination.*`, `pipeline.create/update/activate/pause/delete`). Changes from another console arrive within 15 s.
- **Database down:** the gateway delivers with its saved copy (`data/destinations.json`); the pages say so and allow
  changes again when the database is back.
- **Upgrades:** destinations still in `config.json` are imported once and removed from the file (copy in
  `data/config.json.before-import`). The former per-destination roles become one active pipeline, **Plant data**
  (all machines; first enabled primary → other primaries and failovers as secondaries; enabled mirrors), which keeps
  the disk buffers of that version; the roles are then removed from the destinations.
- BIMBA Operations → Settings lists the active pipelines' destinations and links to the gateway; `/console` is read-only.
- API: `GET/POST /api/gw/destinations`, `PUT/DELETE /api/gw/destinations/{id}`, `POST /api/gw/destinations/test`
  (`{original: id, destination}`), `POST /api/gw/destinations/send-test` (same body), `POST /api/gw/destinations/preview`
  (`{template}`); `GET /api/gw/pipelines/{id}/rejected`, `GET /api/gw/pipelines/{id}/rejected/{route}/{file}`,
  `POST /api/gw/pipelines/{id}/rejected` (`{action: resend | discard, items: [{route, file}]}`); `GET/POST /api/gw/pipelines`, `PUT/DELETE /api/gw/pipelines/{id}`,
  `POST /api/gw/pipelines/{id}/active` (`{active}`).

## Users, roles and sign-in

The console is the React app in `bimba-gateway/ui` (built into the jar) with its JSON API `/api/gw/*` (`ConsoleController`).
Sign-in and every permission check run in the gateway itself, so it keeps working when Django or BIMBA
Operations are down; only the shared PostgreSQL is needed.

| Topic | How it works |
|---|---|
| **Accounts** | Django-format accounts (`auth_user` in the gateway schema `bimba_ignition`), passwords in Django's PBKDF2 format. **Sign-in is by e-mail address only** (case-insensitive, unique: index `auth_user_email_ci_uq`); the internal username of a gateway account is the same e-mail. Administrators add users by e-mail and can change a user's sign-in e-mail (this ends their sessions); users cannot change their own. The Site Agent installer (1.1+) also signs in with the organization e-mail. |
| **Access** | A user needs at least one gateway **role in an organization** (see Organizations below). Django superusers are *platform admins* and have every permission in every organization. Organization members without a role cannot sign in to the gateway. |
| **Permissions** | `gateway.view` (overview) · `agents.view` / `agents.manage` (the organization's site agents, revoke) · `delivery.manage` (add, test, edit, remove destinations; create, activate, pause, delete pipelines) · `users.manage` (create, edit, disable, reset password, unlock, sign out, assign roles) · `roles.manage` · `audit.view`. The built-in Administrator role always has every permission, including ones added later. |
| **Built-in roles** | Administrator (everything; cannot be edited), Operator (overview + site agents incl. revoke), Viewer (read-only), Auditor (overview + audit log). They are shared by every organization and only platform admins can change them. Custom roles belong to the organization that created them. |
| **No escalation** | Nobody can grant a role or permission they do not have; only platform admins can change platform admin accounts; you cannot disable yourself or remove your own user management. |
| **Password policy** | ≥ 12 characters, 3 of 4 character classes, not containing the user name, different from the current one. New and reset accounts get a temporary password and must choose their own at the first sign-in. |
| **Forgot password** | "Forgot password?" on the sign-in page e-mails a one-time link (`/reset-password?token=…`, valid 30 min; a new link replaces older ones; only its SHA-256 is stored in `bimba_gw_password_reset`). The answer is identical for unknown addresses and is given before the mail is sent. Setting the new password follows the policy, unlocks the account and ends all its sessions. Limits: 5 requests per IP and 3 links per account in 15 min, 20 bad links per IP. Mail: `BIMBA_SMTP_HOST/PORT/USER/PASSWORD/STARTTLS/SSL`, `BIMBA_MAIL_FROM`, links use `BIMBA_PUBLIC_URL` (never the request's Host). **Without `BIMBA_SMTP_HOST` the link is written to the gateway log** — set SMTP before real use. |
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
| **Isolation** | Every API call is scoped to the session's organization: other organizations' destinations, pipelines, rejected batches, site agents, users, custom roles and audit entries answer 404 or are not listed. A pipeline can only use its own organization's destinations and only select its own machines. |
| **Machines** | Each machine of the tag tree belongs to one organization (`bimba_gw_machine`); machines nobody assigned belong to the **default organization** (`bimba_gw_setting` `machines.default_org`), also ones added later. The delivery engine only gives a pipeline the readings of its organization's machines, so one organization never receives another's data. Overview shows the organization's machines, the devices serving them and its pipelines (platform admins see the whole gateway). |
| **Members** | Users (users.manage) lists the organization's members. *Add user* creates a new account or adds an **existing account** (for example someone from another organization) with roles here; *Remove* ends the roles here only. Removed while signed in, the session moves to the user's next organization (or ends when there is none). |
| **Shared accounts** | The account itself (name, e-mail, password, enabled, sign out everywhere) of someone who also belongs to another organization can only be changed by a platform admin; an organization admin can still change their roles here or remove them. |
| **Stale tabs** | The web app sends the organization it shows (`X-BIMBA-Org`). After another tab switched the session, a call from the old tab is refused (409 `org_changed`) and the tab reloads in the new organization. |
| **Platform admins** | *Organizations* page: create, rename, delete (empty ones only) organizations, choose the default organization, assign machines. *Audit log → All organizations* shows every organization's events. |
| **Site agents / BIMBA Operations** | Membership is `bimba_org_member`, which the site agent installer and Operations → Site agents already use; the gateway keeps its `admin` / `member` flag in step (admin = the roles allow `agents.manage`). |

Upgrade: Django migrations `0008_multi_org` + `0009_multi_org_required` give existing destinations, pipelines and user
roles the organization `default` ("Default organization"), which also becomes the default for machines, so delivery
continues unchanged.

Demo (`manage.py bimba_org demo-multi-org`): organizations **Northwind Water** and **Contoso Energy** and the user
**sam**, Administrator in Northwind and Operator in Contoso (password printed once; also kept in the gitignored
`site-agent/demo-users.local.txt`). `bimba_org add-user <org> <user> --gateway-role Viewer` gives anyone a role in an organization.

## HTTPS

**Production: the customer's certificate** (from their own certificate authority or a public one). Mount the files
and point the gateway at them, then restart:

| Variable | Value |
|---|---|
| `BIMBA_TLS_CERT` + `BIMBA_TLS_KEY` | PEM files: the certificate followed by its intermediates; the private key (PKCS#8 or RSA PKCS#1, not password-protected) |
| `BIMBA_TLS_PFX` + `BIMBA_TLS_PFX_PASSWORD` | or one PKCS#12 / PFX file |

```yaml
# docker-compose.yml → gateway
    environment:
      BIMBA_TLS_CERT: /tls/gateway.crt
      BIMBA_TLS_KEY: /tls/gateway.key
    volumes:
      - ./gateway-tls:/tls:ro
```

The key must belong to the certificate and the certificate must not be expired; otherwise the gateway logs the reason,
shows it in the console's download dialog, and keeps running on the self-signed certificate. Site agents then trust the
gateway like any web site (the machine's Windows must trust the issuer; domain machines usually get a company root CA
through Group Policy), so no fingerprint pinning is needed.

**Otherwise: self-signed.** On first start the gateway creates a self-signed certificate (5 years, RSA 2048) in the
`gateway-data` volume and prints its SHA-256 fingerprint in the log and in the console. Site agent installers show the
same fingerprint and pin it after the user confirms (`--trust-cert`, MSI `TRUSTCERT`).

**Agent client certificates (mutual TLS).** Separately, the gateway runs a private certificate authority for site
agents (`/data/tls/agent-ca.p12`, RSA 3072, 20 years, created on first start; back up the `gateway-data` volume, a lost
CA means every agent must be re-installed). The HTTPS port asks for a client certificate from this CA only and accepts
connections without one (browsers, agents still on their secret). See [site-agents.md](site-agents.md). A reverse proxy
that ends TLS in front of port 8443 would strip agent certificates; pass 8443 through as TCP instead, or set
`agents.issueCertificates` to false.

## Tech stack

| Layer | What | Where |
|---|---|---|
| Runtime | Java 21 (Eclipse Temurin), Spring Boot 4.1, embedded Tomcat 11 (HTTP 8090 + HTTPS 8443) | `build.gradle`, `BimbaGatewayApplication`, `web/HttpsConnector` |
| REST API | Spring Web MVC `@RestController`s, one JSON error shape (`ApiErrors`) | `web/ConsoleController` (`/api/gw/*`), `web/SystemController` (`/system/*`) |
| Security | Spring Security 7: filter chain, `@PreAuthorize` per call, SPA CSRF (XSRF-TOKEN cookie → `X-XSRF-TOKEN` header), CSP / frame / referrer headers; our database sessions and rules | `web/SecurityConfig`, `web/SessionAuthFilter`, `security/SecurityService` |
| Database | Spring JDBC `JdbcClient` + HikariCP on the shared PostgreSQL, own schema `bimba_ignition` (`BIMBA_GATEWAY_DB_SCHEMA` in .env, passed as `BIMBA_DB_SCHEMA`; Django migrations still define the tables, `migrate_gateway_schema`) | `Db`, `application.yml` |
| Field + delivery | j2mod (Modbus), Eclipse Milo (OPC UA), own expression evaluator; Kafka, Paho MQTT, HTTP | `collect/*`, `delivery/*` |
| Health | Spring Boot Actuator `/actuator/health` | `application.yml` |
| Console UI | React 19, React Router 7, Vite 8 (no UI framework; same calm style as BIMBA Operations) | `ui/` |
| Tests | JUnit 5: Django password compatibility, password policy, expressions, tag tree | `src/test/java` |

The gateway starts and keeps collecting even when the shared database is down (Hikari `initialization-fail-timeout: -1`);
only sign-in, site agent enrollment and heartbeats need it.

## Develop

```bash
cd bimba-gateway/ui
```
```bash
npm install
```
```bash
npm run dev
```

The dev server (http://localhost:5174) proxies `/api` and `/system` to the gateway on :8090, so hot reload works against
the running Docker gateway. Backend tests (no local JDK needed):

```bash
docker run --rm -v "${PWD}:/src" -v bimba-gradle9-cache:/home/gradle/.gradle -w /src gradle:9.2-jdk21 gradle test --no-daemon
```

## Run

```bash
docker compose up -d --build gateway
```
```bash
docker compose logs -f gateway
```

The legacy Ignition gateway and its BIMBA IT/OT Agent module have been removed from the repository (see git history).
Do not run both at the same time: both would read the devices and send every value twice.

## Not done yet
* OPC UA security policies other than None (needs the certificate trust exchange with the server).
* Modbus 32-bit / float registers (`HRF`, `HRI`...) and batch reads of neighbouring registers (one request per tag today).
* Devices and tags are edited in BIMBA Operations → Settings, not in the gateway console.
* No two-factor sign-in yet, and `/system/bimba-status` is still readable without signing in (BIMBA Operations reads it).
* One gateway per plant; no clustering / redundancy.
