# BIMBA Site Agent

A small Windows installer (`BIMBA-Site-Agent-Setup.exe`, about 90 KB, or `BIMBA-Site-Agent.msi` for Intune / SCCM /
Group Policy) that you run on any SCADA / HMI computer, whatever the SCADA product is. It registers the machine with **our BIMBA Setu** ([bimba-setu.md](bimba-setu.md)) and
then reports every minute that it is online. Each machine belongs to exactly one **organization**, and
only that organization's users can see it.

```
SCADA PC (any vendor)                      BIMBA Setu (:8443 https / :8090 http)          Shared PostgreSQL (schema bimba)
BIMBA-Site-Agent-Setup.exe                 POST /system/bimba-agent-enroll                   bimba_org, bimba_org_member,
  wizard: Setu + token OR user/password ──► checks token / Django password,     ───────► bimba_enroll_token, bimba_agent,
  shows what is shared, installs service      decides the organization                       bimba_agent_event
Windows service "BIMBASiteAgent"            POST /system/bimba-agent-certificate (CSR) → own client certificate
  (NT AUTHORITY\LocalService)  ──every 60 s─► POST /system/bimba-agent-heartbeat, mutual TLS          │
                                            console → Site agents (all orgs, by role)      ▼
                                                                         BIMBA Operations /operations/agents
                                                                         (organization sign-in, own machines only)
```

## Security model

| What | How |
|---|---|
| Who decides the organization | Setu, from the credentials. The agent never sends an organization id, so a machine can only land in the organization whose token or user installed it. |
| Enrollment token | Created by an organization admin (BIMBA Operations, Django admin or `bimba_org token`). Optional use limit and expiry, revocable. Only its SHA-256 is stored; the token is shown once. |
| Organization user + password | Same Django account as in BIMBA Operations (PBKDF2 hash checked by Setu). A user in several organizations is asked to pick one, from their own organizations only. |
| After enrollment | Setu issues a random per-machine **agent secret** (only its SHA-256 is stored). The token or password is used once and never written to disk. |
| Machine certificate (mutual TLS) | On its first start the service creates an RSA 2048 key in **its own** Windows key store (LocalService, not exportable), sends a certificate request with its secret, and gets a client certificate from Setu's private **agent CA** (`/data/tls/agent-ca.p12`; CN = agent id, O = organization, client-auth only, 90 days). From then on it signs in with the certificate; Setu refuses the secret and the agent deletes it. Renewed automatically when a third of the lifetime is left. The previous credential stays valid until the new certificate has been used once, so a lost answer cannot lock a machine out. HTTPS only. |
| Secret on the machine | Until the certificate is in use: `%ProgramData%\BIMBA Site Agent\agent.json`, DPAPI-encrypted, folder ACL: SYSTEM + Administrators + the service account only. Afterwards only the certificate thumbprint is kept there. |
| Service account | `NT AUTHORITY\LocalService` (least privilege). It reads inventory and sends heartbeats, nothing else. |
| Brute force | 10 failed enrollments per client IP in 15 min → HTTP 429 (Setu), same for the Lumen sign-in and the admin page. |
| Visibility | Every Lumen API call is filtered by the signed-in user's organization membership; a foreign organization or machine answers 404. Django superusers (platform operators) see all organizations in BIMBA Operations; in the Setu console, users with the `agents.view` permission do. |
| Revocation | Organization admin → Revoke: the agent's next heartbeat gets 410 and the service stops (Setu checks every request against the agent's row and certificate hash, so no CRL is needed). Reinstalling with valid credentials registers it again (same entry, new certificate). |
| Transport | Use Setu's **HTTPS** port (8443). The wizard warns on plain HTTP. Production: give Setu the customer's certificate (`BIMBA_TLS_CERT` + `BIMBA_TLS_KEY` PEM, or `BIMBA_TLS_PFX` + `BIMBA_TLS_PFX_PASSWORD`, see [bimba-setu.md](bimba-setu.md)); agents then trust it like any web site. With the self-signed certificate, agents pin its SHA-256 fingerprint (wizard checkbox, `--trust-cert`, MSI `TRUSTCERT`). Set `"requireHttps": true` in `config.json` → `agents` to refuse plain HTTP. |
| Installer files | Code-signed (Authenticode SHA-256 + timestamp) by `scripts/build-site-agent.ps1` when a signing certificate is configured; the MSI carries the signed exe. |

