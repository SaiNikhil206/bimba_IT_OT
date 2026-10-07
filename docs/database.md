# Database: shared PostgreSQL `bimba_chromalab`

Django and the Boomi listener both write to **`bimba_chromalab` on `103.120.176.105:6433`**
(login `bimba_pg_admin`). The database already holds the Chromalab application's tables. They
are all in schema `public`, including that app's own `django_migrations`.

**Every BIMBA object lives in schema `bimba`, and nothing in `public` is read or written.**

| Schema `bimba` | Created by |
|---|---|
| `bimba_machine`, `bimba_tag`, `bimba_measurement`, `bimba_ingest_batch` | Django migrations (`telemetry`) |
| `auth_*`, `django_admin_log`, `django_content_type`, `django_migrations`, `django_session` | Django migrations (Django's own tables) |
| function `bimba_ingest(jsonb, text)` | `install_sql` (pinned to `search_path=bimba`) |
| view `bimba_ingest_param` (zero rows, parameter descriptor for Boomi) | `install_sql` |

How that's enforced:
- `ensure_schema` (runs before `migrate`) creates the schema, and does nothing else.
- Every Django connection runs `SET search_path TO "bimba"` when it opens (`telemetry/apps.py`),
  so Django can't see or create tables in `public`. It's set per connection because the
  pooler on port 6433 rejects the `options` startup parameter.
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
- **Port 6433 is a pooler.** It keeps one backend per client session (so `SET` and `LISTEN`
  work), but it doesn't reset sessions when they're handed to the next client. Another
  client using the same login can inherit `search_path=bimba` or a `LISTEN`. That's harmless
  for BIMBA, because all its SQL is schema-qualified or pinned.
- Direct port 5432 isn't reachable from here (`pg_hba.conf`), and connections are **not SSL**.
  Use SSL before sending real plant data over the internet.
