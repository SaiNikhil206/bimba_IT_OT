import { useState } from "react";
import { api } from "../api.js";
import { Card, full, Modal, Msg, Status, usePoll, useToast, when } from "../ui.jsx";

// Automatic site agent updates (Setu: agents/AgentUpdates). A newer installer in Setu's installer folder is run over the
// old one by the computer itself: the agent downloads it, its updater checks it with Setu again and installs it the way the
// agent was installed (setup exe, MSI, .deb, .tar.gz). The registration, data sources and buffered readings stay.
// Organization settings: Automatic / Ask me / Off, an optional daily window, how many computers at once (agents.manage
// for the whole organization). Per computer: Update now, Hold (agents.manage for that agent).

const ACTIVE = ["offered", "downloading", "staged", "installing"];
const STEP = { offered: "Starting", downloading: "Downloading", staged: "Installing", installing: "Installing" };
const KIND = { exe: "setup exe", msi: "MSI", deb: ".deb package", tar: ".tar.gz package" };
const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

/** the policy in a few words: "Automatic, 02:00–05:00 (Europe/Berlin), 5 at a time" */
export function policyText(p) {
  if (!p) return "";
  if (p.mode === "off") return "Off: computers are updated by hand";
  const win = p.window ? `between ${p.window.from} and ${p.window.to} (${p.window.zone})` : "any time";
  return p.mode === "manual" ? "Ask me: start each update with Update now" : `Automatic, ${win}, ${plural(p.maxAtOnce, "computer")} at a time`;
}

/** one computer's software state, short (list) */
export function VersionCell({ a }) {
  const u = a.update;
  const v = a.agentVersion ?? "–";
  if (!u || !u.reported) {
    return <><span className="mono">{v}</span>
      <small className={u?.available ? "warnc" : ""} title="Agents before 1.10 cannot update themselves">{u?.available ? `${u.available} available · update by hand` : "Up to date"}</small></>;
  }
  if (ACTIVE.includes(u.state)) {
    return <><span className="mono">{v}</span><small><Status kind="warn">{STEP[u.state]} {u.target}</Status></small></>;
  }
  if (u.state === "failed" && u.available) {
    return <><span className="mono">{v}</span><small className="bad ellipsis" title={u.error ?? ""}>Update to {u.target} failed</small></>;
  }
  if (u.available) {
    const how = u.hold ? "on hold" : !u.allowed ? "refused on the computer" : !u.supported ? "update by hand" : "available";
    return <><span className="mono">{v}</span><small className={u.hold || !u.allowed || !u.supported ? "warnc" : ""}>{u.available} {how}</small></>;
  }
  return <><span className="mono">{v}</span><small>Up to date</small></>;
}

/** the list page's line about updates: the policy, what is under way, what needs a person */
export function UpdatesBar() {
  const { data, error, reload } = usePoll("agents/updates", 30_000);
  const [editing, setEditing] = useState(false);
  if (!data) return error ? <Msg>{error}</Msg> : null;
  const c = data.counts ?? {};
  const parts = [];
  if (c.updating) parts.push(`${plural(c.updating, "computer")} updating now`);
  if (c.waiting) parts.push(`${plural(c.waiting, "computer")} ${data.policy.mode === "automatic" ? "will update" : "can update"}`);
  if (c.held) parts.push(`${c.held} on hold`);
  if (c.manual) parts.push(`${c.manual} to update by hand`);
  if (c.failed) parts.push(`${c.failed} failed: ${data.failed.join(", ")}`);
  const latest = [...new Set(Object.values(data.release ?? {}).map((r) => r.version))].join(" / ");
  return (
    <Card className="upd-bar">
      <div className="upd-line">
        <div className="upd-text">
          <b>Agent updates</b>
          <span className="ellipsis" title={policyText(data.policy)}>{policyText(data.policy)}</span>
          <small className="muted clamp2">{latest ? `Latest on Setu: ${latest}. ` : "No installers on this Setu server. "}{parts.join(" · ") || "All computers are up to date."}</small>
        </div>
        {data.canManage && <button className="btn ghost sm" onClick={() => setEditing(true)}>Update settings</button>}
      </div>
      {editing && <PolicyDialog policy={data.policy} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); reload(); }} />}
    </Card>
  );
}

function PolicyDialog({ policy, onClose, onSaved }) {
  const toast = useToast();
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  const [mode, setMode] = useState(policy.mode);
  const [windowed, setWindowed] = useState(!!policy.window);
  const [from, setFrom] = useState(policy.window?.from ?? "02:00");
  const [to, setTo] = useState(policy.window?.to ?? "05:00");
  const [tz, setTz] = useState(policy.window?.zone ?? zone);
  const [max, setMax] = useState(policy.maxAtOnce ?? 5);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await api("agents/updates", { method: "PUT", body: { mode, maxAtOnce: Number(max), window: windowed ? { from, to, zone: tz } : null } });
      toast("Update settings saved.");
      onSaved();
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };
  return (
    <Modal title="Agent updates" onClose={onClose}
           subtitle="When Setu has a newer installer than a computer runs, the computer installs it over the old one. Its registration, data sources and buffered readings stay; data collection pauses for about a minute."
           footer={<><button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy} onClick={save}>Save</button></>}>
      <div className="field">
        <span className="label">Updates</span>
        <div className="seg three" role="radiogroup" aria-label="Updates">
          <label><input type="radio" name="upd-mode" checked={mode === "automatic"} onChange={() => setMode("automatic")} />
            <b>Automatic</b><small>Computers update on their own.</small></label>
          <label><input type="radio" name="upd-mode" checked={mode === "manual"} onChange={() => setMode("manual")} />
            <b>Ask me</b><small>Start each one with Update now.</small></label>
          <label><input type="radio" name="upd-mode" checked={mode === "off"} onChange={() => setMode("off")} />
            <b>Off</b><small>Only Update now on a computer's page.</small></label>
        </div>
      </div>
      {mode === "automatic" && (
        <>
          <label className="check">
            <input type="checkbox" checked={windowed} onChange={(e) => setWindowed(e.target.checked)} /> Only during a daily window
          </label>
          {windowed && (
            <div className="grid3">
              <div className="field"><label htmlFor="upd-from">From</label>
                <input id="upd-from" type="time" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
              <div className="field"><label htmlFor="upd-to">To</label>
                <input id="upd-to" type="time" value={to} onChange={(e) => setTo(e.target.value)} /></div>
              <div className="field"><label htmlFor="upd-tz">Time zone</label>
                <input id="upd-tz" value={tz} onChange={(e) => setTz(e.target.value)} placeholder="Europe/Berlin" /></div>
            </div>
          )}
          <div className="field">
            <label htmlFor="upd-max">Computers updating at once</label>
            <input id="upd-max" type="number" min={1} max={100} value={max} onChange={(e) => setMax(e.target.value)} style={{ maxWidth: 120 }} />
            <span className="hint">The others wait their turn. A failed update is tried again after an hour, three times.</span>
          </div>
        </>
      )}
      <p className="muted small">A computer can refuse updates itself (<code>updates off</code> on the computer), and you can hold one on its page.</p>
      <Msg>{error}</Msg>
    </Modal>
  );
}

