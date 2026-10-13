import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Empty, FilterSelect, full, Modal, Msg, PageHeader, SortTh, sortRows, Status, useOrgPath, usePoll, useRemembered, Verdict, when } from "../ui.jsx";
import AgentPage from "./Agent.jsx";

// The organization's site agents (agents.view) and adding a computer (installer download + a one-time enrollment token,
// agents.manage for the token). Named tokens for roll-outs: Administration → Enrollment tokens (Tokens.jsx). Removing an agent is on
// its own page (Computer tab).
// Words: a site agent is the BIMBA service installed on one SCADA computer (Windows, Linux or Raspberry Pi); it reads
// data sources (OPC UA, Modbus...) and sends their readings as machines, which pipelines deliver. One computer = one agent.

const ACTIVE = ["online", "offline", "pending"];
const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;
/** the computer's operating system family, from the agent's report */
const platform = (a) => (/windows/i.test(a.os ?? "") ? "windows" : a.os ? "linux" : "");
/** what each column sorts by; Status: not reporting first, then the longest silent */
const SORT = {
  name: (a) => a.name,
  sends: (a) => a.machines?.length ?? 0,
  status: (a) => (a.status === "online" ? 2 : ACTIVE.includes(a.status) ? 1 : 3) * 1e13 + (a.lastSeen ? Date.parse(a.lastSeen) : 0),
};

export default function Agents() {
  const key = useParams()["*"];
  return key ? <AgentPage id={key} key={key} /> : <AgentList />;
}

