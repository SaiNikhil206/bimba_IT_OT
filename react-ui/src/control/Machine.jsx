import { useEffect, useMemo, useRef, useState } from "react";
import { Art } from "./Assets.jsx";
import { machineAlarms } from "./alarms.js";
import {
  KIND_LABEL, band, fmtNum, good, isBool, isNum, onOf, segments, since, specOf, unit,
  useMachineHistory, useNow,
} from "./lib.js";
import { Timeline, Trend } from "./mviz.jsx";
import { AlarmList, VIA, machinePath } from "./shared.jsx";
import { Badge, Dial } from "./viz.jsx";
import Inspector from "./inspect.jsx";

// /control-room/machine/<ID>: one machine's dashboard. Everything here is live: dials, KPIs,
// timeline and trends extend with each WebSocket value, and the trace lists every change as it lands.

const WINDOWS = [5, 15, 60];
const STATE_TAGS = new Set(["running", "tripped", "breaker_closed", "state"]);
const PROTO = { modbus: "Modbus TCP", opcua: "OPC UA", expression: "Ignition expression", other: "Ignition tag" };

export default function MachinePage({ id, live, plugin, machines, perMachine, link, cfg }) {
  const m = machines.find((x) => x.machineId === id);
  const [win, setWin] = useState(15);
  const [hoverT, setHoverT] = useState(null);
  const now = useNow(1000);
  const { series, loading } = useMachineHistory(m, win, live.onValues);
  const trace = useTrace(id, live.onValues, m);

  if (!m) {
    return (
      <section className="cr-panel mx-missing">
        <a className="mx-back" href="/control-room" onClick={link("/control-room")}>← Control Room</a>
        <h2>{live.connected ? `No machine called “${id}”` : "Connecting to the live feed…"}</h2>
        {live.connected && (
          <p className="muted">
            Known machines:{" "}
            {machines.map((x, i) => (
              <span key={x.machineId}>{i ? ", " : ""}<a href={machinePath(x.machineId)} onClick={link(machinePath(x.machineId))}>{x.machineId}</a></span>
            ))}
          </p>
        )}
      </section>
    );
  }

  const t1 = now;
  const t0 = now - win * 60_000;
  const tags = Object.values(m.tags).sort((a, b) => a.tag.localeCompare(b.tag));
  // on/off and text tags go to the state timeline, everything else is a measurement (dial + trend)
  const isState = (t) => STATE_TAGS.has(t.tag) || isBool(t) || t.text != null;
  const states = tags.filter(isState);
  const numeric = tags.filter((t) => !isState(t));
  const points = (cfg?.points ?? []).filter((p) => p.machine === id);
  const pointOf = (tag) => points.find((p) => p.tag === tag);
  const src = sourceOf(cfg, points);
  const alarms = machineAlarms(m);
  const lat = trace.latency;

  return (
    <div className="mx">
      <nav className="mx-switch" aria-label="Machines">
        <a className="mx-back" href="/control-room" onClick={link("/control-room")}>← Control Room</a>
        <div className="chips">
          {machines.map((x) => (
            <a key={x.machineId} href={machinePath(x.machineId)} onClick={link(machinePath(x.machineId))}
               className={`chip ${x.health}${x.machineId === id ? " on" : ""}`} aria-current={x.machineId === id ? "page" : undefined}>
              <i aria-hidden="true" />{x.machineId}
            </a>
          ))}
        </div>
      </nav>

      <section className={`cr-panel mx-head ${m.health}`}>
        <div className="id">
          <div className="k">{KIND_LABEL[m.kind]} · {m.plantId}</div>
          <h2>{m.machineId}</h2>
          <div className="src">{src}</div>
        </div>
        <div className="stats">
          <Stat label="Signal"><Badge state={m.health}>{m.health === "critical" ? "Signal lost" : m.health === "warning" ? "Degraded" : "Healthy"}</Badge></Stat>
          <Stat label="Last value">{since(m.lastSeen)}<small>{m.lastSeen && since(m.lastSeen) !== "now" ? " ago" : ""}</small></Stat>
          <Stat label="Values / min">{perMachine[id] ?? 0}</Stat>
          <Stat label="Device → screen" title="Time from the value's source timestamp in Ignition to its arrival on this page (plugin batching + Boomi Event Streams + database + live feed)">
            {lat ? `${fmtNum(lat.p50 / 1000, 1)} s` : "–"}<small>{lat ? ` p50 · max ${fmtNum(lat.max / 1000, 1)} s` : ""}</small>
          </Stat>
          <Stat label="Tags">{tags.length}<small> · {tags.filter(good).length} good</small></Stat>
        </div>
      </section>

      <FlowGraph m={m} plugin={plugin} cfg={cfg} points={points} rate={perMachine[id] ?? 0} pulse={trace.pulse} />

      <section className="mx-hero">
        <div className={`cr-panel mx-stage-p${m.health === "critical" ? " lost" : ""}`}>
          <div className="mx-stage"><Art m={m} kind={m.kind} /></div>
          <div className="mx-states">
            {states.map((t) => <StateChip key={t.tag} t={t} />)}
          </div>
          {m.health === "critical" && (
            <div className="static"><b>NO SIGNAL</b><span>BAD quality from Ignition · last value {since(m.lastSeen)} ago</span></div>
          )}
        </div>
        <div className="cr-panel mx-dials">
          {numeric.map((t) => (
            <div key={t.tag} className={`mx-dial${good(t) ? "" : " bad"}`}>
              {good(t) && t.num != null && specOf(t.tag).counter
                ? <div className="nodial counter"><b>{fmtNum(t.num, 1)}</b><span>{unit(t.unit)} · running total</span></div>
                : good(t) && t.num != null
                ? <Dial tag={t.tag} value={t.num} u={t.unit} size={150} state={band(t.tag, t.num)} />
                : <div className="nodial"><b>{t.quality ?? "?"}</b><span>no value</span></div>}
              <div className="lab">{specOf(t.tag).label}</div>
            </div>
          ))}
        </div>
      </section>

      <section className="mx-kpis">
        {kpis(m, series, t0, t1).map((k) => (
          <div key={k.label} className="cr-panel tile">
            <div className="lab">{k.label}</div>
            <div className="big">{k.value}<small>{k.unit}</small></div>
            <div className="muted">{k.note}</div>
          </div>
        ))}
      </section>

      <section className="cr-panel">
        <div className="cr-ph">
          <div><h2>Live trends</h2> <span className="muted">{loading ? "loading history…" : "hover any chart: all charts follow the same time"}</span></div>
          <div className="cr-seg" role="group" aria-label="Time window">
            {WINDOWS.map((w) => <button key={w} className={w === win ? "on" : ""} onClick={() => setWin(w)}>{w}m</button>)}
          </div>
        </div>
        {states.length > 0 && (
          <Timeline t0={t0} t1={t1} hoverT={hoverT} setHoverT={setHoverT}
                    lanes={states.map((t) => ({
                      tag: t.tag, text: !isBool(t) && t.text != null && t.text !== "true" && t.text !== "false",
                      alarm: t.tag === "tripped", points: series.get(t.tagId),
                      current: { time: t.time, v: t.num, s: t.text },
                    }))} />
        )}
        <div className="mx-trends">
          {numeric.map((t) => (
            <Trend key={t.tag} tag={t.tag} u={t.unit} points={series.get(t.tagId)} current={t}
                   t0={t0} t1={t1} hoverT={hoverT} setHoverT={setHoverT} />
          ))}
        </div>
      </section>

      <Inspector id={id} cfg={cfg} />

      <section className="mx-lower">
        <div className="cr-panel mx-trace">
          <div className="cr-ph">
            <div><h2>Live value trace</h2> <span className="muted">every change stored for {id}, newest first</span></div>
            <button className={`cr-pause${trace.paused ? " on" : ""}`} onClick={trace.toggle}>{trace.paused ? "▶ Resume" : "❚❚ Pause"}</button>
          </div>
          <div className="tbl">
            <table>
              <thead>
                <tr><th>Source time</th><th>Tag</th><th>Source</th><th className="r">Value</th><th className="r">Change</th><th>Quality</th><th className="r">Latency</th></tr>
              </thead>
              <tbody>
                {trace.rows.length === 0 && (
                  <tr><td colSpan={7} className="muted">Waiting for the next value… values are sent on change, so a machine whose values are steady sends nothing.</td></tr>
                )}
                {trace.rows.map((r) => {
                  const p = pointOf(r.tag);
                  return (
                    <tr key={r.key} className={Date.now() - r.at < 1500 ? "fresh" : ""}>
                      <td className="mono">{clock24(r.t)}<span className="ms">.{String(new Date(r.t).getMilliseconds()).padStart(3, "0")}</span></td>
                      <td>{specOf(r.tag).label}</td>
                      <td className="mono muted">{addressOf(p)}</td>
                      <td className="mono r">{valueText(r)}<small> {unit(r.unit)}</small></td>
                      <td className={`mono r delta ${r.delta > 0 ? "up" : r.delta < 0 ? "down" : ""}`}>
                        {r.delta == null ? (r.changed ? `${r.prevText ?? "–"} → ${valueText(r)}` : "–") : `${r.delta > 0 ? "▲" : r.delta < 0 ? "▼" : "•"} ${fmtNum(Math.abs(r.delta))}`}
                      </td>
                      <td><span className={`q ${r.quality === "GOOD" ? "good" : "critical"}`}>{r.quality}</span></td>
                      <td className="mono r">{r.lat != null ? `${fmtNum(r.lat / 1000, 1)} s` : "–"}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>

        <div className="mx-side">
          <div className="cr-panel">
            <div className="cr-ph">
              <h2>Alarms</h2>
              <Badge state={alarms.some((a) => a.s === "critical") ? "critical" : alarms.length ? "warning" : "good"}>
                {alarms.length ? `${alarms.length} active` : "All clear"}
              </Badge>
            </div>
            <AlarmList alarms={alarms} empty={`${id}: every tag reads GOOD and inside its limits.`} />
          </div>
          <div className="cr-panel">
            <div className="cr-ph"><h2>Tag map</h2><span className="muted">Ignition → BIMBA</span></div>
            <div className="tbl">
              <table>
                <thead><tr><th>Tag</th><th>Source</th><th className="r">Scale</th><th>Stored via</th><th className="r">ID</th></tr></thead>
                <tbody>
                  {tags.map((t) => {
                    const p = pointOf(t.tag);
                    return (
                      <tr key={t.tag}>
                        <td><span className={`dot ${good(t) ? "good" : "critical"}`} aria-hidden="true" />{t.tag}</td>
                        <td className="mono muted">{addressOf(p)}</td>
                        <td className="mono r muted">{p?.scale && p.scale !== 1 ? `×${p.scale}` : "–"}</td>
                        <td className="muted">{VIA[t.via] ?? t.via ?? "–"}</td>
                        <td className="mono r muted">{t.tagId}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}

function Stat({ label, title, children }) {
  return (
    <div className="stat" title={title}>
      <span>{label}</span>
      <b>{children}</b>
    </div>
  );
}

function StateChip({ t }) {
  const ok = good(t);
  const bool = isBool(t);
  const onv = ok && bool && !!t.num;
  const alarm = t.tag === "tripped";
  const cls = !ok ? "bad" : bool ? (onv ? (alarm ? "alarm" : "on") : "off") : "text";
  return (
    <div className={`mx-chip ${cls}`}>
      <span className="l">{specOf(t.tag).label}</span>
      <b>{!ok ? t.quality : bool ? (onv ? (alarm ? "TRIPPED" : "ON") : "OFF") : t.text ?? "–"}</b>
      <span className="s">since {since(t.time)}</span>
    </div>
  );
}

const CANONICAL = ["plantId", "machineId", "timestamp", "tagName", "tagValue", "unit", "quality", "edge.seq"];
const TARGET = { eventstreams: "Boomi Event Streams", kafka: "Kafka", rest: "REST", mqtt: "MQTT" };

function targetName(d) {
  if (/\/api\/ingest\/?$/.test(d.endpoint ?? "")) return "BIMBA Ingest";
  return TARGET[d.transport] ?? d.transport;
}

function targetDetail(d) {
  if (d.transport === "kafka") return d.endpoint?.split(" -> ").pop();
  if (d.transport === "eventstreams") return `topic ${decodeURIComponent(d.endpoint?.split("/").pop() ?? "")}`;
  return d.endpoint?.replace(/^https?:\/\//, "");
}

/**
 * Network graph: OT device -> BIMBA IT/OT Agent (normaliser + disk spool) -> fan-out to every route's
 * destinations. The wires pulse on every value of this machine.
 */
function FlowGraph({ m, plugin, cfg, points, rate, pulse }) {
  const kinds = [...new Set(points.map((p) => p.kind))];
  const kind = kinds.length === 1 ? kinds[0] : points.length ? "other" : null;
  const dev = points.find((p) => p.device)?.device;
  const conn = dev && cfg?.sources?.[kind]?.[dev];
  const lost = m.health === "critical";
  const routes = plugin?.delivery?.routes ?? [];
  const dests = routes.flatMap((r) => (r.destinations ?? []).map((d) => ({ ...d, route: r, active: r.activeDestination === d.name })));
  const spool = routes.map((r) => `${r.name} ${r.bufferedBatches}`).join(" · ");
  const backlog = routes.reduce((a, r) => a + (r.bufferedBatches ?? 0) + (r.pendingBatches ?? 0), 0);
  const col = plugin?.collector;
  return (
    <section className="cr-panel mx-flow" aria-label="Data path">
      <div className="mx-flow-cap">Network graph · physical layer → gateway transformation → IT sinks</div>
      <div className="mx-flow-g">
        <div className={`fnode ot${lost ? " bad" : ""}`}>
          <span className="k">OT hardware node</span>
          <b className="nm">{dev ?? m.machineId}</b>
          <span className={`proto ${kind ?? "other"}`}>{PROTO[kind] ?? "Ignition tag"}</span>
          <span className="muted">{dev ? `Ignition device ${dev}` : kind === "expression" ? "computed inside Ignition" : "Ignition tags"}</span>
          {conn && (
            <code>{kind === "modbus" ? `${conn.host}:${conn.port} · unit ${conn.unitId} · ${points.length} registers` : `${conn.endpoint} · ${points.length} nodes`}</code>
          )}
          <span className={`live ${lost ? "bad" : rate ? "" : "idle"}`}>
            <i aria-hidden="true" />{lost ? "no signal (BAD quality)" : rate ? `ingestion steady · ${rate} values/min` : "quiet: values are sent on change"}
          </span>
        </div>

        <FlowWire label={`${rate}/min`} pulse={pulse} delay={0} />

        <div className="fnode core">
          <span className="k">Internal processing core</span>
          <b className="nm">{plugin?.module ?? "BIMBA IT/OT Agent"} <small>{plugin?.version ?? ""}</small></b>
          <span className="muted">
            Ignition tag events are mapped to the canonical BIMBA message (schema 1.0), batched and written to one
            on-disk spool per route before delivery.
          </span>
          <div className="fields">{CANONICAL.map((f) => <span key={f}>{f}</span>)}</div>
          <span className="muted mono">{plugin?.edgeId ?? "–"} · seq {(col?.lastSeq ?? 0).toLocaleString()} · {col?.subscribedTags ?? 0} tags subscribed</span>
          <span className={`spool${backlog ? " warn" : ""}`}>On-disk spool: {backlog ? `${backlog} batches backlog` : "empty"} <small>({spool || "–"})</small></span>
        </div>

        <FlowWire label={`fan-out ×${routes.length}`} pulse={pulse} delay={450} />

        <div className="fdests">
          {dests.length === 0 && <div className="fnode dest bad"><b className="nm">No destination</b><span className="muted">plugin status not reachable</span></div>}
          {dests.map((d) => {
            const state = d.state === "down" ? "bad" : d.active ? "ok" : "standby";
            return (
              <div key={d.name} className={`fnode dest ${state}`}>
                <div className="dh">
                  <b className="nm">{targetName(d)}</b>
                  <span className="badges"><span className="bdg">{d.role}</span><span className={`bdg t ${d.transport}`}>{d.transport === "eventstreams" ? "ES" : d.transport.toUpperCase()}</span></span>
                </div>
                <span className={`live ${state === "bad" ? "bad" : state === "standby" ? "idle" : ""}`}>
                  <i aria-hidden="true" />
                  {state === "bad" ? "down · retrying" : state === "ok" ? "delivering" : d.lastProbeAt ? `standby · checked ${since(d.lastProbeAt)} ago` : "standby"}
                  {" · "}{(d.messages ?? 0).toLocaleString()} msgs delivered
                </span>
                <code>{targetDetail(d)}</code>
                {d.lastError && state === "bad" && <span className="err">{d.lastError.slice(0, 90)}</span>}
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}

function FlowWire({ label, pulse, delay }) {
  return (
    <div className="fwire" aria-hidden="true">
      <span>{label}</span>
      <div className="ln">{pulse > 0 && <i key={pulse} style={{ animationDelay: `${delay}ms` }} />}</div>
    </div>
  );
}

/** Every value change for one machine from the WebSocket, with the change vs. the previous value. */
function useTrace(id, onValues, m) {
  const [rows, setRows] = useState([]);
  const [paused, setPaused] = useState(false);
  const [pulse, setPulse] = useState(0);
  const prev = useRef(new Map());
  const lats = useRef([]);
  const pausedRef = useRef(false);
  pausedRef.current = paused;

  // seed "previous" values from the snapshot so the first live change has a delta
  const seeded = useRef(false);
  if (m && !seeded.current) {
    seeded.current = true;
    for (const t of Object.values(m.tags)) prev.current.set(t.tagId, { num: t.num, text: t.text });
  }

  useEffect(() => onValues((items) => {
    const mine = items.filter((i) => i.machineId === id);
    if (!mine.length) return;
    const at = Date.now();
    const add = mine.map((i, k) => {
      const p = prev.current.get(i.tagId);
      prev.current.set(i.tagId, { num: i.num, text: i.text });
      const t = Date.parse(i.time);
      const lat = Number.isFinite(t) ? Math.max(0, at - t) : null;
      if (lat != null) lats.current = [...lats.current.slice(-59), lat];
      const numeric = i.num != null && i.text == null;
      return {
        key: `${at}-${k}-${i.tagId}`, at, t, tag: i.tag, unit: i.unit, num: i.num, text: i.text, quality: i.quality, lat,
        delta: numeric && p?.num != null ? i.num - p.num : null,
        changed: !numeric && p && (p.text !== i.text || p.num !== i.num), prevText: p ? valueText(p) : null,
      };
    });
    setPulse((n) => n + 1);
    if (!pausedRef.current) setRows((r) => [...add.reverse(), ...r].slice(0, 80));
  }), [onValues, id]);

  const sorted = [...lats.current].sort((a, b) => a - b);
  const latency = sorted.length ? { p50: sorted[Math.floor(sorted.length / 2)], max: sorted[sorted.length - 1] } : null;
  return { rows, paused, toggle: () => setPaused((p) => !p), pulse, latency };
}

/** 24-hour hh:mm:ss, so milliseconds can follow directly. */
function clock24(t) {
  const d = new Date(t);
  return [d.getHours(), d.getMinutes(), d.getSeconds()].map((n) => String(n).padStart(2, "0")).join(":");
}

function valueText(r) {
  if (r.text === "true" || r.text === "false") return r.num ? "ON" : "OFF";
  if (r.num == null) return r.text ?? "–";
  return fmtNum(r.num);
}

function addressOf(p) {
  if (!p) return "–";
  if (p.kind === "modbus") return `[${p.device}]${p.address}`;
  if (p.kind === "opcua") return `${p.device} · ${p.node?.replace(/^ns=\d+;s=/, "") ?? "–"}`;
  if (p.kind === "expression") return "expression";
  return "–";
}

function sourceOf(cfg, points) {
  if (!points.length) return cfg ? PROTO.other : "…";
  const kinds = [...new Set(points.map((p) => p.kind))];
  const kind = kinds.length === 1 ? kinds[0] : "other";
  const dev = points.find((p) => p.device)?.device;
  const conn = dev && cfg?.sources?.[kind]?.[dev];
  if (kind === "modbus" && conn) return `${PROTO.modbus} · ${dev} · ${conn.host}:${conn.port} · unit ${conn.unitId}`;
  if (kind === "opcua" && conn) return `${PROTO.opcua} · ${dev} · ${conn.endpoint}`;
  return PROTO[kind] ?? PROTO.other;
}

// ---------------------------------------------------------------- KPIs per machine kind

function stateShare(series, tag, m, t0, t1) {
  const t = m.tags[tag];
  if (!t) return null;
  const segs = segments(series.get(t.tagId), t0, t1, { time: t.time, v: t.num, s: t.text }, onOf);
  let on = 0;
  let known = 0;
  let edges = 0;
  let last;
  for (const s of segs) {
    if (s.v == null) continue;
    known += s.b - s.a;
    if (s.v) on += s.b - s.a;
    if (last === false && s.v === true) edges++;
    last = s.v;
  }
  return { share: known ? on / known : null, edges, known };
}

function numbers(series, m, tag, t0) {
  const t = m.tags[tag];
  if (!t) return [];
  return (series.get(t.tagId) ?? []).filter((p) => p.v != null && p.t >= t0);
}

const avgOf = (pts) => (pts.length ? pts.reduce((a, p) => a + p.v, 0) / pts.length : null);
const pct = (v) => (v == null ? "–" : fmtNum(v * 100, 0));

function slopePerMin(pts) {
  if (pts.length < 3) return null;
  const n = pts.length;
  const mx = pts.reduce((a, p) => a + p.t, 0) / n;
  const my = pts.reduce((a, p) => a + p.v, 0) / n;
  let num = 0;
  let den = 0;
  for (const p of pts) {
    num += (p.t - mx) * (p.v - my);
    den += (p.t - mx) ** 2;
  }
  return den ? (num / den) * 60_000 : null;
}

function kpis(m, series, t0, t1) {
  const win = `last ${Math.round((t1 - t0) / 60000)} min`;
  const cur = (tag) => (good(m.tags[tag]) ? m.tags[tag].num : null);
  if (m.kind === "line") {
    const run = stateShare(series, "running", m, t0, t1);
    const sp = numbers(series, m, "speed", t0);
    const te = numbers(series, m, "temperature", t0);
    return [
      { label: "Availability", value: pct(run?.share), unit: "%", note: `time running · ${win}` },
      { label: "Stops", value: run ? run.edges : "–", unit: "", note: `restarts after a stop · ${win}` },
      { label: "Average speed", value: fmtNum(avgOf(sp), 0), unit: " bpm", note: `${sp.length} samples` },
      { label: "Peak temperature", value: te.length ? fmtNum(Math.max(...te.map((p) => p.v)), 1) : "–", unit: " °C", note: win },
    ];
  }
  if (m.kind === "pump") {
    const run = stateShare(series, "running", m, t0, t1);
    const trip = stateShare(series, "tripped", m, t0, t1);
    const pr = numbers(series, m, "discharge_pressure", t0);
    const mt = numbers(series, m, "motor_temp", t0);
    return [
      { label: "Running", value: pct(run?.share), unit: "%", note: `time running · ${win}` },
      { label: "Trips", value: trip ? trip.edges : "–", unit: "", note: `over-temperature trips · ${win}` },
      { label: "Avg discharge pressure", value: fmtNum(avgOf(pr), 2), unit: " bar", note: `${pr.length} samples` },
      { label: "Peak motor temp", value: mt.length ? fmtNum(Math.max(...mt.map((p) => p.v)), 1) : "–", unit: " °C", note: `trip limit ${specOf("motor_temp").hi} °C` },
    ];
  }
  if (m.kind === "tank") {
    const lv = numbers(series, m, "level", t0);
    const recent = lv.filter((p) => p.t >= t1 - 5 * 60_000);
    const slope = slopePerMin(recent);
    const level = cur("level");
    const s = specOf("level");
    let eta = "–";
    let etaNote = "level steady";
    if (slope && level != null) {
      const target = slope > 0 ? s.hi : s.lo;
      const mins = (target - level) / slope;
      if (mins > 0) {
        eta = fmtNum(mins, 1);
        etaNote = `until ${slope > 0 ? "HI" : "LO"} (${target} %) at the current rate`;
      } else etaNote = slope > 0 ? "above HI" : "below LO";
    }
    const of = numbers(series, m, "outlet_flow", t0);
    return [
      { label: "Level", value: level == null ? "–" : fmtNum(level, 1), unit: " %", note: `band ${s.lo}–${s.hi} %` },
      { label: "Fill rate", value: slope == null ? "–" : `${slope > 0 ? "+" : ""}${fmtNum(slope, 2)}`, unit: " %/min", note: "trend over the last 5 min" },
      { label: slope > 0 ? "Time to HI" : "Time to LO", value: eta, unit: eta === "–" ? "" : " min", note: etaNote },
      { label: "Avg outlet flow", value: fmtNum(avgOf(of), 1), unit: " m³/h", note: win },
    ];
  }
  if (m.kind === "meter") {
    const en = numbers(series, m, "energy_total", t0);
    const pw = numbers(series, m, "active_power", t0);
    const vo = numbers(series, m, "bus_voltage", t0);
    const fr = numbers(series, m, "frequency", t0);
    let sags = 0;
    for (let i = 1; i < vo.length; i++) if (vo[i - 1].v >= 380 && vo[i].v < 380) sags++;
    return [
      { label: "Energy used", value: en.length > 1 ? fmtNum(en[en.length - 1].v - en[0].v, 2) : "–", unit: " kWh", note: win },
      { label: "Average power", value: fmtNum(avgOf(pw), 1), unit: " kW", note: `${pw.length} samples` },
      { label: "Voltage sags", value: vo.length ? sags : "–", unit: "", note: `dips below 380 V · ${win}` },
      { label: "Max frequency deviation", value: fr.length ? fmtNum(Math.max(...fr.map((p) => Math.abs(p.v - 50))) * 1000, 0) : "–", unit: " mHz", note: "from 50 Hz" },
    ];
  }
  const all = Object.values(m.tags);
  return [
    { label: "Tags", value: all.length, unit: "", note: `${all.filter(good).length} good` },
    { label: "Numeric tags", value: all.filter(isNum).length, unit: "", note: "shown as trends" },
  ];
}
