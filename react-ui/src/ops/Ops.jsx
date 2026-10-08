import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLive, usePoll } from "../useLive.js";
import { assetKind, computeAlarms, fmtNum, good, isBool, specOf, unit, useConsoleConfig } from "./data.js";
import Overview from "./Overview.jsx";
import { MachineDetail, MachineList } from "./Machines.jsx";
import Flow from "./Flow.jsx";
import Alarms from "./Alarms.jsx";
import Settings from "./Settings.jsx";
import Agents from "./Agents.jsx";
import { BASE, to } from "./paths.js";
import "./ops.css";

// BIMBA Operations (/operations): the calm version of the Control Room. Same data and features,
// one question per screen, details on demand. Read-only except Settings (same API as /console).
//   /operations                       overview: is everything OK?
//   /operations/machines[/<id>[/tab]] machines and one machine's dashboard
//   /operations/flow                  is data getting through?
//   /operations/alarms                what needs attention?
//   /operations/settings[/<kind>[/<item>]]  field connections and destinations
//   /operations/agents[/install]      site agents of the signed-in organization (private per organization)


const NAV = [
  { key: "overview", label: "Overview", icon: "◎" },
  { key: "machines", label: "Machines", icon: "▦" },
  { key: "flow", label: "Data flow", icon: "⇢" },
  { key: "alarms", label: "Alarms", icon: "△" },
  { key: "agents", label: "Site agents", icon: "⌂" },
  { key: "settings", label: "Settings", icon: "⚙" },
];

function routeOf(pathname) {
  const parts = pathname.replace(/^\/operations\/?/, "").split("/").filter(Boolean).map(decodeURIComponent);
  return { page: parts[0] || "overview", a: parts[1] ?? null, b: parts[2] ?? null };
}

function loadTheme() {
  try {
    return localStorage.getItem("bimba.ops.theme") === "dark" ? "dark" : "light";
  } catch {
    return "light";
  }
}

export default function Ops() {
  const live = useLive();
  const summary = usePoll("/api/summary/", 5000);
  const plugin = usePoll("/api/plugin-status/", 4000);
  const deliveries = usePoll("/api/deliveries/?minutes=60", 15000);
  const [cfgVer, setCfgVer] = useState(0);
  const cfg = useConsoleConfig(cfgVer);
  const bumpCfg = useCallback(() => setCfgVer((n) => n + 1), []);
  const [route, setRoute] = useState(() => routeOf(window.location.pathname));
  const [theme, setTheme] = useState(loadTheme);
  const guard = useRef(null);
  const here = useRef(window.location.pathname);
  const setGuard = useCallback((msg) => {
    guard.current = msg;
  }, []);

  useEffect(() => {
    if (!document.getElementById("op-font")) {
      const l = document.createElement("link");
      l.id = "op-font";
      l.rel = "stylesheet";
      l.href = "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap";
      document.head.appendChild(l);
    }
    document.body.classList.add("op-body");
    const on = () => {
      if (guard.current && !window.confirm(guard.current)) {
        window.history.pushState(null, "", here.current);
        return;
      }
      here.current = window.location.pathname;
      setRoute(routeOf(window.location.pathname));
    };
    window.addEventListener("popstate", on);
    return () => {
      document.body.classList.remove("op-body");
      window.removeEventListener("popstate", on);
    };
  }, []);

  useEffect(() => {
    document.body.dataset.opTheme = theme;
    try {
      localStorage.setItem("bimba.ops.theme", theme);
    } catch {
      /* not remembered */
    }
  }, [theme]);

  const go = useCallback((path) => {
    if (path === window.location.pathname) return;
    if (guard.current && !window.confirm(guard.current)) return;
    guard.current = null;
    window.history.pushState(null, "", path);
    here.current = path;
    setRoute(routeOf(path));
    window.scrollTo({ top: 0 });
  }, []);
  const link = useCallback((path) => (e) => {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    go(path);
  }, [go]);

  const machines = useMemo(
    () =>
      Object.values(live.machines)
        .map((m) => {
          const tags = Object.values(m.tags);
          const bad = tags.filter((t) => !good(t)).length;
          return { ...m, kind: assetKind(m), health: tags.length && bad === tags.length ? "critical" : bad ? "warning" : "good" };
        })
        .sort((a, b) => (a.plantId === "BOOMI-TEST") - (b.plantId === "BOOMI-TEST") || a.machineId.localeCompare(b.machineId)),
    [live.machines]
  );
  const alarms = useMemo(() => computeAlarms(plugin.data, machines), [plugin.data, machines]);
  const crit = alarms.filter((a) => a.s === "critical").length;
  const overall = crit ? "critical" : alarms.length ? "warning" : "good";

  const page = NAV.some((n) => n.key === route.page) ? route.page : "overview";
  const title = { overview: "Overview", machines: route.a ?? "Machines", flow: "Data flow", alarms: "Alarms", agents: "Site agents", settings: "Settings" }[page];
  useEffect(() => {
    document.title = `${title} · BIMBA Operations`;
  }, [title]);

  const ctx = {
    live, summary: summary.data, summaryError: summary.error, plugin: plugin.data, deliveries: deliveries.data,
    machines, alarms, overall, cfg, bumpCfg, go, link, setGuard,
  };

  return (
    <div className="op" data-theme={theme}>
      <aside className="op-side">
        <a className="op-brand" href={BASE} onClick={link(BASE)}>
          <span className="mark" aria-hidden="true">B</span>
          <span><b>BIMBA</b> Operations<small>{plugin.data?.plantId ?? cfg?.plantId ?? "Plant"}</small></span>
        </a>
        <nav className="op-nav" aria-label="Main">
          {NAV.map((n) => (
            <a key={n.key} href={to(n.key === "overview" ? "" : n.key)} onClick={link(to(n.key === "overview" ? "" : n.key))}
               className={page === n.key ? "on" : ""} aria-current={page === n.key ? "page" : undefined}>
              <span className="ic" aria-hidden="true">{n.icon}</span>
              {n.label}
              {n.key === "alarms" && alarms.length > 0 && <span className={`badge ${overall}`}>{alarms.length}</span>}
            </a>
          ))}
        </nav>
        <div className="op-side-f">
          <span className={`op-live ${live.connected ? "on" : ""}`}><i aria-hidden="true" />{live.connected ? "Live data" : "Reconnecting…"}</span>
          <button className="op-icon-btn" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
                  aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}>{theme === "dark" ? "☀" : "☾"}</button>
        </div>
      </aside>

      <div className="op-main">
        <div className="op-top">
          <Search machines={machines} go={go} />
          <a className={`op-overall ${overall}`} href={to("alarms")} onClick={link(to("alarms"))}>
            {overall === "good" ? "All systems normal" : `${alarms.length} ${alarms.length === 1 ? "issue" : "issues"} need attention`}
          </a>
        </div>
        <main className="op-page">
          {page === "overview" && <Overview {...ctx} />}
          {page === "machines" && (route.a ? <MachineDetail key={route.a} id={route.a} tab={route.b ?? "overview"} {...ctx} /> : <MachineList {...ctx} />)}
          {page === "flow" && <Flow {...ctx} />}
          {page === "alarms" && <Alarms {...ctx} />}
          {page === "settings" && <Settings kind={route.a} item={route.b} {...ctx} />}
          {page === "agents" && <Agents tab={route.a} {...ctx} />}
        </main>
      </div>
    </div>
  );
}