/** the Computer tab: installed version, the latest on Setu, the update under way, Update now / Hold, history */
export function SoftwareCard({ a, canUpdate, reload }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const u = a.update ?? {};
  const linux = a.details?.platform === "linux";
  const cmd = linux ? "sudo bimba-site-agent updates on" : "BIMBA-Site-Agent-Setup.exe updates on";
  const act = async (action, done) => {
    setBusy(true);
    setError(null);
    try {
      await api(`agents/${a.id}/update`, { method: "POST", body: { action } });
      toast(done);
      reload();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };
  const active = ACTIVE.includes(u.state);
  const online = a.status === "online";
  let state;
  if (!u.reported) {
    state = <><Status kind="warn">{u.available ? `${u.available} available: update by hand` : "Updated by hand"}</Status>
      <small className="muted">Agents before 1.10 cannot update themselves. Run the newer installer on {a.hostname} once; later versions arrive on their own.</small></>;
  } else if (active) {
    state = <><Status kind="warn">{STEP[u.state]} {u.target}</Status><small className="muted">Started {when(u.startedAt)} by {u.by === "automatic" ? "automatic updates" : u.by}{u.attempts > 1 ? ` (attempt ${u.attempts})` : ""}</small></>;
  } else if (u.state === "failed" && u.available) {
    state = <><Status kind="bad">Update to {u.target} failed</Status><small className="bad wrap-any">{u.error}</small>
      <small className="muted">{when(u.at)}{u.retries ? " · tried again automatically within the hour" : " · no more automatic attempts for this version"}</small></>;
  } else if (u.available) {
    state = <><Status kind="warn">{u.available} available</Status>
      <small className="muted">{u.hold ? `On hold since ${when(u.holdAt)} (${u.holdBy})` : !u.allowed ? `Refused on the computer itself: ${cmd} allows it again`
        : !u.supported ? u.why : "Installs as the organization's update settings say, or now with Update now"}</small></>;
  } else {
    state = <><Status kind="good">Up to date</Status>{u.latest && <small className="muted">The latest {KIND[u.kind] ?? "installer"} on Setu is {u.latest}</small>}</>;
  }
  const canNow = canUpdate && u.reported && u.supported && u.allowed && !u.hold && !active && u.available;
  return (
    <Card title="Software updates" subtitle="Setu's newer installers are run over the agent on this computer; it keeps its registration, data sources and buffered readings."
          action={canUpdate && u.reported && <div className="row-acts">
            {canNow && <button className="btn sm" disabled={busy} onClick={() => act("now", `${a.name} updates to ${u.available} ${online ? "within a minute" : "when it is back online"}.`)}>Update now to {u.available}</button>}
            {u.hold ? <button className="btn ghost sm" disabled={busy} onClick={() => act("release", "Hold released.")}>Release hold</button>
              : <button className="btn ghost sm" disabled={busy || active} onClick={() => act("hold", `Updates on hold for ${a.name}.`)}>Hold updates</button>}
          </div>}>
      <dl className="kv">
        <dt>Installed</dt><dd><span className="mono">{a.agentVersion}</span>{u.kind && <small className="muted">from the {KIND[u.kind]}</small>}</dd>
        <dt>State</dt><dd>{state}</dd>
        {u.requested && <><dt>Requested</dt><dd>Starts at the agent's next report{online ? " (within a minute)" : ", when it is back online"}</dd></>}
      </dl>
      <Msg>{error}</Msg>
      {(u.history ?? []).length > 0 && (
        <details className="adv upd-hist">
          <summary>Earlier updates ({u.history.length})</summary>
          <table>
            <thead><tr><th>Version</th><th>Result</th><th>When</th></tr></thead>
            <tbody>
              {u.history.map((h, i) => (
                <tr key={i}>
                  <td><span className="mono">{h.to}</span><small className="mono">from {h.from}</small></td>
                  <td style={{ minWidth: 0 }}><Status kind={h.outcome === "done" ? "good" : "bad"}>{h.outcome === "done" ? "Installed" : "Failed"}</Status>
                    {h.error && <small className="bad wrap-any">{h.error}</small>}
                    <small className="muted">{h.by === "automatic" ? "Automatic" : h.by}</small></td>
                  <td className="nowrap" title={full(h.at)}>{when(h.at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      )}
    </Card>
  );
}
