import { useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Modal, Msg, PageHeader, Status, usePoll, useToast } from "../ui.jsx";
import { serviceOf } from "./services.js";

// Pipelines: which readings go where, and how often. Data = machines + tag patterns; rate = every change, or a
// snapshot / average every N seconds. One primary destination, secondaries tried in order while it is down, mirrors
// that each get their own copy. A draft sends nothing; activating starts delivery within seconds.

const RATE = { change: "Every change", sample: "Snapshot", average: "Average" };

/** the gateway's tag patterns: * and ?, case-insensitive, "machine/tag" or just "tag" (delivery/Selection.java) */
function glob(g) {
  const re = g.split(/([*?])/).map((p) => (p === "*" ? ".*" : p === "?" ? "." : p.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))).join("");
  return new RegExp(`^${re}$`, "i");
}

export function selects({ mode, names, tagMode, include, exclude }, machine, tag) {
  if (mode === "selected" && !names.includes(machine)) return false;
  if (tagMode !== "patterns") return true;
  const hit = (g) => (g.includes("/") ? glob(g).test(`${machine}/${tag}`) : glob(g).test(tag));
  const inc = include.filter(Boolean);
  if (inc.length && !inc.some(hit)) return false;
  return !exclude.filter(Boolean).some(hit);
}

const lines = (text) => text.split(/\r?\n|,/).map((x) => x.trim()).filter(Boolean);
const rateText = (r) => (!r || r.mode === "change" || !r.mode ? "Every change (real time)"
  : `${RATE[r.mode]} every ${r.seconds >= 60 && r.seconds % 60 === 0 ? `${r.seconds / 60} min` : `${r.seconds} s`}`);

export default function Pipelines() {
  const { can } = useSession();
  const toast = useToast();
  const { data, error, reload } = usePoll("pipelines", 10_000);
  const [editing, setEditing] = useState(null);     // null | "new" | pipeline
  const [confirm, setConfirm] = useState(null);
  const [rejected, setRejected] = useState(null);   // pipeline whose rejected batches are open
  const [busy, setBusy] = useState(false);
  const manage = can("delivery.manage");
  const list = data?.pipelines ?? [];
  const dests = data?.destinations ?? [];
  const byId = Object.fromEntries(dests.map((d) => [d.id, d]));
  const active = list.filter((p) => p.active);

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
        {manage && <button className="btn" onClick={() => setEditing("new")} disabled={!data || !dests.length}
                           title={data && !dests.length ? "Add a destination first" : undefined}>New pipeline</button>}
      </PageHeader>
      <Msg>{error}</Msg>
      {data?.source && data.source !== "database" && (
        <Msg kind="warn">The database is not reachable: the gateway delivers with its {data.source}. Changes are possible again when the database is back.</Msg>
      )}
      {(data?.problems ?? []).map((p) => <Msg key={p}>{p}</Msg>)}
      {data && list.length > 0 && active.length === 0 && (
        <Msg kind="warn">No pipeline is active: machine data is not delivered anywhere. Activate a pipeline to start.</Msg>
      )}
      {!data && <p className="muted">Loading…</p>}
      {data && list.length === 0 && <Steps hasDestinations={dests.length > 0} manage={manage} onNew={() => setEditing("new")} />}
      {list.map((p) => (
        <PipelineCard key={p.id} p={p} byId={byId} manage={manage} busy={busy}
                      onEdit={() => setEditing(p)} onActive={(on) => setActive(p, on)} onRejected={() => setRejected(p)} />
      ))}
      {data && list.length > 0 && (
        <p className="muted small">
          A destination can serve several pipelines. Machines that no active pipeline takes are not delivered
          {data.notRoutedMessages ? ` (${data.notRoutedMessages.toLocaleString()} readings since the last restart)` : ""}.
        </p>
      )}

      {editing && (
        <PipelineDialog p={editing === "new" ? null : editing} dests={dests} machines={data.machines} tags={data.tags ?? []} existing={list}
                        onClose={() => setEditing(null)}
                        onSaved={(r, on) => { setEditing(null); toast(on ? `${r.name} saved and active.` : `${r.name} saved as a draft: it sends nothing yet.`); reload(); }}
                        onDelete={(p) => { setEditing(null); setConfirm({
                          title: `Delete ${p.name}?`, confirm: "Delete", danger: true, done: `${p.name} deleted.`,
                          subtitle: p.active ? "Its machines' data stops going to its destinations. The destinations themselves stay." : "The destinations themselves stay.",
                          action: () => api(`pipelines/${p.id}`, { method: "DELETE" }),
                        }); }} />
      )}
      {rejected && <Rejected p={rejected} manage={manage} onClose={() => { setRejected(null); reload(); }} />}
      {confirm && (
        <Confirm title={confirm.title} subtitle={confirm.subtitle} confirm={confirm.confirm} danger={confirm.danger} action={confirm.action}
                 onDone={() => { toast(confirm.done); setConfirm(null); reload(); }} onClose={() => setConfirm(null)} />
      )}
    </>
  );
}

