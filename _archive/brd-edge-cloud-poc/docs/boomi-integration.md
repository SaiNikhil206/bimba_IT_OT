# Boomi integration guide (Model B – Hybrid edge-to-cloud)

In this POC, Boomi sits **on the cloud side**. It subscribes to canonical messages
on the cloud broker and routes them to enterprise targets (BRD §3.2, FR-4). The plant
runs no Boomi runtime; it only needs the edge agent and the edge broker.

```
cloud-mqtt:1883  ──►  Boomi Atom (container "boomi-atom")
  bimba/v1/+/equipment_status/#   ──►  P1  ERP production tracking   ──► enterprise-mock:8090/erp/...
  bimba/v1/+/operator_log/#       ──►  P2  HRM shift adherence       ──► enterprise-mock:8090/hrm/...
  bimba/v1/+/telemetry/#          ──►  P3  Event Streams / data lake ──► Boomi Event Streams (or /datalake/...)
  bimba/v1/+/alarm/#              ──►  P4  (optional) notifications / CMMS
```

> The ingest service → TimescaleDB → Grafana path keeps running whether or not Boomi is
> connected. Boomi gets its **own** persistent MQTT subscription, so both consumers
> receive every message independently.

---

## 1. Start the Atom

1. In Boomi AtomSphere: **Manage → Runtime Management → New → Atom**, choose the
   Docker/Linux installer and **generate an installer token**.
2. Copy `.env.example` to `.env` and paste the token into `BOOMI_INSTALL_TOKEN`.
   Don't commit `.env`; `.gitignore` excludes it.
3. Start the Atom:
   ```powershell
   docker compose --profile boomi up -d boomi-atom
   docker compose logs -f boomi-atom
   ```
4. The Atom (`BIMBA-POC-Atom`) shows as online in Runtime Management. Attach it to an
   environment such as `BIMBA-POC`.

The Atom container is on the `cloud` Docker network, so it reaches these hosts by name:

| Host | Port | What |
|---|---|---|
| `cloud-mqtt` | 1883 | cloud MQTT broker (canonical messages) |
| `enterprise-mock` | 8090 | mock ERP / HRM / data-lake REST endpoints |
| `db` | 5432 | TimescaleDB (`scada`/`scada`, database `scada`), if a process needs JDBC |

## 2. Shared components

### MQTT connection: `BIMBA Cloud Broker`
| Field | Value | Environment extension? |
|---|---|---|
| Server / URL | `tcp://cloud-mqtt:1883` | **yes** (FR-2: broker agnosticism) |
| Client ID | `boomi-bimba-<process>` (unique per listener) | yes |
| Clean session | **false** (the broker queues messages while the process is stopped) | |
| MQTT version | 3.1.1 | |

To point Boomi at an existing enterprise broker (HiveMQ, EMQX, AWS IoT, Azure Event Grid
MQTT, etc.), change only the environment extension. The processes stay the same (FR-2).
For the TLS listener, use `ssl://cloud-mqtt:8883` with a client certificate issued by
the POC CA (volume `bimba-poc_certs`).

### JSON profile: `BIMBA Canonical v1`
Import `docs/canonical-schema.json`, or build the profile from one of its `examples`.
Key elements: `plantId`, `machineId`, `timestamp`, `tagName`, `tagValue`, `unit`,
`quality`, `category`, `messageId`, `source/protocol`.

`tagValue` is polymorphic. It's a number, boolean or string for telemetry and
equipment status, and an object for `operator_log` and `alarm`. The simplest approach
is one profile per category, each with the matching `tagValue` shape (see the examples).

### HTTP client connection: `BIMBA Enterprise Mock`
Base URL `http://enterprise-mock:8090`. Operations send POST with JSON. The Grafana
panel "Enterprise inbox" shows each delivery as it lands.

## 3. Processes

### P1: Equipment status → ERP (BRD matrix row 1)
1. **Start shape:** MQTT connector, *Listen* operation, topic `bimba/v1/+/equipment_status/#`, QoS 1.
2. **Decision:** keep only changes (optional: Process Property / Document Cache keyed by
   `machineId+tagName` holding the last value). Status is sent every poll.
3. **Map:** canonical → ERP production-status profile, for example:
   `plantId→Plant`, `machineId→WorkCenter`, `tagName+tagValue→Status`, `timestamp→EventTime`.
4. **Connector:** HTTP client POST `/erp/production-status`. In production this becomes the SAP
   (IDoc / OData / BAPI) or Oracle connector.

### P2: Operator log → HRM (BRD matrix row 3)
1. MQTT Listen `bimba/v1/+/operator_log/#`.
2. Map `tagValue.operator_id`, `operator_name`, `shift`, `action`, `timestamp` to the HRM
   attendance/shift-event profile. `LOGIN`/`LOGOUT`/`SHIFT_HANDOVER` drive shift adherence.
3. HTTP client POST `/hrm/shift-events`.
4. `messageId` is deterministic for these records, so pass it as the idempotency key.

### P3: Telemetry → Boomi Event Streams / data lake (BRD matrix rows 2 & 4)
1. MQTT Listen `bimba/v1/+/telemetry/#`.
2. Optional: batch or aggregate before sending. Use Flow Control + Data Process Combine
   (for example, 1 document per 5 s per machine) so thousands of tag updates/s don't turn
   into thousands of process executions (NFR-3).
3. **Boomi Event Streams connector**, *Produce* to topic `bimba.telemetry` (environment
   token from Event Streams). Downstream consumers (Kafka, the data warehouse and others)
   subscribe there, decoupled from the plant (FR-4).
4. Without Event Streams, POST to `/datalake/telemetry` on the mock instead.

### P4 (optional): Alarms
MQTT Listen `bimba/v1/+/alarm/#`, then route on `tagValue.state` (`ACTIVE`/`CLEARED`) to
email, Teams or a CMMS work-order API.

## 4. Test it
* Watch the Grafana panel **Enterprise inbox**, or `GET http://localhost:8090/inbox`.
* Stop the Boomi process for a minute, then start it again. With *clean session = false*
  the cloud broker holds the messages and Boomi catches up.
* Cut the WAN (`scripts/wan.ps1 down`). Boomi receives nothing, and the edge queues. Run
  `scripts/wan.ps1 up`: the backlog flows through to Boomi and the ERP/HRM mock.

## 5. Mapping to Model A (fully local edge Atom)
Model A uses the same pieces in different places. The Atom runs on the **plant** network next to
`edge-mqtt`, and listens on `tcp://edge-mqtt:1883` (canonical topics, or raw device
topics directly). The operator journal can be read by the Boomi Database connector
from `legacy-scada-db:5432/legacy` (JDBC), and the Atom's own disk queue provides the
store-and-forward. To try it, add `plant` to the `boomi-atom` service's networks.

## 6. Next step: OPC UA custom connector
The BRD calls for a Boomi Custom Connector for native OPC UA (Model A). The edge agent's
`OpcUaSource` class covers the same behaviour: connect, read a node list, map to
canonical. That makes it the reference for a Connector SDK implementation (Java, Eclipse
Milo client) that offers *Get* (read nodes), *Listen* (subscriptions/monitored items) and
*Browse* (import profile from the address space). The POC simulator on
`opc.tcp://localhost:4840/scada/` works as the test server.
