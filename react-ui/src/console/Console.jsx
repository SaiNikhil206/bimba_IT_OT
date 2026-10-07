import { useCallback, useEffect, useMemo, useState } from "react";
import { useLive, usePoll } from "../useLive.js";
import { api, clone, same } from "./api.js";
import Devices from "./Devices.jsx";
import Modbus from "./Modbus.jsx";
import OpcUa from "./OpcUa.jsx";
import Kafka from "./Kafka.jsx";
import EventStreams from "./EventStreams.jsx";
import Flow from "./Flow.jsx";
import "./console.css";

const PAGES = [
  { key: "devices", name: "Devices", group: "Devices", C: Devices },
  { key: "modbus", name: "Modbus TCP", group: "OT sources", C: Modbus },
  { key: "opcua", name: "OPC UA", group: "OT sources", C: OpcUa },
  { key: "kafka", name: "Kafka", group: "IT targets", C: Kafka },
  { key: "eventstreams", name: "Boomi Event Streams", group: "IT targets", C: EventStreams },
  { key: "flow", name: "Live data flow", group: "Monitor", C: Flow },
];

// The part of the config each page edits, so the sidebar can mark unsaved pages.
const SECTION = {
  modbus: (c) => [c.sources.modbus, c.points.filter((p) => p.kind === "modbus")],
  opcua: (c) => [c.sources.opcua, c.points.filter((p) => p.kind === "opcua")],
  kafka: (c) => c.kafka,
  eventstreams: (c) => c.eventstreams,
};

const pageFromHash = () => Math.max(0, PAGES.findIndex((p) => `#${p.key}` === window.location.hash));

export default function Console() {
  const live = useLive();
  const plugin = usePoll("/api/plugin-status/", 3000);
  const [page, setPage] = useState(pageFromHash);
  const [config, setConfig] = useState(null);
  const [draft, setDraft] = useState(null);
  const [loadError, setLoadError] = useState(null);

  useEffect(() => {
    document.title = "OT/IT Bridge Console";
  }, []);

  const reload = useCallback(async ({ keepDraftFor } = {}) => {
    try {
      const c = await api("config/");
      setConfig(c);
      // after saving one page, keep unsaved edits on the others
      setDraft((d) => {
        if (!d || !keepDraftFor) return clone(c);
        const next = clone(c);
        for (const key of Object.keys(SECTION)) {
          if (key === keepDraftFor) continue;
          if (key === "modbus" || key === "opcua") {
            next.sources[key] = d.sources[key];
            next.points = next.points.map((p) => (p.kind === key ? d.points.find((x) => x.path === p.path) ?? p : p));
          } else next[key] = d[key];
        }
        return next;
      });
      setLoadError(null);
    } catch (e) {
      setLoadError(e.message);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  // Django restarting (502) or the config folder missing: keep retrying in the background
  useEffect(() => {
    if (!loadError) return undefined;
    const t = setTimeout(() => reload(), 5000);
    return () => clearTimeout(t);
  }, [loadError, reload]);

  const go = useCallback((i) => {
    const n = Math.max(0, Math.min(PAGES.length - 1, i));
    setPage(n);
    window.history.replaceState(null, "", `#${PAGES[n].key}`);
    window.scrollTo({ top: 0 });
  }, []);

  useEffect(() => {
    const on = () => setPage(pageFromHash());
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);

  const dirty = useMemo(() => {
    const out = {};
    if (config && draft) for (const [k, f] of Object.entries(SECTION)) out[k] = !same(f(config), f(draft));
    return out;
  }, [config, draft]);

  // warn before leaving with unsaved edits
  useEffect(() => {
    const any = Object.values(dirty).some(Boolean);
    const on = (e) => {
      if (any) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", on);
    return () => window.removeEventListener("beforeunload", on);
  }, [dirty]);

  const P = PAGES[page];
  const last = page === PAGES.length - 1;
  const ctx = {
    live, plugin: plugin.data, pluginError: plugin.error, config, draft, setDraft, reload,
    dirty: dirty[P.key],
  };

  return (
    <>
      <div className="shell">
        <aside className="side" aria-label="Setup steps">
          <div className="app">
            <b>OT/IT Bridge</b>
            <span>
              Edge gateway {plugin.data?.edgeId ?? config?.edgeId ?? "…"}
              {config?.plantId ? ` · ${config.plantId}` : ""}
            </span>
          </div>
          <ol className="steps">
            {PAGES.map((p, k) => (
              <StepItem key={p.key} p={p} k={k} page={page} first={k === 0 || PAGES[k - 1].group !== p.group}
                        dirty={dirty[p.key]} onClick={() => go(k)} />
            ))}
          </ol>
          <div className="side-foot">
            <a href="/">← BIMBA live UI</a>
          </div>
        </aside>
        <div className={`wrap${last || page === 0 ? " wide" : ""}`}>
          <main>
            {loadError && (
              <p className="note err" role="alert">
                <b>Could not load the plugin configuration.</b> {loadError}{" "}
                <button className="linkbtn" onClick={() => reload()}>Retry</button>
              </p>
            )}
            {!config && !loadError ? <p className="muted">Loading configuration…</p> : <P.C {...ctx} />}
          </main>
        </div>
      </div>
      <nav className="nav" aria-label="Wizard">
        <div className="nav-in">
          <button className="btn" onClick={() => go(page - 1)} style={{ visibility: page === 0 ? "hidden" : "visible" }}
                  disabled={page === 0}>
            Back
          </button>
          <span className="where">Step {page + 1} of {PAGES.length}</span>
          <button className="btn primary" onClick={() => go(page + 1)} hidden={last}>
            {page === PAGES.length - 2 ? "Start live data flow" : `Next: ${PAGES[page + 1]?.name}`}
          </button>
        </div>
      </nav>
    </>
  );
}

function StepItem({ p, k, page, first, dirty, onClick }) {
  const cls = k < page ? "done" : k === page ? "cur" : "";
  return (
    <>
      {first && <li className="grp">{p.group}</li>}
      <li>
        <button className={cls} aria-current={k === page ? "step" : undefined} onClick={onClick}>
          <span className="n">{k < page ? "✓" : k + 1}</span>
          {p.name}
          {dirty && <span className="dirty" title="Unsaved changes">Unsaved</span>}
        </button>
      </li>
    </>
  );
}
