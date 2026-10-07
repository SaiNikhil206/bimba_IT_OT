# BIMBA IT/OT Agent: Ignition module installation

## Build
```bash
.\scripts\build-module.ps1
```
Output: `ignition-module/build/modl/BIMBA-IT-OT-Agent-1.4.0.modl`, plus the same file as
`BIMBA-IT-OT-Agent.modl` (the stable name the Docker gateway mounts). The build runs in a
JDK 17 container, so no local Java is needed. It targets Ignition **8.1.33+**; tested
on 8.1.55.

## Install on a gateway
1. The POC module is **unsigned**. Allow unsigned modules by adding this to
   `data/ignition.conf` (or the `--` wrapper args in Docker) and restarting:
   `wrapper.java.additional.N=-Dignition.allowunsignedmodules=true`.
   For production, sign the module with your code-signing certificate instead (IA module-signer).
2. Go to **Gateway → Config → Modules → Install or Upgrade a Module**, upload the `.modl`,
   and accept it. In the Docker POC the file is mounted into `user-lib/modules`, so it
   loads at startup automatically.
3. On first start the module writes `data/bimba/config.json` with defaults. Edit it: the
   module reloads within about 10 s, with no restart needed.

## Tag convention
The module streams every tag under `tagRoots` (default `[default]BIMBA`):
```
[default]BIMBA/<machineId>/<tag>            ->  machineId, tagName
[default]BIMBA/PUMP-P1/motor_temp           ->  PUMP-P1, motor_temp
[default]BIMBA/PUMP-P1/Motor/Temp           ->  PUMP-P1, motor_temp
```
It re-browses every `rediscoverSeconds`, so new machines and tags are picked up automatically.
Values are sent **on change** (Ignition subscription), including quality and source
timestamp, with `EngUnit` as the unit.

## Demo tags
`config.json` → `provisionTagsFile: "demo-tags.json"` imports the POC tags once
(standard Ignition tag-export JSON, so you can also import it in the Designer):

| Folder | Source |
|---|---|
| `LINE-SIM` | expression tags. Live immediately, no device needed |
| `PUMP-P1`, `TANK-T1` | Modbus TCP device **`PLC`** → `plc-sim:5020` |
| `SUBSTN-M1` | OPC UA connection **`METER`** → `opc.tcp://opcua-sim:4840/scada/` |

Device connections (one-time, in the Gateway web UI, login `admin` / `password`):
1. **Config → OPC UA → Device Connections → Create new Device → Modbus TCP**:
   name `PLC`, hostname `plc-sim`, port `5020`. *(Already done in this POC.)*
2. **Config → OPC Client → OPC Connections → Create new OPC Connection → OPC UA**:
   endpoint `opc.tcp://opcua-sim:4840/scada/`, security policy **None**, name `METER`.
   Until this exists, the `SUBSTN-M1` tags arrive as quality **BAD**. That's expected,
   and the UI shows it.

## Status & troubleshooting
* `http://<gateway>:8088/system/bimba-status`: JSON with subscribed tags, active
  destination, per-destination state and last error, and disk-buffer size.
* Gateway logs: loggers `BIMBA`, `BIMBA.Collector`, `BIMBA.Delivery`, `BIMBA.Buffer`.
* Disk buffer: `data/bimba/buffer/*.json` for the main route, `buffer/mirror-<name>/` per mirror
  (one file per undelivered batch). Batches an
  end system rejects as invalid (HTTP 400/413/422) move to `buffer/rejected/`.

## config.json reference
| Key | Meaning |
|---|---|
| `plantId`, `edgeId` | Copied into every message and batch |
| `tagRoots` | Tag folders to stream |
| `batch.maxMessages` / `batch.maxWaitMs` | A batch is sent when either limit is reached (500 msgs / 1 s) |
| `buffer.maxMegabytes` | Disk buffer cap. The oldest batches are discarded beyond it |
| `destinations[]` | `primary` entries first, then `failover` (one "main" route). Each `mirror` gets its own independent route (see `docs/it-receivers.md`) |
| `…transport` | `eventstreams` (`url` = topic REST URL, `auth` bearer = environment token, `esMode` batch/message), `kafka` (`bootstrapServers`, `topic`, `kafkaMode` batch/message, `kafka` = producer properties), `rest` (`url`, `headers`, `auth` basic/bearer) or `mqtt` (`brokerUrl`, `topic` with `{plantId}`, `qos`) |
| `…retryAfterSeconds` | How long a failed destination is skipped before it's retried (failback) |
