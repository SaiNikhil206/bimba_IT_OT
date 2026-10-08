import { Fragment, useCallback, useEffect, useState } from "react";
import { ago } from "./model.js";
import { to } from "./paths.js";
import { Card, Empty, PageHeader, Status, Tabs } from "./ui.jsx";

// Site agents (/operations/agents): machines that installed the BIMBA Site Agent (Windows .exe) and
// registered through the BIMBA Gateway. Private per organization: the page needs an organization
// sign-in and the API only returns the signed-in user's organizations.
//   /operations/agents           machines of the organization
//   /operations/agents/install   how to install + enrollment tokens (admins)

const STATUS = {
  online: ["good", "Online"],
  offline: ["critical", "Offline"],
  pending: ["neutral", "Waiting for first heartbeat"],
  revoked: ["neutral", "Revoked"],
  uninstalled: ["neutral", "Uninstalled"],
};
const EVENT = { enrolled: "Registered", "re-enrolled": "Registered again", revoked: "Revoked", uninstalled: "Uninstalled" };

function cookie(name) {
  const m = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return m ? decodeURIComponent(m[1]) : "";
}

async function call(path, { method = "GET", body } = {}) {
  const headers = { "X-CSRFToken": cookie("csrftoken") };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const r = await fetch(`/api/${path}`, { method, headers, credentials: "same-origin", body: body === undefined ? undefined : JSON.stringify(body) });
  let data = {};
  try {
    data = await r.json();
  } catch {
    /* non-JSON error page */
  }
  if (!r.ok) {
    const err = new Error(data.error || (r.status === 403 ? "Request refused. Reload the page and try again." : `HTTP ${r.status}`));
    err.status = r.status;
    throw err;
  }
  return data;
}

const when = (iso) => (iso ? new Date(iso).toLocaleString([], { dateStyle: "medium", timeStyle: "short" }) : "–");

function loadOrg() {
  try {
    return localStorage.getItem("bimba.ops.agents.org");
  } catch {
    return null;
  }
}

export default function Agents({ tab, link }) {
  const [me, setMe] = useState(null);
  const [slug, setSlug] = useState(loadOrg);

  useEffect(() => {
    call("auth/me/").then(setMe).catch(() => setMe({ user: null, orgs: [] }));
  }, []);

  const orgs = me?.orgs ?? [];
  const org = orgs.find((o) => o.slug === slug) ?? orgs[0];
  useEffect(() => {
    if (!org) return;
    try {
      localStorage.setItem("bimba.ops.agents.org", org.slug);
    } catch {
      /* not remembered */
    }
  }, [org]);

  if (!me) return <PageHeader title="Site agents" subtitle="Loading…" />;
  if (!me.user || !org) return <SignIn onSignedIn={setMe} />;

  const signOut = async () => {
    await call("auth/logout/", { method: "POST" }).catch(() => {});
    setMe({ user: null, orgs: [] });
  };
  const view = tab === "install" ? "install" : "machines";
  return (
    <>
      <PageHeader title="Site agents"
                  subtitle={`Machines registered to ${org.name}. Only members of this organization can see them.`}
                  actions={(
                    <>
                      {orgs.length > 1 && (
                        <select value={org.slug} onChange={(e) => setSlug(e.target.value)} aria-label="Organization">
                          {orgs.map((o) => <option key={o.slug} value={o.slug}>{o.name}</option>)}
                        </select>
                      )}
                      <span className="sub">{me.user.name}{org.role === "admin" ? " · admin" : ""}</span>
                      <button className="op-btn ghost" onClick={signOut}>Sign out</button>
                    </>
                  )} />
      <Tabs items={[{ key: "machines", label: "Machines" }, { key: "install", label: "Install on a machine" }]}
            value={view} hrefFor={(k) => to("agents", k === "machines" ? "" : k)} link={link} />
      {view === "install" ? <Install key={org.slug} org={org} /> : <Machines key={org.slug} org={org} />}
    </>
  );
}

function SignIn({ onSignedIn }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSignedIn(await call("auth/login/", { method: "POST", body: { username, password } }));
    } catch (err) {
      setError(err.message);
      setPassword("");
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <PageHeader title="Site agents" subtitle="The machines your organization registered with the BIMBA Site Agent." />
      <Card title="Sign in to your organization" subtitle="Site agents are private: each organization only sees its own machines." className="op-signin">
        <form className="op-form" onSubmit={submit}>
          <div className="op-field wide">
            <label htmlFor="ag-user">User name</label>
            <input id="ag-user" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
          </div>
          <div className="op-field wide">
            <label htmlFor="ag-pw">Password</label>
            <input id="ag-pw" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </div>
          <div className="op-field wide">
            {error && <span className="err" role="alert">{error}</span>}
            <div><button className="op-btn" disabled={busy || !username || !password}>{busy ? "Signing in…" : "Sign in"}</button></div>
          </div>
        </form>
      </Card>
    </>
  );
}

