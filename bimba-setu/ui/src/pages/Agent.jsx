import { createContext, useContext, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Empty, FilterSelect, full, Modal, Msg, PageHeader, SortTh, sortRows, Status, useOrgPath, usePoll, useRemembered, useToast, Verdict, when } from "../ui.jsx";

// One site agent (/<org>/agents/<id>). The words, kept apart on purpose:
//   computer  the SCADA computer the agent is installed on: Windows, Linux or Raspberry Pi (Overview)
//   agent     the BIMBA Site Agent service on that computer: it reads the data sources and sends their readings
//   data source  a connection the agent reads (OPC UA server, Modbus / S7 / Logix PLC, MQTT broker, DNP3 / IEC 104
//                outstation, SQL database or historian, OPC DA server on Windows)
//   machine   what pipelines receive: one per data source, or one per device of its server (Machines)
// Testing a connection and browsing tags run on the computer itself: the console sends a request the agent picks up
// within seconds (POST agents/<id>/commands) and shows its answer.

/** the data source types and their settings, from Setu (resources/source-kinds.json): the forms below are built from them */
const KindsContext = createContext([]);
let kindsLoad = null;
function useKindsLoader() {
  const [kinds, setKinds] = useState([]);
  useEffect(() => {
    let live = true;
    kindsLoad ??= api("agents/source-kinds").then((r) => r.kinds ?? []);
    kindsLoad.then((k) => live && setKinds(k)).catch(() => { kindsLoad = null; });
    return () => { live = false; };
  }, []);
  return kinds;
}
const useKinds = () => useContext(KindsContext);
const kindOf = (kinds, id) => kinds.find((k) => k.id === id);
const kindLabel = (kinds, id) => kindOf(kinds, id)?.label ?? id;
/** the computer's platform: types such as OPC DA exist on Windows only */
const platformOf = (agent) => (agent.details?.platform === "linux" ? "linux" : "windows");

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/** version v ("1.7.1") is at least need ("1.8") */
const atLeast = (v, need) => {
  const [a = 0, b = 0, c = 0] = String(v ?? "").split(/[^0-9]+/).filter(Boolean).map(Number);
  const [x = 0, y = 0, z = 0] = String(need).split(".").map(Number);
  return a !== x ? a > x : b !== y ? b > y : c >= z;
};
/** agents from 1.4.1 on can list a server's devices ("scan") */
const canScan = (v) => atLeast(v, "1.4.1");
/** the agent (version v) reads this type of source (some need a newer agent: the type's "needs") */
const reads = (kd, v) => !kd?.needs || atLeast(v, kd.needs);
/** the network scan runs in the agent's collector from 1.8 on */
const canNetScan = (a) => atLeast(a.collector?.edge?.version, "1.8");

/** a setting's value, or its field's default */
const getter = (kd, st) => (key) => st?.[key] ?? kd?.fields.find((f) => f.key === key)?.default;
/** a field's showIf / disabledIf: {field, in | notIn | matches}, or a list of them that must all hold */
function holds(cond, get) {
  if (!cond) return true;
  if (Array.isArray(cond)) return cond.every((c) => holds(c, get));
  const v = get(cond.field) == null ? "" : String(get(cond.field));
  if (cond.in) return cond.in.map(String).includes(v);
  if (cond.notIn) return !cond.notIn.map(String).includes(v);
  if (cond.matches) return new RegExp(cond.matches, "i").test(v);
  return true;
}
/** a text that depends on another setting: {field, values} */
const byField = (by, get) => (by ? by.values?.[String(get(by.field))] : undefined);
/** a field's example for the settings (the SQL query per database and result layout) */
const example = (f, get) => f.examples?.by.reduce((o, k) => o?.[String(get(k))], f.examples.values);
const examplesOf = (f) => {
  const out = [];
  const walk = (o) => (typeof o === "string" ? out.push(o) : Object.values(o ?? {}).forEach(walk));
  walk(f.examples?.values);
  return out;
};
/** a new source's settings: every field's default, examples filled in */
function defaults(kd) {
  const st = {};
  for (const f of kd?.fields ?? []) if (f.default !== undefined) st[f.key] = f.default;
  const get = getter(kd, st);
  for (const f of kd?.fields ?? []) if (f.examples) st[f.key] = example(f, get) ?? "";
  return st;
}
/** one line naming what a source reads, from its type's summary: {key}, {key|default}, {key@} = the option's label,
 * [..] left out when a value in it is empty */
function summary(kd, st) {
  if (!kd) return "";
  const get = getter(kd, st);
  const empty = (k) => get(k) === undefined || get(k) === null || get(k) === "";
  const line = (kd.summary ?? []).find((x) => holds(x.if, get))?.text ?? "";
  return line
    .replace(/\[([^\]]*)\]/g, (_, inner) => ([...inner.matchAll(/\{(\w+)[^}]*\}/g)].some(([, k]) => empty(k)) ? "" : inner))
    .replace(/\{(\w+)(@|\|[^}]*)?\}/g, (_, k, mod) => {
      if (mod === "@") return kd.fields.find((f) => f.key === k)?.options?.find((o) => String(o.value) === String(get(k)))?.label ?? String(get(k) ?? "");
      if (mod) return empty(k) ? mod.slice(1) : String(get(k));
      return empty(k) ? "" : String(get(k));
    });
}
const target = (kinds, s) => summary(kindOf(kinds, s.kind), s.settings);

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

const ACTIVE = ["online", "offline", "pending"];

/**
 * One site agent. A verdict first (is it working, and what to do next), then three tabs in the order data flows:
 * Data sources (what it reads) -> Live data (the machines it sends, with their values) -> Computer (where it runs).
 */
