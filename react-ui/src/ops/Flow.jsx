import { fmtNum, since } from "./data.js";
import { VIA, destinations, pipeline, ago } from "./model.js";
import { Steps } from "./Overview.jsx";
import { BarChart, Card, Empty, Legend, PageHeader, Stat, Status } from "./ui.jsx";

// Data flow: one question, "is data getting through?".

export default function Flow(ctx) {
  const { plugin, summary, deliveries, live } = ctx;
  const dests = destinations(plugin);
  const buffered = plugin?.delivery?.bufferedBatches ?? 0;

  // messages stored per minute, last 60 minutes, stacked by the path that stored them
  const byMin = new Map((deliveries?.series ?? []).map((r) => [Math.floor(Date.parse(r.minute) / 60000), r]));
  const nowMin = Math.floor(Date.now() / 60000);
  const rows = Array.from({ length: 60 }, (_, i) => {
    const k = nowMin - 59 + i;
    return { t: k * 60000, ...(byMin.get(k) ?? {}) };
  });
  const keys = Object.keys(VIA).filter((k) => k === "boomi-es" || k === "bimba-rest" || rows.some((r) => r[k] > 0));
  const series = keys.map((k) => ({ key: k, ...VIA[k] }));

  const batches = live.batches.length
    ? live.batches.map((b) => ({ key: b.id, at: b.at, via: b.via, n: b.received, ins: b.inserted, dup: b.duplicates, a: b.firstSeq, z: b.lastSeq }))
    : (deliveries?.recent ?? []).map((b) => ({ key: b.id, at: b.receivedAt, via: b.via, n: b.messages, ins: b.inserted, dup: b.duplicates, a: b.firstSeq, z: b.lastSeq }));

  return (
    <>
      <PageHeader title="Data flow" subtitle="How readings travel from the field devices to the database." />
      <Card><Steps steps={pipeline(ctx)} /></Card>

      <div className="op-stats">
        <Stat label="Data rate" value={summary ? fmtNum(summary.messagesPerSecond, 1) : "–"} unit=" values/s" />
        <Stat label="Readings stored" value={summary ? summary.measurements.toLocaleString() : "–"} />
        <Stat label="Gateway running for" value={plugin?.reachable ? since(plugin.startedAt) : "–"} />
        <Stat label="Waiting on disk" value={buffered} unit={buffered === 1 ? " batch" : " batches"} state={buffered ? "warning" : "good"} note={buffered ? "Delivering the backlog" : "Nothing waiting"} />
      </div>

      <Card title="Destinations" subtitle="Primary with automatic failover, plus independent copies." flush>
        {dests.length === 0 ? <Empty>Gateway status not available.</Empty> : (
          <table className="op-table">
            <thead><tr><th>Destination</th><th>Role</th><th>Status</th><th className="r">Delivered</th><th className="r">Last success</th></tr></thead>
            <tbody>
              {dests.map((d) => (
                <tr key={d.name}>
                  <td><b>{d.label}</b><div className="sub mono">{d.name}</div></td>
                  <td>{d.role === "mirror" ? "Copy" : d.role === "primary" ? "Primary" : "Failover"}</td>
                  <td><Status state={d.state}>{d.text}</Status></td>
                  <td className="r num">{(d.messages ?? 0).toLocaleString()}</td>
                  <td className="r sub">{d.lastSuccessAt ? ago(d.lastSuccessAt) : d.lastProbeAt ? `checked ${ago(d.lastProbeAt)}` : "never"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Readings stored per minute" subtitle="Last 60 minutes, by the path that stored them." action={<Legend series={series} />}>
        <BarChart rows={rows} series={series} height={220} />
      </Card>

      <Card title="Recent batches" subtitle="Each batch the database received, newest first." flush>
        {batches.length === 0 ? <Empty>No batches yet.</Empty> : (
          <div className="op-scroll short">
            <table className="op-table">
              <thead><tr><th>Time</th><th>Stored via</th><th className="r">Readings</th><th className="r">New</th><th className="r">Duplicates</th><th className="r">Sequence</th></tr></thead>
              <tbody>
                {batches.slice(0, 12).map((b) => (
                  <tr key={b.key}>
                    <td className="mono">{new Date(b.at).toLocaleTimeString()}</td>
                    <td>{VIA[b.via]?.label ?? b.via}</td>
                    <td className="r num">{b.n}</td>
                    <td className="r num">{b.ins}</td>
                    <td className="r num">{b.dup || "–"}</td>
                    <td className="r mono sub">{b.a != null ? `${b.a}–${b.z}` : "–"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
