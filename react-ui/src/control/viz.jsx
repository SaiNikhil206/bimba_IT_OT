import { useId, useMemo, useState } from "react";
import { clamp01, fmtNum, hhmm, hms, specOf, unit, useWidth } from "./lib.js";

// Hand-drawn SVG marks for the Control Room. Colors come from CSS custom properties (control.css).

export const STATUS_ICON = { good: "✓", warning: "!", critical: "✕", neutral: "•" };

export function Badge({ state, children }) {
  return (
    <span className={`cr-badge ${state}`}>
      <span aria-hidden="true">{STATUS_ICON[state] ?? "•"}</span>
      {children}
    </span>
  );
}

/** Tiny trend line with a soft area under it; the newest point gets a dot. */
export function Spark({ points, w = 120, h = 28, cls = "s1", min, max }) {
  const gid = useId();
  if (!points || points.length < 2) return <svg className="cr-spark" width={w} height={h} aria-hidden="true" />;
  const t0 = points[0].t;
  const t1 = points[points.length - 1].t || t0 + 1;
  let lo = min ?? Infinity;
  let hi = max ?? -Infinity;
  if (min == null || max == null) for (const p of points) { lo = Math.min(lo, p.v); hi = Math.max(hi, p.v); }
  if (hi === lo) { hi += 1; lo -= 1; }
  const x = (t) => ((t - t0) / Math.max(1, t1 - t0)) * (w - 4) + 2;
  const y = (v) => h - 3 - clamp01((v - lo) / (hi - lo)) * (h - 6);
  const d = points.map((p, i) => `${i ? "L" : "M"}${x(p.t).toFixed(1)} ${y(p.v).toFixed(1)}`).join("");
  const last = points[points.length - 1];
  return (
    <svg className={`cr-spark ${cls}`} width={w} height={h} viewBox={`0 0 ${w} ${h}`} aria-hidden="true">
      <defs>
        <linearGradient id={gid} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" className="stop-a" />
          <stop offset="1" className="stop-b" />
        </linearGradient>
      </defs>
      <path d={`${d}L${x(last.t)} ${h}L${x(t0)} ${h}Z`} fill={`url(#${gid})`} />
      <path d={d} className="line" />
      <circle cx={x(last.t)} cy={y(last.v)} r="2.5" className="head" />
    </svg>
  );
}

const polar = (cx, cy, r, a) => [cx + r * Math.cos(a), cy + r * Math.sin(a)];
function arc(cx, cy, r, a0, a1) {
  const [x0, y0] = polar(cx, cy, r, a0);
  const [x1, y1] = polar(cx, cy, r, a1);
  return `M${x0} ${y0}A${r} ${r} 0 ${a1 - a0 > Math.PI ? 1 : 0} 1 ${x1} ${y1}`;
}

/** 240° dial with alarm ticks and a needle. */
export function Dial({ tag, value, u, size = 132, state = "good" }) {
  const s = specOf(tag);
  const A0 = (150 * Math.PI) / 180;
  const SWEEP = (240 * Math.PI) / 180;
  const f = value == null || s.min == null ? 0 : clamp01((value - s.min) / (s.max - s.min));
  const r = size / 2 - 10;
  const c = size / 2;
  const at = (v) => A0 + SWEEP * clamp01((v - s.min) / (s.max - s.min));
  const ticks = [];
  for (let i = 0; i <= 24; i++) {
    const a = A0 + (SWEEP * i) / 24;
    const major = i % 6 === 0;
    const [x0, y0] = polar(c, c, r - (major ? 11 : 7), a);
    const [x1, y1] = polar(c, c, r - 3, a);
    ticks.push(<line key={i} x1={x0} y1={y0} x2={x1} y2={y1} className={major ? "tick major" : "tick"} />);
  }
  const [nx, ny] = polar(c, c, r - 16, A0 + SWEEP * f);
  return (
    <svg className={`cr-dial ${state}`} width={size} height={size * 0.86} viewBox={`0 0 ${size} ${size * 0.86}`}
         role="img" aria-label={`${s.label}: ${fmtNum(value)} ${unit(u)}`}>
      <path d={arc(c, c, r, A0, A0 + SWEEP)} className="track" />
      {s.hi != null && <path d={arc(c, c, r, at(s.hi), A0 + SWEEP)} className="alarm hi" />}
      {s.lo != null && <path d={arc(c, c, r, A0, at(s.lo))} className="alarm lo" />}
      {value != null && <path d={arc(c, c, r, A0, A0 + Math.max(0.001, SWEEP * f))} className="fill" />}
      {ticks}
      {value != null && <line x1={c} y1={c} x2={nx} y2={ny} className="needle" />}
      <circle cx={c} cy={c} r="4" className="hub" />
      <text x={c} y={c + 22} className="dial-val">{fmtNum(value, 1)}</text>
      <text x={c} y={c + 36} className="dial-unit">{unit(u)}</text>
    </svg>
  );
}

