import { useEffect, useState } from "react";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Empty, full, Modal, Msg, PageHeader, Status, usePoll, useToast, Verdict, when } from "../ui.jsx";

// Quarantine: readings Setu refused before routing (canonical/Ingest.java) because they do not match the BIMBA
// message format of their version, or name a machine their site agent does not collect. Each entry keeps the records as
// they arrived and why; after fixing the cause (the agent's sources, its software, Setu) replay them, or discard
// them. Refreshes every 30 s.

const REASON = {
  SCHEMA_INVALID: ["Does not match the format", "A field is missing or has the wrong type, a timestamp is not UTC, or the quality is unknown."],
  UNKNOWN_MACHINE: ["Machine not on this agent", "The site agent reported a machine that none of its data sources has. Add the source (or machine) to the agent, then replay."],
  UNSUPPORTED_VERSION: ["Unsupported version", "The message is in a format version this Setu server cannot read yet. Update Setu, then replay."],
  MALFORMED_JSON: ["Not readable", "The request was not valid JSON. Replaying cannot fix it; check the sender."],
};
const reasonLabel = (r) => REASON[r]?.[0] ?? r;

export default function Quarantine() {
  const { can } = useSession();
  const manage = can("quarantine.manage");
  const read = can("messages.view");          // the readings themselves
  const toast = useToast();
  const { data, error, reload } = usePoll("quarantine", 30_000);
  const [picked, setPicked] = useState([]);
  const [view, setView] = useState(null);         // entry being inspected
  const [busy, setBusy] = useState(false);
  const list = data?.entries ?? [];
  const chosen = list.filter((e) => picked.includes(e.id));

  const act = async (action, ids) => {
    if (action === "discard" && !window.confirm(`Discard ${ids.length} entr${ids.length === 1 ? "y" : "ies"} for good? Their readings are never delivered.`)) return;
    setBusy(true);
    try {
      const r = await api("quarantine", { method: "POST", body: { action, ids } });
      toast(action === "replay"
        ? `${r.delivered} reading(s) delivered${r.quarantined ? `, ${r.quarantined} still refused (kept in quarantine)` : ""}.`
        : `${r.discarded} entr${r.discarded === 1 ? "y" : "ies"} discarded.`);
      setPicked([]);
      setView(null);
      reload();
    } catch (e) {
      toast(e.message);
    } finally {
      setBusy(false);
    }
  };

  const records = data?.totalRecords ?? 0;
  return (
    <>
      <PageHeader title="Quarantine"
                  subtitle="Readings refused before delivery because they do not match the BIMBA message format. They are kept as they arrived, so they can be replayed once the cause is fixed." />
      <Msg>{error}</Msg>
      {!data && !error && <p className="muted">Loading…</p>}
      {data && (
        <>
          <Verdict kind={records ? "warn" : "good"}
                   title={records ? `${records.toLocaleString()} reading${records === 1 ? " is" : "s are"} in quarantine` : "Every reading matched the format"}>
            {records ? "They are not delivered until they are replayed." : "Nothing was refused."}
          </Verdict>
          <Card flush title="Refused readings"
                subtitle={data.totalEntries > list.length ? `The newest ${list.length} of ${data.totalEntries} entries` : undefined}
                action={manage && list.length > 0 && (
                  <div className="row-acts">
                    <button className="btn danger sm" disabled={busy || !chosen.length} onClick={() => act("discard", chosen.map((e) => e.id))}>Discard…</button>
                    <button className="btn sm" disabled={busy || !chosen.length} onClick={() => act("replay", chosen.map((e) => e.id))}>Replay ({chosen.length})</button>
                  </div>)}>
            <table className="rej qtab">
              <thead><tr>
                {manage && <th><input type="checkbox" aria-label="Select all" checked={list.length > 0 && picked.length === list.length}
                                      onChange={(e) => setPicked(e.target.checked ? list.map((x) => x.id) : [])} /></th>}
                <th>When</th><th>From</th><th>Why</th><th className="r">Readings</th>{read && <th />}
              </tr></thead>
              <tbody>
                {list.length === 0 && <Empty cols={4 + (manage ? 1 : 0) + (read ? 1 : 0)}>No refused readings.</Empty>}
                {list.map((e) => (
                  <tr key={e.id}>
                    {manage && <td className="q-sel"><input type="checkbox" aria-label={`Select ${e.id}`} checked={picked.includes(e.id)}
                                          onChange={(x) => setPicked(x.target.checked ? [...picked, e.id] : picked.filter((y) => y !== e.id))} /></td>}
                    <td className="nowrap q-when" title={full(e.at)}>{when(e.at)}</td>
                    <td className="q-from">{e.origin === "agent" ? (e.agentName ?? "Site agent") : "Setu collector"}
                      {e.machines?.length > 0 && <small className="ellipsis" title={e.machines.join(", ")}>{e.machines.join(", ")}</small>}</td>
                    <td className="q-why"><Status kind="warn">{reasonLabel(e.reason)}</Status><small className="why-text" title={e.detail}>{e.detail}</small></td>
                    <td className="r q-n">{e.count.toLocaleString()}<span className="q-unit"> reading{e.count === 1 ? "" : "s"}</span></td>
                    {read && <td className="r q-act"><button className="btn ghost sm" onClick={() => setView(e)}>View</button></td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
          <AlertSettings manage={manage} />
          <Card title="Message format">
            <dl className="kv">
              <dt>Written by this Setu server</dt><dd>Schema version {data.schemaVersion}</dd>
              <dt>Accepted</dt><dd>{[...(data.acceptedMajors ?? [])].sort().map((m) => `${m}.x`).join(", ")} (later minor versions too: fields this Setu server does not know are kept)</dd>
            </dl>
          </Card>
        </>
      )}
      {view && <Entry entry={view} busy={busy} manage={manage} onAct={act} onClose={() => setView(null)} />}
    </>
  );
}

/** Who is told about new quarantined readings (e-mail, webhook) and how long they are kept, for this organization. */
function AlertSettings({ manage }) {
  const toast = useToast();
  const { data, error } = usePoll("quarantine/settings", 0);
  const [f, setF] = useState(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState(null);
  useEffect(() => {
    if (data) setF({ ...data, emails: (data.emails ?? []).join(", ") });
  }, [data]);
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const save = async () => {
    setBusy(true);
    setMsg(null);
    try {
      const r = await api("quarantine/settings", { method: "PUT", body: f });
      setF({ ...r, emails: (r.emails ?? []).join(", ") });
      toast("Quarantine settings saved.");
    } catch (e) {
      setMsg({ kind: "bad", text: e.message });
    } finally {
      setBusy(false);
    }
  };
  const test = async () => {
    setBusy(true);
    setMsg(null);
    try {
      const r = await api("quarantine/settings/test", { method: "POST" });
      const parts = Object.entries(r).map(([k, v]) => `${k === "email" ? "E-mail" : "Webhook"}: ${v}`);
      setMsg({ kind: parts.length ? "good" : "warn", text: parts.length ? parts.join(" · ") : "Nothing to test: save an e-mail address or a webhook first." });
    } catch (e) {
      setMsg({ kind: "bad", text: e.message });
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card title="Alerts and retention" subtitle="For this organization: who is told when readings are quarantined, and how long they are kept. Pipelines also tell them when a disk buffer fills up, unless a pipeline turns that off.">
      <Msg>{error}</Msg>
      {!f ? <p className="muted">Loading…</p> : (
        <>
          <div className="grid2">
            <div className="field span2">
              <label htmlFor="q-emails">E-mail alerts to</label>
              <input id="q-emails" type="text" value={f.emails} disabled={!manage} placeholder="ops@example.com, oncall@example.com"
                     onChange={(e) => set("emails", e.target.value)} />
              {!f.mailConfigured && <span className="hint">This Setu server has no mail server set up yet (BIMBA_SMTP_HOST): e-mails are only written to its log.</span>}
            </div>
            <div className="field">
              <label htmlFor="q-hook">Webhook (POST, JSON)</label>
              <input id="q-hook" type="text" inputMode="url" value={f.webhookUrl ?? ""} disabled={!manage} placeholder="https://hooks.example.com/bimba"
                     onChange={(e) => set("webhookUrl", e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="q-secret">Webhook secret (optional)</label>
              <input id="q-secret" type="password" autoComplete="new-password" value={f.webhookSecret ?? ""} disabled={!manage}
                     onChange={(e) => set("webhookSecret", e.target.value)} />
              <span className="hint">Signs each call: header X-BIMBA-Signature = sha256 HMAC of the body.</span>
            </div>
            <div className="field">
              <label htmlFor="q-min">Alert after (readings)</label>
              <input id="q-min" type="number" min="1" max="100000" value={f.minRecords} disabled={!manage} onChange={(e) => set("minRecords", e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="q-quiet">At most one alert every (minutes)</label>
              <input id="q-quiet" type="number" min="1" max="1440" value={f.quietMinutes} disabled={!manage} onChange={(e) => set("quietMinutes", e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="q-keep">Keep quarantined readings (days)</label>
              <input id="q-keep" type="number" min="1" max="365" value={f.retentionDays} disabled={!manage} onChange={(e) => set("retentionDays", e.target.value)} />
              <span className="hint">Older ones are removed every hour.</span>
            </div>
          </div>
          {msg && <Msg kind={msg.kind}>{msg.text}</Msg>}
          {manage && (
            <div className="row-acts" style={{ justifyContent: "flex-start", marginTop: 10 }}>
              <button className="btn" disabled={busy} onClick={save}>Save</button>
              <button className="btn ghost" disabled={busy} onClick={test}>Send a test alert</button>
            </div>
          )}
        </>
      )}
    </Card>
  );
}

const SHOWN = 50;

function Entry({ entry, busy, manage, onAct, onClose }) {
  const { data, error } = usePoll(`quarantine/${entry.id}`, 0);
  const items = data?.items ?? [];
  const pretty = (raw) => { try { return JSON.stringify(JSON.parse(raw), null, 2); } catch { return raw; } };
  return (
    <Modal wide title={reasonLabel(entry.reason)} subtitle={REASON[entry.reason]?.[1]} onClose={onClose}
           footer={<>
             {manage && <button className="btn danger" disabled={busy} style={{ marginRight: "auto" }} onClick={() => onAct("discard", [entry.id])}>Discard…</button>}
             {manage && <button className="btn" disabled={busy || !data} onClick={() => onAct("replay", [entry.id])}>Replay</button>}
             <button className="btn ghost" onClick={onClose}>Close</button></>}>
      <Msg>{error}</Msg>
      {!data && !error && <p className="muted">Loading…</p>}
      {data && (
        <>
          <p className="muted small">{full(entry.at)} · {entry.count.toLocaleString()} reading{entry.count === 1 ? "" : "s"}
            {items.length > SHOWN ? ` · the first ${SHOWN} are shown` : ""}</p>
          {items.slice(0, SHOWN).map((it, i) => (
            <div className="field" key={i}>
              <span className="label ellipsis" title={`${it.machineId ?? ""} ${it.tagName ?? ""}`}>
                {it.machineId ? `${it.machineId}${it.tagName ? ` / ${it.tagName}` : ""}` : `Record ${i + 1}`}
              </span>
              <p className="small bad wrap-any">{it.detail}</p>
              <pre className="code">{pretty(it.raw)}</pre>
            </div>
          ))}
        </>
      )}
    </Modal>
  );
}
