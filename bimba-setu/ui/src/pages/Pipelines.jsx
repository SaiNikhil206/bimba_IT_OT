import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Modal, Msg, PageHeader, Status, useOrgPath, usePoll, useRecordAddress, useToast } from "../ui.jsx";
import { serviceOf } from "./services.js";

// Pipelines: which readings go where, and how often. Data = machines + tag patterns; rate = every change, or a
// snapshot / average / minimum / maximum / count every N seconds; filters drop duplicates and unchanged values. One
// primary destination, secondaries tried in order while it is down, mirrors that each get their own copy. A draft sends
// nothing; activating starts delivery within seconds. Each route has a disk buffer, sized with the formula below.

const RATE = { change: "Every change", sample: "Snapshot", average: "Average", min: "Minimum", max: "Maximum", count: "Count" };
const WINDOW = ["average", "min", "max", "count"];
const MB = 1_048_576;
/** bytes of one reading in a buffered batch when the pipeline has not measured it yet (a 1.x message with source + edge) */
const READING_BYTES = 600;

/** Setu's tag patterns: * and ?, case-insensitive, "machine/tag" or just "tag" (delivery/Selection.java) */
function glob(g) {
  const re = g.split(/([*?])/).map((p) => (p === "*" ? ".*" : p === "?" ? "." : p.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))).join("");
  return new RegExp(`^${re}$`, "i");
}

/**
 * A tag the registry renames (deliveredMachine / deliveredTag) is selected by either name, as Selection.java does: one
 * name taken by the machines and include patterns, and no exclude pattern on either.
 */
export function selects({ mode, names, tagMode, include, exclude }, machine, tag, delivered) {
  const both = delivered?.machine && delivered?.tag ? [[machine, tag], [delivered.machine, delivered.tag]] : [[machine, tag]];
  const hit = (m, t) => (g) => (g.includes("/") ? glob(g).test(`${m}/${t}`) : glob(g).test(t));
  const inc = include.filter(Boolean);
  const taken = ([m, t]) => (mode !== "selected" || names.includes(m)) && (tagMode !== "patterns" || !inc.length || inc.some(hit(m, t)));
  const excluded = ([m, t]) => tagMode === "patterns" && exclude.filter(Boolean).some(hit(m, t));
  return both.some(taken) && !both.some(excluded);
}

const lines = (text) => text.split(/\r?\n|,/).map((x) => x.trim()).filter(Boolean);
const rateText = (r) => (!r || r.mode === "change" || !r.mode ? "Every change (real time)"
  : `${RATE[r.mode]} every ${r.seconds >= 60 && r.seconds % 60 === 0 ? `${r.seconds / 60} min` : `${r.seconds} s`}`);
const filterText = (f, refresh) => [f?.dedupe && "duplicates dropped", f?.changeOnly && (Number(f.deadband) > 0 ? `only changes beyond ${f.deadband}` : "only changed values"),
  Number(refresh) > 0 && `unchanged values again every ${refresh} s`].filter(Boolean).join(" · ");
/** who a pipeline's buffer alerts reach, for its card */
function alertedText(a) {
  const own = [...(a?.emails ?? []), ...(a?.webhookUrl ? ["its webhook"] : [])];
  const org = a?.organization !== false;
  if (!own.length) return org ? "The organization's alert recipients (Quarantine page) are told." : "Nobody is told by e-mail or webhook.";
  return `Told: ${own.join(", ")}${org ? ", and the organization's alert recipients" : ""}.`;
}
const mbText = (bytes) => (bytes >= 1024 * MB ? `${(bytes / 1024 / MB).toFixed(1)} GB` : `${Math.max(0, Math.round(bytes / MB))} MB`);

/**
 * FR-RES-05 sizing formula: tags × readings per tag per minute × 60 × bytes per reading × outage hours, plus 25 % for
 * batch overhead and bursts, in MB (rounded up). Each route (main + every mirror) gets a buffer of this size.
 */
export function bufferSize({ tags, perMinute, bytes, hours }) {
  const raw = Number(tags) * Number(perMinute) * 60 * Number(bytes) * Number(hours) * 1.25;
  return Number.isFinite(raw) && raw > 0 ? Math.ceil(raw / MB) : 0;
}

