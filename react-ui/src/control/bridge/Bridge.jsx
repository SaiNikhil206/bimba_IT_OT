import { useCallback, useEffect, useMemo, useState } from "react";
import { api, clone, same } from "../../console/api.js";
import { since } from "../lib.js";
import { Badge } from "../viz.jsx";
import { MachineChips, bridgePath, sourceHealth } from "./common.jsx";
import { Modbus, OpcUa } from "./Sources.jsx";
import { EventStreams, Kafka } from "./Targets.jsx";
import "./bridge.css";

// Bridge Console in the Control Room design: /control-room/bridge[/<section>[/<item>]].
// Same backend API and save semantics as the OT/IT Bridge Console at /console (config.json,
// demo-tags.json, console-sources.json; the plugin hot-reloads), shown in the new design.

const TABS = [
  { key: "", name: "Overview", group: "Bridge" },
  { key: "modbus", name: "Modbus TCP", group: "OT sources" },
  { key: "opcua", name: "OPC UA", group: "OT sources" },
  { key: "eventstreams", name: "Boomi Event Streams", group: "IT targets" },
  { key: "kafka", name: "Kafka", group: "IT targets" },
];

// the part of the config each page edits (dirty tracking + keeping other pages' drafts after a save)
const SECTION = {
  modbus: (c) => [c.sources.modbus, c.points.filter((p) => p.kind === "modbus")],
  opcua: (c) => [c.sources.opcua, c.points.filter((p) => p.kind === "opcua")],
  kafka: (c) => c.kafka,
  eventstreams: (c) => c.eventstreams,
};

