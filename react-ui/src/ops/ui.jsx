import { useState } from "react";
import { fmtNum, hms, hhmm, specOf, unit, useWidth } from "./data.js";

// BIMBA Operations UI kit. Every page is built from these few pieces so the app looks the same everywhere.

export const STATE_LABEL = { good: "OK", warning: "Warning", critical: "Critical", neutral: "Idle" };
const ICON = { good: "✓", warning: "!", critical: "✕", neutral: "•" };

export function PageHeader({ title, subtitle, back, actions }) {
  return (
    <header className="op-ph">
      <div>
        {back}
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {actions && <div className="op-ph-a">{actions}</div>}
    </header>
  );
}

export function Card({ title, subtitle, action, children, flush, className = "" }) {
  return (
    <section className={`op-card${flush ? " flush" : ""} ${className}`}>
      {(title || action) && (
        <div className="op-card-h">
          <div>
            {title && <h2>{title}</h2>}
            {subtitle && <p>{subtitle}</p>}
          </div>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

/** Status: icon + label, never color alone. */
export function Status({ state = "neutral", children }) {
  return (
    <span className={`op-status ${state}`}>
      <i aria-hidden="true">{ICON[state]}</i>
      {children ?? STATE_LABEL[state]}
    </span>
  );
}

export function Stat({ label, value, unit: u, note, state }) {
  return (
    <div className="op-stat">
      <span className="l">{label}</span>
      <span className="v">
        {value}
        {u && <small>{u}</small>}
      </span>
      {(note || state) && <span className="n">{state && <Status state={state}>{note}</Status>}{!state && note}</span>}
    </div>
  );
}

export function Tabs({ items, value, hrefFor, link }) {
  return (
    <nav className="op-tabs" aria-label="Sections">
      {items.map((t) => (
        <a key={t.key} href={hrefFor(t.key)} onClick={link(hrefFor(t.key))} className={t.key === value ? "on" : ""}
           aria-current={t.key === value ? "page" : undefined}>
          {t.label}
          {t.count ? <span className="c">{t.count}</span> : null}
        </a>
      ))}
    </nav>
  );
}

export function Seg({ items, value, onChange, label }) {
  return (
    <div className="op-seg" role="group" aria-label={label}>
      {items.map(([v, l]) => (
        <button key={v} type="button" className={v === value ? "on" : ""} aria-pressed={v === value} onClick={() => onChange(v)}>{l}</button>
      ))}
    </div>
  );
}

export function Empty({ children }) {
  return <p className="op-empty">{children}</p>;
}

// ---------------------------------------------------------------- charts

function niceStep(raw) {
  const p = 10 ** Math.floor(Math.log10(raw || 1));
  const n = raw / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * p;
}

/** One series over time, one y-axis, crosshair tooltip. Values are sent on change, so the last one holds until t1. */
export function LineChart({ points, tag, u, t0, t1, height = 240, current }) {
  const [box, W] = useWidth(640);
  const [hx, setHx] = useState(null);
  const H = height;
  const P = { l: 48, r: 16, t: 12, b: 26 };
  const s = specOf(tag ?? "");
  const pts = (points ?? []).filter((p) => p.v != null && p.t >= t0);
  if (pts.length && current != null) pts.push({ t: t1, v: current });
  if (pts.length < 2) return <div ref={box} className="op-chart empty" style={{ height: H }}>No values in this time window yet.</div>;
  let lo = Infinity;
  let hi = -Infinity;
  for (const p of pts) { lo = Math.min(lo, p.v); hi = Math.max(hi, p.v); }
  const pad = (hi - lo) * 0.15 || Math.abs(hi) * 0.05 || 1;
  lo -= pad;
  hi += pad;
  const step = niceStep((hi - lo) / 4);
  const ticks = [];
  for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) ticks.push(v);
  const digits = step >= 1 ? 0 : Math.min(3, Math.ceil(-Math.log10(step)));
  const x = (t) => P.l + ((t - t0) / (t1 - t0)) * (W - P.l - P.r);
  const y = (v) => P.t + (1 - (v - lo) / (hi - lo)) * (H - P.t - P.b);
  const d = pts.map((p, i) => `${i ? "L" : "M"}${x(p.t).toFixed(1)} ${y(p.v).toFixed(1)}`).join("");
  const area = `${d}L${x(pts[pts.length - 1].t)} ${H - P.b}L${x(pts[0].t)} ${H - P.b}Z`;
  const xt = Array.from({ length: 5 }, (_, i) => t0 + ((t1 - t0) * i) / 4);
  let near = null;
  if (hx != null) {
    const t = t0 + ((hx - P.l) / (W - P.l - P.r)) * (t1 - t0);
    near = pts.reduce((b, p) => (Math.abs(p.t - t) < Math.abs(b.t - t) ? p : b), pts[0]);
  }
  const move = (e) => {
    const px = e.clientX - e.currentTarget.getBoundingClientRect().left;
    setHx(px < P.l || px > W - P.r ? null : px);
  };
  const inside = (v) => v != null && v > lo && v < hi;
  return (
    <div ref={box} className="op-chart" style={{ height: H }}>
      <svg width={W} height={H} onMouseMove={move} onMouseLeave={() => setHx(null)} role="img" aria-label={`${s.label} over time`}>
        {ticks.map((v) => (
          <g key={v}>
            <line x1={P.l} x2={W - P.r} y1={y(v)} y2={y(v)} className="grid" />
            <text x={P.l - 8} y={y(v) + 4} className="ax r">{fmtNum(v, digits)}</text>
          </g>
        ))}
        {xt.map((t, i) => <text key={i} x={x(t)} y={H - 6} className={`ax ${i === 0 ? "l" : i === 4 ? "e" : "c"}`}>{hhmm(t)}</text>)}
        {inside(s.hi) && <><line x1={P.l} x2={W - P.r} y1={y(s.hi)} y2={y(s.hi)} className="limit" /><text x={W - P.r} y={y(s.hi) - 4} className="ax e lim">high limit {s.hi}</text></>}
        {inside(s.lo) && <><line x1={P.l} x2={W - P.r} y1={y(s.lo)} y2={y(s.lo)} className="limit" /><text x={W - P.r} y={y(s.lo) - 4} className="ax e lim">low limit {s.lo}</text></>}
        <path d={area} className="area" />
        <path d={d} className="line" />
        {near && (
          <>
            <line x1={x(near.t)} x2={x(near.t)} y1={P.t} y2={H - P.b} className="cross" />
            <circle cx={x(near.t)} cy={y(near.v)} r="4.5" className="dot" />
          </>
        )}
      </svg>
      {near && (
        <div className="op-tip" style={{ left: Math.min(Math.max(x(near.t), 70), W - 70) }}>
          <b>{fmtNum(near.v, 3)} {unit(u)}</b>
          <span>{hms(near.t)}</span>
        </div>
      )}
    </div>
  );
}

/** Stacked bars per minute (one axis). series: [{key, label, cls}], rows: [{t, [key]: n}] */
export function BarChart({ rows, series, height = 200 }) {
  const [box, W] = useWidth(640);
  const [hi, setHi] = useState(null);
  const H = height;
  const P = { l: 44, r: 8, t: 10, b: 24 };
  if (!rows.length) return <div ref={box} className="op-chart empty" style={{ height: H }}>No deliveries in this window.</div>;
  const tot = (r) => series.reduce((a, s) => a + (r[s.key] ?? 0), 0);
  const max = Math.max(1, ...rows.map(tot));
  const step = niceStep(max / 4);
  const top = Math.ceil(max / step) * step;
  const bw = (W - P.l - P.r) / rows.length;
  const y = (v) => P.t + (1 - v / top) * (H - P.t - P.b);
  const ticks = [];
  for (let v = 0; v <= top; v += step) ticks.push(v);
  return (
    <div ref={box} className="op-chart" style={{ height: H }} onMouseLeave={() => setHi(null)}>
      <svg width={W} height={H} role="img" aria-label="Messages stored per minute">
        {ticks.map((v) => (
          <g key={v}>
            <line x1={P.l} x2={W - P.r} y1={y(v)} y2={y(v)} className="grid" />
            <text x={P.l - 8} y={y(v) + 4} className="ax r">{v.toLocaleString()}</text>
          </g>
        ))}
        {rows.map((r, i) => {
          let acc = 0;
          const x0 = P.l + i * bw + 1;
          return (
            <g key={r.t} onMouseEnter={() => setHi(i)}>
              <rect x={P.l + i * bw} y={P.t} width={bw} height={H - P.t - P.b} fill="transparent" />
              {series.map((s) => {
                const v = r[s.key] ?? 0;
                if (!v) return null;
                const y1 = y(acc + v);
                const y0 = y(acc);
                acc += v;
                return <rect key={s.key} x={x0} y={y1} width={Math.max(1, bw - 2)} height={Math.max(0, y0 - y1 - 1)} rx="1.5" className={`bar ${s.cls}${hi === i ? " on" : ""}`} />;
              })}
            </g>
          );
        })}
        {[0, Math.floor(rows.length / 2), rows.length - 1].map((i) => (
          <text key={i} x={P.l + i * bw + bw / 2} y={H - 6} className={`ax ${i === 0 ? "l" : i === rows.length - 1 ? "e" : "c"}`}>
            {i === rows.length - 1 ? "now" : hhmm(rows[i].t)}
          </text>
        ))}
      </svg>
      {hi != null && (
        <div className="op-tip" style={{ left: Math.min(Math.max(P.l + hi * bw + bw / 2, 90), W - 90) }}>
          <span>{hhmm(rows[hi].t)}</span>
          {series.filter((s) => rows[hi][s.key]).map((s) => (
            <b key={s.key}><i className={`key ${s.cls}`} aria-hidden="true" />{rows[hi][s.key].toLocaleString()} <small>{s.label}</small></b>
          ))}
          {!tot(rows[hi]) && <b>No messages</b>}
        </div>
      )}
    </div>
  );
}

export function Legend({ series }) {
  return (
    <div className="op-legend">
      {series.map((s) => <span key={s.key}><i className={`key ${s.cls}`} aria-hidden="true" />{s.label}</span>)}
    </div>
  );
}
