import { useEffect, useMemo, useRef, useState } from "react";
import { fmtNum, specOf, unit } from "./data.js";
import { cap } from "./model.js";
import { Card, Empty, Seg, Status } from "./ui.jsx";

// Messages tab. Two views of the same stream, simple first:
//   Changes           every value change as it is stored (live feed)
//   Packet inspector  per message, side by side: what the device holds (from the tag map), the
//                     canonical message as Kafka received it, and when Kafka / PostgreSQL confirmed it

export default function Messages({ m, live, cfg, points }) {
  const [mode, setMode] = useState("changes");
  const [paused, setPaused] = useState(false);
  return (
    <Card
      title={mode === "changes" ? "Value changes" : <>Packet inspector <span className="op-tap">Live</span></>}
      subtitle={mode === "changes"
        ? "Every change stored for this machine, newest first."
        : "Each message from the device to the database, side by side."}
      action={
        <div className="op-row">
          <Seg items={[["changes", "Changes"], ["delivery", "Packet inspector"]]} value={mode} onChange={setMode} label="View" />
          <button className="op-btn ghost" onClick={() => setPaused((p) => !p)}>{paused ? "Resume stream" : "Pause stream"}</button>
        </div>
      }
      flush
    >
      {mode === "changes" ? <Changes m={m} live={live} paused={paused} /> : <Delivery m={m} cfg={cfg} points={points} paused={paused} />}
    </Card>
  );
}

const clock = (t) => {
  if (!Number.isFinite(t)) return "–";
  const d = new Date(t);
  return `${[d.getHours(), d.getMinutes(), d.getSeconds()].map((n) => String(n).padStart(2, "0")).join(":")}.${String(d.getMilliseconds()).padStart(3, "0")}`;
};
/** A canonical tagValue: number, boolean or text. */
const msgVal = (v) => (typeof v === "boolean" ? (v ? "On" : "Off") : typeof v === "number" ? fmtNum(v) : v == null ? "–" : cap(String(v)));
const valText = (num, text) => (text === "true" || text === "false" ? (num ? "On" : "Off") : num == null ? cap(text) ?? "–" : fmtNum(num));

