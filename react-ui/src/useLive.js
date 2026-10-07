import { useCallback, useEffect, useReducer, useRef, useState } from "react";

// Live state fed by the BIMBA (REST + WS) WebSocket (/ws/live/), which relays PostgreSQL
// NOTIFY events from bimba_ingest(): data stored via Boomi OR BIMBA Ingest shows up here.

const initial = { machines: {}, batches: [] };

function key(machineId, tag) {
  return `${machineId}\u0000${tag}`;
}

function reducer(state, action) {
  switch (action.type) {
    case "snapshot": {
      const machines = {};
      for (const m of action.machines) {
        machines[m.machineId] = { ...m, tags: Object.fromEntries(m.tags.map((t) => [t.tag, t])) };
      }
      return { ...state, machines };
    }
    case "values": {
      const machines = { ...state.machines };
      for (const it of action.items) {
        const m = machines[it.machineId] || { plantId: it.plantId, machineId: it.machineId, tags: {} };
        const prev = m.tags[it.tag] || {};
        machines[it.machineId] = {
          ...m,
          lastSeen: it.time,
          tags: { ...m.tags, [it.tag]: { ...prev, tagId: it.tagId, tag: it.tag, unit: it.unit ?? prev.unit,
            num: it.num, text: it.text, quality: it.quality, time: it.time, flash: Date.now() } },
        };
      }
      return { ...state, machines };
    }
    case "batch":
      return { ...state, batches: [{ ...action.batch, id: `${action.batch.at}-${Math.random()}` }, ...state.batches].slice(0, 40) };
    default:
      return state;
  }
}

export function useLive() {
  const [state, dispatch] = useReducer(reducer, initial);
  const [connected, setConnected] = useState(false);
  const listeners = useRef(new Set());

  useEffect(() => {
    let ws;
    let retry = 1000;
    let stopped = false;
    let timer;

    const connect = () => {
      const proto = window.location.protocol === "https:" ? "wss" : "ws";
      ws = new WebSocket(`${proto}://${window.location.host}/ws/live/`);
      ws.onopen = () => {
        setConnected(true);
        retry = 1000;
      };
      ws.onmessage = (e) => {
        let msg;
        try {
          msg = JSON.parse(e.data);
        } catch {
          return;
        }
        if (msg.type === "snapshot") dispatch({ type: "snapshot", machines: msg.machines });
        else if (msg.type === "values") {
          dispatch({ type: "values", items: msg.items });
          listeners.current.forEach((fn) => fn(msg.items));
        } else if (msg.type === "batch") dispatch({ type: "batch", batch: msg });
      };
      ws.onclose = () => {
        setConnected(false);
        if (!stopped) {
          timer = setTimeout(connect, retry);
          retry = Math.min(retry * 2, 15000);
        }
      };
    };
    connect();
    return () => {
      stopped = true;
      clearTimeout(timer);
      ws && ws.close();
    };
  }, []);

  const onValues = useCallback((fn) => {
    listeners.current.add(fn);
    return () => listeners.current.delete(fn);
  }, []);

  return { ...state, connected, onValues, key };
}

export function usePoll(url, intervalMs) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    let alive = true;
    const load = () =>
      fetch(url)
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((d) => alive && (setData(d), setError(null)))
        .catch((e) => alive && setError(e.message));
    load();
    const t = setInterval(load, intervalMs);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [url, intervalMs]);
  return { data, error };
}
