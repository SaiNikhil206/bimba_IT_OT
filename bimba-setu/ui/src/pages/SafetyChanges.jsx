import { useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api.js";
import { Card, full, Modal, Msg, Status, useToast, when } from "../ui.jsx";

// Four-eyes on safety configuration (BRD 4.5): a change to a data source's Allow machine commands switch, or to a tag's
// writable flag, write limits, approval setting (and, while it is writable, its address, data type, scaling or unit) is
// saved as a change request. It takes effect once another person with Approve machine commands there approves it.
// Shown on a site agent's Data sources tab and on the Machines page. Server side: agents/SafetyChanges.java,
// agents/SafetyConfig.java (web/SafetyChangesController).

const FIELD = {
  writable: "Writable", writeMin: "Lowest value", writeMax: "Highest value", writeApproval: "Second person approves each command",
  writeAddress: "Command topic", address: "Address", dataType: "Data type", rawLow: "Raw low", rawHigh: "Raw high",
  scaledLow: "Scaled low", scaledHigh: "Scaled high", sourceUnit: "Device unit", unit: "Unit",
};
const ORDER = Object.keys(FIELD);

const show = (v) => (v === undefined || v === null || v === "" ? "–" : typeof v === "boolean" ? (v ? "on" : "off")
  : typeof v === "number" ? v.toLocaleString(undefined, { maximumFractionDigits: 6 }) : String(v));

/** "expires in 6 days" (when() only says how long ago) */
function left(iso) {
  const h = (Date.parse(iso) - Date.now()) / 3_600_000;
  return h <= 1 ? "expires within the hour" : h < 48 ? `expires in ${Math.ceil(h)} hours` : `expires in ${Math.ceil(h / 24)} days`;
}

const STATE = {
  pending: ["warn", "Waiting for approval"], approved: ["good", "Approved"], rejected: ["bad", "Rejected"],
  withdrawn: ["", "Withdrawn"], expired: ["bad", "Expired"], outdated: ["bad", "Out of date"],
};

/** one change in words: "Allow machine commands: off → on", "Press-1 / speed: Highest value 1,500 → 3,000" */
function ChangeLine({ c }) {
  if (c.kind === "writes") return <li><b>Allow machine commands</b>: {show(c.before)} → {show(c.after)}</li>;
  const keys = ORDER.filter((k) => show(c.before?.[k]) !== show(c.after?.[k]));
  return (
    <li>
      <b>{c.machine} / {c.label || c.tag}</b>:{" "}
      {keys.map((k, i) => <span key={k}>{i ? "; " : ""}{FIELD[k]} {show(c.before?.[k])} → {show(c.after?.[k])}</span>)}
    </li>
  );
}

/** the open requests (and, with history, the decided ones too) of a list answer of /safety-changes */
export function SafetyRequests({ data, reload, title, subtitle, sourceLink, history }) {
  const toast = useToast();
  const [rejecting, setRejecting] = useState(null);
  const [busy, setBusy] = useState(null);
  const list = (data?.requests ?? []).filter((r) => history || r.state === "pending");
  if (!list.length) return null;
  const act = async (r, action) => {
    setBusy(r.id);
    try {
      await api(`safety-changes/${r.id}/${action}`, { method: "POST" });
      toast(action === "approve" ? `Approved: the machine command settings of ${r.source} apply now.` : "Change request withdrawn.");
    } catch (e) {
      toast(e.message);
    } finally {
      setBusy(null);
      reload();
    }
  };
  return (
    <Card flush title={title ?? `Machine command settings waiting for approval (${list.filter((r) => r.state === "pending").length})`}
          subtitle={subtitle ?? `A second person approves each change to Allow machine commands, a writable tag or its limits, within ${data.days} days. Until then the old settings apply. Whoever proposed it cannot approve it.`}>
      <div className="sreq-list">
        {list.map((r) => {
          const [kind, label] = STATE[r.state] ?? ["", r.state];
          return (
            <div key={r.id} className="sreq">
              <div className="sreq-main">
                <b className="ellipsis" title={`${r.source} on ${r.agent}`}>
                  {sourceLink ? <Link to={sourceLink(r)}>{r.source}</Link> : r.source}<span className="muted"> on {r.agent}</span>
                </b>
                <ul className="sreq-changes">{r.changes.map((c, i) => <ChangeLine key={i} c={c} />)}</ul>
                <small className="ellipsis" title={`${r.requestedBy} · ${full(r.requestedAt)}`}>
                  Proposed by {r.mine ? "you" : r.requestedByName || r.requestedBy} · {when(r.requestedAt)}
                  {r.state === "pending" && ` · ${left(r.expiresAt)}`}
                  {r.decidedBy && r.state !== "pending" && ` · ${label.toLowerCase()} by ${r.decidedByName || r.decidedBy}`}
                </small>
                {r.why && <small className={r.state === "rejected" || r.state === "outdated" ? "warnc" : ""}>{r.why}</small>}
              </div>
              <div className="sreq-side">
                <Status kind={kind}>{label}</Status>
                <div className="row-acts">
                  {r.can.reject && <button className="btn ghost sm" disabled={busy === r.id} onClick={() => setRejecting(r)}>Reject…</button>}
                  {r.can.approve && <button className="btn sm" disabled={busy === r.id} onClick={() => act(r, "approve")}>Approve</button>}
                  {r.can.withdraw && <button className="btn ghost sm" disabled={busy === r.id} onClick={() => act(r, "withdraw")}>Withdraw</button>}
                  {r.state === "pending" && r.mine && !r.can.approve && <small className="muted">waits for another person</small>}
                </div>
              </div>
            </div>
          );
        })}
      </div>
      {rejecting && <RejectChange r={rejecting} onClose={() => setRejecting(null)} onDone={() => {
        setRejecting(null);
        toast("Change request rejected. The old settings stay.");
        reload();
      }} />}
    </Card>
  );
}

function RejectChange({ r, onClose, onDone }) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const go = async () => {
    setBusy(true);
    try {
      await api(`safety-changes/${r.id}/reject`, { method: "POST", body: { reason } });
      onDone();
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };
  return (
    <Modal title={`Reject the change to ${r.source}`} subtitle={`Proposed by ${r.requestedByName || r.requestedBy}.`} onClose={onClose}
           footer={<><button className="btn ghost" onClick={onClose}>Cancel</button><button className="btn danger" disabled={busy} onClick={go}>Reject</button></>}>
      <ul className="sreq-changes">{r.changes.map((c, i) => <ChangeLine key={i} c={c} />)}</ul>
      <div className="field">
        <label htmlFor="sr-why">Why (they see it)</label>
        <input id="sr-why" type="text" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
      </div>
      <Msg>{error}</Msg>
    </Modal>
  );
}
