import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Empty, Flow, full, Modal, Msg, PageHeader, Status, useOrgPath, usePoll, useToast, when } from "../ui.jsx";

// One site agent (/<org>/agents/<id>). The words, kept apart on purpose:
//   computer  the SCADA computer the agent is installed on: Windows, Linux or Raspberry Pi (Overview)
//   agent     the BIMBA Site Agent service on that computer: it reads the data sources and sends their readings
//   data source  a connection the agent reads (OPC UA server, Modbus / S7 / Logix PLC, MQTT broker, DNP3 / IEC 104
//                outstation, SQL database or historian, OPC DA server on Windows)
//   machine   what pipelines receive: one per data source, or one per device of its server (Machines)
// Testing a connection and browsing tags run on the computer itself: the console sends a request the agent picks up
// within seconds (POST agents/<id>/commands) and shows its answer.

const KINDS = { opcua: "OPC UA", modbus: "Modbus TCP", opcda: "OPC DA", mqtt: "MQTT", s7: "Siemens S7", eip: "EtherNet/IP",
  dnp3: "DNP3", iec104: "IEC 104", sql: "SQL / historian" };
/** sources that need a newer agent: kind -> the first agent version that reads them */
const NEEDS = { opcda: "1.6", mqtt: "1.6", s7: "1.6", eip: "1.6", dnp3: "1.7", iec104: "1.7", sql: "1.7" };
const SQL_ENGINES = [["sqlserver", "SQL Server", 1433], ["postgresql", "PostgreSQL", 5432], ["mysql", "MySQL / MariaDB", 3306]];
/** example queries per database and result layout (AVEVA Historian's Live table for SQL Server rows) */
const SQL_EXAMPLES = {
  sqlserver: {
    rows: "SELECT TagName AS tag, Value AS value, DateTime AS time, OPCQuality AS quality\nFROM Runtime.dbo.Live\nWHERE TagName IN ({tags})",
    columns: "SELECT TOP 1 *\nFROM dbo.LineData\nORDER BY [time] DESC",
  },
  postgresql: {
    rows: "SELECT tag_name AS tag, value, ts AS time\nFROM readings_latest\nWHERE tag_name IN ({tags})",
    columns: "SELECT *\nFROM line_data\nORDER BY time DESC\nLIMIT 1",
  },
};
SQL_EXAMPLES.mysql = SQL_EXAMPLES.postgresql;
const isExample = (q) => !String(q ?? "").trim() || Object.values(SQL_EXAMPLES).some((e) => e.rows === q || e.columns === q);
const S7_CPUS = [["S7_1500", "S7-1500"], ["S7_1200", "S7-1200"], ["S7_300", "S7-300"], ["S7_400", "S7-400"], ["LOGO", "LOGO! 8"]];
const MQTT_ADDRESS = { value: "Topic", json: "Topic#field", sparkplug: "Group/node#metric" };
const POLICIES = [
  ["Auto", "Strongest the server offers"],
  ["None", "No security (the server must allow it)"],
  ["Basic256Sha256", "Basic256Sha256"],
  ["Aes128_Sha256_RsaOaep", "Aes128-Sha256-RsaOaep"],
  ["Aes256_Sha256_RsaPss", "Aes256-Sha256-RsaPss"],
];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/** agents from 1.4.1 on can list a server's devices ("scan") */
const canScan = (v) => {
  const [a = 0, b = 0, c = 0] = String(v ?? "").split(/[^0-9]+/).filter(Boolean).map(Number);
  return a > 1 || (a === 1 && (b > 4 || (b === 4 && c >= 1)));
};
/** the agent (version v) reads this kind of source: OPC DA, MQTT, S7, Logix from 1.6, DNP3, IEC 104, SQL from 1.7 */
const reads = (kind, v) => {
  if (!NEEDS[kind]) return true;
  const [a = 0, b = 0] = String(v ?? "").split(/[^0-9]+/).filter(Boolean).map(Number);
  const [x, y] = NEEDS[kind].split(".").map(Number);
  return a > x || (a === x && b >= y);
};
/** same rule as Setu: other characters become "-" */
const machineName = (raw) => String(raw ?? "").replace(/[^A-Za-z0-9 ._-]+/g, "-").replace(/^[^A-Za-z0-9]+/, "").trim().slice(0, 120).trim() || "device";
/** "ns=1;s=[sim]" -> "sim" (until a scan gives the device's own name) */
const deviceLabel = (nodeId) => (/;s=(.*)$/.exec(nodeId ?? "")?.[1] ?? nodeId ?? "").replace(/^\[(.*)\]$/, "$1");
const fp = (hex) => (hex ? hex.toUpperCase().match(/.{2}/g).join(":") : "");
const short = (hex) => (hex ? `${fp(hex).slice(0, 11)}…${fp(hex).slice(-5)}` : "");

/** Sends a request to the agent and waits for its answer (the agent long-polls Setu). */
async function ask(agentId, body) {
  const { id } = await api(`agents/${agentId}/commands`, { method: "POST", body });
  for (let i = 0; i < 90; i++) {
    await sleep(i < 10 ? 400 : 1000);
    const r = await api(`agents/${agentId}/commands/${id}`, { background: true });
    if (r.state === "done") return r.result;
    if (r.state === "failed" || r.state === "expired") throw new Error(r.error || "The agent could not do it.");
  }
  throw new Error("The agent did not answer.");
}

const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;
const statusKind = (st) => (st === "online" ? "good" : st === "offline" ? "bad" : "");

/** every machine the agent's data sources report as, with the source it comes from */
function machinesOf(a) {
  return a.sources.flatMap((s) => s.machines.map((m) => ({ ...m, source: s })));
}

export default function AgentPage({ id }) {
  const { can } = useSession();
  const at = useOrgPath();
  const { data, error, reload } = usePoll(`agents/${id}`, 10_000);
  const [tab, setTab] = useState("overview");

  if (error && !data) {
    return (
      <>
        <p><Link to={at("/agents")}>← Site agents</Link></p>
        <Msg>{error}</Msg>
      </>
    );
  }
  if (!data) return <p className="muted">Loading…</p>;
  const st = data.status;
  const machines = machinesOf(data);
  const kinds = [...new Set(data.sources.map((s) => KINDS[s.kind]))].join(", ");
  return (
    <>
      <p className="crumb"><Link to={at("/agents")}>← Site agents</Link></p>
      <PageHeader title={data.name} subtitle={`Site agent on the computer ${data.hostname}${data.site ? ` · ${data.site}` : ""}`}>
        <Status kind={statusKind(st)}>{st}</Status>
      </PageHeader>
      <Flow label="How this agent's data flows" steps={[
        { key: "pc", label: "Computer", value: data.hostname, sub: data.os || "–", on: tab === "overview", onClick: () => setTab("overview") },
        { key: "agent", label: "Site agent", value: `Agent ${data.agentVersion}`, sub: `${st}${data.lastSeen ? ` · ${when(data.lastSeen)}` : ""}`,
          on: tab === "overview", onClick: () => setTab("overview") },
        { key: "src", label: "Data sources", value: data.sources.length ? plural(data.sources.length, "source") : "None yet",
          sub: kinds || "OPC UA, Modbus…", on: tab === "sources", onClick: () => setTab("sources") },
        { key: "m", label: "Machines", value: machines.length ? plural(machines.length, "machine") : "None yet",
          sub: machines.length ? machines.map((m) => m.name).join(", ") : "From the sources", on: tab === "machines", onClick: () => setTab("machines") },
        { key: "p", label: "Delivered by", value: "Pipelines", sub: "Choose machines", ...(can("gateway.view") ? { to: at("/pipelines") } : {}) },
      ]} />
      <div className="tabs" role="tablist">
        {[["overview", "Computer & agent"], ["sources", `Data sources${data.sources.length ? ` (${data.sources.length})` : ""}`],
          ["machines", `Machines${machines.length ? ` (${machines.length})` : ""}`], ["live", "Live values"]].map(([k, label]) => (
          <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? "on" : ""} onClick={() => setTab(k)}>{label}</button>
        ))}
      </div>
      {tab === "overview" && <Overview a={data} />}
      {tab === "sources" && <Sources a={data} canManage={can("agents.manage")} reload={reload} />}
      {tab === "machines" && <Machines a={data} toSources={() => setTab("sources")} />}
      {tab === "live" && <Live id={id} />}
    </>
  );
}

// ------------------------------------------------------------------ computer & agent

