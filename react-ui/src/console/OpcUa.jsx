import { useMemo, useState } from "react";
import { api, clone, same } from "./api.js";
import { validNode } from "./model.js";
import { gatewayUrl } from "./Modbus.jsx";
import { ActionBar, Input, Select, Seg } from "./ui.jsx";

const POLICIES = ["None", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"];

export default function OpcUa({ config, draft, setDraft, reload }) {
  const servers = useMemo(() => Object.keys(draft.sources.opcua ?? {}).sort(), [draft.sources.opcua]);
  const [picked, setPicked] = useState(null);
  const [reads, setReads] = useState({});
  const server = servers.includes(picked) ? picked : servers[0];

  if (!server) {
    return (
      <>
        <Header />
        <div className="card empty">No tag in demo-tags.json reads from an OPC UA connection.</div>
      </>
    );
  }

  const conn = draft.sources.opcua[server];
  const points = draft.points.filter((p) => p.kind === "opcua" && p.device === server);
  const machines = [...new Set(points.map((p) => p.machine))];

  const setConn = (k, v) =>
    setDraft((d) => {
      const next = { ...d.sources.opcua[server], [k]: v };
      // policy None and mode None only go together
      if (k === "policy") next.mode = v === "None" ? "None" : next.mode === "None" ? "SignAndEncrypt" : next.mode;
      if (k === "mode") next.policy = v === "None" ? "None" : next.policy === "None" ? "Basic256Sha256" : next.policy;
      return { ...d, sources: { ...d.sources, opcua: { ...d.sources.opcua, [server]: next } } };
    });
  const setPoint = (path, v) => setDraft((d) => ({ ...d, points: d.points.map((p) => (p.path === path ? { ...p, node: v } : p)) }));

  const invalid =
    (!String(conn.endpoint ?? "").startsWith("opc.tcp://") && "Endpoint URL must start with opc.tcp://") ||
    points.map((p) => (!validNode(p.node) ? `${p.tag}: NodeId must look like ns=2;s=Name` : null)).find(Boolean) ||
    null;

  const changed = () =>
    Object.keys(draft.sources.opcua).filter(
      (s) =>
        !same(draft.sources.opcua[s], config.sources.opcua[s]) ||
        !same(draft.points.filter((p) => p.device === s && p.kind === "opcua"), config.points.filter((p) => p.device === s && p.kind === "opcua"))
    );

  const save = async () => {
    const msgs = [];
    for (const s of changed()) {
      const r = await api("sources/opcua/", {
        method: "PUT",
        body: {
          device: s,
          connection: draft.sources.opcua[s],
          points: draft.points.filter((p) => p.kind === "opcua" && p.device === s).map((p) => ({ path: p.path, node: p.node.trim() })),
        },
      });
      msgs.push(r.message);
    }
    await reload({ keepDraftFor: "opcua" });
    return { ok: true, message: msgs.join(" ") };
  };

  const revert = () =>
    setDraft((d) => ({
      ...d,
      sources: { ...d.sources, opcua: clone(config.sources.opcua) },
      points: d.points.map((p) => (p.kind === "opcua" ? clone(config.points.find((x) => x.path === p.path)) : p)),
    }));

  const test = async () => {
    const r = await api("test/opcua/", {
      method: "POST",
      body: { device: server, connection: conn, points: points.map((p) => ({ path: p.path, node: p.node })) },
    });
    setReads(Object.fromEntries((r.reads ?? []).map((x) => [x.path, x])));
    return r.offered && r.ok ? { ...r, message: `${r.message} Server offers ${r.offered}.` } : r;
  };

  const tested = Object.keys(reads).length > 0;

  return (
    <>
      <Header />
      <p className="note">
        Ignition connects to this server through its OPC connection <b>{server}</b> (Gateway → Config → OPC Client → OPC
        Connections, <a href={gatewayUrl()} target="_blank" rel="noreferrer">open gateway</a>). NodeIds you save here are
        applied to the Ignition tags automatically. Endpoint and security are what <b>Test connection</b> uses.
      </p>
      <div className="card">
        <div className="card-h">
          <div>
            <h2>OPC connection {server}</h2>
            <div className="muted small">Used by {machines.join(", ") || "no machine"}</div>
          </div>
          <Seg label="Connection" items={servers} value={server} onChange={(v) => { setPicked(v); setReads({}); }} />
        </div>
        <div className="card-b">
          <div className="fields">
            <Input label="Endpoint URL" obj={conn} k="endpoint" onChange={setConn} mono wide
                   invalid={!String(conn.endpoint ?? "").startsWith("opc.tcp://")} />
            <Select label="Security policy" obj={conn} k="policy" onChange={setConn} options={POLICIES} />
            <Select label="Security mode" obj={conn} k="mode" onChange={setConn} options={["None", "Sign", "SignAndEncrypt"]} />
            <Select label="Authentication" obj={conn} k="auth" onChange={setConn} options={["Anonymous", "Username"]} />
            <Input label="Timeout (ms)" obj={conn} k="timeoutMs" onChange={setConn} type="number" mono />
            {conn.auth === "Username" && (
              <>
                <Input label="Username" obj={conn} k="username" onChange={setConn} mono />
                <Input label="Password" obj={conn} k="password" onChange={setConn} type="password" mono
                       placeholder={conn.passwordSet ? "•••••••• (unchanged)" : ""}
                       hint="Stored in console-sources.json for testing only." />
              </>
            )}
          </div>
        </div>
        <ActionBar onTest={test} onSave={save} onRevert={revert} dirty={changed().length > 0}
                   invalid={invalid} />
      </div>

      <div className="card">
        <div className="card-h">
          <h2>Subscribed nodes</h2>
          <span className="muted" style={{ fontSize: 13.5 }}>{points.length} data points · from demo-tags.json</span>
        </div>
        <div className="tbl">
          <table>
            <thead>
              <tr><th>Data point</th><th>NodeId</th><th>Type</th>{tested && <th>Test read</th>}</tr>
            </thead>
            <tbody>
              {points.map((p) => {
                const rd = reads[p.path];
                return (
                  <tr key={p.path}>
                    <td className="mono">{p.machine}/{p.tag}</td>
                    <td>
                      <input type="text" className={`m${validNode(p.node) ? "" : " invalid"}`} style={{ minWidth: 280 }}
                             aria-label={`${p.tag} NodeId`} value={p.node ?? ""} spellCheck={false}
                             onChange={(e) => setPoint(p.path, e.target.value)} />
                    </td>
                    <td className="mono muted">{rd?.type ?? p.dataType}</td>
                    {tested && (
                      <td className={`read${rd?.error ? " err" : ""}`}>
                        {!rd ? "–" : rd.error ? rd.error : <>{String(rd.value)} <span className="muted">{rd.status}</span></>}
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

function Header() {
  return (
    <>
      <p className="kicker">OT source</p>
      <h1>Configure OPC UA</h1>
      <p className="lead">Set the server endpoint and security, then choose the nodes to subscribe to.</p>
    </>
  );
}
