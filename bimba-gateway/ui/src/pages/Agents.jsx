import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Empty, Flow, full, Modal, Msg, PageHeader, SecretDialog, Status, useOrgPath, usePoll, useToast, when } from "../ui.jsx";
import AgentPage from "./Agent.jsx";

// The organization's site agents (agents.view; revoke = agents.manage), the installer download (agents.view) and the
// organization's enrollment tokens (agents.manage). The installer asks the person installing for an enrollment token
// or their organization e-mail address and password.
// Words: a site agent is the BIMBA service installed on one Windows SCADA computer; it reads data sources (OPC UA,
// Modbus...) and sends their readings as machines, which pipelines deliver. One computer = one agent.

const ACTIVE = ["online", "offline", "pending"];
const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

export default function Agents() {
  const key = useParams()["*"];
  return key ? <AgentPage id={key} key={key} /> : <AgentList />;
}

function AgentList() {
  const { can, org } = useSession();
  const at = useOrgPath();
  const toast = useToast();
  const { data, error, reload } = usePoll("agents", 15_000);
  const [q, setQ] = useState("");
  const [revoking, setRevoking] = useState(null);
  const [download, setDownload] = useState(false);
  const n = q.trim().toLowerCase();
  const all = data?.agents ?? [];
  const rows = all.filter((a) => !n || [a.name, a.hostname, a.site, a.ip, a.remoteIp, ...(a.machines ?? [])].join(" ").toLowerCase().includes(n));
  const live = all.filter((a) => ACTIVE.includes(a.status));
  const online = live.filter((a) => a.status === "online").length;
  const sources = live.reduce((t, a) => t + (a.sources ?? 0), 0);
  const machines = live.reduce((t, a) => t + (a.machines?.length ?? 0), 0);

  return (
    <>
      <PageHeader title="Site agents" subtitle={`A site agent is the BIMBA service on a Windows SCADA computer of ${org.name}. It reads the computer's data sources and sends their data as machines.`}>
        <button className="btn" onClick={() => setDownload(true)}>Download installer</button>
      </PageHeader>
      <Flow label="How site agents send data" steps={[
        { key: "a", label: "1 · Install", value: live.length ? plural(live.length, "site agent") : "No agent yet",
          sub: live.length ? `on ${plural(live.length, "computer")}, ${online} online` : "On each SCADA computer", onClick: () => setDownload(true) },
        { key: "s", label: "2 · Data sources", value: sources ? plural(sources, "data source") : "None yet", sub: "Added per agent" },
        { key: "m", label: "3 · Machines", value: machines ? plural(machines, "machine") : "None yet", sub: "One per source, or per device" },
        { key: "p", label: "4 · Deliver", value: "Pipelines", sub: "Machines → destinations", ...(can("gateway.view") ? { to: at("/pipelines") } : {}) },
      ]} />
      <div className="toolbar">
        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter by agent, computer, site, IP or machine" aria-label="Filter" />
      </div>
      <Msg>{error}</Msg>
      <Card flush>
        <table>
          <thead><tr><th>Site agent</th><th>On computer</th><th>Sends machines</th><th>Status</th><th /></tr></thead>
          <tbody>
            {!data && <Empty cols={5}>Loading…</Empty>}
            {data && rows.length === 0 && <Empty cols={5}>{all.length ? "No site agent matches the filter."
              : <>No site agents yet. <button className="link" onClick={() => setDownload(true)}>Download the installer</button> and run it on a SCADA computer.</>}</Empty>}
            {rows.map((a) => {
              const ms = a.machines ?? [];
              return (
                  <tr key={a.id}>
                    <td style={{ minWidth: 0 }}><Link className="rowlink ellipsis" to={at(`/agents/${a.id}`)} title={a.name}><b>{a.name}</b></Link>
                      <small>agent {a.agentVersion || "–"}</small></td>
                    <td style={{ minWidth: 0 }}><span className="ellipsis" title={a.hostname}>{a.hostname}</span>
                      <small className="ellipsis" title={[a.site, a.ip, a.os].filter(Boolean).join(" · ")}>{[a.site, a.ip].filter(Boolean).join(" · ") || a.os}</small></td>
                    <td style={{ minWidth: 0 }}>
                      {ms.length ? <>{plural(ms.length, "machine")} from {plural(a.sources, "data source")}
                        <small className="clamp2" title={ms.join(", ")}>{ms.join(", ")}</small></>
                        : <><span className="muted">No machines yet</span>
                          {a.sources ? <small>{plural(a.sources, "data source")}, no device picked yet</small>
                            : ACTIVE.includes(a.status) && <small><Link to={at(`/agents/${a.id}`)}>Add a data source</Link></small>}</>}
                    </td>
                    <td>
                      <Status kind={a.status === "online" ? "good" : a.status === "offline" ? "bad" : ""}>{a.status}</Status>
                      <small title={full(a.lastSeen)}>{when(a.lastSeen)}</small>
                      <small title={a.signIn === "certificate" ? `Mutual TLS, certificate valid until ${full(a.certificateNotAfter)} (renewed automatically)`
                        : "Signs in with its enrollment secret until it gets a certificate (over HTTPS)"}>
                        {a.signIn === "certificate" ? "certificate" : "secret"}</small>
                    </td>
                    <td className="r">
                      {can("agents.manage") && ACTIVE.includes(a.status) && <button className="btn danger sm" onClick={() => setRevoking(a)}>Revoke…</button>}
                    </td>
                  </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
      {can("agents.manage") && <Tokens />}
      {download && <InstallerDialog onClose={() => setDownload(false)} canTokens={can("agents.manage")} />}
      {revoking && (
        <Confirm title={`Revoke ${revoking.name}?`} danger confirm="Revoke"
                 subtitle={`${org.name}. The agent on the computer ${revoking.hostname} stops and its machines stop sending data. Installing it again registers the computer again.`}
                 action={() => api(`agents/${revoking.id}/revoke`, { method: "POST" })}
                 onDone={() => { toast(`${revoking.name} revoked.`); setRevoking(null); reload(); }}
                 onClose={() => setRevoking(null)} />
      )}
    </>
  );
}

// ------------------------------------------------------------------ installer download

const LOCAL = /^(localhost|127\.\d+\.\d+\.\d+|\[?::1\]?)$/i;

function CopyLine({ text }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try { await navigator.clipboard.writeText(text); setCopied(true); } catch { setCopied(false); }
  };
  return (
    <div className="secret neutral"><code className="cmd">{text}</code>
      <button className="btn ghost sm" onClick={copy}>{copied ? "Copied" : "Copy"}</button></div>
  );
}

function InstallerDialog({ onClose, canTokens }) {
  const [info, setInfo] = useState(null);
  const [error, setError] = useState(null);
  const [server, setServer] = useState("");
  const [kind, setKind] = useState("exe");

  useEffect(() => {
    api("agents/installer").then((i) => {
      setInfo(i);
      const host = window.location.hostname.includes(":") ? `[${window.location.hostname}]` : window.location.hostname;
      setServer(i.httpsPort ? `https://${host}:${i.httpsPort}` : window.location.origin);
    }).catch((e) => setError(e.message));
  }, []);

  const addr = server.trim().replace(/\/$/, "");
  const valid = /^https?:\/\/[^/\s?#@"]+$/i.test(addr);
  let host = "";
  try { host = new URL(addr).hostname; } catch { /* invalid */ }
  const plainHttp = /^http:\/\//i.test(addr) && !LOCAL.test(host);
  const tls = info?.tls ?? {};
  const pin = tls.enabled && tls.selfSigned && /^https:/i.test(addr) ? (info.tlsFingerprint || "").replace(/:/g, "").toLowerCase() : "";
  const file = info?.[kind];
  const meta = (info?.release?.files ?? []).find((f) => f.name === file?.fileName && f.sha256 === file?.sha256);
  const signed = kind === "exe" ? file?.signed : meta?.signed;
  const href = `/api/gw/agents/installer/download?kind=${kind}` + (kind === "exe" && !signed ? `&server=${encodeURIComponent(addr)}` : "");
  const exeCmd = `BIMBA-Site-Agent-Setup.exe install --server ${addr} --token <enrollment token> --site "<site>"` + (pin ? ` --trust-cert ${pin}` : "");
  const msiCmd = `msiexec /i BIMBA-Site-Agent.msi /qn SERVER=${addr} TOKEN=<enrollment token> SITE="<site>"` + (pin ? ` TRUSTCERT=${pin}` : "");
  const none = info && !info.exe?.available && !info.msi?.available;

  return (
    <Modal wide title="Download the site agent installer" onClose={onClose}
           subtitle="Run it on any Windows SCADA computer of your organization. It installs the site agent, which registers the computer here and then reports every minute that it is online."
           footer={<><button className="btn ghost" onClick={onClose}>Close</button>
             {file?.available && valid
               ? <a className="btn" href={href} download={file.fileName}>Download {kind === "msi" ? "MSI" : "installer"}</a>
               : <button className="btn" disabled>Download</button>}</>}>
      <Msg>{error}</Msg>
      {!info && !error && <p className="muted">Loading…</p>}
      {none && <Msg>The installers are not on this gateway. Build them with scripts\build-site-agent.ps1 (served from site-agent\dist, mounted at /installers).</Msg>}
      {info && !info.registrationEnabled && <Msg kind="warn">Site agent registration is turned off on this gateway, so installs will fail until it is turned on.</Msg>}
      {info && !none && (
        <>
          <div className="seg two" role="radiogroup" aria-label="Kind of installer">
            <label><input type="radio" name="i-kind" checked={kind === "exe"} onChange={() => setKind("exe")} />
              <b>Setup wizard (.exe)</b><small>Someone installs it on the computer and signs in with a token or their e-mail address.</small></label>
            <label><input type="radio" name="i-kind" checked={kind === "msi"} onChange={() => setKind("msi")} />
              <b>MSI package</b><small>Unattended roll-out with Intune, Configuration Manager or a startup script.</small></label>
          </div>

          <div className="field">
            <label htmlFor="i-srv">Gateway address the SCADA computers use</label>
            <input id="i-srv" type="url" value={server} onChange={(e) => setServer(e.target.value)} spellCheck="false" autoComplete="off" />
            <span className="hint">An address the SCADA computers can reach (host name or IP of this gateway){info.httpsPort ? `, HTTPS port ${info.httpsPort}` : ""}.
              {kind === "exe" && (signed ? " The installer is code-signed and cannot be changed, so the person installing enters this address."
                : " Built into the downloaded installer, so nobody has to type it.")}</span>
          </div>
          {!valid && server && <Msg>Enter only the address, for example https://gateway.example.com:8443 (no path).</Msg>}
          {valid && LOCAL.test(host) && <Msg kind="warn">{host} only works on this computer. Other computers need this gateway's network name or IP address.</Msg>}
          {valid && plainHttp && <Msg kind="warn">Plain HTTP sends the token or password unencrypted, and agents cannot get their certificate. Use the HTTPS address.</Msg>}
          {valid && plainHttp && info.requireHttps && <Msg>This gateway only accepts registrations over HTTPS.</Msg>}

          {!file?.available && <Msg>{file?.fileName} is not on this gateway. Build it with scripts\build-site-agent.ps1.</Msg>}
          {file?.available && (signed
            ? <Msg kind={meta && meta.status !== "Valid" ? "warn" : "good"}>Code-signed{meta?.signer ? ` by ${meta.signer}` : ""}.{meta && meta.status !== "Valid" ? " The signing certificate is not trusted by the build machine (test certificate?), so Windows treats the file as from an unknown publisher." : ""}</Msg>
            : <Msg kind="warn">Not code-signed: Windows SmartScreen and application control policies may warn about or block it. Sign release builds (see scripts\build-site-agent.ps1).</Msg>)}

          {kind === "exe" ? (
            <>
              <div className="field">
                <span className="label">The person installing needs one of these</span>
                <ol className="steps">
                  <li><b>An enrollment token</b><span>{canTokens ? "Create one under Enrollment tokens on this page. " : "Ask an administrator of your organization for one. "}
                    Best for many computers: no personal password on the SCADA computer.</span></li>
                  <li><b>Their organization e-mail address and password</b><span>A user of this organization. If they belong to several
                    organizations, the installer asks which one.</span></li>
                </ol>
              </div>
              <div className="field">
                <span className="label">Silent install (from an administrator prompt)</span>
                <CopyLine text={exeCmd} />
                <span className="hint">Or --email &lt;e-mail&gt; instead of --token (the password is asked for).</span>
              </div>
            </>
          ) : (
            <div className="field">
              <span className="label">Install command (Intune Win32 app, Configuration Manager, or a Group Policy startup script)</span>
              <CopyLine text={msiCmd} />
              <span className="hint">Needs an enrollment token{canTokens ? " (Enrollment tokens on this page)" : ""}; unattended installs never take a password.
                Upgrades with a newer MSI keep the registration and need no token. Remove with msiexec /x BIMBA-Site-Agent.msi /qn or from Apps.</span>
            </div>
          )}

          <dl className="kv">
            <dt>Gateway certificate</dt>
            <dd>{!tls.enabled ? "HTTPS is off" : tls.selfSigned
              ? <>Self-signed: agents trust it by its fingerprint{kind === "msi" ? " (TRUSTCERT)" : " (the installer shows it to confirm)"}.<span className="mono"> {info.tlsFingerprint}</span></>
              : <>Issued by {tls.issuer}, valid until {full(tls.notAfter)}. Agents trust it like a web site when Windows trusts its issuer.</>}</dd>
            {tls.error && <><dt>Certificate problem</dt><dd className="bad">{tls.error} (the self-signed certificate is used instead)</dd></>}
            <dt>Agent sign-in</dt>
            <dd>{info.agentCertificates ? `Each agent gets its own client certificate (mutual TLS, ${info.certificateDays} days, renewed automatically); its key never leaves the computer.`
              : "Agent secret (client certificates are turned off on this gateway)."}</dd>
            {file?.available && (
              <>
                <dt>File</dt><dd>{file.fileName} · {(file.size / 1024).toFixed(0)} KB{info.release?.version ? ` · version ${info.release.version}` : ""} · built {full(file.builtAt)}</dd>
                <dt>SHA-256</dt><dd className="mono">{file.sha256}</dd>
              </>
            )}
          </dl>
        </>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------------ enrollment tokens

function Tokens() {
  const { org } = useSession();
  const toast = useToast();
  const { data, error, reload } = usePoll("agents/tokens", 30_000);
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState(null);
  const [revoking, setRevoking] = useState(null);

  return (
    <>
      <Card flush title="Enrollment tokens"
            subtitle={`Let the installer register computers into ${org.name} without a personal password. A token is shown only once, when it is created.`}
            action={<button className="btn sm" onClick={() => setCreating(true)}>New token</button>}>
        <Msg>{error}</Msg>
        <table>
          <thead><tr><th>Name</th><th>Token</th><th>Used</th><th>Expires</th><th>Status</th><th /></tr></thead>
          <tbody>
            {!data && <Empty cols={6}>Loading…</Empty>}
            {data && data.tokens.length === 0 && <Empty cols={6}>No enrollment tokens yet.</Empty>}
            {(data?.tokens ?? []).map((t) => (
              <tr key={t.id}>
                <td><b className="ellipsis" title={t.label}>{t.label || <span className="muted">–</span>}</b>
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