function Overview({ a }) {
  const d = a.details ?? {};
  const os = d.os ?? {};
  const hw = d.hardware ?? {};
  const c = a.collector ?? {};
  const edge = c.edge ?? {};
  const linux = d.platform === "linux";
  return (
    <div className="stack">
      <div className="mgrid">
        <Card title="Site agent" subtitle="The BIMBA service installed on the computer.">
          <dl className="kv">
            <dt>Status</dt><dd><Status kind={statusKind(a.status)}>{a.status}</Status></dd>
            <dt>Version</dt><dd>{a.agentVersion}</dd>
            <dt>Last report</dt><dd title={full(a.lastSeen)}>{when(a.lastSeen) || "–"}</dd>
            <dt>Signs in with</dt><dd>{a.signIn === "certificate" ? `its certificate (valid until ${full(a.certificateNotAfter)})` : "its enrollment secret"}</dd>
            <dt>Registered</dt><dd>{full(a.enrolledAt)}<small className="muted"> · {a.enrolledBy}</small></dd>
            <dt>Data collection</dt>
            <dd>
              {c.edge ? <EdgeState edge={edge} /> : <span className="muted">Not reported (agent before 1.4)</span>}
              {c.sent != null && <small className="muted">{c.sent.toLocaleString()} readings sent{c.rejected ? `, ${c.rejected} refused` : ""}</small>}
              {c.spool?.files > 0 && <small className="warnc">{c.spool.files} batch(es) waiting on the computer ({Math.round((c.spool.bytes ?? 0) / 1024)} KB)</small>}
              {c.lastUploadError && <small className="warnc" title={c.lastUploadError}>Upload: {c.lastUploadError}</small>}
            </dd>
          </dl>
        </Card>
        <Card title="Computer" subtitle="The SCADA computer the agent runs on.">
          <dl className="kv">
            <dt>Computer name</dt><dd>{d.hostname ?? a.hostname}</dd>
            {d.domain && <><dt>Domain</dt><dd>{d.domain}</dd></>}
            {linux
              ? <><dt>Linux</dt><dd>{os.name ?? a.os}{os.arch ? ` (${os.arch}${os.build ? `, kernel ${os.build}` : ""})` : ""}</dd></>
              : <><dt>Windows</dt><dd>{os.name ?? a.os}{os.version ? ` (${os.version}${os.arch ? `, ${os.arch}` : ""})` : ""}</dd></>}
            {os.lastBoot && <><dt>Last start</dt><dd title={full(os.lastBoot)}>{when(os.lastBoot)}</dd></>}
            {d.timezone && <><dt>Time zone</dt><dd>{d.timezone}</dd></>}
            <dt>IP address</dt><dd>{a.ip}<small className="muted"> · seen from {a.remoteIp}</small></dd>
          </dl>
        </Card>
      </div>
      <div className="mgrid">
        <Card title="SCADA / OT software" subtitle={linux ? "Found on the computer in the installed packages, services and /opt."
          : "Found on the computer in the installed programs and Windows services."}>
          {(a.scada ?? []).length === 0 ? <p className="muted">None detected.</p> : (
            <dl className="kv">
              {a.scada.map((s, i) => (
                <span key={i} style={{ display: "contents" }}>
                  <dt>{s.source === "service" ? "Service" : s.source === "folder" ? "Folder" : "Installed"}</dt>
                  <dd>{s.product} {s.version ?? ""}{s.source === "service" ? <small className="muted"> · {s.service} ({s.state})</small>
                    : s.source === "folder" ? <small className="muted"> · {s.path}</small> : null}</dd>
                </span>
              ))}
            </dl>
          )}
        </Card>
        <Card title="Hardware">
          <dl className="kv">
            <dt>Model</dt><dd>{[hw.manufacturer, hw.model].filter(Boolean).join(" ") || "–"}</dd>
            {hw.serialNumber && <><dt>Serial number</dt><dd className="mono">{hw.serialNumber}</dd></>}
            <dt>Processor</dt><dd>{hw.cpu ?? "–"}{hw.cores ? ` · ${hw.cores} cores, ${hw.logicalProcessors} threads` : ""}</dd>
            <dt>Memory</dt><dd>{hw.memoryGb != null ? `${hw.memoryGb} GB` : "–"}</dd>
            {(d.disks ?? []).map((k) => (
              <span key={k.drive} style={{ display: "contents" }}><dt>Disk {k.drive}</dt><dd>{k.freeGb} GB free of {k.sizeGb} GB</dd></span>
            ))}
          </dl>
        </Card>
      </div>
      {(d.network ?? []).length > 0 && (
        <Card title="Network" flush>
          <table>
            <thead><tr><th>Adapter</th><th>IP addresses</th><th>MAC</th></tr></thead>
            <tbody>
              {d.network.map((n) => (
                <tr key={n.name + n.mac}>
                  <td><b>{n.name}</b><small>{n.description}</small></td>
                  <td className="mono">{[...(n.ipv4 ?? []), ...(n.ipv6 ?? [])].join(", ")}{n.gateway && <small>gateway {n.gateway}</small>}</td>
                  <td className="mono nowrap">{n.mac}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  );
}

function EdgeState({ edge }) {
  const label = { running: "collecting", idle: "ready, nothing to collect", stopped: "stopped", missing: "not installed" }[edge.state] ?? edge.state;
  return (
    <>
      <Status kind={edge.state === "running" ? "good" : edge.state === "idle" ? "" : "bad"}>{label}</Status>
      {edge.error && <small className="warnc" title={edge.error}>{edge.error}</small>}
    </>
  );
}

// ------------------------------------------------------------------ machines

/** what pipelines receive from this agent: each machine, the data source it comes from, and its last readings */
function Machines({ a, toSources }) {
  const { can } = useSession();
  const at = useOrgPath();
  const { data: live } = usePoll(`agents/${a.id}/live`, 10_000);
  const rows = machinesOf(a);
  const devices = a.collector?.edge?.devices ?? [];
  const online = a.status === "online";
  const seen = new Map();
  for (const v of live?.values ?? []) {
    const m = seen.get(v.machine) ?? { good: 0, n: 0, at: null };
    m.n++;
    if (v.quality === "GOOD") m.good++;
    if (!m.at || v.timestamp > m.at) m.at = v.timestamp;
    seen.set(v.machine, m);
  }
  return (
    <div className="stack">
      <Card flush title="Machines"
            subtitle={<>What pipelines receive from this agent. A data source reports as one machine (its name), or as one machine per device
              of its server. Rename or remove machines in the data source.{can("gateway.view") && <> Choose where their data goes
              in <Link to={at("/pipelines")}>Pipelines</Link>.</>}</>}>
        <table className="narrow-cut">
          <thead><tr><th>Machine</th><th>From data source</th><th className="r">Tags</th><th>Status</th></tr></thead>
          <tbody>
            {rows.length === 0 && (
              <Empty cols={4}>No machines yet. A machine appears when you <button className="link" onClick={toSources}>add a data source</button>.</Empty>
            )}
            {rows.map(({ name, tags, source: s }) => {
              return (
                <tr key={`${s.id}/${name}`} className={s.enabled ? "" : "off"}>
                  <td style={{ minWidth: 0 }}><b className="ellipsis" title={name}>{name}</b></td>
                  <td style={{ minWidth: 0 }}>
                    <span className="ellipsis" title={s.name}>{s.name}</span>
                    <small className="mono ellipsis" title={target(s)}>{KINDS[s.kind]} · {target(s)}</small>
                  </td>
                  <td className="r">{tags}</td>
                  <td><MachineState s={s} dev={devices.find((d) => d.name === s.id)} online={online} last={seen.get(name)} version={a.agentVersion} /></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

/** a machine's state: its data source's, and when collecting, its own last readings */
function MachineState({ s, dev, online, last, version }) {
  if (!s.enabled || !dev || dev.error) return <SourceState s={s} dev={dev} online={online} version={version} />;
  return (
    <>
      <Status kind="good">collecting</Status>
      <small title={full(last?.at)}>{last ? `${last.good}/${last.n} tags good · ${when(last.at)}` : "no reading yet"}</small>
    </>
  );
}

// ------------------------------------------------------------------ data sources

function Sources({ a, canManage, reload }) {
  const toast = useToast();
  const [editing, setEditing] = useState(null);       // source | {new, ...preset}
  const [removing, setRemoving] = useState(null);
  const [acting, setActing] = useState(null);         // source id being checked, or source id + device being added
  const [notice, setNotice] = useState(null);
  const fresh = a.sources.flatMap((s) => (s.scan?.newDevices ?? []).map((d) => ({ s, d })));
  const missing = a.sources.filter((s) => s.scan?.missing?.length);

  const checkNow = async (s) => {
    setActing(s.id);
    setNotice(null);
    try {
      const r = await ask(a.id, { kind: "scan", sourceId: s.id, nodeId: s.settings.machinesFrom, limit: 5000 });
      toast(`${s.name}: ${(r.machines ?? []).length} device(s) on the server.`);
    } catch (e) {
      setNotice(`${s.name}: ${e.message}`);
    } finally {
      setActing(null);
      reload();
    }
  };

  const device = async (s, d, action) => {
    setActing(s.id + d.nodeId);
    setNotice(null);
    try {
      await api(`agents/${a.id}/sources/${s.id}/devices`, { method: "POST", body: { nodeId: d.nodeId, action } });
      toast(action === "add" ? `${d.name} added to ${s.name}. The agent applies it within seconds.` : `${d.name} ignored.`);
    } catch (e) {
      setNotice(e.message);
    } finally {
      setActing(null);
      reload();
    }
  };
  const devices = a.collector?.edge?.devices ?? [];
  const found = a.details?.dataSources ?? {};
  const used = new Set(a.sources.map((s) => s.settings?.endpoint ?? s.settings?.progId));
  const suggestions = [...(found.opcua ?? []), ...(found.opcda ?? [])].filter((s) => !used.has(s.endpoint ?? s.progId));
  const online = a.status === "online";

  return (
    <div className="stack">
      {!a.collector?.edge && <Msg kind="warn">This computer runs agent {a.agentVersion}. Install agent 1.4 or later on it to collect data.</Msg>}
      <Msg>{notice}</Msg>
      {canManage && fresh.length > 0 && (
        <Card title="New devices found" subtitle="They appeared on the server after its devices were added. Add one to send it as its own machine.">
          <div className="sugg">
            {fresh.map(({ s, d }) => (
              <div key={s.id + d.nodeId} className="sugg-i">
                <div>
                  <b className="ellipsis" title={d.name}>{d.name}</b>
                  <small className="ellipsis" title={`${s.name} · ${d.nodeId}`}>{d.tags} tag{d.tags === 1 ? "" : "s"} · {s.name}</small>
                </div>
                <span className="row-acts">
                  <button className="btn ghost sm" disabled={!!acting} onClick={() => device(s, d, "add")}>Add</button>
                  <button className="btn ghost sm" disabled={!!acting} onClick={() => device(s, d, "ignore")}>Ignore</button>
                </span>
              </div>
            ))}
          </div>
        </Card>
      )}
      {missing.map((s) => (
        <Msg key={s.id} kind="warn">
          {s.name}: {s.scan.missing.join(", ")} {s.scan.missing.length === 1 ? "is" : "are"} no longer on the server. Their tags read as bad until you untick them (Edit).
        </Msg>
      ))}
      {canManage && suggestions.length > 0 && (
        <Card title={`Found on ${a.hostname}`} subtitle="SCADA interfaces the agent found on the computer. Add one to start collecting its tags.">
          <div className="sugg">
            {suggestions.map((s) => (
              <div key={s.endpoint ?? s.progId} className="sugg-i">
                <div>
                  <b className="ellipsis" title={s.product ?? s.name}>{s.product ?? s.name}</b>
                  <small className="mono ellipsis" title={s.endpoint ?? s.progId}>{KINDS[s.kind]} · {s.endpoint ?? s.progId}</small>
                </div>
                <button className="btn ghost sm" onClick={() => setEditing(preset(s, a))}>Add</button>
              </div>
            ))}
          </div>
        </Card>
      )}
      <Card flush title="Data sources" subtitle={`Connections the agent reads on ${a.hostname}. Each reports as one machine, or as one machine per device of its server (see Machines). Only changes are sent.`}
            action={canManage && <button className="btn" onClick={() => setEditing({ kind: "opcua", settings: { endpoint: "opc.tcp://localhost:4840", policy: "Auto", auth: "Anonymous" }, tags: [], pollMs: 1000, enabled: true, name: "" })}>Add data source</button>}>
        <table>
          <thead><tr><th>Name</th><th>Source</th><th>Tags</th><th>Status</th><th /></tr></thead>
          <tbody>
            {a.sources.length === 0 && <Empty cols={5}>No data sources yet.{canManage && suggestions.length ? " Add one of the interfaces found above." : ""}</Empty>}
            {a.sources.map((s) => {
              const dev = devices.find((d) => d.name === s.id);
              return (
                <tr key={s.id} className={s.enabled ? "" : "off"}>
                  <td style={{ minWidth: 0 }}>
                    <b>{s.name}</b>
                    {(s.scan || s.machines.length > 1) && (
                      <small className="ellipsis" title={s.machines.map((m) => `${m.name} (${m.tags} tags)`).join(", ")}>
                        {s.machines.length} machine{s.machines.length === 1 ? "" : "s"}{s.machines.length ? `: ${s.machines.map((m) => m.name).join(", ")}` : ""}
                      </small>
                    )}
                    <small>every {s.pollMs >= 1000 ? `${s.pollMs / 1000} s` : `${s.pollMs} ms`}</small>
                  </td>
                  <td style={{ minWidth: 0 }}>
                    {KINDS[s.kind]}
                    <small className="mono ellipsis" title={target(s)}>{target(s)}</small>
                  </td>
                  <td>{s.tags.length}</td>
                  <td><SourceState s={s} dev={dev} online={online} version={a.agentVersion} />{s.scan && <ScanState scan={s.scan} busy={acting === s.id} />}</td>
                  <td className="r">
                    {canManage && (
                      <div className="row-acts">
                        {s.scan && online && canScan(a.agentVersion) && (
                          <button className="btn ghost sm" disabled={!!acting} onClick={() => checkNow(s)}>{acting === s.id ? "Checking…" : "Check for devices"}</button>
                        )}
                        <button className="btn ghost sm" onClick={() => setEditing(s)}>Edit</button>
                        <button className="btn ghost sm" title="Add a new data source with the same settings" onClick={() => setEditing(duplicate(s, a.sources))}>Duplicate</button>
                        <button className="btn danger sm" onClick={() => setRemoving(s)}>Remove</button>
                      </div>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
      {editing && (
        <SourceEditor agent={a} source={editing} onClose={() => setEditing(null)}
                      onSaved={(s) => { setEditing(null); toast(`${s.name} saved. The agent applies it within seconds.`); reload(); }} />
      )}
      {removing && (
        <Confirm title={`Remove ${removing.name}?`} danger confirm="Remove"
                 subtitle={`The agent stops reading it. Pipelines stop receiving machine ${removing.name}.`}
                 action={() => api(`agents/${a.id}/sources/${removing.id}`, { method: "DELETE" })}
                 onDone={() => { toast(`${removing.name} removed.`); setRemoving(null); reload(); }}
                 onClose={() => setRemoving(null)} />
      )}
    </div>
  );
}

function target(s) {
  const st = s.settings ?? {};
  if (s.kind === "opcua") return st.endpoint;
  if (s.kind === "modbus") return `${st.host}:${st.port ?? 502} unit ${st.unitId ?? 1}`;
  if (s.kind === "mqtt") return `${st.url}${st.payload && st.payload !== "value" ? ` (${st.payload})` : ""}`;
  if (s.kind === "s7") return st.cpu === "LOGO" ? `LOGO! ${st.host}` : `${(st.cpu ?? "S7_1500").replace("_", "-")} ${st.host} rack ${st.rack ?? 0} slot ${st.slot ?? (st.cpu === "S7_300" ? 2 : 1)}`;
  if (s.kind === "eip") return `Logix ${st.host} slot ${st.slot ?? 0}`;
  if (s.kind === "dnp3") return `DNP3 ${st.host}:${st.port || 20000} outstation ${st.outstation ?? 10}`;
  if (s.kind === "iec104") return `IEC 104 ${st.host}:${st.port || 2404} CA ${st.commonAddress ?? 1}`;
  if (s.kind === "sql") {
    const engine = SQL_ENGINES.find(([v]) => v === (st.engine ?? "sqlserver"))?.[1] ?? "SQL";
    return `${engine} ${st.host}${st.instance ? `\\${st.instance}` : ""}${st.database ? `/${st.database}` : ""}`;
  }
  return st.progId;
}

function SourceState({ s, dev, online, version }) {
  if (!s.enabled) return <Status>paused</Status>;
  if (!reads(s.kind, version)) return <><Status kind="warn">waiting</Status><small>{KINDS[s.kind]} needs agent {NEEDS[s.kind]} or later</small></>;
  if (!dev) return <Status>{online ? "starting" : "agent offline"}</Status>;
  if (dev.error) return <><Status kind="bad">no connection</Status><small className="bad ellipsis" title={dev.error}>{dev.error}</small></>;
  return <><Status kind="good">collecting</Status><small>{dev.goodTags}/{dev.tags} tags good · {when(dev.lastOk)}</small></>;
}

/** a per-device source's devices are checked again every 10 minutes: shown under its status */
function ScanState({ scan, busy }) {
  if (busy || scan.checking) return <small>checking the server for devices…</small>;
  if (scan.error) return <small className="warnc ellipsis" title={scan.error}>device check: {scan.error}</small>;
  const mode = { add: "new devices are added", suggest: "new devices are suggested", ignore: "new devices are ignored" }[scan.mode];
  return <small title={full(scan.at)}>{scan.at ? `devices checked ${when(scan.at)}` : "devices not checked yet"} · {mode}</small>;
}

function preset(found, a) {
  const base = (found.product ?? found.name ?? "SCADA").replace(/[^A-Za-z0-9 ._-]+/g, " ").trim();
  const name = `${a.hostname}-${base}`.replace(/\s+/g, "-").slice(0, 120);
  if (found.kind === "opcda") return { kind: "opcda", name, settings: { progId: found.progId, host: "" }, tags: [], pollMs: 1000, enabled: true };
  // no vendor defaults (accounts, folders): the person adding the source enters the server's own sign-in
  return { kind: "opcua", name, settings: { endpoint: found.endpoint, policy: "Auto", auth: "Anonymous" }, tags: [], pollMs: 1000, enabled: true };
}

/**
 * A new source with the settings of {@code s} (name -2, -3…): same server, sign-in (the password stays on Setu),
 * security and tags. A per-device source's devices are not copied: their machines exist already; Find devices lists them.
 */
function duplicate(s, all) {
  const names = new Set(all.map((x) => x.name.toLowerCase()));
  const base = s.name.replace(/-\d+$/, "").slice(0, 115);
  let i = 2;
  while (names.has(`${base}-${i}`.toLowerCase())) i++;
  const perDev = !!s.settings?.machinesFrom;
  const { devices: _d, ...settings } = s.settings ?? {};
  return {
    kind: s.kind, name: `${base}-${i}`, settings: perDev ? settings : { ...settings },
    tags: perDev ? [] : s.tags.map(({ machine: _m, ...t }) => ({ ...t })),
    pollMs: s.pollMs, enabled: true, perDevice: perDev, copyOf: s.id, copyName: s.name, hasPassword: s.hasPassword,
  };
}

/** the source's devices from its settings (node id -> machine, "" = ignored) and tags (each with its machine) */
function initDevs(source) {
  const tags = source.tags ?? [];
  return Object.entries(source.settings?.devices ?? {}).map(([nodeId, machine]) => ({
    nodeId, device: deviceLabel(nodeId), machine: machine || "", on: !!machine, auto: false,
    tags: machine ? tags.filter((t) => t.machine === machine).map(({ machine: _m, ...t }) => t) : [],
  }));
}

/** a scan's devices merged into the source's: machine names and units kept, new devices ticked */
function mergeScan(devs, r, prefix) {
  const byNode = new Map(devs.map((d) => [d.nodeId, d]));
  const out = (r.machines ?? []).map((m) => {
    const old = byNode.get(m.nodeId);
    byNode.delete(m.nodeId);
    const keep = new Map((old?.tags ?? []).map((t) => [t.address, t]));
    const tags = (m.tags ?? []).map((t) => keep.get(t.address) ?? { name: t.name, address: t.address, unit: "" });
    return old ? { ...old, device: m.name, tags, gone: false }
      : { nodeId: m.nodeId, device: m.name, machine: machineName(`${prefix ?? ""}${m.name}`), auto: true, on: tags.length > 0, tags };
  });
  return [...out, ...[...byNode.values()].map((d) => ({ ...d, gone: true }))];
}

// ------------------------------------------------------------------ source editor

function SourceEditor({ agent, source, onClose, onSaved }) {
  const isNew = !source.id;
  const [f, setF] = useState(() => ({
    name: source.name ?? "",
    kind: source.kind ?? "opcua",
    settings: { ...(source.settings ?? {}), password: source.id ? "" : source.settings?.password ?? "" },
    tags: (source.tags ?? []).map((t) => ({ ...t })),
    pollMs: source.pollMs ?? 1000,
    enabled: source.enabled ?? true,
  }));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [test, setTest] = useState(null);         // {busy} | {ok, result} | {error}
  const [browse, setBrowse] = useState(false);
  const [perDevice, setPerDevice] = useState(() => (source.kind ?? "opcua") === "opcua" && (!!source.perDevice || !!source.settings?.machinesFrom));
  const [devs, setDevs] = useState(() => initDevs(source));
  const [scan, setScan] = useState(null);         // {busy} | {count, truncated}
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const setS = (k, v) => setF((x) => ({ ...x, settings: { ...x.settings, [k]: v } }));
  const st = f.settings;
  const edge = agent.collector?.edge ?? {};
  const sourceForCommand = () => ({ kind: f.kind, settings: { ...st } });

  const runTest = async () => {
    setTest({ busy: true });
    try {
      const body = { kind: f.kind === "modbus" ? "read" : "test", source: sourceForCommand(), sourceId: source.id, passwordFrom: source.copyOf };
      if (f.kind !== "opcua") body.tags = f.tags.length ? f.tags.slice(0, 20) : [];
      setTest({ ok: true, result: await ask(agent.id, body) });
    } catch (e) {
      setTest({ error: e.message });
    }
  };

  const findDevices = async () => {
    setScan({ busy: true });
    setTest(null);
    try {
      const r = await ask(agent.id, { kind: "scan", source: sourceForCommand(), sourceId: source.id, passwordFrom: source.copyOf, nodeId: st.machinesFrom || null, limit: 5000 });
      setS("machinesFrom", r.root);
      setDevs((ds) => mergeScan(ds, r, st.machinePrefix));
      setScan({ count: (r.machines ?? []).length, truncated: r.truncated });
      setTest({ ok: true, result: { security: r.security, serverCertSha256: r.serverCertSha256 } });
    } catch (e) {
      setScan(null);
      setTest({ error: e.message });
    }
  };

  const setPrefix = (v) => {
    setS("machinePrefix", v);
    setDevs((ds) => ds.map((d) => (d.auto ? { ...d, machine: machineName(`${v}${d.device}`) } : d)));
  };

  const save = async () => {
    setError(null);
    const body = { ...f, settings: { ...st } };
    if (f.kind === "opcua" && perDevice) {
      const picked = devs.filter((d) => d.on && d.tags.length);
      const seen = new Set();
      const dup = picked.find((d) => seen.size === seen.add(d.machine.trim().toLowerCase()).size);
      if (!st.machinesFrom) return setError("Click Find devices first: the machines come from the server's devices.");
      if (dup) return setError(`Two devices report as machine "${dup.machine}". Give each device its own machine name.`);
      body.tags = picked.flatMap((d) => d.tags.map((t) => ({ ...t, machine: d.machine.trim() })));
      body.settings.devices = Object.fromEntries(devs.map((d) => [d.nodeId, d.on && d.tags.length ? d.machine.trim() : ""]));
      body.settings.newDevices = st.newDevices ?? "suggest";
    } else {
      for (const k of ["machinesFrom", "machinePrefix", "newDevices", "devices"]) delete body.settings[k];
      body.tags = f.tags.map(({ machine: _m, ...t }) => t);
    }
    setBusy(true);
    try {
      if (!body.settings.password) delete body.settings.password;
      if (source.copyOf) body.copyOf = source.copyOf;
      const r = await api(isNew ? `agents/${agent.id}/sources` : `agents/${agent.id}/sources/${source.id}`,
        { method: isNew ? "POST" : "PUT", body });
      onSaved(r);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const addTags = (list) => {
    setF((x) => {
      const have = new Set(x.tags.map((t) => t.address));
      return { ...x, tags: [...x.tags, ...list.filter((t) => !have.has(t.address))] };
    });
  };

  return (
    <Modal wide title={source.copyOf ? `Duplicate ${source.copyName}` : isNew ? "Add data source" : `Edit ${source.name}`}
           subtitle={`The site agent reads it on the computer ${agent.hostname} and sends it to Setu.`}
           onClose={onClose}
           footer={<>
             <button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</button>
           </>}>
      <Msg>{error}</Msg>
      <div className="grid2">
        <div className="field">
          <label htmlFor="src-name">{perDevice ? "Name" : "Machine name"}</label>
          <input type="text" id="src-name" value={f.name} maxLength={120} onChange={(e) => set("name", e.target.value)} placeholder={perDevice ? "e.g. LINE-1-SCADA" : "e.g. LINE-1-HMI"} />
          <span className="hint">{perDevice ? "Names this connection. Each device below reports as its own machine." : "Pipelines receive this source's readings as this machine. Unique on Setu."}</span>
        </div>
        <div className="field">
          <label htmlFor="src-kind">Type</label>
          <select id="src-kind" value={f.kind} disabled={!isNew} onChange={(e) => {
            const k = e.target.value;
            setF((x) => ({ ...x, kind: k, tags: [], pollMs: k === "sql" && x.pollMs < 5000 ? 10000 : x.pollMs,
              settings: k === "modbus" ? { host: "localhost", port: 502, unitId: 1 } : k === "opcua" ? { endpoint: "opc.tcp://localhost:4840", policy: "Auto", auth: "Anonymous" }
              : k === "mqtt" ? { url: "mqtt://localhost:1883", payload: "value", qos: 1 }
              : k === "s7" ? { host: "", cpu: "S7_1500", rack: 0, slot: 1 }
              : k === "eip" ? { host: "", backplane: 1, slot: 0 }
              : k === "dnp3" ? { host: "", port: 20000, outstation: 10, master: 1 }
              : k === "iec104" ? { host: "", port: 2404, commonAddress: 1, giMinutes: 15 }
              : k === "sql" ? { engine: "sqlserver", host: "localhost", database: "", tls: "off", layout: "rows", query: SQL_EXAMPLES.sqlserver.rows, staleSeconds: 0 }
              : { progId: "" } }));
            setTest(null);
          }}>
            <option value="opcua">OPC UA</option>
            <option value="modbus">Modbus TCP</option>
            <option value="mqtt">MQTT / Sparkplug B</option>
            <option value="s7">Siemens S7 (S7-300/400/1200/1500, LOGO!)</option>
            <option value="eip">Allen-Bradley Logix (EtherNet/IP)</option>
            <option value="dnp3">DNP3 outstation (RTU / IED)</option>
            <option value="iec104">IEC 60870-5-104 outstation</option>
            <option value="sql">SQL database / historian</option>
            {agent.details?.platform !== "linux" && <option value="opcda">OPC DA (classic)</option>}
          </select>
        </div>
      </div>

      {f.kind === "opcua" && (
        <div className="grid2">
          <div className="field span2">
            <label htmlFor="src-ep">Endpoint</label>
            <input type="text" id="src-ep" className="mono" value={st.endpoint ?? ""} placeholder="opc.tcp://localhost:4840"
                   onChange={(e) => setF((x) => ({ ...x, settings: { ...x.settings, endpoint: e.target.value, ...(source.copyOf ? { serverCert: "" } : {}) } }))} />
            <span className="hint">Seen from the computer: localhost = the SCADA server on {agent.hostname} itself. Any OPC UA server works (SCADA, HMI, OPC server, PLC); the endpoint is in its OPC UA settings.</span>
          </div>
          <div className="field">
            <label htmlFor="src-pol">Security</label>
            <select id="src-pol" value={st.policy ?? "Auto"} onChange={(e) => setS("policy", e.target.value)}>
              {POLICIES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="src-mode">Message security</label>
            <select id="src-mode" value={st.mode ?? "Auto"} disabled={(st.policy ?? "Auto") === "None"} onChange={(e) => setS("mode", e.target.value)}>
              <option value="Auto">Sign and encrypt if offered</option>
              <option value="SignAndEncrypt">Sign and encrypt</option>
              <option value="Sign">Sign only</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="src-auth">Sign-in</label>
            <select id="src-auth" value={st.auth ?? "Anonymous"} onChange={(e) => setS("auth", e.target.value)}>
              <option value="Anonymous">Anonymous</option>
              <option value="Username">User name + password</option>
            </select>
          </div>
          {st.auth === "Username" && (
            <>
              <div className="field">
                <label htmlFor="src-user">User name</label>
                <input type="text" id="src-user" value={st.username ?? ""} onChange={(e) => setS("username", e.target.value)} autoComplete="off" />
              </div>
              <div className="field">
                <label htmlFor="src-pw">Password</label>
                <input id="src-pw" type="password" value={st.password ?? ""} autoComplete="new-password"
                       placeholder={source.hasPassword ? (source.copyOf ? `same as ${source.copyName}` : "unchanged") : ""} onChange={(e) => setS("password", e.target.value)} />
                <span className="hint">Stored encrypted on Setu; sent only to this agent.</span>
              </div>
            </>
          )}
          <div className="field span2">
            <span className="label">Server certificate</span>
            {st.serverCert
              ? <span className="small">Only the trusted certificate <span className="mono" title={fp(st.serverCert)}>{short(st.serverCert)}</span> is accepted.{" "}
                  <button className="link" onClick={() => setS("serverCert", "")}>Forget it</button></span>
              : <span className="hint">Not pinned yet: Test connection shows the server's certificate, then you can trust it.</span>}
          </div>
        </div>
      )}

      {f.kind === "modbus" && (
        <div className="grid2">
          <div className="field">
            <label htmlFor="src-host">PLC host / IP</label>
            <input type="text" id="src-host" value={st.host ?? ""} onChange={(e) => setS("host", e.target.value)} placeholder="192.168.10.20" />
            <span className="hint">Seen from {agent.hostname}.</span>
          </div>
          <div className="field">
            <label htmlFor="src-port">Port</label>
            <input id="src-port" type="number" min={1} max={65535} value={st.port ?? 502} onChange={(e) => setS("port", Number(e.target.value))} />
          </div>
          <div className="field">
            <label htmlFor="src-unit">Unit id</label>
            <input id="src-unit" type="number" min={0} max={255} value={st.unitId ?? 1} onChange={(e) => setS("unitId", Number(e.target.value))} />
          </div>
          <label className="check" style={{ alignSelf: "end" }}>
            <input type="checkbox" checked={!!st.zeroBased} onChange={(e) => setS("zeroBased", e.target.checked)} /> Addresses start at 0
          </label>
        </div>
      )}

      {(f.kind === "s7" || f.kind === "eip") && (
        <div className="grid2">
          <div className="field">
            <label htmlFor="src-phost">PLC address</label>
            <input type="text" id="src-phost" className="mono" value={st.host ?? ""} onChange={(e) => setS("host", e.target.value)} placeholder="192.168.0.10" />
            <span className="hint">Host name or IP address, seen from {agent.hostname}.</span>
          </div>
          {f.kind === "s7" ? (
            <div className="field">
              <label htmlFor="src-cpu">CPU</label>
              <select id="src-cpu" value={st.cpu ?? "S7_1500"} onChange={(e) => setF((x) => ({ ...x, settings: { ...x.settings, cpu: e.target.value,
                slot: e.target.value === "S7_300" ? 2 : e.target.value === "LOGO" ? 0 : 1 } }))}>
                {S7_CPUS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </select>
            </div>
          ) : (
            <div className="field">
              <label htmlFor="src-bp">Backplane port</label>
              <input type="number" id="src-bp" min="-1" max="31" value={st.backplane ?? 1} onChange={(e) => setS("backplane", Number(e.target.value))} />
              <span className="hint">1 for ControlLogix / CompactLogix; -1 for a controller addressed directly (no routing).</span>
            </div>
          )}
          {!(f.kind === "s7" && st.cpu === "LOGO") && (
            <>
              {f.kind === "s7" && (
                <div className="field">
                  <label htmlFor="src-rack">Rack</label>
                  <input type="number" id="src-rack" min="0" max="7" value={st.rack ?? 0} onChange={(e) => setS("rack", Number(e.target.value))} />
                </div>
              )}
              <div className="field">
                <label htmlFor="src-slot">{f.kind === "s7" ? "Slot" : "CPU slot"}</label>
                <input type="number" id="src-slot" min="0" max="31" value={st.slot ?? (f.kind === "s7" ? 1 : 0)} onChange={(e) => setS("slot", Number(e.target.value))} />
                <span className="hint">{f.kind === "s7" ? "S7-300: 2, S7-400: see hardware config, S7-1200/1500: 1." : "The slot of the controller in the chassis (0 for CompactLogix)."}</span>
              </div>
            </>
          )}
          {f.kind === "s7" && (st.cpu === "S7_1200" || st.cpu === "S7_1500") && (
            <Msg kind="warn">In TIA Portal: allow PUT/GET communication in the CPU's protection settings, and turn off optimized block access on the data blocks read.</Msg>
          )}
        </div>
      )}

      {f.kind === "mqtt" && (
        <div className="grid2">
          <div className="field span2">
            <label htmlFor="src-url">Broker</label>
            <input type="text" id="src-url" className="mono" value={st.url ?? ""} onChange={(e) => setS("url", e.target.value)} placeholder="mqtt://broker:1883" />
            <span className="hint">Seen from {agent.hostname}: mqtt://host:1883, mqtts://host:8883 (TLS), ws:// or wss:// for MQTT over WebSocket. The agent only subscribes.</span>
          </div>
          <div className="field">
            <label htmlFor="src-payload">Messages</label>
            <select id="src-payload" value={st.payload ?? "value"} onChange={(e) => setS("payload", e.target.value)}>
              <option value="value">One value per topic</option>
              <option value="json">JSON (pick fields)</option>
              <option value="sparkplug">Sparkplug B</option>
            </select>
            <span className="hint">{st.payload === "sparkplug" ? "Tags: group/edge node[/device]#metric, e.g. Plant1/Line1/Press3#Pressure."
              : st.payload === "json" ? "Tags: topic#field, e.g. plant/line1/status#motor.speed (arrays: items[0])."
              : "Tags: the topic, e.g. plant/line1/temperature; the message is the value (21.5, true, text)."}</span>
          </div>
          <div className="field">
            <label htmlFor="src-qos">QoS</label>
            <select id="src-qos" value={st.qos ?? 1} onChange={(e) => setS("qos", Number(e.target.value))}>
              <option value={1}>1 · at least once</option>
              <option value={0}>0 · at most once</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="src-muser">User name</label>
            <input type="text" id="src-muser" value={st.username ?? ""} onChange={(e) => setS("username", e.target.value)} autoComplete="off" placeholder="none" />
          </div>
          <div className="field">
            <label htmlFor="src-mpw">Password</label>
            <input id="src-mpw" type="password" value={st.password ?? ""} autoComplete="new-password"
                   placeholder={source.hasPassword ? (source.copyOf ? `same as ${source.copyName}` : "unchanged") : ""} onChange={(e) => setS("password", e.target.value)} />
            <span className="hint">Stored encrypted on Setu; sent only to this agent.</span>
          </div>
          {/^(mqtts|ssl|wss):/i.test(st.url ?? "") && (
            <div className="field span2">
              <label htmlFor="src-ca">Broker CA certificate (optional)</label>
              <textarea id="src-ca" className="mono" rows={3} value={st.caCert ?? ""} onChange={(e) => setS("caCert", e.target.value)}
                        placeholder="-----BEGIN CERTIFICATE-----" spellCheck="false" />
              <span className="hint">Only for a private CA; otherwise the computer's trusted certificates are used.</span>
            </div>
          )}
          <div className="field">
            <label htmlFor="src-stale">Stale after (s)</label>
            <input type="number" id="src-stale" min="0" max="86400" value={st.staleSeconds ?? 0} onChange={(e) => setS("staleSeconds", Number(e.target.value))} />
            <span className="hint">0 = never. Otherwise a value without updates for that long is sent as UNCERTAIN.</span>
          </div>
          <div className="field">
            <label htmlFor="src-cid">Client id (optional)</label>
            <input type="text" id="src-cid" className="mono" value={st.clientId ?? ""} maxLength={64} onChange={(e) => setS("clientId", e.target.value)} placeholder="automatic" />
          </div>
          {st.payload === "sparkplug" && (
            <label className="check span2">
              <input type="checkbox" checked={!!st.rebirth} onChange={(e) => setS("rebirth", e.target.checked)} />
              Ask edge nodes to republish their metrics (Sparkplug rebirth) when the agent has not seen their birth.
              <small className="muted"> This sends a command (NCMD) to the edge node; off = read only, values appear as the edge nodes publish them.</small>
            </label>
          )}
        </div>
      )}

      {f.kind === "opcda" && (
        <div className="grid2">
          <div className="field span2">
            <label htmlFor="src-prog">OPC DA server</label>
            <input type="text" id="src-prog" className="mono" list="da-servers" value={st.progId ?? ""} onChange={(e) => setS("progId", e.target.value)} placeholder="Kepware.KEPServerEX.V6" />
            <datalist id="da-servers">
              {(agent.details?.dataSources?.opcda ?? []).map((s) => <option key={s.progId} value={s.progId}>{s.name}</option>)}
            </datalist>
            <span className="hint">The server's ProgID (or {"{CLSID}"}). Servers installed on {agent.hostname} are suggested.</span>
          </div>
          <div className="field">
            <label htmlFor="src-dahost">On computer</label>
            <input type="text" id="src-dahost" value={st.host ?? ""} onChange={(e) => setS("host", e.target.value)} placeholder="this computer" />
            <span className="hint">Empty = {agent.hostname} itself (recommended). Another computer works over DCOM if it allows it.</span>
          </div>
          <div className="field">
            <label htmlFor="src-dafrom">Read from</label>
            <select id="src-dafrom" value={st.readFrom ?? "cache"} onChange={(e) => setS("readFrom", e.target.value)}>
              <option value="cache">Server cache (recommended)</option>
              <option value="device">Device on every read</option>
            </select>
            <span className="hint">The cache is refreshed by the server at the read rate below; device reads load the PLC network.</span>
          </div>
        </div>
      )}

      {(f.kind === "dnp3" || f.kind === "iec104") && (
        <div className="grid2">
          <div className="field">
            <label htmlFor="src-ohost">Outstation host / IP</label>
            <input type="text" id="src-ohost" className="mono" value={st.host ?? ""} onChange={(e) => setS("host", e.target.value)} placeholder="192.168.0.30" />
            <span className="hint">The RTU, IED or protocol gateway, seen from {agent.hostname}.</span>
          </div>
          <div className="field">
            <label htmlFor="src-oport">Port</label>
            <input id="src-oport" type="number" min={1} max={65535} value={st.port ?? (f.kind === "dnp3" ? 20000 : 2404)} onChange={(e) => setS("port", Number(e.target.value))} />
          </div>
          {f.kind === "dnp3" ? (
            <>
              <div className="field">
                <label htmlFor="src-out">Outstation address</label>
                <input id="src-out" type="number" min={0} max={65519} value={st.outstation ?? 10} onChange={(e) => setS("outstation", Number(e.target.value))} />
                <span className="hint">The outstation's DNP3 address.</span>
              </div>
              <div className="field">
                <label htmlFor="src-master">Master address</label>
                <input id="src-master" type="number" min={0} max={65519} value={st.master ?? 1} onChange={(e) => setS("master", Number(e.target.value))} />
                <span className="hint">The agent's own DNP3 address: one the SCADA master does not use, allowed on the outstation.</span>
              </div>
              <p className="hint span2">Read only: every read is a Class 0 poll (the current value of every point). Events are never read, so they stay for the SCADA master.</p>
            </>
          ) : (
            <>
              <div className="field">
                <label htmlFor="src-ca">Common address</label>
                <input id="src-ca" type="number" min={1} max={65534} value={st.commonAddress ?? 1} onChange={(e) => setS("commonAddress", Number(e.target.value))} />
                <span className="hint">The station's ASDU address. A tag can name another one: 2/4001.</span>
              </div>
              <div className="field">
                <label htmlFor="src-gi">Read all values again</label>
                <select id="src-gi" value={st.giMinutes ?? 15} onChange={(e) => setS("giMinutes", Number(e.target.value))}>
                  <option value={0}>Only after connecting</option>
                  {[5, 15, 60, 240, 1440].map((m) => <option key={m} value={m}>Every {m < 60 ? `${m} min` : `${m / 60} h`}</option>)}
                </select>
                <span className="hint">A general interrogation. In between, changes arrive as the outstation sends them.</span>
              </div>
              <div className="field">
                <label htmlFor="src-tz">Time zone of time tags</label>
                <input type="text" id="src-tz" value={st.timeZone ?? ""} onChange={(e) => setS("timeZone", e.target.value)} placeholder="UTC" />
                <span className="hint">The outstation's clock, e.g. UTC or Europe/Berlin.</span>
              </div>
              <p className="hint span2">Read only: no commands besides the general interrogation, no clock setting. The outstation must accept one more connection.</p>
            </>
          )}
        </div>
      )}

      {f.kind === "sql" && (
        <div className="grid2">
          <div className="field">
            <label htmlFor="src-engine">Database</label>
            <select id="src-engine" value={st.engine ?? "sqlserver"} onChange={(e) => {
              const engine = e.target.value;
              setF((x) => ({ ...x, settings: { ...x.settings, engine, port: "", ...(engine === "sqlserver" ? {} : { instance: "" }),
                ...(isExample(x.settings.query) ? { query: SQL_EXAMPLES[engine][x.settings.layout ?? "rows"] } : {}) } }));
            }}>
              {SQL_ENGINES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
            <span className="hint">AVEVA (Wonderware) Historian and WinCC use SQL Server.</span>
          </div>
          <div className="field">
            <label htmlFor="src-dbhost">Server</label>
            <input type="text" id="src-dbhost" className="mono" value={st.host ?? ""} onChange={(e) => setS("host", e.target.value)} placeholder="historian01" />
            <span className="hint">Host name or IP address, seen from {agent.hostname}.</span>
          </div>
          {(st.engine ?? "sqlserver") === "sqlserver" && (
            <div className="field">
              <label htmlFor="src-inst">Instance (optional)</label>
              <input type="text" id="src-inst" className="mono" value={st.instance ?? ""} maxLength={16} onChange={(e) => setS("instance", e.target.value)} placeholder="default instance" />
              <span className="hint">e.g. SQLEXPRESS or WINCC. Without a port, the SQL Server Browser finds it.</span>
            </div>
          )}
          <div className="field">
            <label htmlFor="src-dbport">Port</label>
            <input id="src-dbport" type="number" min={1} max={65535} value={st.port ?? ""} onChange={(e) => setS("port", e.target.value === "" ? "" : Number(e.target.value))}
                   placeholder={String(st.instance && (st.engine ?? "sqlserver") === "sqlserver" ? "automatic" : SQL_ENGINES.find(([v]) => v === (st.engine ?? "sqlserver"))[2])} />
          </div>
          <div className="field">
            <label htmlFor="src-dbname">Database name</label>
            <input type="text" id="src-dbname" className="mono" value={st.database ?? ""} maxLength={128} onChange={(e) => setS("database", e.target.value)} placeholder="e.g. Runtime" />
          </div>
          <div className="field">
            <label htmlFor="src-tls">Encryption</label>
            <select id="src-tls" value={st.tls ?? "off"} onChange={(e) => setS("tls", e.target.value)}>
              <option value="off">Off</option>
              <option value="trust">Encrypted (certificate not checked)</option>
              <option value="verify">Encrypted and verified</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="src-duser">User name</label>
            <input type="text" id="src-duser" value={st.username ?? ""} maxLength={128} onChange={(e) => setS("username", e.target.value)} autoComplete="off" />
          </div>
          <div className="field">
            <label htmlFor="src-dpw">Password</label>
            <input id="src-dpw" type="password" value={st.password ?? ""} autoComplete="new-password"
                   placeholder={source.hasPassword ? (source.copyOf ? `same as ${source.copyName}` : "unchanged") : ""} onChange={(e) => setS("password", e.target.value)} />
            <span className="hint">Stored encrypted on Setu; sent only to this agent.</span>
          </div>
          <p className="hint span2">Use a database login that can only read (for example db_datareader). The agent runs only reading queries.</p>
          <div className="field span2">
            <span className="label">Result</span>
            <div className="seg two" role="radiogroup" aria-label="Result">
              {[["rows", "One row per tag", "Columns tag and value, optionally time and quality."],
                ["columns", "One column per tag", "One row; each tag is a column. A column time is the row's time."]].map(([v, b, d]) => (
                <label key={v}><input type="radio" name="src-layout" checked={(st.layout ?? "rows") === v}
                  onChange={() => setF((x) => ({ ...x, settings: { ...x.settings, layout: v,
                    ...(isExample(x.settings.query) ? { query: SQL_EXAMPLES[x.settings.engine ?? "sqlserver"][v] } : {}) } }))} />
                  <b>{b}</b><small>{d}</small></label>
              ))}
            </div>
          </div>
          <div className="field span2">
            <label htmlFor="src-query">Query</label>
            <textarea id="src-query" className="mono" rows={4} value={st.query ?? ""} onChange={(e) => setS("query", e.target.value)} spellCheck="false" />
            <span className="hint">{(st.layout ?? "rows") === "rows"
              ? "Name the columns with AS: tag, value, and optionally time and quality. {tags} becomes the tags below."
              : "The first row is read; each tag below is a column name."}</span>
          </div>
          <div className="field">
            <label htmlFor="src-dtz">Time zone of the times</label>
            <input type="text" id="src-dtz" value={st.timeZone ?? ""} onChange={(e) => setS("timeZone", e.target.value)} placeholder="UTC" />
            <span className="hint">For times stored without a zone (SQL Server datetime): the database server's, e.g. Europe/Berlin.</span>
          </div>
          <div className="field">
            <label htmlFor="src-dstale">Stale after (s)</label>
            <input type="number" id="src-dstale" min="0" value={st.staleSeconds ?? 0} onChange={(e) => setS("staleSeconds", Number(e.target.value))} />
            <span className="hint">0 = never. Otherwise a value whose time is older is sent as UNCERTAIN.</span>
          </div>
        </div>
      )}

      {!reads(f.kind, agent.agentVersion) && (
        <Msg kind="warn">This agent ({agent.agentVersion}) does not read {KINDS[f.kind]} yet: update it to {NEEDS[f.kind]} or later.</Msg>
      )}

      {f.kind === "opcua" && (
        <div className="field">
          <span className="label">Machines</span>
          <div className="seg two" role="radiogroup" aria-label="Machines">
            <label><input type="radio" name="src-machines" checked={!perDevice} onChange={() => setPerDevice(false)} />
              <b>One machine</b><small>Every tag reports as the machine name above.</small></label>
            <label><input type="radio" name="src-machines" checked={perDevice} onChange={() => setPerDevice(true)} />
              <b>One machine per device</b><small>Each device or device folder on the server becomes its own machine.</small></label>
          </div>
        </div>
      )}

      <div className="grid2">
        <div className="field">
          <label htmlFor="src-poll">Read every</label>
          <select id="src-poll" value={f.pollMs} onChange={(e) => set("pollMs", Number(e.target.value))}>
            {[250, 500, 1000, 2000, 5000, 10000, 30000, 60000, 300000].map((v) => (
              <option key={v} value={v}>{v < 1000 ? `${v} ms` : v < 60000 ? `${v / 1000} s` : `${v / 60000} min`}</option>
            ))}
          </select>
          <span className="hint">Only changes are sent.</span>
        </div>
        <label className="check" style={{ alignSelf: "end" }}>
          <input type="checkbox" checked={f.enabled} onChange={(e) => set("enabled", e.target.checked)} /> Collect this source
        </label>
      </div>

      {f.kind === "opcua" && perDevice && (
        <div className="grid2">
          <div className="field">
            <label htmlFor="src-prefix">Machine name prefix</label>
            <input type="text" id="src-prefix" value={st.machinePrefix ?? ""} maxLength={60} onChange={(e) => setPrefix(e.target.value)} placeholder="e.g. PLANT1-" />
            <span className="hint">Goes before each device name, e.g. PLANT1-sim. Keeps machines of different sites apart.</span>
          </div>
          <div className="field">
            <label htmlFor="src-new">New devices on the server</label>
            <select id="src-new" value={st.newDevices ?? "suggest"} onChange={(e) => setS("newDevices", e.target.value)}>
              <option value="suggest">Suggest them here</option>
              <option value="add">Add them automatically</option>
              <option value="ignore">Ignore them</option>
            </select>
            <span className="hint">The server is checked every 10 minutes.</span>
          </div>
        </div>
      )}

      {(
        <div className="test-row">
          <button className="btn ghost" disabled={test?.busy || scan?.busy || agent.status !== "online"} onClick={runTest}>
            {test?.busy ? "Asking the agent…" : f.kind === "modbus" || (f.kind !== "opcua" && f.tags.length) ? "Test connection (read tags)" : "Test connection"}
          </button>
          {f.kind === "opcua" && perDevice && (
            <button className="btn ghost" disabled={test?.busy || scan?.busy || agent.status !== "online" || !canScan(agent.agentVersion)} onClick={findDevices}>
              {scan?.busy ? "Finding devices…" : devs.length ? "Find devices again" : "Find devices"}
            </button>
          )}
          {agent.status !== "online" && <span className="hint">The agent is offline.</span>}
          {agent.status === "online" && perDevice && !canScan(agent.agentVersion) && (
            <span className="hint">Finding devices needs agent 1.4.1 or later (this agent is {agent.agentVersion}).</span>
          )}
        </div>
      )}
      {test?.error && (
        <Msg>
          {test.error}
          {/certificate/i.test(test.error) && edge.opcuaCertificate && (
            <><br /><small>The agent's OPC UA certificate: <span className="mono">{fp(edge.opcuaCertificate)}</span> ({edge.opcuaApplicationUri})</small></>
          )}
        </Msg>
      )}
      {test?.ok && f.kind === "opcua" && (
        <div className="msg good" role="status">
          Connected to {test.result.product ?? "the server"} {test.result.version ?? ""} ({test.result.security}).
          {test.result.serverCertSha256 && st.serverCert !== test.result.serverCertSha256 && (
            <div className="trust">
              <span>Server certificate <span className="mono" title={fp(test.result.serverCertSha256)}>{short(test.result.serverCertSha256)}</span></span>
              <button className="btn ghost sm" onClick={() => setS("serverCert", test.result.serverCertSha256)}>Trust only this certificate</button>
            </div>
          )}
        </div>
      )}
      {test?.ok && f.kind === "opcda" && (
        <div className="msg good" role="status">
          Connected to {test.result.product || test.result.target}{test.result.version ? ` ${test.result.version}` : ""}{test.result.state ? ` (${test.result.state})` : ""}.
          {(test.result.values ?? []).length > 0 && (
            <span> {test.result.values.map((v) => `${v.name} = ${v.good ? v.value : `${v.quality.toLowerCase()} (${v.detail})`}`).join(" · ")}</span>
          )}
        </div>
      )}
      {test?.ok && (f.kind === "s7" || f.kind === "eip") && (
        <div className="msg good" role="status">
          Connected to {test.result.target}{test.result.pdu ? ` (PDU ${test.result.pdu} bytes)` : ""}.
          {(test.result.values ?? []).length > 0 && (
            <span> {test.result.values.map((v) => `${v.name} = ${v.good ? v.value : `${v.quality.toLowerCase()} (${v.detail})`}`).join(" · ")}</span>
          )}
        </div>
      )}
      {test?.ok && (f.kind === "dnp3" || f.kind === "iec104" || f.kind === "sql") && (
        <div className="msg good" role="status">
          Connected to {test.result.target}
          {f.kind === "sql" ? `: the query returned ${test.result.rows} row${test.result.rows === 1 ? "" : "s"} (${(test.result.columns ?? []).join(", ")}).`
            : test.result.points ? `: ${test.result.points}.` : "."}
          {test.result.note && <span> {test.result.note}</span>}
          {(test.result.values ?? []).length > 0 && (
            <span> {test.result.values.map((v) => `${v.name} = ${v.good ? v.value : `${v.quality.toLowerCase()} (${v.detail})`}`).join(" · ")}</span>
          )}
        </div>
      )}
      {test?.ok && f.kind === "mqtt" && (
        <div className="msg good" role="status">
          Connected to {test.result.target}.
          {(test.result.values ?? []).length > 0 && (
            <span> {test.result.values.map((v) => `${v.name} = ${v.good ? v.value : `${v.quality.toLowerCase()} (${v.detail})`}`).join(" · ")}</span>
          )}
        </div>
      )}
      {test?.ok && f.kind === "modbus" && (
        <div className="msg good" role="status">
          Connected to {test.result.target}.
          {(test.result.values ?? []).length > 0 && (
            <span> {test.result.values.map((v) => `${v.name} = ${v.good ? v.value : `bad (${v.detail})`}`).join(" · ")}</span>
          )}
        </div>
      )}

      {f.kind === "opcua" && perDevice && scan?.count != null && (
        <p className="hint">{scan.count} device{scan.count === 1 ? "" : "s"} on the server{scan.truncated ? " (stopped at 5,000 tags: untick the devices you do not need)" : ""}.</p>
      )}
      {f.kind === "opcua" && perDevice
        ? <DeviceList devs={devs} onChange={setDevs} />
        : <TagTable kind={f.kind} payload={f.kind === "sql" ? st.layout ?? "rows" : st.payload} tags={f.tags} onChange={(tags) => set("tags", tags)}
                    onBrowse={["opcua", "opcda", "mqtt", "dnp3", "iec104", "sql"].includes(f.kind) && reads(f.kind, agent.agentVersion) && agent.status === "online" ? () => setBrowse(true) : null} />}
      {browse && <Browser agent={agent} source={sourceForCommand()} sourceId={source.id} passwordFrom={source.copyOf} onClose={() => setBrowse(false)}
                          onAdd={(list) => { addTags(list); setBrowse(false); }} />}
    </Modal>
  );
}

/** per-device mode: each device of the server, ticked = collected as the machine named next to it */
function DeviceList({ devs, onChange }) {
  const upd = (i, patch) => onChange(devs.map((d, j) => (j === i ? { ...d, ...patch } : d)));
  const on = devs.filter((d) => d.on && d.tags.length);
  if (!devs.length) return <p className="hint">Click Find devices: the agent lists the server's devices and their tags. Each device you tick becomes a machine.</p>;
  return (
    <div className="field">
      <span className="label">Devices: {on.length} of {devs.length} collected, {on.reduce((n, d) => n + d.tags.length, 0).toLocaleString()} tags</span>
      <div className="tags">
        <div className="dev-row head"><span /><span>Device on the server</span><span>Machine name</span><span className="r">Tags</span></div>
        {devs.map((d, i) => (
          <div className="dev-row" key={d.nodeId}>
            <input type="checkbox" aria-label={`Collect ${d.device}`} checked={d.on && d.tags.length > 0} disabled={!d.tags.length}
                   onChange={(e) => upd(i, { on: e.target.checked })} />
            <span className="b-name">
              <b className="ellipsis" title={d.device}>{d.device}</b>
              <small className={`mono ellipsis${d.gone ? " bad" : ""}`} title={d.nodeId}>{d.gone ? "no longer on the server" : d.nodeId}</small>
            </span>
            <input type="text" aria-label={`Machine name for ${d.device}`} value={d.machine} maxLength={120} disabled={!d.on || !d.tags.length}
                   onChange={(e) => upd(i, { machine: e.target.value, auto: false })} />
            <span className="r">{d.tags.length}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/** a tag uses unit conversion or an engineering range */
const engineered = (t) => [t.sourceUnit, t.engLow, t.engHigh, t.rawLow, t.rawHigh, t.scaledLow, t.scaledHigh]
  .some((v) => v !== undefined && v !== null && String(v).trim() !== "");
/** what a tag's address is, per source type (SQL: per result layout) */
const ADDRESS_HEAD = { opcua: "Node id", opcda: "Item id", eip: "Tag", dnp3: "Point", iec104: "IOA" };

function TagTable({ kind, payload, tags, onChange, onBrowse }) {
  const [more, setMore] = useState(() => new Set());       // rows whose unit / range settings are open
  const upd = (i, k, v) => onChange(tags.map((t, j) => (j === i ? { ...t, [k]: v } : t)));
  const del = (i) => {
    setMore(new Set());
    onChange(tags.filter((_, j) => j !== i));
  };
  const toggle = (i) => setMore((s) => {
    const n = new Set(s);
    if (n.has(i)) n.delete(i); else n.add(i);
    return n;
  });
  const add = () => onChange([...tags, { name: "", address: kind === "modbus" ? "HR1" : kind === "s7" ? "DB1.DBD0" : kind === "dnp3" ? "AI0" : "", unit: "" }]);
  return (
    <div className="field">
      <div className="tag-h">
        <span className="label">Tags ({tags.length})</span>
        <div className="row-acts">
          {onBrowse && <button className="btn ghost sm" onClick={onBrowse}>{kind === "mqtt" ? "Browse the broker…" : kind === "dnp3" || kind === "iec104" ? "Browse the outstation…"
            : kind === "sql" ? "Browse the result…" : "Browse the server…"}</button>}
          <button className="btn ghost sm" onClick={add}>Add a tag</button>
        </div>
      </div>
      {tags.length === 0 ? <p className="hint">{kind === "modbus" ? "Add the registers to read: HR1 (holding register), IR5, C3 (coil), DI2."
        : kind === "opcda" ? "Browse the server and tick the items to collect, or add item ids such as Channel1.Device1.Tag1."
        : kind === "mqtt" ? "Browse the broker (the agent listens for a few seconds) and tick the values, or add them by address."
        : kind === "s7" ? "Add the addresses to read: DB1.DBD0 (REAL), DB1.DBW4 (INT), DB1.DBX8.0 (BOOL), MW10, M0.1, IW64, Q0.1; add :DINT, :WORD, :STRING[20] for another type."
        : kind === "eip" ? "Add controller tags by name: Line1_Speed, Motor.Speed (UDT member), Levels[3] (array element), Program:Main.Count."
        : kind === "dnp3" ? "Browse the outstation, or add points: AI5 (analog input), BI3 (binary input), DBI2 (double-bit), BO1, AO2 (output status), CT4 (counter), FC4 (frozen counter)."
        : kind === "iec104" ? "Browse the outstation (the agent reads all values once), or add information object addresses: 4001, or 2/4001 for common address 2."
        : kind === "sql" ? (payload === "columns" ? "Browse the result, or add tags by column name." : "Browse the result, or add tags by their name in the tag column.")
        : "Browse the server and tick the tags to collect."}</p> : (
        <div className="tags">
          <div className="tag-row head"><span>Name</span><span>{kind === "mqtt" ? MQTT_ADDRESS[payload ?? "value"] : kind === "sql" ? (payload === "columns" ? "Column" : "Tag in the database") : ADDRESS_HEAD[kind] ?? "Address"}</span><span>Unit</span><span /></div>
          {tags.map((t, i) => (
            <div key={i}>
              <div className="tag-row">
                <input type="text" aria-label="Tag name" value={t.name ?? ""} onChange={(e) => upd(i, "name", e.target.value)} placeholder="name" />
                <input type="text" aria-label="Address" className="mono" value={t.address ?? ""} onChange={(e) => upd(i, "address", e.target.value)} title={t.address} />
                <input type="text" aria-label="Unit" value={t.unit ?? ""} onChange={(e) => upd(i, "unit", e.target.value)} placeholder="unit" />
                <div className="row-acts">
                  <button className={`btn ghost sm${engineered(t) ? " on" : ""}`} aria-expanded={more.has(i)} onClick={() => toggle(i)}
                          aria-label={`Unit and range of ${t.name || t.address}`} title="Unit conversion and engineering range">⋯</button>
                  <button className="btn ghost sm" aria-label={`Remove ${t.name || t.address}`} onClick={() => del(i)}>✕</button>
                </div>
              </div>
              {more.has(i) && (
                <div className="tag-more">
                  <label className="narrow-only"><span>Unit</span>
                    <input type="text" value={t.unit ?? ""} onChange={(e) => upd(i, "unit", e.target.value)} placeholder="e.g. degC" /></label>
                  <label><span>Device unit</span>
                    <input type="text" value={t.sourceUnit ?? ""} onChange={(e) => upd(i, "sourceUnit", e.target.value)} placeholder="same as unit" /></label>
                  <label><span>Range low</span>
                    <input type="number" step="any" value={t.engLow ?? ""} onChange={(e) => upd(i, "engLow", e.target.value)} placeholder="none" /></label>
                  <label><span>Range high</span>
                    <input type="number" step="any" value={t.engHigh ?? ""} onChange={(e) => upd(i, "engHigh", e.target.value)} placeholder="none" /></label>
                  <label><span>Raw low</span>
                    <input type="number" step="any" value={t.rawLow ?? ""} onChange={(e) => upd(i, "rawLow", e.target.value)} placeholder="none" /></label>
                  <label><span>Raw high</span>
                    <input type="number" step="any" value={t.rawHigh ?? ""} onChange={(e) => upd(i, "rawHigh", e.target.value)} placeholder="none" /></label>
                  <label><span>Scaled low</span>
                    <input type="number" step="any" value={t.scaledLow ?? ""} onChange={(e) => upd(i, "scaledLow", e.target.value)} placeholder="none" /></label>
                  <label><span>Scaled high</span>
                    <input type="number" step="any" value={t.scaledHigh ?? ""} onChange={(e) => upd(i, "scaledHigh", e.target.value)} placeholder="none" /></label>
                  <p className="hint">Device unit: the unit the device reports in (e.g. degF); values are converted to the tag's unit.
                    Outside the range, readings are sent with quality UNCERTAIN. Scaling (all four): raw low–high becomes scaled low–high,
                    e.g. counts 0–32767 to 0–100, before the unit conversion.</p>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/** what is browsed, per source type: root folder, title, what the agent does meanwhile */
const BROWSE = {
  opcua: ["Objects", "Browse the OPC UA server", "Asking the agent…"],
  opcda: ["Server", "Browse the OPC DA server", "Asking the agent…"],
  mqtt: ["Broker", "Browse the MQTT broker", "The agent listens to the broker for a few seconds…"],
  dnp3: ["Outstation", "Browse the DNP3 outstation", "The agent reads the outstation's points (Class 0)…"],
  iec104: ["Outstation", "Browse the IEC 104 outstation", "The agent reads all values (general interrogation)…"],
  sql: ["Result", "Browse the query's result", "The agent runs the query…"],
};

/** A server's address space (OPC UA, OPC DA), a broker's topics, an outstation's points or a query's result, browsed by
 * the agent: open folders, tick variables, add them as tags. */
function Browser({ agent, source, sourceId, passwordFrom, onClose, onAdd }) {
  const [root, title, waiting] = BROWSE[source.kind] ?? BROWSE.opcua;
  const [path, setPath] = useState([{ nodeId: null, name: root }]);
  const [note, setNote] = useState(null);
  const [items, setItems] = useState(null);
  const [error, setError] = useState(null);
  const [picked, setPicked] = useState({});
  const node = path[path.length - 1];

  useEffect(() => {
    let live = true;
    setItems(null);
    setError(null);
    ask(agent.id, { kind: "browse", source, sourceId, passwordFrom, nodeId: node.nodeId, limit: 1000 })
      .then((r) => { if (live) { setItems(r.items ?? []); setNote(r.note ?? null); } })
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
    // source is fixed while the browser is open
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [node.nodeId]);

  const vars = (items ?? []).filter((i) => i.nodeClass === "Variable");
  const count = Object.keys(picked).length;
  const toggle = (i) => setPicked((p) => {
    const n = { ...p };
    if (n[i.nodeId]) delete n[i.nodeId]; else n[i.nodeId] = i;
    return n;
  });
  const name = (i) => [...path.slice(1).map((p) => p.name.replace(/ \((group|edge node|device)\)$| \{…\}$/, "")), i.name]
    .filter((x) => !/^(Devices|Objects)$/i.test(x)).slice(-2).join("_")
    .replace(/[^A-Za-z0-9_]+/g, "_");

  return (
    <Modal wide title={title} subtitle={source.kind === "opcda" && source.settings.host ? `${source.settings.progId} on ${source.settings.host}` : target(source)} onClose={onClose}
           footer={<>
             <button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={!count} onClick={() => onAdd(Object.values(picked).map((i) => ({ name: i.tagName, address: i.nodeId, unit: "" })))}>
               Add {count || ""} tag{count === 1 ? "" : "s"}
             </button>
           </>}>
      <nav className="bc" aria-label="Folder">
        {path.map((p, i) => (
          <span key={i}>
            {i > 0 && <span className="muted"> / </span>}
            {i < path.length - 1 ? <button className="link" onClick={() => setPath(path.slice(0, i + 1))}>{p.name}</button> : <b>{p.name}</b>}
          </span>
        ))}
      </nav>
      <Msg>{error}</Msg>
      {note && <p className="hint">{note}</p>}
      {!items && !error && <p className="muted">{waiting}</p>}
      {items && (
        <div className="browse">
          {items.length === 0 && <p className="muted">Empty.</p>}
          {vars.length > 1 && (
            <label className="check"><input type="checkbox" checked={vars.every((v) => picked[v.nodeId])}
              onChange={(e) => setPicked((p) => {
                const n = { ...p };
                vars.forEach((v) => (e.target.checked ? (n[v.nodeId] = { ...v, tagName: v.tagName ?? name(v) }) : delete n[v.nodeId]));
                return n;
              })} /> All variables here</label>
          )}
          {items.map((i) => i.nodeClass === "Variable" ? (
            <label key={i.nodeId} className="b-row">
              <input type="checkbox" checked={!!picked[i.nodeId]} onChange={() => toggle({ ...i, tagName: i.tagName ?? name(i) })} />
              <span className="b-name"><b className="ellipsis" title={i.name}>{i.name}</b><small className="mono ellipsis" title={i.nodeId}>{i.nodeId}</small></span>
              <span className={`b-val mono ellipsis${i.good === false ? " bad" : ""}`} title={String(i.value ?? "")}>{i.value == null ? "–" : String(i.value)}</span>
            </label>
          ) : (
            <button key={i.nodeId} className="b-row folder" onClick={() => setPath([...path, { nodeId: i.nodeId, name: i.name }])}>
              <span aria-hidden="true">▸</span>
              <span className="b-name"><b className="ellipsis" title={i.name}>{i.name}</b><small className="mono ellipsis" title={i.nodeId}>{i.nodeId}</small></span>
              <span />
            </button>
          ))}
        </div>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------------ live

function Live({ id }) {
  const { data, error } = usePoll(`agents/${id}/live`, 2_000);
  const [q, setQ] = useState("");
  const n = q.trim().toLowerCase();
  const rows = (data?.values ?? []).filter((v) => !n || `${v.machine} ${v.tag}`.toLowerCase().includes(n));
  return (
    <div className="stack">
      <div className="toolbar">
        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter by machine or tag" aria-label="Filter" />
        {data && <span className="muted small">{data.accepted.toLocaleString()} readings received since Setu started{data.rejected ? ` · ${data.rejected} refused` : ""}</span>}
      </div>
      <Msg>{error}</Msg>
      <Card flush>
        <table>
          <thead><tr><th>Machine</th><th>Tag</th><th className="r">Value</th><th>Quality</th><th>Time</th></tr></thead>
          <tbody>
            {!data && <Empty cols={5}>Loading…</Empty>}
            {data && rows.length === 0 && <Empty cols={5}>{data.values.length ? "No tag matches the filter." : "No readings from this agent since Setu started."}</Empty>}
            {rows.map((v) => (
              <tr key={`${v.machine}/${v.tag}`}>
                <td>{v.machine}</td>
                <td className="mono">{v.tag}</td>
                <td className="r mono nowrap">{v.value == null ? "–" : typeof v.value === "number" ? v.value.toLocaleString(undefined, { maximumFractionDigits: 6 }) : String(v.value)} {v.unit ?? ""}</td>
                <td><Status kind={v.quality === "GOOD" ? "good" : "bad"}>{v.quality}</Status>{v.detail && <small className="ellipsis" title={v.detail}>{v.detail}</small>}</td>
                <td className="nowrap" title={full(v.timestamp)}>{new Date(v.timestamp).toLocaleTimeString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}
