import { fmtNum, hms, onOf, segments, specOf, unit, useWidth } from "./lib.js";

// Charts for the machine dashboard. Each chart draws at its real pixel width (useWidth).

const PAD_L = 132;
const PAD_R = 12;

function timeTicks(t0, t1, n) {
  return Array.from({ length: n }, (_, i) => t0 + ((t1 - t0) * i) / (n - 1));
}

/**
 * State timeline: one lane per on/off or text tag. Values are sent on change, so each value holds
 * until the next one. Unknown time (before the first change in the window) is drawn hatched.
 */
export function Timeline({ lanes, t0, t1, hoverT, setHoverT }) {
  const [ref, W] = useWidth(900);
  const LH = 26;
  const H = lanes.length * (LH + 8) + 22;
  const x = (t) => PAD_L + ((t - t0) / (t1 - t0)) * (W - PAD_L - PAD_R);
  const move = (e) => {
    const r = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - r.left;
    setHoverT(px < PAD_L || px > W - PAD_R ? null : t0 + ((px - PAD_L) / (W - PAD_L - PAD_R)) * (t1 - t0));
  };
  return (
    <div ref={ref} className="mx-timeline" onMouseMove={move} onMouseLeave={() => setHoverT(null)}>
      <svg width={W} height={H} role="img" aria-label="State timeline">
        <defs>
          <pattern id="mx-unknown" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <line x1="0" y1="0" x2="0" y2="6" className="hatch" />
          </pattern>
        </defs>
        {lanes.map((ln, i) => {
          const y = i * (LH + 8) + 4;
          const valueOf = ln.text ? (p) => p.s ?? null : onOf;
          const segs = segments(ln.points, t0, t1, ln.current, valueOf);
          const cats = ln.text ? [...new Set(segs.map((s) => s.v).filter((v) => v != null))] : [];
          return (
            <g key={ln.tag}>
              <text x="0" y={y + LH / 2 + 4} className="lane-lab">{specOf(ln.tag).label}</text>
              <rect x={PAD_L} y={y} width={W - PAD_L - PAD_R} height={LH} rx="6" className="lane-bg" />
              {segs.map((s, k) => {
                const cls = s.v == null || s.v === undefined ? "unk"
                  : ln.text ? `c${Math.min(2, cats.indexOf(s.v))}`
                  : s.v ? (ln.alarm ? "alarm" : "on") : "off";
                const w = Math.max(1, x(s.b) - x(s.a) - 2);
                return (
                  <g key={k}>
                    <rect x={x(s.a) + 1} y={y + 2} width={w} height={LH - 4} rx="4" className={`seg ${cls}`}
                          fill={cls === "unk" ? "url(#mx-unknown)" : undefined} />
                    {(() => {
                      const full = s.v == null ? "no change recorded" : ln.text ? String(s.v) : s.v ? (ln.alarm ? "TRIPPED" : "ON") : "OFF";
                      const label = full.length * 6.6 + 14 <= w ? full : s.v == null && w > 26 ? "?" : null;
                      return label && <text x={x(s.a) + 7} y={y + LH / 2 + 4} className={`seg-t ${cls}`}>{label}</text>;
                    })()}
                  </g>
                );
              })}
            </g>
          );
        })}
        {timeTicks(t0, t1, 6).map((t, i) => (
          <text key={i} x={x(t)} y={H - 4} className={`ax ${i === 0 ? "l" : i === 5 ? "e" : "c"}`}>{hms(t).slice(0, 5)}</text>
        ))}
        {hoverT != null && <line x1={x(hoverT)} x2={x(hoverT)} y1="0" y2={H - 16} className="cross" />}
      </svg>
      {hoverT != null && (
        <div className="cr-tip" style={{ left: x(hoverT), top: 0, transform: "translate(-50%, -105%)" }}>
          <b>{hms(hoverT)}</b>
          {lanes.map((ln) => {
            const valueOf = ln.text ? (p) => p.s ?? null : onOf;
            const s = segments(ln.points, t0, t1, ln.current, valueOf).find((g) => g.a <= hoverT && g.b >= hoverT);
            const v = s?.v;
            return <div key={ln.tag}>{specOf(ln.tag).label}: <strong>{v == null ? "?" : ln.text ? v : v ? (ln.alarm ? "TRIPPED" : "ON") : "OFF"}</strong></div>;
          })}
        </div>
      )}
    </div>
  );
}

