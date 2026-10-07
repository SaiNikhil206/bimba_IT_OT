import { useEffect, useMemo, useRef, useState } from "react";
import { fmtNum, specOf } from "./lib.js";
import { VIA } from "./shared.jsx";

// Packet stream inspector for one machine. Per message:
//   device    what the field device holds for this value (derived from the plugin's tag map)
//   canonical the real BIMBA message as the Kafka mirror received it
//   delivery  Kafka partition/offset + broker time, and when/how PostgreSQL stored it (by messageId)

const KEEP = 40;
const FN = { HR: [3, "Read Holding Registers"], IR: [4, "Read Input Registers"], C: [1, "Read Coils"], DI: [2, "Read Discrete Inputs"] };

export default function Inspector({ id, cfg }) {
  const [rows, setRows] = useState([]);
  const [paused, setPaused] = useState(false);
  const [tagFilter, setTagFilter] = useState("");
  const [status, setStatus] = useState(null);
  const stored = useRef(new Map());
  const [, setTick] = useState(0);
  const pausedRef = useRef(paused);
  pausedRef.current = paused;

  // newest Kafka mirror records for this machine
  useEffect(() => {
    let alive = true;
    let timer;
    const load = async () => {
      if (!pausedRef.current && !document.hidden) {
        try {
          const r = await fetch(`/api/console/kafka-tail/?machine=${encodeURIComponent(id)}&depth=60`);
          const d = await r.json();
          if (!alive) return;
          setStatus(d);
          if (d.available !== false) {
            const incoming = [];
            for (const rec of d.records ?? []) {
              for (const msg of rec.messages ?? []) {
                incoming.push({ id: msg.messageId, msg, kafka: { p: rec.partition, o: rec.offset, ts: rec.timestamp, batch: rec.batchId } });
              }
            }
            setRows((prev) => {
              const seen = new Set(prev.map((x) => x.id));
              const add = incoming.filter((x) => x.id && !seen.has(x.id));
              if (!add.length) return prev;
              return [...add, ...prev].sort((a, b) => (b.msg.edge?.seq ?? 0) - (a.msg.edge?.seq ?? 0)).slice(0, KEEP);
            });
          }
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

  // ask PostgreSQL which of the shown messages it has stored (and when, and via which path)
  const rowsRef = useRef(rows);
  rowsRef.current = rows;
  useEffect(() => {
    let alive = true;
    let timer;
    const check = async () => {
      const want = rowsRef.current.filter((x) => !stored.current.has(x.id) && Date.now() - x.kafka.ts < 180_000).map((x) => x.id);
      if (want.length && !document.hidden) {
        try {
          const r = await fetch(`/api/messages/lookup/?ids=${want.slice(0, 200).join(",")}`);
          const d = await r.json();
          let hit = false;
          for (const [k, v] of Object.entries(d.messages ?? {})) {
            stored.current.set(k, v);
            hit = true;
          }
          if (alive && hit) setTick((n) => n + 1);
        } catch {
          /* retried on the next round */
        }
      }
      if (alive) timer = setTimeout(check, 1500);
    };
    check();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, []);

  const points = useMemo(() => new Map((cfg?.points ?? []).filter((p) => p.machine === id).map((p) => [p.tag, p])), [cfg, id]);
  const tags = [...new Set(rows.map((r) => r.msg.tagName))].sort();
  const shown = rows.filter((r) => !tagFilter || r.msg.tagName === tagFilter);
  const storedCount = rows.filter((r) => stored.current.has(r.id)).length;

  return (
    <section className="cr-panel mx-insp">
      <div className="cr-ph">
        <div>
          <h2>Packet stream inspector</h2> <span className="mx-tap">LIVE WIRE TAP</span>
          <div className="muted">
            {status?.available === false
              ? `Kafka mirror unavailable: ${status.error ?? "unknown error"}`
              : status
                ? `Kafka ${status.topic} · ${rows.length} messages for ${id} · ${storedCount} confirmed in PostgreSQL`
                : "Reading the Kafka mirror…"}
          </div>
        </div>
        <div className="mx-insp-ctl">
          <select value={tagFilter} onChange={(e) => setTagFilter(e.target.value)} aria-label="Filter by tag">
            <option value="">All tags</option>
            {tags.map((t) => <option key={t} value={t}>{specOf(t).label}</option>)}
          </select>
          <button className={`cr-pause${paused ? " on" : ""}`} onClick={() => setPaused((p) => !p)}>{paused ? "▶ Resume stream" : "❚❚ Pause stream"}</button>
          <button className="cr-pause" onClick={() => setRows([])}>Clear view</button>
        </div>
      </div>

      <div className="mx-insp-grid head" aria-hidden="true">
        <span>Timestamp</span><span>At the device <em>derived from tag map</em></span><span>Canonical record <em>BIMBA schema, as Kafka received it</em></span><span>Delivery</span>
      </div>
      {shown.length === 0 && (
        <p className="muted mx-insp-empty">
          {status ? `No messages for ${id} in the newest Kafka records yet. Values are sent on change.` : "Waiting for the first records…"}
        </p>
      )}
      <div className="mx-insp-rows">
        {shown.map((r) => (
          <Row key={r.id} r={r} p={points.get(r.msg.tagName)} cfg={cfg} db={stored.current.get(r.id)} />
        ))}
      </div>
    </section>
  );
}

function Row({ r, p, cfg, db }) {
  const src = Date.parse(r.msg.timestamp);
  const age = Date.now() - r.kafka.ts;
  const dbAt = db ? Date.parse(db.receivedAt) : null;
  return (
    <div className="mx-insp-grid row">
      <div className="ts">
        <b>{clock(src)}</b>
        <span>seq #{r.msg.edge?.seq ?? "–"}</span>
        <span className="tg">{specOf(r.msg.tagName).label}</span>
      </div>
      <Json value={deviceView(r.msg, p, cfg)} />
      <Json value={r.msg} />
      <div className="dl">
        <span className="chip k">
          <i aria-hidden="true" />Kafka p{r.kafka.p} @{r.kafka.o}
          <small>{clock(r.kafka.ts)} · +{fmtNum((r.kafka.ts - src) / 1000, 2)} s</small>
        </span>
        {db ? (
          <span className="chip s">
            <i aria-hidden="true" />PostgreSQL · {VIA[db.via] ?? db.via}
            <small>{clock(dbAt)} · +{fmtNum((dbAt - src) / 1000, 2)} s</small>
          </span>
        ) : age < 60_000 ? (
          <span className="chip w"><i aria-hidden="true" />PostgreSQL · in flight…<small>waiting for Boomi Event Streams</small></span>
        ) : (
          <span className="chip x"><i aria-hidden="true" />PostgreSQL · not found<small>not stored after {Math.round(age / 1000)} s</small></span>
        )}
        {db && <span className="e2e">device → database {fmtNum((dbAt - src) / 1000, 2)} s</span>}
      </div>
    </div>
  );
}

/** What the field device holds for this value, from the plugin's tag map (register / node / expression). */
function deviceView(msg, p, cfg) {
  if (!p) return { protocol: msg.source?.protocol ?? "ignition", address: msg.source?.address, note: "tag not in the tag map" };
  if (p.kind === "modbus") {
    const conn = cfg?.sources?.modbus?.[p.device] ?? {};
    const m = /^(?:\d+\.)?(HR|IR|C|DI)(\d+)$/i.exec(p.address ?? "");
    const area = m?.[1]?.toUpperCase();
    const n = m ? Number(m[2]) : null;
    const offset = n == null ? null : conn.zeroBased ? n : n - 1;
    const [fc, fname] = FN[area] ?? [null, null];
    const bit = area === "C" || area === "DI";
    const raw = msg.tagValue == null ? null
      : bit ? (msg.tagValue ? 1 : 0)
      : Math.round(Number(msg.tagValue) / (p.scale || 1)) & 0xffff;
    // most telling fields first: the raw register word, then how it is addressed
    return {
      protocol: "Modbus TCP", unitId: conn.unitId, function: fc, register: offset,
      raw, rawHex: raw == null || bit ? undefined : `0x${raw.toString(16).toUpperCase().padStart(4, "0")}`,
      scale: p.scale && p.scale !== 1 ? p.scale : undefined,
      functionName: fname, address: `[${p.device}]${p.address}`, host: conn.host ? `${conn.host}:${conn.port}` : undefined,
    };
  }
  if (p.kind === "opcua") {
    const conn = cfg?.sources?.opcua?.[p.device] ?? {};
    return { protocol: "OPC UA", connection: p.device, endpoint: conn.endpoint, nodeId: p.node, dataType: p.dataType, value: msg.tagValue };
  }
  return { protocol: "Ignition expression", tag: msg.source?.address, value: msg.tagValue, note: "computed in the gateway, no field device" };
}

function clock(t) {
  if (!Number.isFinite(t)) return "–";
  const d = new Date(t);
  return `${[d.getHours(), d.getMinutes(), d.getSeconds()].map((n) => String(n).padStart(2, "0")).join(":")}.${String(d.getMilliseconds()).padStart(3, "0")}`;
}

/** Pretty JSON with syntax colors (undefined fields are left out, like JSON.stringify). */
export function Json({ value }) {
  return <pre className="mx-json">{render(value, 0)}</pre>;
}

function render(v, depth) {
  const pad = "  ".repeat(depth);
  if (v === null) return <span className="n">null</span>;
  if (typeof v === "string") return <span className="s">"{v}"</span>;
  if (typeof v === "number") return <span className="d">{v}</span>;
  if (typeof v === "boolean") return <span className="b">{String(v)}</span>;
  if (Array.isArray(v)) return <>[{v.map((x, i) => <span key={i}>{"\n"}{pad}  {render(x, depth + 1)}{i < v.length - 1 ? "," : ""}</span>)}{"\n"}{pad}]</>;
  const entries = Object.entries(v).filter(([, x]) => x !== undefined);
  return (
    <>
      {"{"}
      {entries.map(([k, x], i) => (
        <span key={k}>{"\n"}{pad}  <span className="k">"{k}"</span>: {render(x, depth + 1)}{i < entries.length - 1 ? "," : ""}</span>
      ))}
      {"\n"}{pad}{"}"}
    </>
  );
}
