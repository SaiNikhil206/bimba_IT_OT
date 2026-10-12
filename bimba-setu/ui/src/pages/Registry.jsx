import { useMemo, useState } from "react";
import { api, apiText } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Empty, Modal, Msg, PageHeader, Status, usePoll, useToast } from "../ui.jsx";

// Tag registry (FR-CAN-04): for each tag as it is collected (machine / tag), its canonical name, machine, plant id, data
// type, unit, writable flag and description. Setu applies it to every reading before delivery
// (canonical/TagRegistry.java); tags without an entry are delivered as collected. Edit one tag, or export / import CSV.

const SHOWN = 300;
const key = (machine, tag) => `${machine}/${tag}`;

export default function Registry() {
  const { can } = useSession();
  const manage = can("delivery.manage");
  const toast = useToast();
  const { data, error, reload } = usePoll("registry", 0);
  const [q, setQ] = useState("");
  const [only, setOnly] = useState("all");          // all | mapped | unmapped
  const [editing, setEditing] = useState(null);      // row
  const [importing, setImporting] = useState(false);

  const rows = useMemo(() => {
    if (!data) return [];
    const byKey = new Map();
    for (const t of data.tags ?? []) byKey.set(key(t.machine, t.tag), { machine: t.machine, tag: t.tag, unit: t.unit, collected: true });
    for (const e of data.entries ?? []) {
      const k = key(e.machine, e.tag);
      byKey.set(k, { ...(byKey.get(k) ?? { machine: e.machine, tag: e.tag, unit: "", collected: false }), entry: e });
    }
    return [...byKey.values()].sort((a, b) => key(a.machine, a.tag).localeCompare(key(b.machine, b.tag)));
  }, [data]);
  const needle = q.trim().toLowerCase();
  const shown = rows.filter((r) => (only === "all" || (only === "mapped") === !!r.entry)
    && (!needle || [r.machine, r.tag, r.entry?.tagName, r.entry?.machineId, r.entry?.description].some((x) => (x ?? "").toLowerCase().includes(needle))));
  const mapped = rows.filter((r) => r.entry).length;

  const exportCsv = async () => {
    try {
      const text = await apiText("registry/export");
      const url = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
      const a = document.createElement("a");
      a.href = url;
      a.download = "bimba-tag-registry.csv";
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      toast(e.message);
    }
  };

  return (
    <>
      <PageHeader title="Tag registry"
                  subtitle="Each tag's canonical name, machine, plant, data type, unit and writable flag. Applied to every reading before delivery; tags without an entry are sent as collected. Pipelines select a renamed tag by either name.">
        <button className="btn ghost" onClick={exportCsv}>Export CSV</button>
        {manage && <button className="btn" onClick={() => setImporting(true)}>Import CSV…</button>}
      </PageHeader>
      <Msg>{error}</Msg>
      {!data && !error && <p className="muted">Loading…</p>}
      {data && (
        <Card flush title={`${rows.length.toLocaleString()} tag${rows.length === 1 ? "" : "s"} · ${mapped.toLocaleString()} in the registry`}
              action={(
                <div className="reg-tools">
                  <input type="search" aria-label="Search tags" placeholder="Search" value={q} onChange={(e) => setQ(e.target.value)} />
                  <select aria-label="Show" value={only} onChange={(e) => setOnly(e.target.value)}>
                    <option value="all">All tags</option>
                    <option value="mapped">In the registry</option>
                    <option value="unmapped">Not in the registry</option>
                  </select>
                </div>)}>
          <table className="rej regtab">
            <thead><tr><th>Collected as</th><th>Delivered as</th><th>Plant</th><th>Type</th><th>Unit</th><th>Writable</th>{manage && <th />}</tr></thead>
            <tbody>
              {shown.length === 0 && <Empty cols={manage ? 7 : 6}>{rows.length ? "No tag matches." : "No tags yet: add devices or site agent data sources first."}</Empty>}
              {shown.slice(0, SHOWN).map((r) => {
                const e = r.entry;
                const renamed = e && (e.tagName || e.machineId);
                return (
                  <tr key={key(r.machine, r.tag)}>
                    <td className="q-when"><span className="mono ellipsis" title={key(r.machine, r.tag)}>{key(r.machine, r.tag)}</span>
                      {!r.collected && <small className="warnc">Not collected right now</small>}</td>
                    <td className="q-from">{renamed
                      ? <span className="mono ellipsis" title={key(e.machineId ?? r.machine, e.tagName ?? r.tag)}>{key(e.machineId ?? r.machine, e.tagName ?? r.tag)}</span>
                      : <span className="muted">{e ? "same names" : "as collected"}</span>}
                      {e?.description && <small className="ellipsis" title={e.description}>{e.description}</small>}</td>
                    <td className="q-why"><span className="reg-k">Plant </span>{e?.plantId ?? <span className="muted">–</span>}</td>
                    <td className="q-why"><span className="reg-k">Type </span>{e?.dataType ?? <span className="muted">–</span>}</td>
                    <td className="q-why"><span className="reg-k">Unit </span>{e?.unit ?? (r.unit || <span className="muted">–</span>)}
                      {e?.unit && r.unit && e.unit !== r.unit && <small>from {r.unit}</small>}</td>
                    <td className="q-why"><span className="reg-k">Writable </span>{e?.writable == null ? <span className="muted">–</span>
                      : <Status kind={e.writable ? "warn" : ""}>{e.writable ? "Yes" : "No"}</Status>}</td>
                    {manage && <td className="r q-act"><button className="btn ghost sm" onClick={() => setEditing(r)}>{e ? "Edit" : "Add"}</button></td>}
                  </tr>
                );
              })}
            </tbody>
          </table>
          {shown.length > SHOWN && <p className="muted small" style={{ padding: "10px 16px" }}>The first {SHOWN} of {shown.length.toLocaleString()} are shown: search to narrow.</p>}
        </Card>
      )}
      {editing && <EntryDialog row={editing} machines={data.machines ?? []} types={data.types ?? []}
                               onClose={() => setEditing(null)} onSaved={(text) => { setEditing(null); toast(text); reload(); }} />}
      {importing && <ImportDialog onClose={() => setImporting(false)} onDone={(text) => { setImporting(false); toast(text); reload(); }} />}
    </>
  );
}

