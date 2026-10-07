import { band, fmtNum, good, isNum, since, specOf, unit } from "./lib.js";

// Active alarms derived from the plugin status + live tag values. Shared by the overview and the
// machine dashboard (which filters to its own machine).

export function computeAlarms(p, machines) {
  const out = [];
  if (p && !p.reachable) out.push({ s: "critical", t: "Edge gateway unreachable", d: p.error ?? "" });
  for (const r of p?.delivery?.routes ?? []) {
    for (const d of r.destinations ?? []) {
      if (d.state !== "down") continue;
      // a standby failover keeps its last failed state until it is probed or used again: not an outage
      const standby = r.activeDestination && r.activeDestination !== d.name;
      out.push(standby
        ? { s: "warning", t: `${d.name} standby, last attempt failed`, d: `${since(d.lastFailureAt)} ago · ${d.lastError?.slice(0, 60) ?? ""}` }
        : { s: "critical", t: `${d.name} down`, d: d.lastError?.slice(0, 80) ?? "retrying" });
    }
    if (r.bufferedBatches > 0) out.push({ s: "warning", t: `Route ${r.name} buffering`, d: `${r.bufferedBatches} batches on disk` });
  }
  const main = p?.delivery?.routes?.find((r) => r.name === "main");
  const act = main?.destinations?.find((d) => d.name === main.activeDestination);
  if (act && act.role !== "primary") out.push({ s: "warning", t: "Running on failover", d: `${act.name} is carrying the data` });

  // every device-driven machine BAD while expression tags (LINE-SIM) still update = Ignition trial expired
  const lost = machines.filter((m) => m.health === "critical" && m.kind !== "generic");
  const device = machines.filter((m) => m.kind !== "generic" && m.kind !== "line");
  if (lost.length && lost.length === device.length && machines.some((m) => m.kind === "line" && m.health === "good")) {
    out.push({ s: "critical", t: "All device drivers BAD", d: "Ignition trial expired? Gateway → Status → Overview → Reset Trial" });
  }
  for (const m of machines) out.push(...machineAlarms(m));
  return out;
}

export function machineAlarms(m) {
  const out = [];
  if (m.health === "critical") out.push({ s: "critical", t: `${m.machineId} signal lost`, d: `BAD quality since ${since(m.lastSeen)} ago`, m: m.machineId });
  for (const t of Object.values(m.tags)) {
    if (t.tag === "tripped" && good(t) && t.num) out.push({ s: "critical", t: `${m.machineId} tripped`, d: `since ${since(t.time)} ago`, m: m.machineId });
    if (!isNum(t) || !good(t)) continue;
    const b = band(t.tag, t.num);
    if (b !== "good" && b !== "neutral")
      out.push({ s: b, t: `${m.machineId} ${specOf(t.tag).label} ${b === "critical" ? "high" : "low"}`, d: `${fmtNum(t.num)} ${unit(t.unit)}`, m: m.machineId });
  }
  return out;
}
