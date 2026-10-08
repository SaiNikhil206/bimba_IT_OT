import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Empty, full, Modal, Msg, PageHeader, Status, useOrgPath, usePoll, useToast, when } from "../ui.jsx";

// One site agent's machine (/<org>/agents/<id>): what the machine is (Overview), the SCADA data sources the agent
// collects on it and pushes to the gateway (Data sources: OPC UA, Modbus TCP; OPC DA soon), and the last values it
// sent (Live). Testing a connection and browsing tags run on the machine itself: the console sends a request the
// agent picks up within seconds (POST agents/<id>/commands) and shows its answer.

const KINDS = { opcua: "OPC UA", modbus: "Modbus TCP", opcda: "OPC DA" };
const POLICIES = [
  ["Auto", "Strongest the server offers"],
  ["None", "No security (the server must allow it)"],
  ["Basic256Sha256", "Basic256Sha256"],
  ["Aes128_Sha256_RsaOaep", "Aes128-Sha256-RsaOaep"],
  ["Aes256_Sha256_RsaPss", "Aes256-Sha256-RsaPss"],
];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fp = (hex) => (hex ? hex.toUpperCase().match(/.{2}/g).join(":") : "");
const short = (hex) => (hex ? `${fp(hex).slice(0, 11)}…${fp(hex).slice(-5)}` : "");

/** Sends a request to the machine and waits for its answer (the agent long-polls the gateway). */
async function ask(agentId, body) {
  const { id } = await api(`agents/${agentId}/commands`, { method: "POST", body });
  for (let i = 0; i < 90; i++) {
    await sleep(i < 10 ? 400 : 1000);
    const r = await api(`agents/${agentId}/commands/${id}`, { background: true });
    if (r.state === "done") return r.result;
    if (r.state === "failed" || r.state === "expired") throw new Error(r.error || "The machine could not do it.");
  }
  throw new Error("The machine did not answer.");
}

export default function Machine({ id }) {
  const { can } = useSession();
  const at = useOrgPath();
  const { data, error, reload } = usePoll(`agents/${id}`, 10_000);
  const [tab, setTab] = useState("overview");

  if (error && !data) {
    return (
      <>
        <p><Link to={at("/agents")}>← Site agents</Link></p>
        <Msg>{error}</Msg>
      </>
    );
  }
  if (!data) return <p className="muted">Loading…</p>;
  const st = data.status;
  return (
    <>
      <p className="crumb"><Link to={at("/agents")}>← Site agents</Link></p>
      <PageHeader title={data.name} subtitle={[data.hostname !== data.name ? data.hostname : null, data.site, data.os].filter(Boolean).join(" · ")}>
        <Status kind={st === "online" ? "good" : st === "offline" ? "bad" : ""}>{st}</Status>
      </PageHeader>
      <div className="tabs" role="tablist">
        {[["overview", "Overview"], ["sources", `Data sources${data.sources.length ? ` (${data.sources.length})` : ""}`], ["live", "Live values"]].map(([k, label]) => (
          <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? "on" : ""} onClick={() => setTab(k)}>{label}</button>
        ))}
      </div>
      {tab === "overview" && <Overview a={data} />}
      {tab === "sources" && <Sources a={data} canManage={can("agents.manage")} reload={reload} />}
      {tab === "live" && <Live id={id} />}
    </>
  );
}

// ------------------------------------------------------------------ overview