function EntryDialog({ row, machines, types, onClose, onSaved }) {
  const e = row.entry ?? {};
  const [f, setF] = useState({
    tagName: e.tagName ?? "", machineId: e.machineId ?? "", plantId: e.plantId ?? "", dataType: e.dataType ?? "",
    unit: e.unit ?? "", writable: e.writable == null ? "" : e.writable ? "true" : "false", description: e.description ?? "",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const send = async (body, text) => {
    setBusy(true);
    setError(null);
    try {
      await api("registry/entry", { method: "PUT", body: { machine: row.machine, tag: row.tag, ...body } });
      onSaved(text);
    } catch (x) {
      setError(x.message);
      setBusy(false);
    }
  };
  const others = machines.filter((m) => m !== row.machine);
  return (
    <Modal title={key(row.machine, row.tag)} subtitle="Empty fields keep the value as collected." onClose={onClose}
           footer={<>
             {row.entry && <button className="btn danger" disabled={busy} style={{ marginRight: "auto" }}
                                   onClick={() => send({}, "Registry entry removed: the tag is delivered as collected.")}>Remove entry</button>}
             <button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy} onClick={() => send(f, "Registry entry saved. It applies from the next reading.")}>Save</button></>}>
      <Msg>{error}</Msg>
      <div className="grid2">
        <div className="field">
          <label htmlFor="r-tag">Canonical tag name</label>
          <input id="r-tag" type="text" value={f.tagName} placeholder={row.tag} onChange={(x) => set("tagName", x.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="r-machine">Machine</label>
          <select id="r-machine" value={f.machineId} onChange={(x) => set("machineId", x.target.value)}>
            <option value="">{row.machine} (as collected)</option>
            {others.map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="r-plant">Plant id</label>
          <input id="r-plant" type="text" value={f.plantId} placeholder="as collected" onChange={(x) => set("plantId", x.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="r-type">Data type</label>
          <select id="r-type" value={f.dataType} onChange={(x) => set("dataType", x.target.value)}>
            <option value="">As collected</option>
            {types.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="r-unit">Unit</label>
          <input id="r-unit" type="text" value={f.unit} placeholder={row.unit || "none"} onChange={(x) => set("unit", x.target.value)} />
          {row.unit && <span className="hint">Collected in {row.unit}; another unit of the same kind is converted (e.g. bar → psi).</span>}
        </div>
        <div className="field">
          <label htmlFor="r-w">Writable</label>
          <select id="r-w" value={f.writable} onChange={(x) => set("writable", x.target.value)}>
            <option value="">Not set</option>
            <option value="true">Yes: commands may write it</option>
            <option value="false">No: read only</option>
          </select>
        </div>
        <div className="field span2">
          <label htmlFor="r-desc">Description</label>
          <input id="r-desc" type="text" maxLength={500} value={f.description} onChange={(x) => set("description", x.target.value)} />
        </div>
      </div>
    </Modal>
  );
}

function ImportDialog({ onClose, onDone }) {
  const [text, setText] = useState("");
  const [mode, setMode] = useState("merge");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const pick = async (e) => {
    const file = e.target.files?.[0];
    if (file) setText(await file.text());
  };
  const go = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await api("registry/import", { method: "POST", body: { csv: text, mode } });
      onDone(`${r.rows} row(s) imported; the registry has ${r.entries} entries.`);
    } catch (x) {
      setError(x.message);
      setBusy(false);
    }
  };
  return (
    <Modal title="Import the tag registry" subtitle="CSV with a header row: machine, tag, canonical_tag, machine_id, plant_id, data_type, unit, writable, description (Export CSV gives the layout)."
           onClose={onClose}
           footer={<><button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy || !text.trim()} onClick={go}>Import</button></>}>
      <Msg>{error}</Msg>
      <div className="field">
        <label htmlFor="r-file">File</label>
        <input id="r-file" type="file" accept=".csv,text/csv" onChange={pick} />
      </div>
      <div className="field">
        <label htmlFor="r-csv">Or paste it</label>
        <textarea id="r-csv" rows={8} className="mono" spellCheck="false" value={text} onChange={(e) => setText(e.target.value)} />
      </div>
      <div className="seg two">
        <label><input type="radio" name="r-mode" checked={mode === "merge"} onChange={() => setMode("merge")} />
          <b>Add and update</b><small>Rows replace the entry of the same tag; other entries stay.</small></label>
        <label><input type="radio" name="r-mode" checked={mode === "replace"} onChange={() => setMode("replace")} />
          <b>Replace everything</b><small>The file becomes the whole registry.</small></label>
      </div>
      <p className="hint">Nothing is saved when a row is wrong; the message names the row.</p>
    </Modal>
  );
}