/** Search box: machines and readings, Enter opens the machine. Ctrl+K or / focuses it. */
function Search({ machines, go }) {
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const [sel, setSel] = useState(0);
  const input = useRef(null);
  useEffect(() => {
    const on = (e) => {
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target?.tagName ?? "");
      if (((e.key === "k" || e.key === "K") && (e.ctrlKey || e.metaKey)) || (e.key === "/" && !typing)) {
        e.preventDefault();
        input.current?.focus();
      }
    };
    window.addEventListener("keydown", on);
    return () => window.removeEventListener("keydown", on);
  }, []);
  const items = useMemo(() => {
    const n = q.trim().toLowerCase();
    if (!n) return [];
    const out = [];
    for (const m of machines) {
      if (m.machineId.toLowerCase().includes(n)) out.push({ key: m.machineId, m, title: m.machineId, sub: "Machine" });
      for (const t of Object.values(m.tags)) {
        const label = specOf(t.tag).label;
        if (label.toLowerCase().includes(n) || t.tag.includes(n)) {
          const v = !good(t) ? t.quality : isBool(t) ? (t.num ? "On" : "Off") : t.num != null ? `${fmtNum(t.num)} ${unit(t.unit)}` : t.text;
          out.push({ key: `${m.machineId}/${t.tag}`, m, title: label, sub: m.machineId, val: v });
        }
      }
    }
    return out.slice(0, 8);
  }, [q, machines]);
  const pick = (it) => {
    setQ("");
    setOpen(false);
    input.current?.blur();
    go(to("machines", it.m.machineId));
  };
  return (
    <div className="op-search">
      <input ref={input} value={q} placeholder="Search machines and readings" aria-label="Search machines and readings"
             onChange={(e) => { setQ(e.target.value); setSel(0); setOpen(true); }}
             onFocus={() => setOpen(true)} onBlur={() => setTimeout(() => setOpen(false), 150)}
             onKeyDown={(e) => {
               if (e.key === "ArrowDown") { e.preventDefault(); setSel((s) => Math.min(items.length - 1, s + 1)); }
               else if (e.key === "ArrowUp") { e.preventDefault(); setSel((s) => Math.max(0, s - 1)); }
               else if (e.key === "Enter" && items[sel]) pick(items[sel]);
               else if (e.key === "Escape") { setQ(""); e.currentTarget.blur(); }
             }} />
      <kbd>Ctrl K</kbd>
      {open && q && (
        <ul className="op-search-r" role="listbox">
          {items.length === 0 && <li className="none">No match for “{q}”</li>}
          {items.map((it, i) => (
            <li key={it.key} role="option" aria-selected={i === sel}>
              <button className={i === sel ? "on" : ""} onMouseDown={(e) => e.preventDefault()} onClick={() => pick(it)} onMouseEnter={() => setSel(i)}>
                <span><b>{it.title}</b><small>{it.sub}</small></span>
                {it.val && <span className="v">{it.val}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