export default function Bridge({ section, item, live, plugin, machines, link, setGuard, bumpCfg }) {
  const [config, setConfig] = useState(null);
  const [draft, setDraft] = useState(null);
  const [loadError, setLoadError] = useState(null);

  const reload = useCallback(async ({ keepDraftFor } = {}) => {
    try {
      const c = await api("config/");
      setConfig(c);
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
      if (keepDraftFor) bumpCfg();
    } catch (e) {
      setLoadError(e.message);
    }
  }, [bumpCfg]);

  useEffect(() => {
    reload();
  }, [reload]);
  useEffect(() => {
    if (!loadError) return undefined;
    const t = setTimeout(() => reload(), 5000);
    return () => clearTimeout(t);
  }, [loadError, reload]);

  const dirty = useMemo(() => {
    const out = {};
    if (config && draft) for (const [k, f] of Object.entries(SECTION)) out[k] = !same(f(config), f(draft));
    return out;
  }, [config, draft]);
  const anyDirty = Object.values(dirty).some(Boolean);

  // warn before leaving with unsaved edits (in-page navigation and closing the tab)
  useEffect(() => {
    setGuard(anyDirty ? "The Bridge Console has unsaved changes. Leave and discard them?" : null);
    const on = (e) => {
      if (anyDirty) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", on);
    return () => window.removeEventListener("beforeunload", on);
  }, [anyDirty, setGuard]);
  useEffect(() => () => setGuard(null), [setGuard]);

  const tab = TABS.find((t) => t.key === (section ?? "")) ?? TABS[0];
  const ctx = { config, draft, setDraft, reload, dirty: dirty[tab.key], live, plugin, machines, link, item };
  const status = targetStatus(plugin);

  return (
    <div className="bx">
      <section className="cr-panel bx-head">
        <div>
          <div className="k">OT/IT bridge · edge gateway {plugin?.edgeId ?? config?.edgeId ?? "…"}{config?.plantId ? ` · ${config.plantId}` : ""}</div>
          <h2>Bridge Console</h2>
          <p className="muted">
            Connect field devices to IT targets. Changes are saved to the BIMBA IT/OT Agent's configuration; the plugin
            reloads them within about 10 s, without a gateway restart.
          </p>
        </div>
        <nav className="bx-tabs" aria-label="Bridge sections">
          {TABS.map((t, i) => (
            <span key={t.key || "hub"} className="bx-tab-w">
              {(i === 0 || TABS[i - 1].group !== t.group) && <span className="grp">{t.group}</span>}
              <a href={bridgePath(t.key)} onClick={link(bridgePath(t.key))} className={`bx-tab${t.key === tab.key ? " on" : ""}`}
                 aria-current={t.key === tab.key ? "page" : undefined}>
                {t.key && <i className={`dot ${status[t.key] ?? "neutral"}`} aria-hidden="true" />}
                {t.name}
                {dirty[t.key] && <span className="unsaved" title="Unsaved changes">●</span>}
              </a>
            </span>
          ))}
        </nav>
      </section>

      {loadError && (
        <p className="cr-panel bx-note bad" role="alert">
          <b>Could not load the plugin configuration.</b> {loadError} · retrying every 5 s{" "}
          <button className="bx-btn ghost" onClick={() => reload()}>Retry now</button>
        </p>
      )}
      {!config || !draft ? (
        !loadError && <p className="muted">Loading configuration…</p>
      ) : tab.key === "modbus" ? <Modbus {...ctx} />
        : tab.key === "opcua" ? <OpcUa {...ctx} />
        : tab.key === "kafka" ? <Kafka {...ctx} />
        : tab.key === "eventstreams" ? <EventStreams {...ctx} />
        : <Hub {...ctx} />}
    </div>
  );
}

/** Tab dots: OT sources from tag quality, IT targets from the plugin's destination state. */
function targetStatus(plugin) {
  const out = {};
  for (const d of plugin?.delivery?.destinations ?? []) {
    const k = d.transport === "eventstreams" ? "eventstreams" : d.transport === "kafka" ? "kafka" : null;
    if (k) out[k] = d.state === "down" ? "critical" : d.state === "up" ? "good" : "neutral";
  }
  return out;
}

// ---------------------------------------------------------------- overview

function Hub({ config, plugin, machines, link }) {
  const sources = [
    ...Object.entries(config.sources.modbus ?? {}).map(([dev, c]) => ({ kind: "modbus", dev, c })),
    ...Object.entries(config.sources.opcua ?? {}).map(([dev, c]) => ({ kind: "opcua", dev, c })),
  ];
  const expr = [...new Set(config.points.filter((p) => p.kind === "expression").map((p) => p.machine))];
  const dests = (plugin?.delivery?.routes ?? []).flatMap((r) => (r.destinations ?? []).map((d) => ({ ...d, active: r.activeDestination === d.name })));
  const byTransport = (t) => dests.find((d) => d.transport === t);
  const ingest = dests.find((d) => /\/api\/ingest\/?$/.test(d.endpoint ?? ""));

  const targets = [
    { key: "eventstreams", name: "Boomi Event Streams", role: config.eventstreams?.role ?? "primary", d: byTransport("eventstreams"),
      detail: config.eventstreams?.url?.split("/").slice(-1)[0] ? `topic ${decodeURIComponent(config.eventstreams.url.split("/").pop())}` : "not configured",
      enabled: config.eventstreams?.enabled, test: () => api("test/eventstreams/", { method: "POST", body: { ...config.eventstreams, token: null } }) },
    { key: null, name: "BIMBA Ingest", role: "failover", d: ingest, detail: ingest?.endpoint ?? "http://django:8000/api/ingest/", enabled: !!ingest,
      note: "REST failover · set in config.json" },
    { key: "kafka", name: "Kafka", role: "mirror", d: byTransport("kafka"), detail: `${config.kafka?.bootstrapServers ?? "–"} → ${(config.kafka?.topic ?? "").replaceAll("{plantId}", config.plantId ?? "")}`,
      enabled: config.kafka?.enabled, test: () => api("test/kafka/", { method: "POST", body: { ...config.kafka } }) },
  ];

  return (
    <>
      <section className="bx-route cr-panel" aria-label="Routing">
        <div className="bx-route-g">
          <div className="st"><span className="k">OT sources</span><b>{sources.length + (expr.length ? 1 : 0)}</b><span className="muted">connections</span></div>
          <div className="ar" aria-hidden="true" />
          <div className="st"><span className="k">Edge agent</span><b>{plugin?.reachable ? plugin.edgeId : "unreachable"}</b><span className="muted">{plugin?.collector?.subscribedTags ?? 0} tags · seq {(plugin?.collector?.lastSeq ?? 0).toLocaleString()}</span></div>
          <div className="ar" aria-hidden="true" />
          <div className="st"><span className="k">Main route</span><b>{(plugin?.delivery?.routes ?? []).find((r) => (r.kind ?? r.name) === "main")?.activeDestination ?? "buffering"}</b><span className="muted">primary → failover</span></div>
          <div className="ar" aria-hidden="true" />
          <div className="st"><span className="k">Mirror routes</span><b>{(plugin?.delivery?.routes ?? []).filter((r) => (r.kind ?? r.name) !== "main").map((r) => r.name).join(", ") || "none"}</b><span className="muted">own retry + disk buffer</span></div>
        </div>
      </section>

      <h3 className="bx-sec">OT sources <span>field devices Ignition reads</span></h3>
      <div className="bx-cards">
        {sources.map(({ kind, dev, c }) => {
          const h = sourceHealth(config, machines, kind, dev);
          const n = config.points.filter((p) => p.kind === kind && p.device === dev).length;
          const test = () => api(`test/${kind}/`, {
            method: "POST",
            body: kind === "modbus"
              ? { connection: c, points: config.points.filter((p) => p.kind === "modbus" && p.device === dev).map((p) => ({ path: p.path, address: p.address })) }
              : { device: dev, connection: { ...c, password: null }, points: config.points.filter((p) => p.kind === "opcua" && p.device === dev).map((p) => ({ path: p.path, node: p.node })) },
          });
          return (
            <Card key={kind + dev} kind={kind} title={dev} badge={kind === "modbus" ? "MODBUS TCP" : "OPC UA"} state={h.state}
                  stateText={{ good: "Reading", warning: "Degraded", critical: "No signal", neutral: "No data" }[h.state]}
                  lines={[kind === "modbus" ? `${c.host}:${c.port} · unit ${c.unitId}` : c.endpoint,
                          `${n} ${kind === "modbus" ? "registers" : "nodes"} · ${h.ok}/${h.total} tags GOOD`]}
                  footer={<MachineChips ids={h.ids} machines={machines} link={link} />}
                  test={test} href={bridgePath(kind, dev)} link={link} />
          );
        })}
        {expr.length > 0 && (
          <Card kind="expression" title="Ignition expressions" badge="GATEWAY" state="good" stateText="Computed"
                lines={["Computed inside the gateway", "no field device to configure"]}
                footer={<MachineChips ids={expr} machines={machines} link={link} />} />
        )}
      </div>

      <h3 className="bx-sec">IT targets <span>where the agent delivers every batch</span></h3>
      <div className="bx-cards">
        {targets.map((t) => {
          const st = !t.enabled ? "neutral" : t.d?.state === "down" ? "critical" : t.d?.active ? "good" : t.d ? "neutral" : "neutral";
          const txt = !t.enabled ? "Disabled" : t.d?.state === "down" ? "Down" : t.d?.active ? "Delivering" : t.d ? "Standby" : "Not running";
          return (
            <Card key={t.name} kind={t.key ?? "rest"} title={t.name} badge={t.role.toUpperCase()} state={st} stateText={txt}
                  lines={[t.detail, t.d ? `${(t.d.messages ?? 0).toLocaleString()} msgs · last ${t.d.active ? since(t.d.lastSuccessAt) : since(t.d.lastProbeAt ?? t.d.lastSuccessAt)}` : t.note ?? "–"]}
                  test={t.test} href={t.key ? bridgePath(t.key) : null} link={link}
                  footer={t.note && <span className="muted">{t.note}</span>} />
          );
        })}
      </div>
    </>
  );
}

function Card({ kind, title, badge, state, stateText, lines, footer, test, href, link }) {
  const [res, setRes] = useState(null);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true);
    setRes(null);
    try {
      const r = await test();
      setRes({ ok: r?.ok !== false, text: r?.message ?? "OK" });
    } catch (e) {
      setRes({ ok: false, text: e.message });
    } finally {
      setBusy(false);
    }
  };
  return (
    <article className={`cr-panel bx-card ${state}`}>
      <header>
        <div>
          <span className={`proto ${kind}`}>{badge}</span>
          <h4>{title}</h4>
        </div>
        <Badge state={state}>{stateText}</Badge>
      </header>
      {lines.map((l, i) => <div key={i} className={i === 0 ? "mono l1" : "muted"}>{l}</div>)}
      {footer && <div className="ft">{footer}</div>}
      {(test || href) && (
        <div className="acts">
          {test && <button className="bx-btn" onClick={run} disabled={busy}>{busy ? <span className="spin" aria-hidden="true" /> : "⚡"} Test</button>}
          {href && <a className="bx-btn primary" href={href} onClick={link(href)}>Configure →</a>}
        </div>
      )}
      {res && <div className={`bx-result ${res.ok ? "ok" : "bad"}`}><b>{res.ok ? "✓" : "✕"}</b> {res.text}</div>}
    </article>
  );
}
