import { useCallback, useEffect, useId, useState } from "react";
import { api, clone, same } from "../console/api.js";
import { modbusArea, validModbus, validNode } from "../console/model.js";
import { fmtNum, good, hms, isBool, since, specOf, unit } from "./data.js";
import { destinations, ago } from "./model.js";
import { to } from "./paths.js";
import { Card, Empty, PageHeader, Status } from "./ui.jsx";

// Settings = the Bridge Console: field connections (Modbus TCP, OPC UA) and destinations (Boomi Event
// Streams, Kafka). Same API and save rules as /console; the plugin reloads changes within ~10 s.

const gatewayUrl = () => `${window.location.protocol}//${window.location.hostname}:8088/web/config/`;

export default function Settings({ kind, item, machines, plugin, link, setGuard, bumpCfg }) {
  const [config, setConfig] = useState(null);
  const [error, setError] = useState(null);
  const reload = useCallback(async () => {
    try {
      setConfig(await api("config/"));
      setError(null);
    } catch (e) {
      setError(e.message);
    }
  }, []);
  useEffect(() => {
    reload();
  }, [reload]);
  useEffect(() => () => setGuard(null), [setGuard]);

  const back = <a className="op-back" href={to("settings")} onClick={link(to("settings"))}>← Settings</a>;
  if (error) return <><PageHeader title="Settings" /><Card><Empty>Could not load the gateway configuration: {error}. <button className="op-btn ghost" onClick={reload}>Retry</button></Empty></Card></>;
  if (!config) return <><PageHeader title="Settings" /><Card><Empty>Loading configuration…</Empty></Card></>;

  const saved = async () => {
    await reload();
    bumpCfg();
  };
  const common = { config, machines, plugin, link, back, setGuard, saved };
  if (kind === "modbus" && config.sources.modbus?.[item ?? Object.keys(config.sources.modbus)[0]]) return <ModbusForm key={item} device={item ?? Object.keys(config.sources.modbus)[0]} {...common} />;
  if (kind === "opcua" && config.sources.opcua?.[item ?? Object.keys(config.sources.opcua)[0]]) return <OpcUaForm key={item} device={item ?? Object.keys(config.sources.opcua)[0]} {...common} />;
  if (kind === "eventstreams") return <EventStreamsForm {...common} />;
  if (kind === "kafka") return <KafkaForm {...common} />;
  return <SettingsHome {...common} />;
}

// ---------------------------------------------------------------- list

function usedBy(config, kind, device) {
  return [...new Set(config.points.filter((p) => p.kind === kind && p.device === device).map((p) => p.machine))];
}

function connState(config, machines, kind, device) {
  const ids = usedBy(config, kind, device);
  const tags = machines.filter((m) => ids.includes(m.machineId)).flatMap((m) => Object.values(m.tags));
  const ok = tags.filter(good).length;
  return !tags.length ? ["neutral", "No data"] : ok === tags.length ? ["good", "Reading"] : ok ? ["warning", "Partial"] : ["critical", "No signal"];
}