function AgentList() {
  const { can, org } = useSession();
  const at = useOrgPath();
  const { data, error } = usePoll("agents", 15_000);
  const [q, setQ] = useState("");
  const [show, setShow] = useState("active");          // active | removed
  const [adding, setAdding] = useState(false);
  const [f, setF] = useRemembered("agents.filter", { status: "", os: "", site: "", data: "" });
  const [sort, setSort] = useRemembered("agents.sort", { key: "name", dir: 1 });
  const n = q.trim().toLowerCase();
  const all = data?.agents ?? [];
  const live = all.filter((a) => ACTIVE.includes(a.status));
  const removed = all.filter((a) => !ACTIVE.includes(a.status));
  const shown = show === "active" ? live : removed;
  const sites = [...new Set(all.map((a) => a.site).filter(Boolean))].sort();
  const oses = [...new Set(all.map(platform).filter(Boolean))];
  const filtered = shown.filter((a) => (!n || [a.name, a.hostname, a.site, a.ip, a.remoteIp, ...(a.machines ?? [])].join(" ").toLowerCase().includes(n))
    && (!f.status || (f.status === "online") === (a.status === "online"))
    && (!f.os || platform(a) === f.os)
    && (!f.site || a.site === f.site)
    && (!f.data || (f.data === "sending") === (a.machines?.length > 0)));
  const rows = sortRows(filtered, sort, SORT);
  const filtering = n || f.status || f.os || f.site || f.data;
  const offline = live.filter((a) => a.status !== "online");
  const sources = live.reduce((t, a) => t + (a.sources ?? 0), 0);
  const machines = live.reduce((t, a) => t + (a.machines?.length ?? 0), 0);

  return (
    <>
      <PageHeader title="Site agents" subtitle={`The BIMBA agent on each SCADA computer of ${org.name}. It reads the computer's PLCs, servers and databases and sends their data to Setu.`}>
        <button className="btn" onClick={() => setAdding(true)}>Add a computer</button>
      </PageHeader>
      {data && (live.length === 0 ? (
        <Card title="How it works">
          <ol className="steps">
            <li><b>Add a computer</b><span>Install the BIMBA Site Agent on a SCADA computer (Windows, Linux or Raspberry Pi). It registers the computer here.</span></li>
            <li><b>Add its data sources</b><span>The PLCs, OPC servers or databases the agent reads. Scan the network finds them for you.</span></li>
            <li><b>Choose where the data goes</b><span>Each data source sends its data as a machine; pipelines deliver machines to your systems.</span></li>
          </ol>
          <p><button className="btn" onClick={() => setAdding(true)}>Add a computer</button></p>
        </Card>
      ) : offline.length ? (
        <Verdict kind="bad" title={`${offline.length} of ${plural(live.length, "computer")} not reporting`}>
          {offline.map((a) => a.name).join(", ")}. The others send {plural(machines, "machine")} from {plural(sources, "data source")}.
        </Verdict>
      ) : (
        <Verdict kind="good" title={live.length === 1 ? "The computer is online" : `All ${live.length} computers are online`}>
          They send {plural(machines, "machine")} from {plural(sources, "data source")}.
          {can("gateway.view") && <> Choose where the data goes in <Link to={at("/pipelines")}>Pipelines</Link>.</>}
        </Verdict>
      ))}
      {removed.length > 0 && (
        <div className="tabs" role="tablist">
          <button role="tab" aria-selected={show === "active"} className={show === "active" ? "on" : ""} onClick={() => setShow("active")}>Active ({live.length})</button>
          <button role="tab" aria-selected={show === "removed"} className={show === "removed" ? "on" : ""} onClick={() => setShow("removed")}>Removed ({removed.length})</button>
        </div>
      )}
      {all.length > 0 && (
        <div className="toolbar">
          <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search name, computer, site, IP or machine" aria-label="Search" />
          {show === "active" && <FilterSelect label="Status" value={f.status} onChange={(v) => setF({ ...f, status: v })}
                                              options={[["online", "Online"], ["offline", "Not reporting"]]} />}
          {oses.length > 1 && <FilterSelect label="Computer" value={f.os} onChange={(v) => setF({ ...f, os: v })}
                                            options={[["windows", "Windows"], ["linux", "Linux / Pi"]]} />}
          {sites.length > 1 && <FilterSelect label="Site" value={f.site} onChange={(v) => setF({ ...f, site: v })} options={sites.map((x) => [x, x])} />}
          <FilterSelect label="Data" value={f.data} onChange={(v) => setF({ ...f, data: v })} options={[["sending", "Sends machines"], ["none", "Nothing yet"]]} />
          {filtering && <button className="btn ghost sm" onClick={() => { setQ(""); setF({ status: "", os: "", site: "", data: "" }); }}>Clear filters</button>}
          {filtering && <span className="muted small count">{rows.length} of {shown.length}</span>}
        </div>
      )}
      <Msg>{error}</Msg>
      {(all.length > 0 || !data) && (
        <Card flush>
          <table>
            <thead><tr>
              <SortTh k="name" sort={sort} setSort={setSort}>Computer</SortTh>
              <SortTh k="sends" sort={sort} setSort={setSort}>Sends</SortTh>
              <SortTh k="status" sort={sort} setSort={setSort}>Status</SortTh>
            </tr></thead>
            <tbody>
              {!data && <Empty cols={3}>Loading…</Empty>}
              {data && rows.length === 0 && <Empty cols={3}>{filtering ? "No site agent matches the filters." : show === "removed" ? "No removed agents." : "No active site agents."}</Empty>}
              {rows.map((a) => {
                const ms = a.machines ?? [];
                const active = ACTIVE.includes(a.status);
                return (
                  <tr key={a.id} className={active ? "" : "off"}>
                    <td style={{ minWidth: 0 }}>
                      <Link className="rowlink ellipsis" to={at(`/agents/${a.id}`)} title={a.name}><b>{a.name}</b></Link>
                      <small className="ellipsis" title={[a.hostname, a.site, a.ip, a.os].filter(Boolean).join(" · ")}>{[a.hostname !== a.name && a.hostname, a.site, a.ip].filter(Boolean).join(" · ") || a.os}</small>
                    </td>
                    <td style={{ minWidth: 0 }}>
                      {ms.length ? <>{plural(ms.length, "machine")}<small className="clamp2" title={ms.join(", ")}>{ms.join(", ")}</small></>
                        : <><span className="muted">Nothing yet</span>
                          {active && <small><Link to={at(`/agents/${a.id}`)}>{a.sources ? "Pick its devices" : "Add a data source"}</Link></small>}</>}
                    </td>
                    <td>
                      <Status kind={a.status === "online" ? "good" : a.status === "offline" ? "bad" : ""}>{active ? a.status : "removed"}</Status>
                      <small title={full(a.lastSeen)}>{a.lastSeen ? when(a.lastSeen) : "never reported"}</small>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Card>
      )}
      {adding && <AddComputer onClose={() => setAdding(false)} canTokens={can("agents.manage")} />}
    </>
  );
}

// ------------------------------------------------------------------ add a computer (installer download)

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

/**
 * Add a computer, in four steps: which computer (Windows / Linux, kind of package), the Setu address it uses, how it
 * registers (Copy token: a one-time token, one computer, a few minutes, put into the commands), then download and install. The file's
 * signature, certificate and checksum are under Technical details.
 */
function AddComputer({ onClose, canTokens }) {
  const [info, setInfo] = useState(null);
  const [error, setError] = useState(null);
  const [server, setServer] = useState("");
  const [kind, setKind] = useState("exe");
  const [token, setToken] = useState(null);           // the one-time token copied here: {token, id, endsAt}
  const [making, setMaking] = useState(false);
  const [copied, setCopied] = useState(false);
  const [used, setUsed] = useState(false);            // a computer registered with it
  const [now, setNow] = useState(() => Date.now());
  const at = useOrgPath();
  const linux = kind === "deb" || kind === "tar";
  const setPlatform = (p) => setKind(p === "linux" ? "deb" : "exe");

  useEffect(() => {
    api("agents/installer").then((i) => {
      setInfo(i);
      const host = window.location.hostname.includes(":") ? `[${window.location.hostname}]` : window.location.hostname;
      setServer(i.httpsPort ? `https://${host}:${i.httpsPort}` : window.location.origin);
    }).catch((e) => setError(e.message));
  }, []);

  /** Copy token: a new one-time token (one computer, a few minutes), put on the clipboard and into the commands */
  const copyToken = async () => {
    setMaking(true);
    setError(null);
    setUsed(false);
    try {
      const t = await api("agents/tokens/one-time", { method: "POST" });
      const life = Date.parse(t.expiresAt) - Date.parse(t.now ?? t.createdAt);     // Setu's clock decides, not this one
      setToken({ ...t, endsAt: Date.now() + life });
      setNow(Date.now());
      try {
        await navigator.clipboard.writeText(t.token);
        setCopied(true);
      } catch {
        setCopied(false);                                // no clipboard (plain http): it is shown to copy by hand
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setMaking(false);
    }
  };
  useEffect(() => {                                      // the countdown
    if (!token) return undefined;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [token]);
  const left = token ? Math.max(0, Math.ceil((token.endsAt - now) / 1000)) : 0;
  const live = !!token && left > 0 && !used;
  useEffect(() => {                                      // did a computer register with it?
    if (!live) return undefined;
    const t = setInterval(() => {
      api("agents/tokens", { background: true })
        .then((r) => { if ((r.tokens ?? []).find((x) => x.id === token.id)?.uses > 0) setUsed(true); })
        .catch(() => {});
    }, 3000);
    return () => clearInterval(t);
  }, [live, token]);

  const addr = server.trim().replace(/\/$/, "");
  const valid = /^https?:\/\/[^/\s?#@"]+$/i.test(addr);
  let host = "";
  try { host = new URL(addr).hostname; } catch { /* invalid */ }
  const plainHttp = /^http:\/\//i.test(addr) && !LOCAL.test(host);
  const tls = info?.tls ?? {};
  const pin = tls.enabled && tls.selfSigned && /^https:/i.test(addr) ? (info.tlsFingerprint || "").replace(/:/g, "").toLowerCase() : "";
  const file = info?.[kind];
  const meta = (info?.release?.files ?? []).find((f) => f.name === file?.fileName && f.sha256 === file?.sha256);
  const signed = linux ? false : kind === "exe" ? file?.signed : meta?.signed;
  const href = `/api/gw/agents/installer/download?kind=${kind}` + (kind === "exe" && !signed ? `&server=${encodeURIComponent(addr)}` : "");
  const tok = live ? token.token : "<enrollment token>";
  const exeCmd = `BIMBA-Site-Agent-Setup.exe install --server ${addr} --token ${tok} --site "<site>"` + (pin ? ` --trust-cert ${pin}` : "");
  const msiCmd = `msiexec /i BIMBA-Site-Agent.msi /qn SERVER=${addr} TOKEN=${tok} SITE="<site>"` + (pin ? ` TRUSTCERT=${pin}` : "");
  const debCmd = `sudo bimba-site-agent install --server ${addr} --token ${tok} --site "<site>"` + (pin ? ` --trust-cert ${pin}` : "");
  const debAuto = `sudo BIMBA_SERVER=${addr} BIMBA_ENROLL_TOKEN=${tok}` + (pin ? ` BIMBA_TRUST_CERT=${pin}` : "")
    + ` apt install -y ./${info?.deb?.fileName ?? "bimba-site-agent_all.deb"}`;
  const tarCmd = `sudo ./bimba-site-agent/install.sh --server ${addr} --token ${tok} --site "<site>"` + (pin ? ` --trust-cert ${pin}` : "");
  const none = info && !["exe", "msi", "deb", "tar"].some((k) => info[k]?.available);
  const buildScript = linux ? "scripts\\build-linux-agent.ps1" : "scripts\\build-site-agent.ps1";
  const label = { exe: "installer", msi: "MSI", deb: ".deb package", tar: ".tar.gz" }[kind];

  return (
    <Modal wide title="Add a computer" onClose={onClose}
           subtitle="Install the BIMBA Site Agent on a SCADA computer. It registers the computer here; then you add the data sources it reads."
           footer={<><button className="btn ghost" onClick={onClose}>Close</button>
             {file?.available && valid
               ? <a className="btn" href={href} download={file.fileName}>Download {label}</a>
               : <button className="btn" disabled>Download</button>}</>}>
      <Msg>{error}</Msg>
      {!info && !error && <p className="muted">Loading…</p>}
      {none && <Msg>The installers are not on this Setu server. Build them with scripts\build-site-agent.ps1 (Windows) and scripts\build-linux-agent.ps1 (Linux), served from site-agent\dist (mounted at /installers).</Msg>}
      {info && !info.registrationEnabled && <Msg kind="warn">Site agent registration is turned off on this Setu server, so installs will fail until it is turned on.</Msg>}
      {info && !none && (
        <>
          <h3 className="step-h">1. The computer</h3>
          <div className="seg two" role="radiogroup" aria-label="Operating system">
            <label><input type="radio" name="i-os" checked={!linux} onChange={() => setPlatform("windows")} />
              <b>Windows</b><small>Windows 10 / 11, Windows Server 2016 and newer. Nothing else to install.</small></label>
            <label><input type="radio" name="i-os" checked={linux} onChange={() => setPlatform("linux")} />
              <b>Linux / Raspberry Pi</b><small>x86-64 servers and industrial PCs, Raspberry Pi (64- or 32-bit). Uses Java 17 or newer.</small></label>
          </div>
          {!linux ? (
            <div className="seg two" role="radiogroup" aria-label="Kind of installer">
              <label><input type="radio" name="i-kind" checked={kind === "exe"} onChange={() => setKind("exe")} />
                <b>Setup wizard (.exe)</b><small>Someone runs it on the computer.</small></label>
              <label><input type="radio" name="i-kind" checked={kind === "msi"} onChange={() => setKind("msi")} />
                <b>MSI package</b><small>Unattended roll-out: Intune, Configuration Manager, a startup script.</small></label>
            </div>
          ) : (
            <div className="seg two" role="radiogroup" aria-label="Kind of package">
              <label><input type="radio" name="i-kind" checked={kind === "deb"} onChange={() => setKind("deb")} />
                <b>Debian package (.deb)</b><small>Debian, Ubuntu, Raspberry Pi OS. Installs Java with it.</small></label>
              <label><input type="radio" name="i-kind" checked={kind === "tar"} onChange={() => setKind("tar")} />
                <b>Archive (.tar.gz)</b><small>Any Linux with systemd: RHEL, Rocky, Alma, SUSE… Install Java 17 first.</small></label>
            </div>
          )}

          <h3 className="step-h">2. The Setu address it uses</h3>
          <div className="field">
            <input id="i-srv" type="url" aria-label="Setu address" value={server} onChange={(e) => setServer(e.target.value)} spellCheck="false" autoComplete="off" />
            <span className="hint">An address the SCADA computer can reach (this Setu server's name or IP){info.httpsPort ? `, HTTPS port ${info.httpsPort}` : ""}.
              {kind === "exe" && (signed ? " The installer is code-signed, so the person installing types this address." : " It is built into the downloaded installer.")}</span>
          </div>
          {!valid && server && <Msg>Enter only the address, for example https://setu.example.com:8443 (no path).</Msg>}
          {valid && LOCAL.test(host) && <Msg kind="warn">{host} only works on this computer. Other computers need this Setu server's network name or IP address.</Msg>}
          {valid && plainHttp && <Msg kind="warn">Plain HTTP sends the token or password unencrypted, and agents cannot get their certificate. Use the HTTPS address.</Msg>}
          {valid && plainHttp && info.requireHttps && <Msg>This Setu server only accepts registrations over HTTPS.</Msg>}

          <h3 className="step-h">3. How it registers</h3>
          {!canTokens ? (
            <p className="hint">Ask an administrator of your organization for an enrollment token{kind === "exe" || linux ? ", or sign in with your e-mail address and password while installing" : ""}.</p>
          ) : used ? (
            <div className="field">
              <Msg kind="good">A computer registered with the token. It appears in the list within a minute; the token no longer works.</Msg>
              <div><button className="btn ghost" disabled={making} onClick={copyToken}>Copy a token for another computer</button></div>
            </div>
          ) : live ? (
            <div className="field">
              <CopyLine text={token.token} />
              <span className="hint"><b>{copied ? "Copied. " : ""}Works for one computer, expires in {Math.floor(left / 60)}:{String(left % 60).padStart(2, "0")}.</b>{" "}
                Paste it into the installer now{kind === "exe" ? "" : " (it is in the commands below)"}.</span>
            </div>
          ) : (
            <div className="field">
              {token && <Msg kind="warn">The token expired before a computer used it. Copy a new one when the installer asks for it.</Msg>}
              <div><button className="btn" disabled={making} onClick={copyToken}>{making ? "Copying…" : token ? "Copy a new token" : "Copy token"}</button></div>
              <span className="hint">A one-time token: it registers one computer and expires after {Math.round((token ? token.endsAt - Date.parse(token.createdAt) : 180000) / 60000) || 3} minutes,
                so copy it when the installer asks for it.{kind === "exe" ? " Or the person installing signs in with their e-mail address and password." : ""}</span>
            </div>
          )}
          {canTokens && (kind === "msi" || kind === "deb") && (
            <p className="hint">Rolling out to many computers? Create a named token under <Link to={at("/tokens")}>Administration → Enrollment tokens</Link>.</p>
          )}

          <h3 className="step-h">4. Install</h3>
          {!file?.available && <Msg>{file?.fileName} is not on this Setu server. Build it with {buildScript}.</Msg>}
          {file?.available && !linux && !signed && (
            <Msg kind="warn">Not code-signed: Windows SmartScreen may warn about it. Sign release builds (see scripts\build-site-agent.ps1).</Msg>
          )}
          {linux ? (
            <div className="field">
              <span className="label">Copy the {label} to the computer, then run:</span>
              {kind === "deb" ? <><CopyLine text={`sudo apt install ./${file?.fileName ?? "bimba-site-agent_all.deb"}`} /><CopyLine text={debCmd} /></>
                : <><CopyLine text={`tar xzf ${file?.fileName ?? "bimba-site-agent-linux.tar.gz"}`} /><CopyLine text={tarCmd} /></>}
              <span className="hint">Check it with sudo bimba-site-agent status. The computer appears in this list within a minute.</span>
            </div>
          ) : kind === "exe" ? (
            <div className="field">
              <span className="hint">Download the installer and run it on the computer: the wizard asks for the token (or an e-mail address and password) and installs the agent. The computer appears in this list within a minute.</span>
            </div>
          ) : (
            <div className="field">
              <span className="label">Install command (Intune Win32 app, Configuration Manager, or a Group Policy startup script)</span>
              <CopyLine text={msiCmd} />
              <span className="hint">Unattended installs need an enrollment token. A newer MSI upgrades in place and keeps the registration.</span>
            </div>
          )}

          <details className="adv">
            <summary>More ways to install, and technical details</summary>
            {kind === "exe" && (
              <div className="field">
                <span className="label">Silent install (from an administrator prompt)</span>
                <CopyLine text={exeCmd} />
                <span className="hint">Or --email &lt;e-mail&gt; instead of --token (the password is asked for).</span>
              </div>
            )}
            {kind === "deb" && (
              <div className="field">
                <span className="label">Unattended roll-out (Ansible, cloud-init, a prepared Raspberry Pi image)</span>
                <CopyLine text={debAuto} />
                <span className="hint">The package registers the computer while it installs. Remove with sudo apt remove bimba-site-agent (purge also deletes its data).</span>
              </div>
            )}
            {linux && <p className="hint">Or --email &lt;e-mail&gt; instead of --token (the password is asked for). Logs: journalctl -u bimba-site-agent.
              {kind === "tar" && " Upgrade: unpack the newer archive and run install.sh again. Remove with sudo /opt/bimba-site-agent/uninstall.sh."}</p>}
            <dl className="kv">
              {file?.available && !linux && signed && (
                <><dt>Signature</dt><dd>Code-signed{meta?.signer ? ` by ${meta.signer}` : ""}.{meta && meta.status !== "Valid" ? " The signing certificate is not trusted by the build machine (test certificate?), so Windows treats the file as from an unknown publisher." : ""}</dd></>
              )}
              <dt>Setu certificate</dt>
              <dd>{!tls.enabled ? "HTTPS is off" : tls.selfSigned
                ? <>Self-signed: agents trust it by its fingerprint{kind === "msi" ? " (TRUSTCERT)" : linux ? " (--trust-cert)" : " (the installer shows it to confirm)"}.<span className="mono"> {info.tlsFingerprint}</span></>
                : <>Issued by {tls.issuer}, valid until {full(tls.notAfter)}. Agents trust it like a web site when the computer trusts its issuer.</>}</dd>
              {tls.error && <><dt>Certificate problem</dt><dd className="bad">{tls.error} (the self-signed certificate is used instead)</dd></>}
              <dt>Agent sign-in</dt>
              <dd>{info.agentCertificates ? `Each agent gets its own client certificate (mutual TLS, ${info.certificateDays} days, renewed automatically); its key never leaves the computer.`
                : "Agent secret (client certificates are turned off on this Setu server)."}</dd>
              {file?.available && (
                <>
                  <dt>File</dt><dd>{file.fileName} · {(file.size / 1024).toFixed(0)} KB{(linux ? info.releaseLinux : info.release)?.version ? ` · version ${(linux ? info.releaseLinux : info.release).version}` : ""} · built {full(file.builtAt)}</dd>
                  <dt>SHA-256</dt><dd className="mono">{file.sha256}</dd>
                </>
              )}
            </dl>
          </details>
        </>
      )}
    </Modal>
  );
}
