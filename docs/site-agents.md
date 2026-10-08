# BIMBA Site Agent

A small Windows installer (`BIMBA-Site-Agent-Setup.exe`, about 90 KB, or `BIMBA-Site-Agent.msi` for Intune / SCCM /
Group Policy) that you run on any SCADA / HMI computer, whatever the SCADA product is. It registers the machine with **our BIMBA Gateway** ([bimba-gateway.md](bimba-gateway.md)) and
then reports every minute that it is online. Each machine belongs to exactly one **organization**, and
only that organization's users can see it.

```
SCADA PC (any vendor)                      BIMBA Gateway (:8443 https / :8090 http)          Shared PostgreSQL (schema bimba)
BIMBA-Site-Agent-Setup.exe                 POST /system/bimba-agent-enroll                   bimba_org, bimba_org_member,
  wizard: gateway + token OR user/password ──► checks token / Django password,     ───────► bimba_enroll_token, bimba_agent,
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
| Who decides the organization | The gateway, from the credentials. The agent never sends an organization id, so a machine can only land in the organization whose token or user installed it. |
| Enrollment token | Created by an organization admin (BIMBA Operations, Django admin or `bimba_org token`). Optional use limit and expiry, revocable. Only its SHA-256 is stored; the token is shown once. |
| Organization user + password | Same Django account as in BIMBA Operations (PBKDF2 hash checked by the gateway). A user in several organizations is asked to pick one, from their own organizations only. |
| After enrollment | The gateway issues a random per-machine **agent secret** (only its SHA-256 is stored). The token or password is used once and never written to disk. |
| Machine certificate (mutual TLS) | On its first start the service creates an RSA 2048 key in **its own** Windows key store (LocalService, not exportable), sends a certificate request with its secret, and gets a client certificate from the gateway's private **agent CA** (`/data/tls/agent-ca.p12`; CN = agent id, O = organization, client-auth only, 90 days). From then on it signs in with the certificate; the gateway refuses the secret and the agent deletes it. Renewed automatically when a third of the lifetime is left. The previous credential stays valid until the new certificate has been used once, so a lost answer cannot lock a machine out. HTTPS only. |
| Secret on the machine | Until the certificate is in use: `%ProgramData%\BIMBA Site Agent\agent.json`, DPAPI-encrypted, folder ACL: SYSTEM + Administrators + the service account only. Afterwards only the certificate thumbprint is kept there. |
| Service account | `NT AUTHORITY\LocalService` (least privilege). It reads inventory and sends heartbeats, nothing else. |
| Brute force | 10 failed enrollments per client IP in 15 min → HTTP 429 (gateway), same for the Lumen sign-in and the admin page. |
| Visibility | Every Lumen API call is filtered by the signed-in user's organization membership; a foreign organization or machine answers 404. Django superusers (platform operators) see all organizations in BIMBA Operations; in the gateway console, users with the `agents.view` permission do. |
| Revocation | Organization admin → Revoke: the agent's next heartbeat gets 410 and the service stops (the gateway checks every request against the agent's row and certificate hash, so no CRL is needed). Reinstalling with valid credentials registers it again (same entry, new certificate). |
| Transport | Use the gateway's **HTTPS** port (8443). The wizard warns on plain HTTP. Production: give the gateway the customer's certificate (`BIMBA_TLS_CERT` + `BIMBA_TLS_KEY` PEM, or `BIMBA_TLS_PFX` + `BIMBA_TLS_PFX_PASSWORD`, see [bimba-gateway.md](bimba-gateway.md)); agents then trust it like any web site. With the self-signed certificate, agents pin its SHA-256 fingerprint (wizard checkbox, `--trust-cert`, MSI `TRUSTCERT`). Set `"requireHttps": true` in `config.json` → `agents` to refuse plain HTTP. |
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

**Download** both installers from the gateway console: **Site agents → Download installer** (permission `agents.view`).
Enrollment tokens are created on the same page (**Enrollment tokens → New token**, permission `agents.manage`; shown once).
They run on Windows 10/11 and Server 2016+ (.NET Framework 4.8, built in).

**Wizard** (`BIMBA-Site-Agent-Setup.exe`): double-click → approve the administrator prompt → gateway address → enrollment
token *or* e-mail address + password → check the shared details → Install. The machine appears under Site agents within a
minute. An unsigned build downloaded from the console already contains the gateway address; a signed build cannot be
changed, so the address is typed (the console shows it).

**Silent exe** (elevated prompt). The exe is a Windows GUI program, so wait for it:

```powershell
Start-Process .\BIMBA-Site-Agent-Setup.exe -ArgumentList 'install --server https://gateway:8443 --token bimba_et_... --site "Plant 1"' -Wait -NoNewWindow -PassThru
```

**MSI** (`BIMBA-Site-Agent.msi`, Intune Win32 app / Configuration Manager / Group Policy startup script):

```powershell
msiexec /i BIMBA-Site-Agent.msi /qn SERVER=https://gateway:8443 TOKEN=bimba_et_... SITE="Plant 1" [TRUSTCERT=<sha256>] [NAME=<display name>]
```

Windows Installer owns the file (`C:\Program Files\BIMBA Site Agent\bimba-site-agent.exe`), the service and the Apps
entry; the exe only registers the machine (`enroll`, before the service starts), and on removal tells the gateway
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
The gateway serves `site-agent/dist` (mounted at `/installers`), so a rebuild needs no gateway rebuild.

## Gateway configuration

Works without changes: the gateway reads the database from its `BIMBA_DB_*` environment
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

`issueCertificates: false` keeps agents on their secret (for example behind a proxy that ends TLS before the gateway,
where client certificates cannot reach it).

The gateway console (`http://<gateway>:8090/` → **Site agents**) lists every organization's machines for users
with the `agents.view` permission; `agents.manage` can revoke them (see [bimba-gateway.md](bimba-gateway.md)). The registry's DB health is in `/system/bimba-status` → `siteAgents`.

## Not done yet
* **Code-signing certificate**: the build signs with whatever is configured; release builds need the company's real
  certificate (CA-issued or Azure Trusted Signing). Until then the files are unsigned or test-signed and Windows warns.
* The MSI install, upgrade and uninstall have not been run on a real machine yet (they need an elevated prompt).
* No console-generated MSI transform (.mst) for Group Policy yet.
* The agent registers and reports health only; it does not collect SCADA tag data (the BIMBA Gateway does that).
* Sign-in to BIMBA Operations is per organization for **Site agents only**; the other /operations pages are still open (POC).
* No automatic agent updates: re-run a newer installer over the old one (it keeps the registration).