function SettingsHome({ config, machines, plugin, link }) {
  const sources = [
    ...Object.entries(config.sources.modbus ?? {}).map(([dev, c]) => ({ kind: "modbus", dev, type: "Modbus TCP", addr: `${c.host}:${c.port} · unit ${c.unitId}`, c })),
    ...Object.entries(config.sources.opcua ?? {}).map(([dev, c]) => ({ kind: "opcua", dev, type: "OPC UA", addr: c.endpoint, c })),
  ];
  const dests = destinations(plugin);
  const find = (t) => dests.find((d) => d.transport === t);
  const ingest = dests.find((d) => d.label === "BIMBA Ingest");
  const targets = [
    { key: "eventstreams", name: "Boomi Event Streams", role: "Primary", addr: config.eventstreams?.url ? `topic ${decodeURIComponent(config.eventstreams.url.split("/").pop())}` : "not set", d: find("eventstreams"),
      test: () => api("test/eventstreams/", { method: "POST", body: { ...config.eventstreams, token: null } }) },
    { key: null, name: "BIMBA Ingest", role: "Failover", addr: ingest?.endpoint ?? "http://django:8000/api/ingest/", d: ingest, note: "Set in config.json" },
    { key: "kafka", name: "Kafka", role: "Copy", addr: `${config.kafka?.bootstrapServers ?? "–"} · ${(config.kafka?.topic ?? "").replaceAll("{plantId}", config.plantId ?? "")}`, d: find("kafka"),
      test: () => api("test/kafka/", { method: "POST", body: { ...config.kafka } }) },
  ];
  return (
    <>
      <PageHeader title="Settings" subtitle="Where the gateway reads data from, and where it delivers it." />
      <Card title="Field connections" subtitle="Devices the edge gateway reads." flush>
        <ul className="op-list">
          {sources.map((s) => {
            const [st, stText] = connState(config, machines, s.kind, s.dev);
            const pts = config.points.filter((p) => p.kind === s.kind && p.device === s.dev);
            const test = () => api(`test/${s.kind}/`, {
              method: "POST",
              body: s.kind === "modbus"
                ? { connection: s.c, points: pts.map((p) => ({ path: p.path, address: p.address })) }
                : { device: s.dev, connection: { ...s.c, password: null }, points: pts.map((p) => ({ path: p.path, node: p.node })) },
            });
            return <Row key={s.kind + s.dev} title={s.dev} type={s.type} addr={s.addr} extra={`Used by ${usedBy(config, s.kind, s.dev).join(", ") || "no machine"}`}
                        state={st} stateText={stText} test={test} href={to("settings", s.kind, s.dev)} link={link} />;
          })}
          <Row title="Gateway expressions" type="Computed" addr="Calculated inside Ignition" extra={`Used by ${[...new Set(config.points.filter((p) => p.kind === "expression").map((p) => p.machine))].join(", ") || "no machine"}`}
               state="good" stateText="Running" />
        </ul>
      </Card>
      <Card title="Destinations" subtitle="Where every batch is delivered." flush>
        <ul className="op-list">
          {targets.map((t) => (
            <Row key={t.name} title={t.name} type={t.role} addr={t.addr} extra={t.note}
                 state={t.d?.state ?? "neutral"} stateText={t.d?.text ?? "Not running"} test={t.test}
                 href={t.key ? to("settings", t.key) : null} link={link} />
          ))}
        </ul>
      </Card>
    </>
  );
}

function Row({ title, type, addr, extra, state, stateText, test, href, link }) {
  const [res, setRes] = useState(null);
  const [busy, setBusy] = useState(false);
  const run = async (e) => {
    e.stopPropagation();
    setBusy(true);
    setRes(null);
    try {
      const r = await test();
      setRes({ ok: r?.ok !== false, text: r?.message ?? "OK" });
    } catch (err) {
      setRes({ ok: false, text: err.message });
    } finally {
      setBusy(false);
    }
  };
  return (
    <li>
      <div className="main">
        <b>{title}</b>
        <span className="sub">{type} · <span className="mono">{addr}</span></span>
        {extra && <span className="sub">{extra}</span>}
        {res && <span className={`res ${res.ok ? "ok" : "bad"}`}>{res.ok ? "✓" : "✕"} {res.text}</span>}
      </div>
      <Status state={state}>{stateText}</Status>
      <div className="acts">
        {test && <button className="op-btn ghost" onClick={run} disabled={busy}>{busy ? "Testing…" : "Test"}</button>}
        {href && <a className="op-btn" href={href} onClick={link(href)}>Edit</a>}
      </div>
    </li>
  );
}

// ---------------------------------------------------------------- form pieces

function Field({ label, hint, error, wide, children }) {
  const id = useId();
  return (
    <div className={`op-field${wide ? " wide" : ""}`}>
      <label htmlFor={id}>{label}</label>
      {children(id)}
      {(error || hint) && <span className={error ? "err" : "hint"}>{error || hint}</span>}
    </div>
  );
}

function Text({ label, hint, wide, obj, k, set, type = "text", mono, invalid, placeholder, disabled }) {
  return (
    <Field label={label} hint={hint} wide={wide} error={typeof invalid === "string" ? invalid : null}>
      {(id) => (
        <input id={id} type={type} className={`${mono ? "mono" : ""}${invalid ? " invalid" : ""}`} value={obj[k] ?? ""} placeholder={placeholder}
               disabled={disabled} spellCheck={false} autoComplete={type === "password" ? "new-password" : "off"}
               onChange={(e) => set(k, type === "number" ? (e.target.value === "" ? "" : Number(e.target.value)) : e.target.value)} />
      )}
    </Field>
  );
}