### What is shared
Computer name, domain, Windows edition/version, manufacturer/model/serial, CPU/RAM, fixed disks (size/free),
network adapters (IPs, MAC), time zone, and the **SCADA / OT software found** (Programs and Features + Windows
services matched against a list: Ignition, WinCC/TIA, AVEVA/Wonderware, Citect, iFIX/CIMPLICITY, FactoryTalk/RSLinx,
Geo SCADA, KEPServerEX, Matrikon, zenon, GENESIS64, DeltaV/Ovation, Experion, CENTUM, 800xA, InduSoft, MC Works).
The machine is identified by a SHA-256 of the Windows MachineGuid. No user names, files, process data or
credentials are collected. Preview it on any PC without installing: `BIMBA-Site-Agent-Setup.exe inventory`.

## Set up an organization

BIMBA Operations needs one admin user per organization. On the Docker host:

```bash
docker compose exec django python manage.py bimba_org create acme "Acme Water"
```
```bash
docker compose exec django python manage.py bimba_org add-user acme alice --role admin
```
```bash
docker compose exec django python manage.py bimba_org token acme --label "Plant 1" --uses 10 --days 7
```

(`add-user` prompts for the password; `--role member` users can install and view but not manage tokens.)
The same is available in Django admin (`/admin` → Organizations, Enroll tokens) and, for organization admins,
in BIMBA Operations → **Site agents → Install on a machine**.

## Install on a machine

**Download** both installers from the Setu console: **Site agents → Download installer** (permission `agents.view`).
Enrollment tokens are created on the same page (**Enrollment tokens → New token**, permission `agents.manage`; shown once).
They run on Windows 10/11 and Server 2016+ (.NET Framework 4.8, built in).

**Wizard** (`BIMBA-Site-Agent-Setup.exe`): double-click → approve the administrator prompt → Setu address → enrollment
token *or* e-mail address + password → check the shared details → Install. The machine appears under Site agents within a
minute. An unsigned build downloaded from the console already contains Setu address; a signed build cannot be
changed, so the address is typed (the console shows it).

**Silent exe** (elevated prompt). The exe is a Windows GUI program, so wait for it:

```powershell
Start-Process .\BIMBA-Site-Agent-Setup.exe -ArgumentList 'install --server https://setu.example.com:8443 --token bimba_et_... --site "Plant 1"' -Wait -NoNewWindow -PassThru
```

**MSI** (`BIMBA-Site-Agent.msi`, Intune Win32 app / Configuration Manager / Group Policy startup script):

```powershell
msiexec /i BIMBA-Site-Agent.msi /qn SERVER=https://setu.example.com:8443 TOKEN=bimba_et_... SITE="Plant 1" [TRUSTCERT=<sha256>] [NAME=<display name>]
```

Windows Installer owns the file (`C:\Program Files\BIMBA Site Agent\bimba-site-agent.exe`), the service and the Apps
entry; the exe only registers the machine (`enroll`, before the service starts), and on removal tells Setu
(`uninstall-notify`, done by the service because only it can use its certificate) and deletes ProgramData
(`remove-data`). `TOKEN` is a hidden property, so it is not written to MSI logs. A newer MSI upgrades in place and keeps
the registration (no `TOKEN` needed). An agent installed with the .exe must be removed before the MSI is installed;
`BIMBA-Site-Agent-Setup.exe uninstall` on an MSI install runs `msiexec /x`. Group Policy software installation cannot
pass `SERVER` / `TOKEN`; use a startup script with the command above (or a transform).

