import { useMemo, useState } from "react";
import { api, clone, same } from "../../console/api.js";
import { modbusArea, validModbus, validNode } from "../../console/model.js";
import { fmtNum, good, isBool, since, specOf, unit } from "../lib.js";
import { Badge } from "../viz.jsx";
import { MachineChips, bridgePath, sourceHealth } from "./common.jsx";
import { ActionBar, Input, Select } from "./forms.jsx";

// OT source pages: Modbus TCP devices and OPC UA connections (same API + rules as /console).

const gatewayUrl = () => `${window.location.protocol}//${window.location.hostname}:8090/`;
const POLICIES = ["None", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"];

function liveOf(live, p) {
  return live.machines[p.machine]?.tags?.[p.tag];
}

function LiveCell({ t }) {
  if (!t) return <td className="mono muted">waiting</td>;
  if (!good(t)) return <td><span className="q critical">{t.quality}</span></td>;
  const v = isBool(t) ? (t.num ? "ON" : "OFF") : t.num != null ? fmtNum(t.num) : t.text;
  return (
    <td className={`mono live${t.flash && Date.now() - t.flash < 1200 ? " fresh" : ""}`}>
      {v} <small>{unit(t.unit)}</small><span className="age">{since(t.time)}</span>
    </td>
  );
}

function DevicePicker({ kind, items, value, link }) {
  if (items.length < 2) return null;
  return (
    <div className="cr-seg" role="group" aria-label="Device">
      {items.map((it) => (
        <a key={it} href={bridgePath(kind, it)} onClick={link(bridgePath(kind, it))} className={`seg-a${it === value ? " on" : ""}`}>{it}</a>
      ))}
    </div>
  );
}

function Header({ kicker, title, lead }) {
  return (
    <div className="bx-title">
      <span className="k">{kicker}</span>
      <h3>{title}</h3>
      <p className="muted">{lead}</p>
    </div>
  );
}

function SourceStatus({ config, machines, kind, device, link }) {
  const h = sourceHealth(config, machines, kind, device);
  const last = h.ms.map((m) => m.lastSeen).filter(Boolean).sort().pop();
  return (
    <section className="cr-panel bx-live">
      <div className="cr-ph">
        <h2>Live from Ignition</h2>
        <Badge state={h.state}>{{ good: "Reading", warning: "Degraded", critical: "No signal", neutral: "No data" }[h.state]}</Badge>
      </div>
      <div className="bx-meter">
        <div><span>Tags GOOD</span><b>{h.ok}<small> / {h.total}</small></b></div>
        <div><span>Last value</span><b>{last ? since(last) : "–"}<small>{last && since(last) !== "now" ? " ago" : ""}</small></b></div>
      </div>
      <span className="k">Machines on this connection</span>
      <MachineChips ids={h.ids} machines={machines} link={link} />
      {h.state === "critical" && (
        <p className="bx-note bad">
          Every tag on {device} reads BAD. Check the device is running and its connection in the BIMBA Gateway.
        </p>
      )}
    </section>
  );
}

// ---------------------------------------------------------------- Modbus TCP

export function Modbus({ config, draft, setDraft, reload, live, machines, link, item }) {
  const devices = useMemo(() => Object.keys(draft.sources.modbus ?? {}).sort(), [draft.sources.modbus]);
  const [reads, setReads] = useState({});
  const device = devices.includes(item) ? item : devices[0];
  const head = <Header kicker="OT source" title="Modbus TCP" lead="How the gateway reaches each Modbus device and which registers it reads." />;
  if (!device) return <>{head}<section className="cr-panel empty">No tag in demo-tags.json reads from a Modbus device.</section></>;

  const conn = draft.sources.modbus[device];
  const points = draft.points.filter((p) => p.kind === "modbus" && p.device === device);
  const setConn = (k, v) =>
    setDraft((d) => ({ ...d, sources: { ...d.sources, modbus: { ...d.sources.modbus, [device]: { ...d.sources.modbus[device], [k]: v } } } }));
  const setPoint = (path, k, v) => setDraft((d) => ({ ...d, points: d.points.map((p) => (p.path === path ? { ...p, [k]: v } : p)) }));

  const invalid =
    (!String(conn.host ?? "").trim() && "Host is required") ||
    (!(conn.port >= 1 && conn.port <= 65535) && "Port must be 1–65535") ||
    (!(conn.unitId >= 0 && conn.unitId <= 255) && "Unit ID must be 0–255") ||
    points.map((p) => (!validModbus(p.address) ? `${p.tag}: register must look like HR1, IR1, C1 or DI1` : !(Number(p.scale) > 0) ? `${p.tag}: scale must be > 0` : null)).find(Boolean) ||
    null;

  const changed = () =>
    Object.keys(draft.sources.modbus).filter(
      (dev) =>
        !same(draft.sources.modbus[dev], config.sources.modbus[dev]) ||
        !same(draft.points.filter((p) => p.device === dev && p.kind === "modbus"), config.points.filter((p) => p.device === dev && p.kind === "modbus"))
    );

  const save = async () => {
    const msgs = [];
    for (const dev of changed()) {
      const r = await api("sources/modbus/", {
        method: "PUT",
        body: {
          device: dev,
          connection: draft.sources.modbus[dev],
          points: draft.points.filter((p) => p.kind === "modbus" && p.device === dev)
            .map((p) => ({ path: p.path, address: p.address.trim().toUpperCase(), scale: Number(p.scale), unit: p.unit })),
        },
      });
      msgs.push(r.message);
    }
    await reload({ keepDraftFor: "modbus" });
    return { ok: true, message: msgs.join(" ") };
  };
  const revert = () =>
    setDraft((d) => ({
      ...d,
      sources: { ...d.sources, modbus: clone(config.sources.modbus) },
      points: d.points.map((p) => (p.kind === "modbus" ? clone(config.points.find((x) => x.path === p.path)) : p)),
    }));
  const test = async () => {
    const r = await api("test/modbus/", { method: "POST", body: { connection: conn, points: points.map((p) => ({ path: p.path, address: p.address })) } });
    setReads(Object.fromEntries((r.reads ?? []).map((x) => [x.path, x])));
    return r;
  };
  const tested = Object.keys(reads).length > 0;

  return (
    <>
      <div className="bx-row">{head}<DevicePicker kind="modbus" items={devices} value={device} link={link} /></div>
      <div className="bx-split">
        <section className="cr-panel">
          <div className="cr-ph">
            <div><h2>Ignition device {device}</h2> <span className="proto modbus">MODBUS TCP</span></div>
            <a className="bx-ext" href={gatewayUrl()} target="_blank" rel="noreferrer">Open gateway ↗</a>
          </div>
          <div className="bx-form">
            <Input label="Host / IP address" obj={conn} k="host" onChange={setConn} mono invalid={!String(conn.host ?? "").trim()} />
            <Input label="Port" obj={conn} k="port" onChange={setConn} type="number" mono />
            <Input label="Unit ID" obj={conn} k="unitId" onChange={setConn} type="number" mono
                   hint="For registers without a unit prefix (2.HR1 reads unit 2)." />
            <Input label="Timeout (ms)" obj={conn} k="timeoutMs" onChange={setConn} type="number" mono />
            <Select label="Addressing" obj={{ v: String(!!conn.zeroBased) }} k="v" wide
                    onChange={(_, v) => setConn("zeroBased", v === "true")}
                    options={[["false", "One-based (HR1 = register 0, Ignition default)"], ["true", "Zero-based (HR0 = register 0)"]]} />
          </div>
          <p className="bx-note">
            Ignition keeps its own copy of this connection (Gateway → Config → OPC UA → Device Connections). The register
            mapping is applied to the Ignition tags when you save; host and port here are what <b>Test connection</b> uses.
          </p>
          <ActionBar onTest={test} onSave={save} onRevert={revert} dirty={changed().length > 0} invalid={invalid} />
        </section>
        <SourceStatus config={config} machines={machines} kind="modbus" device={device} link={link} />
      </div>

      <section className="cr-panel">
        <div className="cr-ph">
          <h2>Registers</h2>
          <span className="muted">{points.length} data points · demo-tags.json · live values from PostgreSQL</span>
        </div>
        <div className="bx-tbl">
          <table>
            <thead>
              <tr><th>Data point</th><th>Register</th><th>Type</th><th>Scale</th><th>Unit</th><th>Live value</th>{tested && <th>Test read</th>}</tr>
            </thead>
            <tbody>
              {points.map((p) => {
                const area = modbusArea(p.address);
                const rd = reads[p.path];
                return (
                  <tr key={p.path}>
                    <td><b>{specOf(p.tag).label}</b><div className="mono muted">{p.machine}/{p.tag}</div></td>
                    <td><input type="text" className={`mono${validModbus(p.address) ? "" : " invalid"}`} style={{ width: 92 }}
                               aria-label={`${p.tag} register`} value={p.address ?? ""} spellCheck={false}
                               onChange={(e) => setPoint(p.path, "address", e.target.value)} /></td>
                    <td className="mono muted">{area === "C" || area === "DI" ? "BOOL" : area ? "UINT16" : "?"}</td>
                    <td><input type="number" step="any" min="0" className={`mono${Number(p.scale) > 0 ? "" : " invalid"}`} style={{ width: 92 }}
                               aria-label={`${p.tag} scale`} value={p.scale ?? ""} disabled={area === "C" || area === "DI"}
                               onChange={(e) => setPoint(p.path, "scale", e.target.value === "" ? "" : Number(e.target.value))} /></td>
                    <td><input type="text" style={{ width: 80 }} aria-label={`${p.tag} unit`} value={p.unit ?? ""}
                               onChange={(e) => setPoint(p.path, "unit", e.target.value)} /></td>
                    <LiveCell t={liveOf(live, p)} />
                    {tested && <ModbusRead rd={rd} p={p} area={area} />}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}

function ModbusRead({ rd, p, area }) {
  if (!rd) return <td className="mono muted">–</td>;
  if (rd.error) return <td className="mono bad">{rd.error}</td>;
  if (area === "C" || area === "DI") return <td className="mono read">{rd.raw} <span className="muted">→</span> {rd.raw ? "ON" : "OFF"}</td>;
  const v = rd.raw * Number(p.scale || 1);
  return <td className="mono read">{rd.rawHex} <span className="muted">→</span> {Number(v.toFixed(4)).toLocaleString()} {unit(p.unit)}</td>;
}

// ---------------------------------------------------------------- OPC UA

export function OpcUa({ config, draft, setDraft, reload, live, machines, link, item }) {
  const servers = useMemo(() => Object.keys(draft.sources.opcua ?? {}).sort(), [draft.sources.opcua]);
  const [reads, setReads] = useState({});
  const server = servers.includes(item) ? item : servers[0];
  const head = <Header kicker="OT source" title="OPC UA" lead="Server endpoint and security, and the nodes the gateway subscribes to." />;
  if (!server) return <>{head}<section className="cr-panel empty">No tag in demo-tags.json reads from an OPC UA connection.</section></>;

  const conn = draft.sources.opcua[server];
  const points = draft.points.filter((p) => p.kind === "opcua" && p.device === server);
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
    const r = await api("test/opcua/", { method: "POST", body: { device: server, connection: conn, points: points.map((p) => ({ path: p.path, node: p.node })) } });
    setReads(Object.fromEntries((r.reads ?? []).map((x) => [x.path, x])));
    return r.offered && r.ok ? { ...r, message: `${r.message} Server offers ${r.offered}.` } : r;
  };
  const tested = Object.keys(reads).length > 0;

  return (
    <>
      <div className="bx-row">{head}<DevicePicker kind="opcua" items={servers} value={server} link={link} /></div>
      <div className="bx-split">
        <section className="cr-panel">
          <div className="cr-ph">
            <div><h2>OPC connection {server}</h2> <span className="proto opcua">OPC UA</span></div>
            <a className="bx-ext" href={gatewayUrl()} target="_blank" rel="noreferrer">Open gateway ↗</a>
          </div>
          <div className="bx-form">
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
                       placeholder={conn.passwordSet ? "•••••••• (unchanged)" : ""} hint="Stored in console-sources.json for testing only." />
              </>
            )}
          </div>
          <p className="bx-note">
            Ignition connects through its OPC connection <b>{server}</b> (Gateway → Config → OPC Client → OPC Connections).
            NodeIds you save are applied to the Ignition tags; endpoint and security are what <b>Test connection</b> uses.
          </p>
          <ActionBar onTest={test} onSave={save} onRevert={revert} dirty={changed().length > 0} invalid={invalid} />
        </section>
        <SourceStatus config={config} machines={machines} kind="opcua" device={server} link={link} />
      </div>

      <section className="cr-panel">
        <div className="cr-ph">
          <h2>Subscribed nodes</h2>
          <span className="muted">{points.length} data points · demo-tags.json · live values from PostgreSQL</span>
        </div>
        <div className="bx-tbl">
          <table>
            <thead><tr><th>Data point</th><th>NodeId</th><th>Type</th><th>Live value</th>{tested && <th>Test read</th>}</tr></thead>
            <tbody>
              {points.map((p) => {
                const rd = reads[p.path];
                return (
                  <tr key={p.path}>
                    <td><b>{specOf(p.tag).label}</b><div className="mono muted">{p.machine}/{p.tag}</div></td>
                    <td><input type="text" className={`mono${validNode(p.node) ? "" : " invalid"}`} style={{ minWidth: 260 }}
                               aria-label={`${p.tag} NodeId`} value={p.node ?? ""} spellCheck={false}
                               onChange={(e) => setPoint(p.path, e.target.value)} /></td>
                    <td className="mono muted">{rd?.type ?? p.dataType}</td>
                    <LiveCell t={liveOf(live, p)} />
                    {tested && (
                      <td className={`mono ${rd?.error ? "bad" : "read"}`}>
                        {!rd ? "–" : rd.error ? rd.error : <>{String(rd.value)} <span className="muted">{rd.status}</span></>}
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}
