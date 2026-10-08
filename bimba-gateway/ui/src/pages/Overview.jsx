import { Link } from "react-router-dom";
import { useSession } from "../session.jsx";
import { Card, Empty, full, Msg, PageHeader, Status, usePoll, Verdict, when } from "../ui.jsx";

// Is the gateway collecting and delivering this organization's machine data? Refreshes every 10 s (background: does not
// keep the session alive). Organization users see their machines, the devices serving them and their pipelines;
// platform admins see the whole gateway.

const PROTOCOL = { modbus: "Modbus TCP", opcua: "OPC UA" };

export default function Overview() {
  const { org } = useSession();
  const { data: st, error } = usePoll("overview", 10_000);
  const whole = st?.scope === "gateway";
  return (
    <>
      <PageHeader title="Overview" subtitle={whole ? `The whole gateway (platform admin view). You are working in ${org.name}.`
        : `${org.name}: its machines' readings in, canonical BIMBA messages out.`} />
      <Msg>{error}</Msg>
      {!st ? <p className="muted">Loading…</p> : <Body st={st} org={org} whole={whole} />}
    </>
  );
}

function Body({ st, org, whole }) {
  const col = st.collector || { devices: [], machines: [] };
  const del = st.delivery || { destinations: [], pipelines: [] };
  const pipes = del.pipelines ?? [];
  const idle = pipes.filter((p) => !p.activeDestination);
  const down = col.devices.filter((d) => !d.connected);
  const [kind, text] = st.configError ? ["bad", `Collection stopped: ${st.configError}`]
    : !whole && !(col.machines || []).length ? ["warn", `No machines are assigned to ${org.name} yet. A platform admin assigns them under Organizations.`]
    : down.length ? ["warn", `${down.length} of ${col.devices.length} device connections are down`]
    : !pipes.length ? ["warn", "No pipeline is active: machine data is not delivered anywhere"]
    : idle.length ? ["bad", `${idle.map((p) => p.name).join(", ")}: no destination is accepting data; batches are kept on disk`]
    : ["good", `Collecting from ${col.devices.length} device connection(s), delivering through ${pipes.length} pipeline${pipes.length === 1 ? "" : "s"}`];
  const stats = [
    ["Tags", col.subscribedTags ?? "–"],
    ["Machines", (col.machines || []).length],
    whole ? ["Messages sent", (col.lastSeq || 0).toLocaleString()] : ["Readings delivered", (del.messagesDelivered || 0).toLocaleString()],
    ["Batches on disk", del.bufferedBatches ?? 0],
  ];
  return (
    <>
      <Verdict kind={kind} title={text} />
      <div className="stats">
        {stats.map(([l, v]) => <div className="stat" key={l}><div className="l">{l}</div><div className="v">{v}</div></div>)}
      </div>
      <Card title="Devices" flush>
        <table>
          <thead><tr><th>Device</th><th>Protocol</th><th>Address</th><th>Status</th><th>Tags OK</th><th>Last good read</th></tr></thead>
          <tbody>
            {col.devices.length === 0 && <Empty cols={6}>{whole ? "No tags configured. Add them in BIMBA Operations → Settings." : `No device serves ${org.name}'s machines.`}</Empty>}
            {col.devices.map((d) => (
              <tr key={`${d.kind}-${d.name}`}>
                <td><b>{d.name}</b></td>
                <td>{PROTOCOL[d.kind] ?? "Expression"}</td>
                <td className="mono nowrap">{d.target}</td>
                <td><Status kind={d.connected ? "good" : "bad"}>{d.connected ? "Connected" : "Down"}</Status>{d.error && <small>{d.error}</small>}</td>
                <td>{d.goodTags} / {d.tags}</td>
                <td className="muted" title={full(d.lastOk)}>{when(d.lastOk)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      <Card title="Pipelines" flush action={<Link className="btn ghost sm" to="/pipelines">Manage pipelines</Link>}>
        <table>
          <thead><tr><th>Pipeline</th><th>Machines</th><th>Delivering to</th><th>Mirrors</th><th>On disk</th></tr></thead>
          <tbody>
            {pipes.length === 0 && <Empty cols={5}>No active pipeline. Add destinations, then create and activate a pipeline.</Empty>}
            {pipes.map((p) => {
              const main = (p.routes ?? []).find((r) => r.kind === "main");
              const primary = main?.destinations?.[0]?.name;
              const mirrors = (p.routes ?? []).filter((r) => r.kind === "mirror");
              return (
                <tr key={p.id}>
                  <td><b>{p.name}</b></td>
                  <td>{p.machines?.length ? p.machines.join(", ") : "All"}</td>
                  <td>
                    <Status kind={!p.activeDestination ? "bad" : p.activeDestination === primary ? "good" : "warn"}>
                      {p.activeDestination ?? "Nothing accepts data"}
                    </Status>
                    {p.activeDestination && p.activeDestination !== primary && <small>Secondary: {primary} is down</small>}
                  </td>
                  <td>{mirrors.length ? mirrors.map((r) => {
                    const d = r.destinations?.[0];
                    return <div key={r.name}><Status kind={d?.state === "up" ? "good" : d?.state === "down" ? "bad" : ""}>{d?.name}</Status></div>;
                  }) : <span className="muted">–</span>}</td>
                  <td>{(p.bufferedBatches ?? 0).toLocaleString()}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
      <Card title="Gateway">
        <dl className="kv">
          <dt>Plant / edge id</dt><dd>{st.plantId} / {st.edgeId}</dd>
          <dt>Running since</dt><dd>{full(st.startedAt)}</dd>
          {whole && <><dt>Configuration</dt><dd className="mono">{st.configFile}</dd></>}
          <dt>Destinations</dt><dd>{st.destinationSource === "database" ? "BIMBA database (managed in Delivery)" : st.destinationSource}</dd>
          {(st.destinationProblems || []).length > 0 && <><dt>Delivery problems</dt><dd>{st.destinationProblems.join("; ")}</dd></>}
          {whole && <><dt>Tag tree problems</dt><dd>{(st.tagProblems || []).length ? st.tagProblems.join("; ") : "none"}</dd></>}
          <dt>HTTPS certificate SHA-256</dt><dd className="mono">{st.tlsFingerprint || "HTTPS disabled"}</dd>
        </dl>
        <p className="muted small">Site agent installers that show a certificate warning must show exactly this fingerprint.</p>
      </Card>
    </>
  );
}