function Overview({ a }) {
  const d = a.details ?? {};
  const os = d.os ?? {};
  const hw = d.hardware ?? {};
  const c = a.collector ?? {};
  const edge = c.edge ?? {};
  return (
    <div className="stack">
      <div className="mgrid">
        <Card title="Machine">
          <dl className="kv">
            <dt>Computer name</dt><dd>{d.hostname ?? a.hostname}</dd>
            {d.domain && <><dt>Domain</dt><dd>{d.domain}</dd></>}
            <dt>Windows</dt><dd>{os.name ?? a.os}{os.version ? ` (${os.version}${os.arch ? `, ${os.arch}` : ""})` : ""}</dd>
            {os.lastBoot && <><dt>Last start</dt><dd title={full(os.lastBoot)}>{when(os.lastBoot)}</dd></>}
            {d.timezone && <><dt>Time zone</dt><dd>{d.timezone}</dd></>}
            <dt>IP address</dt><dd>{a.ip}<small className="muted"> · seen from {a.remoteIp}</small></dd>
          </dl>
        </Card>
        <Card title="Hardware">
          <dl className="kv">
            <dt>Model</dt><dd>{[hw.manufacturer, hw.model].filter(Boolean).join(" ") || "–"}</dd>
            {hw.serialNumber && <><dt>Serial number</dt><dd className="mono">{hw.serialNumber}</dd></>}
            <dt>Processor</dt><dd>{hw.cpu ?? "–"}{hw.cores ? ` · ${hw.cores} cores, ${hw.logicalProcessors} threads` : ""}</dd>
            <dt>Memory</dt><dd>{hw.memoryGb != null ? `${hw.memoryGb} GB` : "–"}</dd>
            {(d.disks ?? []).map((k) => (
              <span key={k.drive} style={{ display: "contents" }}><dt>Disk {k.drive}</dt><dd>{k.freeGb} GB free of {k.sizeGb} GB</dd></span>
            ))}
          </dl>
        </Card>
      </div>
      <div className="mgrid">
        <Card title="SCADA / OT software" subtitle="Found in the installed programs and Windows services.">
          {(a.scada ?? []).length === 0 ? <p className="muted">None detected.</p> : (
            <dl className="kv">
              {a.scada.map((s, i) => (
                <span key={i} style={{ display: "contents" }}>
                  <dt>{s.source === "service" ? "Service" : "Installed"}</dt>
                  <dd>{s.product} {s.version ?? ""}{s.source === "service" ? <small className="muted"> · {s.service} ({s.state})</small> : null}</dd>
                </span>
              ))}
            </dl>
          )}
        </Card>
        <Card title="Agent">
          <dl className="kv">
            <dt>Version</dt><dd>{a.agentVersion}</dd>
            <dt>Last report</dt><dd title={full(a.lastSeen)}>{when(a.lastSeen) || "–"}</dd>
            <dt>Signs in with</dt><dd>{a.signIn === "certificate" ? `its certificate (valid until ${full(a.certificateNotAfter)})` : "its enrollment secret"}</dd>
            <dt>Registered</dt><dd>{full(a.enrolledAt)}<small className="muted"> · {a.enrolledBy}</small></dd>
            <dt>Data collection</dt>
            <dd>
              {c.edge ? <EdgeState edge={edge} /> : <span className="muted">Not reported (agent before 1.4)</span>}
              {c.sent != null && <small className="muted">{c.sent.toLocaleString()} readings sent{c.rejected ? `, ${c.rejected} refused` : ""}</small>}
              {c.spool?.files > 0 && <small className="warnc">{c.spool.files} batch(es) waiting on the machine ({Math.round((c.spool.bytes ?? 0) / 1024)} KB)</small>}
              {c.lastUploadError && <small className="warnc" title={c.lastUploadError}>Upload: {c.lastUploadError}</small>}
            </dd>
          </dl>
        </Card>
      </div>
      {(d.network ?? []).length > 0 && (
        <Card title="Network" flush>
          <table>
            <thead><tr><th>Adapter</th><th>IP addresses</th><th>MAC</th></tr></thead>
            <tbody>
              {d.network.map((n) => (
                <tr key={n.name + n.mac}>
                  <td><b>{n.name}</b><small>{n.description}</small></td>
                  <td className="mono">{[...(n.ipv4 ?? []), ...(n.ipv6 ?? [])].join(", ")}{n.gateway && <small>gateway {n.gateway}</small>}</td>
                  <td className="mono nowrap">{n.mac}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  );
}

function EdgeState({ edge }) {
  const label = { running: "collecting", idle: "ready, nothing to collect", stopped: "stopped", missing: "not installed" }[edge.state] ?? edge.state;
  return (
    <>
      <Status kind={edge.state === "running" ? "good" : edge.state === "idle" ? "" : "bad"}>{label}</Status>
      {edge.error && <small className="warnc" title={edge.error}>{edge.error}</small>}
    </>
  );
}

// ------------------------------------------------------------------ data sources

function Sources({ a, canManage, reload }) {
  const toast = useToast();
  const [editing, setEditing] = useState(null);       // source | {new, ...preset}
  const [removing, setRemoving] = useState(null);
  const devices = a.collector?.edge?.devices ?? [];
  const found = a.details?.dataSources ?? {};
  const used = new Set(a.sources.map((s) => s.settings?.endpoint ?? s.settings?.progId));
  const suggestions = [...(found.opcua ?? []), ...(found.opcda ?? [])].filter((s) => !used.has(s.endpoint ?? s.progId));
  const online = a.status === "online";

  return (
    <div className="stack">
      {!a.collector?.edge && <Msg kind="warn">This machine runs agent {a.agentVersion}. Install version 1.4 or later on it to collect data.</Msg>}
      {canManage && suggestions.length > 0 && (
        <Card title="Found on this machine" subtitle="SCADA interfaces the agent found. Add one to start collecting its tags.">
          <div className="sugg">
            {suggestions.map((s) => (
              <div key={s.endpoint ?? s.progId} className="sugg-i">
                <div>
                  <b className="ellipsis" title={s.product ?? s.name}>{s.product ?? s.name}</b>
                  <small className="mono ellipsis" title={s.endpoint ?? s.progId}>{KINDS[s.kind]} · {s.endpoint ?? s.progId}</small>
                </div>
                <button className="btn ghost sm" onClick={() => setEditing(preset(s, a))}>Add</button>
              </div>
            ))}
          </div>
        </Card>
      )}
      <Card flush title="Data sources" subtitle={`Each source reports as one machine in ${a.org}'s pipelines. The agent reads it on ${a.hostname} and sends every change.`}
            action={canManage && <button className="btn" onClick={() => setEditing({ kind: "opcua", settings: { endpoint: "opc.tcp://localhost:4840", policy: "Auto", auth: "Anonymous" }, tags: [], pollMs: 1000, enabled: true, name: "" })}>Add data source</button>}>
        <table>
          <thead><tr><th>Machine name</th><th>Source</th><th>Tags</th><th>Status</th><th /></tr></thead>
          <tbody>
            {a.sources.length === 0 && <Empty cols={5}>No data sources yet.{canManage && suggestions.length ? " Add one of the interfaces found above." : ""}</Empty>}
            {a.sources.map((s) => {
              const dev = devices.find((d) => d.name === s.id);
              return (
                <tr key={s.id} className={s.enabled ? "" : "off"}>
                  <td><b>{s.name}</b><small>every {s.pollMs >= 1000 ? `${s.pollMs / 1000} s` : `${s.pollMs} ms`}</small></td>
                  <td style={{ minWidth: 0 }}>
                    {KINDS[s.kind]}
                    <small className="mono ellipsis" title={target(s)}>{target(s)}</small>
                  </td>
                  <td>{s.tags.length}</td>
                  <td><SourceState s={s} dev={dev} online={online} /></td>
                  <td className="r">
                    {canManage && (
                      <div className="row-acts">
                        <button className="btn ghost sm" onClick={() => setEditing(s)}>Edit</button>
                        <button className="btn danger sm" onClick={() => setRemoving(s)}>Remove</button>
                      </div>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
      {editing && (
        <SourceEditor agent={a} source={editing} onClose={() => setEditing(null)}
                      onSaved={(s) => { setEditing(null); toast(`${s.name} saved. The machine applies it within seconds.`); reload(); }} />
      )}
      {removing && (
        <Confirm title={`Remove ${removing.name}?`} danger confirm="Remove"
                 subtitle={`The agent stops reading it. Pipelines stop receiving machine ${removing.name}.`}
                 action={() => api(`agents/${a.id}/sources/${removing.id}`, { method: "DELETE" })}
                 onDone={() => { toast(`${removing.name} removed.`); setRemoving(null); reload(); }}
                 onClose={() => setRemoving(null)} />
      )}
    </div>
  );
}

function target(s) {
  const st = s.settings ?? {};
  if (s.kind === "opcua") return st.endpoint;
  if (s.kind === "modbus") return `${st.host}:${st.port ?? 502} unit ${st.unitId ?? 1}`;
  return st.progId;
}

function SourceState({ s, dev, online }) {
  if (!s.enabled) return <Status>paused</Status>;
  if (s.kind === "opcda") return <><Status kind="warn">waiting</Status><small>OPC DA: agent 1.5</small></>;
  if (!dev) return <Status>{online ? "starting" : "machine offline"}</Status>;
  if (dev.error) return <><Status kind="bad">no connection</Status><small className="bad ellipsis" title={dev.error}>{dev.error}</small></>;
  return <><Status kind="good">collecting</Status><small>{dev.goodTags}/{dev.tags} tags good · {when(dev.lastOk)}</small></>;
}

function preset(found, a) {
  const base = (found.product ?? found.name ?? "SCADA").replace(/[^A-Za-z0-9 ._-]+/g, " ").trim();
  const name = `${a.hostname}-${base}`.replace(/\s+/g, "-").slice(0, 120);
  if (found.kind === "opcda") return { kind: "opcda", name, settings: { progId: found.progId, host: "" }, tags: [], pollMs: 1000, enabled: true };
  return { kind: "opcua", name, settings: { endpoint: found.endpoint, policy: "Auto", auth: "Anonymous" }, tags: [], pollMs: 1000, enabled: true };
}

// ------------------------------------------------------------------ source editor

function SourceEditor({ agent, source, onClose, onSaved }) {
  const isNew = !source.id;
  const [f, setF] = useState(() => ({
    name: source.name ?? "",
    kind: source.kind ?? "opcua",
    settings: { ...(source.settings ?? {}), password: "" },
    tags: (source.tags ?? []).map((t) => ({ ...t })),
    pollMs: source.pollMs ?? 1000,
    enabled: source.enabled ?? true,
  }));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [test, setTest] = useState(null);         // {busy} | {ok, result} | {error}
  const [browse, setBrowse] = useState(false);
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const setS = (k, v) => setF((x) => ({ ...x, settings: { ...x.settings, [k]: v } }));
  const st = f.settings;
  const edge = agent.collector?.edge ?? {};
  const sourceForCommand = () => ({ kind: f.kind, settings: { ...st } });

  const runTest = async () => {
    setTest({ busy: true });
    try {
      const body = { kind: f.kind === "modbus" ? "read" : "test", source: sourceForCommand(), sourceId: source.id };
      if (f.kind === "modbus") body.tags = f.tags.length ? f.tags.slice(0, 20) : [];
      setTest({ ok: true, result: await ask(agent.id, body) });
    } catch (e) {
      setTest({ error: e.message });
    }
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = { ...f, settings: { ...st } };
      if (!body.settings.password) delete body.settings.password;
      const r = await api(isNew ? `agents/${agent.id}/sources` : `agents/${agent.id}/sources/${source.id}`,
        { method: isNew ? "POST" : "PUT", body });
      onSaved(r);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const addTags = (list) => {
    setF((x) => {
      const have = new Set(x.tags.map((t) => t.address));
      return { ...x, tags: [...x.tags, ...list.filter((t) => !have.has(t.address))] };
    });
  };

  return (
    <Modal wide title={isNew ? "Add data source" : `Edit ${source.name}`}
           subtitle={`Read on ${agent.hostname} by the BIMBA Site Agent and sent to the gateway.`}
           onClose={onClose}
           footer={<>
             <button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</button>
           </>}>
      <Msg>{error}</Msg>
      <div className="grid2">
        <div className="field">
          <label htmlFor="src-name">Machine name</label>
          <input type="text" id="src-name" value={f.name} maxLength={120} onChange={(e) => set("name", e.target.value)} placeholder="e.g. LINE-1-HMI" />
          <span className="hint">Its readings report as this machine in pipelines. Unique on the gateway.</span>
        </div>
        <div className="field">
          <label htmlFor="src-kind">Type</label>
          <select id="src-kind" value={f.kind} disabled={!isNew} onChange={(e) => {
            const k = e.target.value;
            setF((x) => ({ ...x, kind: k, tags: [], settings: k === "modbus" ? { host: "localhost", port: 502, unitId: 1 } : k === "opcua" ? { endpoint: "opc.tcp://localhost:4840", policy: "Auto", auth: "Anonymous" } : { progId: "" } }));
            setTest(null);
          }}>
            <option value="opcua">OPC UA</option>
            <option value="modbus">Modbus TCP</option>
            <option value="opcda">OPC DA (classic)</option>
          </select>
        </div>
      </div>

      {f.kind === "opcua" && (
        <div className="grid2">
          <div className="field span2">
            <label htmlFor="src-ep">Endpoint</label>
            <input type="text" id="src-ep" className="mono" value={st.endpoint ?? ""} onChange={(e) => setS("endpoint", e.target.value)} placeholder="opc.tcp://localhost:62541" />
            <span className="hint">Seen from the machine: localhost = the SCADA server on {agent.hostname}. Ignition: opc.tcp://localhost:62541.</span>
          </div>
          <div className="field">
            <label htmlFor="src-pol">Security</label>
            <select id="src-pol" value={st.policy ?? "Auto"} onChange={(e) => setS("policy", e.target.value)}>
              {POLICIES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="src-mode">Message security</label>
            <select id="src-mode" value={st.mode ?? "Auto"} disabled={(st.policy ?? "Auto") === "None"} onChange={(e) => setS("mode", e.target.value)}>
              <option value="Auto">Sign and encrypt if offered</option>
              <option value="SignAndEncrypt">Sign and encrypt</option>
              <option value="Sign">Sign only</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="src-auth">Sign-in</label>
            <select id="src-auth" value={st.auth ?? "Anonymous"} onChange={(e) => setS("auth", e.target.value)}>
              <option value="Anonymous">Anonymous</option>
              <option value="Username">User name + password</option>
            </select>
          </div>
          {st.auth === "Username" && (
            <>
              <div className="field">
                <label htmlFor="src-user">User name</label>
                <input type="text" id="src-user" value={st.username ?? ""} onChange={(e) => setS("username", e.target.value)} autoComplete="off" />
              </div>
              <div className="field">
                <label htmlFor="src-pw">Password</label>
                <input id="src-pw" type="password" value={st.password ?? ""} autoComplete="new-password"
                       placeholder={source.hasPassword ? "unchanged" : ""} onChange={(e) => setS("password", e.target.value)} />
                <span className="hint">Stored encrypted on the gateway; sent only to this machine.</span>
              </div>
            </>
          )}
          <div className="field span2">
            <span className="label">Server certificate</span>
            {st.serverCert
              ? <span className="small">Only the trusted certificate <span className="mono" title={fp(st.serverCert)}>{short(st.serverCert)}</span> is accepted.{" "}
                  <button className="link" onClick={() => setS("serverCert", "")}>Forget it</button></span>
              : <span className="hint">Not pinned yet: Test connection shows the server's certificate, then you can trust it.</span>}
          </div>
        </div>
      )}

      {f.kind === "modbus" && (
        <div className="grid2">
          <div className="field">
            <label htmlFor="src-host">PLC host / IP</label>
            <input type="text" id="src-host" value={st.host ?? ""} onChange={(e) => setS("host", e.target.value)} placeholder="192.168.10.20" />
            <span className="hint">Seen from {agent.hostname}.</span>
          </div>
          <div className="field">
            <label htmlFor="src-port">Port</label>
            <input id="src-port" type="number" min={1} max={65535} value={st.port ?? 502} onChange={(e) => setS("port", Number(e.target.value))} />
          </div>
          <div className="field">
            <label htmlFor="src-unit">Unit id</label>
            <input id="src-unit" type="number" min={0} max={255} value={st.unitId ?? 1} onChange={(e) => setS("unitId", Number(e.target.value))} />
          </div>
          <label className="check" style={{ alignSelf: "end" }}>
            <input type="checkbox" checked={!!st.zeroBased} onChange={(e) => setS("zeroBased", e.target.checked)} /> Addresses start at 0
          </label>
        </div>
      )}

      {f.kind === "opcda" && (
        <div className="grid2">
          <div className="field span2">
            <label htmlFor="src-prog">OPC DA server</label>
            <input type="text" id="src-prog" className="mono" list="da-servers" value={st.progId ?? ""} onChange={(e) => setS("progId", e.target.value)} placeholder="Kepware.KEPServerEX.V6" />
            <datalist id="da-servers">
              {(agent.details?.dataSources?.opcda ?? []).map((s) => <option key={s.progId} value={s.progId}>{s.name}</option>)}
            </datalist>
          </div>
          <Msg kind="warn">OPC DA sources are saved now and collected by agent version 1.5.</Msg>
        </div>
      )}

      <div className="grid2">
        <div className="field">
          <label htmlFor="src-poll">Read every</label>
          <select id="src-poll" value={f.pollMs} onChange={(e) => set("pollMs", Number(e.target.value))}>
            {[250, 500, 1000, 2000, 5000, 10000, 30000, 60000, 300000].map((v) => (
              <option key={v} value={v}>{v < 1000 ? `${v} ms` : v < 60000 ? `${v / 1000} s` : `${v / 60000} min`}</option>
            ))}
          </select>
          <span className="hint">Only changes are sent.</span>
        </div>
        <label className="check" style={{ alignSelf: "end" }}>
          <input type="checkbox" checked={f.enabled} onChange={(e) => set("enabled", e.target.checked)} /> Collect this source
        </label>
      </div>

      {f.kind !== "opcda" && (
        <div className="test-row">
          <button className="btn ghost" disabled={test?.busy || agent.status !== "online"} onClick={runTest}>
            {test?.busy ? "Asking the machine…" : f.kind === "modbus" ? "Test connection (read tags)" : "Test connection"}
          </button>
          {agent.status !== "online" && <span className="hint">The machine is offline.</span>}
        </div>
      )}
      {test?.error && (
        <Msg>
          {test.error}
          {/certificate/i.test(test.error) && edge.opcuaCertificate && (
            <><br /><small>This machine's OPC UA certificate: <span className="mono">{fp(edge.opcuaCertificate)}</span> ({edge.opcuaApplicationUri})</small></>
          )}
        </Msg>
      )}
      {test?.ok && f.kind === "opcua" && (
        <div className="msg good" role="status">
          Connected to {test.result.product ?? "the server"} {test.result.version ?? ""} ({test.result.security}).
          {test.result.serverCertSha256 && st.serverCert !== test.result.serverCertSha256 && (
            <div className="trust">
              <span>Server certificate <span className="mono" title={fp(test.result.serverCertSha256)}>{short(test.result.serverCertSha256)}</span></span>
              <button className="btn ghost sm" onClick={() => setS("serverCert", test.result.serverCertSha256)}>Trust only this certificate</button>
            </div>
          )}
        </div>
      )}
      {test?.ok && f.kind === "modbus" && (
        <div className="msg good" role="status">
          Connected to {test.result.target}.
          {(test.result.values ?? []).length > 0 && (
            <span> {test.result.values.map((v) => `${v.name} = ${v.good ? v.value : `bad (${v.detail})`}`).join(" · ")}</span>
          )}
        </div>
      )}

      <TagTable kind={f.kind} tags={f.tags} onChange={(tags) => set("tags", tags)}
                onBrowse={f.kind === "opcua" && agent.status === "online" ? () => setBrowse(true) : null} />
      {browse && <Browser agent={agent} source={sourceForCommand()} sourceId={source.id} onClose={() => setBrowse(false)}
                          onAdd={(list) => { addTags(list); setBrowse(false); }} />}
    </Modal>
  );
}

function TagTable({ kind, tags, onChange, onBrowse }) {
  const upd = (i, k, v) => onChange(tags.map((t, j) => (j === i ? { ...t, [k]: v } : t)));
  const del = (i) => onChange(tags.filter((_, j) => j !== i));
  const add = () => onChange([...tags, { name: "", address: kind === "modbus" ? "HR1" : "", unit: "" }]);
  return (
    <div className="field">
      <div className="tag-h">
        <span className="label">Tags ({tags.length})</span>
        <div className="row-acts">
          {onBrowse && <button className="btn ghost sm" onClick={onBrowse}>Browse the server…</button>}
          <button className="btn ghost sm" onClick={add}>Add a tag</button>
        </div>
      </div>
      {tags.length === 0 ? <p className="hint">{kind === "opcua" ? "Browse the server and tick the tags to collect." : "Add the registers to read: HR1 (holding register), IR5, C3 (coil), DI2."}</p> : (
        <div className="tags">
          <div className="tag-row head"><span>Name</span><span>{kind === "opcua" ? "Node id" : "Address"}</span><span>Unit</span><span /></div>
          {tags.map((t, i) => (
            <div className="tag-row" key={i}>
              <input type="text" aria-label="Tag name" value={t.name ?? ""} onChange={(e) => upd(i, "name", e.target.value)} placeholder="name" />
              <input type="text" aria-label="Address" className="mono" value={t.address ?? ""} onChange={(e) => upd(i, "address", e.target.value)} title={t.address} />
              <input type="text" aria-label="Unit" value={t.unit ?? ""} onChange={(e) => upd(i, "unit", e.target.value)} placeholder="unit" />
              <button className="btn ghost sm" aria-label={`Remove ${t.name || t.address}`} onClick={() => del(i)}>✕</button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/** OPC UA address space of the server, browsed from the machine: open folders, tick variables, add them as tags. */
function Browser({ agent, source, sourceId, onClose, onAdd }) {
  const [path, setPath] = useState([{ nodeId: null, name: "Objects" }]);
  const [items, setItems] = useState(null);
  const [error, setError] = useState(null);
  const [picked, setPicked] = useState({});
  const node = path[path.length - 1];

  useEffect(() => {
    let live = true;
    setItems(null);
    setError(null);
    ask(agent.id, { kind: "browse", source, sourceId, nodeId: node.nodeId, limit: 1000 })
      .then((r) => live && setItems(r.items ?? []))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
    // source is fixed while the browser is open
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [node.nodeId]);

  const vars = (items ?? []).filter((i) => i.nodeClass === "Variable");
  const count = Object.keys(picked).length;
  const toggle = (i) => setPicked((p) => {
    const n = { ...p };
    if (n[i.nodeId]) delete n[i.nodeId]; else n[i.nodeId] = i;
    return n;
  });
  const name = (i) => [...path.slice(1).map((p) => p.name), i.name].filter((x) => !/^(Devices|Objects)$/i.test(x)).slice(-2).join("_");

  return (
    <Modal wide title="Browse the OPC UA server" subtitle={source.settings.endpoint} onClose={onClose}
           footer={<>
             <button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={!count} onClick={() => onAdd(Object.values(picked).map((i) => ({ name: i.tagName, address: i.nodeId, unit: "" })))}>
               Add {count || ""} tag{count === 1 ? "" : "s"}
             </button>
           </>}>
      <nav className="bc" aria-label="Folder">
        {path.map((p, i) => (
          <span key={i}>
            {i > 0 && <span className="muted"> / </span>}
            {i < path.length - 1 ? <button className="link" onClick={() => setPath(path.slice(0, i + 1))}>{p.name}</button> : <b>{p.name}</b>}
          </span>
        ))}
      </nav>
      <Msg>{error}</Msg>
      {!items && !error && <p className="muted">Asking the machine…</p>}
      {items && (
        <div className="browse">
          {items.length === 0 && <p className="muted">Empty.</p>}
          {vars.length > 1 && (
            <label className="check"><input type="checkbox" checked={vars.every((v) => picked[v.nodeId])}
              onChange={(e) => setPicked((p) => {
                const n = { ...p };
                vars.forEach((v) => (e.target.checked ? (n[v.nodeId] = { ...v, tagName: name(v) }) : delete n[v.nodeId]));
                return n;
              })} /> All variables here</label>
          )}
          {items.map((i) => i.nodeClass === "Variable" ? (
            <label key={i.nodeId} className="b-row">
              <input type="checkbox" checked={!!picked[i.nodeId]} onChange={() => toggle({ ...i, tagName: name(i) })} />
              <span className="b-name"><b className="ellipsis" title={i.name}>{i.name}</b><small className="mono ellipsis" title={i.nodeId}>{i.nodeId}</small></span>
              <span className={`b-val mono ellipsis${i.good === false ? " bad" : ""}`} title={String(i.value ?? "")}>{i.value == null ? "–" : String(i.value)}</span>
            </label>
          ) : (
            <button key={i.nodeId} className="b-row folder" onClick={() => setPath([...path, { nodeId: i.nodeId, name: i.name }])}>
              <span aria-hidden="true">▸</span>
              <span className="b-name"><b className="ellipsis" title={i.name}>{i.name}</b><small className="mono ellipsis" title={i.nodeId}>{i.nodeId}</small></span>
              <span />
            </button>
          ))}
        </div>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------------ live

function Live({ id }) {
  const { data, error } = usePoll(`agents/${id}/live`, 2_000);
  const [q, setQ] = useState("");
  const n = q.trim().toLowerCase();
  const rows = (data?.values ?? []).filter((v) => !n || `${v.machine} ${v.tag}`.toLowerCase().includes(n));
  return (
    <div className="stack">
      <div className="toolbar">
        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter by machine or tag" aria-label="Filter" />
        {data && <span className="muted small">{data.accepted.toLocaleString()} readings received since the gateway started{data.rejected ? ` · ${data.rejected} refused` : ""}</span>}
      </div>
      <Msg>{error}</Msg>
      <Card flush>
        <table>
          <thead><tr><th>Machine</th><th>Tag</th><th className="r">Value</th><th>Quality</th><th>Time</th></tr></thead>
          <tbody>
            {!data && <Empty cols={5}>Loading…</Empty>}
            {data && rows.length === 0 && <Empty cols={5}>{data.values.length ? "No tag matches the filter." : "No readings from this machine since the gateway started."}</Empty>}
            {rows.map((v) => (
              <tr key={`${v.machine}/${v.tag}`}>
                <td>{v.machine}</td>
                <td className="mono">{v.tag}</td>
                <td className="r mono nowrap">{v.value == null ? "–" : typeof v.value === "number" ? v.value.toLocaleString(undefined, { maximumFractionDigits: 6 }) : String(v.value)} {v.unit ?? ""}</td>
                <td><Status kind={v.quality === "GOOD" ? "good" : "bad"}>{v.quality}</Status>{v.detail && <small className="ellipsis" title={v.detail}>{v.detail}</small>}</td>
                <td className="nowrap" title={full(v.timestamp)}>{new Date(v.timestamp).toLocaleTimeString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}
