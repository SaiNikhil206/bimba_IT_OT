import { PROTO } from "./api.js";

// Tag paths from demo-tags.json ("PUMP-P1/motor_temp") matched to live tags (machineId + tag).
export const pathKey = (machine, tag) => `${machine}/${tag}`.toLowerCase();

export function pointIndex(points) {
  const m = new Map();
  for (const p of points ?? []) m.set(pathKey(p.machine, p.tag), p);
  return m;
}

/** Where a machine's data comes from: the source kind + device of its mapped tags. */
export function machineSource(points, machineId) {
  const mine = (points ?? []).filter((p) => p.machine === machineId);
  if (!mine.length) return { kind: "other", device: null };
  const kinds = [...new Set(mine.map((p) => p.kind))];
  const devices = [...new Set(mine.map((p) => p.device).filter(Boolean))];
  return { kind: kinds.length === 1 ? kinds[0] : "other", device: devices.join(", ") || null };
}

export function sourceLine(config, src) {
  const conn = src.device && config?.sources?.[src.kind]?.[src.device];
  if (src.kind === "modbus" && conn) return `${PROTO.modbus} · ${conn.host}:${conn.port} · unit ${conn.unitId}`;
  if (src.kind === "opcua" && conn) return `${PROTO.opcua} · ${conn.endpoint}`;
  return PROTO[src.kind] ?? PROTO.other;
}

/** Machines seen live (WebSocket) plus machines mapped in the tag file, sorted. */
export function machineList(live, points) {
  const ids = new Set(Object.keys(live.machines));
  for (const p of points ?? []) ids.add(p.machine);
  return [...ids].sort();
}

/** Where a tag reads from (register, node, expression), for the tables. */
export function addressOf(p) {
  if (!p) return "–";
  if (p.kind === "modbus") return `[${p.device}]${p.address}`;
  if (p.kind === "opcua") return p.node?.replace(/^ns=\d+;s=/, "") ?? "–";
  if (p.kind === "expression") return "expression";
  return "–";
}

export function modbusArea(address) {
  const m = /^(?:\d+\.)?(HR|IR|C|DI)\d+$/i.exec((address ?? "").trim());
  return m ? m[1].toUpperCase() : null;
}

export const validModbus = (a) => modbusArea(a) !== null;
export const validNode = (n) => /^(ns=\d+;)?[isgb]=.+$/.test((n ?? "").trim());

/** Raw register value the PLC holds for a scaled value (what the flow page shows as "Raw"). */
export function rawFor(p, t) {
  if (!p || !t) return "–";
  if (p.kind === "modbus") {
    const area = modbusArea(p.address);
    if (area === "C" || area === "DI") return t.num == null ? "–" : String(t.num ? 1 : 0);
    if (t.num == null) return "–";
    const r = Math.round(t.num / (p.scale || 1)) & 0xffff;
    return "0x" + r.toString(16).toUpperCase().padStart(4, "0");
  }
  if (p.kind === "opcua") return p.dataType ?? "–";
  if (p.kind === "expression") return "expr";
  return "–";
}

export function fmt(t) {
  if (!t) return "–";
  if (t.num == null) return t.text ?? "–";
  if (t.text === "true" || t.text === "false") return t.num ? "ON" : "OFF";
  const n = Number(t.num);
  const abs = Math.abs(n);
  return n.toLocaleString(undefined, { maximumFractionDigits: abs >= 1000 ? 0 : abs >= 100 ? 1 : 2 });
}