Other commands: `uninstall [--quiet]`, `status` (shows whether the service signs in with its certificate), `inventory`,
and `test --server ... --token ...` (registers, sends one heartbeat and, over HTTPS, runs the certificate round trip
(issue, secret refused, renew) without installing anything). Exit codes: 0 ok, 1 failed, 2 usage, 3 credentials
refused, 4 choose `--org`, 5 not elevated. Log: `%ProgramData%\BIMBA Site Agent\logs\agent.log`.

### Build and sign

```powershell
.\scripts\build-site-agent.ps1
```

Builds `site-agent/dist/BIMBA-Site-Agent-Setup.exe`, `BIMBA-Site-Agent.msi` (WiX Toolset 5, restored from NuGet) and
`release.json` (version, SHA-256, signer; shown in the console). It signs both files when one of these is set:
`BIMBA_SIGN_THUMBPRINT` (certificate in the Windows store, also USB tokens / EV), `BIMBA_SIGN_PFX` +
`BIMBA_SIGN_PFX_PASSWORD`, or `BIMBA_SIGN_DLIB` + `BIMBA_SIGN_DMDF` (Azure Trusted Signing). `BIMBA_SIGN_TIMESTAMP`
sets the timestamp server (default DigiCert). `-RequireSigning` fails instead of building unsigned. To try the pipeline
before the real certificate exists, `scripts/new-test-signing-cert.ps1` creates an untrusted **test** certificate.
Setu serves `site-agent/dist` (mounted at `/installers`), so a rebuild needs no Setu rebuild.

## Data sources

An organization adds data sources to an agent in the Setu console (**Site agents → agent → Data sources → Add data
source**). The agent reads them on its computer, sends only changes to Setu, and each source reports as one
machine. **Test connection** and **Browse** run on the agent's computer. Every source is read only.

| Type | Agent | Tag address | Notes |
|---|---|---|---|
| OPC UA | all | node id (browse) | any OPC UA server; certificate pinning; one machine per device folder |
| Modbus TCP | all | `HR1`, `IR5`, `C3`, `DI2` (`2.HR1` = unit 2) | |
| OPC DA (classic) | Windows 1.6+ | item id (browse) | through the 32-bit bridge, local or DCOM |
| MQTT / Sparkplug B | 1.6+ | topic, `topic#field`, `group/node[/device]#metric` | subscribe only; rebirth requests optional |
| Siemens S7 | 1.6+ | `DB1.DBD0`, `MW10`, `I0.0`, `:DINT`, `:STRING[20]` | S7-300/400/1200/1500, LOGO! (PUT/GET on, no optimized blocks) |
| EtherNet/IP (Logix) | 1.6+ | `Line1_Speed`, `Motor.Speed`, `Levels[3]` | ControlLogix / CompactLogix |
| DNP3 | 1.7+ | `AI5`, `BI3`, `DBI2`, `BO1`, `AO2`, `CT4`, `FC4`, `OS1` | see below |
| IEC 60870-5-104 | 1.7+ | IOA `4001`, or `2/4001` for common address 2 | see below |
| SQL database / historian | 1.7+ | tag name, or column name | SQL Server, PostgreSQL, MySQL / MariaDB; see below |

Every numeric tag can be **scaled** (raw low–high → scaled low–high, e.g. counts 0–32767 → 0–100 %), converted to
another unit and checked against an engineering range (outside = UNCERTAIN), whatever the protocol.

**DNP3** (outstation over TCP, default port 20000). Settings: outstation address, and the agent's own **master
address**: one the SCADA master does not use, allowed on the outstation (most RTUs accept several masters, often each on
its own port). Each read is a Class 0 poll, the current value of every point. Events are never read, so the
outstation's event buffers stay with the SCADA master. The agent only sends READ and the confirmations the protocol
requires. Point flags become the quality: not ONLINE, RESTART or COMM_LOST = BAD; forced, over range, reference
error, chatter filter = UNCERTAIN. **Browse** lists the points the outstation reports, by type.

