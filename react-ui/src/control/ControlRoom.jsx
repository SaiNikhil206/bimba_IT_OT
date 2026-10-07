import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLive, usePoll } from "../useLive.js";
import AssetCard from "./Assets.jsx";
import River from "./River.jsx";
import MachinePage from "./Machine.jsx";
import Bridge from "./bridge/Bridge.jsx";
import { bridgePath } from "./bridge/common.jsx";
import { computeAlarms } from "./alarms.js";
import { assetKind, fmtNum, good, hms, isNum, since, specOf, unit, useConsoleConfig, useNow, useRate, useTagSeries } from "./lib.js";
import Palette, { useHotkey } from "./palette.jsx";
import { Badge, Heat, Odometer, QualityRing, Scope, Spark } from "./viz.jsx";
import { AlarmList, VIA, machinePath } from "./shared.jsx";
import "./control.css";
import "./machine.css";
import "./flow.css";

// BIMBA Control Room (/control-room): a dark, operator-style view of the same live data the
// BIMBA live UI and the OT/IT Bridge Console use. Read-only; it changes nothing.
//   /control-room                   plant overview
//   /control-room/machine/<ID>      one machine's dashboard
//   /control-room/bridge[/<section>[/<item>]]   Bridge Console (OT sources + IT targets setup)
// Navigation stays inside the page (history API), so the live WebSocket keeps running.

const WINDOWS = [5, 15, 60];
const BASE = "/control-room";

function loadTheme() {
  try {
    return localStorage.getItem("bimba.cr.theme") === "light" ? "light" : "dark";
  } catch {
    return "dark";
  }
}

function routeOf(pathname) {
  const m = /^\/control-room\/machine\/([^/]+)\/?$/.exec(pathname);
  if (m) return { page: "machine", id: decodeURIComponent(m[1]) };
  const b = /^\/control-room\/bridge(?:\/([^/]+))?(?:\/([^/]+))?\/?$/.exec(pathname);
  if (b) return { page: "bridge", section: b[1] ?? "", item: b[2] ? decodeURIComponent(b[2]) : null };
  return { page: "overview" };
}

