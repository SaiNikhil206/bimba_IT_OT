import { fmtNum, good, isNum, onOf, segments, specOf } from "./data.js";

// Machine-specific KPIs over a time window (copied from the Control Room machine page).

// ---------------------------------------------------------------- KPIs per machine kind

function stateShare(series, tag, m, t0, t1) {
  const t = m.tags[tag];
  if (!t) return null;
  const segs = segments(series.get(t.tagId), t0, t1, { time: t.time, v: t.num, s: t.text }, onOf);
  let on = 0;
  let known = 0;
  let edges = 0;
  let last;
  for (const s of segs) {
    if (s.v == null) continue;
    known += s.b - s.a;
    if (s.v) on += s.b - s.a;
    if (last === false && s.v === true) edges++;
    last = s.v;
  }
  return { share: known ? on / known : null, edges, known };
}

function numbers(series, m, tag, t0) {
  const t = m.tags[tag];
  if (!t) return [];
  return (series.get(t.tagId) ?? []).filter((p) => p.v != null && p.t >= t0);
}

const avgOf = (pts) => (pts.length ? pts.reduce((a, p) => a + p.v, 0) / pts.length : null);
const pct = (v) => (v == null ? "–" : fmtNum(v * 100, 0));

function slopePerMin(pts) {
  if (pts.length < 3) return null;
  const n = pts.length;
  const mx = pts.reduce((a, p) => a + p.t, 0) / n;
  const my = pts.reduce((a, p) => a + p.v, 0) / n;
  let num = 0;
  let den = 0;
  for (const p of pts) {
    num += (p.t - mx) * (p.v - my);
    den += (p.t - mx) ** 2;
  }
  return den ? (num / den) * 60_000 : null;
}

export function kpis(m, series, t0, t1) {
  const win = `last ${Math.round((t1 - t0) / 60000)} min`;
  const cur = (tag) => (good(m.tags[tag]) ? m.tags[tag].num : null);
  if (m.kind === "line") {
    const run = stateShare(series, "running", m, t0, t1);
    const sp = numbers(series, m, "speed", t0);
    const te = numbers(series, m, "temperature", t0);
    return [
      { label: "Availability", value: pct(run?.share), unit: "%", note: `time running · ${win}` },
      { label: "Stops", value: run ? run.edges : "–", unit: "", note: `restarts after a stop · ${win}` },
      { label: "Average speed", value: fmtNum(avgOf(sp), 0), unit: " bpm", note: `${sp.length} samples` },
      { label: "Peak temperature", value: te.length ? fmtNum(Math.max(...te.map((p) => p.v)), 1) : "–", unit: " °C", note: win },
    ];
  }
  if (m.kind === "pump") {
    const run = stateShare(series, "running", m, t0, t1);
    const trip = stateShare(series, "tripped", m, t0, t1);
    const pr = numbers(series, m, "discharge_pressure", t0);
    const mt = numbers(series, m, "motor_temp", t0);
    return [
      { label: "Running", value: pct(run?.share), unit: "%", note: `time running · ${win}` },
      { label: "Trips", value: trip ? trip.edges : "–", unit: "", note: `over-temperature trips · ${win}` },
      { label: "Avg discharge pressure", value: fmtNum(avgOf(pr), 2), unit: " bar", note: `${pr.length} samples` },
      { label: "Peak motor temp", value: mt.length ? fmtNum(Math.max(...mt.map((p) => p.v)), 1) : "–", unit: " °C", note: `trip limit ${specOf("motor_temp").hi} °C` },
    ];
  }
  if (m.kind === "tank") {
    const lv = numbers(series, m, "level", t0);
    const recent = lv.filter((p) => p.t >= t1 - 5 * 60_000);
    const slope = slopePerMin(recent);
    const level = cur("level");
    const s = specOf("level");
    let eta = "–";
    let etaNote = "level steady";
    if (slope && level != null) {
      const target = slope > 0 ? s.hi : s.lo;
      const mins = (target - level) / slope;
      if (mins > 0) {
        eta = fmtNum(mins, 1);
        etaNote = `until ${slope > 0 ? "HI" : "LO"} (${target} %) at the current rate`;
      } else etaNote = slope > 0 ? "above HI" : "below LO";
    }
    const of = numbers(series, m, "outlet_flow", t0);
    return [
      { label: "Level", value: level == null ? "–" : fmtNum(level, 1), unit: " %", note: `band ${s.lo}–${s.hi} %` },
      { label: "Fill rate", value: slope == null ? "–" : `${slope > 0 ? "+" : ""}${fmtNum(slope, 2)}`, unit: " %/min", note: "trend over the last 5 min" },
      { label: slope > 0 ? "Time to HI" : "Time to LO", value: eta, unit: eta === "–" ? "" : " min", note: etaNote },
      { label: "Avg outlet flow", value: fmtNum(avgOf(of), 1), unit: " m³/h", note: win },
    ];
  }
  if (m.kind === "meter") {
    const en = numbers(series, m, "energy_total", t0);
    const pw = numbers(series, m, "active_power", t0);
    const vo = numbers(series, m, "bus_voltage", t0);
    const fr = numbers(series, m, "frequency", t0);
    let sags = 0;
    for (let i = 1; i < vo.length; i++) if (vo[i - 1].v >= 380 && vo[i].v < 380) sags++;
    return [
      { label: "Energy used", value: en.length > 1 ? fmtNum(en[en.length - 1].v - en[0].v, 2) : "–", unit: " kWh", note: win },
      { label: "Average power", value: fmtNum(avgOf(pw), 1), unit: " kW", note: `${pw.length} samples` },
      { label: "Voltage sags", value: vo.length ? sags : "–", unit: "", note: `dips below 380 V · ${win}` },
      { label: "Max frequency deviation", value: fr.length ? fmtNum(Math.max(...fr.map((p) => Math.abs(p.v - 50))) * 1000, 0) : "–", unit: " mHz", note: "from 50 Hz" },
    ];
  }
  const all = Object.values(m.tags);
  return [
    { label: "Tags", value: all.length, unit: "", note: `${all.filter(good).length} good` },
    { label: "Numeric tags", value: all.filter(isNum).length, unit: "", note: "shown as trends" },
  ];
}
