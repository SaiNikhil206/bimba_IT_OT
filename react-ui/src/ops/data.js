import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";

// Data helpers for BIMBA Operations (/operations). Copied from the Control Room so the two apps
// can change independently; nothing here touches the DOM beyond chart measuring.

/** Display range + alarm band per tag name. Ranges follow the Modbus / OPC UA simulators. */
export const SPEC = {
  speed: { min: 0, max: 300, label: "Line speed" },
  temperature: { min: 0, max: 100, hi: 80, label: "Temperature" },
  level: { min: 0, max: 100, lo: 25, hi: 85, label: "Level" },
  inlet_flow: { min: 0, max: 150, label: "Inlet flow" },
  outlet_flow: { min: 0, max: 150, label: "Outlet flow" },
  discharge_pressure: { min: 0, max: 6, label: "Discharge pressure" },
  motor_temp: { min: 0, max: 120, hi: 90, label: "Motor temp" },
  motor_current: { min: 0, max: 60, label: "Motor current" },
  bus_voltage: { min: 300, max: 450, lo: 380, label: "Bus voltage" },
  feeder_current: { min: 0, max: 100, label: "Feeder current" },
  active_power: { min: 0, max: 60, label: "Active power" },
  frequency: { min: 49.8, max: 50.2, label: "Frequency" },
  energy_total: { counter: true, label: "Energy" },
  running: { label: "Running" },
  tripped: { label: "Tripped" },
  state: { label: "State" },
  breaker_closed: { label: "Breaker closed" },
  speed_setpoint: { label: "Speed setpoint" },
};

export const specOf = (tag) => SPEC[tag] ?? { label: tag.charAt(0).toUpperCase() + tag.slice(1).replace(/_/g, " ") };

/** Which illustration a machine gets, from its tags (falls back to its name). */
export function assetKind(m) {
  const tags = Object.keys(m.tags ?? {});
  const id = m.machineId.toUpperCase();
  if (tags.includes("level") || id.startsWith("TANK")) return "tank";
  if (tags.includes("discharge_pressure") || id.startsWith("PUMP")) return "pump";
  if (tags.includes("active_power") || tags.includes("bus_voltage") || id.startsWith("SUBST")) return "meter";
  if (tags.includes("speed") || id.startsWith("LINE")) return "line";
  return "generic";
}

export const KIND_LABEL = { tank: "Storage tank", pump: "Centrifugal pump", meter: "Substation meter", line: "Production line", generic: "Device" };

export const isNum = (t) => t && t.num != null && t.text == null;
export const isBool = (t) => t && (t.dataType === "boolean" || t.text === "true" || t.text === "false");
export const good = (t) => t?.quality === "GOOD";

export function fmtNum(n, digits) {
  if (n == null || Number.isNaN(n)) return "–";
  const abs = Math.abs(n);
  const d = digits ?? (abs >= 1000 ? 0 : abs >= 100 ? 1 : 2);
  return Number(n).toLocaleString(undefined, { maximumFractionDigits: d, minimumFractionDigits: Math.min(d, abs >= 100 ? 0 : 1) });
}

export const UNIT = { degC: "°C", m3: "m³", "m3/h": "m³/h" };
export const unit = (u) => (u ? UNIT[u] ?? u : "");

/** good | warning | critical for a numeric value against its alarm band. */
export function band(tag, v) {
  const s = specOf(tag);
  if (v == null) return "neutral";
  if (s.hi != null && v >= s.hi) return "critical";
  if (s.lo != null && v <= s.lo) return "warning";
  return "good";
}