/** Tag-quality ring: GOOD / UNCERTAIN / BAD share, with the score in the middle. */
export function QualityRing({ counts }) {
  const total = counts.good + counts.warning + counts.critical;
  const score = total ? Math.round((counts.good / total) * 100) : 0;
  const r = 52;
  const C = 2 * Math.PI * r;
  const GAP = total > 1 ? 3 : 0;
  let off = 0;
  const segs = ["good", "warning", "critical"].filter((k) => counts[k] > 0).map((k) => {
    const len = (counts[k] / total) * C;
    const seg = <circle key={k} cx="64" cy="64" r={r} className={`seg ${k}`}
                        strokeDasharray={`${Math.max(0, len - GAP)} ${C}`} strokeDashoffset={-off} />;
    off += len;
    return seg;
  });
  return (
    <div className="cr-ring">
      <svg width="128" height="128" viewBox="0 0 128 128" role="img" aria-label={`Data quality ${score}%`}>
        <circle cx="64" cy="64" r={r} className="bg" />
        <g transform="rotate(-90 64 64)">{segs}</g>
        <circle cx="64" cy="64" r="40" className="inner" />
        <text x="64" y="66" className="score">{total ? score : "–"}<tspan className="pct">%</tspan></text>
        <text x="64" y="84" className="cap">GOOD</text>
      </svg>
      <ul className="cr-legend">
        <li><Badge state="good">Good</Badge><b>{counts.good}</b></li>
        <li><Badge state="warning">Uncertain</Badge><b>{counts.warning}</b></li>
        <li><Badge state="critical">Bad</Badge><b>{counts.critical}</b></li>
      </ul>
    </div>
  );
}

/** Rolling-digit counter. */
export function Odometer({ value }) {
  const text = value == null ? "–" : Math.round(value).toLocaleString();
  return (
    <span className="cr-odo" aria-label={text}>
      {[...text].map((ch, i) =>
        /\d/.test(ch) ? (
          <span key={text.length - i} className="col" aria-hidden="true">
            <span className="strip" style={{ transform: `translateY(-${Number(ch) * 10}%)` }}>
              {"0123456789".split("").map((d) => <span key={d}>{d}</span>)}
            </span>
          </span>
        ) : (
          <span key={`s${text.length - i}`} className="sep" aria-hidden="true">{ch}</span>
        )
      )}
    </span>
  );
}

/** Minutes x end-system heatmap of stored messages. One hue, darker = more. */
export function Heat({ series, rows, minutes = 60 }) {
  const [hover, setHover] = useState(null);
  const now = Math.floor(Date.now() / 60000) * 60000;
  const cols = useMemo(() => Array.from({ length: minutes }, (_, i) => now - (minutes - 1 - i) * 60000), [now, minutes]);
  const byMin = useMemo(() => new Map((series ?? []).map((r) => [Date.parse(r.minute), r])), [series]);
  const max = Math.max(1, ...cols.flatMap((c) => rows.map((r) => byMin.get(c)?.[r.key] ?? 0)));
  const CW = 12;
  const CH = 26;
  const LW = 0;
  const W = LW + cols.length * CW;
  return (
    <div className="cr-heat">
      <div className="heat-rows">
        {rows.map((r) => <div key={r.key} className="heat-lab" style={{ height: CH }}>{r.label}</div>)}
      </div>
      <div className="heat-plot" onMouseLeave={() => setHover(null)}>
        <svg viewBox={`0 0 ${W} ${rows.length * CH + 18}`} preserveAspectRatio="none" width="100%"
             height={rows.length * CH + 18} role="img" aria-label="Messages stored per minute by end system">
          {rows.map((r, ri) =>
            cols.map((c, ci) => {
              const v = byMin.get(c)?.[r.key] ?? 0;
              const on = hover && hover.c === c && hover.r === r.key;
              return (
                <rect key={`${r.key}${c}`} x={LW + ci * CW + 1} y={ri * CH + 1} width={CW - 2} height={CH - 2} rx="2"
                      className={`cell${v ? "" : " zero"}${on ? " on" : ""}`}
                      style={v ? { fillOpacity: 0.14 + 0.86 * (v / max) } : undefined}
                      onMouseEnter={() => setHover({ c, r: r.key, v, label: r.label, ci })} />
              );
            })
          )}
        </svg>
        <div className="heat-axis">
          <span>{hhmm(cols[0])}</span><span>{hhmm(cols[Math.floor(cols.length / 2)])}</span><span>now</span>
        </div>
        {hover && (
          <div className="cr-tip" style={{ left: `${((hover.ci + 0.5) / cols.length) * 100}%` }}>
            <b>{hhmm(hover.c)}</b> · {hover.label}
            <div><strong>{hover.v.toLocaleString()}</strong> msgs stored</div>
          </div>
        )}
      </div>
      <div className="heat-scale" aria-hidden="true">
        <span>0</span><i /><span>{max.toLocaleString()} / min</span>
      </div>
    </div>
  );
}

