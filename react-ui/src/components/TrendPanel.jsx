import { useEffect, useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { useChartTheme } from "../theme.js";
import { clock } from "../format.js";

const WINDOWS = [5, 15, 60];

// History from /api/tags/<id>/history/ plus live points appended from the WebSocket.
export default function TrendPanel({ selected, onValues }) {
  const th = useChartTheme();
  const [minutes, setMinutes] = useState(15);
  const [points, setPoints] = useState([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!selected) return;
    let alive = true;
    setLoading(true);
    fetch(`/api/tags/${selected.tagId}/history/?minutes=${minutes}`)
      .then((r) => r.json())
      .then((d) => {
        if (!alive) return;
        setPoints(d.points.filter((p) => p.num !== null).map((p) => ({ t: new Date(p.time).getTime(), v: p.num })));
        setLoading(false);
      })
      .catch(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [selected?.tagId, minutes]);

  useEffect(() => {
    if (!selected) return;
    return onValues((items) => {
      const mine = items.filter((i) => i.tagId === selected.tagId && i.num !== null);
      if (!mine.length) return;
      setPoints((prev) => {
        const cutoff = Date.now() - minutes * 60_000;
        const next = [...prev, ...mine.map((i) => ({ t: new Date(i.time).getTime(), v: i.num }))];
        return next.filter((p) => p.t >= cutoff).slice(-3000);
      });
    });
  }, [selected?.tagId, minutes, onValues]);

  const last = points[points.length - 1];
  return (
    <div className="card">
      <div className="card-head">
        <h2>{selected ? `${selected.machineId} · ${selected.tag}` : "Trend"}</h2>
        <div className="seg" role="group" aria-label="Time window">
          {WINDOWS.map((w) => (
            <button key={w} className={w === minutes ? "on" : ""} onClick={() => setMinutes(w)}>{w}m</button>
          ))}
        </div>
      </div>
      <div className="trend-value">
        {last ? last.v.toLocaleString(undefined, { maximumFractionDigits: 2 }) : "–"}
        <span className="unit"> {selected?.unit ?? ""}</span>
      </div>
      <div className="chart" style={{ height: 240 }}>
        {!selected ? (
          <div className="empty">Pick a numeric tag in a machine card</div>
        ) : points.length === 0 ? (
          <div className="empty">{loading ? "Loading…" : "No numeric history in this window"}</div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={points} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
              <CartesianGrid vertical={false} stroke={th.grid} />
              <XAxis dataKey="t" type="number" scale="time" domain={["dataMin", "dataMax"]}
                     tickFormatter={(t) => clock(t).slice(0, 5)} tick={{ fill: th.muted, fontSize: 11 }}
                     tickLine={false} axisLine={{ stroke: th.axis }} minTickGap={40} />
              <YAxis domain={["auto", "auto"]} tick={{ fill: th.muted, fontSize: 11 }} tickLine={false}
                     axisLine={false} width={52}
                     tickFormatter={(v) => v.toLocaleString(undefined, { maximumFractionDigits: 1 })} />
              <Tooltip
                cursor={{ stroke: th.muted, strokeWidth: 1 }}
                content={({ active, payload }) =>
                  active && payload?.length ? (
                    <div className="tip">
                      <div className="tip-title">{clock(payload[0].payload.t)}</div>
                      <div className="tip-row">
                        <span className="tip-key" style={{ background: th.series[0] }} />
                        <strong>{Number(payload[0].value).toLocaleString(undefined, { maximumFractionDigits: 3 })}</strong>
                        <span style={{ color: th.text2 }}>{selected.unit ?? ""}</span>
                      </div>
                    </div>
                  ) : null
                }
              />
              <Line type="monotone" dataKey="v" stroke={th.series[0]} strokeWidth={2} dot={false}
                    activeDot={{ r: 4, stroke: th.surface, strokeWidth: 2 }} isAnimationActive={false} />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}
