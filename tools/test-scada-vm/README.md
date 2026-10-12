# Test SCADA machine (Windows Server VM in Docker)

A **test-only** Windows Server 2022 machine that runs as a Docker container (`dockurr/windows`, QEMU/KVM), next to
Setu. Use it to try the BIMBA Site Agent installer without touching the laptop's Windows.

## Start

```powershell
.\tools\test-scada-vm\start.ps1
```

The script loads KVM inside Docker Desktop's Linux VM and copies `site-agent/dist` into `tools/test-scada-vm/shared`.
Then it starts the `test-scada` compose profile. Watch the screen at http://localhost:8006 (this PC only).

The first start downloads the official Windows Server 2022 evaluation ISO from Microsoft (5 GB, resumable) into
`tools/test-scada-vm/iso` (gitignored). The unattended install then takes 20–30 minutes. The disk lives in the Docker volume `test-scada-disk`, so later starts boot in about a minute.
Sign-in is user `bimba`, with the password from `BIMBA_TESTVM_PASSWORD` in `.env`. Windows signs in automatically.

## Install the agent

1. In the VM, open **Shared** on the desktop (drive `Z:`) → `BIMBA-Site-Agent-Setup.exe`.
2. Setu: `https://172.30.0.254:8443` (dockur forwards 172.30.0.254 to the Docker host, which publishes 8443). `setu` and the laptop's LAN IP do not work from inside the VM.
3. Trust Setu certificate (SHA-256 `4F:5E:D4:88…46:33`), then sign in with an EasyStepIn e-mail + password or
   an enrollment token → Install.
4. Setu console http://localhost:8090 → organization **EasyStepIn** → **Site agents** → machine `TEST-SCADA`.

SCADA software for the agent to detect, e.g. the Ignition trial for Windows: put its installer in
`tools/test-scada-vm/shared` and run it from `Z:` in the VM. Ignition can read the simulators
(Modbus `plc-sim:5020`, OPC UA `opc.tcp://opcua-sim:4840`).

## Stop / remove

```powershell
docker compose --profile test-scada stop test-scada
```
```powershell
docker compose --profile test-scada rm -sf test-scada; docker volume rm bimba_test-scada-disk
```

Revoke the machine in the console when you are done.