function Changes({ m, live, paused }) {
  const [rows, setRows] = useState([]);
  const prev = useRef(new Map(Object.values(m.tags).map((t) => [t.tagId, t.num])));
  const pausedRef = useRef(paused);
  pausedRef.current = paused;
  const { onValues } = live;
  useEffect(() => onValues((items) => {
    const mine = items.filter((i) => i.machineId === m.machineId);
    if (!mine.length) return;
    const at = Date.now();
    const add = mine.map((i, k) => {
      const p = prev.current.get(i.tagId);
      prev.current.set(i.tagId, i.num);
      const numeric = i.num != null && i.text == null;
      return { key: `${at}-${k}-${i.tagId}`, t: Date.parse(i.time), tag: i.tag, u: i.unit, num: i.num, text: i.text, q: i.quality,
               delta: numeric && p != null ? i.num - p : null, lat: at - Date.parse(i.time) };
    });
    if (!pausedRef.current) setRows((r) => [...add.reverse(), ...r].slice(0, 60));
  }), [onValues, m.machineId]);

  if (!rows.length) return <Empty>Waiting for the next change… values are sent when they change, so a steady machine sends nothing.</Empty>;
  return (
    <div className="op-scroll">
      <table className="op-table">
        <thead><tr><th>Time</th><th>Reading</th><th className="r">Value</th><th className="r">Change</th><th>Quality</th><th className="r">Delay</th></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key}>
              <td className="mono">{clock(r.t)}</td>
              <td>{specOf(r.tag).label}</td>
              <td className="r num">{valText(r.num, r.text)} <span className="sub">{unit(r.u)}</span></td>
              <td className={`r mono ${r.delta > 0 ? "up" : r.delta < 0 ? "down" : "sub"}`}>{r.delta == null ? "–" : `${r.delta > 0 ? "+" : ""}${fmtNum(r.delta)}`}</td>
              <td><Status state={r.q === "GOOD" ? "good" : "critical"}>{r.q === "GOOD" ? "Good" : r.q}</Status></td>
              <td className="r mono sub" title="From the reading's timestamp to this screen">{fmtNum(r.lat / 1000, 1)} s</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const FN = { HR: [3, "Read Holding Registers"], IR: [4, "Read Input Registers"], C: [1, "Read Coils"], DI: [2, "Read Discrete Inputs"] };

function Delivery({ m, cfg, points, paused }) {
  const id = m.machineId;
  const [rows, setRows] = useState([]);
  const [status, setStatus] = useState(null);
  const [tagFilter, setTagFilter] = useState("");
  const stored = useRef(new Map());
  const [, setTick] = useState(0);
  const pausedRef = useRef(paused);
  pausedRef.current = paused;
  const rowsRef = useRef(rows);
  rowsRef.current = rows;

  // newest Kafka records for this machine: the first request always runs, repeats pause while hidden
  useEffect(() => {
    let alive = true;
    let timer;
    let first = true;
    const load = async () => {
      if (!pausedRef.current && (first || !document.hidden)) {
        first = false;
        try {
          const d = await (await fetch(`/api/console/kafka-tail/?machine=${encodeURIComponent(id)}&depth=60`)).json();
          if (!alive) return;
          setStatus(d);
          const inc = (d.records ?? []).flatMap((rec) => (rec.messages ?? []).map((msg) => ({ id: msg.messageId, msg, k: { p: rec.partition, o: rec.offset, ts: rec.timestamp } })));
          setRows((prev) => {
            const seen = new Set(prev.map((x) => x.id));
            const add = inc.filter((x) => x.id && !seen.has(x.id));
            return add.length ? [...add, ...prev].sort((a, b) => (b.msg.edge?.seq ?? 0) - (a.msg.edge?.seq ?? 0)).slice(0, 40) : prev;
          });
        } catch (e) {
          if (alive) setStatus({ available: false, error: e.message });
        }
      }
      if (alive) timer = setTimeout(load, 2500);
    };
    load();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [id]);

  // ask PostgreSQL which shown messages it has stored, when, and via which path
  useEffect(() => {
    let alive = true;
    let timer;
    const check = async () => {
      const want = rowsRef.current.filter((x) => !stored.current.has(x.id) && Date.now() - x.k.ts < 180_000).map((x) => x.id);
      if (want.length) {
        try {
          const d = await (await fetch(`/api/messages/lookup/?ids=${want.join(",")}`)).json();
          let hit = false;
          for (const [k, v] of Object.entries(d.messages ?? {})) { stored.current.set(k, v); hit = true; }
          if (alive && hit) setTick((n) => n + 1);
        } catch { /* next round */ }
      }
      if (alive) timer = setTimeout(check, 1500);
    };
    check();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, []);

  const pmap = useMemo(() => new Map(points.map((p) => [p.tag, p])), [points]);
  if (status?.available === false) return <Empty>The packet inspector reads the Kafka copy: {status.error}</Empty>;

  const tags = [...new Set(rows.map((r) => r.msg.tagName))].sort();
  const shown = rows.filter((r) => !tagFilter || r.msg.tagName === tagFilter);
  const confirmed = rows.filter((r) => stored.current.has(r.id)).length;

  return (
    <>
      <div className="op-insp-bar">
        <span className="sub">
          {status ? <>Kafka <span className="mono">{status.topic}</span> · {rows.length} messages for {id} · {confirmed} confirmed in PostgreSQL</> : "Reading the Kafka copy…"}
        </span>
        <div className="op-row">
          <select value={tagFilter} onChange={(e) => setTagFilter(e.target.value)} aria-label="Filter by reading">
            <option value="">All readings</option>
            {tags.map((t) => <option key={t} value={t}>{specOf(t).label}</option>)}
          </select>
          <button className="op-btn ghost" onClick={() => { setRows([]); setTagFilter(""); }}>Clear view</button>
        </div>
      </div>
      <div className="op-insp-grid op-insp-head" aria-hidden="true">
        <span>Timestamp</span>
        <span>At the device <em>derived from tag map</em></span>
        <span>Canonical record <em>BIMBA schema, as Kafka received it</em></span>
        <span>Delivery</span>
      </div>
      {shown.length === 0 && (
        <Empty>{status ? `No messages for ${id} in the newest Kafka records yet. Values are sent when they change.` : "Waiting for the first records…"}</Empty>
      )}
      <div className="op-insp-rows">
        {shown.map((r) => {
          const src = Date.parse(r.msg.timestamp);
          const db = stored.current.get(r.id);
          const dbAt = db ? Date.parse(db.receivedAt) : null;
          const age = Date.now() - r.k.ts;
          return (
            <div key={r.id} className="op-insp-grid op-insp-row">
              <div className="ts">
                <b className="mono">{clock(src)}</b>
                <span className="mono sub">seq #{r.msg.edge?.seq ?? "–"}</span>
                <span className="tg">{specOf(r.msg.tagName).label}</span>
                <span className="val">{msgVal(r.msg.tagValue)} <small>{unit(r.msg.unit)}</small></span>
              </div>
              <Json value={deviceView(r.msg, pmap.get(r.msg.tagName), cfg)} />
              <Json value={r.msg} />
              <div className="dl">
                <span className="op-dchip ok">
                  <i aria-hidden="true" />Kafka · partition {r.k.p} @ {r.k.o}
                  <small>{clock(r.k.ts)} · +{fmtNum((r.k.ts - src) / 1000, 2)} s</small>
                </span>
                {db ? (
                  <span className="op-dchip ok">
                    <i aria-hidden="true" />PostgreSQL · {db.via === "boomi-es" ? "Boomi Event Streams" : db.via}
                    <small>{clock(dbAt)} · +{fmtNum((dbAt - src) / 1000, 2)} s</small>
                  </span>
                ) : age < 60_000 ? (
                  <span className="op-dchip wait"><i aria-hidden="true" />PostgreSQL · in flight…<small>waiting for Boomi Event Streams</small></span>
                ) : (
                  <span className="op-dchip bad"><i aria-hidden="true" />PostgreSQL · not found<small>not stored after {Math.round(age / 1000)} s</small></span>
                )}
                {db && <span className="sub">Device → database {fmtNum((dbAt - src) / 1000, 2)} s</span>}
              </div>
            </div>
          );
        })}
      </div>
    </>
  );
}

function deviceView(msg, p, cfg) {
  if (!p) return { protocol: msg.source?.protocol ?? "ignition", address: msg.source?.address };
  if (p.kind === "modbus") {
    const conn = cfg?.sources?.modbus?.[p.device] ?? {};
    const mm = /^(?:\d+\.)?(HR|IR|C|DI)(\d+)$/i.exec(p.address ?? "");
    const area = mm?.[1]?.toUpperCase();
    const n = mm ? Number(mm[2]) : null;
    const bit = area === "C" || area === "DI";
    const raw = msg.tagValue == null ? null : bit ? (msg.tagValue ? 1 : 0) : Math.round(Number(msg.tagValue) / (p.scale || 1)) & 0xffff;
    return {
      protocol: "Modbus TCP", unitId: conn.unitId, function: FN[area]?.[0], functionName: FN[area]?.[1],
      register: n == null ? null : conn.zeroBased ? n : n - 1, raw,
      rawHex: raw == null || bit ? undefined : `0x${raw.toString(16).toUpperCase().padStart(4, "0")}`,
      scale: p.scale && p.scale !== 1 ? p.scale : undefined, host: conn.host ? `${conn.host}:${conn.port}` : undefined,
    };
  }
  if (p.kind === "opcua") {
    const conn = cfg?.sources?.opcua?.[p.device] ?? {};
    return { protocol: "OPC UA", endpoint: conn.endpoint, nodeId: p.node, dataType: p.dataType, value: msg.tagValue };
  }
  return { protocol: "Ignition expression", tag: msg.source?.address, value: msg.tagValue, note: "computed in the gateway, no field device" };
}

/** Pretty JSON with syntax colors; undefined fields are left out, like JSON.stringify. */
function Json({ value }) {
  return <pre className="op-json">{render(value, 0)}</pre>;
}

function render(v, depth) {
  const pad = "  ".repeat(depth);
  if (v === null) return <span className="j-l">null</span>;
  if (typeof v === "string") return <span className="j-s">"{v}"</span>;
  if (typeof v === "number") return <span className="j-n">{v}</span>;
  if (typeof v === "boolean") return <span className="j-l">{String(v)}</span>;
  if (Array.isArray(v)) return <>[{v.map((x, i) => <span key={i}>{"\n"}{pad}  {render(x, depth + 1)}{i < v.length - 1 ? "," : ""}</span>)}{"\n"}{pad}]</>;
  const entries = Object.entries(v).filter(([, x]) => x !== undefined);
  return (
    <>
      {"{"}
      {entries.map(([k, x], i) => (
        <span key={k}>{"\n"}{pad}  <span className="j-k">"{k}"</span>: {render(x, depth + 1)}{i < entries.length - 1 ? "," : ""}</span>
      ))}
      {"\n"}{pad}{"}"}
    </>
  );
}
