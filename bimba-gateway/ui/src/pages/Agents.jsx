import { useEffect, useState } from "react";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Empty, full, Modal, Msg, PageHeader, SecretDialog, Status, usePoll, useToast, when } from "../ui.jsx";

// Machines of the organization that registered with the BIMBA Site Agent (agents.view; revoke = agents.manage),
// the installer download (agents.view) and the organization's enrollment tokens (agents.manage). The installer
// asks the person installing for an enrollment token or their organization e-mail address and password.

const ACTIVE = ["online", "offline", "pending"];

export default function Agents() {
  const { can, org } = useSession();
  const toast = useToast();
  const { data, error, reload } = usePoll("agents", 15_000);
  const [q, setQ] = useState("");
  const [revoking, setRevoking] = useState(null);
  const [download, setDownload] = useState(false);
  const n = q.trim().toLowerCase();
  const rows = (data?.agents ?? []).filter((a) => !n || [a.name, a.hostname, a.site, a.ip, a.remoteIp].join(" ").toLowerCase().includes(n));

  return (
    <>
      <PageHeader title="Site agents" subtitle={`Machines registered to ${org.name} with the BIMBA Site Agent (its enrollment tokens or its users).`}>
        <button className="btn" onClick={() => setDownload(true)}>Download installer</button>
      </PageHeader>
      <div className="toolbar">
        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter by machine, site or IP" aria-label="Filter" />
      </div>
      <Msg>{error}</Msg>
      <Card flush>
        <table>
          <thead><tr><th>Machine</th><th>Site / OS</th><th>IP</th><th>SCADA software</th><th>Status</th><th /></tr></thead>
          <tbody>
            {!data && <Empty cols={6}>Loading…</Empty>}
            {data && rows.length === 0 && <Empty cols={6}>{data.agents.length ? "No machine matches the filter."
              : <>No machines registered yet. <button className="link" onClick={() => setDownload(true)}>Download the installer</button> and run it on a SCADA machine.</>}</Empty>}
            {rows.map((a) => {
              const sw = (a.scada || []).filter((s) => s.source !== "service").map((s) => `${s.product} ${s.version || ""}`.trim());
              return (
                  <tr key={a.id}>
                    <td><b>{a.name}</b><small>{a.hostname}</small></td>
                    <td>{a.site || <span className="muted">–</span>}<small>{a.os}</small></td>
                    <td>{a.ip}<small>seen from {a.remoteIp}</small></td>
                    <td>{sw.length ? sw.map((s) => <span className="chip" key={s} title={s}>{s}</span>) : <span className="muted">None detected</span>}</td>
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
                 subtitle={`${org.name}. The agent on ${revoking.hostname} stops reporting. Installing it again registers the machine again.`}
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
           subtitle="Run it on any Windows SCADA machine of your organization. It registers the machine here and then reports every minute that it is online."
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
              <b>Setup wizard (.exe)</b><small>Someone installs it on the machine and signs in with a token or their e-mail address.</small></label>
            <label><input type="radio" name="i-kind" checked={kind === "msi"} onChange={() => setKind("msi")} />
              <b>MSI package</b><small>Unattended roll-out with Intune, Configuration Manager or a startup script.</small></label>
          </div>

          <div className="field">
            <label htmlFor="i-srv">Gateway address the machines use</label>
            <input id="i-srv" type="url" value={server} onChange={(e) => setServer(e.target.value)} spellCheck="false" autoComplete="off" />
            <span className="hint">An address the SCADA machines can reach (host name or IP of this gateway){info.httpsPort ? `, HTTPS port ${info.httpsPort}` : ""}.
              {kind === "exe" && (signed ? " The installer is code-signed and cannot be changed, so the person installing enters this address."
                : " Built into the downloaded installer, so nobody has to type it.")}</span>
          </div>
          {!valid && server && <Msg>Enter only the address, for example https://gateway.example.com:8443 (no path).</Msg>}
          {valid && LOCAL.test(host) && <Msg kind="warn">{host} only works on this computer. Other machines need this gateway's network name or IP address.</Msg>}
          {valid && plainHttp && <Msg kind="warn">Plain HTTP sends the token or password unencrypted, and machines cannot get their certificate. Use the HTTPS address.</Msg>}
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
                    Best for many machines: no personal password on the SCADA machine.</span></li>
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
              ? <>Self-signed: machines trust it by its fingerprint{kind === "msi" ? " (TRUSTCERT)" : " (the installer shows it to confirm)"}.<span className="mono"> {info.tlsFingerprint}</span></>
              : <>Issued by {tls.issuer}, valid until {full(tls.notAfter)}. Machines trust it like a web site when Windows trusts its issuer.</>}</dd>
            {tls.error && <><dt>Certificate problem</dt><dd className="bad">{tls.error} (the self-signed certificate is used instead)</dd></>}
            <dt>Machine sign-in</dt>
            <dd>{info.agentCertificates ? `Each machine gets its own client certificate (mutual TLS, ${info.certificateDays} days, renewed automatically); its key never leaves the machine.`
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
            subtitle={`Let the installer register machines into ${org.name} without a personal password. A token is shown only once, when it is created.`}
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
                      subtitle={`Registers machines into ${org.name}${secret.maxUses ? `, at most ${secret.maxUses}` : ""}${secret.expiresAt ? `, until ${full(secret.expiresAt)}` : ""}.`}
                      note="It is shown only now. Paste it into the installer's “Enrollment token” field, or pass it with --token. Revoke it here when the roll-out is done." />
      )}
      {revoking && (
        <Confirm title={`Revoke token "${revoking.label}"?`} danger confirm="Revoke"
                 subtitle="Installers can no longer register machines with it. Machines it already registered keep working."
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
           subtitle="Anyone who has the token can register machines into this organization until it expires, is used up or is revoked."
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
          <label htmlFor="t-u">Maximum machines</label>
          <input id="t-u" type="number" min="1" max="100000" value={f.maxUses} onChange={set("maxUses")} />
          <span className="hint">Empty = no limit.</span>
        </div>
      </div>
      <Msg>{error}</Msg>
    </Modal>
  );
}
