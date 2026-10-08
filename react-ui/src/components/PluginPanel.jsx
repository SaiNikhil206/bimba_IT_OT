import { ago } from "../format.js";

// Health of the BIMBA Gateway as seen from its /system/bimba-status page.
export default function PluginPanel({ status, error }) {
  const reachable = status?.reachable;
  const d = status?.delivery;
  const c = status?.collector;
  return (
    <div className="card">
      <div className="card-head">
        <h2>BIMBA Gateway</h2>
        <Status state={reachable ? (d?.bufferedBatches > 0 ? "warning" : "good") : "critical"}
                label={reachable ? (d?.bufferedBatches > 0 ? "Buffering" : "Running") : "Unreachable"} />
      </div>
      {!reachable && (
        <p className="muted">
          Gateway status page not reachable{status?.error ? `: ${status.error}` : error ? `: ${error}` : ""}.
        </p>
      )}
      {reachable && (
        <>
          <dl className="facts">
            <div><dt>Edge</dt><dd>{status.edgeId} · {status.plantId}</dd></div>
            <div><dt>Subscribed tags</dt><dd>{c?.subscribedTags ?? "–"}</dd></div>
            <div><dt>Delivering to</dt><dd>{d?.activeDestination ?? <span className="bad-text">none – buffering</span>}</dd></div>
            <div><dt>Disk buffer (main)</dt><dd>{d?.bufferedBatches ?? 0} batches · {fmtBytes(d?.bufferedBytes ?? 0)}</dd></div>
            <div><dt>Replayed</dt><dd>{(d?.replayedMessages ?? 0).toLocaleString()} msgs</dd></div>
            <div><dt>Last seq</dt><dd className="num">{(c?.lastSeq ?? 0).toLocaleString()}</dd></div>
            {(d?.routes ?? []).filter((r) => (r.kind ?? r.name) !== "main").map((r) => (
              <div key={r.name}>
                <dt>Mirror · {r.name}</dt>
                <dd>{r.activeDestination ? "delivering" : <span className="bad-text">buffering</span>} · {r.bufferedBatches} buffered</dd>
              </div>
            ))}
          </dl>
          <div className="table-wrap"><table className="table dest">
            <thead>
              <tr><th>Destination</th><th>Route · role</th><th>Transport</th><th>State</th><th className="r">Batches</th><th>Last error</th></tr>
            </thead>
            <tbody>
              {(d?.destinations ?? []).map((x) => (
                <tr key={x.name}>
                  <td><strong>{x.name}</strong><div className="muted small" title={x.endpoint}>{x.endpoint}</div></td>
                  <td>{x.route ?? "main"} · {x.role}</td>
                  <td>{x.transport.toUpperCase()}</td>
                  <td><Status state={x.state === "up" ? "good" : x.state === "down" ? "critical" : "neutral"}
                              label={x.state === "up" ? "Up" : x.state === "down" ? "Down" : "Idle"} /></td>
                  <td className="r num">{x.batches.toLocaleString()}</td>
                  <td className="muted small err" title={x.lastError ?? ""}>
                    {x.lastError ? `${x.lastError.slice(0, 60)} (${ago(x.lastFailureAt)})` : "–"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table></div>
        </>
      )}
    </div>
  );
}

export function Status({ state, label }) {
  const icon = { good: "✓", warning: "!", critical: "✕", neutral: "•" }[state] ?? "•";
  return (
    <span className={`status ${state}`}>
      <span className="status-icon" aria-hidden="true">{icon}</span>
      {label}
    </span>
  );
}

function fmtBytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