export default function AgentPage({ id }) {
  const at = useOrgPath();
  const { data, error, reload } = usePoll(`agents/${id}`, 10_000);
  const [tab, setTab] = useState("sources");
  const kinds = useKindsLoader();
  const { can } = useSession();

  if (error && !data) {
    return (
      <>
        <p><Link to={at("/agents")}>← Site agents</Link></p>
        <Msg>{error}</Msg>
      </>
    );
  }
  if (!data) return <p className="muted">Loading…</p>;
  const machines = machinesOf(data);
  const where = [data.hostname, data.site, data.os].filter(Boolean).join(" · ");
  return (
    <KindsContext.Provider value={kinds}>
      <p className="crumb"><Link to={at("/agents")}>← Site agents</Link></p>
      <PageHeader title={data.name} subtitle={`On the computer ${where}`}>
        <Status kind={statusKind(data.status)}>{ACTIVE.includes(data.status) ? data.status : "removed"}</Status>
      </PageHeader>
      <AgentVerdict a={data} machines={machines} goTo={setTab} />
      <div className="tabs" role="tablist">
        {[["sources", `Data sources${data.sources.length ? ` (${data.sources.length})` : ""}`],
          ["data", `Live data${machines.length ? ` (${plural(machines.length, "machine")})` : ""}`],
          ["computer", "Computer"]].map(([k, label]) => (
          <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? "on" : ""} onClick={() => setTab(k)}>{label}</button>
        ))}
      </div>
      {tab === "sources" && <Sources a={data} canManage={can("agents.manage") && ACTIVE.includes(data.status)} reload={reload} />}
      {tab === "data" && <LiveData a={data} toSources={() => setTab("sources")} />}
      {tab === "computer" && <Overview a={data} canManage={can("agents.manage")} />}
    </KindsContext.Provider>
  );
}

/** the agent's state in one sentence, and the next thing to do */
function AgentVerdict({ a, machines, goTo }) {
  const { can } = useSession();
  const at = useOrgPath();
  const devices = a.collector?.edge?.devices ?? [];
  const on = a.sources.filter((s) => s.enabled);
  const broken = on.filter((s) => devices.find((d) => d.name === s.id)?.error);
  const link = (label, tab) => <button className="link" onClick={() => goTo(tab)}>{label}</button>;
  if (!ACTIVE.includes(a.status)) {
    return <Verdict kind="bad" title="This agent was removed">It no longer sends data. To add {a.hostname} again, run the installer on it again.</Verdict>;
  }
  if (a.status !== "online") {
    return (
      <Verdict kind="bad" title={a.lastSeen ? `Not heard from since ${when(a.lastSeen)}` : "Not heard from yet"}>
        Check that {a.hostname} is running and can reach Setu, and that the BIMBA Site Agent service is started.
      </Verdict>
    );
  }
  if (!a.collector?.edge) {
    return <Verdict kind="warn" title="Online, but this agent cannot collect data">Agent {a.agentVersion} only reports that it is online. Install agent 1.4 or later on {a.hostname}.</Verdict>;
  }
  if (!on.length) {
    return (
      <Verdict kind="warn" title="Online, nothing to collect yet">
        Next: {link("add a data source", "sources")}, the PLC, server or database the agent should read. Scan the network finds the ones {a.hostname} can reach.
      </Verdict>
    );
  }
  if (broken.length) {
    return (
      <Verdict kind="warn" title={`${broken.length} of ${plural(on.length, "data source")} cannot connect`}>
        {broken.map((s) => s.name).join(", ")}: see {link("Data sources", "sources")} for the reason.
      </Verdict>
    );
  }
  return (
    <Verdict kind="good" title={`Collecting from ${plural(on.length, "data source")}`}>
      {plural(machines.length, "machine")} send their data to Setu: see {link("Live data", "data")}.
      {can("gateway.view") && <> Choose where it goes in <Link to={at("/pipelines")}>Pipelines</Link>.</>}
    </Verdict>
  );
}

// ------------------------------------------------------------------ computer & agent