function Steps({ hasDestinations, manage, onNew }) {
  return (
    <Card title="Send machine data in three steps">
      <ol className="steps">
        <li className={hasDestinations ? "done" : ""}>
          <b>Add destinations</b><span>The systems that can receive data: Boomi, Kafka, Splunk, MQTT, … Test each connection.</span>
          {!hasDestinations && <Link className="btn ghost sm" to="/destinations">Go to Destinations</Link>}
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

function PipelineCard({ p, byId, manage, busy, onEdit, onActive, onRejected }) {
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
    <Card className="pipe">
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
function Rejected({ p, manage, onClose }) {
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
                <td>{manage && <button className="btn ghost sm" onClick={() => open(r)}>View</button>}</td>
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

function PipelineDialog({ p, dests, machines, tags, existing, onClose, onSaved, onDelete }) {
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
  const picked = tags.filter((t) => selects(sel, t.machine, t.tag));

  const body = () => ({
    name: f.name.trim(), description: f.description, active: f.active,
    machines: f.mode === "selected" ? { mode: "selected", names: f.names } : { mode: "all" },
    tags: f.tagMode === "patterns" ? { mode: "patterns", include: lines(f.include), exclude: lines(f.exclude) } : { mode: "all" },
    rate: f.rateMode === "change" ? { mode: "change" } : { mode: f.rateMode, seconds: Number(f.rateSeconds) },
    primary: f.primary === "" ? null : Number(f.primary), secondary: f.secondary, mirrors: f.mirrors,
  });

  const save = async () => {
    setError(null);
    if (!f.name.trim()) return setError("Give the pipeline a name.");
    if (f.active && f.primary === "") return setError("Choose a primary destination, or clear \"Active\" to save a draft.");
    if (f.mode === "selected" && !f.names.length) return setError("Choose at least one machine, or send all machines.");
    if (f.tagMode === "patterns" && !lines(f.include).length && !lines(f.exclude).length) return setError("Enter a tag pattern, or send all tags.");
    if (f.rateMode !== "change" && !(Number(f.rateSeconds) >= 1 && Number(f.rateSeconds) <= 86400)) return setError("The interval must be 1 to 86400 seconds.");
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
            <b>Only chosen machines</b><small>Pick from the machines the gateway reads.</small></label>
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
            <b>Tags matching patterns</b><small>e.g. PUMP-*/Flow* or Temp* · * any text, ? one character.</small></label>
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
          <div className="tagset">{picked.map((t) => <span className="chip" key={`${t.machine}/${t.tag}`} title={`${t.machine}/${t.tag}`}>{t.machine}/{t.tag}</span>)}
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
          <label><input type="radio" name="p-rate" checked={f.rateMode === "average"} onChange={() => set("rateMode", "average")} />
            <b>Average</b><small>Every N s, average / min / max of each number tag.</small></label>
        </div>
        {f.rateMode !== "change" && (
          <div className="field" style={{ marginTop: 10, maxWidth: 260 }}>
            <label htmlFor="p-sec">Every (seconds)</label>
            <input id="p-sec" type="number" min="1" max="86400" value={f.rateSeconds} onChange={(e) => set("rateSeconds", e.target.value)} />
            <span className="hint">60 = once a minute. Fewer messages, lower cloud cost.</span>
          </div>
        )}
      </fieldset>

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
