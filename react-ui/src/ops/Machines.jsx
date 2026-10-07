import { useMemo, useState } from "react";
import { band, fmtNum, good, isBool, isNum, machineAlarms, onOf, segments, since, specOf, unit, useMachineHistory, useNow, useWidth } from "./data.js";
import { kpis } from "./kpis.js";
import { KIND, headline, health, readingText, runState, targetName, ago } from "./model.js";
import Messages from "./Messages.jsx";
import { to } from "./paths.js";
import { Card, Empty, LineChart, PageHeader, Seg, Stat, Status, Tabs } from "./ui.jsx";

// Machines: a list, and one machine's page split into four tabs so no screen shows everything at once.

const STATE_TAGS = new Set(["running", "tripped", "breaker_closed", "state"]);
const isState = (t) => STATE_TAGS.has(t.tag) || isBool(t) || t.text != null;
const PROTO = { modbus: "Modbus TCP", opcua: "OPC UA", expression: "Gateway expression", other: "Ignition tag" };

export function MachineList({ machines, link }) {
  return (
    <>
      <PageHeader title="Machines" subtitle={`${machines.length} machines reporting through the edge gateway.`} />
      <Card flush>
        <table className="op-table click">
          <thead><tr><th>Machine</th><th>Type</th><th>Status</th><th>Main reading</th><th>State</th><th className="r">Last update</th></tr></thead>
          <tbody>
            {machines.map((m) => {
              const h = headline(m);
              const hs = health(m);
              const rs = runState(m);
              const href = to("machines", m.machineId);
              return (
                <tr key={m.machineId} onClick={link(href)}>
                  <td><a href={href} onClick={link(href)} className="strong">{m.machineId}</a><div className="sub">{m.plantId}</div></td>
                  <td>{KIND[m.kind]}</td>
                  <td><Status state={hs.state}>{hs.text}</Status></td>
                  <td>{h && m.health !== "critical" ? <><b className="num">{h.value}</b> <span className="sub">{h.unit} · {h.label}</span></> : <span className="sub">–</span>}</td>
                  <td>{rs && m.health !== "critical" ? <Status state={rs.state}>{rs.text}</Status> : <span className="sub">–</span>}</td>
                  <td className="r sub">{ago(m.lastSeen)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
    </>
  );
}

const TABS = [
  { key: "overview", label: "Overview" },
  { key: "trends", label: "Trends" },
  { key: "messages", label: "Messages" },
  { key: "details", label: "Details" },
];

export function MachineDetail({ id, tab, machines, live, cfg, plugin, link, go }) {
  const m = machines.find((x) => x.machineId === id);
  const [tag, setTag] = useState(null);
  const back = <a className="op-back" href={to("machines")} onClick={link(to("machines"))}>← Machines</a>;

  if (!m) {
    return (
      <>
        <PageHeader back={back} title={id} />
        <Card><Empty>{live.connected ? `No machine called “${id}” is reporting.` : "Connecting to the live feed…"}</Empty></Card>
      </>
    );
  }
  const points = (cfg?.points ?? []).filter((p) => p.machine === id);
  const src = sourceOf(cfg, points);
  const hs = health(m);
  const alarms = machineAlarms(m);
  const openTrend = (t) => {
    setTag(t);
    go(to("machines", id, "trends"));
  };

  return (
    <>
      <PageHeader back={back} title={m.machineId} subtitle={`${KIND[m.kind]} · ${src.line}`}
                  actions={<Status state={hs.state}>{hs.text}</Status>} />
      <Tabs items={TABS.map((t) => (t.key === "overview" && alarms.length ? { ...t, count: alarms.length } : t))}
            value={TABS.some((t) => t.key === tab) ? tab : "overview"} hrefFor={(k) => to("machines", id, k === "overview" ? "" : k)} link={link} />
      {tab === "trends" ? <Trends m={m} live={live} tag={tag} setTag={setTag} />
        : tab === "messages" ? <Messages m={m} live={live} cfg={cfg} points={points} />
        : tab === "details" ? <Details m={m} cfg={cfg} points={points} src={src} plugin={plugin} link={link} />
        : <OverviewTab m={m} live={live} alarms={alarms} openTrend={openTrend} />}
    </>
  );
}

function OverviewTab({ m, live, alarms, openTrend }) {
  const now = useNow(5000);
  const { series } = useMachineHistory(m, 15, live.onValues);
  const tags = Object.values(m.tags).sort((a, b) => isState(b) - isState(a) || a.tag.localeCompare(b.tag));
  return (
    <>
      {alarms.length > 0 && (
        <div className="op-alert">
          {alarms.map((a, i) => <Status key={i} state={a.s}>{a.t.replace(`${m.machineId} `, "")} · {a.d}</Status>)}
        </div>
      )}
      <div className="op-stats">
        {kpis(m, series, now - 15 * 60_000, now).map((k) => <Stat key={k.label} label={k.label} value={k.value} unit={k.unit} note={k.note} />)}
      </div>
      <Card title="Readings" subtitle="Latest value of every reading. Select one to see its trend." flush>
        <table className="op-table click">
          <thead><tr><th>Reading</th><th className="r">Value</th><th>Status</th><th className="r">Updated</th></tr></thead>
          <tbody>
            {tags.map((t) => {
              const numeric = isNum(t) && good(t) && !isState(t);
              const st = !good(t) ? "critical" : numeric ? band(t.tag, t.num) : "good";
              const stText = !good(t) ? "No signal" : st === "critical" ? "High" : st === "warning" ? "Low" : "OK";
              return (
                <tr key={t.tag} onClick={numeric ? () => openTrend(t.tag) : undefined} className={numeric ? "" : "static"}>
                  <td>{specOf(t.tag).label}</td>
                  <td className="r num">{readingText(t)}</td>
                  <td><Status state={st === "neutral" ? "good" : st}>{stText}</Status></td>
                  <td className="r sub">{ago(t.time)}{numeric && <span className="go" aria-hidden="true"> ›</span>}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
    </>
  );
}

const WINDOWS = [[15, "15 min"], [60, "1 hour"], [360, "6 hours"]];

function Trends({ m, live, tag, setTag }) {
  const [win, setWin] = useState(15);
  const now = useNow(2000);
  const { series, loading } = useMachineHistory(m, win, live.onValues);
  const numeric = Object.values(m.tags).filter((t) => !isState(t)).sort((a, b) => a.tag.localeCompare(b.tag));
  const states = Object.values(m.tags).filter(isState);
  const sel = numeric.find((t) => t.tag === tag) ?? numeric[0];
  const t0 = now - win * 60_000;
  const pts = sel ? (series.get(sel.tagId) ?? []).filter((p) => p.v != null && p.t >= t0) : [];
  const vals = pts.map((p) => p.v);
  const avg = vals.length ? vals.reduce((a, v) => a + v, 0) / vals.length : null;

  return (
    <>
      <Card>
        <div className="op-trend-bar">
          <div className="op-chips" role="group" aria-label="Reading">
            {numeric.map((t) => (
              <button key={t.tag} className={t === sel ? "on" : ""} aria-pressed={t === sel} onClick={() => setTag(t.tag)}>{specOf(t.tag).label}</button>
            ))}
          </div>
          <Seg items={WINDOWS} value={win} onChange={setWin} label="Time window" />
        </div>
        {sel ? (
          <>
            <div className="op-trend-h">
              <div><span className="l">{specOf(sel.tag).label}</span><span className="v">{good(sel) ? fmtNum(sel.num) : "–"}<small>{unit(sel.unit)}</small></span></div>
              <dl>
                <div><dt>Min</dt><dd>{vals.length ? fmtNum(Math.min(...vals)) : "–"}</dd></div>
                <div><dt>Average</dt><dd>{avg != null ? fmtNum(avg) : "–"}</dd></div>
                <div><dt>Max</dt><dd>{vals.length ? fmtNum(Math.max(...vals)) : "–"}</dd></div>
              </dl>
            </div>
            {loading && !pts.length ? <div className="op-chart empty" style={{ height: 280 }}>Loading history…</div>
              : <LineChart points={series.get(sel.tagId)} tag={sel.tag} u={sel.unit} t0={t0} t1={now} height={280}
                           current={good(sel) && sel.num != null ? sel.num : null} />}
          </>
        ) : <Empty>This machine has no numeric readings.</Empty>}
      </Card>
      {states.length > 0 && (
        <Card title="States" subtitle="On/off and status readings over the same window.">
          <StateLanes states={states} series={series} t0={t0} t1={now} />
        </Card>
      )}
    </>
  );
}

function StateLanes({ states, series, t0, t1 }) {
  const [box, W] = useWidth(700);
  const L = 140;
  const x = (t) => L + ((t - t0) / (t1 - t0)) * (W - L);
  return (
    <div ref={box} className="op-lanes">
      {states.map((t) => {
        const text = !isBool(t) && t.text != null;
        const valueOf = text ? (p) => p.s ?? null : onOf;
        const segs = segments(series.get(t.tagId), t0, t1, { time: t.time, v: t.num, s: t.text }, valueOf);
        const on = (v) => (text ? v === "RUNNING" : v === true);
        const alarm = t.tag === "tripped";
        return (
          <div key={t.tag} className="lane">
            <span className="nm">{specOf(t.tag).label}</span>
            <svg width={W - L} height="26" aria-label={`${specOf(t.tag).label} over time`}>
              {segs.map((s, i) => {
                const w = Math.max(1, x(s.b) - x(s.a) - 1);
                const cls = s.v == null ? "unk" : on(s.v) ? (alarm ? "alarm" : "on") : "off";
                const label = s.v == null ? "" : text ? String(s.v).toLowerCase() : on(s.v) ? (alarm ? "tripped" : "on") : "off";
                return (
                  <g key={i}>
                    <rect x={x(s.a) - L} y="2" width={w} height="22" rx="4" className={`seg ${cls}`}>
                      <title>{label || "no change recorded"}</title>
                    </rect>
                    {w > label.length * 7 + 12 && label && <text x={x(s.a) - L + 7} y="17" className={`seg-t ${cls}`}>{label}</text>}
                  </g>
                );
              })}
            </svg>
          </div>
        );
      })}
      <p className="op-hint">Grey = no change recorded in this window yet (values are sent when they change).</p>
    </div>
  );
}

function Details({ m, cfg, points, src, plugin, link }) {
  const dests = (plugin?.delivery?.routes ?? []).map((r) => r.destinations?.find((d) => d.name === r.activeDestination)).filter(Boolean);
  const vias = [...new Set(Object.values(m.tags).map((t) => t.via).filter(Boolean))];
  const settingsHref = src.kind === "modbus" || src.kind === "opcua" ? to("settings", src.kind, src.device) : null;
  return (
    <div className="op-two">
      <div className="op-col">
        <Card title="Connection" action={settingsHref && <a className="op-link" href={settingsHref} onClick={link(settingsHref)}>Edit in Settings →</a>}>
          <dl className="op-dl">
            <div><dt>Protocol</dt><dd>{PROTO[src.kind] ?? PROTO.other}</dd></div>
            <div><dt>Device</dt><dd>{src.device ?? "–"}</dd></div>
            <div><dt>Address</dt><dd className="mono">{src.address ?? "–"}</dd></div>
            <div><dt>Ignition folder</dt><dd className="mono">[default]BIMBA/{m.machineId}</dd></div>
          </dl>
        </Card>
        <Card title="Data path">
          <ol className="op-path">
            <li><b>{PROTO[src.kind] ?? "Device"}</b><span>{src.device ? `${src.device} · ${src.address}` : "computed in the gateway"}</span></li>
            <li><b>Edge gateway</b><span>{plugin?.edgeId ?? "Ignition"} · BIMBA IT/OT Agent {plugin?.version ?? ""}</span></li>
            {dests.map((d) => <li key={d.name}><b>{targetName(d)}</b><span>{d.role === "mirror" ? "copy of every batch" : d.role}</span></li>)}
            <li><b>Database</b><span>PostgreSQL · stored via {vias.map((v) => (v === "boomi-es" ? "Boomi Event Streams" : v)).join(", ") || "–"}</span></li>
          </ol>
        </Card>
      </div>
      <Card title="Tag map" subtitle="Where each reading comes from." flush>
        <table className="op-table">
          <thead><tr><th>Reading</th><th>Source</th><th className="r">Scale</th><th className="r">Tag ID</th></tr></thead>
          <tbody>
            {Object.values(m.tags).sort((a, b) => a.tag.localeCompare(b.tag)).map((t) => {
              const p = points.find((x) => x.tag === t.tag);
              return (
                <tr key={t.tag}>
                  <td>{specOf(t.tag).label}<div className="sub mono">{t.tag}</div></td>
                  <td className="mono">{addressOf(p)}</td>
                  <td className="r mono">{p?.scale && p.scale !== 1 ? `× ${p.scale}` : "–"}</td>
                  <td className="r mono sub">{t.tagId}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

export function addressOf(p) {
  if (!p) return "–";
  if (p.kind === "modbus") return `${p.device} · ${p.address}`;
  if (p.kind === "opcua") return p.node ?? "–";
  if (p.kind === "expression") return "expression";
  return "–";
}

function sourceOf(cfg, points) {
  const kinds = [...new Set(points.map((p) => p.kind))];
  const kind = kinds.length === 1 ? kinds[0] : points.length ? "other" : null;
  const device = points.find((p) => p.device)?.device ?? null;
  const conn = device && cfg?.sources?.[kind]?.[device];
  const address = kind === "modbus" && conn ? `${conn.host}:${conn.port} · unit ${conn.unitId}` : kind === "opcua" && conn ? conn.endpoint : null;
  const line = kind === "expression" ? "Computed in the gateway" : device ? `${PROTO[kind]} via ${device}` : cfg ? PROTO.other : "…";
  return { kind, device, address, line };
}
