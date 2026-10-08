import { useEffect, useMemo, useRef } from "react";
import { reducedMotion, since } from "./lib.js";
import { STATUS_ICON } from "./viz.jsx";

// "Data river": devices -> edge gateway -> IT targets -> PostgreSQL.
// Every value stored by bimba_ingest() (WebSocket feed) launches one particle along the route it took.

const W = 1000;
const H = 330;
const GW = { x: 430, y: H / 2 };
const MX = 170; // right edge of the device column
const DX = 640; // left edge of the target column
const DW = 150;
const DBX = 930;

const TARGET = { eventstreams: "Boomi Event Streams", kafka: "Kafka", rest: "REST", mqtt: "MQTT", database: "BIMBA database" };
function targetName(d) {
  if (/\/api\/ingest\/?$/.test(d.endpoint ?? "")) return "BIMBA Ingest";
  return TARGET[d.transport] ?? d.transport;
}
/** CSS color slot per target: follows the entity, never its position. */
function slot(d) {
  if (d.transport === "eventstreams" || d.name?.startsWith("boomi")) return "p1";
  if (d.transport === "kafka") return "p3";
  return "p2";
}

function spread(n, span, center) {
  if (n <= 1) return [center];
  const gap = Math.min(64, span / (n - 1));
  return Array.from({ length: n }, (_, i) => center + (i - (n - 1) / 2) * gap);
}

