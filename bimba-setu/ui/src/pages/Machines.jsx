import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api.js";
import { Card, Empty, full, Modal, Msg, PageHeader, Status, useOrgPath, usePoll, useRecordAddress, useToast, when } from "../ui.jsx";
import { SafetyRequests } from "./SafetyChanges.jsx";

// Machines: every machine the organization's site agents collect (those with writable tags first). A machine's page
// (/<org>/machines/<machine>) shows where it comes from (its site agent and data source, with links), which pipelines
// deliver it (links), its live data, and its machine commands: set a writable tag, approve, the history.
// Commands: only tags marked writable on a site agent's data source (Site agents → data source → tag ⋯), within their
// limits; the site agent writes, reads back and answers. A tag can need a second person's approval. Anyone who may send
// or approve can stop commands (organization, site or site agent); only Resume needs the manage permission.
// Server side: agents/MachineCommands.java (web/MachinesController, web/MachineCommandsController).

const OPEN = ["approval", "pending", "sent"];

/** a value as people read it: On / Off, numbers without float noise, "–" for none */
export function fmt(v, unit) {
  if (v === null || v === undefined || v === "") return "–";
  if (typeof v === "boolean") return v ? "On" : "Off";
  const n = typeof v === "number" ? v.toLocaleString(undefined, { maximumFractionDigits: 6 }) : String(v);
  return unit ? `${n} ${unit}` : n;
}

/** the state of a command, as a status line */
function stateOf(c) {
  const v = c.result?.verified;
  switch (c.state) {
    case "approval": return ["warn", "Waiting for approval"];
    case "pending": return ["", "On its way to the agent"];
    case "sent": return ["", "Being written"];
    case "done": return v === true ? ["good", "Done · read back"] : v === false ? ["warn", "Done · not confirmed"] : ["good", "Sent"];
    case "failed": return ["bad", c.result?.refusedBy === "computer" ? "Refused by the computer" : "Failed"];
    case "expired": return ["bad", "Expired"];
    case "rejected": return ["bad", "Rejected"];
    case "cancelled": return ["", "Cancelled"];
    default: return ["", c.state];
  }
}

/** a machine card's last command, in a few words (the card has no read-back result) */
const LAST = { approval: "waiting for approval", pending: "on its way", sent: "being written", done: "done", failed: "failed",
  expired: "expired", rejected: "rejected", cancelled: "cancelled" };

/** the user may send, approve or resume commands (people who only look see no command controls) */
const commander = (can) => !!(can?.send || can?.approve || can?.resume);

const qualityKind = (q) => (q === "GOOD" ? "good" : q === "BAD" ? "bad" : q ? "warn" : "");

/** approve, reject (asks why) and cancel, shared by both pages */
function useActions(reload) {
  const toast = useToast();
  const [rejecting, setRejecting] = useState(null);
  const act = async (c, action) => {
    try {
      await api(`machine-commands/${c.id}/${action}`, { method: "POST" });
      toast(action === "approve" ? `Approved: ${c.machine} / ${c.label || c.tag} goes to the agent now.` : "Command cancelled.");
    } catch (e) {
      toast(e.message);
    }
    reload();
  };
  const dialog = rejecting && <RejectDialog command={rejecting} onClose={() => setRejecting(null)} onDone={() => {
    setRejecting(null);
    toast("Command rejected.");
    reload();
  }} />;
  return { approve: (c) => act(c, "approve"), cancel: (c) => act(c, "cancel"), reject: setRejecting, dialog };
}

export default function Machines() {
  const at = useOrgPath();
  const { data, error, reload } = usePoll("machines", 10_000);
  const machines = useMemo(() => data?.machines.map((m) => ({ ...m, uid: m.machine })), [data]);
  const rec = useRecordAddress(at("/machines"), machines, "machine");
  if (rec.record) return <MachinePage machine={rec.record.machine} back={at("/machines")} />;
  if (rec.key) return <p className="muted">Loading…</p>;
  return <MachineList data={data} error={error} reload={reload} open={rec.open} at={at} />;
}

// ------------------------------------------------------------------ the machines