function Overview({ a, canManage }) {
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
      {canManage && ACTIVE.includes(a.status) && <RemoveAgent a={a} />}
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

/** take the agent out of the organization (Setu revokes its registration and certificate) */
function RemoveAgent({ a }) {
  const { org } = useSession();
  const at = useOrgPath();
  const go = useNavigate();
  const toast = useToast();
  const [asking, setAsking] = useState(false);
  return (
    <Card title="Remove this agent" subtitle={`The agent on ${a.hostname} stops sending data and can no longer sign in to Setu. Its data sources and their machines stop. To add the computer again, run the installer on it again.`}
          action={<button className="btn danger" onClick={() => setAsking(true)}>Remove agent…</button>}>
      {asking && (
        <Confirm title={`Remove ${a.name}?`} danger confirm="Remove agent"
                 subtitle={`${org.name}. The agent on ${a.hostname} stops, and its machines (${plural(machinesOf(a).length, "machine")}) stop sending data.`}
                 action={() => api(`agents/${a.id}/revoke`, { method: "POST" })}
                 onDone={() => { toast(`${a.name} removed.`); go(at("/agents")); }}
                 onClose={() => setAsking(false)} />
      )}
    </Card>
  );
}

// ------------------------------------------------------------------ live data

/**
 * What the agent sends: each machine (what pipelines receive), the data source it comes from, its state, and the last
 * value of each of its tags. Replaces the separate Machines and Live values tabs.
 */
function LiveData({ a, toSources }) {
  const kinds = useKinds();
  const { data, error } = usePoll(`agents/${a.id}/live`, 2_000);
  const [q, setQ] = useState("");
  const [f, setF] = useRemembered("live.filter", { source: "", quality: "" });
  const [order, setOrder] = useRemembered("live.order", { key: "name" });
  const [tagSort, setTagSort] = useRemembered("live.tags", { key: "tag", dir: 1 });
  const n = q.trim().toLowerCase();
  const devices = a.collector?.edge?.devices ?? [];
  const online = a.status === "online";
  const byMachine = new Map();
  for (const v of data?.values ?? []) {
    if (!byMachine.has(v.machine)) byMachine.set(v.machine, []);
    byMachine.get(v.machine).push(v);
  }
  // the tags shown for a machine: the search (machine name matches = all its tags), the quality filter, the tag sort
  const shownTags = (name) => sortRows((byMachine.get(name) ?? []).filter((v) => (!n || name.toLowerCase().includes(n) || v.tag.toLowerCase().includes(n))
    && (!f.quality || v.quality !== "GOOD")), tagSort, {
    tag: (v) => v.tag,
    value: (v) => (typeof v.value === "number" ? v.value : String(v.value ?? "")),
    quality: (v) => (v.quality === "GOOD" ? 1 : 0),
    time: (v) => Date.parse(v.timestamp) || 0,
  });
  const troubled = (m) => sourceState(m.source, devices.find((d) => d.name === m.source.id), kindOf(kinds, m.source.kind), a.agentVersion) === "problem"
    || (byMachine.get(m.name) ?? []).some((v) => v.quality !== "GOOD");
  const all = machinesOf(a);
  const filtered = all.filter((m) => (!f.source || m.source.id === f.source)
    && (!n || `${m.name} ${(byMachine.get(m.name) ?? []).map((v) => v.tag).join(" ")}`.toLowerCase().includes(n))
    && (!f.quality || troubled(m)));
  const rows = order.key === "problems"
    ? sortRows(filtered, { key: "p", dir: 1 }, { p: (m) => -(byMachine.get(m.name) ?? []).filter((v) => v.quality !== "GOOD").length - (troubled(m) ? 1000 : 0) })
    : sortRows(filtered, { key: order.key, dir: order.key === "za" ? -1 : 1 }, { name: (m) => m.name, za: (m) => m.name, source: (m) => `${m.source.name} ${m.name}` });
  const filtering = n || f.source || f.quality;
  if (!a.sources.length) {
    return <Card><p className="muted">Nothing is collected yet. Machines appear here when you <button className="link" onClick={toSources}>add a data source</button>.</p></Card>;
  }
  return (
    <div className="stack">
      <div className="toolbar">
        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search machine or tag" aria-label="Search machines and tags" />
        {a.sources.length > 1 && <FilterSelect label="Data source" value={f.source} onChange={(v) => setF({ ...f, source: v })}
                                               options={a.sources.map((s) => [s.id, s.name])} />}
        <FilterSelect label="Quality" value={f.quality} onChange={(v) => setF({ ...f, quality: v })} all="All tags" options={[["bad", "Not good only"]]} />
        <label className="filter"><span>Sort</span>
          <select value={order.key} onChange={(e) => setOrder({ key: e.target.value })} aria-label="Sort machines">
            <option value="name">Machine A–Z</option>
            <option value="za">Machine Z–A</option>
            <option value="problems">Problems first</option>
            <option value="source">Data source</option>
          </select>
        </label>
        {filtering && <button className="btn ghost sm" onClick={() => { setQ(""); setF({ source: "", quality: "" }); }}>Clear filters</button>}
        <span className="muted small count">{filtering ? `${rows.length} of ${plural(all.length, "machine")} · ` : ""}{data ? `${data.accepted.toLocaleString()} readings since Setu started${data.rejected ? ` · ${data.rejected} refused` : ""}` : ""}</span>
      </div>
      <Msg>{error}</Msg>
      {rows.length === 0 && <p className="muted">{f.quality ? "Every tag reads good." : "No machine or tag matches the filters."}</p>}
      {rows.map(({ name, tags, source: s }) => {
        const values = shownTags(name);
        const sent = byMachine.get(name) ?? [];                   // every tag it sent, whatever the filters show
        const good = sent.filter((v) => v.quality === "GOOD").length;
        return (
          <Card flush key={`${s.id}/${name}`} className={s.enabled ? "" : "off"}
                title={<span className="ellipsis" title={name}>{name}</span>}
                subtitle={<span className="ellipsis" title={`${s.name} · ${target(kinds, s)}`}>from {s.name} · {kindLabel(kinds, s.kind)} · {target(kinds, s)}</span>}
                action={<div className="m-state">{sent.length && s.enabled && !devices.find((d) => d.name === s.id)?.error
                  ? <><Status kind={good === sent.length ? "good" : "warn"}>{good}/{sent.length} good</Status>
                      {tags > sent.length && <small className="muted">{plural(tags - sent.length, "more tag")} without a change since Setu started</small>}</>
                  : <SourceState s={s} dev={devices.find((d) => d.name === s.id)} online={online} version={a.agentVersion} />}</div>}>
            {values.length === 0 ? <p className="muted pad">{sent.length ? "No tag matches the filters." : "No reading yet."}</p> : (
              <table>
                <thead><tr>
                  <SortTh k="tag" sort={tagSort} setSort={setTagSort}>Tag</SortTh>
                  <SortTh k="value" sort={tagSort} setSort={setTagSort} className="r">Value</SortTh>
                  <SortTh k="quality" sort={tagSort} setSort={setTagSort}>Quality</SortTh>
                  <SortTh k="time" sort={tagSort} setSort={setTagSort}>Time</SortTh>
                </tr></thead>
                <tbody>
                  {values.map((v) => (
                    <tr key={v.tag}>
                      <td className="mono" style={{ minWidth: 0 }}><span className="ellipsis" title={v.tag}>{v.tag}</span></td>
                      <td className="r mono nowrap">{v.value == null ? "–" : typeof v.value === "number" ? v.value.toLocaleString(undefined, { maximumFractionDigits: 6 }) : String(v.value)} {v.unit ?? ""}</td>
                      <td><Status kind={v.quality === "GOOD" ? "good" : "bad"}>{v.quality}</Status>{v.detail && <small className="ellipsis" title={v.detail}>{v.detail}</small>}</td>
                      <td className="nowrap" title={full(v.timestamp)}>{new Date(v.timestamp).toLocaleTimeString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        );
      })}
    </div>
  );
}

// ------------------------------------------------------------------ data sources

function Sources({ a, canManage, reload }) {
  const toast = useToast();
  const kinds = useKinds();
  const [editing, setEditing] = useState(null);       // source | {new, ...preset}
  const [removing, setRemoving] = useState(null);
  const [acting, setActing] = useState(null);         // source id being checked, or source id + device being added
  const [notice, setNotice] = useState(null);
  const [scanning, setScanning] = useState(false);    // the Scan the network dialog
  const [net, setNet] = useState(null);               // its last answer
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
  const inUse = new Set(a.sources.map((s) => `${s.kind}|${target(kinds, s)}`));
  const scanTitle = !online ? "The agent is offline." : !canNetScan(a) ? "Scanning the network needs agent 1.8 or later." : undefined;
  const [q, setQ] = useState("");
  const [f, setF] = useRemembered("sources.filter", { kind: "", state: "" });
  const [sort, setSort] = useRemembered("sources.sort", { key: "name", dir: 1 });
  const nq = q.trim().toLowerCase();
  const stateOf = (s) => sourceState(s, devices.find((d) => d.name === s.id), kindOf(kinds, s.kind), a.agentVersion);
  const usedKinds = [...new Set(a.sources.map((s) => s.kind))];
  const list = sortRows(a.sources.filter((s) => (!nq || `${s.name} ${target(kinds, s)} ${s.machines.map((m) => m.name).join(" ")}`.toLowerCase().includes(nq))
    && (!f.kind || s.kind === f.kind) && (!f.state || stateOf(s) === f.state)), sort, {
    name: (s) => s.name,
    source: (s) => `${kindLabel(kinds, s.kind)} ${target(kinds, s)}`,
    tags: (s) => s.tags.length,
    state: (s) => STATE_ORDER.indexOf(stateOf(s)),
  });
  const filtering = nq || f.kind || f.state;

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
      <Card flush title="Data sources" subtitle={`The PLCs, servers and databases the agent reads on ${a.hostname}. Each sends its data as one machine (or one per device of its server). Only changes are sent.`}
            action={canManage && (
              <div className="row-acts">
                <button className="btn ghost" disabled={!kinds.length || !!scanTitle} title={scanTitle} onClick={() => setScanning(true)}>Scan the network</button>
                <button className="btn" disabled={!kinds.length}
                        onClick={() => setEditing({ kind: "opcua", settings: defaults(kindOf(kinds, "opcua")), tags: [], pollMs: 1000, enabled: true, name: "" })}>Add data source</button>
              </div>
            )}>
        {a.sources.length > 1 && (
          <div className="toolbar card-tools">
            <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search name, address or machine" aria-label="Search data sources" />
            {usedKinds.length > 1 && <FilterSelect label="Type" value={f.kind} onChange={(v) => setF({ ...f, kind: v })}
                                                   options={usedKinds.map((k) => [k, kindLabel(kinds, k)]).sort((x, y) => x[1].localeCompare(y[1]))} />}
            <FilterSelect label="Status" value={f.state} onChange={(v) => setF({ ...f, state: v })}
                          options={STATE_ORDER.map((x) => [x, STATE_LABEL[x]])} />
            {filtering && <button className="btn ghost sm" onClick={() => { setQ(""); setF({ kind: "", state: "" }); }}>Clear filters</button>}
            {filtering && <span className="muted small count">{list.length} of {a.sources.length}</span>}
          </div>
        )}
        <table>
          <thead><tr>
            <SortTh k="name" sort={sort} setSort={setSort}>Name</SortTh>
            <SortTh k="source" sort={sort} setSort={setSort}>Source</SortTh>
            <SortTh k="tags" sort={sort} setSort={setSort}>Tags</SortTh>
            <SortTh k="state" sort={sort} setSort={setSort}>Status</SortTh>
            <th />
          </tr></thead>
          <tbody>
            {a.sources.length > 0 && list.length === 0 && <Empty cols={5}>No data source matches the filters.</Empty>}
            {a.sources.length === 0 && <Empty cols={5}>No data sources yet.{canManage ? suggestions.length ? " Add one of the interfaces found below." : " Add one, or scan the network for them." : ""}</Empty>}
            {list.map((s) => {
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
                    {kindLabel(kinds, s.kind)}
                    <small className="mono ellipsis" title={target(kinds, s)}>{target(kinds, s)}</small>
                  </td>
                  <td>{s.tags.length}</td>
                  <td><SourceState s={s} dev={dev} online={online} version={a.agentVersion} />{s.scan && <ScanState scan={s.scan} busy={acting === s.id} />}</td>
                  <td className="r">
                    {canManage && (
                      <div className="row-acts">
                        {s.scan && online && canScan(a.agentVersion) && (
                          <button className="btn ghost sm" disabled={!!acting} onClick={() => checkNow(s)}>{acting === s.id ? "Checking…" : "Check for devices"}</button>
                        )}
                        <button className="btn ghost sm" disabled={!kinds.length} onClick={() => setEditing(s)}>Edit</button>
                        <button className="btn ghost sm" disabled={!kinds.length} title="Add a new data source with the same settings" onClick={() => setEditing(duplicate(s, a.sources))}>Duplicate</button>
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
      {canManage && suggestions.length > 0 && (
        <Card title={`Found on ${a.hostname}`} subtitle="SCADA interfaces the agent found on the computer. Add one to start collecting its tags.">
          <div className="sugg">
            {suggestions.map((s) => (
              <div key={s.endpoint ?? s.progId} className="sugg-i">
                <div>
                  <b className="ellipsis" title={s.product ?? s.name}>{s.product ?? s.name}</b>
                  <small className="mono ellipsis" title={s.endpoint ?? s.progId}>{kindLabel(kinds, s.kind)} · {s.endpoint ?? s.progId}</small>
                </div>
                <button className="btn ghost sm" disabled={!kinds.length} onClick={() => setEditing(preset(s, a, kinds))}>Add</button>
              </div>
            ))}
          </div>
        </Card>
      )}
      {canManage && net && (
        <Card title="Found on the network"
              subtitle={`${plural(net.found.length, "answer")} from ${net.addresses.toLocaleString()} address${net.addresses === 1 ? "" : "es"}`
                + `${net.networks?.length ? ` on ${net.networks.map((n) => n.network).join(", ")}` : ""} in ${Math.max(1, Math.round(net.ms / 1000))} s`
                + `${net.truncated ? " (stopped at 1,024 addresses)" : ""}${net.complete === false ? " (time ran out before every address was tried)" : ""}.`}
              action={<div className="row-acts">
                <button className="btn ghost sm" disabled={!!scanTitle} onClick={() => setScanning(true)}>Scan again</button>
                <button className="btn ghost sm" onClick={() => setNet(null)}>Clear</button>
              </div>}>
          {net.found.length === 0 ? <p className="muted">Nothing answered on the standard ports of these types.</p> : (
            <div className="sugg">
              {net.found.map((x) => {
                const kd = kindOf(kinds, x.kind);
                const draft = fromScan(x, a, kinds);
                const taken = inUse.has(`${x.kind}|${summary(kd, draft.settings)}`);
                return (
                  <div key={`${x.kind}|${x.host}|${x.port}`} className="sugg-i">
                    <div>
                      <b className="ellipsis" title={x.product || kd?.label}>{x.product || kd?.label}</b>
                      <small className="mono ellipsis" title={`${x.host}:${x.port}`}>{kd?.label} · {x.host}:{x.port}</small>
                      <small className={`ellipsis${x.confirmed ? "" : " warnc"}`} title={x.detail}>{x.detail}</small>
                    </div>
                    {taken ? <small className="muted">in use</small>
                      : <button className="btn ghost sm" onClick={() => setEditing(draft)}>Add</button>}
                  </div>
                );
              })}
            </div>
          )}
          {net.unresolved?.length > 0 && <p className="hint">Host names the computer could not resolve: {net.unresolved.join(", ")}.</p>}
        </Card>
      )}
      {scanning && <ScanDialog a={a} onClose={() => setScanning(false)} onDone={(r) => { setScanning(false); setNet(r); }} />}
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

/**
 * Scan the network: the agent tries the standard ports of the chosen types on its own networks (and the addresses
 * given) and asks each open port who it is, read only. What answers can be added as a data source, pre-filled.
 */
function ScanDialog({ a, onClose, onDone }) {
  const kinds = useKinds();
  const scannable = kinds.filter((k) => k.scan && (!k.platforms || k.platforms.includes(platformOf(a))));
  const [picked, setPicked] = useState(() => new Set(scannable.map((k) => k.id)));
  const [own, setOwn] = useState(true);
  const [extra, setExtra] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const toggle = (id) => setPicked((p) => {
    const n = new Set(p);
    if (n.has(id)) n.delete(id); else n.add(id);
    return n;
  });
  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      const subnets = extra.split(/[\s,;]+/).filter(Boolean);
      onDone(await ask(a.id, { kind: "netscan", protocols: [...picked], subnets, ownNetworks: own }));
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };
  return (
    <Modal title="Scan the network" subtitle={`The agent on ${a.hostname} looks for data sources it can reach.`} onClose={onClose}
           footer={<>
             <button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy || !picked.size || (!own && !extra.trim())} onClick={start}>
               {busy ? "Scanning… (up to 30 s)" : "Start the scan"}
             </button>
           </>}>
      <Msg>{error}</Msg>
      <div className="field">
        <span className="label">Look for</span>
        <div className="checks">
          {scannable.map((k) => (
            <label key={k.id} className="check">
              <input type="checkbox" checked={picked.has(k.id)} onChange={() => toggle(k.id)} />
              <span className="ellipsis" title={`${k.label}: port ${k.scan.ports.join(", ")}`}>{k.label} <small className="muted">{k.scan.ports.join(", ")}</small></span>
            </label>
          ))}
        </div>
      </div>
      <label className="check">
        <input type="checkbox" checked={own} onChange={(e) => setOwn(e.target.checked)} />
        <span>The computer's own networks <small className="muted">each up to 254 addresses around {a.hostname}'s address, and {a.hostname} itself</small></span>
      </label>
      <div className="field">
        <label htmlFor="ns-more">Also scan (optional)</label>
        <input id="ns-more" type="text" className="mono" value={extra} onChange={(e) => setExtra(e.target.value)} placeholder="192.168.20.0/24, 10.0.0.5, plc7:5020" />
        <span className="hint">Networks from /22 to /32, addresses or host names, seen from {a.hostname}. Add :port for a device on
          another port than its type's usual one (plc7:5020): that port is asked as every chosen type. At most 1,024 addresses in all.</span>
      </div>
      <Msg kind="warn">The agent makes one short connection to the standard ports of every address, then sends each open port a
        read-only request asking what it is. Nothing is written. Some sites forbid scans or raise an alarm on them: check
        with the network or OT team first.</Msg>
    </Modal>
  );
}

/** a data source's state for the Status filter and sort: problems first */
const STATE_ORDER = ["problem", "starting", "collecting", "paused"];
const STATE_LABEL = { problem: "Needs attention", starting: "Starting", collecting: "Collecting", paused: "Paused" };
function sourceState(s, dev, kd, version) {
  if (!s.enabled) return "paused";
  if (!reads(kd, version) || dev?.error) return "problem";
  return dev ? "collecting" : "starting";
}

function SourceState({ s, dev, online, version }) {
  const kd = kindOf(useKinds(), s.kind);
  if (!s.enabled) return <Status>paused</Status>;
  if (!reads(kd, version)) return <><Status kind="warn">waiting</Status><small>{kd.label} needs agent {kd.needs} or later</small></>;
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

/** an interface the agent found on its computer, as a new source */
function preset(found, a, kinds) {
  const base = (found.product ?? found.name ?? "SCADA").replace(/[^A-Za-z0-9 ._-]+/g, " ").trim();
  const name = `${a.hostname}-${base}`.replace(/\s+/g, "-").slice(0, 120);
  const kind = found.kind === "opcda" ? "opcda" : "opcua";
  const kd = kindOf(kinds, kind);
  // no vendor defaults (accounts, folders): the person adding the source enters the server's own sign-in
  const settings = kind === "opcda" ? { progId: found.progId, host: "" } : { endpoint: found.endpoint, policy: "Auto", auth: "Anonymous" };
  return { kind, name, settings: { ...defaults(kd), ...settings }, tags: [], pollMs: kd?.pollMs ?? 1000, enabled: true };
}

/** a data source the network scan found, as a new source: its type's defaults and what the device told */
function fromScan(found, a, kinds) {
  const kd = kindOf(kinds, found.kind);
  const names = new Set(a.sources.map((s) => s.name.toLowerCase()));
  const where = found.host === "localhost" ? a.hostname : found.host;
  const base = machineName(`${found.product || kd?.label || found.kind} ${where}`.replace(/\s+/g, "-")).slice(0, 110);
  let name = base;
  for (let i = 2; names.has(name.toLowerCase()); i++) name = `${base}-${i}`;
  return { kind: found.kind, name, settings: { ...defaults(kd), ...found.settings }, tags: [], pollMs: kd?.pollMs ?? 1000, enabled: true };
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

/**
 * Add or change a data source. The type's fields come from Setu (source-kinds.json): the main ones first, the rest under
 * Advanced settings. Only the OPC UA devices list (one machine per device) and the tag table are special.
 */
function SourceEditor({ agent, source, onClose, onSaved }) {
  const kinds = useKinds();
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
  const [errorField, setErrorField] = useState(null);
  const [test, setTest] = useState(null);         // {busy} | {ok, result} | {error}
  const [browse, setBrowse] = useState(false);
  const [perDevice, setPerDevice] = useState(() => (source.kind ?? "opcua") === "opcua" && (!!source.perDevice || !!source.settings?.machinesFrom));
  const [devs, setDevs] = useState(() => initDevs(source));
  const [scan, setScan] = useState(null);         // {busy} | {count, truncated}
  const kd = kindOf(kinds, f.kind);
  const per = !!kd?.perDevice && perDevice;
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const st = f.settings;
  const get = getter(kd, st);
  const edge = agent.collector?.edge ?? {};
  const sourceForCommand = () => ({ kind: f.kind, settings: { ...st } });

  /** one setting changed: its option may set others (S7 CPU -> slot), examples follow (SQL query), a duplicate forgets its pin */
  const change = (key, value, also = {}) => setF((x) => {
    const field = kd.fields.find((g) => g.key === key);
    const s = { ...x.settings, [key]: value, ...also };
    if (source.copyOf) for (const k of field?.clearsOnCopy ?? []) s[k] = "";
    for (const g of kd.fields) {
      const cur = String(x.settings[g.key] ?? "");
      if (g.examples?.by.includes(key) && (!cur.trim() || examplesOf(g).includes(cur))) s[g.key] = example(g, getter(kd, s)) ?? cur;
    }
    return { ...x, settings: s };
  });
  const setS = (k, v) => change(k, v);

  const switchKind = (k) => {
    const nk = kindOf(kinds, k);
    setF((x) => ({ ...x, kind: k, tags: [], pollMs: nk?.pollMs ?? x.pollMs, settings: defaults(nk) }));
    setTest(null);
    setError(null);
    setErrorField(null);
  };

  const runTest = async () => {
    setTest({ busy: true });
    try {
      const body = { kind: "test", source: sourceForCommand(), sourceId: source.id, passwordFrom: source.copyOf };
      if (kd.test?.readsTags) body.tags = f.tags.slice(0, 20);
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
    setErrorField(null);
    const body = { ...f, settings: { ...st } };
    if (per) {
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
      setErrorField(e.code ?? null);
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

  const types = kinds.filter((k) => !k.platforms || k.platforms.includes(platformOf(agent)) || k.id === f.kind);
  return (
    <Modal wide title={source.copyOf ? `Duplicate ${source.copyName}` : isNew ? "Add data source" : `Edit ${source.name}`}
           subtitle={`The site agent reads it on the computer ${agent.hostname} and sends it to Setu.`}
           onClose={onClose}
           footer={<>
             <button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy || !kd} onClick={save}>{busy ? "Saving…" : "Save"}</button>
           </>}>
      <Msg>{error}</Msg>
      <div className="grid2">
        <div className="field">
          <label htmlFor="src-name">{per ? "Name" : "Machine name"}</label>
          <input type="text" id="src-name" value={f.name} maxLength={120} onChange={(e) => set("name", e.target.value)} placeholder={per ? "e.g. LINE-1-SCADA" : "e.g. LINE-1-HMI"}
                 aria-invalid={errorField === "name" || undefined} />
          <span className="hint">{per ? "Names this connection. Each device below reports as its own machine." : "Pipelines receive this source's readings as this machine. Unique on Setu."}</span>
        </div>
        <div className="field">
          <label htmlFor="src-kind">Type</label>
          <select id="src-kind" value={f.kind} disabled={!isNew} onChange={(e) => switchKind(e.target.value)}>
            {types.map((k) => <option key={k.id} value={k.id}>{k.menu ?? k.label}</option>)}
          </select>
        </div>
      </div>

      {!kd ? <p className="muted">Loading the settings…</p> : (
        <Fields key={f.kind} kd={kd} st={st} get={get} change={change} agent={agent} source={source} errorField={errorField} />
      )}

      {kd && !reads(kd, agent.agentVersion) && (
        <Msg kind="warn">This agent ({agent.agentVersion}) does not read {kd.label} yet: update it to {kd.needs} or later.</Msg>
      )}

      {kd?.perDevice && (
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

      {per && (
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

      {kd && (
        <div className="test-row">
          <button className="btn ghost" disabled={test?.busy || scan?.busy || agent.status !== "online"} onClick={runTest}>
            {test?.busy ? "Asking the agent…" : kd.test?.readsTags && f.tags.length ? "Test connection (read tags)" : kd.test?.button ?? "Test connection"}
          </button>
          {per && (
            <button className="btn ghost" disabled={test?.busy || scan?.busy || agent.status !== "online" || !canScan(agent.agentVersion)} onClick={findDevices}>
              {scan?.busy ? "Finding devices…" : devs.length ? "Find devices again" : "Find devices"}
            </button>
          )}
          {agent.status !== "online" && <span className="hint">The agent is offline.</span>}
          {agent.status === "online" && per && !canScan(agent.agentVersion) && (
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
      {test?.ok && <TestResult r={test.result} st={st} onTrust={(sha) => setS("serverCert", sha)} />}

      {per && scan?.count != null && (
        <p className="hint">{scan.count} device{scan.count === 1 ? "" : "s"} on the server{scan.truncated ? " (stopped at 5,000 tags: untick the devices you do not need)" : ""}.</p>
      )}
      {kd && (per
        ? <DeviceList devs={devs} onChange={setDevs} />
        : <TagTable kd={kd} get={get} tags={f.tags} onChange={(tags) => set("tags", tags)}
                    onBrowse={kd.browse && reads(kd, agent.agentVersion) && agent.status === "online" ? () => setBrowse(true) : null} />)}
      {browse && <Browser agent={agent} kd={kd} source={sourceForCommand()} sourceId={source.id} passwordFrom={source.copyOf} onClose={() => setBrowse(false)}
                          onAdd={(list) => { addTags(list); setBrowse(false); }} />}
    </Modal>
  );
}

/** a type's settings: the main ones, then the rest under Advanced settings (open when one of them is set or wrong) */
function Fields({ kd, st, get, change, agent, source, errorField }) {
  const shown = kd.fields.filter((f) => holds(f.showIf, get));
  const main = shown.filter((f) => !f.advanced);
  const adv = shown.filter((f) => f.advanced);
  const changed = (f) => f.type !== "note" && st[f.key] != null && st[f.key] !== "" && String(st[f.key]) !== String(f.default ?? "");
  const [open, setOpen] = useState(() => adv.some(changed));
  const wrongInside = adv.some((f) => f.key === errorField);
  useEffect(() => {
    if (wrongInside) setOpen(true);
  }, [wrongInside, errorField]);
  const n = adv.filter(changed).length;
  const field = (f) => <Field key={f.key} f={f} st={st} get={get} change={change} agent={agent} source={source} invalid={f.key === errorField} />;
  return (
    <>
      {main.length > 0 && <div className="grid2">{main.map(field)}</div>}
      {adv.length > 0 && (
        <details className="adv" open={open} onToggle={(e) => setOpen(e.currentTarget.open)}>
          <summary>Advanced settings{n ? ` (${n} changed)` : ""}</summary>
          <div className="grid2">{adv.map(field)}</div>
        </details>
      )}
    </>
  );
}

/** one setting, drawn from its description (see source-kinds.json for the keys) */
function Field({ f, st, get, change, agent, source, invalid }) {
  const id = `src-${f.key}`;
  const words = (t) => (t == null ? t : String(t).replaceAll("{computer}", agent.hostname));
  const value = get(f.key) ?? "";
  const disabled = f.disabledIf ? holds(f.disabledIf, get) : false;
  const option = f.type === "select" ? f.options?.find((o) => String(o.value) === String(value)) : null;
  const hint = words(byField(f.hintBy, get) ?? option?.hint ?? f.hint);
  const placeholder = words(byField(f.placeholderBy, get) ?? f.placeholder);
  const span = f.span ? " span2" : "";
  const bad = invalid || undefined;

  if (f.type === "note") {
    return f.noteKind === "warn" ? <div className="span2"><Msg kind="warn">{words(f.text)}</Msg></div> : <p className="hint span2">{words(f.text)}</p>;
  }
  if (f.type === "checkbox") {
    return (
      <label className={`check${span}`} style={f.span ? undefined : { alignSelf: "end" }}>
        <input type="checkbox" id={id} checked={!!value} aria-invalid={bad} onChange={(e) => change(f.key, e.target.checked)} />
        <span>{f.label}{hint && <small className="muted"> {hint}</small>}</span>
      </label>
    );
  }
  if (f.type === "certPin") {
    return (
      <div className={`field${span}`}>
        <span className="label">{f.label}</span>
        {st[f.key]
          ? <span className="small">Only the trusted certificate <span className="mono" title={fp(st[f.key])}>{short(st[f.key])}</span> is accepted.{" "}
              <button className="link" onClick={() => change(f.key, "")}>Forget it</button></span>
          : <span className="hint">Not pinned yet: Test connection shows the server's certificate, then you can trust it.</span>}
      </div>
    );
  }
  if (f.type === "choice") {
    return (
      <div className={`field${span}`}>
        <span className="label">{f.label}</span>
        <div className={`seg${f.options.length === 2 ? " two" : f.options.length === 3 ? " three" : ""}`} role="radiogroup" aria-label={f.label}>
          {f.options.map((o) => (
            <label key={String(o.value)}>
              <input type="radio" name={id} checked={String(value) === String(o.value)} onChange={() => change(f.key, o.value, o.set)} />
              <b>{o.label}</b>{o.hint && <small>{words(o.hint)}</small>}
            </label>
          ))}
        </div>
        {hint && <span className="hint">{hint}</span>}
      </div>
    );
  }
  let control;
  if (f.type === "select") {
    control = (
      <select id={id} value={String(value)} disabled={disabled} aria-invalid={bad}
              onChange={(e) => {
                const o = f.options.find((x) => String(x.value) === e.target.value);
                change(f.key, o.value, o.set);
              }}>
        {f.options.map((o) => <option key={String(o.value)} value={String(o.value)}>{o.label}</option>)}
      </select>
    );
  } else if (f.type === "textarea") {
    control = <textarea id={id} className={f.mono ? "mono" : undefined} rows={f.rows ?? 3} value={value} placeholder={placeholder}
                        spellCheck="false" aria-invalid={bad} onChange={(e) => change(f.key, e.target.value)} />;
  } else if (f.type === "password") {
    control = <input id={id} type="password" value={st[f.key] ?? ""} autoComplete="new-password" aria-invalid={bad}
                     placeholder={source.hasPassword ? (source.copyOf ? `same as ${source.copyName}` : "unchanged") : ""}
                     onChange={(e) => change(f.key, e.target.value)} />;
  } else if (f.type === "number") {
    control = <input id={id} type="number" min={f.min} max={f.max} value={value} placeholder={placeholder} disabled={disabled} aria-invalid={bad}
                     onChange={(e) => change(f.key, e.target.value === "" ? "" : Number(e.target.value))} />;
  } else {
    const suggestions = f.suggest ? agent.details?.dataSources?.[f.suggest] ?? [] : [];
    control = (
      <>
        <input type="text" id={id} className={f.mono ? "mono" : undefined} value={value} maxLength={f.maxLength} placeholder={placeholder}
               disabled={disabled} aria-invalid={bad} autoComplete="off" list={f.suggest ? `${id}-list` : undefined}
               onChange={(e) => change(f.key, e.target.value)} />
        {f.suggest && (
          <datalist id={`${id}-list`}>
            {suggestions.map((s) => <option key={s.progId ?? s.endpoint} value={s.progId ?? s.endpoint}>{s.name ?? s.product}</option>)}
          </datalist>
        )}
      </>
    );
  }
  return (
    <div className={`field${span}`}>
      <label htmlFor={id}>{f.label}</label>
      {control}
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

/** the agent's answer to Test connection, whatever the type: what it reached, and the tags it read */
function TestResult({ r, st, onTrust }) {
  const what = [r.product || r.target || "the server", r.version].filter(Boolean).join(" ");
  const extra = [r.security, r.state, r.pdu ? `PDU ${r.pdu} bytes` : null].filter(Boolean).join(", ");
  return (
    <div className="msg good" role="status">
      Connected to {what}{extra ? ` (${extra})` : ""}
      {r.rows != null ? `: the query returned ${r.rows} row${r.rows === 1 ? "" : "s"} (${(r.columns ?? []).join(", ")}).` : r.points ? `: ${r.points}.` : "."}
      {r.note && <span> {r.note}</span>}
      {(r.values ?? []).length > 0 && (
        <span> {r.values.map((v) => `${v.name} = ${v.good ? v.value : `${String(v.quality ?? "bad").toLowerCase()} (${v.detail})`}`).join(" · ")}</span>
      )}
      {r.serverCertSha256 && st.serverCert !== r.serverCertSha256 && (
        <div className="trust">
          <span>Server certificate <span className="mono" title={fp(r.serverCertSha256)}>{short(r.serverCertSha256)}</span></span>
          <button className="btn ghost sm" onClick={() => onTrust(r.serverCertSha256)}>Trust only this certificate</button>
        </div>
      )}
    </div>
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

function TagTable({ kd, get, tags, onChange, onBrowse }) {
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
  // what a tag's address is and how to add one, per source type (MQTT: per message format, SQL: per result layout)
  const tg = kd.tags ?? {};
  const head = byField(tg.addressBy, get) ?? tg.address ?? "Address";
  const hint = byField(tg.hintBy, get) ?? tg.hint ?? "Add the tags to collect.";
  const add = () => onChange([...tags, { name: "", address: tg.newAddress ?? "", unit: "" }]);
  return (
    <div className="field">
      <div className="tag-h">
        <span className="label">Tags ({tags.length})</span>
        <div className="row-acts">
          {onBrowse && <button className="btn ghost sm" onClick={onBrowse}>{kd.browse?.button ?? "Browse…"}</button>}
          <button className="btn ghost sm" onClick={add}>Add a tag</button>
        </div>
      </div>
      {tags.length === 0 ? <p className="hint">{hint}</p> : (
        <div className="tags">
          <div className="tag-row head"><span>Name</span><span>{head}</span><span>Unit</span><span /></div>
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

/** A server's address space (OPC UA, OPC DA), a broker's topics, an outstation's points or a query's result, browsed by
 * the agent: open folders, tick variables, add them as tags. */
function Browser({ agent, kd, source, sourceId, passwordFrom, onClose, onAdd }) {
  const { root, title, waiting } = kd.browse;
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
    <Modal wide title={title} subtitle={summary(kd, source.settings)} onClose={onClose}
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
