import { clock } from "../format.js";
import { VIA_LABEL } from "../theme.js";

// Batches as stored by an end system. Live rows come from the WebSocket; before the
// first live batch arrives we show the recent history from /api/deliveries/.
export default function BatchTable({ live, recent }) {
  const rows = live.length
    ? live.map((b) => ({ key: b.id, at: b.at, via: b.via, messages: b.received, inserted: b.inserted,
                         duplicates: b.duplicates, firstSeq: b.firstSeq, lastSeq: b.lastSeq }))
    : (recent ?? []).map((b) => ({ key: b.id, at: b.receivedAt, via: b.via, messages: b.messages,
                                   inserted: b.inserted, duplicates: b.duplicates, firstSeq: b.firstSeq,
                                   lastSeq: b.lastSeq }));
  return (
    <div className="card">
      <div className="card-head">
        <h2>Incoming batches</h2>
        <span className="muted small">{live.length ? "live" : "recent"}</span>
      </div>
      <div className="scroll">
        <table className="table">
          <thead>
            <tr><th>Time</th><th>Received via</th><th className="r">Msgs</th><th className="r">New</th><th className="r">Dup</th><th className="r">Seq</th></tr>
          </thead>
          <tbody>
            {rows.slice(0, 25).map((r) => (
              <tr key={r.key}>
                <td className="num">{clock(r.at)}</td>
                <td><span className={`via ${r.via?.split("-")[0]}`}>{VIA_LABEL[r.via] ?? r.via}</span></td>
                <td className="r num">{r.messages}</td>
                <td className="r num">{r.inserted}</td>
                <td className={`r num ${r.duplicates ? "warn-text" : ""}`}>{r.duplicates}</td>
                <td className="r num muted">{r.firstSeq != null ? `${r.firstSeq}–${r.lastSeq}` : "–"}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={6} className="muted">No batches yet</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
