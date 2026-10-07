import { useState } from "react";
import { to } from "./paths.js";
import { Card, PageHeader, Seg, Status } from "./ui.jsx";

// Alarms: one question, "what needs attention?".

export default function Alarms({ alarms, link }) {
  const [f, setF] = useState("all");
  const crit = alarms.filter((a) => a.s === "critical").length;
  const shown = alarms.filter((a) => f === "all" || a.s === f);
  return (
    <>
      <PageHeader title="Alarms" subtitle="Active conditions on the machines and the data path. They clear automatically when resolved."
                  actions={alarms.length > 0 && (
                    <Seg items={[["all", `All (${alarms.length})`], ["critical", `Critical (${crit})`], ["warning", `Warning (${alarms.length - crit})`]]}
                         value={f} onChange={setF} label="Filter" />
                  )} />
      {alarms.length === 0 ? (
        <section className="op-verdict good">
          <span className="big" aria-hidden="true">✓</span>
          <div><h2>No active alarms</h2><p>Every route is delivering and every reading is inside its limits.</p></div>
        </section>
      ) : (
        <Card flush>
          <ul className="op-alarms">
            {shown.map((a, i) => (
              <li key={i}>
                <Status state={a.s}>{a.s === "critical" ? "Critical" : "Warning"}</Status>
                <div>
                  <b>{a.t}</b>
                  <span>{a.d}</span>
                </div>
                {a.m && <a className="op-link" href={to("machines", a.m)} onClick={link(to("machines", a.m))}>Open {a.m} →</a>}
              </li>
            ))}
          </ul>
        </Card>
      )}
    </>
  );
}
