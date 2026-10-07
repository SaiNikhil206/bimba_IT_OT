import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";

// Shared helpers for the Control Room page (/control-room).

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
};

export const specOf = (tag) => SPEC[tag] ?? { label: tag.replace(/_/g, " ") };

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

export function clamp01(v) {
  return Math.max(0, Math.min(1, v));
}

export function frac(tag, v) {
  const s = specOf(tag);
  if (s.min == null || v == null) return null;
  return clamp01((v - s.min) / (s.max - s.min));
}

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

export function reducedMotion() {
  return window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
}

/**
 * Rolling per-tag history: seeded from /api/tags/<id>/history/ once per tag, then extended with
 * WebSocket values. Re-renders at most twice a second.
 */
export function useTagSeries(tagIds, onValues, minutes = 15) {
  const store = useRef(new Map()); // tagId -> [{t, v}]
  const seeded = useRef(new Set());
  const [, setTick] = useState(0);
  const pending = useRef(false);
  const win = useRef(minutes);
  if (win.current !== minutes) {
    // new window: drop what we have and seed again with the longer/shorter history
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
    }, 500);
  }, []);

  const key = tagIds.join(",");
  useEffect(() => {
    for (const id of tagIds) {
      if (seeded.current.has(id)) continue;
      seeded.current.add(id);
      fetch(`/api/tags/${id}/history/?minutes=${minutes}`)
        .then((r) => (r.ok ? r.json() : null))
        .then((d) => {
          if (!d) return;
          const hist = d.points.filter((p) => p.num != null).map((p) => ({ t: Date.parse(p.time), v: p.num }));
          const liveTail = store.current.get(id) ?? [];
          const first = liveTail[0]?.t ?? Infinity;
          store.current.set(id, [...hist.filter((p) => p.t < first), ...liveTail]);
          bump();
        })
        .catch(() => seeded.current.delete(id));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, minutes, bump]);

  useEffect(
    () =>
      onValues((items) => {
        const cutoff = Date.now() - minutes * 60_000;
        let hit = false;
        for (const i of items) {
          if (i.num == null || i.text != null) continue;
          const arr = store.current.get(i.tagId) ?? [];
          arr.push({ t: Date.parse(i.time), v: i.num });
          while (arr.length && arr[0].t < cutoff) arr.shift();
          if (arr.length > 1200) arr.splice(0, arr.length - 1200);
          store.current.set(i.tagId, arr);
          hit = true;
        }
        if (hit) bump();
      }),
    [onValues, minutes, bump]
  );

  return store.current;
}

/** Values stored per second over the last `seconds`, counted from the WebSocket feed. */
export function useRate(onValues, seconds = 120) {
  const buckets = useRef(new Map());
  const [series, setSeries] = useState([]);
  useEffect(() => onValues((items) => {
    const s = Math.floor(Date.now() / 1000);
    buckets.current.set(s, (buckets.current.get(s) ?? 0) + items.length);
  }), [onValues]);
  useEffect(() => {
    const t = setInterval(() => {
      const now = Math.floor(Date.now() / 1000);
      const out = [];
      for (let s = now - seconds; s < now; s++) out.push({ t: s * 1000, v: buckets.current.get(s) ?? 0 });
      for (const k of buckets.current.keys()) if (k < now - seconds) buckets.current.delete(k);
      setSeries(out);
    }, 1000);
    return () => clearInterval(t);
  }, [seconds]);
  return series;
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
