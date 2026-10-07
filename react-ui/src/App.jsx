import { useEffect, useMemo, useState } from "react";
import { usePoll, useLive } from "./useLive.js";
import PluginPanel from "./components/PluginPanel.jsx";
import DeliveryChart from "./components/DeliveryChart.jsx";
import MachineGrid from "./components/MachineGrid.jsx";
import TrendPanel from "./components/TrendPanel.jsx";
import BatchTable from "./components/BatchTable.jsx";
import "./styles.css";

export default function App() {
  const live = useLive();
  const summary = usePoll("/api/summary/", 5000);
  const plugin = usePoll("/api/plugin-status/", 4000);
  const deliveries = usePoll("/api/deliveries/?minutes=30", 10000);
  const [selected, setSelected] = useState(null);

  const machines = useMemo(
    () => Object.values(live.machines).sort((a, b) => a.machineId.localeCompare(b.machineId)),
    [live.machines]
  );

  // default the trend to the first numeric tag once data arrives
  useEffect(() => {
    if (selected || machines.length === 0) return;
    for (const m of machines) {
      const t = Object.values(m.tags).find((x) => x.num != null && x.text == null && x.quality === "GOOD");
      if (t) {
        setSelected({ tagId: t.tagId, machineId: m.machineId, tag: t.tag, unit: t.unit });
        return;
      }
    }
  }, [machines, selected]);

  const s = summary.data;
  return (
    <div className="page">
      <header className="topbar">
        <div className="brand">
          <span className="logo" aria-hidden="true" />
          <div>
            <h1>BIMBA live UI</h1>
            <p className="sub">Ignition SCADA → BIMBA IT/OT Agent → Boomi Event Streams / BIMBA Ingest → shared PostgreSQL</p>
          </div>
        </div>
        <div className="kpis">
          <Kpi label="Machines" value={s?.machines ?? "–"} />
          <Kpi label="Tags" value={s?.tags ?? "–"} />
          <Kpi label="Msgs / s" value={s ? s.messagesPerSecond.toFixed(1) : "–"} />
          <Kpi label="Stored rows" value={s ? s.measurements.toLocaleString() : "–"} />
          <a className="console-link" href="/console">OT/IT Bridge Console</a>
          <span className={`pill ${live.connected ? "ok" : "bad"}`} role="status">
            <span className="dot" aria-hidden="true" />
            {live.connected ? "Live" : "Reconnecting…"}
          </span>
        </div>
      </header>

      <section className="row two">
        <PluginPanel status={plugin.data} error={plugin.error} />
        <DeliveryChart data={deliveries.data} summary={s} />
      </section>

      <section className="row">
        <MachineGrid machines={machines} selected={selected} onSelect={setSelected} />
      </section>

      <section className="row two">
        <TrendPanel selected={selected} onValues={live.onValues} />
        <BatchTable live={live.batches} recent={deliveries.data?.recent} />
      </section>

      <footer className="foot">
        Data is written by <code>bimba_ingest()</code> in PostgreSQL. This page updates the same way whether
        Boomi or BIMBA Ingest stored it (LISTEN/NOTIFY → BIMBA REST + WS).
      </footer>
    </div>
  );
}

function Kpi({ label, value }) {
  return (
    <div className="kpi">
      <div className="kpi-value">{value}</div>
      <div className="kpi-label">{label}</div>
    </div>
  );
}