export default function Pipelines() {
  const { can } = useSession();
  const toast = useToast();
  const { data, error, reload } = usePoll("pipelines", 10_000);
  const [confirm, setConfirm] = useState(null);
  const [rejected, setRejected] = useState(null);   // pipeline whose rejected batches are open
  const [busy, setBusy] = useState(false);
  const manage = can("pipelines.manage");
  const list = data?.pipelines ?? [];
  const dests = data?.destinations ?? [];
  const byId = Object.fromEntries(dests.map((d) => [d.id, d]));
  const active = list.filter((p) => p.active);
  const rec = useRecordAddress(useOrgPath()("/pipelines"), data ? list : undefined, "pipeline");
  const editing = manage && data ? rec.record : null;     // null | "new" | pipeline

  const setActive = (p, on) => {
    const go = () => api(`pipelines/${p.id}/active`, { method: "POST", body: { active: on } });
    if (!on) {
      setConfirm({ title: `Pause ${p.name}?`, confirm: "Pause", danger: true, action: go, done: `${p.name} paused.`,
        subtitle: "Its machines' data stops going to its destinations until you activate it again." });
      return;
    }
    (async () => {
      setBusy(true);
      try {
        await go();
        toast(`${p.name} is active. Data starts flowing within seconds.`);
        reload();
      } catch (e) {
        toast(e.message);
      } finally {
        setBusy(false);
      }
    })();
  };

  return (
    <>
      <PageHeader title="Pipelines" subtitle="Which machines' data goes where: a primary destination, secondaries while it is down, and mirrors that get a copy.">
        {manage && <button className="btn" onClick={() => rec.open("new")} disabled={!data || !dests.length}
                           title={data && !dests.length ? "Add a destination first" : undefined}>New pipeline</button>}
      </PageHeader>
      <Msg>{error}</Msg>
      {data?.source && data.source !== "database" && (
        <Msg kind="warn">The database is not reachable: Setu delivers with its {data.source}. Changes are possible again when the database is back.</Msg>
      )}
      {(data?.problems ?? []).map((p) => <Msg key={p}>{p}</Msg>)}
      {data && list.length > 0 && active.length === 0 && (
        <Msg kind="warn">No pipeline is active: machine data is not delivered anywhere. Activate a pipeline to start.</Msg>
      )}
      {!data && <p className="muted">Loading…</p>}
      {data && list.length === 0 && <Steps hasDestinations={dests.length > 0} manage={manage} onNew={() => rec.open("new")} />}
      {list.map((p) => (
        <PipelineCard key={p.id} p={p} mark={rec.mark(p)} byId={byId} manage={manage} busy={busy}
                      onEdit={() => rec.open(p)} onActive={(on) => setActive(p, on)} onRejected={() => setRejected(p)} />
      ))}
      {data && list.length > 0 && (
        <p className="muted small">
          A destination can serve several pipelines. Machines that no active pipeline takes are not delivered
          {data.notRoutedMessages ? ` (${data.notRoutedMessages.toLocaleString()} readings since the last restart)` : ""}.
        </p>
      )}

      {editing && (
        <PipelineDialog p={editing === "new" ? null : editing} dests={dests} machines={data.machines} tags={data.tags ?? []} existing={list}
                        bufferDefault={data.bufferDefaultMegabytes} diskFree={data.diskFreeBytes}
                        onClose={rec.close}
                        onSaved={(r, on) => { rec.close(); toast(on ? `${r.name} saved and active.` : `${r.name} saved as a draft: it sends nothing yet.`); reload(); }}
                        onDelete={(p) => { rec.close(); setConfirm({
                          title: `Delete ${p.name}?`, confirm: "Delete", danger: true, done: `${p.name} deleted.`,
                          subtitle: p.active ? "Its machines' data stops going to its destinations. The destinations themselves stay." : "The destinations themselves stay.",
                          action: () => api(`pipelines/${p.id}`, { method: "DELETE" }),
                        }); }} />
      )}
      {rejected && <Rejected p={rejected} manage={can("quarantine.manage")} read={can("messages.view")} onClose={() => { setRejected(null); reload(); }} />}
      {confirm && (
        <Confirm title={confirm.title} subtitle={confirm.subtitle} confirm={confirm.confirm} danger={confirm.danger} action={confirm.action}
                 onDone={() => { toast(confirm.done); setConfirm(null); reload(); }} onClose={() => setConfirm(null)} />
      )}
    </>
  );
}

function Steps({ hasDestinations, manage, onNew }) {
  const at = useOrgPath();
  return (
    <Card title="Send machine data in three steps">
      <ol className="steps">
        <li className={hasDestinations ? "done" : ""}>
          <b>Add destinations</b><span>The systems that can receive data: Boomi, Kafka, Splunk, MQTT, … Test each connection.</span>
          {!hasDestinations && <Link className="btn ghost sm" to={at("/destinations")}>Go to Destinations</Link>}
        </li>
        <li><b>Create a pipeline</b><span>Choose the machines, a primary destination, secondaries for when it is down, and mirrors.</span>
          {hasDestinations && manage && <button className="btn sm" onClick={onNew}>New pipeline</button>}
        </li>
        <li><b>Activate it</b><span>Data starts flowing within seconds. Until then the pipeline is a draft and sends nothing.</span></li>
      </ol>
    </Card>
  );
}

// ------------------------------------------------------------------ one pipeline

