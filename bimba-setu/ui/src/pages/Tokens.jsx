import { useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Empty, full, Modal, Msg, PageHeader, SecretDialog, Status, useOrgPath, usePoll, useRemembered, useToast, when } from "../ui.jsx";

// Administration → Enrollment tokens (agents.manage). A token lets the site agent installer register computers into the
// organization without anyone's password. Named tokens are for roll-outs to many computers (MSI, apt, a prepared image);
// a single install uses the one-time token Add a computer copies (one computer, a few minutes), listed here as well.

export default function Tokens() {
  const { org } = useSession();
  const at = useOrgPath();
  const toast = useToast();
  const { data, error, reload } = usePoll("agents/tokens", 30_000);
  const [show, setShow] = useRemembered("tokens.show", { which: "active" });
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState(null);
  const [revoking, setRevoking] = useState(null);
  const all = data?.tokens ?? [];
  const active = all.filter((t) => t.status === "active");
  const rows = show.which === "active" ? active : all;

  return (
    <>
      <PageHeader title="Enrollment tokens"
                  subtitle={`Let the site agent installer register computers into ${org.name} without anyone's password. A token is shown only once, when it is created.`}>
        <button className="btn" onClick={() => setCreating(true)}>New token</button>
      </PageHeader>
      <p className="muted">For one computer you do not need a token here: <Link to={at("/agents")}>Site agents</Link> → Add a computer copies a
        one-time token (one computer, a few minutes). Create a named token for a roll-out to many computers.</p>
      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={show.which === "active"} className={show.which === "active" ? "on" : ""} onClick={() => setShow({ which: "active" })}>Active ({active.length})</button>
        <button role="tab" aria-selected={show.which === "all"} className={show.which === "all" ? "on" : ""} onClick={() => setShow({ which: "all" })}>All ({all.length})</button>
      </div>
      <Msg>{error}</Msg>
      <Card flush>
        <table>
          <thead><tr><th>Name</th><th>Token</th><th>Used</th><th>Expires</th><th>Status</th><th /></tr></thead>
          <tbody>
            {!data && <Empty cols={6}>Loading…</Empty>}
            {data && rows.length === 0 && <Empty cols={6}>{show.which === "active" ? "No active tokens." : "No enrollment tokens yet."}</Empty>}
            {rows.map((t) => (
              <tr key={t.id} className={t.status === "active" ? "" : "off"}>
                <td style={{ minWidth: 0 }}><b className="ellipsis" title={t.label}>{t.label || <span className="muted">–</span>}</b>
                  <small title={full(t.createdAt)}>{when(t.createdAt)}{t.createdBy ? ` by ${t.createdBy}` : ""}</small></td>
                <td className="mono">{t.prefix}…</td>
                <td>{t.uses}{t.maxUses ? ` of ${t.maxUses}` : ""}
                  <small title={full(t.lastUsedAt)}>{t.lastUsedAt ? `last ${when(t.lastUsedAt)}` : "never used"}</small></td>
                <td title={full(t.expiresAt)}>{t.expiresAt ? full(t.expiresAt) : <span className="muted">Never</span>}</td>
                <td><Status kind={t.status === "active" ? "good" : t.status === "revoked" ? "bad" : ""}>{t.status}</Status></td>
                <td className="r">{t.status === "active" && <button className="btn danger sm" onClick={() => setRevoking(t)}>Revoke…</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      {creating && <TokenDialog org={org} onClose={() => setCreating(false)}
                                onCreated={(t) => { setCreating(false); setSecret(t); reload(); }} />}
      {secret && (
        <SecretDialog title={`Enrollment token "${secret.label}"`} secret={secret.token} onClose={() => setSecret(null)}
                      subtitle={`Registers computers into ${org.name}${secret.maxUses ? `, at most ${secret.maxUses}` : ""}${secret.expiresAt ? `, until ${full(secret.expiresAt)}` : ""}.`}
                      note="It is shown only now. Paste it into the installer's “Enrollment token” field, or pass it with --token. Revoke it here when the roll-out is done." />
      )}
      {revoking && (
        <Confirm title={`Revoke token "${revoking.label}"?`} danger confirm="Revoke"
                 subtitle="Installers can no longer register computers with it. Agents it already registered keep working."
                 action={() => api(`agents/tokens/${revoking.id}/revoke`, { method: "POST" })}
                 onDone={() => { toast(`Token "${revoking.label}" revoked.`); setRevoking(null); reload(); }}
                 onClose={() => setRevoking(null)} />
      )}
    </>
  );
}

function TokenDialog({ org, onClose, onCreated }) {
  const [f, setF] = useState({ label: "", days: "30", maxUses: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      onCreated(await api("agents/tokens", { method: "POST", body: f }));
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };
  return (
    <Modal title={`New enrollment token for ${org.name}`} onClose={onClose}
           subtitle="Anyone who has the token can register computers into this organization until it expires, is used up or is revoked."
           footer={<><button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy || !f.label.trim()} onClick={save}>Create token</button></>}>
      <div className="grid2">
        <div className="field span2">
          <label htmlFor="t-l">Name</label>
          <input id="t-l" type="text" value={f.label} onChange={set("label")} maxLength={200} placeholder="e.g. Plant 2 roll-out" autoFocus />
        </div>
        <div className="field">
          <label htmlFor="t-d">Valid for (days)</label>
          <input id="t-d" type="number" min="1" max="3650" value={f.days} onChange={set("days")} />
          <span className="hint">Empty = never expires.</span>
        </div>
        <div className="field">
          <label htmlFor="t-u">Maximum computers</label>
          <input id="t-u" type="number" min="1" max="100000" value={f.maxUses} onChange={set("maxUses")} />
          <span className="hint">Empty = no limit.</span>
        </div>
      </div>
      <Msg>{error}</Msg>
    </Modal>
  );
}
