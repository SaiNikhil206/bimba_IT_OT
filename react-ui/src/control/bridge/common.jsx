import { good, since } from "../lib.js";
import { machinePath } from "../shared.jsx";
import { Badge } from "../viz.jsx";

// Helpers shared by the Bridge Console pages.

export const bridgePath = (section = "", item = "") =>
  `/control-room/bridge${section ? `/${section}` : ""}${item ? `/${encodeURIComponent(item)}` : ""}`;

/** Health of the machines behind one OT connection. */
export function sourceHealth(config, machines, kind, device) {
  const ids = [...new Set(config.points.filter((p) => p.kind === kind && p.device === device).map((p) => p.machine))];
  const ms = ids.map((id) => machines.find((m) => m.machineId === id)).filter(Boolean);
  const tags = ms.flatMap((m) => Object.values(m.tags));
  const ok = tags.filter(good).length;
  const state = !tags.length ? "neutral" : ok === tags.length ? "good" : ok ? "warning" : "critical";
  return { ids, ms, ok, total: tags.length, state };
}

export function MachineChips({ ids, machines, link }) {
  return (
    <div className="bx-mchips">
      {ids.map((id) => {
        const m = machines.find((x) => x.machineId === id);
        return (
          <a key={id} href={machinePath(id)} onClick={link(machinePath(id))} className={`chip ${m?.health ?? "neutral"}`}>
            <i aria-hidden="true" />{id}
          </a>
        );
      })}
      {ids.length === 0 && <span className="muted">no machine</span>}
    </div>
  );
}

/** What the running plugin reports for one destination. */
export function RouteState({ plugin, name, enabled }) {
  const d = plugin?.delivery?.destinations?.find((x) => x.name === name);
  const route = plugin?.delivery?.routes?.find((r) => r.destinations?.some((x) => x.name === name));
  let st;
  if (!plugin?.reachable) st = ["critical", `Plugin not reachable${plugin?.error ? `: ${plugin.error}` : ""}`];
  else if (!d) st = ["neutral", enabled ? "Not running yet (the plugin reloads within ~10 s)" : "Disabled in config.json"];
  else if (d.state === "down") st = ["critical", "Down, retrying"];
  else if (route?.activeDestination === name) st = ["good", "Delivering"];
  else st = ["neutral", d.lastProbeAt ? `Standby · checked ${since(d.lastProbeAt)} ago` : "Standby"];
  return (
    <section className="cr-panel">
      <div className="cr-ph"><h2>Running in the plugin</h2><Badge state={st[0]}>{st[1]}</Badge></div>
      {d && (
        <dl className="bx-facts">
          <div><dt>Route · role</dt><dd>{d.route ?? "main"} · {d.role}</dd></div>
          <div><dt>Delivered</dt><dd>{d.batches.toLocaleString()} batches · {(d.messages ?? 0).toLocaleString()} msgs</dd></div>
          <div><dt>Last success</dt><dd>{d.lastSuccessAt ? `${since(d.lastSuccessAt)} ago` : "never"}</dd></div>
          <div><dt>Buffered on disk</dt><dd>{route?.bufferedBatches ?? 0} batches</dd></div>
          <div><dt>Failures</dt><dd>{d.failures ?? 0}{d.lastFailureAt ? ` · last ${since(d.lastFailureAt)} ago` : ""}</dd></div>
          <div className="wide"><dt>Endpoint</dt><dd className="mono">{d.endpoint}</dd></div>
          {d.lastError && <div className="wide"><dt>Last error</dt><dd className="mono bad">{d.lastError}</dd></div>}
        </dl>
      )}
    </section>
  );
}