function MachineList({ data, error, reload, open, at }) {
  const toast = useToast();
  const [q, setQ] = useState("");
  const [stopping, setStopping] = useState(false);
  const can = data?.can ?? {};
  const waiting = usePoll(can.approve ? "machine-commands?state=approval&limit=50" : null, 10_000);
  // changes to writable tags, limits and Allow machine commands that wait for a second person (four-eyes)
  const changes = usePoll(data ? "safety-changes?state=pending" : null, 15_000);
  const reloadAll = () => {
    reload();
    waiting.reload();
    changes.reload();
  };
  const acts = useActions(reloadAll);
  const list = data?.machines ?? [];
  const shown = useMemo(() => {
    const s = q.trim().toLowerCase();
    return !s ? list : list.filter((m) => [m.machine, m.agent, m.site, m.source, m.kindLabel].some((x) => String(x ?? "").toLowerCase().includes(s)));
  }, [list, q]);
  const pending = can.approve ? waiting.data?.commands ?? [] : [];

  return (
    <>
      <PageHeader title="Machines"
                  subtitle="Every machine your site agents collect. Open one to see its live data, the site agent and data source it comes from, the pipelines that deliver it, and its machine commands." />
      <Msg>{error}</Msg>
      {!data && !error && <p className="muted">Loading…</p>}
      {data && (
        <>
          {commander(can) && <StopState data={data} onStop={() => setStopping(true)} onChanged={reload} />}
          {pending.length > 0 && (
            <Card flush title={`Waiting for approval (${pending.length})`}
                  subtitle={`Each needs a second person: approve within ${data.approvalMinutes} minutes, or it expires. Your own requests wait for someone else.`}>
              <Commandtable commands={pending} acts={acts} can={can} machineLink={(c) => at(`/machines/${c.machine}`)} />
            </Card>
          )}
          <SafetyRequests data={changes.data} reload={reloadAll} sourceLink={(r) => at(`/agents/${r.agentId}`)} />
          <Card title={`Machines (${list.length})`}
                subtitle={list.length ? undefined : "No machines yet: add a data source to a site agent first."}
                action={list.length > 6 && (
                  <div className="reg-tools"><input type="search" aria-label="Search machines" placeholder="Search" value={q} onChange={(e) => setQ(e.target.value)} /></div>)}>
            {list.length > 0 && shown.length === 0 && <p className="muted">No machine matches.</p>}
            <div className="mcards">
              {shown.map((m) => <MachineCard key={m.machine} m={m} onOpen={() => open({ uid: m.machine })} />)}
            </div>
            {commander(can) && list.length > 0 && list.every((m) => !m.writable) && (
              <p className="hint">No machine has writable tags yet. On a site agent's data source (OPC UA, Modbus, MQTT), turn on Allow
                machine commands under Advanced, then open a tag's ⋯ settings and mark it writable with its limits.</p>
            )}
          </Card>
        </>
      )}
      {stopping && <StopDialog data={data} onClose={() => setStopping(false)} onDone={(n) => {
        setStopping(false);
        toast(`Machine commands stopped${n ? `; ${n} waiting command${n === 1 ? "" : "s"} cancelled` : ""}.`);
        reloadAll();
      }} />}
      {acts.dialog}
    </>
  );
}

function MachineCard({ m, onOpen }) {
  const [kind, label] = m.paused ? ["", "Paused"] : !m.online ? ["bad", "Agent offline"] : m.bad ? ["warn", `${m.bad} tag${m.bad === 1 ? "" : "s"} BAD`] : ["good", "Online"];
  const last = m.lastCommand;
  return (
    <button type="button" className="mcard" onClick={onOpen}>
      <span className="mcard-h">
        <b className="ellipsis" title={m.machine}>{m.machine}</b>
        <Status kind={kind}>{label}</Status>
      </span>
      <small className="ellipsis" title={`${m.kindLabel} on ${m.agent ?? "?"}${m.site ? ` · ${m.site}` : ""}`}>
        {m.kindLabel} on {m.agent ?? "?"}{m.site ? ` · ${m.site}` : ""}</small>
      <span className="mcard-f">
        <span>{m.tags} tag{m.tags === 1 ? "" : "s"} · {m.writable ? <b>{m.writable} writable</b> : "read only"}</span>
        <small title={full(m.updated)}>{m.updated ? `updated ${when(m.updated)}` : "no values yet"}</small>
      </span>
      <small className={m.pipelines ? "" : "warnc"}>{m.pipelines ? `Delivered by ${m.pipelines} pipeline${m.pipelines === 1 ? "" : "s"}` : "No active pipeline delivers it"}</small>
      {m.waiting > 0 && <Status kind="warn">{m.waiting} waiting for approval</Status>}
      {last && !m.waiting && <small className="ellipsis">Last command: {last.tag} · {LAST[last.state] ?? last.state} · {when(last.at)}</small>}
      {m.writable > 0 && m.blocked && <small className="warnc clamp2" title={m.blocked}>{m.blocked}</small>}
    </button>
  );
}

