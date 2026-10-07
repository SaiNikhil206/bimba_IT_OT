import { ago, fmtValue } from "../format.js";

// One card per machine (Ignition folder), one row per tag with value, unit and quality.
export default function MachineGrid({ machines, selected, onSelect }) {
  if (machines.length === 0) {
    return <div className="card empty">Waiting for the first data from the plugin…</div>;
  }
  return (
    <div className="machines">
      {machines.map((m) => {
        const tags = Object.values(m.tags).sort((a, b) => a.tag.localeCompare(b.tag));
        const bad = tags.filter((t) => t.quality !== "GOOD").length;
        return (
          <div key={m.machineId} className="card machine">
            <div className="card-head">
              <h3>{m.machineId}</h3>
              <span className={`status ${bad === 0 ? "good" : bad === tags.length ? "critical" : "warning"}`}>
                <span className="status-icon" aria-hidden="true">{bad === 0 ? "✓" : "!"}</span>
                {bad === 0 ? "All good" : `${bad} bad quality`}
              </span>
            </div>
            <ul className="tags">
              {tags.map((t) => {
                const numeric = t.num !== null && t.num !== undefined;
                const isSel = selected?.tagId === t.tagId;
                const fresh = t.flash && Date.now() - t.flash < 1200;
                return (
                  <li key={t.tag}>
                    <button
                      className={`tag ${isSel ? "sel" : ""} ${t.quality !== "GOOD" ? "badq" : ""}`}
                      disabled={!numeric}
                      onClick={() => onSelect({ tagId: t.tagId, machineId: m.machineId, tag: t.tag, unit: t.unit })}
                      title={numeric ? "Show trend" : t.quality !== "GOOD" ? "Bad quality" : "Not numeric"}
                    >
                      <span className="tag-name">{t.tag}</span>
                      <span className={`tag-val ${fresh ? "fresh" : ""}`}>
                        {t.quality === "GOOD" ? fmtValue(t) : "—"}
                        {t.quality === "GOOD" && t.unit ? <span className="unit"> {t.unit}</span> : null}
                      </span>
                      <span className={`q ${t.quality === "GOOD" ? "good" : t.quality === "UNCERTAIN" ? "warning" : "critical"}`}>
                        {t.quality ?? "?"}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
            <div className="muted small">updated {ago(m.lastSeen && tags.map((t) => t.time).filter(Boolean).sort().pop())}</div>
          </div>
        );
      })}
    </div>
  );
}