export function hhmm(iso) {
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

export function hms(t) {
  return new Date(t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function since(iso) {
  if (!iso) return "–";
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 5) return "now";
  if (s < 60) return `${Math.round(s)}s`;
  if (s < 3600) return `${Math.round(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ${String(Math.round((s % 3600) / 60)).padStart(2, "0")}m`;
  return `${Math.floor(s / 86400)}d ${Math.floor((s % 86400) / 3600)}h`;
}

export function useNow(ms) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

/**
 * Rendered pixel width of an element, so charts draw at real size and text never stretches.
 * Returns [callbackRef, width]. Measures as soon as the element mounts (or is swapped for another
 * one), then on every resize: ResizeObserver for layout changes, plus the window resize event,
 * because ResizeObserver only reports while the page is rendering (not in throttled background tabs).
 */
export function useWidth(fallback = 600) {
  const [w, setW] = useState(fallback);
  const node = useRef(null);
  const watched = useRef(null);
  const stop = useRef(null);
  const ref = useCallback((el) => {
    node.current = el;
  }, []);
  useLayoutEffect(() => {
    const el = node.current;
    if (el === watched.current) return;
    watched.current = el;
    stop.current?.();
    stop.current = null;
    if (!el) return;
    const measure = () => {
      const cw = el.clientWidth;
      if (cw > 0) setW(Math.max(120, Math.round(cw)));
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    window.addEventListener("resize", measure);
    stop.current = () => {
      ro.disconnect();
      window.removeEventListener("resize", measure);
    };
  });
  useEffect(() => () => stop.current?.(), []);
  return [ref, w];
}

/**
 * Every tag of one machine over the last `minutes`: numeric, boolean and text values ({t, v, s}).
 * Seeded from /api/tags/<id>/history/ and extended with WebSocket values for this machine.
 */
export function useMachineHistory(machine, minutes, onValues) {
  const store = useRef(new Map());
  const seeded = useRef(new Set());
  const [loading, setLoading] = useState(0);
  const [, setTick] = useState(0);
  const pending = useRef(false);
  const win = useRef(minutes);
  if (win.current !== minutes) {
    win.current = minutes;
    store.current = new Map();
    seeded.current = new Set();
  }
  const bump = useCallback(() => {
    if (pending.current) return;
    pending.current = true;
    setTimeout(() => {
      pending.current = false;
      setTick((n) => n + 1);
    }, 400);
  }, []);

  const id = machine?.machineId;
  const tagIds = Object.values(machine?.tags ?? {}).map((t) => t.tagId).sort((a, b) => a - b).join(",");
  useEffect(() => {
    if (!tagIds) return;
    for (const tid of tagIds.split(",").map(Number)) {
      if (seeded.current.has(tid)) continue;
      seeded.current.add(tid);
      setLoading((n) => n + 1);
      fetch(`/api/tags/${tid}/history/?minutes=${minutes}`)
        .then((r) => (r.ok ? r.json() : null))
        .then((d) => {
          if (!d) return;
          const hist = d.points.map((p) => ({ t: Date.parse(p.time), v: p.num, s: p.text }));
          const tail = store.current.get(tid) ?? [];
          const first = tail[0]?.t ?? Infinity;
          store.current.set(tid, [...hist.filter((p) => p.t < first), ...tail]);
          bump();
        })
        .catch(() => seeded.current.delete(tid))
        .finally(() => setLoading((n) => n - 1));
    }
  }, [tagIds, minutes, bump]);

  useEffect(
    () =>
      onValues((items) => {
        const cutoff = Date.now() - minutes * 60_000;
        let hit = false;
        for (const i of items) {
          if (i.machineId !== id) continue;
          const arr = store.current.get(i.tagId) ?? [];
          arr.push({ t: Date.parse(i.time), v: i.num, s: i.text, q: i.quality });
          while (arr.length && arr[0].t < cutoff) arr.shift();
          if (arr.length > 3000) arr.splice(0, arr.length - 3000);
          store.current.set(i.tagId, arr);
          hit = true;
        }
        if (hit) bump();
      }),
    [onValues, minutes, id, bump]
  );
  return { series: store.current, loading: loading > 0 };
}

/** The plugin's tag map (/api/console/config/): source kind, device, register / node, scale. */
export function useConsoleConfig(version = 0) {
  const [cfg, setCfg] = useState(null);
  useEffect(() => {
    let alive = true;
    fetch("/api/console/config/")
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => alive && setCfg(d))
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [version]);
  return cfg;
}

/** Truthiness of a stored value: booleans come back as 0/1 (bucket averages), text as "true"/"false". */
export const onOf = (p) => (p.s === "true" ? true : p.s === "false" ? false : p.v == null ? null : p.v >= 0.5);

/**
 * Step segments for a state tag over [t0, t1]. Values are sent on change, so a value holds until the
 * next one. Before the first point the value is only known if the tag's last change is older than t0.
 */
export function segments(points, t0, t1, current, valueOf) {
  const pts = (points ?? []).filter((p) => p.t >= t0 && p.t <= t1);
  let cur = current && current.time && Date.parse(current.time) < t0 ? valueOf(current) : undefined;
  let start = t0;
  const out = [];
  for (const p of pts) {
    const v = valueOf(p);
    if (v === cur) continue;
    if (p.t > start) out.push({ a: start, b: p.t, v: cur });
    start = p.t;
    cur = v;
  }
  if (t1 > start) out.push({ a: start, b: t1, v: cur });
  return out;
}

// ---------------------------------------------------------------- alarms

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
  const main = p?.delivery?.routes?.find((r) => (r.kind ?? r.name) === "main");
  const act = main?.destinations?.find((d) => d.name === main.activeDestination);
  if (act && act.role !== "primary") out.push({ s: "warning", t: "Running on failover", d: `${act.name} is carrying the data` });

  // every device-driven machine BAD while expression tags (LINE-SIM) still update = field network / devices unreachable
  const lost = machines.filter((m) => m.health === "critical" && m.kind !== "generic");
  const device = machines.filter((m) => m.kind !== "generic" && m.kind !== "line");
  if (lost.length && lost.length === device.length && machines.some((m) => m.kind === "line" && m.health === "good")) {
    out.push({ s: "critical", t: "All device drivers BAD", d: "The gateway cannot reach any field device. Check the device network and BIMBA Gateway → Devices." });
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