export default function ControlRoom() {
  const live = useLive();
  const summary = usePoll("/api/summary/", 5000);
  const plugin = usePoll("/api/plugin-status/", 4000);
  const deliveries = usePoll("/api/deliveries/?minutes=60", 15000);
  const [theme, setTheme] = useState(loadTheme);
  const [route, setRoute] = useState(() => routeOf(window.location.pathname));
  const now = useNow(1000);
  const [cfgVer, setCfgVer] = useState(0);
  const bumpCfg = useCallback(() => setCfgVer((n) => n + 1), []);
  const cfg = useConsoleConfig(cfgVer);
  // a page with unsaved edits (Bridge Console) sets a message; navigating away asks first
  const guard = useRef(null);
  const setGuard = useCallback((msg) => {
    guard.current = msg;
  }, []);
  const here = useRef(window.location.pathname);
  const [search, setSearch] = useState(false);
  const openSearch = useCallback(() => setSearch(true), []);
  useHotkey(openSearch);

  useEffect(() => {
    if (!document.getElementById("cr-fonts")) {
      const l = document.createElement("link");
      l.id = "cr-fonts";
      l.rel = "stylesheet";
      l.href = "https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap";
      document.head.appendChild(l);
    }
    document.body.classList.add("cr-body");
    const on = () => {
      if (guard.current && !window.confirm(guard.current)) {
        window.history.pushState(null, "", here.current); // stay on the page with the edits
        return;
      }
      here.current = window.location.pathname;
      setRoute(routeOf(window.location.pathname));
    };
    window.addEventListener("popstate", on);
    return () => {
      document.body.classList.remove("cr-body");
      window.removeEventListener("popstate", on);
    };
  }, []);

  useEffect(() => {
    document.title = route.page === "machine" ? `${route.id} · BIMBA Control Room`
      : route.page === "bridge" ? "Bridge Console · BIMBA Control Room" : "BIMBA Control Room";
  }, [route]);

  useEffect(() => {
    document.body.dataset.crTheme = theme;
    try {
      localStorage.setItem("bimba.cr.theme", theme);
    } catch {
      /* storage blocked: theme just isn't remembered */
    }
  }, [theme]);

  const go = useCallback((to) => {
    if (to === window.location.pathname) return;
    if (guard.current && !window.confirm(guard.current)) return;
    guard.current = null;
    window.history.pushState(null, "", to);
    here.current = to;
    setRoute(routeOf(to));
    window.scrollTo({ top: 0 });
  }, []);

  /** onClick for <a href>: in-page navigation, but ctrl/middle-click still opens a new tab. */
  const link = useCallback((to) => (e) => {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    go(to);
  }, [go]);

  const machines = useMemo(
    () =>
      Object.values(live.machines)
        .map((m) => {
          const tags = Object.values(m.tags);
          const bad = tags.filter((t) => !good(t)).length;
          return { ...m, kind: assetKind(m), health: tags.length && bad === tags.length ? "critical" : bad ? "warning" : "good" };
        })
        // live plant first, test harness last
        .sort((a, b) => (a.plantId === "BOOMI-TEST") - (b.plantId === "BOOMI-TEST") || a.machineId.localeCompare(b.machineId)),
    [live.machines]
  );

  // values per minute per machine, from the live feed
  const [perMachine, setPerMachine] = useState({});
  useEffect(() => {
    const log = [];
    const off = live.onValues((items) => {
      const t = Date.now();
      for (const i of items) log.push([t, i.machineId]);
    });
    const timer = setInterval(() => {
      const cut = Date.now() - 60_000;
      while (log.length && log[0][0] < cut) log.shift();
      const out = {};
      for (const [, id] of log) out[id] = (out[id] ?? 0) + 1;
      setPerMachine(out);
    }, 2000);
    return () => {
      off();
      clearInterval(timer);
    };
  }, [live.onValues]);

  const alarms = useMemo(() => computeAlarms(plugin.data, machines), [plugin.data, machines]);
  const p = plugin.data;
  const ctx = { live, summary: summary.data, summaryError: summary.error, plugin: p, deliveries: deliveries.data, machines, perMachine, alarms, go, link, cfg };
  const crit = alarms.filter((a) => a.s === "critical").length;
  const state = crit ? "critical" : alarms.length ? "warning" : "good";

  const tickerItems = live.batches.length
    ? live.batches.map((b) => ({ key: b.id, at: b.at, via: b.via, n: b.received, dup: b.duplicates, seq: b.firstSeq != null ? `${b.firstSeq}–${b.lastSeq}` : null }))
    : (deliveries.data?.recent ?? []).map((b) => ({ key: b.id, at: b.receivedAt, via: b.via, n: b.messages, dup: b.duplicates, seq: b.firstSeq != null ? `${b.firstSeq}–${b.lastSeq}` : null }));

  return (
    <div className="cr" data-theme={theme}>
      <header className="cr-top">
        <a className="cr-brand" href={BASE} onClick={link(BASE)}>
          <span className="cr-radar" aria-hidden="true"><i /></span>
          <div>
            <h1>BIMBA <span>Control Room</span></h1>
            <p>{p?.plantId ?? "PLANT"} · {p?.edgeId ?? "edge gateway"} · Ignition → Boomi Event Streams / BIMBA Ingest → PostgreSQL</p>
          </div>
        </a>
        <div className="cr-top-m">
          <button className="cr-search" onClick={openSearch} aria-label="Search machines and tags (Ctrl+K)">
            <span aria-hidden="true">⌕</span> Search machines, tags… <kbd>Ctrl K</kbd>
          </button>
          <div className="cr-ingest" title="Values stored in PostgreSQL over the last 60 s">
            <span>INGEST</span><b>{summary.data ? fmtNum(summary.data.messagesPerSecond, 1) : "–"}</b><small>values/s</small>
          </div>
          <a className={`cr-state ${state}`} href={BASE} onClick={link(BASE)} title="Active alarms (overview)">
            <i aria-hidden="true">{state === "good" ? "●" : state === "warning" ? "▲" : "✕"}</i>
            {state === "good" ? "ALL SYSTEMS NORMAL" : state === "warning" ? `DEGRADED · ${alarms.length}` : `ATTENTION · ${crit} CRITICAL`}
          </a>
        </div>
        <div className="cr-top-r">
          <div className="cr-clock" aria-label="Local time">
            <b>{hms(now)}</b>
            <span>{new Date(now).toLocaleDateString([], { weekday: "short", day: "2-digit", month: "short" })}</span>
          </div>
          <span className={`cr-live ${live.connected ? "on" : ""}`} role="status">
            <i aria-hidden="true" />{live.connected ? "LIVE" : "RECONNECTING"}
          </span>
          <nav className="cr-links" aria-label="Other screens">
            <a href={bridgePath()} onClick={link(bridgePath())} className={route.page === "bridge" ? "on" : ""}>Bridge Console</a>
          </nav>
          <button className="cr-theme" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
                  aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}>
            {theme === "dark" ? "☀" : "☾"}
          </button>
        </div>
      </header>

      {search && <Palette machines={machines} go={go} onClose={() => setSearch(false)} />}

      {route.page === "machine" ? <MachinePage key={route.id} id={route.id} {...ctx} />
        : route.page === "bridge" ? <Bridge section={route.section} item={route.item} setGuard={setGuard} bumpCfg={bumpCfg} {...ctx} />
        : <Overview {...ctx} />}

      <footer className="cr-ticker" aria-label="Recent batches">
        <span className="tag">BATCHES</span>
        <div className="track">
          <div className="belt">
            {[0, 1].map((copy) => (
              <span key={copy} className="run" aria-hidden={copy === 1}>
                {tickerItems.length === 0 && <span className="it">Waiting for batches…</span>}
                {tickerItems.slice(0, 16).map((b) => (
                  <span key={`${copy}${b.key}`} className="it">
                    <i className={b.via?.startsWith("boomi") ? "p1" : "p2"} />
                    <b>{hms(b.at)}</b> {VIA[b.via] ?? b.via} · {b.n} msgs{b.dup ? ` · ${b.dup} dup` : ""}{b.seq ? ` · seq ${b.seq}` : ""}
                  </span>
                ))}
              </span>
            ))}
          </div>
        </div>
      </footer>
    </div>
  );
}