function Choice({ label, hint, wide, obj, k, set, options, disabled }) {
  return (
    <Field label={label} hint={hint} wide={wide}>
      {(id) => (
        <select id={id} value={obj[k] ?? ""} disabled={disabled} onChange={(e) => set(k, e.target.value)}>
          {options.map((o) => {
            const [v, l] = Array.isArray(o) ? o : [o, o];
            return <option key={String(v)} value={v}>{l}</option>;
          })}
        </select>
      )}
    </Field>
  );
}

function Switch({ label, obj, k, set }) {
  const id = useId();
  return (
    <div className="op-field wide">
      <label className="op-switch" htmlFor={id}>
        <input id={id} type="checkbox" checked={!!obj[k]} onChange={(e) => set(k, e.target.checked)} />
        <span className="sw" aria-hidden="true"><i /></span>{label}
      </label>
    </div>
  );
}

function Advanced({ children }) {
  return (
    <details className="op-adv">
      <summary>Advanced settings</summary>
      <div className="op-form">{children}</div>
    </details>
  );
}

/** Test / Save / Revert, with one result line. Also registers the unsaved-changes guard. */
function Actions({ dirty, invalid, onTest, onSave, onRevert, setGuard }) {
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(null);
  useEffect(() => {
    setGuard(dirty ? "You have unsaved changes. Leave and discard them?" : null);
    const on = (e) => {
      if (dirty) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", on);
    return () => window.removeEventListener("beforeunload", on);
  }, [dirty, setGuard]);
  const run = async (kind, fn) => {
    setBusy(kind);
    setMsg(null);
    try {
      const r = await fn();
      setMsg({ ok: r?.ok !== false, text: r?.message ?? (kind === "save" ? "Saved." : "OK") });
    } catch (e) {
      setMsg({ ok: false, text: e.message });
    } finally {
      setBusy(null);
    }
  };
  return (
    <div className="op-actions">
      <button className="op-btn ghost" disabled={!!busy} onClick={() => run("test", onTest)}>{busy === "test" ? "Testing…" : "Test connection"}</button>
      <span className={`res ${msg ? (msg.ok ? "ok" : "bad") : ""}`} aria-live="polite">
        {msg ? `${msg.ok ? "✓" : "✕"} ${msg.text}` : invalid ? <span className="bad">{invalid}</span> : null}
      </span>
      <span className="grow" />
      {dirty && !busy && <span className="dirty">Unsaved changes</span>}
      <button className="op-btn ghost" disabled={!!busy || !dirty} onClick={() => { onRevert(); setMsg(null); }}>Revert</button>
      <button className="op-btn primary" disabled={!!busy || !dirty || !!invalid} onClick={() => run("save", onSave)}>{busy === "save" ? "Saving…" : "Save"}</button>
    </div>
  );
}

function liveCell(machines, p) {
  const t = machines.find((m) => m.machineId === p.machine)?.tags?.[p.tag];
  if (!t) return <span className="sub">waiting</span>;
  if (!good(t)) return <Status state="critical">{t.quality}</Status>;
  return <><b className="num">{isBool(t) ? (t.num ? "On" : "Off") : t.num != null ? fmtNum(t.num) : t.text}</b> <span className="sub">{unit(t.unit)} · {ago(t.time)}</span></>;
}

function FormHeader({ back, title, subtitle, state, link, config, kind, device }) {
  return (
    <PageHeader back={back} title={title} subtitle={subtitle}
                actions={<>{state}{kind && <a className="op-link" href={gatewayUrl()} target="_blank" rel="noreferrer">Open Ignition gateway ↗</a>}</>} />
  );
}

// ---------------------------------------------------------------- Modbus TCP

function ModbusForm({ device, config, machines, back, setGuard, saved }) {
  const orig = { conn: config.sources.modbus[device], points: config.points.filter((p) => p.kind === "modbus" && p.device === device) };
  const [d, setD] = useState(() => clone(orig));
  const [reads, setReads] = useState({});
  const setConn = (k, v) => setD((x) => ({ ...x, conn: { ...x.conn, [k]: v } }));
  const setPoint = (path, k, v) => setD((x) => ({ ...x, points: x.points.map((p) => (p.path === path ? { ...p, [k]: v } : p)) }));
  const c = d.conn;
  const invalid =
    (!String(c.host ?? "").trim() && "Host is required") ||
    (!(c.port >= 1 && c.port <= 65535) && "Port must be 1–65535") ||
    (!(c.unitId >= 0 && c.unitId <= 255) && "Unit ID must be 0–255") ||
    d.points.map((p) => (!validModbus(p.address) ? `${p.tag}: register must look like HR1, IR1, C1 or DI1` : !(Number(p.scale) > 0) ? `${p.tag}: scale must be above 0` : null)).find(Boolean) || null;
  const dirty = !same(d, orig);
  const [st, stText] = connState(config, machines, "modbus", device);
  const save = async () => {
    const r = await api("sources/modbus/", {
      method: "PUT",
      body: { device, connection: d.conn, points: d.points.map((p) => ({ path: p.path, address: p.address.trim().toUpperCase(), scale: Number(p.scale), unit: p.unit })) },
    });
    await saved();
    return r;
  };
  const test = async () => {
    const r = await api("test/modbus/", { method: "POST", body: { connection: d.conn, points: d.points.map((p) => ({ path: p.path, address: p.address })) } });
    setReads(Object.fromEntries((r.reads ?? []).map((x) => [x.path, x])));
    return r;
  };
  return (
    <>
      <FormHeader back={back} title={device} subtitle={`Modbus TCP connection · used by ${usedBy(config, "modbus", device).join(", ")}`}
                  state={<Status state={st}>{stText}</Status>} kind="modbus" />
      <Card title="Connection">
        <div className="op-form">
          <Text label="Host or IP address" obj={c} k="host" set={setConn} mono invalid={!String(c.host ?? "").trim()} />
          <Text label="Port" obj={c} k="port" set={setConn} type="number" mono />
          <Text label="Unit ID" obj={c} k="unitId" set={setConn} type="number" mono />
        </div>
        <Advanced>
          <Text label="Timeout (ms)" obj={c} k="timeoutMs" set={setConn} type="number" mono />
          <Choice label="Addressing" obj={{ v: String(!!c.zeroBased) }} k="v" set={(_, v) => setConn("zeroBased", v === "true")}
                  options={[["false", "One-based (HR1 = register 0)"], ["true", "Zero-based (HR0 = register 0)"]]} />
        </Advanced>
        <p className="op-note">Ignition keeps its own copy of this connection. Register changes are applied to the Ignition tags when you save.</p>
        <Actions dirty={dirty} invalid={invalid} onTest={test} onSave={save} onRevert={() => { setD(clone(orig)); setReads({}); }} setGuard={setGuard} />
      </Card>
      <Card title="Registers" subtitle="Which register each reading comes from." flush>
        <div className="op-scroll">
          <table className="op-table form">
            <thead><tr><th>Reading</th><th>Register</th><th>Scale</th><th>Unit</th><th>Live value</th>{Object.keys(reads).length > 0 && <th>Test read</th>}</tr></thead>
            <tbody>
              {d.points.map((p) => {
                const area = modbusArea(p.address);
                const bit = area === "C" || area === "DI";
                const rd = reads[p.path];
                return (
                  <tr key={p.path}>
                    <td>{specOf(p.tag).label}<div className="sub">{p.machine}</div></td>
                    <td><input className={`mono sm${validModbus(p.address) ? "" : " invalid"}`} value={p.address ?? ""} aria-label={`${p.tag} register`}
                               spellCheck={false} onChange={(e) => setPoint(p.path, "address", e.target.value)} /></td>
                    <td><input type="number" step="any" className={`mono sm${Number(p.scale) > 0 ? "" : " invalid"}`} value={p.scale ?? ""} disabled={bit}
                               aria-label={`${p.tag} scale`} onChange={(e) => setPoint(p.path, "scale", e.target.value === "" ? "" : Number(e.target.value))} /></td>
                    <td><input className="sm" value={p.unit ?? ""} aria-label={`${p.tag} unit`} onChange={(e) => setPoint(p.path, "unit", e.target.value)} /></td>
                    <td>{liveCell(machines, p)}</td>
                    {Object.keys(reads).length > 0 && (
                      <td className="mono">{!rd ? "–" : rd.error ? <span className="bad">{rd.error}</span>
                        : bit ? (rd.raw ? "On" : "Off") : `${Number((rd.raw * Number(p.scale || 1)).toFixed(4)).toLocaleString()} ${unit(p.unit)}`}</td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}

// ---------------------------------------------------------------- OPC UA

const POLICIES = ["None", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"];

function OpcUaForm({ device, config, machines, back, setGuard, saved }) {
  const orig = { conn: config.sources.opcua[device], points: config.points.filter((p) => p.kind === "opcua" && p.device === device) };
  const [d, setD] = useState(() => clone(orig));
  const [reads, setReads] = useState({});
  const setConn = (k, v) => setD((x) => {
    const next = { ...x.conn, [k]: v };
    if (k === "policy") next.mode = v === "None" ? "None" : next.mode === "None" ? "SignAndEncrypt" : next.mode;
    if (k === "mode") next.policy = v === "None" ? "None" : next.policy === "None" ? "Basic256Sha256" : next.policy;
    return { ...x, conn: next };
  });
  const setNode = (path, v) => setD((x) => ({ ...x, points: x.points.map((p) => (p.path === path ? { ...p, node: v } : p)) }));
  const c = d.conn;
  const invalid =
    (!String(c.endpoint ?? "").startsWith("opc.tcp://") && "Endpoint must start with opc.tcp://") ||
    d.points.map((p) => (!validNode(p.node) ? `${p.tag}: NodeId must look like ns=2;s=Name` : null)).find(Boolean) || null;
  const dirty = !same(d, orig);
  const [st, stText] = connState(config, machines, "opcua", device);
  const save = async () => {
    const r = await api("sources/opcua/", { method: "PUT", body: { device, connection: d.conn, points: d.points.map((p) => ({ path: p.path, node: p.node.trim() })) } });
    await saved();
    return r;
  };
  const test = async () => {
    const r = await api("test/opcua/", { method: "POST", body: { device, connection: d.conn, points: d.points.map((p) => ({ path: p.path, node: p.node })) } });
    setReads(Object.fromEntries((r.reads ?? []).map((x) => [x.path, x])));
    return r.offered && r.ok ? { ...r, message: `${r.message} Server offers ${r.offered}.` } : r;
  };
  return (
    <>
      <FormHeader back={back} title={device} subtitle={`OPC UA connection · used by ${usedBy(config, "opcua", device).join(", ")}`}
                  state={<Status state={st}>{stText}</Status>} kind="opcua" />
      <Card title="Connection">
        <div className="op-form">
          <Text label="Endpoint URL" obj={c} k="endpoint" set={setConn} mono wide invalid={!String(c.endpoint ?? "").startsWith("opc.tcp://")} />
          <Choice label="Security policy" obj={c} k="policy" set={setConn} options={POLICIES} />
          <Choice label="Security mode" obj={c} k="mode" set={setConn} options={["None", "Sign", "SignAndEncrypt"]} />
        </div>
        <Advanced>
          <Choice label="Authentication" obj={c} k="auth" set={setConn} options={["Anonymous", "Username"]} />
          <Text label="Timeout (ms)" obj={c} k="timeoutMs" set={setConn} type="number" mono />
          {c.auth === "Username" && (
            <>
              <Text label="Username" obj={c} k="username" set={setConn} mono />
              <Text label="Password" obj={c} k="password" set={setConn} type="password" mono placeholder={c.passwordSet ? "•••••••• (unchanged)" : ""}
                    hint="Stored for connection tests only." />
            </>
          )}
        </Advanced>
        <p className="op-note">Ignition connects through its OPC connection {device}. NodeId changes are applied to the Ignition tags when you save.</p>
        <Actions dirty={dirty} invalid={invalid} onTest={test} onSave={save} onRevert={() => { setD(clone(orig)); setReads({}); }} setGuard={setGuard} />
      </Card>
      <Card title="Nodes" subtitle="Which node each reading subscribes to." flush>
        <div className="op-scroll">
          <table className="op-table form">
            <thead><tr><th>Reading</th><th>NodeId</th><th>Live value</th>{Object.keys(reads).length > 0 && <th>Test read</th>}</tr></thead>
            <tbody>
              {d.points.map((p) => {
                const rd = reads[p.path];
                return (
                  <tr key={p.path}>
                    <td>{specOf(p.tag).label}<div className="sub">{p.machine}</div></td>
                    <td><input className={`mono${validNode(p.node) ? "" : " invalid"}`} style={{ minWidth: 240 }} value={p.node ?? ""} aria-label={`${p.tag} NodeId`}
                               spellCheck={false} onChange={(e) => setNode(p.path, e.target.value)} /></td>
                    <td>{liveCell(machines, p)}</td>
                    {Object.keys(reads).length > 0 && <td className="mono">{!rd ? "–" : rd.error ? <span className="bad">{rd.error}</span> : String(rd.value)}</td>}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}

// ---------------------------------------------------------------- Boomi Event Streams

const ENV_REF = /^\$\{BOOMI_ES_[A-Z0-9_]+\}$/;
const parseUrl = (url) => {
  const m = /^https:\/\/([^/]+)\/rest\/([^/]+)\/([^/]+)\/([^/]+)\/([^/?#]+)/.exec((url ?? "").trim());
  return m ? { host: m[1], account: m[3], environment: m[4], topic: decodeURIComponent(m[5]) } : null;
};

function DestState({ plugin, name }) {
  const d = destinations(plugin).find((x) => x.name === name);
  return d ? <Status state={d.state}>{d.text}</Status> : <Status state="neutral">Not running</Status>;
}

function EventStreamsForm({ config, plugin, back, setGuard, saved }) {
  const orig = config.eventstreams;
  const [es, setEs] = useState(() => clone(orig));
  const set = (k, v) => setEs((x) => ({ ...x, [k]: v }));
  const parts = parseUrl(es.url);
  const tokenChanged = es.token !== orig.token;
  const invalid =
    (!String(es.url ?? "").startsWith("https://") && "Topic URL must start with https://") ||
    (!ENV_REF.test(es.token ?? "") && "Token must be a reference like ${BOOMI_ES_PRODUCE_TOKEN}") || null;
  const body = () => ({ ...es, token: tokenChanged ? es.token : null });
  const d = destinations(plugin).find((x) => x.name === orig.name);
  return (
    <>
      <FormHeader back={back} title="Boomi Event Streams" subtitle="Primary destination. If it fails, the gateway switches to BIMBA Ingest automatically."
                  state={<DestState plugin={plugin} name={orig.name} />} />
      {orig.literalToken && <p className="op-note bad">config.json holds a literal token. Put it in .env as BOOMI_ES_PRODUCE_TOKEN and save the reference instead.</p>}
      <Card title="Topic">
        <div className="op-form">
          <Switch label="Deliver to Event Streams" obj={es} k="enabled" set={set} />
          <Text label="Topic REST URL" obj={es} k="url" set={set} mono wide invalid={!String(es.url ?? "").startsWith("https://")}
                hint={parts ? `Region ${parts.host} · topic ${parts.topic}` : "From Event Streams: topic → View REST."} />
          <Text label="Environment token" obj={es} k="token" set={set} mono wide placeholder="${BOOMI_ES_PRODUCE_TOKEN}" invalid={!ENV_REF.test(es.token ?? "")}
                hint={orig.tokenEnvSet || tokenChanged ? "Read from .env when the gateway reloads." : "Not set in .env yet."} />
        </div>
        <Advanced>
          <Choice label="Messages" obj={es} k="esMode" set={set} options={[["batch", "One message per batch"], ["message", "One message per reading"]]} />
          <Choice label="Produce API" obj={es} k="esApi" set={set} options={[["auto", "Detect from URL"], ["single", "Single message"], ["multi", "Multi message"]]} />
          <Text label="Timeout (ms)" obj={es} k="timeoutMs" set={set} type="number" mono />
          <Text label="Retry after (s)" obj={es} k="retryAfterSeconds" set={set} type="number" mono hint="How long the failover is used before Event Streams is tried again." />
        </Advanced>
        <Actions dirty={!same(es, orig)} invalid={invalid} setGuard={setGuard} onRevert={() => setEs(clone(orig))}
                 onTest={() => api("test/eventstreams/", { method: "POST", body: body() })}
                 onSave={async () => { const r = await api("eventstreams/", { method: "PUT", body: body() }); await saved(); return r; }} />
      </Card>
      {d && (
        <Card title="Delivery">
          <dl className="op-dl">
            <div><dt>Delivered</dt><dd>{(d.messages ?? 0).toLocaleString()} readings in {d.batches.toLocaleString()} batches</dd></div>
            <div><dt>Last success</dt><dd>{d.lastSuccessAt ? ago(d.lastSuccessAt) : "never"}</dd></div>
            <div><dt>Failures</dt><dd>{d.failures ?? 0}{d.lastFailureAt ? ` · last ${ago(d.lastFailureAt)}` : ""}</dd></div>
            {d.lastError && <div><dt>Last error</dt><dd className="mono bad">{d.lastError}</dd></div>}
          </dl>
        </Card>
      )}
    </>
  );
}

// ---------------------------------------------------------------- Kafka

function KafkaForm({ config, plugin, back, setGuard, saved }) {
  const orig = config.kafka;
  const [k, setK] = useState(() => clone(orig));
  const set = (key, v) => setK((x) => ({ ...x, [key]: v }));
  const sasl = k.securityProtocol?.startsWith("SASL");
  const topic = (k.topic ?? "").replaceAll("{plantId}", config.plantId ?? "");
  const invalid = (!String(k.bootstrapServers ?? "").trim() && "Bootstrap servers are required") || (!String(k.topic ?? "").trim() && "Topic is required") || null;
  return (
    <>
      <FormHeader back={back} title="Kafka" subtitle="A copy of every batch, on its own route: Kafka being down never holds up the primary path."
                  state={<DestState plugin={plugin} name={orig.name} />} />
      <Card title="Producer">
        <div className="op-form">
          <Switch label="Copy every batch to Kafka" obj={k} k="enabled" set={set} />
          <Text label="Bootstrap servers" obj={k} k="bootstrapServers" set={set} mono wide invalid={!String(k.bootstrapServers ?? "").trim()}
                hint="host:port as the gateway reaches it (kafka:9092 inside Docker)." />
          <Text label="Topic" obj={k} k="topic" set={set} mono wide invalid={!String(k.topic ?? "").trim()} hint={k.topic?.includes("{plantId}") ? `Resolves to ${topic}` : undefined} />
        </div>
        <Advanced>
          <Choice label="Security protocol" obj={k} k="securityProtocol" set={set} options={["PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL"]} />
          <Choice label="SASL mechanism" obj={k} k="saslMechanism" set={set} disabled={!sasl} options={["PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512"]} />
          {sasl && (
            <>
              <Text label="Username" obj={k} k="username" set={set} mono />
              <Text label="Password" obj={k} k="password" set={set} type="password" mono placeholder={k.passwordSet ? "•••••••• (unchanged)" : ""} />
            </>
          )}
          <Choice label="Record layout" obj={k} k="kafkaMode" set={set} options={[["batch", "One record per batch"], ["message", "One record per reading"]]} />
          <Text label="Timeout (ms)" obj={k} k="timeoutMs" set={set} type="number" mono />
          <Text label="Retry after (s)" obj={k} k="retryAfterSeconds" set={set} type="number" mono />
        </Advanced>
        <Actions dirty={!same(k, orig)} invalid={invalid} setGuard={setGuard} onRevert={() => setK(clone(orig))}
                 onTest={() => api("test/kafka/", { method: "POST", body: k })}
                 onSave={async () => { const r = await api("kafka/", { method: "PUT", body: k }); await saved(); return r; }} />
      </Card>
      <details className="op-card op-disclose">
        <summary>Recent records on {topic}</summary>
        <TopicTail />
      </details>
    </>
  );
}

function TopicTail() {
  const [d, setD] = useState(null);
  useEffect(() => {
    let alive = true;
    let timer;
    const load = async () => {
      try {
        const j = await (await fetch("/api/console/kafka-tail/?depth=10")).json();
        if (alive) setD(j);
      } catch (e) {
        if (alive) setD({ available: false, error: e.message });
      }
      if (alive) timer = setTimeout(load, 4000);
    };
    load();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, []);
  if (!d) return <Empty>Reading the topic…</Empty>;
  if (d.available === false) return <Empty>{d.error}</Empty>;
  return (
    <table className="op-table">
      <thead><tr><th>Time</th><th>Partition · offset</th><th className="r">Readings</th><th>Machines</th></tr></thead>
      <tbody>
        {(d.records ?? []).slice(0, 8).map((r) => (
          <tr key={`${r.partition}-${r.offset}`}>
            <td className="mono">{hms(r.timestamp)}</td>
            <td className="mono">{r.partition} · {r.offset}</td>
            <td className="r num">{r.count}</td>
            <td className="sub">{[...new Set(r.messages.map((x) => x.machineId))].join(", ")}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