**IEC 60870-5-104** (port 2404). Settings: common address, how often all values are read again (general + counter
interrogation; default every 15 minutes, also after the outstation restarts), and the time zone of its time tags. In
between, the outstation's spontaneous data keeps the values current. Reads single/double points, step positions,
bitstrings, measured values (normalized values arrive as -1..+1: scale them), integrated totals (read without
freezing or resetting them), with or without time tag. IV = BAD; NT, SB, BL, OV = UNCERTAIN. No commands besides the
interrogations, no clock setting. The outstation must accept one more connection.

**SQL database / historian.** Settings: database (SQL Server, PostgreSQL, MySQL / MariaDB), server, port, SQL Server
instance (e.g. `SQLEXPRESS`, `WINCC`), database name, sign-in, encryption, the **query** and its result layout:

* **One row per tag**: the query returns `tag` and `value`, optionally `time` and `quality` (use AS). `{tags}` becomes
  the source's tag names (as parameters). AVEVA (Wonderware) Historian:
  `SELECT TagName AS tag, Value AS value, DateTime AS time, OPCQuality AS quality FROM Runtime.dbo.Live WHERE TagName IN ({tags})`.
  With several rows for a tag, the newest (by `time`) counts. Tag names match regardless of case.
* **One column per tag**: one row, each tag's address is a column name, an optional `time` column is the row's time:
  `SELECT TOP 1 * FROM dbo.LineData ORDER BY [time] DESC`.

Quality column: OPC numbers (192+ GOOD, 64–191 UNCERTAIN, lower BAD), `GOOD` / `BAD` / `UNCERTAIN` text, or true /
false. Times without a zone (SQL Server `datetime`) are read in the source's time zone. **Stale after** sends values
whose time is older as UNCERTAIN. The source's query is run once per read cycle (default 10 s). Read only: Setu
and the agent accept one `SELECT` (or `WITH … SELECT`) statement and refuse anything that writes, runs procedures or
reads files; the connection is opened read only where the database supports it, and the connection address is built
from the separate fields (no free JDBC URL). Still, **use a login that can only read** (`db_datareader`, `GRANT SELECT`).

Test outstations and databases for all of these: `bimba-setu/agent/test-linux` (`dnp3_sim.py` uses opendnp3,
`iec104_sim.py` uses lib60870, `sql_seed.sh` fills SQL Server, PostgreSQL and MariaDB containers; `utility_check.py` runs
the console requests against them).

## Setu configuration

Works without changes: Setu reads the database from its `BIMBA_DB_*` environment
(docker-compose passes the same values as Django). Optional section in `ignition/bimba/config.json`:

```json
"agents": {
  "enabled": true,
  "heartbeatSeconds": 60,
  "requireHttps": false,
  "maxFailures": 10,
  "failureWindowMinutes": 15,
  "issueCertificates": true,
  "certificateDays": 90
}
```

`issueCertificates: false` keeps agents on their secret (for example behind a proxy that ends TLS before Setu,
where client certificates cannot reach it).

Setu console (`http://<setu>:8090/` → **Site agents**) lists every organization's machines for users
with the `agents.view` permission; `agents.manage` can revoke them (see [bimba-setu.md](bimba-setu.md)). The registry's DB health is in `/system/bimba-status` → `siteAgents`.

## Not done yet
* **Code-signing certificate**: the build signs with whatever is configured; release builds need the company's real
  certificate (CA-issued or Azure Trusted Signing). Until then the files are unsigned or test-signed and Windows warns.
* The MSI install, upgrade and uninstall have not been run on a real machine yet (they need an elevated prompt).
* No console-generated MSI transform (.mst) for Group Policy yet.
* DNP3: TCP only (no serial lines), no Secure Authentication; IEC 104: no TLS (IEC 62351-3) yet; SQL: no Oracle yet,
  no Windows (integrated) sign-in for SQL Server.
* Sign-in to BIMBA Operations is per organization for **Site agents only**; the other /operations pages are still open (POC).
* No automatic agent updates: re-run a newer installer over the old one (it keeps the registration).