function Overview({ live, summary: s, summaryError, plugin: p, deliveries, machines, perMachine, alarms, go, link, cfg }) {
  const [selected, setSelected] = useState(null);
  const [win, setWin] = useState(15);

  const numericIds = useMemo(
    () => machines.flatMap((m) => Object.values(m.tags).filter((t) => isNum(t) && good(t)).map((t) => t.tagId)).sort((a, b) => a - b),
    [machines]
  );
  const series = useTagSeries(numericIds, live.onValues, 15);
  const scopeSeries = useTagSeries(selected ? [selected.tagId] : [], live.onValues, win);
  const rate = useRate(live.onValues, 120);

  // default the oscilloscope to the first live numeric tag
  useEffect(() => {
    if (selected) return;
    for (const m of machines) {
      const t = Object.values(m.tags).find((x) => isNum(x) && good(x) && specOf(x.tag).min != null);
      if (t) return setSelected({ tagId: t.tagId, machineId: m.machineId, tag: t.tag, unit: t.unit });
    }
  }, [machines, selected]);

  const quality = useMemo(() => {
    const c = { good: 0, warning: 0, critical: 0 };
    for (const m of machines) for (const t of Object.values(m.tags)) {
      if (t.quality === "GOOD") c.good++;
      else if (t.quality === "UNCERTAIN") c.warning++;
      else c.critical++;
    }
    return c;
  }, [machines]);

  const shownVias = ["boomi-es", "bimba-rest", ...["boomi-rest", "boomi-mqtt", "bimba-mqtt"].filter((v) => (deliveries?.series ?? []).some((r) => r[v] > 0))];
  const scopePts = selected ? scopeSeries.get(selected.tagId) : null;
  const sel = selected && live.machines[selected.machineId]?.tags[selected.tag];
  const stats = scopePts?.length
    ? { min: Math.min(...scopePts.map((x) => x.v)), max: Math.max(...scopePts.map((x) => x.v)), avg: scopePts.reduce((a, x) => a + x.v, 0) / scopePts.length }
    : null;

  return (
    <>
      <section className="cr-hero">
        <div className="cr-panel river">
          <div className="cr-ph">
            <h2>Data river</h2>
            <span className="muted">each dot = one value stored by <code>bimba_ingest()</code> · click a device to open it</span>
          </div>
          <River machines={machines} plugin={p} onValues={live.onValues} stored={s?.measurements} rateByMachine={perMachine}
                 onOpen={(id) => go(machinePath(id))} />
        </div>

        <div className="cr-vitals">
          <div className="cr-panel tile hero">
            <div className="lab">Throughput</div>
            <div className="big">{s ? fmtNum(s.messagesPerSecond, 1) : "–"}<small>values / s</small></div>
            <Spark points={rate} w={260} h={46} cls="s1" min={0} />
            <div className="muted">stored in the last 60 s · sparkline = live feed, last 2 min</div>
          </div>
          <div className="cr-panel tile">
            <div className="lab">Rows in PostgreSQL</div>
            <div className="big odo"><Odometer value={s?.measurements} /></div>
            <div className="muted">{s?.machines ?? "–"} machines · {s?.tags ?? "–"} tags</div>
          </div>
          <div className="cr-panel tile split">
            <div>
              <div className="lab">Agent uptime</div>
              <div className="mid">{p?.reachable ? since(p.startedAt) : "–"}</div>
            </div>
            <div>
              <div className="lab">Disk buffer</div>
              <div className="mid">{p?.delivery?.bufferedBatches ?? "–"}<small> batches</small></div>
            </div>
          </div>
        </div>
      </section>

      <section className="cr-panel cr-sysrow">
        <div className="cr-ph"><h2>Systems</h2><span className="muted">field devices · edge · IT targets · store</span></div>
        <div className="cr-systems">
          {systems(cfg, machines, p, s, summaryError).map((x) => (
            <div key={x.k + x.b} className={`cr-sys ${x.s}`}>
              <span className="k">{x.k}</span>
              <b>{x.b}</b>
              <span className="st">{x.st}</span>
              <span className="d" title={x.d}>{x.d}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="cr-mid">
        <div className="cr-panel">
          <div className="cr-ph"><h2>Tag quality</h2><span className="muted">as reported by Ignition</span></div>
          <QualityRing counts={quality} />
        </div>
        <div className="cr-panel alarms">
          <div className="cr-ph">
            <h2>Active alarms</h2>
            <Badge state={alarms.some((a) => a.s === "critical") ? "critical" : alarms.length ? "warning" : "good"}>
              {alarms.length ? `${alarms.length} active` : "All clear"}
            </Badge>
          </div>
          <AlarmList alarms={alarms} link={link} />
        </div>
        <div className="cr-panel heat">
          <div className="cr-ph"><h2>Delivery heatmap</h2><span className="muted">messages stored per minute · last 60 min</span></div>
          <Heat series={deliveries?.series} rows={shownVias.map((v) => ({ key: v, label: VIA[v] }))} />
        </div>
      </section>

      <section className="cr-assets" aria-label="Assets">
        {machines.length === 0 && <div className="cr-panel empty">Waiting for the first values from the BIMBA IT/OT Agent…</div>}
        {machines.map((m) => (
          <AssetCard key={m.machineId} m={m} kind={m.kind} series={series} selected={selected} onSelect={setSelected}
                     href={machinePath(m.machineId)} onOpen={link(machinePath(m.machineId))} />
        ))}
      </section>

      <section className="cr-panel scope">
        <div className="cr-ph">
          <div>
            <h2>Oscilloscope</h2>
            <span className="muted">{selected ? `${selected.machineId} · ${specOf(selected.tag).label}` : "pick a reading in any asset card"}</span>
          </div>
          <div className="cr-readout">
            <div><span>NOW</span><b>{sel && isNum(sel) ? fmtNum(sel.num) : "–"}</b></div>
            <div><span>MIN</span><b>{stats ? fmtNum(stats.min) : "–"}</b></div>
            <div><span>AVG</span><b>{stats ? fmtNum(stats.avg) : "–"}</b></div>
            <div><span>MAX</span><b>{stats ? fmtNum(stats.max) : "–"}</b></div>
            <div className="u">{unit(selected?.unit)}</div>
          </div>
          <div className="cr-seg" role="group" aria-label="Time window">
            {WINDOWS.map((w) => <button key={w} className={w === win ? "on" : ""} onClick={() => setWin(w)}>{w}m</button>)}
          </div>
        </div>
        <Scope key={`${selected?.tagId}-${win}`} points={scopePts} tag={selected?.tag} u={selected?.unit} />
      </section>
    </>
  );
}

/** Health of every system in the path, derived from tag quality, the plugin status and the API. */
function systems(cfg, machines, p, s, summaryError) {
  const out = [];
  for (const kind of ["modbus", "opcua"]) {
    for (const dev of Object.keys(cfg?.sources?.[kind] ?? {})) {
      const ids = new Set((cfg.points ?? []).filter((x) => x.kind === kind && x.device === dev).map((x) => x.machine));
      const ms = machines.filter((m) => ids.has(m.machineId));
      const tags = ms.flatMap((m) => Object.values(m.tags));
      const ok = tags.filter(good).length;
      const st = !tags.length ? "neutral" : ok === tags.length ? "good" : ok === 0 ? "critical" : "warning";
      const c = cfg.sources[kind][dev];
      out.push({
        k: kind === "modbus" ? "Modbus TCP" : "OPC UA", b: dev, s: st,
        st: { good: "UP", warning: "DEGRADED", critical: "NO SIGNAL", neutral: "NO TAGS" }[st],
        d: `${kind === "modbus" ? `${c.host}:${c.port}` : c.endpoint} · ${ok}/${tags.length} tags GOOD`,
      });
    }
  }
  out.push(p?.reachable
    ? { k: "Edge agent", b: p.edgeId, s: "good", st: "UP", d: `${p.module ?? ""} ${p.version ?? ""} · ${p.collector?.subscribedTags ?? 0} tags` }
    : { k: "Edge agent", b: "Ignition", s: p ? "critical" : "neutral", st: p ? "UNREACHABLE" : "…", d: p?.error ?? "" });
  const TN = { eventstreams: "Boomi Event Streams", kafka: "Kafka", rest: "BIMBA Ingest", mqtt: "MQTT" };
  for (const r of p?.delivery?.routes ?? []) {
    for (const d of r.destinations ?? []) {
      const active = r.activeDestination === d.name;
      const st = d.state === "down" ? "critical" : active ? "good" : d.state === "up" ? "neutral" : "neutral";
      out.push({
        k: `${d.role} · ${d.transport}`, b: TN[d.transport] ?? d.name, s: st,
        st: d.state === "down" ? "DOWN" : active ? "DELIVERING" : d.state === "up" ? "STANDBY · OK" : "STANDBY",
        d: `${(d.messages ?? 0).toLocaleString()} msgs · last ${active ? since(d.lastSuccessAt) : since(d.lastProbeAt ?? d.lastSuccessAt)}`,
      });
    }
  }
  out.push(s && !summaryError
    ? { k: "Store", b: "PostgreSQL", s: "good", st: "UP", d: `${s.measurements.toLocaleString()} rows · schema bimba` }
    : { k: "Store", b: "PostgreSQL", s: summaryError ? "critical" : "neutral", st: summaryError ? "API ERROR" : "…", d: summaryError ?? "" });
  return out;
}