export default function River({ machines, plugin, onValues, stored, rateByMachine, onOpen }) {
  const layer = useRef(null);
  const paths = useRef({});
  const parts = useRef([]);
  const raf = useRef(0);

  const ms = machines.slice(0, 6);
  // devices sit below the column captions (y 18): first box top >= 36 for up to 6 devices
  const my = spread(ms.length, H - 90, H / 2 + 10);

  const dests = useMemo(() => {
    const out = [];
    for (const r of plugin?.delivery?.routes ?? []) {
      for (const d of r.destinations ?? []) out.push({ ...d, route: r.name, active: r.activeDestination === d.name, main: (r.kind ?? r.name) === "main" });
    }
    return out;
  }, [plugin]);
  const dy = spread(dests.length, H - 60, H / 2);
  const mainY = dests.map((d, i) => (d.main ? dy[i] : null)).filter((v) => v != null);
  const dbY = mainY.length ? mainY.reduce((a, b) => a + b, 0) / mainY.length : H / 2;

  // keep the latest layout reachable from the WebSocket callback
  const layout = useRef({});
  layout.current = { ms, dests };

  useEffect(() => {
    if (reducedMotion()) return undefined;
    const tick = (now) => {
      const g = layer.current;
      const alive = [];
      for (const p of parts.current) {
        const segs = p.segs;
        let el = p.el;
        const t = now - p.start;
        if (t < 0) { alive.push(p); continue; }
        let acc = 0;
        let idx = segs.findIndex((s) => (acc += s.dur) > t);
        if (idx === -1) { el?.remove(); continue; }
        if (!el) {
          el = document.createElementNS("http://www.w3.org/2000/svg", "circle");
          el.setAttribute("r", p.r);
          g.appendChild(el);
          p.el = el;
        }
        const s = segs[idx];
        const before = segs.slice(0, idx).reduce((a, x) => a + x.dur, 0);
        const path = paths.current[s.id];
        if (!path) { el.remove(); continue; }
        const k = (t - before) / s.dur;
        const e = k < 0.5 ? 2 * k * k : 1 - (-2 * k + 2) ** 2 / 2;
        const pt = path.getPointAtLength(path.getTotalLength() * e);
        el.setAttribute("cx", pt.x);
        el.setAttribute("cy", pt.y);
        el.setAttribute("class", `pt ${s.cls}`);
        alive.push(p);
      }
      parts.current = alive;
      raf.current = alive.length ? requestAnimationFrame(tick) : 0;
    };

    const off = onValues((items) => {
      if (document.hidden) return;
      const { ms: cur, dests: ds } = layout.current;
      const main = ds.find((d) => d.main && d.active);
      const mirrors = ds.filter((d) => !d.main && d.active && d.state !== "down");
      const now = performance.now();
      items.slice(0, 14).forEach((it, i) => {
        const mi = cur.findIndex((m) => m.machineId === it.machineId);
        if (mi < 0) return;
        const start = now + i * 70;
        const up = { id: `m${mi}`, dur: 900, cls: "ink" };
        if (main) {
          parts.current.push({ start, r: 3.2, segs: [up, { id: `d${main.name}`, dur: 700, cls: slot(main) }, { id: `b${main.name}`, dur: 600, cls: slot(main) }] });
        }
        for (const mir of mirrors) {
          parts.current.push({ start, r: 2.4, segs: [up, { id: `d${mir.name}`, dur: 800, cls: slot(mir) }] });
        }
      });
      if (parts.current.length > 260) parts.current.splice(0, parts.current.length - 260).forEach((p) => p.el?.remove());
      if (!raf.current) raf.current = requestAnimationFrame(tick);
    });
    return () => {
      off();
      cancelAnimationFrame(raf.current);
      raf.current = 0;
      parts.current.forEach((p) => p.el?.remove());
      parts.current = [];
    };
  }, [onValues]);

  const reg = (id) => (el) => {
    if (el) paths.current[id] = el;
    else delete paths.current[id];
  };
  const col = plugin?.collector;

  return (
    <svg className="cr-river" viewBox={`0 0 ${W} ${H}`} width="100%" role="img"
         aria-label="Data flow from devices through the edge gateway to Boomi Event Streams, BIMBA Ingest, Kafka and PostgreSQL">
      <defs>
        <radialGradient id="gw-glow">
          <stop offset="0" className="glow-a" />
          <stop offset="1" className="glow-b" />
        </radialGradient>
      </defs>

      {/* column captions */}
      <text x="20" y="18" className="cap">OT · DEVICES</text>
      <text x={GW.x} y="18" className="cap mid">EDGE · IGNITION</text>
      <text x={DX} y="18" className="cap">IT · TARGETS</text>
      <text x={DBX} y="18" className="cap mid">STORE</text>

      {/* device -> gateway links */}
      {ms.map((m, i) => {
        const d = `M${MX} ${my[i]}C${MX + 130} ${my[i]} ${GW.x - 150} ${GW.y} ${GW.x - 44} ${GW.y}`;
        const lost = m.health === "critical";
        return (
          <g key={m.machineId}>
            <path d={d} className="link" />
            <path d={d} className={`link flow ink${lost ? " cut" : ""}`} ref={reg(`m${i}`)} />
          </g>
        );
      })}

      {/* gateway -> targets, targets -> DB */}
      {dests.map((d, i) => {
        const y = dy[i];
        const p1 = `M${GW.x + 44} ${GW.y}C${GW.x + 120} ${GW.y} ${DX - 90} ${y} ${DX} ${y}`;
        const p2 = `M${DX + DW} ${y}C${DX + DW + 40} ${y} ${DBX - 70} ${dbY} ${DBX - 34} ${dbY}`;
        const cls = `link flow ${slot(d)}${d.active ? "" : " idle"}${d.state === "down" ? " cut" : ""}`;
        return (
          <g key={d.name}>
            <path d={p1} className="link" />
            <path d={p1} className={cls} ref={reg(`d${d.name}`)} />
            {d.main && (
              <>
                <path d={p2} className="link" />
                <path d={p2} className={cls} ref={reg(`b${d.name}`)} />
              </>
            )}
          </g>
        );
      })}

      {/* devices */}
      {ms.map((m, i) => (
        <g key={m.machineId} transform={`translate(20 ${my[i] - 19})`} className={`dev ${m.health}${onOpen ? " openable" : ""}`}
           role={onOpen ? "link" : undefined} tabIndex={onOpen ? 0 : undefined} aria-label={`Open ${m.machineId} dashboard`}
           onClick={onOpen ? () => onOpen(m.machineId) : undefined}
           onKeyDown={onOpen ? (e) => (e.key === "Enter" || e.key === " ") && (e.preventDefault(), onOpen(m.machineId)) : undefined}>
          <rect width={MX - 20} height="38" rx="9" className="box" />
          <circle cx="16" cy="19" r="5" className="led" />
          <text x="30" y="17" className="nm">{m.machineId}</text>
          <text x="30" y="30" className="sub">
            {m.health === "critical" ? "signal lost" : `${rateByMachine[m.machineId] ?? 0} values / min`}
          </text>
        </g>
      ))}

      {/* gateway */}
      <g transform={`translate(${GW.x} ${GW.y})`} className={`gw${plugin?.reachable ? "" : " off"}`}>
        <circle r="78" fill="url(#gw-glow)" />
        <circle r="56" className="orbit" />
        <g className="spin"><circle r="56" className="orbit-dash" /></g>
        <polygon points={hex(44)} className="core" />
        <polygon points={hex(34)} className="core-in" />
        <text y="-4" className="gw-t">{plugin?.edgeId ?? "gateway"}</text>
        <text y="11" className="gw-s">{plugin?.reachable ? `seq ${(col?.lastSeq ?? 0).toLocaleString()}` : "unreachable"}</text>
        <text y="76" className="gw-s">{plugin?.module ?? "BIMBA IT/OT Agent"} {plugin?.version ?? ""}</text>
      </g>

      {/* targets */}
      {dests.map((d, i) => {
        const state = d.state === "down" ? "critical" : d.active ? "good" : "neutral";
        const label = d.state === "down" ? "Down" : d.active ? "Delivering" : "Standby";
        // a probed standby has no recent deliveries, so show when it was last health-checked instead
        const when = !d.active && d.lastProbeAt ? `checked ${since(d.lastProbeAt)}` : since(d.lastSuccessAt);
        return (
          <g key={d.name} transform={`translate(${DX} ${dy[i] - 27})`} className={`dst ${slot(d)} ${state}`}>
            <rect width={DW} height="54" rx="10" className="box" />
            <rect width="4" height="34" x="0" y="10" rx="2" className="bar" />
            <text x="14" y="18" className="nm">{targetName(d)}</text>
            <text x="14" y="31" className="sub">{d.role} · {(d.messages ?? 0).toLocaleString()} msgs</text>
            <text x="14" y="45" className={`st ${state}`}>{STATUS_ICON[state]} {label} · {when}</text>
          </g>
        );
      })}

      {/* database */}
      <g transform={`translate(${DBX} ${dbY})`} className="db">
        <path d="M-32 -30v56a32 10 0 0 0 64 0v-56" className="body" />
        <ellipse cx="0" cy="-30" rx="32" ry="10" className="top" />
        <path d="M-32 -10a32 10 0 0 0 64 0M-32 8a32 10 0 0 0 64 0" className="ring" />
        <text y="50" className="nm">PostgreSQL</text>
        <text y="63" className="sub">{stored != null ? `${stored.toLocaleString()} rows` : "schema bimba"}</text>
      </g>

      <g ref={layer} className="particles" />
    </svg>
  );
}

function hex(r) {
  return Array.from({ length: 6 }, (_, i) => {
    const a = (Math.PI / 3) * i + Math.PI / 6;
    return `${(r * Math.cos(a)).toFixed(1)},${(r * Math.sin(a)).toFixed(1)}`;
  }).join(" ");
}
