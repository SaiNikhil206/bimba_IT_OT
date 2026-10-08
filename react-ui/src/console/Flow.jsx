import { useEffect, useMemo, useState } from "react";
import { VIA_LABEL } from "../theme.js";
import { ago, t3 } from "./api.js";
import { fmt, machineList, machineSource, pathKey, pointIndex, rawFor, sourceLine } from "./model.js";
import { Json, Quality, TickCell, useNow } from "./ui.jsx";

const TARGET = { eventstreams: "Boomi Event Streams", kafka: "Kafka", rest: "REST", mqtt: "MQTT", database: "BIMBA database" };

function targetName(d) {
  if (d.transport === "rest" && /\/api\/ingest\/?$/.test(d.endpoint ?? "")) return "BIMBA Ingest";
  return TARGET[d.transport] ?? d.transport;
}

function targetDetail(d) {
  if (d.transport === "kafka") return d.endpoint?.split(" -> ").pop();
  if (d.transport === "eventstreams") return `topic ${decodeURIComponent(d.endpoint?.split("/").pop() ?? "")}`;
  return d.endpoint;
}

export default function Flow({ live, plugin, config }) {
  useNow(1000);
  const ids = machineList(live, config?.points);
  const [sel, setSel] = useState(null);
  // default: the machine that reported most recently
  const freshest = ids.filter((id) => live.machines[id]?.lastSeen)
    .sort((a, b) => (live.machines[b].lastSeen > live.machines[a].lastSeen ? 1 : -1))[0];
  const machine = ids.includes(sel) ? sel : freshest ?? ids[0];
  const [pulse, setPulse] = useState(0);
  const tail = useKafkaTail(machine);

  // animate the arrows whenever a new value for this machine is stored
  const { onValues } = live;
  useEffect(() => {
    if (!machine) return undefined;
    return onValues((items) => {
      if (items.some((i) => i.machineId === machine)) setPulse((n) => n + 1);
    });
  }, [onValues, machine]);

  const idx = useMemo(() => pointIndex(config?.points), [config?.points]);
  const m = live.machines[machine];
  const tags = Object.values(m?.tags ?? {}).sort((a, b) => a.tag.localeCompare(b.tag));
  const src = machineSource(config?.points, machine);
  const latestTime = tags.reduce((t, x) => (x.time && x.time > t ? x.time : t), "");

  const routes = plugin?.delivery?.routes ?? [];
  const targets = routes.flatMap((r) => (r.destinations ?? []).map((d) => ({ ...d, active: r.activeDestination === d.name })));
  const col = plugin?.collector;

  const latest = tail.data?.records?.[0];
  const latestMsg = latest?.messages?.[latest.messages.length - 1];

  const log = useMemo(() => {
    const rows = [];
    for (const r of tail.data?.records ?? []) {
      const newest = Math.max(...r.messages.map((x) => Date.parse(x.timestamp) || 0));
      rows.push({
        key: `k${r.partition}-${r.offset}`, time: r.timestamp, target: "Kafka", pos: `p${r.partition} · offset ${r.offset}`,
        detail: `${r.messages.length} of ${r.count} msgs`, ms: newest ? r.timestamp - newest : null, ack: "Delivered",
      });
    }
    for (const b of live.batches) {
      rows.push({
        key: `b${b.id}`, time: Date.parse(b.at), target: `${VIA_LABEL[b.via] ?? b.via} → DB`,
        pos: b.firstSeq != null ? `seq ${b.firstSeq}–${b.lastSeq}` : "–",
        detail: `${b.received} msgs${b.duplicates ? `, ${b.duplicates} dup` : ""} (all devices)`, ms: null, ack: "Stored",
      });
    }
    return rows.sort((a, b) => b.time - a.time).slice(0, 8);
  }, [tail.data, live.batches]);

  return (
    <>
      <p className="kicker">Live</p>
      <h1>Device data flow</h1>
      <p className="lead">Pick a device to follow its data from the device to Kafka and Boomi Event Streams.</p>
      <div className="pick">
        <label htmlFor="flow-dev" style={{ fontWeight: 500 }}>Device</label>
        <select id="flow-dev" value={machine ?? ""} onChange={(e) => setSel(e.target.value)}>
          {ids.map((id) => (
            <option key={id} value={id}>{id} · {sourceLine(config, machineSource(config?.points, id)).split(" · ")[0]}</option>
          ))}
        </select>
        <span className={`status${live.connected ? "" : " off"}`}>{live.connected ? "Streaming" : "Reconnecting…"}</span>
      </div>

      <div className="flow">
        <div className="node">
          <div className="t">OT · Device</div>
          <div className="n">{machine ?? "–"}</div>
          <div className="d">{sourceLine(config, src)}{src.device ? ` · Ignition ${src.device}` : ""}</div>
        </div>
        <Arrow pulse={pulse} />
        <div className="node">
          <div className="t">Edge gateway</div>
          <div className="n">{plugin?.edgeId ?? config?.edgeId ?? "–"}</div>
          <div className="d">
            {plugin?.reachable
              ? `${plugin.module ?? "BIMBA IT/OT Agent"} ${plugin.version ?? ""} · ${col?.subscribedTags ?? 0} tags · seq ${(col?.lastSeq ?? 0).toLocaleString()}`
              : "Plugin status not reachable"}
          </div>
        </div>
        <Arrow pulse={pulse} delay={450} />
        <div className="targets">
          {targets.length === 0 && <div className="node dim"><div className="t">IT</div><div className="d">No destination running</div></div>}
          {targets.map((d) => (
            <div key={d.name} className={`node${d.active ? "" : " dim"}`}>
              <div className="t">IT · {targetName(d)} · {d.role}</div>
              <div className="mono">{targetDetail(d)}</div>
              <div className="s">
                <span className={`status ${d.state === "down" ? "bad" : d.active ? "" : "off"}`}>
                  {d.state === "down" ? "Down" : d.active ? "Delivering" : "Standby"} · last success {ago(d.lastSuccessAt)}
                </span>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="panes">
        <section className="card">
          <div className="card-h">
            <h2>Emitted by device</h2>
            <span className="mono muted">{latestTime ? t3(latestTime) : "–"}</span>
          </div>
          <div className="tbl">
            <table>
              <thead><tr><th>Data point</th><th className="r">Raw</th><th className="r">Value</th></tr></thead>
              <tbody>
                {tags.length === 0 && <tr><td colSpan={3} className="muted">No values stored for {machine} yet.</td></tr>}
                {tags.map((t) => {
                  const p = idx.get(pathKey(machine, t.tag));
                  return (
                    <tr key={t.tag}>
                      <td>{t.tag}</td>
                      <TickCell flash={t.flash} className="v muted">{rawFor(p, t)}</TickCell>
                      <TickCell flash={t.flash}>
                        {t.quality && t.quality !== "GOOD" ? <Quality q={t.quality} /> : <>{fmt(t)} <span className="muted">{t.unit ?? ""}</span></>}
                      </TickCell>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>

        <section className="card">
          <div className="card-h">
            <h2>Received in IT</h2>
            <span className="muted" style={{ fontSize: 13.5 }}>
              {latest ? `Kafka ${tail.data.topic} · p${latest.partition} @ ${latest.offset}` : "Latest message"}
            </span>
          </div>
          {latestMsg ? (
            <Json value={latestMsg} />
          ) : (
            <p className="empty" style={{ margin: 0 }}>
              {tail.error || tail.data?.error
                ? `Kafka mirror: ${tail.error ?? tail.data.error}`
                : tail.data
                  ? `No message for ${machine} in the last ${tail.data.scanned} Kafka records. Values are sent on change, so a device whose values do not change sends nothing.`
                  : "Reading the Kafka mirror…"}
            </p>
          )}
          <div className="tbl log">
            <table>
              <thead><tr><th>Time</th><th>Target</th><th>Position</th><th>Detail</th><th className="r">Latency</th><th></th></tr></thead>
              <tbody>
                {log.length === 0 && <tr><td colSpan={6} className="muted">Waiting for deliveries…</td></tr>}
                {log.map((e) => (
                  <tr key={e.key}>
                    <td className="mono muted">{t3(e.time)}</td>
                    <td>{e.target}</td>
                    <td className="mono">{e.pos}</td>
                    <td className="muted">{e.detail}</td>
                    <td className="mono r">{e.ms != null ? `${e.ms} ms` : "–"}</td>
                    <td><span className="ack">{e.ack}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </>
  );
}

function Arrow({ pulse, delay = 0 }) {
  return (
    <div className="arrow">
      {pulse > 0 && <span key={pulse} className="dot go" style={{ animationDelay: `${delay}ms` }} />}
    </div>
  );
}

/** Polls the newest Kafka mirror records that contain this machine's messages. */
function useKafkaTail(machine) {
  const [state, setState] = useState({ data: null, error: null });
  useEffect(() => {
    if (!machine) return undefined;
    let alive = true;
    let timer;
    setState({ data: null, error: null });
    const load = async () => {
      try {
        const r = await fetch(`/api/console/kafka-tail/?machine=${encodeURIComponent(machine)}`);
        const data = await r.json();
        if (alive) setState({ data, error: r.ok ? null : data.error ?? `HTTP ${r.status}` });
      } catch (e) {
        if (alive) setState((s) => ({ ...s, error: e.message }));
      }
      if (alive) timer = setTimeout(load, 2500);
    };
    load();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [machine]);
  return state;
}
