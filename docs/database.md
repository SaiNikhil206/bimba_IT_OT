# Database: shared PostgreSQL `bimba_chromalab`

Django and the Boomi listener both write to **`bimba_chromalab` on `103.120.176.105:6433`**
(login `bimba_pg_admin`). The database already holds the Chromalab application's tables. They
are all in schema `public`, including that app's own `django_migrations`.

**Every BIMBA object lives in a BIMBA schema, and nothing in `public` is read or written.**

| Schema | Used by | Holds |
|---|---|---|
| `bimba` (`BIMBA_DB_SCHEMA`) | Django (BIMBA Operations, BIMBA Ingest), Boomi listener | readings (`bimba_machine`, `bimba_tag`, `bimba_measurement`, `bimba_ingest_batch`), Operations accounts and organizations |
| `bimba_ignition` (`BIMBA_GATEWAY_DB_SCHEMA`) | BIMBA Gateway (the Ignition-replacement server; also the legacy Ignition module) | gateway accounts, organizations, roles, sessions, audit log, destinations, pipelines, machine owners, site agents |

Both schemas have the same tables (the same Django migrations). Django migrates `bimba` and then, with
`migrate_gateway_schema` at every start, `bimba_ignition`; the gateway (Java) only uses `bimba_ignition` and writes
every name schema-qualified. On 2026-10-08 the gateway's configuration was copied from `bimba` into `bimba_ignition`
(not the readings). The copies of the gateway tables left in `bimba` are no longer used. Accounts are now separate:
a password changed in the gateway console does not change the Operations / Django admin account, and site agents
enrolled through the gateway are recorded in `bimba_ignition` only.

| Schema `bimba` | Created by |
|---|---|
| `bimba_machine`, `bimba_tag`, `bimba_measurement`, `bimba_ingest_batch` | Django migrations (`telemetry`) |
| `auth_*`, `django_admin_log`, `django_content_type`, `django_migrations`, `django_session` | Django migrations (Django's own tables) |
| function `bimba_ingest(jsonb, text)` | `install_sql` (pinned to `search_path=bimba`) |
| view `bimba_ingest_param` (zero rows, parameter descriptor for Boomi) | `install_sql` |

How that's enforced:
- `ensure_schema` (runs before `migrate`) creates the schema, and does nothing else.
- Django's database backend `bimba/pgbackend` sends **every statement** as
  `SET LOCAL search_path TO "<schema>"; <statement>` in one round trip, so the schema applies to exactly that statement
  on whatever server connection the pooler picks (see below). A session-level `SET search_path` (the earlier approach)
  is not safe there: on 2026-10-08 a migration ran on a pooled connection with the default path and created BIMBA
  tables in `public`; they were removed again the same day.
- `bimba_ingest()` carries `SET search_path FROM CURRENT` (= `bimba`), and Boomi calls it
  schema-qualified (`bimba.bimba_ingest(...)`).

Checked after the switch: `public` still has its 8 tables, and `public.django_migrations` still has
its 9 rows (apps `administration`, `chromalab`).

## Configuration
`.env` (not in git) holds `BIMBA_DB_HOST/PORT/NAME/USER/PASSWORD/SCHEMA`. Set them by hand or
with `.\scripts\set-database.ps1` (hidden password prompt). They're required: there is no local
database, so `docker compose up` refuses to start until `BIMBA_DB_HOST/USER/PASSWORD` are set.
The Boomi connection **BIMBA PostgreSQL Connection** stores the same credentials, encrypted in Boomi.

## Constraints of this server
- **PostgreSQL 10.23.** Django 5.x needs 13+, so the backend is pinned to **Django 4.0.10**
  (plus Channels 3.0.5, psycopg2, Python 3.10). Django 4.0 is end-of-life: upgrade the
  server to PostgreSQL 13+ and move Django back to a supported release (4.2 LTS / 5.x).
  `db_default` (Django 5) was replaced by `default=timezone.now` plus DB-side
  `DEFAULT now()` set in migration `0001`.
- **Port 6433 is a transaction-mode pooler.** Each transaction may run on another server connection, and session
  settings (`SET`, `LISTEN`) stay on the server connection, where the next client of the same login inherits them.
  So nothing in BIMBA may depend on a session setting: Django pins the schema per statement (`bimba/pgbackend`,
  server-side cursors off), the gateway and Boomi write schema-qualified names, `LISTEN/NOTIFY` falls back to polling.
- A client that drops in the middle of a transaction can leave it "idle in transaction" on the pooler, holding its
  locks (seen 2026-10-08 during a migration). End it with `pg_terminate_backend(pid)` (own login only) after checking
  `pg_stat_activity`.
- Direct port 5432 isn't reachable from here (`pg_hba.conf`), and connections are **not SSL**.
  Use SSL before sending real plant data over the internet.
