import { fmtNum } from "./data.js";
import { KIND, headline, health, pipeline, runState } from "./model.js";
import { to } from "./paths.js";
import { Card, PageHeader, Stat, Status } from "./ui.jsx";

// Overview: one question, "is everything OK?". A verdict, four numbers, the machines, the data path.

export default function Overview(ctx) {
  const { machines, alarms, overall, summary, link } = ctx;
  const plant = machines.filter((m) => m.plantId !== "BOOMI-TEST");
  const online = plant.filter((m) => m.health !== "critical").length;
  const steps = pipeline(ctx);
  const tags = plant.flatMap((m) => Object.values(m.tags));
  const goodPct = tags.length ? Math.round((tags.filter((t) => t.quality === "GOOD").length / tags.length) * 100) : null;
  const delivery = steps.find((s) => s.key === "delivery");

  return (
    <>
      <PageHeader title="Overview" subtitle="Live status of the plant and its data path." />

      <section className={`op-verdict ${overall}`} aria-live="polite">
        <span className="big" aria-hidden="true">{overall === "good" ? "✓" : overall === "warning" ? "!" : "✕"}</span>
        <div>
          <h2>{overall === "good" ? "All systems normal" : `${alarms.length} ${alarms.length === 1 ? "issue needs" : "issues need"} attention`}</h2>
          {overall === "good" ? (
            <p>Every machine is reporting and every value is reaching the database.</p>
          ) : (
            <ul>
              {alarms.slice(0, 3).map((a, i) => <li key={i}><Status state={a.s}>{a.t}</Status></li>)}
              {alarms.length > 3 && <li><a href={to("alarms")} onClick={link(to("alarms"))}>and {alarms.length - 3} more →</a></li>}
            </ul>
          )}
        </div>
        {overall !== "good" && <a className="op-btn" href={to("alarms")} onClick={link(to("alarms"))}>View alarms</a>}
      </section>

      <div className="op-stats">
        <Stat label="Machines online" value={`${online}`} unit={` of ${plant.length}`} />
        <Stat label="Data rate" value={summary ? fmtNum(summary.messagesPerSecond, 1) : "–"} unit=" values/s" />
        <Stat label="Readings OK" value={goodPct ?? "–"} unit="%" />
        <Stat label="Delivery" value={{ good: "Healthy", warning: "Degraded", critical: "Stopped" }[delivery.state] ?? "–"}
              state={delivery.state} note={delivery.text} />
      </div>

      <Card title="Machines" subtitle="Select a machine to see its readings, trends and messages."
            action={<a className="op-link" href={to("machines")} onClick={link(to("machines"))}>All machines →</a>}>
        <div className="op-mgrid">
          {plant.map((m) => {
            const h = headline(m);
            const hs = health(m);
            const rs = runState(m);
            return (
              <a key={m.machineId} className="op-mcard" href={to("machines", m.machineId)} onClick={link(to("machines", m.machineId))}>
                <div className="t">
                  <span className="k">{KIND[m.kind]}</span>
                  <Status state={hs.state}>{hs.text}</Status>
                </div>
                <b className="nm">{m.machineId}</b>
                <div className="hl">
                  {h ? <><span className="v">{h.value}<small>{h.unit}</small></span><span className="l">{h.label}</span></> : <span className="l">No readings</span>}
                </div>
                {rs && m.health !== "critical" && <div className="rs"><Status state={rs.state}>{rs.text}</Status></div>}
              </a>
            );
          })}
          {plant.length === 0 && <p className="op-empty">Waiting for the first values from the gateway…</p>}
        </div>
      </Card>

      <Card title="Data path" subtitle="From the field devices to the database."
            action={<a className="op-link" href={to("flow")} onClick={link(to("flow"))}>Data flow →</a>}>
        <Steps steps={steps} />
      </Card>
    </>
  );
}

export function Steps({ steps }) {
  return (
    <ol className="op-steps">
      {steps.map((s) => (
        <li key={s.key} className={s.state}>
          <span className="n" aria-hidden="true" />
          <div>
            <span className="k">{s.title}</span>
            <b>{s.text}</b>
            <span className="d">{s.note}</span>
          </div>
          <Status state={s.state} />
        </li>
      ))}
    </ol>
  );
}
