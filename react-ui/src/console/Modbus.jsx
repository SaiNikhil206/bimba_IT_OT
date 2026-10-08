import { useMemo, useState } from "react";
import { api, clone, same } from "./api.js";
import { modbusArea, validModbus } from "./model.js";
import { ActionBar, Input, Select, Seg } from "./ui.jsx";

export const gatewayUrl = () => `${window.location.protocol}//${window.location.hostname}:8090/`;

export default function Modbus({ config, draft, setDraft, reload }) {
  const devices = useMemo(() => Object.keys(draft.sources.modbus ?? {}).sort(), [draft.sources.modbus]);
  const [picked, setPicked] = useState(null);
  const [reads, setReads] = useState({});
  const device = devices.includes(picked) ? picked : devices[0];

  if (!device) {
    return (
      <>
        <Header />
        <div className="card empty">No tag in demo-tags.json reads from a Modbus device.</div>
      </>
    );
  }

  const conn = draft.sources.modbus[device];
  const points = draft.points.filter((p) => p.kind === "modbus" && p.device === device);
  const machines = [...new Set(points.map((p) => p.machine))];

  const setConn = (k, v) =>
    setDraft((d) => ({ ...d, sources: { ...d.sources, modbus: { ...d.sources.modbus, [device]: { ...d.sources.modbus[device], [k]: v } } } }));
  const setPoint = (path, k, v) => setDraft((d) => ({ ...d, points: d.points.map((p) => (p.path === path ? { ...p, [k]: v } : p)) }));

  const invalid =
    (!String(conn.host ?? "").trim() && "Host is required") ||
    (!(conn.port >= 1 && conn.port <= 65535) && "Port must be 1–65535") ||
    (!(conn.unitId >= 0 && conn.unitId <= 255) && "Unit ID must be 0–255") ||
    points.map((p) => (!validModbus(p.address) ? `${p.tag}: register must look like HR1, IR1, C1 or DI1` : !(Number(p.scale) > 0) ? `${p.tag}: scale must be > 0` : null)).find(Boolean) ||
    null;

  const devicesChanged = () =>
    Object.keys(draft.sources.modbus).filter(
      (dev) =>
        !same(draft.sources.modbus[dev], config.sources.modbus[dev]) ||
        !same(draft.points.filter((p) => p.device === dev && p.kind === "modbus"), config.points.filter((p) => p.device === dev && p.kind === "modbus"))
    );

  const save = async () => {
    const msgs = [];
    for (const dev of devicesChanged()) {
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
      <Header />
      <p className="note">
        Ignition keeps its own copy of the connection to device <b>{device}</b> (Gateway → Config → OPC UA → Device
        Connections, <a href={gatewayUrl()} target="_blank" rel="noreferrer">open gateway</a>). The register mapping below
        is applied to Ignition automatically when you save. Host and port here are what <b>Test connection</b> uses, so
        keep them the same as the gateway's.
      </p>
      <div className="card">
        <div className="card-h">
          <div>
            <h2>Ignition device {device}</h2>
            <div className="muted small">Used by {machines.join(", ") || "no machine"}</div>
          </div>
          <Seg label="Device" items={devices} value={device} onChange={(v) => { setPicked(v); setReads({}); }} />
        </div>
        <div className="card-b">
          <div className="fields">
            <Input label="Host / IP address" obj={conn} k="host" onChange={setConn} mono invalid={!String(conn.host ?? "").trim()} />
            <Input label="Port" obj={conn} k="port" onChange={setConn} type="number" mono />
            <Input label="Unit ID" obj={conn} k="unitId" onChange={setConn} type="number" mono
                   hint="Used for registers without a unit prefix (write 2.HR1 for unit 2)." />
            <Input label="Timeout (ms)" obj={conn} k="timeoutMs" onChange={setConn} type="number" mono />
            <Select label="Addressing" obj={{ v: String(!!conn.zeroBased) }} k="v"
                    onChange={(_, v) => setConn("zeroBased", v === "true")}
                    options={[["false", "One-based (HR1 = register 0, Ignition default)"], ["true", "Zero-based (HR0 = register 0)"]]} />
          </div>
        </div>
        <ActionBar onTest={test} onSave={save} onRevert={revert} dirty={devicesChanged().length > 0}
                   invalid={invalid} />
      </div>

      <div className="card">
        <div className="card-h">
          <h2>Registers</h2>
          <span className="muted" style={{ fontSize: 13.5 }}>{points.length} data points · from demo-tags.json</span>
        </div>
        <div className="tbl">
          <table>
            <thead>
              <tr>
                <th>Data point</th><th>Register</th><th>Type</th><th>Scale</th><th>Unit</th>
                {tested && <th>Test read</th>}
              </tr>
            </thead>
            <tbody>
              {points.map((p) => {
                const area = modbusArea(p.address);
                const rd = reads[p.path];
                return (
                  <tr key={p.path}>
                    <td className="mono">{p.machine}/{p.tag}</td>
                    <td>
                      <input type="text" className={`m${validModbus(p.address) ? "" : " invalid"}`} style={{ width: 90 }}
                             aria-label={`${p.tag} register`} value={p.address ?? ""} spellCheck={false}
                             onChange={(e) => setPoint(p.path, "address", e.target.value)} />
                    </td>
                    <td className="mono muted">{area === "C" || area === "DI" ? "BOOL" : area ? "UINT16" : "?"}</td>
                    <td>
                      <input type="number" step="any" min="0" className={`m${Number(p.scale) > 0 ? "" : " invalid"}`} style={{ width: 90 }}
                             aria-label={`${p.tag} scale`} value={p.scale ?? ""} disabled={area === "C" || area === "DI"}
                             onChange={(e) => setPoint(p.path, "scale", e.target.value === "" ? "" : Number(e.target.value))} />
                    </td>
                    <td>
                      <input type="text" style={{ width: 80 }} aria-label={`${p.tag} unit`} value={p.unit ?? ""}
                             onChange={(e) => setPoint(p.path, "unit", e.target.value)} />
                    </td>
                    {tested && <ReadCell rd={rd} p={p} area={area} />}
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

function ReadCell({ rd, p, area }) {
  if (!rd) return <td className="read muted">–</td>;
  if (rd.error) return <td className="read err">{rd.error}</td>;
  if (area === "C" || area === "DI") return <td className="read">{rd.raw} ({rd.raw ? "ON" : "OFF"})</td>;
  const v = rd.raw * Number(p.scale || 1);
  return (
    <td className="read">
      {rd.rawHex} <span className="muted">→</span> {Number(v.toFixed(4)).toLocaleString()} {p.unit}
    </td>
  );
}

function Header() {
  return (
    <>
      <p className="kicker">OT source</p>
      <h1>Configure Modbus TCP</h1>
      <p className="lead">Tell the gateway how to reach each Modbus device and which registers to read.</p>
    </>
  );
}