// ------------------------------------------------------------------ one machine

function MachinePage({ machine, back }) {
  const toast = useToast();
  const at = useOrgPath();
  const path = `machines/${encodeURIComponent(machine)}`;
  const { data, error, reload } = usePoll(path, 5_000);
  const [setting, setSetting] = useState(null);
  const [stopping, setStopping] = useState(false);
  const [read, setRead] = useState(null);           // {state, values, at, error}
  const timer = useRef(null);
  const asked = useRef(false);
  const acts = useActions(reload);
  useEffect(() => () => clearTimeout(timer.current), []);

  const readNow = async () => {
    clearTimeout(timer.current);
    setRead({ state: "pending" });
    try {
      const { id } = await api(`${path}/read`, { method: "POST" });
      const until = Date.now() + 60_000;
      const poll = async () => {
        try {
          const r = await api(`${path}/read/${id}`, { background: true });
          if ((r.state === "pending" || r.state === "sent") && Date.now() < until) {
            timer.current = setTimeout(poll, 700);
          } else {
            setRead(r);
          }
        } catch (e) {
          setRead({ state: "failed", error: e.message });
        }
      };
      timer.current = setTimeout(poll, 700);
    } catch (e) {
      setRead({ state: "failed", error: e.message });
    }
  };
  // a machine whose values Setu has not seen since it started: read them once when the page opens
  useEffect(() => {
    if (!data || asked.current) return;
    asked.current = true;
    if (data.can?.read && data.online && !data.paused && data.values.some((v) => v.value === null || v.value === undefined)) readNow();
  }, [data]); // eslint-disable-line react-hooks/exhaustive-deps

  if (error && !data) return <><BackLink to={back} /><Msg>{error}</Msg></>;
  if (!data) return <><BackLink to={back} /><p className="muted">Loading…</p></>;

  // the newer of: the last reading the agent sent, the value read on request
  const fresh = Object.fromEntries((read?.state === "done" ? read.values ?? [] : []).map((v) => [v.tag, v]));
  const values = data.values.map((v) => {
    const r = fresh[v.tag];
    return r && (!v.at || read.at > v.at) ? { ...v, value: r.value, quality: r.quality, detail: r.detail, at: read.at } : v;
  });
  const writable = values.filter((v) => v.writable);
  const target = (v) => ({ ...v, sourceId: data.sourceId, machine: data.machine, source: data.source, agent: data.agent, site: data.site });
  const can = data.can ?? {};
  const [kind, label] = data.paused ? ["", "Data source paused"] : !data.online ? ["bad", "Site agent offline"] : ["good", "Site agent online"];

  return (
    <>
      <BackLink to={back} />
      <PageHeader title={data.machine} subtitle={`${data.kindLabel} · from site agent ${data.agent ?? "?"}${data.site ? ` at ${data.site}` : ""}`} />
      <div className="mgrid three">
        <AgentCard data={data} at={at} />
        <SourceCard data={data} at={at} />
        <PipelinesCard data={data} at={at} />
      </div>
      <Card flush title="Live data"
            subtitle={<><Status kind={kind}>{label}</Status> · read every {data.pollMs >= 1000 ? `${data.pollMs / 1000} s` : `${data.pollMs} ms`}; changes arrive as they happen</>}
            action={(
              <div className="row-acts">
                {read?.state === "pending" && <span className="muted small">Asking the agent…</span>}
                {read?.state === "done" && <span className="muted small" title={full(read.at)}>Read from the machine {when(read.at)}</span>}
                {can.read && <button className="btn ghost sm" disabled={read?.state === "pending" || !data.online} onClick={readNow}>Read now</button>}
              </div>)}>
        {read?.state && read.state !== "done" && read.state !== "pending" && <div className="card-tools"><Msg kind="warn">Read now: {read.error ?? read.state}</Msg></div>}
        {writable.length > 0 && data.blocked && <div className="card-tools"><Msg kind="warn">{data.blocked}</Msg></div>}
        <table className="rej cmdtab">
          <thead><tr><th>Tag</th><th>Value</th><th>Quality</th><th>Allowed</th><th /></tr></thead>
          <tbody>
            {values.length === 0 && <Empty cols={5}>This machine has no tags.</Empty>}
            {values.map((v) => (
              <tr key={v.tag}>
                <td className="c-what"><b className="ellipsis" title={v.label || v.tag}>{v.label || v.tag}</b>
                  <small className="ellipsis mono" title={v.address}>{v.address}</small></td>
                <td className="c-val"><span className="reg-k">Value </span><b className="mval">{fmt(v.value, v.unit)}</b>
                  {v.at && <small title={full(v.at)}>{when(v.at)}</small>}</td>
                <td className="c-st"><span className="reg-k">Quality </span>{v.quality ? <Status kind={qualityKind(v.quality)}>{v.quality}</Status> : <span className="muted">–</span>}
                  {v.detail && <small className="c-why" title={v.detail}>{v.detail}</small>}</td>
                <td className="c-lim"><span className="reg-k">Allowed </span>
                  {!v.writable ? <span className="muted">read only</span>
                    : v.takes === "boolean" ? "On / off" : v.takes === "text" ? "Text" : `${fmt(v.writeMin)} to ${fmt(v.writeMax, v.unit)}`}
                  {v.approval && <small>needs a second person</small>}</td>
                <td className="r c-act">
                  {v.writable && can.send && <button className="btn sm" disabled={!!data.blocked} title={data.blocked ?? undefined}
                                                     onClick={() => setSetting(target(v))}>Set value…</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      {(commander(can) || data.commands.length > 0) && (
        <Card flush title="Machine commands" subtitle="Values written to this machine, newest first; kept 90 days (the audit log keeps them for good).">
          {commander(can) && <div className="card-tools"><StopState data={data} onStop={() => setStopping(true)} onChanged={reload} compact /></div>}
          <Commandtable commands={data.commands} acts={acts} can={can} />
        </Card>
      )}
      {setting && <SetValue target={setting} data={data} onClose={() => { setSetting(null); reload(); }} />}
      {stopping && <StopDialog data={data} onClose={() => setStopping(false)} onDone={(n) => {
        setStopping(false);
        toast(`Machine commands stopped${n ? `; ${n} waiting command${n === 1 ? "" : "s"} cancelled` : ""}.`);
        reload();
      }} />}
      {acts.dialog}
    </>
  );
}

const BackLink = ({ to }) => <p className="crumb"><Link to={to}>← All machines</Link></p>;

const agentKind = (s) => (s === "online" ? "good" : s === "offline" || s === "revoked" ? "bad" : "warn");

/** the site agent that collects the machine, with a link to its page */
function AgentCard({ data, at }) {
  const a = data.agentInfo;
  return (
    <Card title="Site agent" subtitle="The computer that reads this machine."
          action={a && <Link className="btn ghost sm" to={at(`/agents/${a.id}`)}>Open agent →</Link>}>
      {!a ? <p className="muted">The site agent is gone.</p> : (
        <dl className="kv">
          <dt>Agent</dt><dd><Link to={at(`/agents/${a.id}`)}>{a.name}</Link></dd>
          <dt>Status</dt><dd><Status kind={agentKind(a.status)}>{a.status}</Status>
            {a.lastSeen && <small className="muted" title={full(a.lastSeen)}>last report {when(a.lastSeen)}</small>}</dd>
          <dt>Computer</dt><dd>{a.hostname || "–"}{a.ip && <small className="muted">{a.ip}</small>}</dd>
          {a.site && <><dt>Site</dt><dd>{a.site}</dd></>}
          <dt>Version</dt><dd>{a.agentVersion || "–"}</dd>
        </dl>
      )}
    </Card>
  );
}

/** the data source the machine is read through */
function SourceCard({ data, at }) {
  return (
    <Card title="Data source" subtitle="How the agent reads this machine."
          action={data.agentId && <Link className="btn ghost sm" to={at(`/agents/${data.agentId}`)}>Edit on the agent →</Link>}>
      <dl className="kv">
        <dt>Name</dt><dd>{data.source}</dd>
        <dt>Type</dt><dd>{data.kindLabel}</dd>
        {data.address && <><dt>Address</dt><dd className="mono">{data.address}</dd></>}
        <dt>Reads every</dt><dd>{data.pollMs >= 1000 ? `${data.pollMs / 1000} s` : `${data.pollMs} ms`}{data.paused && <small className="warnc">paused</small>}</dd>
        <dt>Tags</dt><dd>{data.values.length} · {data.values.filter((v) => v.writable).length || "none"} writable</dd>
        <dt>Commands</dt><dd>{data.writesAllowed ? "allowed for writable tags" : "off (read only)"}</dd>
      </dl>
    </Card>
  );
}

/** the pipelines that deliver the machine's readings, with links */
function PipelinesCard({ data, at }) {
  const list = data.pipelines ?? [];
  const active = list.filter((p) => p.active);
  return (
    <Card title={`Pipelines (${active.length})`} subtitle="Where this machine's readings are delivered."
          action={<Link className="btn ghost sm" to={at("/pipelines")}>All pipelines →</Link>}>
      {list.length === 0 ? <p className="muted">No pipeline takes this machine: its readings are not delivered anywhere yet.</p> : (
        <ul className="plist">
          {list.map((p) => (
            <li key={p.id}>
              <span className="plist-h">
                <Link className="ellipsis" to={at(`/pipelines/${p.uid}`)} title={p.name}><b>{p.name}</b></Link>
                <Status kind={!p.active ? "" : p.bufferAlert ? "warn" : "good"}>{!p.active ? "Paused" : p.bufferAlert ? "Buffer filling" : "Active"}</Status>
              </span>
              <small className="ellipsis" title={p.destinations.join(", ")}>to {p.destinations.length ? p.destinations.join(", ") : "no destination"}</small>
              <small>{p.allMachines ? "all machines" : "selected machines"} · {p.tags} of {data.values.length} tag{data.values.length === 1 ? "" : "s"}
                {p.active && p.readingsPerMinute != null ? ` · ${p.readingsPerMinute} readings/min (all machines)` : ""}</small>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

/** the kill switch: what is stopped, who stopped it, and Stop / Resume (compact: only a line while nothing is stopped) */
function StopState({ data, onStop, onChanged, compact }) {
  const toast = useToast();
  const stops = data.stops ?? {};
  const names = Object.fromEntries((data.agents ?? []).map((a) => [a.id, a.name]));
  const list = [
    ...(stops.org ? [{ scope: "org", target: "", label: "Whole organization", ...stops.org }] : []),
    ...Object.entries(stops.sites ?? {}).map(([k, v]) => ({ scope: "site", target: k, label: `Site ${k}`, ...v })),
    ...Object.entries(stops.agents ?? {}).map(([k, v]) => ({ scope: "agent", target: k, label: `Site agent ${names[k] ?? k}`, ...v })),
  ];
  const resume = async (s) => {
    try {
      await api("machine-commands/resume", { method: "POST", body: { scope: s.scope, target: s.target } });
      toast(`Machine commands resumed: ${s.label.toLowerCase()}.`);
      onChanged();
    } catch (e) {
      toast(e.message);
    }
  };
  const all = !!stops.org;
  if (compact && !list.length) {
    return (
      <div className="cmd-line">
        <Status kind="good">Machine commands are allowed</Status>
        <button className="btn danger sm" onClick={onStop}>Stop commands…</button>
      </div>
    );
  }
  return (
    <section className={`verdict ${all ? "bad" : list.length ? "warn" : "good"} cmd-state`}>
      <span className="big" aria-hidden="true">{all || list.length ? "■" : "✓"}</span>
      <div>
        <h2>{all ? "Machine commands are stopped" : list.length ? "Machine commands are stopped in part" : "Machine commands are allowed"}</h2>
        {list.length === 0 ? <p>Only for writable tags, within their limits. A command not picked up within {data.ttlSeconds} s is dropped.</p> : (
          <ul className="stops">
            {list.map((s) => (
              <li key={`${s.scope}/${s.target}`}>
                <span className="min0"><b>{s.label}</b>: stopped by {s.byName} <span title={full(s.at)}>{when(s.at)}</span>
                  {s.reason ? ` · ${s.reason}` : ""}</span>
                {data.can?.resume && (s.scope !== "org" || (data.can?.resumeOrg ?? true)) && <button className="btn ghost sm" onClick={() => resume(s)}>Resume</button>}
              </li>
            ))}
          </ul>
        )}
      </div>
      {!all && <button className="btn danger sm cmd-stop" onClick={onStop}>Stop commands…</button>}
    </section>
  );
}

/** commands as a table: approve / reject waiting ones (not your own), cancel your own, details; machineLink: link each row */
function Commandtable({ commands, acts, can, machineLink }) {
  const [open, setOpen] = useState(null);
  return (
    <>
      <table className="rej cmdtab">
        <thead><tr><th>{machineLink ? "Machine / tag" : "Tag"}</th><th>Value</th><th>By</th><th>State</th><th /></tr></thead>
        <tbody>
          {commands.length === 0 && <Empty cols={5}>No commands yet.</Empty>}
          {commands.map((c) => {
            const [kind, label] = stateOf(c);
            const name = machineLink ? `${c.machine} / ${c.label || c.tag}` : c.label || c.tag;
            return (
              <tr key={c.id}>
                <td className="c-what">{machineLink ? <Link className="ellipsis" to={machineLink(c)} title={name}><b>{name}</b></Link>
                  : <b className="ellipsis" title={name}>{name}</b>}
                  <small title={full(c.requestedAt)}>{when(c.requestedAt)}{c.reason ? ` · ${c.reason}` : ""}</small></td>
                <td className="c-val"><span className="reg-k">Value </span>{fmt(c.value, c.unit)}
                  {c.result && "after" in c.result && <small>reads {fmt(c.result.after, c.unit)}</small>}
                  {c.state === "approval" && c.valueBefore !== null && <small>now {fmt(c.valueBefore, c.unit)}</small>}</td>
                <td className="c-who"><span className="ellipsis" title={c.requestedBy}>{c.requestedByName || c.requestedBy}</span>
                  {c.approvedBy && <small className="ellipsis" title={c.approvedBy}>approved by {c.approvedByName || c.approvedBy}</small>}</td>
                <td className="c-st"><Status kind={kind}>{label}</Status>
                  {(c.error || c.result?.detail) && <small className="c-why" title={c.error || c.result.detail}>{c.error || c.result.detail}</small>}</td>
                <td className="r c-act">
                  <div className="row-acts">
                    {c.state === "approval" && can.approve && !c.mine && <>
                      <button className="btn ghost sm" onClick={() => acts.reject(c)}>Reject…</button>
                      <button className="btn sm" onClick={() => acts.approve(c)}>Approve</button></>}
                    {c.mine && (c.state === "approval" || c.state === "pending") && <button className="btn ghost sm" onClick={() => acts.cancel(c)}>Cancel</button>}
                    <button className="btn ghost sm" onClick={() => setOpen(c)}>Details</button>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {open && <Details c={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function Details({ c, onClose }) {
  const [kind, label] = stateOf(c);
  const r = c.result ?? {};
  return (
    <Modal title={`${c.machine} / ${c.label || c.tag}`} subtitle={`${c.source} on ${c.agent}`} onClose={onClose}
           footer={<button className="btn" onClick={onClose}>Close</button>}>
      <dl className="kv">
        <dt>State</dt><dd><Status kind={kind}>{label}</Status></dd>
        <dt>Set to</dt><dd>{fmt(c.value, c.unit)}</dd>
        {c.valueBefore !== null && <><dt>Read before asking</dt><dd>{fmt(c.valueBefore, c.unit)}</dd></>}
        {"before" in r && <><dt>Machine before</dt><dd>{fmt(r.before, c.unit)}</dd></>}
        {"after" in r && <><dt>Machine after</dt><dd>{fmt(r.after, c.unit)}</dd></>}
        {r.deviceValue !== undefined && r.deviceValue !== c.value && <><dt>Written to the device</dt><dd>{fmt(r.deviceValue)}</dd></>}
        {r.target && <><dt>Written to</dt><dd className="mono">{r.target}</dd></>}
        {(c.error || r.detail) && <><dt>Note</dt><dd className={c.error ? "bad" : ""}>{c.error || r.detail}</dd></>}
        <dt>Asked by</dt><dd>{c.requestedByName || c.requestedBy} · {full(c.requestedAt)}</dd>
        {c.reason && <><dt>Reason</dt><dd>{c.reason}</dd></>}
        {c.approvedBy && <><dt>Approved by</dt><dd>{c.approvedByName || c.approvedBy} · {full(c.approvedAt)}</dd></>}
        {c.doneAt && <><dt>Answered</dt><dd>{full(c.doneAt)}</dd></>}
        <dt>Command id</dt><dd className="mono small">{c.id}</dd>
      </dl>
    </Modal>
  );
}

/** set one tag: the value, a reason, then the command's progress until the agent answers */
function SetValue({ target: t, data, onClose }) {
  const [value, setValue] = useState(t.takes === "boolean" ? (t.value === true ? "false" : "true") : "");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [cmd, setCmd] = useState(null);
  const timer = useRef(null);
  useEffect(() => () => clearTimeout(timer.current), []);

  const poll = (id, until) => {
    timer.current = setTimeout(async () => {
      try {
        const c = await api(`machine-commands/${id}`, { background: true });
        setCmd(c);
        if (OPEN.includes(c.state) && c.state !== "approval" && Date.now() < until) poll(id, until);
      } catch {
        if (Date.now() < until) poll(id, until);
      }
    }, 700);
  };
  const send = async () => {
    setBusy(true);
    setError(null);
    try {
      const c = await api("machine-commands", { method: "POST", body: { sourceId: t.sourceId, machine: t.machine, tag: t.tag, value, reason } });
      setCmd(c);
      if (c.state !== "approval") poll(c.id, Date.now() + 75_000);
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };

  const num = t.takes === "number";
  const out = num && value.trim() !== "" && (Number(value) < t.writeMin || Number(value) > t.writeMax || Number.isNaN(Number(value)));
  const shownValue = t.takes === "boolean" ? value === "true" : num && value.trim() !== "" ? Number(value) : value;
  const [kind, label] = cmd ? stateOf(cmd) : ["", ""];
  const finished = cmd && !OPEN.includes(cmd.state);
  return (
    <Modal title={`Set ${t.machine} / ${t.label || t.tag}`} subtitle={`${t.source} on ${t.agent}${t.site ? ` · ${t.site}` : ""}`} onClose={onClose}
           footer={cmd ? <button className="btn" onClick={onClose}>{finished || cmd.state === "approval" ? "Close" : "Close (it carries on)"}</button> : (
             <><button className="btn ghost" onClick={onClose}>Cancel</button>
               <button className="btn" disabled={busy || value === "" || out} onClick={send}>{t.approval ? "Ask for approval" : "Write to the machine"}</button></>)}>
      {!cmd ? (
        <>
          <dl className="kv">
            <dt>Now</dt><dd>{fmt(t.value, t.unit)}{t.at && <small title={full(t.at)}>read {when(t.at)}</small>}</dd>
            <dt>Allowed</dt><dd>{t.takes === "boolean" ? "On / off" : t.takes === "text" ? "Text, up to 200 characters" : `${fmt(t.writeMin)} to ${fmt(t.writeMax, t.unit)}`}</dd>
          </dl>
          <div className="field">
            <span className="label">New value</span>
            {t.takes === "boolean" ? (
              <div className="seg two" role="radiogroup" aria-label="New value">
                <label><input type="radio" name="w-val" checked={value === "true"} onChange={() => setValue("true")} /><b>On</b></label>
                <label><input type="radio" name="w-val" checked={value === "false"} onChange={() => setValue("false")} /><b>Off</b></label>
              </div>
            ) : (
              <div className="w-input">
                <input id="w-val" aria-label="New value" type={num ? "number" : "text"} step="any" min={num ? t.writeMin : undefined} max={num ? t.writeMax : undefined}
                       maxLength={num ? undefined : 200} value={value} onChange={(e) => setValue(e.target.value)} autoFocus aria-invalid={out || undefined} />
                {t.unit && <span className="muted">{t.unit}</span>}
              </div>
            )}
            {out && <span className="hint bad">Outside the allowed range.</span>}
          </div>
          <div className="field">
            <label htmlFor="w-why">Reason (kept in the audit log)</label>
            <input id="w-why" type="text" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. raise the pressure for batch 42" />
          </div>
          {value !== "" && !out && (
            <p className="w-sum">Set <b>{t.machine} / {t.label || t.tag}</b>
              {t.value !== null && t.value !== undefined && <> from <b>{fmt(t.value, t.unit)}</b></>} to <b>{fmt(shownValue, t.unit)}</b>
              {t.approval ? ", once another person approves it." : ". The site agent writes it now."}</p>
          )}
          <Msg>{error}</Msg>
        </>
      ) : (
        <div className="w-progress" aria-live="polite">
          <Status kind={kind}>{label}</Status>
          {cmd.state === "approval" && <p>Another person with the approve permission sees it under Machine commands. It expires in {data.approvalMinutes} minutes.</p>}
          {OPEN.includes(cmd.state) && cmd.state !== "approval" && <p className="muted">The site agent picks it up, writes it and reads it back…</p>}
          {cmd.result && "after" in cmd.result && (
            <dl className="kv">
              <dt>Before</dt><dd>{fmt(cmd.result.before, t.unit)}</dd>
              <dt>Now</dt><dd>{fmt(cmd.result.after, t.unit)}</dd>
            </dl>
          )}
          {(cmd.error || cmd.result?.detail) && <Msg kind={cmd.error ? "bad" : "warn"}>{cmd.error || cmd.result.detail}</Msg>}
        </div>
      )}
    </Modal>
  );
}

function StopDialog({ data, onClose, onDone }) {
  // a role limited to some places stops only there (the server offers only its sites and agents)
  const orgOk = data.can?.stopOrg ?? true;
  const [scope, setScope] = useState(orgOk ? "org" : data.sites?.length ? "site" : "agent");
  const [target, setTarget] = useState(orgOk ? "" : data.sites?.[0] ?? data.agents?.[0]?.id ?? "");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const sites = data.sites ?? [];
  const agents = data.agents ?? [];
  const go = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await api("machine-commands/stop", { method: "POST", body: { scope, target, reason } });
      onDone(r.cancelled);
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };
  return (
    <Modal title="Stop machine commands" subtitle="No command goes out where they are stopped; the waiting ones are cancelled. Resuming needs the Resume machine commands permission."
           onClose={onClose}
           footer={<><button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn danger" disabled={busy || (scope !== "org" && !target)} onClick={go}>Stop commands</button></>}>
      <div className="field">
        <span className="label">Where</span>
        <div className="seg" role="radiogroup" aria-label="Where">
          <label><input type="radio" name="s-scope" checked={scope === "org"} disabled={!orgOk} onChange={() => { setScope("org"); setTarget(""); }} /><b>Whole organization</b>
            {!orgOk && <small>Your roles hold only for some places.</small>}</label>
          <label><input type="radio" name="s-scope" checked={scope === "site"} disabled={!sites.length} onChange={() => { setScope("site"); setTarget(sites[0] ?? ""); }} />
            <b>One site</b>{!sites.length && <small>No site agent has a site set.</small>}</label>
          <label><input type="radio" name="s-scope" checked={scope === "agent"} disabled={!agents.length} onChange={() => { setScope("agent"); setTarget(agents[0]?.id ?? ""); }} />
            <b>One site agent</b></label>
        </div>
      </div>
      {scope === "site" && (
        <div className="field"><label htmlFor="s-site">Site</label>
          <select id="s-site" value={target} onChange={(e) => setTarget(e.target.value)}>{sites.map((s) => <option key={s} value={s}>{s}</option>)}</select></div>
      )}
      {scope === "agent" && (
        <div className="field"><label htmlFor="s-agent">Site agent</label>
          <select id="s-agent" value={target} onChange={(e) => setTarget(e.target.value)}>{agents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></div>
      )}
      <div className="field">
        <label htmlFor="s-why">Reason</label>
        <input id="s-why" type="text" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. maintenance on line 2" />
      </div>
      <Msg>{error}</Msg>
    </Modal>
  );
}

function RejectDialog({ command: c, onClose, onDone }) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const go = async () => {
    setBusy(true);
    try {
      await api(`machine-commands/${c.id}/reject`, { method: "POST", body: { reason } });
      onDone();
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };
  return (
    <Modal title={`Reject: ${c.machine} / ${c.label || c.tag} to ${fmt(c.value, c.unit)}`} subtitle={`Asked by ${c.requestedByName || c.requestedBy}.`} onClose={onClose}
           footer={<><button className="btn ghost" onClick={onClose}>Cancel</button><button className="btn danger" disabled={busy} onClick={go}>Reject</button></>}>
      <div className="field">
        <label htmlFor="r-why">Why (they see it)</label>
        <input id="r-why" type="text" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
      </div>
      <Msg>{error}</Msg>
    </Modal>
  );
}