// ---------------------------------------------------------------- machines

function Machines({ org }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [open, setOpen] = useState(null);

  const load = useCallback(() => {
    call(`agents/?org=${encodeURIComponent(org.slug)}`).then((d) => { setData(d); setError(null); }).catch((e) => setError(e.message));
  }, [org.slug]);
  useEffect(() => {
    load();
    const t = setInterval(load, 15000);
    return () => clearInterval(t);
  }, [load]);

  if (error && !data) return <Card><p className="op-note bad">{error}</p></Card>;
  if (!data) return <Card><Empty>Loading machines…</Empty></Card>;
  const agents = data.agents;
  const active = agents.filter((a) => a.status === "online" || a.status === "offline" || a.status === "pending");
  const online = agents.filter((a) => a.status === "online").length;
  const offline = agents.filter((a) => a.status === "offline");
  const verdict = active.length === 0 ? "neutral" : offline.length ? "warning" : "good";

  return (
    <>
      {active.length > 0 && (
        <section className={`op-verdict ${verdict}`}>
          <span className="big" aria-hidden="true">{verdict === "good" ? "✓" : "!"}</span>
          <div>
            <h2>{online} of {active.length} {active.length === 1 ? "machine" : "machines"} online</h2>
            <p>{offline.length
              ? `Not reporting: ${offline.map((a) => a.name).join(", ")}. Check that the machine is on and can reach the gateway.`
              : "Every registered machine sent a heartbeat in the last few minutes."}</p>
          </div>
        </section>
      )}
      <Card flush>
        {agents.length === 0 ? (
          <Empty>No machines yet. Open “Install on a machine” to register the first one.</Empty>
        ) : (
          <table className="op-table click">
            <thead><tr><th>Machine</th><th>Site</th><th>SCADA software</th><th>Status</th><th className="r">Last seen</th></tr></thead>
            <tbody>
              {agents.map((a) => {
                const [state, label] = STATUS[a.status] ?? ["neutral", a.status];
                const isOpen = open === a.id;
                const products = [...new Set(a.scada.filter((s) => s.source === "installed").map((s) => s.product))];
                return (
                  <Fragment key={a.id}>
                    <tr className={isOpen ? "open" : ""} onClick={() => setOpen(isOpen ? null : a.id)} aria-expanded={isOpen}>
                      <td><b>{a.name}</b><div className="sub">{a.hostname} · {a.ip || a.remoteIp}</div></td>
                      <td>{a.site || <span className="sub">–</span>}</td>
                      <td>{products.length ? <>{products[0]}{products.length > 1 && <span className="sub"> +{products.length - 1} more</span>}</> : <span className="sub">None detected</span>}</td>
                      <td><Status state={state}>{label}</Status></td>
                      <td className="r sub">{a.lastSeen ? ago(a.lastSeen) : "never"}</td>
                    </tr>
                    {isOpen && (
                      <tr className="op-expand">
                        <td colSpan={5}><AgentDetail id={a.id} admin={org.role === "admin"} onChanged={() => { load(); }} onRemoved={() => { setOpen(null); load(); }} /></td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>
    </>
  );
}

function AgentDetail({ id, admin, onChanged, onRemoved }) {
  const [a, setA] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => call(`agents/${id}/`).then(setA).catch((e) => setError(e.message)), [id]);
  useEffect(() => {
    load();
  }, [load]);
  if (error) return <p className="op-note bad">{error}</p>;
  if (!a) return <p className="sub">Loading…</p>;
  const d = a.details ?? {};
  const hw = d.hardware ?? {};
  const nics = (d.network ?? []).filter((n) => n.ipv4?.length || n.ipv6?.length);

  const act = async (what) => {
    const q = what === "revoke"
      ? `Revoke ${a.name}? The agent on that machine stops reporting. Installing it again registers the machine again.`
      : `Remove ${a.name} from the list?`;
    if (!window.confirm(q)) return;
    setBusy(true);
    try {
      if (what === "revoke") {
        await call(`agents/${id}/revoke/`, { method: "POST" });
        await load();
        onChanged();
      } else {
        await call(`agents/${id}/remove/`, { method: "DELETE" });
        onRemoved();
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="op-col">
      <dl className="op-dl">
        <div><dt>Windows</dt><dd>{a.os || "–"}{d.os?.arch ? ` · ${d.os.arch}` : ""}</dd></div>
        <div><dt>Hardware</dt><dd>{[hw.manufacturer, hw.model].filter(Boolean).join(" ") || "–"}{hw.serialNumber ? <span className="sub"> · S/N {hw.serialNumber}</span> : null}</dd></div>
        <div><dt>Processor / memory</dt><dd>{hw.cpu || "–"}{hw.memoryGb ? ` · ${hw.memoryGb} GB` : ""}</dd></div>
        <div><dt>Disks</dt><dd>{(d.disks ?? []).map((x) => `${x.drive} ${x.freeGb} GB free of ${x.sizeGb} GB`).join(", ") || "–"}</dd></div>
        <div><dt>Network</dt><dd>{nics.length ? nics.map((n) => <div key={n.name}>{n.name}: {[...(n.ipv4 ?? [])].join(", ") || (n.ipv6 ?? []).join(", ")} <span className="sub mono">{n.mac}</span></div>) : "–"}</dd></div>
        <div><dt>Seen by the gateway from</dt><dd>{a.remoteIp || "–"}</dd></div>
        <div><dt>Registered</dt><dd>{when(a.enrolledAt)} <span className="sub">by {a.enrolledBy} ({a.enrolledVia === "token" ? "enrollment token" : "organization user"})</span></dd></div>
        <div><dt>Agent</dt><dd>{a.agentVersion ? `v${a.agentVersion}` : "–"} <span className="sub">· heartbeat every {a.heartbeatSeconds} s · {d.timezone ?? ""}</span></dd></div>
      </dl>
      <div>
        <h3>SCADA and OT software on this machine</h3>
        {a.scada.length === 0 ? <p className="sub">None of the known SCADA, HMI or OPC packages were found.</p> : (
          <ul className="op-agent-sw">
            {a.scada.map((s, i) => (
              <li key={i}><b>{s.product}</b> {s.version && <span className="sub">{s.version}</span>}
                <span className="sub"> · {s.source === "service" ? `Windows service, ${s.state?.toLowerCase()}` : s.vendor || s.family}</span></li>
            ))}
          </ul>
        )}
      </div>
      {a.events?.length > 0 && (
        <div>
          <h3>History</h3>
          <ul className="op-agent-sw">
            {a.events.map((e, i) => <li key={i}>{when(e.at)} · {EVENT[e.kind] ?? e.kind} <span className="sub">{e.actor}{e.remoteIp ? ` from ${e.remoteIp}` : ""}</span></li>)}
          </ul>
        </div>
      )}
      <div className="op-row">
        <span className="sub mono">Agent id {a.id}</span>
        <span className="grow" style={{ flex: 1 }} />
        {admin && a.status !== "revoked" && a.status !== "uninstalled" && (
          <button className="op-btn ghost" disabled={busy} onClick={() => act("revoke")}>Revoke…</button>
        )}
        {admin && (a.status === "revoked" || a.status === "uninstalled") && (
          <button className="op-btn ghost" disabled={busy} onClick={() => act("remove")}>Remove from list…</button>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- install + tokens

function loadGateway() {
  try {
    return localStorage.getItem("bimba.ops.agents.gateway") || `https://${window.location.hostname}:8443`;
  } catch {
    return `https://${window.location.hostname}:8443`;
  }
}

function Install({ org }) {
  const [gateway, setGateway] = useState(loadGateway);
  const [created, setCreated] = useState(null);
  useEffect(() => {
    try {
      localStorage.setItem("bimba.ops.agents.gateway", gateway);
    } catch {
      /* not remembered */
    }
  }, [gateway]);

  return (
    <>
      <Card title="Install the BIMBA Site Agent" subtitle="Run the installer on each SCADA or HMI computer (Windows 10/11, Server 2016 or later, administrator rights).">
        <ol className="op-steps-v">
          <li>
            <b>Download the installer</b>
            <span><a className="op-link" href="/downloads/BIMBA-Site-Agent-Setup.exe" download>BIMBA-Site-Agent-Setup.exe</a> (about 75 KB, no other software needed)</span>
          </li>
          <li>
            <b>Run it and enter the gateway address</b>
            <div className="op-field" style={{ maxWidth: 420 }}>
              <input value={gateway} onChange={(e) => setGateway(e.target.value)} aria-label="Gateway address" className="mono" />
              <span className="hint">The address the machines use to reach the BIMBA Gateway (HTTPS port 8443). The installer asks you to confirm the gateway's certificate fingerprint, shown on the gateway's Overview page.</span>
            </div>
          </li>
          <li>
            <b>Prove the machine belongs to {org.name}</b>
            <span>Either paste an enrollment token{org.role === "admin" ? " (create one below)" : " from your organization admin"}, or sign in with your own organization user name and password.</span>
          </li>
          <li>
            <b>Check what is shared and click Install</b>
            <span>The machine shows up under Machines within a minute.</span>
          </li>
        </ol>
      </Card>
      {org.role === "admin"
        ? <Tokens org={org} gateway={gateway} created={created} setCreated={setCreated} />
        : <Card><p className="sub">Only organization admins can create enrollment tokens. You can still install with your own user name and password.</p></Card>}
    </>
  );
}

function Tokens({ org, gateway, created, setCreated }) {
  const [tokens, setTokens] = useState(null);
  const [error, setError] = useState(null);
  const [form, setForm] = useState({ label: "", maxUses: "10", days: "7" });
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(null);

  const load = useCallback(() => {
    call(`agents/tokens/?org=${encodeURIComponent(org.slug)}`).then((d) => setTokens(d.tokens)).catch((e) => setError(e.message));
  }, [org.slug]);
  useEffect(() => {
    load();
  }, [load]);

  const create = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setCreated(await call("agents/tokens/", { method: "POST", body: { org: org.slug, ...form } }));
      setForm({ ...form, label: "" });
      load();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };
  const revoke = async (t) => {
    if (!window.confirm(`Revoke token ${t.prefix}…${t.label ? ` (${t.label})` : ""}? Machines already registered keep working.`)) return;
    try {
      await call(`agents/tokens/${t.id}/revoke/`, { method: "POST" });
      load();
    } catch (err) {
      setError(err.message);
    }
  };
  const copy = (key, text) => navigator.clipboard?.writeText(text).then(() => {
    setCopied(key);
    setTimeout(() => setCopied(null), 1500);
  });
  const command = created && `BIMBA-Site-Agent-Setup.exe install --server ${gateway} --token ${created.token} --site "<site>"`;

  return (
    <Card title="Enrollment tokens" subtitle="A token lets an installer add machines to this organization. It is shown once; only its fingerprint is stored." flush>
      <div style={{ padding: "0 20px 20px" }}>
        <form className="op-row" onSubmit={create}>
          <input placeholder="Label, e.g. Plant 2 roll-out" value={form.label} onChange={(e) => setForm({ ...form, label: e.target.value })} style={{ flex: "1 1 220px" }} aria-label="Label" />
          <label className="sub">Uses <input className="sm" type="number" min="1" max="10000" value={form.maxUses} onChange={(e) => setForm({ ...form, maxUses: e.target.value })} placeholder="∞" /></label>
          <label className="sub">Valid days <input className="sm" type="number" min="1" max="365" value={form.days} onChange={(e) => setForm({ ...form, days: e.target.value })} placeholder="∞" /></label>
          <button className="op-btn" disabled={busy}>{busy ? "Creating…" : "Create token"}</button>
        </form>
        {error && <p className="op-note bad">{error}</p>}
        {created && (
          <div className="op-secret" role="status">
            <b>Copy this token now. It is not shown again.</b>
            <div className="op-row"><code className="mono">{created.token}</code>
              <button className="op-btn ghost" type="button" onClick={() => copy("t", created.token)}>{copied === "t" ? "Copied" : "Copy"}</button></div>
            <span className="sub">Silent install (elevated prompt, for software deployment tools):</span>
            <div className="op-row"><code className="mono">{command}</code>
              <button className="op-btn ghost" type="button" onClick={() => copy("c", command)}>{copied === "c" ? "Copied" : "Copy"}</button></div>
            <button className="op-link" type="button" onClick={() => setCreated(null)} style={{ border: 0, background: "none", padding: 0, cursor: "pointer", alignSelf: "flex-start" }}>I saved it, hide</button>
          </div>
        )}
      </div>
      {tokens && tokens.length > 0 && (
        <table className="op-table">
          <thead><tr><th>Token</th><th>Label</th><th>Uses</th><th>Expires</th><th>Status</th><th className="r" /></tr></thead>
          <tbody>
            {tokens.map((t) => (
              <tr key={t.id}>
                <td className="mono">{t.prefix}…</td>
                <td>{t.label || <span className="sub">–</span>}<div className="sub">by {t.createdBy ?? "system"}, {when(t.createdAt)}</div></td>
                <td className="num">{t.uses}{t.maxUses != null ? ` / ${t.maxUses}` : ""}</td>
                <td className="sub">{t.expiresAt ? when(t.expiresAt) : "never"}</td>
                <td><Status state={t.state === "active" ? "good" : "neutral"}>{t.state === "active" ? "Active" : t.state[0].toUpperCase() + t.state.slice(1)}</Status></td>
                <td className="r">{t.state === "active" && <button className="op-btn ghost" onClick={() => revoke(t)}>Revoke</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}