function PipelineCard({ p, mark, byId, manage, busy, onEdit, onActive, onRejected }) {
  const st = p.status;
  const live = {};
  (st?.routes ?? []).forEach((r) => (r.destinations ?? []).forEach((d) => { live[d.name] = { ...d, active: r.activeDestination === d.name }; }));
  const names = p.machines?.mode === "selected" ? p.machines.names ?? [] : null;
  const buffered = st?.bufferedBatches ?? 0;
  const [kind, text] = !p.active ? ["", "Draft"]
    : !st ? ["warn", "Starting"]
    : !st.activeDestination ? ["bad", "Not delivering"]
    : st.activeDestination !== byId[p.primary]?.name ? ["warn", "On secondary"]
    : ["good", "Delivering"];
  return (
    <Card className="pipe" {...mark}>
      <div className="pipe-h">
        <div>
          <h2>{p.name} <Status kind={kind}>{text}</Status></h2>
          {p.description && <p className="sub">{p.description}</p>}
        </div>
        {manage && (
          <div className="acts">
            <button className="btn ghost sm" disabled={busy} onClick={() => onActive(!p.active)}>{p.active ? "Pause" : "Activate"}</button>
            <button className="btn ghost sm" onClick={onEdit}>Edit</button>
          </div>
        )}
      </div>
      <div className="flow">
        <section>
          <h3>Data</h3>
          {names ? <p>{names.length} machine{names.length === 1 ? "" : "s"}<small>{names.join(", ")}</small></p>
            : <p>All machines<small>Also machines added later</small></p>}
          {p.tags?.mode === "patterns" && (
            <p className="small">Tags: {(p.tags.include ?? []).join(", ") || "all"}
              {(p.tags.exclude ?? []).length > 0 && <small>except {p.tags.exclude.join(", ")}</small>}</p>
          )}
          <p className="small">{rateText(p.rate)}</p>
          {filterText(p.filters, p.refresh?.seconds) && <p className="small">{filterText(p.filters, p.refresh?.seconds)}</p>}
          {p.gapMarkers === false && <p className="small muted">No gap markers</p>}
        </section>
        <span className="arrow" aria-hidden="true">→</span>
        <section>
          <h3>Primary</h3>
          <Dest id={p.primary} byId={byId} live={live} running={p.active} />
        </section>
        <section>
          <h3>Secondary <small>if the primary is down</small></h3>
          {p.secondary.length ? <ol>{p.secondary.map((id) => <li key={id}><Dest id={id} byId={byId} live={live} running={p.active} /></li>)}</ol>
            : <p className="muted small">None: batches wait on disk while the primary is down.</p>}
        </section>
        <section>
          <h3>Mirrors <small>a copy each</small></h3>
          {p.mirrors.length ? <ul>{p.mirrors.map((id) => <li key={id}><Dest id={id} byId={byId} live={live} running={p.active} /></li>)}</ul>
            : <p className="muted small">None</p>}
        </section>
      </div>
      {p.active && buffered > 0 && <p className="muted small">{buffered.toLocaleString()} batch{buffered === 1 ? "" : "es"} waiting on disk; they are sent when a destination accepts data again.</p>}
      {p.active && st && (st.bufferAlert || (st.bufferUsedPercent ?? 0) >= 70) && (
        <p className="small bad">Disk buffer {st.bufferUsedPercent}% full ({mbText(st.bufferMaxBytes * st.bufferUsedPercent / 100)} of {mbText(st.bufferMaxBytes)}):
          its destinations are not keeping up. At 100% the oldest batches are discarded. {alertedText(p.bufferAlerts)}</p>
      )}
      {p.active && (st?.rejectedBatches ?? 0) > 0 && (
        <p className="small bad">{st.rejectedBatches} batch{st.rejectedBatches === 1 ? " was" : "es were"} refused as invalid and kept aside.{" "}
          <button className="link" onClick={onRejected}>Review…</button></p>
      )}
    </Card>
  );
}

function Dest({ id, byId, live, running }) {
  const d = byId[id];
  if (id == null) return <p className="muted small">Not chosen</p>;
  if (!d) return <p className="bad small">Removed destination</p>;
  const s = live[d.name];
  const [kind, text] = !running ? ["", "Draft"] : !s ? ["warn", "Starting"] : s.active ? ["good", "Delivering"]
    : s.state === "down" ? ["bad", "Down"] : s.state === "up" ? ["good", "Up"] : ["", "Standby"];
  return (
    <div className="dst">
      <b>{d.name}</b>
      <small>{serviceOf(d).label}</small>
      <Status kind={kind}>{text}</Status>
      {running && s?.state === "down" && s.lastError && <small className="err">{s.lastError}</small>}
    </div>
  );
}

// ------------------------------------------------------------------ rejected batches