/** One small-multiple trend (one tag, one y-axis). All trends share t0/t1 and the hover time. */
export function Trend({ tag, u, points, t0, t1, hoverT, setHoverT, current }) {
  const [ref, W] = useWidth(500);
  const H = 132;
  const P = { l: 46, r: 10, t: 10, b: 20 };
  const s = specOf(tag);
  const pts = (points ?? []).filter((p) => p.v != null && p.t >= t0);
  // values are sent on change: the latest value still holds now
  if (pts.length && current?.num != null && current.quality === "GOOD") pts.push({ t: t1, v: current.num });
  let lo = Infinity;
  let hi = -Infinity;
  for (const p of pts) { lo = Math.min(lo, p.v); hi = Math.max(hi, p.v); }
  if (!pts.length) { lo = s.min ?? 0; hi = s.max ?? 1; }
  const pad = (hi - lo) * 0.12 || Math.abs(hi) * 0.05 || 1;
  lo -= pad;
  hi += pad;
  const x = (t) => P.l + ((t - t0) / (t1 - t0)) * (W - P.l - P.r);
  const y = (v) => P.t + (1 - (v - lo) / (hi - lo)) * (H - P.t - P.b);
  const d = pts.map((p, i) => `${i ? "L" : "M"}${x(p.t).toFixed(1)} ${y(p.v).toFixed(1)}`).join("");
  const area = pts.length > 1 ? `${d}L${x(pts[pts.length - 1].t)} ${H - P.b}L${x(pts[0].t)} ${H - P.b}Z` : "";
  const mid = (lo + hi) / 2;
  const near = hoverT != null && pts.length
    ? pts.reduce((b, p) => (Math.abs(p.t - hoverT) < Math.abs(b.t - hoverT) ? p : b), pts[0]) : null;
  const vals = pts.map((p) => p.v);
  const avg = vals.length ? vals.reduce((a, v) => a + v, 0) / vals.length : null;
  const move = (e) => {
    const r = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - r.left;
    setHoverT(px < P.l || px > W - P.r ? null : t0 + ((px - P.l) / (W - P.l - P.r)) * (t1 - t0));
  };
  const digits = Math.abs(hi - lo) < 2 ? 2 : Math.abs(hi - lo) < 20 ? 1 : 0;
  return (
    <div className="mx-trend">
      <div className="mx-trend-h">
        <b>{s.label}</b>
        <span className="now">{current?.quality === "GOOD" && current.num != null ? fmtNum(current.num) : "–"}<small>{unit(u)}</small></span>
        <span className="muted">min {vals.length ? fmtNum(Math.min(...vals)) : "–"} · avg {avg != null ? fmtNum(avg) : "–"} · max {vals.length ? fmtNum(Math.max(...vals)) : "–"}</span>
      </div>
      <div ref={ref} className="mx-trend-c" onMouseMove={move} onMouseLeave={() => setHoverT(null)}>
        <svg width={W} height={H} role="img" aria-label={`${s.label} trend`}>
          {[hi - pad, mid, lo + pad].map((v, i) => (
            <g key={i}>
              <line x1={P.l} x2={W - P.r} y1={y(v)} y2={y(v)} className="grid" />
              <text x={P.l - 6} y={y(v) + 3} className="ax r">{fmtNum(v, digits)}</text>
            </g>
          ))}
          {timeTicks(t0, t1, 4).map((t, i) => (
            <text key={i} x={x(t)} y={H - 5} className={`ax ${i === 0 ? "l" : i === 3 ? "e" : "c"}`}>{hms(t).slice(0, 5)}</text>
          ))}
          {s.hi != null && s.hi > lo && s.hi < hi && <line x1={P.l} x2={W - P.r} y1={y(s.hi)} y2={y(s.hi)} className="limit hi" />}
          {s.lo != null && s.lo > lo && s.lo < hi && <line x1={P.l} x2={W - P.r} y1={y(s.lo)} y2={y(s.lo)} className="limit lo" />}
          {area && <path d={area} className="area" />}
          {d && <path d={d} className="line" />}
          {pts.length > 0 && <circle cx={x(pts[pts.length - 1].t)} cy={y(pts[pts.length - 1].v)} r="3.5" className="beam" />}
          {!pts.length && <text x={W / 2} y={H / 2} className="empty-t">no values in this window</text>}
          {hoverT != null && <line x1={x(hoverT)} x2={x(hoverT)} y1={P.t} y2={H - P.b} className="cross" />}
          {near && <circle cx={x(near.t)} cy={y(near.v)} r="4.5" className="probe" />}
        </svg>
        {near && (
          <div className="cr-tip" style={{ left: x(near.t), top: 4, transform: "translate(-50%, 0)" }}>
            <b>{hms(near.t)}</b> <strong>{fmtNum(near.v, 3)}</strong> {unit(u)}
          </div>
        )}
      </div>
    </div>
  );
}
