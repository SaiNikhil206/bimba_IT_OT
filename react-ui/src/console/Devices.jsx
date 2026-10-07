import { PROTO, ago } from "./api.js";
import { addressOf, fmt, machineList, machineSource, pathKey, pointIndex } from "./model.js";
import { Quality, TickCell, useNow } from "./ui.jsx";

export default function Devices({ live, config }) {
  useNow(2000);
  const idx = pointIndex(config?.points);
  const ids = machineList(live, config?.points);

  return (
    <>
      <p className="kicker">OT devices</p>
      <h1>Devices sending data</h1>
      <p className="lead">
        Every machine the BIMBA IT/OT Agent streams from Ignition. The values below are the latest ones stored in
        PostgreSQL and update live as new readings arrive.
      </p>
      {!live.connected && (
        <p className="note" role="status">Connecting to the live feed (/ws/live/)…</p>
      )}
      {ids.length === 0 ? (
        <div className="card empty">No machines yet. Values appear here as soon as the plugin delivers a batch.</div>
      ) : (
        <div className="sims">
          {ids.map((id) => (
            <DeviceCard key={id} id={id} m={live.machines[id]} config={config} idx={idx} />
          ))}
        </div>
      )}
    </>
  );
}

function DeviceCard({ id, m, config, idx }) {
  const src = machineSource(config?.points, id);
  const tags = Object.values(m?.tags ?? {}).sort((a, b) => a.tag.localeCompare(b.tag));
  // tags mapped in demo-tags.json that have not reported yet
  const mapped = (config?.points ?? []).filter((p) => p.machine === id && !tags.some((t) => pathKey(id, t.tag) === pathKey(p.machine, p.tag)));
  const bad = tags.filter((t) => t.quality && t.quality !== "GOOD").length;
  const status = !m
    ? { cls: "off", text: "No data yet" }
    : bad === tags.length && tags.length > 0
      ? { cls: "bad", text: `All ${bad} tags BAD` }
      : bad
        ? { cls: "warn", text: `${bad} of ${tags.length} tags BAD` }
        : { cls: "", text: `Updated ${ago(m.lastSeen)}` };
  const col = src.kind === "modbus" ? "Register" : src.kind === "opcua" ? "Node" : "Source";

  return (
    <section className="card">
      <div className="card-h">
        <div>
          <h2>{id}</h2>
          <div className="mono muted">
            {m?.plantId ?? config?.plantId} · {PROTO[src.kind] ?? PROTO.other}
            {src.device ? ` · ${src.device}` : ""}
          </div>
        </div>
        <span className={`status ${status.cls}`} title={m?.lastSeen ? `Last value ${new Date(m.lastSeen).toLocaleString()}` : ""}>
          {status.text}
        </span>
      </div>
      <div className="tbl">
        <table>
          <thead>
            <tr><th>Data point</th><th>{col}</th><th className="r">Value</th></tr>
          </thead>
          <tbody>
            {tags.map((t) => {
              const p = idx.get(pathKey(id, t.tag));
              return (
                <tr key={t.tag}>
                  <td>{t.tag}</td>
                  <td className="mono muted">{addressOf(p)}</td>
                  <TickCell flash={t.flash}>
                    {t.quality && t.quality !== "GOOD" ? <Quality q={t.quality} /> : <>{fmt(t)} <span className="muted">{t.unit ?? ""}</span></>}
                  </TickCell>
                </tr>
              );
            })}
            {mapped.map((p) => (
              <tr key={p.path}>
                <td>{p.tag}</td>
                <td className="mono muted">{addressOf(p)}</td>
                <td className="v muted">waiting</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
