import { ago } from "./api.js";

/** What the running plugin reports for one destination (from /system/bimba-status). */
export default function RouteState({ plugin, pluginError, name, enabled }) {
  const d = plugin?.delivery?.destinations?.find((x) => x.name === name);
  const route = plugin?.delivery?.routes?.find((r) => r.destinations?.some((x) => x.name === name));
  let status;
  if (!plugin?.reachable) status = { cls: "off", text: `Plugin not reachable${plugin?.error || pluginError ? `: ${plugin?.error ?? pluginError}` : ""}` };
  else if (!d) status = { cls: "off", text: enabled ? "Not running yet (the plugin reloads within ~10 s)" : "Disabled in config.json" };
  else if (d.state === "down") status = { cls: "bad", text: "Down, retrying" };
  else if (route?.activeDestination === name) status = { cls: "", text: "Delivering" };
  else status = { cls: "off", text: "Standby" };

  return (
    <div className="card">
      <div className="card-h">
        <h2>Running in the plugin</h2>
        <span className={`status ${status.cls}`}>{status.text}</span>
      </div>
      {d && (
        <div className="card-b">
          <dl className="facts">
            <div><dt>Route · role</dt><dd>{d.route ?? "main"} · {d.role}</dd></div>
            <div><dt>Endpoint</dt><dd className="mono small">{d.endpoint}</dd></div>
            <div><dt>Delivered</dt><dd>{d.batches.toLocaleString()} batches · {(d.messages ?? 0).toLocaleString()} msgs</dd></div>
            <div><dt>Last success</dt><dd>{ago(d.lastSuccessAt)}</dd></div>
            <div><dt>Buffered on disk</dt><dd>{route?.bufferedBatches ?? 0} batches</dd></div>
            <div><dt>Failures</dt><dd>{d.failures ?? 0}{d.lastFailureAt ? ` · last ${ago(d.lastFailureAt)}` : ""}</dd></div>
            {d.lastError && (
              <div style={{ gridColumn: "1 / -1" }}><dt>Last error</dt><dd className="mono small">{d.lastError}</dd></div>
            )}
          </dl>
        </div>
      )}
    </div>
  );
}
