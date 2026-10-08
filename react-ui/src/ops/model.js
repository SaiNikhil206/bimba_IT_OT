import { KIND_LABEL, band, fmtNum, good, isBool, isNum, since, specOf, unit } from "./data.js";

// What each screen summarises, computed once here so every page says the same thing the same way.

export const KIND = KIND_LABEL;

const HEADLINE = { line: "speed", pump: "discharge_pressure", tank: "level", meter: "active_power" };

/** The one reading that best describes a machine, for lists and cards. */
export function headline(m) {
  const t = m.tags[HEADLINE[m.kind]] ?? Object.values(m.tags).find(isNum);
  if (!t) return null;
  if (!good(t)) return { label: specOf(t.tag).label, value: "No signal", unit: "", state: "critical" };
  return { label: specOf(t.tag).label, value: fmtNum(t.num), unit: unit(t.unit), state: band(t.tag, t.num) };
}

/** Running / stopped / tripped, when the machine reports it. */
export function runState(m) {
  const tr = m.tags.tripped;
  if (tr && good(tr) && tr.num) return { state: "critical", text: "Tripped" };
  const r = m.tags.running;
  if (r && good(r)) return r.num ? { state: "good", text: "Running" } : { state: "neutral", text: "Stopped" };
  const st = m.tags.state;
  if (st && good(st) && st.text) return { state: st.text === "RUNNING" ? "good" : "neutral", text: cap(st.text) };
  return null;
}

export function health(m) {
  if (m.health === "critical") return { state: "critical", text: "No signal" };
  if (m.health === "warning") return { state: "warning", text: "Partial signal" };
  return { state: "good", text: "Online" };
}

/** "just now", "12s ago", "3m ago". */
export const ago = (iso) => {
  if (!iso) return "never";
  const s = since(iso);
  return s === "now" ? "just now" : `${s} ago`;
};

export const cap = (s) => (s ? s.charAt(0) + s.slice(1).toLowerCase() : s);

export function readingText(t) {
  if (!t) return "–";
  if (!good(t)) return "No signal";
  if (isBool(t)) return t.num ? "On" : "Off";
  if (t.num != null) return `${fmtNum(t.num)} ${unit(t.unit)}`.trim();
  return cap(t.text) ?? "–";
}

const TARGET = { eventstreams: "Boomi Event Streams", kafka: "Kafka", rest: "BIMBA Ingest", mqtt: "MQTT", database: "BIMBA database" };
export const targetName = (d) => (/\/api\/ingest\/?$/.test(d?.endpoint ?? "") ? "BIMBA Ingest" : TARGET[d?.transport] ?? d?.name ?? "–");

/** Every destination with its route, active flag and a plain-language status. */
export function destinations(plugin) {
  return (plugin?.delivery?.routes ?? []).flatMap((r) =>
    (r.destinations ?? []).map((d) => {
      const active = r.activeDestination === d.name;
      const state = d.state === "down" && active ? "critical" : d.state === "down" ? "warning" : active ? "good" : "neutral";
      const text = d.state === "down" ? (active ? "Down" : "Standby, last check failed") : active ? "Delivering" : "Standby";
      return { ...d, route: r, active, state, text, label: targetName(d) };
    })
  );
}

/** The four steps data takes, each with one status and one line of detail. */
export function pipeline({ cfg, machines, plugin, summary, summaryError, deliveries }) {
  const tags = machines.filter((m) => m.plantId !== "BOOMI-TEST").flatMap((m) => Object.values(m.tags));
  const ok = tags.filter(good).length;
  const conns = Object.keys(cfg?.sources?.modbus ?? {}).length + Object.keys(cfg?.sources?.opcua ?? {}).length;
  const devState = !tags.length ? "neutral" : ok === tags.length ? "good" : ok ? "warning" : "critical";

  const main = plugin?.delivery?.routes?.find((r) => (r.kind ?? r.name) === "main");
  const act = main?.destinations?.find((d) => d.name === main.activeDestination);
  const buffered = plugin?.delivery?.bufferedBatches ?? 0;
  const mirrors = (plugin?.delivery?.routes ?? []).filter((r) => (r.kind ?? r.name) !== "main");
  const mirrorDown = mirrors.filter((r) => !r.activeDestination).length;
  const delState = !plugin?.reachable ? "neutral" : !act ? "critical" : act.role !== "primary" || buffered || mirrorDown ? "warning" : "good";
  const delText = !plugin?.reachable ? "Unknown" : !act ? "Not delivering, buffering on disk" : act.role !== "primary" ? `On failover: ${targetName(act)}` : `Via ${targetName(act)}`;

  const lastBatch = (deliveries?.recent ?? [])[0]?.receivedAt;
  const age = lastBatch ? (Date.now() - Date.parse(lastBatch)) / 1000 : Infinity;
  const dbState = summaryError ? "critical" : !summary ? "neutral" : age < 60 ? "good" : "warning";

  return [
    { key: "devices", title: "Field devices", state: devState,
      text: tags.length ? `${ok} of ${tags.length} readings OK` : "Waiting for data",
      note: `${conns} connection${conns === 1 ? "" : "s"} + gateway expressions` },
    { key: "gateway", title: "Edge gateway", state: plugin ? (plugin.reachable ? "good" : "critical") : "neutral",
      text: plugin?.reachable ? `Running for ${since(plugin.startedAt)}` : plugin ? "Not reachable" : "Checking…",
      note: plugin?.edgeId ?? "BIMBA Gateway" },
    { key: "delivery", title: "Delivery", state: delState, text: delText,
      note: buffered ? `${buffered} batches waiting on disk` : mirrors.length ? `Copy to ${mirrors.map((r) => targetName(r.destinations?.[0])).join(", ")}` : "No copies" },
    { key: "database", title: "Database", state: dbState,
      text: summaryError ? "API not reachable" : summary ? `${summary.measurements.toLocaleString()} readings stored` : "Checking…",
      note: lastBatch ? `Last write ${ago(lastBatch)}` : "PostgreSQL · schema bimba" },
  ];
}

export const VIA = {
  "boomi-es": { label: "Boomi Event Streams", cls: "s1" },
  "bimba-rest": { label: "BIMBA Ingest (failover)", cls: "s2" },
  "boomi-rest": { label: "Boomi REST", cls: "s3" },
  "boomi-mqtt": { label: "Boomi MQTT", cls: "s4" },
  "bimba-mqtt": { label: "BIMBA Ingest MQTT", cls: "s5" },
  "bimba-gateway": { label: "BIMBA Gateway (direct)", cls: "s6" },
};