function niceStep(raw) {
  const p = 10 ** Math.floor(Math.log10(raw || 1));
  const n = raw / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * p;
}

/** Phosphor-style oscilloscope trace with a crosshair readout. */
export function Scope({ points, tag, u }) {
  const [box, W] = useWidth(800);
  const [hx, setHx] = useState(null);
  const H = 258;
  const P = { l: 44, r: 12, t: 12, b: 24 };
  const s = specOf(tag ?? "");
  if (!points || points.length < 2) return <div ref={box} className="cr-scope empty">Waiting for values…</div>;
  const t0 = points[0].t;
  const t1 = points[points.length - 1].t;
  let lo = Infinity;
  let hi = -Infinity;
  for (const p of points) { lo = Math.min(lo, p.v); hi = Math.max(hi, p.v); }
  const pad = (hi - lo) * 0.15 || Math.abs(hi) * 0.05 || 1;
  lo -= pad;
  hi += pad;
  const x = (t) => P.l + ((t - t0) / Math.max(1, t1 - t0)) * (W - P.l - P.r);
  const y = (v) => P.t + (1 - (v - lo) / (hi - lo)) * (H - P.t - P.b);
  const d = points.map((p, i) => `${i ? "L" : "M"}${x(p.t).toFixed(1)} ${y(p.v).toFixed(1)}`).join("");
  const step = niceStep((hi - lo) / 4);
  const yt = [];
  for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) yt.push(v);
  const digits = step >= 1 ? 0 : Math.min(3, Math.ceil(-Math.log10(step)));
  const xt = Array.from({ length: 6 }, (_, i) => t0 + ((t1 - t0) * i) / 5);
  const avg = points.reduce((a, p) => a + p.v, 0) / points.length;

  let near = null;
  if (hx != null) {
    const t = t0 + ((hx - P.l) / (W - P.l - P.r)) * (t1 - t0);
    near = points.reduce((b, p) => (Math.abs(p.t - t) < Math.abs(b.t - t) ? p : b), points[0]);
  }
  const move = (e) => {
    const r = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - r.left;
    setHx(px < P.l || px > W - P.r ? null : px);
  };
  const band = (v) => v >= lo && v <= hi;
  return (
    <div ref={box} className="cr-scope">
      <svg width={W} height={H}
           onMouseMove={move} onMouseLeave={() => setHx(null)} role="img"
           aria-label={`${s.label} trend, ${points.length} points`}>
        {yt.map((v, i) => (
          <g key={i}>
            <line x1={P.l} x2={W - P.r} y1={y(v)} y2={y(v)} className="grid" />
            <text x={P.l - 6} y={y(v) + 3} className="ax r">{fmtNum(v, digits)}</text>
          </g>
        ))}
        {xt.map((t, i) => <text key={i} x={x(t)} y={H - 6} className={`ax ${i === 0 ? "l" : i === 5 ? "e" : "c"}`}>{hms(t).slice(0, 5)}</text>)}
        {s.hi != null && band(s.hi) && <line x1={P.l} x2={W - P.r} y1={y(s.hi)} y2={y(s.hi)} className="limit hi" />}
        {s.lo != null && band(s.lo) && <line x1={P.l} x2={W - P.r} y1={y(s.lo)} y2={y(s.lo)} className="limit lo" />}
        <line x1={P.l} x2={W - P.r} y1={y(avg)} y2={y(avg)} className="avg" />
        <path d={d} className="glow" />
        <path d={d} className="trace" />
        <circle cx={x(points[points.length - 1].t)} cy={y(points[points.length - 1].v)} r="4" className="beam" />
        {near && (
          <g>
            <line x1={x(near.t)} x2={x(near.t)} y1={P.t} y2={H - P.b} className="cross" />
            <circle cx={x(near.t)} cy={y(near.v)} r="5" className="probe" />
          </g>
        )}
      </svg>
      {near && (
        <div className="cr-tip" style={{ left: x(near.t), top: 8 }}>
          <b>{hms(near.t)}</b>
          <div><strong>{fmtNum(near.v, 3)}</strong> {unit(u)}</div>
        </div>
      )}
    </div>
  );
}
