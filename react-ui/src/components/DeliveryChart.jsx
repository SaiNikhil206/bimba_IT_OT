import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { useChartTheme, VIA_LABEL, VIA_ORDER } from "../theme.js";
import { ago, clock } from "../format.js";

// Messages stored per minute, split by which end system received them.
// Boomi = primary, BIMBA Ingest or the gateway's built-in BIMBA database = failover, so a BIMBA bar means the primary was down.
export default function DeliveryChart({ data, summary }) {
  const th = useChartTheme();
  const rows = (data?.series ?? []).map((r) => ({ ...r, label: clock(r.minute).slice(0, 5) }));
  const present = VIA_ORDER.filter((v) => rows.some((r) => r[v] > 0));
  const shown = present.length ? present : ["boomi-es", "bimba-rest"];
  const last = summary?.lastBatchByVia ?? {};
  const five = summary?.last5MinByVia ?? {};

  return (
    <div className="card">
      <div className="card-head">
        <h2>Received by end system</h2>
        <span className="muted small">messages / minute · last 30 min</span>
      </div>
      <div className="via-tiles">
        {["boomi", "bimba"].map((sys) => {
          const vias = VIA_ORDER.filter((v) => v.startsWith(sys));
          const count = vias.reduce((a, v) => a + (five[v] ?? 0), 0);
          const lastAt = vias.map((v) => last[v]).filter(Boolean).sort().pop();
          return (
            <div key={sys} className="via-tile">
              <div className="via-name">{sys === "boomi" ? "Boomi Event Streams (primary)" : "BIMBA Ingest / Gateway (failover)"}</div>
              <div className="via-count">{count.toLocaleString()}</div>
              <div className="muted small">msgs last 5 min · last batch {ago(lastAt)}</div>
            </div>
          );
        })}
      </div>
      <div className="chart" style={{ height: 190 }}>
        {rows.length === 0 ? (
          <div className="empty">No batches received yet</div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={rows} margin={{ top: 8, right: 8, left: 0, bottom: 0 }} barCategoryGap={2}>
              <CartesianGrid vertical={false} stroke={th.grid} />
              <XAxis dataKey="label" tick={{ fill: th.muted, fontSize: 11 }} tickLine={false}
                     axisLine={{ stroke: th.axis }} minTickGap={24} />
              <YAxis tick={{ fill: th.muted, fontSize: 11 }} tickLine={false} axisLine={false} width={44} />
              <Tooltip cursor={{ fill: th.grid, opacity: 0.5 }} content={<ViaTooltip th={th} />} />
              <Legend iconType="plainline" wrapperStyle={{ fontSize: 12, color: th.text2 }}
                      formatter={(v) => <span style={{ color: th.text2 }}>{VIA_LABEL[v]}</span>} />
              {shown.map((v, i) => (
                <Bar key={v} dataKey={v} name={v} stackId="via"
                     fill={th.series[VIA_ORDER.indexOf(v)]}
                     stroke={th.surface} strokeWidth={1}
                     radius={i === shown.length - 1 ? [4, 4, 0, 0] : 0}
                     isAnimationActive={false} />
              ))}
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}

function ViaTooltip({ active, payload, label, th }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="tip">
      <div className="tip-title">{label}</div>
      {payload.map((p) => (
        <div key={p.dataKey} className="tip-row">
          <span className="tip-key" style={{ background: p.color }} />
          <strong>{Number(p.value).toLocaleString()}</strong>
          <span style={{ color: th.text2 }}>{VIA_LABEL[p.dataKey]}</span>
        </div>
      ))}
    </div>
  );
}