/** Batches an end system refused as invalid (e.g. HTTP 400): why, and send them again (after fixing it) or discard them. */
function Rejected({ p, manage, read, onClose }) {
  const toast = useToast();
  const { data, error, reload } = usePoll(`pipelines/${p.id}/rejected`, 0);
  const [picked, setPicked] = useState([]);
  const [view, setView] = useState(null);           // { item, text }
  const [busy, setBusy] = useState(false);
  const list = data?.rejected ?? [];
  const key = (r) => `${r.route}/${r.file}`;
  const act = async (action, items) => {
    if (action === "discard" && !window.confirm(`Discard ${items.length} batch(es) for good? Their readings are not delivered.`)) return;
    setBusy(true);
    try {
      const r = await api(`pipelines/${p.id}/rejected`, { method: "POST", body: { action, items: items.map((x) => ({ route: x.route, file: x.file })) } });
      toast(action === "resend" ? `${r.done} batch(es) queued again; they are sent with the next retry.` : `${r.done} batch(es) discarded.`);
      setPicked([]);
      setView(null);
      reload();
    } catch (e) {
      toast(e.message);
    } finally {
      setBusy(false);
    }
  };
  const open = async (item) => {
    try {
      setView({ item, text: (await api(`pipelines/${p.id}/rejected/${item.route}/${item.file}`)).text });
    } catch (e) {
      setView({ item, text: e.message });
    }
  };
  const chosen = list.filter((r) => picked.includes(key(r)));
  return (
    <Modal wide title={`Refused batches · ${p.name}`} onClose={onClose}
           subtitle="A destination answered that these batches are invalid (e.g. HTTP 400), so they are not retried by themselves. Fix the cause (template, field, size), then send them again."
           footer={<>
             {manage && <button className="btn danger" disabled={busy || !chosen.length} style={{ marginRight: "auto" }} onClick={() => act("discard", chosen)}>Discard…</button>}
             {manage && <button className="btn" disabled={busy || !chosen.length} onClick={() => act("resend", chosen)}>Send again ({chosen.length})</button>}
             <button className="btn ghost" onClick={onClose}>Close</button></>}>
      <Msg>{error}</Msg>
      {!data && !error && <p className="muted">Loading…</p>}
      {data && list.length === 0 && <p className="muted">No refused batches.</p>}
      {list.length > 0 && (
        <table className="rej">
          <thead><tr><th>{manage && <input type="checkbox" aria-label="All" checked={picked.length === list.length}
                                                onChange={(e) => setPicked(e.target.checked ? list.map(key) : [])} />}</th>
            <th>When</th><th>Refused by</th><th>Why</th><th>Readings</th><th /></tr></thead>
          <tbody>
            {list.map((r) => (
              <tr key={key(r)}>
                <td>{manage && <input type="checkbox" checked={picked.includes(key(r))}
                                      onChange={(e) => setPicked(e.target.checked ? [...picked, key(r)] : picked.filter((x) => x !== key(r)))} />}</td>
                <td className="nowrap">{new Date(r.at).toLocaleString()}<small>{r.kind === "mirror" ? r.routeName : "main route"}</small></td>
                <td>{r.destination ?? "–"}</td>
                <td className="why">{r.reason ?? "–"}</td>
                <td>{r.count ?? "–"}</td>
                <td>{read && <button className="btn ghost sm" onClick={() => open(r)}>View</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {view && (
        <div className="field">
          <span className="label">Batch {view.item.file} (first 64 KB)</span>
          <pre className="code tall">{(() => { try { return JSON.stringify(JSON.parse(view.text), null, 2); } catch { return view.text; } })()}</pre>
        </div>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------------ create / edit

function PipelineDialog({ p, dests, machines, tags, existing, bufferDefault, diskFree, onClose, onSaved, onDelete }) {
  const isNew = !p;
  const [f, setF] = useState(() => ({
    name: p?.name ?? (existing.length ? "" : "Plant data"),
    description: p?.description ?? "",
    mode: p?.machines?.mode === "selected" ? "selected" : "all",
    names: p?.machines?.names ?? [],
    tagMode: p?.tags?.mode === "patterns" ? "patterns" : "all",
    include: (p?.tags?.include ?? []).join("\n"),
    exclude: (p?.tags?.exclude ?? []).join("\n"),
    rateMode: p?.rate?.mode ?? "change",
    rateSeconds: p?.rate?.seconds ?? 60,
    batchMax: p?.batch?.maxMessages ?? "",
    batchWait: p?.batch?.maxWaitMs ?? "",
    windowFn: WINDOW.includes(p?.rate?.mode) ? p.rate.mode : "average",
    dedupe: !!p?.filters?.dedupe,
    changeOnly: !!p?.filters?.changeOnly,
    deadband: p?.filters?.deadband ?? "",
    gapMarkers: p ? p.gapMarkers !== false : true,
    bufferMb: p?.buffer?.maxMegabytes ?? "",
    alertEmails: (p?.bufferAlerts?.emails ?? []).join(", "),
    alertHook: p?.bufferAlerts?.webhookUrl ?? "",
    alertSecret: p?.bufferAlerts?.webhookSecret ?? "",
    alertOrg: p?.bufferAlerts?.organization !== false,
    refresh: p?.refresh?.seconds ?? "",
    primary: p?.primary ?? "",
    secondary: p?.secondary ?? [],
    mirrors: p?.mirrors ?? [],
    active: p ? p.active : true,
  }));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [tests, setTests] = useState(null);          // null | [{name, ok?, message}]
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const used = new Set([f.primary, ...f.secondary, ...f.mirrors].filter((x) => x !== "" && x != null).map(Number));
  const free = dests.filter((d) => !used.has(d.id));
  const allMachines = [...new Set([...(machines ?? []), ...f.names])].sort();
  const name = (id) => dests.find((d) => d.id === id)?.name ?? `#${id}`;
  const sel = { mode: f.mode, names: f.names, tagMode: f.tagMode, include: lines(f.include), exclude: lines(f.exclude) };
  const picked = tags.filter((t) => selects(sel, t.machine, t.tag, { machine: t.deliveredMachine, tag: t.deliveredTag }));

  const body = () => ({
    name: f.name.trim(), description: f.description, active: f.active,
    machines: f.mode === "selected" ? { mode: "selected", names: f.names } : { mode: "all" },
    tags: f.tagMode === "patterns" ? { mode: "patterns", include: lines(f.include), exclude: lines(f.exclude) } : { mode: "all" },
    rate: f.rateMode === "change" ? { mode: "change" } : { mode: f.rateMode, seconds: Number(f.rateSeconds) },
    batch: { maxMessages: String(f.batchMax ?? "").trim(), maxWaitMs: String(f.batchWait ?? "").trim() },
    filters: { dedupe: f.dedupe, changeOnly: f.changeOnly, deadband: f.changeOnly ? String(f.deadband ?? "").trim() : "" },
    gapMarkers: f.gapMarkers,
    buffer: { maxMegabytes: String(f.bufferMb ?? "").trim() },
    bufferAlerts: { emails: f.alertEmails, webhookUrl: f.alertHook.trim(), webhookSecret: f.alertSecret, organization: f.alertOrg },
    refresh: f.rateMode === "change" ? { seconds: String(f.refresh ?? "").trim() } : {},
    primary: f.primary === "" ? null : Number(f.primary), secondary: f.secondary, mirrors: f.mirrors,
  });

  const save = async () => {
    setError(null);
    if (!f.name.trim()) return setError("Give the pipeline a name.");
    if (f.active && f.primary === "") return setError("Choose a primary destination, or clear \"Active\" to save a draft.");
    if (f.mode === "selected" && !f.names.length) return setError("Choose at least one machine, or send all machines.");
    if (f.tagMode === "patterns" && !lines(f.include).length && !lines(f.exclude).length) return setError("Enter a tag pattern, or send all tags.");
    if (f.rateMode !== "change" && !(Number(f.rateSeconds) >= 1 && Number(f.rateSeconds) <= 86400)) return setError("The interval must be 1 to 86400 seconds.");
    if (f.changeOnly && String(f.deadband).trim() !== "" && !(Number(f.deadband) >= 0)) return setError("The deadband is a number of 0 or more.");
    if (f.rateMode === "change" && String(f.refresh).trim() !== "" && !(Number(f.refresh) >= 1 && Number(f.refresh) <= 86400))
      return setError("Send unchanged values again every 1 to 86400 seconds, or leave it empty.");
    if (picked.length === 0) return setError("These settings select no tag. Widen the machines or the tag patterns.");
    setBusy(true);
    try {
      const r = isNew ? await api("pipelines", { method: "POST", body: body() }) : await api(`pipelines/${p.id}`, { method: "PUT", body: body() });
      onSaved(r, f.active);
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };

  /** tests every chosen destination with its saved settings; nothing is delivered */
  const testAll = async () => {
    const ids = [...used];
    if (!ids.length) return setError("Choose destinations first.");
    setError(null);
    setTests(ids.map((id) => ({ id, name: name(id), message: "Testing…" })));
    let full = [];
    try {
      full = (await api("destinations")).destinations;
    } catch (e) {
      return setTests([{ id: 0, name: "Destinations", ok: false, message: e.message }]);
    }
    await Promise.all(ids.map(async (id) => {
      const d = full.find((x) => x.id === id);
      let r;
      try {
        r = await api("destinations/test", { method: "POST", body: { original: id, destination: d } });
      } catch (e) {
        r = { ok: false, message: e.message };
      }
      setTests((t) => t.map((x) => (x.id === id ? { ...x, ...r } : x)));
    }));
  };

  const move = (k, i, dir) => {
    const l = [...f[k]];
    [l[i], l[i + dir]] = [l[i + dir], l[i]];
    set(k, l);
  };

  return (
    <Modal wide title={isNew ? "New pipeline" : `Edit ${p.name}`} onClose={onClose}
           subtitle="Choose the machines and where their data goes. The destinations must exist already (Destinations page)."
           footer={<>
             {!isNew && <button className="btn danger" style={{ marginRight: "auto" }} onClick={() => onDelete(p)}>Delete…</button>}
             <button className="btn ghost" onClick={testAll}>Test destinations</button>
             <button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy} onClick={save}>{f.active ? (isNew ? "Create and activate" : "Save") : "Save as draft"}</button></>}>
      <div className="grid2">
        <div className="field">
          <label htmlFor="p-name">Name</label>
          <input id="p-name" type="text" value={f.name} onChange={(e) => set("name", e.target.value)} placeholder="e.g. Plant data to Boomi" />
        </div>
        <div className="field">
          <label htmlFor="p-desc">Description <span className="muted">(optional)</span></label>
          <input id="p-desc" type="text" value={f.description} onChange={(e) => set("description", e.target.value)} />
        </div>
      </div>

      <fieldset className="field" style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className="label" style={{ marginBottom: 6 }}>1 · Data: machines</legend>
        <div className="seg two">
          <label><input type="radio" name="p-mode" checked={f.mode === "all"} onChange={() => set("mode", "all")} />
            <b>All machines</b><small>Also machines added to the tag tree later.</small></label>
          <label><input type="radio" name="p-mode" checked={f.mode === "selected"} onChange={() => set("mode", "selected")} />
            <b>Only chosen machines</b><small>Pick from the machines Setu reads.</small></label>
        </div>
        {f.mode === "selected" && (
          <div className="machines">
            {allMachines.length === 0 && <span className="muted small">No machines are assigned to this organization yet (a platform admin assigns them under Organizations).</span>}
            {allMachines.map((m) => (
              <label className="check" key={m}>
                <input type="checkbox" checked={f.names.includes(m)}
                       onChange={(e) => set("names", e.target.checked ? [...f.names, m] : f.names.filter((x) => x !== m))} />
                <span>{m}{!(machines ?? []).includes(m) && <small className="muted"> not in the tag tree now</small>}</span>
              </label>
            ))}
          </div>
        )}
      </fieldset>

      <fieldset className="field" style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className="label" style={{ marginBottom: 6 }}>Tags</legend>
        <div className="seg two">
          <label><input type="radio" name="p-tags" checked={f.tagMode === "all"} onChange={() => set("tagMode", "all")} />
            <b>All tags</b><small>Every tag of these machines.</small></label>
          <label><input type="radio" name="p-tags" checked={f.tagMode === "patterns"} onChange={() => set("tagMode", "patterns")} />
            <b>Tags matching patterns</b><small>e.g. PUMP-*/Flow* or Temp* · * any text, ? one character. A tag renamed in the tag registry matches by either name.</small></label>
        </div>
        {f.tagMode === "patterns" && (
          <div className="grid2" style={{ marginTop: 10 }}>
            <div className="field">
              <label htmlFor="p-inc">Include <span className="muted">(one per line)</span></label>
              <textarea id="p-inc" rows={3} className="mono" value={f.include} onChange={(e) => set("include", e.target.value)}
                        placeholder={"PUMP-*/Flow*\nTemp*"} spellCheck="false" />
            </div>
            <div className="field">
              <label htmlFor="p-exc">Exclude <span className="muted">(one per line)</span></label>
              <textarea id="p-exc" rows={3} className="mono" value={f.exclude} onChange={(e) => set("exclude", e.target.value)}
                        placeholder="*/Debug*" spellCheck="false" />
            </div>
          </div>
        )}
        <details className="adv" style={{ marginTop: 8 }}>
          <summary>{picked.length} of {tags.length} tags selected</summary>
          <div className="tagset">{picked.map((t) => {
            const name = `${t.machine}/${t.tag}${t.deliveredTag ? ` → ${t.deliveredMachine}/${t.deliveredTag}` : ""}`;
            return <span className="chip" key={`${t.machine}/${t.tag}`} title={t.deliveredTag ? `Collected as ${t.machine}/${t.tag}, delivered as ${t.deliveredMachine}/${t.deliveredTag}` : name}>{name}</span>;
          })}
            {picked.length === 0 && <span className="muted small">No tag matches.</span>}</div>
        </details>
      </fieldset>

      <fieldset className="field" style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className="label" style={{ marginBottom: 6 }}>Rate</legend>
        <div className="seg three">
          <label><input type="radio" name="p-rate" checked={f.rateMode === "change"} onChange={() => set("rateMode", "change")} />
            <b>Every change</b><small>Real time: each new value as it is read.</small></label>
          <label><input type="radio" name="p-rate" checked={f.rateMode === "sample"} onChange={() => set("rateMode", "sample")} />
            <b>Snapshot</b><small>Every N s, the current value of each tag (also unchanged ones).</small></label>
          <label><input type="radio" name="p-rate" checked={WINDOW.includes(f.rateMode)} onChange={() => set("rateMode", f.windowFn)} />
            <b>Window</b><small>Every N s, one value per tag: the average, minimum, maximum or count of the window.</small></label>
        </div>
        {f.rateMode !== "change" && (
          <div className="grid2" style={{ marginTop: 10 }}>
            {WINDOW.includes(f.rateMode) && (
              <div className="field">
                <label htmlFor="p-fn">Value of the window</label>
                <select id="p-fn" value={f.rateMode} onChange={(e) => setF((x) => ({ ...x, rateMode: e.target.value, windowFn: e.target.value }))}>
                  <option value="average">Average of the good numbers (with min, max, count)</option>
                  <option value="min">Minimum of the good numbers</option>
                  <option value="max">Maximum of the good numbers</option>
                  <option value="count">Count: how many readings the tag reported</option>
                </select>
                <span className="hint">Tags without a good number in the window send their last value.</span>
              </div>
            )}
            <div className="field">
              <label htmlFor="p-sec">Every (seconds)</label>
              <input id="p-sec" type="number" min="1" max="86400" value={f.rateSeconds} onChange={(e) => set("rateSeconds", e.target.value)} />
              <span className="hint">60 = once a minute. Fewer messages, lower cloud cost.</span>
            </div>
          </div>
        )}
        <details className="adv" style={{ marginTop: 10 }} open={f.dedupe || f.changeOnly || !f.gapMarkers || String(f.refresh) !== ""}>
          <summary>Filters{filterText(f, f.rateMode === "change" ? f.refresh : 0) ? ` · ${filterText(f, f.rateMode === "change" ? f.refresh : 0)}` : ""}</summary>
          <label className="check">
            <input type="checkbox" checked={f.dedupe} onChange={(e) => set("dedupe", e.target.checked)} />
            <span>Drop duplicates <small className="muted">A reading this pipeline already carried (same message id, or same machine, tag and time,
              e.g. sent again by a site agent after a lost answer) is not sent twice.</small></span>
          </label>
          <label className="check">
            <input type="checkbox" checked={f.changeOnly} onChange={(e) => set("changeOnly", e.target.checked)} />
            <span>Only changed values <small className="muted">A reading is sent only when its value or quality differs from the last one sent for
              its tag{f.rateMode !== "change" ? " (each window's value is compared with the last one sent)" : ""}.</small></span>
          </label>
          {f.changeOnly && (
            <div className="field" style={{ maxWidth: 260, marginLeft: 28 }}>
              <label htmlFor="p-db">Deadband <span className="muted">(numbers, optional)</span></label>
              <input id="p-db" type="number" min="0" step="any" placeholder="0" value={f.deadband} onChange={(e) => set("deadband", e.target.value)} />
              <span className="hint">A number that moved by at most this much counts as unchanged. 0 = every change.</span>
            </div>
          )}
          {f.rateMode === "change" && (
            <div className="field" style={{ maxWidth: 320 }}>
              <label htmlFor="p-refresh">Send unchanged values again every (s) <span className="muted">(optional)</span></label>
              <input id="p-refresh" type="number" min="1" max="86400" placeholder="never" value={f.refresh} onChange={(e) => set("refresh", e.target.value)} />
              <span className="hint">A tag that did not change for this long is sent again (marked aggregate.fn = repeat), so a receiver can tell a
                steady value from a lost one. 900 = every 15 minutes.</span>
            </div>
          )}
          <label className="check">
            <input type="checkbox" checked={f.gapMarkers} onChange={(e) => set("gapMarkers", e.target.checked)} />
            <span>Send gap markers <small className="muted">When a device or a site agent stops answering, each of its machines gets a
              "lost" message (tag _connection, category gap) and a "restored" one with the start and end of the gap, so receivers can tell
              missing data from a steady value.</small></span>
          </label>
        </details>
        <details className="adv" style={{ marginTop: 10 }} open={String(f.batchMax) !== "" || String(f.batchWait) !== ""}>
          <summary>Batching</summary>
          <div className="grid2">
            <div className="field">
              <label htmlFor="p-bmax">Readings per batch</label>
              <input id="p-bmax" type="number" min="1" max="10000" placeholder="Setu default" value={f.batchMax}
                     onChange={(e) => set("batchMax", e.target.value)} />
              <span className="hint">1 = every reading in its own message (single-record envelope).</span>
            </div>
            <div className="field">
              <label htmlFor="p-bwait">Longest wait (ms)</label>
              <input id="p-bwait" type="number" min="50" max="600000" placeholder="Setu default" value={f.batchWait}
                     onChange={(e) => set("batchWait", e.target.value)} />
              <span className="hint">A batch that is not full is sent after this. Empty = Setu's setting.</span>
            </div>
          </div>
        </details>
      </fieldset>

      <BufferSize f={f} set={set} picked={picked.length} routes={1 + f.mirrors.length} status={p?.status}
                  bufferDefault={bufferDefault} diskFree={diskFree} />
      <BufferAlerts f={f} set={set} saved={p} />

      <div className="field">
        <label htmlFor="p-primary">2 · Primary destination</label>
        <select id="p-primary" value={f.primary} onChange={(e) => set("primary", e.target.value === "" ? "" : Number(e.target.value))}>
          <option value="">— choose —</option>
          {dests.filter((d) => d.id === f.primary || !used.has(d.id)).map((d) => <option key={d.id} value={d.id}>{d.name} · {serviceOf(d).label}</option>)}
        </select>
        <span className="hint">Gets every batch of these machines. When it fails, the secondaries are tried; it is used again as soon as it works.</span>
      </div>

      <DestList title="3 · Secondary destinations" hint="Tried in this order while the primary is down. Optional."
                ids={f.secondary} name={name} free={free} ordered
                onAdd={(id) => set("secondary", [...f.secondary, id])} onRemove={(i) => set("secondary", f.secondary.filter((_, j) => j !== i))}
                onMove={(i, dir) => move("secondary", i, dir)} />
      <DestList title="4 · Mirrors" hint="Each gets its own copy of every batch, with its own retry and disk buffer. Optional."
                ids={f.mirrors} name={name} free={free}
                onAdd={(id) => set("mirrors", [...f.mirrors, id])} onRemove={(i) => set("mirrors", f.mirrors.filter((_, j) => j !== i))} />

      <label className="check">
        <input type="checkbox" checked={f.active} onChange={(e) => set("active", e.target.checked)} />
        <span><b>Active</b> <small className="muted">Deliver data now. Clear it to keep the pipeline as a draft that sends nothing.</small></span>
      </label>

      {tests && (
        <ul className="tests">
          {tests.map((t) => (
            <li key={t.id} className={t.ok === true ? "ok" : t.ok === false ? "bad" : ""}>
              <b>{t.ok === true ? "✓" : t.ok === false ? "✕" : "…"} {t.name}</b> <span>{t.message}</span>
            </li>
          ))}
        </ul>
      )}
      <Msg>{error}</Msg>
    </Modal>
  );
}

/**
 * FR-RES-05: the size of each route's disk buffer, and the documented formula to choose it: tags × readings per tag per
 * minute × 60 × bytes per reading × hours the destinations may be away (+25 %). Filled from what the pipeline measured
 * when it is running, else from its settings.
 */
function BufferSize({ f, set, picked, routes, status, bufferDefault, diskFree }) {
  const measuredRate = status?.readingsPerMinute && picked ? Math.max(0.1, Math.round((status.readingsPerMinute / picked) * 10) / 10) : null;
  const rateGuess = f.rateMode === "change" ? (measuredRate ?? 60) : Math.max(0.01, Math.round((60 / Math.max(1, Number(f.rateSeconds) || 60)) * 100) / 100);
  const [calc, setCalc] = useState(() => ({ tags: picked || 1, perMinute: rateGuess, bytes: status?.bytesPerReading || READING_BYTES, hours: 24 }));
  const setC = (k, v) => setCalc((x) => ({ ...x, [k]: v }));
  // follow the form: a new rate or tag selection gives the formula its numbers again (still editable)
  useEffect(() => setC("perMinute", rateGuess), [rateGuess]);
  useEffect(() => setC("tags", picked || 1), [picked]);
  const size = bufferSize(calc);
  const chosen = Number(f.bufferMb) > 0 ? Number(f.bufferMb) : bufferDefault ?? 512;
  const total = chosen * routes * MB;
  return (
    <details className="adv" open={String(f.bufferMb) !== ""}>
      <summary>Disk buffer · {String(f.bufferMb) !== "" ? `${f.bufferMb} MB` : `Setu default (${bufferDefault ?? 512} MB)`} per route</summary>
      <p className="small muted">While a destination is away, its batches wait on disk, in one buffer per route (the main route and each mirror).
        When a buffer is 70% full Setu warns (log, this page, and the people under Buffer alerts); at 100% the oldest batches are discarded.</p>
      <div className="grid2">
        <div className="field">
          <label htmlFor="p-buf">Size per route (MB)</label>
          <input id="p-buf" type="number" min="1" max="102400" placeholder={`Setu default (${bufferDefault ?? 512})`} value={f.bufferMb}
                 onChange={(e) => set("bufferMb", e.target.value)} />
          <span className="hint">{routes} route{routes === 1 ? "" : "s"} × {chosen} MB = {mbText(total)} at most
            {diskFree ? `; ${mbText(diskFree)} free on Setu's disk` : ""}.</span>
          {diskFree > 0 && total > diskFree && <span className="hint bad">More than the free disk space: the disk fills before the buffers do.</span>}
        </div>
        <div className="field">
          <span className="label">Sizing formula</span>
          <p className="small" style={{ margin: 0 }}>tags × readings per tag per minute × 60 × bytes per reading × outage hours × 1.25</p>
        </div>
        <div className="field">
          <label htmlFor="p-ct">Tags</label>
          <input id="p-ct" type="number" min="1" value={calc.tags} onChange={(e) => setC("tags", e.target.value)} />
          <span className="hint">{picked} selected now.</span>
        </div>
        <div className="field">
          <label htmlFor="p-cr">Readings per tag per minute</label>
          <input id="p-cr" type="number" min="0" step="any" value={calc.perMinute} onChange={(e) => setC("perMinute", e.target.value)} />
          <span className="hint">{f.rateMode !== "change" ? `One every ${f.rateSeconds} s.` : measuredRate != null ? `Measured: ${measuredRate}.` : "Every change: how often values change."}</span>
        </div>
        <div className="field">
          <label htmlFor="p-cb">Bytes per reading</label>
          <input id="p-cb" type="number" min="1" value={calc.bytes} onChange={(e) => setC("bytes", e.target.value)} />
          <span className="hint">{status?.bytesPerReading ? "Measured on this pipeline." : `About ${READING_BYTES} for the BIMBA message.`}</span>
        </div>
        <div className="field">
          <label htmlFor="p-ch">Outage to survive (hours)</label>
          <input id="p-ch" type="number" min="0" step="any" value={calc.hours} onChange={(e) => setC("hours", e.target.value)} />
          <span className="hint">How long the destinations may be away without losing data.</span>
        </div>
      </div>
      <div className="row-acts" style={{ justifyContent: "flex-start", gap: 10, alignItems: "center" }}>
        <span className="small"><b>{size ? `${size.toLocaleString()} MB` : "–"}</b> per route</span>
        <button type="button" className="btn ghost sm" disabled={!size || size > 102400} onClick={() => set("bufferMb", String(size))}>Use {size || "–"} MB</button>
        {size > 102400 && <span className="small bad">More than 100 GB: shorten the outage, lower the rate, or use a window.</span>}
      </div>
    </details>
  );
}

/**
 * FR-RES-05: who is told when one of the pipeline's disk buffers is 70% full and when it is back: its own e-mail
 * addresses and webhook, and the organization's alert recipients (Quarantine page) unless that is turned off.
 */
function BufferAlerts({ f, set, saved }) {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState(null);
  const own = f.alertEmails.trim() !== "" || f.alertHook.trim() !== "";
  const label = { email: "E-mail", webhook: "Webhook", organizationEmail: "Organization e-mail", organizationWebhook: "Organization webhook" };
  const test = async () => {
    setBusy(true);
    setMsg(null);
    try {
      const r = await api(`pipelines/${saved.id}/buffer-alerts/test`, { method: "POST" });
      const parts = Object.entries(r).map(([k, v]) => `${label[k] ?? k}: ${v}`);
      setMsg({ kind: parts.length ? "good" : "warn", text: parts.length ? parts.join(" · ") : "Nobody to tell: save an e-mail address or a webhook first." });
    } catch (e) {
      setMsg({ kind: "bad", text: e.message });
    } finally {
      setBusy(false);
    }
  };
  return (
    <details className="adv" open={own || !f.alertOrg}>
      <summary>Buffer alerts · {own ? (f.alertOrg ? "own recipients + organization's" : "own recipients only")
        : f.alertOrg ? "organization's recipients" : "nobody"}</summary>
      <p className="small muted">Who is told when a disk buffer of this pipeline is 70% full, and when it is back below 60%: for example the
        team that runs its destinations.</p>
      <div className="grid2">
        <div className="field span2">
          <label htmlFor="p-aemails">E-mail alerts to <span className="muted">(optional)</span></label>
          <input id="p-aemails" type="text" value={f.alertEmails} placeholder="integration@example.com, oncall@example.com"
                 onChange={(e) => set("alertEmails", e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="p-ahook">Webhook (POST, JSON) <span className="muted">(optional)</span></label>
          <input id="p-ahook" type="text" inputMode="url" value={f.alertHook} placeholder="https://hooks.example.com/bimba"
                 onChange={(e) => set("alertHook", e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="p-asecret">Webhook secret <span className="muted">(optional)</span></label>
          <input id="p-asecret" type="password" autoComplete="new-password" value={f.alertSecret} onChange={(e) => set("alertSecret", e.target.value)} />
          <span className="hint">Signs each call: header X-BIMBA-Signature = sha256 HMAC of the body.</span>
        </div>
      </div>
      <label className="check">
        <input type="checkbox" checked={f.alertOrg} onChange={(e) => set("alertOrg", e.target.checked)} />
        <span>Also tell the organization's alert recipients <small className="muted">The e-mail addresses and webhook on the Quarantine page.
          An address on both lists gets one e-mail.</small></span>
      </label>
      {!own && !f.alertOrg && <span className="hint bad">Nobody is told: only Setu log and this page show a filling buffer.</span>}
      {saved && (
        <div className="row-acts" style={{ justifyContent: "flex-start", gap: 10, alignItems: "center", marginTop: 6 }}>
          <button type="button" className="btn ghost sm" disabled={busy} onClick={test}>Send a test alert</button>
          <span className="small muted">Uses the saved settings: save changes first.</span>
        </div>
      )}
      {msg && <Msg kind={msg.kind}>{msg.text}</Msg>}
    </details>
  );
}

function DestList({ title, hint, ids, name, free, ordered, onAdd, onRemove, onMove }) {
  return (
    <div className="field">
      <span className="label">{title}</span>
      {ids.length > 0 && (
        <ol className={`picked${ordered ? "" : " plain"}`}>
          {ids.map((id, i) => (
            <li key={id}>
              <span>{name(id)}</span>
              {ordered && (
                <span className="order">
                  <button type="button" className="btn ghost sm" disabled={i === 0} onClick={() => onMove(i, -1)} aria-label={`Try ${name(id)} earlier`}>▲</button>
                  <button type="button" className="btn ghost sm" disabled={i === ids.length - 1} onClick={() => onMove(i, 1)} aria-label={`Try ${name(id)} later`}>▼</button>
                </span>
              )}
              <button type="button" className="btn ghost sm" onClick={() => onRemove(i)} aria-label={`Remove ${name(id)}`}>✕</button>
            </li>
          ))}
        </ol>
      )}
      <select value="" onChange={(e) => e.target.value && onAdd(Number(e.target.value))} disabled={!free.length} aria-label={`Add to ${title}`}>
        <option value="">{free.length ? "+ Add a destination" : "All destinations are used in this pipeline"}</option>
        {free.map((d) => <option key={d.id} value={d.id}>{d.name} · {serviceOf(d).label}</option>)}
      </select>
      <span className="hint">{hint}</span>
    </div>
  );
}
