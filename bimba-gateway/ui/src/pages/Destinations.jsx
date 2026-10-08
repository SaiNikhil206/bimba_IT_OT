import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Empty, full, Modal, Msg, PageHeader, PasswordInput, Status, useOrgPath, usePoll, useRecordAddress, useToast, when } from "../ui.jsx";
import { CATEGORIES, SERVICES, serviceOf } from "./services.js";

// Destinations: connections to the systems that can receive machine data (Boomi, Kafka, Splunk, ...), stored in the
// BIMBA database. A destination gets data only when a pipeline uses it (Pipelines page). Everyone with gateway.view
// sees them; delivery.manage adds them from the service catalog, tests, edits and removes them.

const ROLE = { primary: "Primary", secondary: "Secondary", mirror: "Mirror" };
const TRANSPORT = { eventstreams: "Event Streams REST", rest: "HTTP", mqtt: "MQTT", kafka: "Kafka", database: "Built in" };
const SECRET_KEY = /authorization|api[-_.]?key|token|secret|password|passwd|jaas|credential/i;

export default function Destinations() {
  const { can } = useSession();
  const toast = useToast();
  const { data, error, reload } = usePoll("destinations", 10_000);
  const [adding, setAdding] = useState(null);       // the service picked for a new destination (/destinations/new)
  const [confirm, setConfirm] = useState(null);     // { title, subtitle, confirm, danger, action, done }
  const manage = can("delivery.manage");
  const list = data?.destinations ?? [];
  const unused = list.filter((d) => !d.usedBy.length).length;
  const at = useOrgPath();
  const rec = useRecordAddress(at("/destinations"), data ? list : undefined, "destination");
  const shown = manage && data ? rec.record : null;   // null | "new" | destination
  const picking = shown === "new" && !adding;
  const editing = shown === "new" ? (adding ? { service: adding } : null) : shown ? { service: serviceOf(shown), d: shown } : null;
  const close = () => {
    setAdding(null);
    rec.close();
  };

  return (
    <>
      <PageHeader title="Destinations" subtitle="Connections to the systems that can receive machine data. A destination gets data only when a pipeline uses it.">
        {manage && <button className="btn" onClick={() => rec.open("new")} disabled={!data}>Add destination</button>}
      </PageHeader>
      <Msg>{error}</Msg>
      {data?.source && data.source !== "database" && (
        <Msg kind="warn">The database is not reachable: the gateway delivers with its {data.source}. Changes are possible again when the database is back.</Msg>
      )}
      {(data?.problems ?? []).map((p) => <Msg key={p}>{p}</Msg>)}
      <Card flush>
        <table className="dest">
          <thead><tr><th>Destination</th><th>Service</th><th>Used by</th><th>Status</th><th>Messages</th><th>Last success</th>{manage && <th className="r" />}</tr></thead>
          <tbody>
            {!data && <Empty cols={7}>Loading…</Empty>}
            {data && list.length === 0 && (
              <Empty cols={7}>No destinations yet.{manage ? " Add the systems that should receive data, then use them in a pipeline." : ""}</Empty>
            )}
            {list.map((d) => <Row key={d.id} d={d} mark={rec.mark(d)} manage={manage} onEdit={() => rec.open(d)} />)}
          </tbody>
        </table>
      </Card>
      <p className="muted small">
        {unused > 0 ? `${unused} destination${unused === 1 ? " is" : "s are"} not used by any pipeline and receive${unused === 1 ? "s" : ""} no data. ` : ""}
        Choose primaries, secondaries and mirrors on the <Link to={at("/pipelines")}>Pipelines</Link> page. Secrets are stored encrypted.
      </p>

      {picking && (
        <ServicePicker added={list.map((d) => serviceOf(d).key)} onClose={close} onPick={setAdding} />
      )}
      {editing && (
        <DestinationDialog d={editing.d} service={editing.service} mask={data.mask} names={list.map((x) => x.name)}
                           onClose={close}
                           onBack={editing.d ? null : () => setAdding(null)}
                           onSaved={(name, isNew) => {
                             close();
                             toast(isNew ? `${name} added. It receives data once a pipeline uses it.` : `${name} saved.`);
                             reload();
                           }}
                           onDelete={(d) => { close(); setConfirm({
                             title: `Remove ${d.name}?`, confirm: "Remove", danger: true, done: `${d.name} removed.`,
                             subtitle: d.usedBy.length ? `It is used by ${[...new Set(d.usedBy.map((u) => u.name))].join(", ")}: remove it there first.`
                               : "It is deleted with its settings.",
                             action: () => api(`destinations/${d.id}`, { method: "DELETE" }),
                           }); }} />
      )}
      {confirm && (
        <Confirm title={confirm.title} subtitle={confirm.subtitle} confirm={confirm.confirm} danger={confirm.danger} action={confirm.action}
                 onDone={() => { toast(confirm.done); setConfirm(null); reload(); }} onClose={() => setConfirm(null)} />
      )}
    </>
  );
}

/** one state for a destination that may run in several pipelines: down wins, then up */
function liveState(d) {
  const active = d.usedBy.some((u) => u.active);
  if (!d.usedBy.length) return ["", "Not used"];
  if (!active) return ["", "Pipeline paused"];
  if (!d.live.length) return ["warn", "Starting"];
  if (d.live.some((s) => s.state === "down")) return ["bad", "Down"];
  if (d.live.some((s) => s.state === "up")) return ["good", "Up"];
  return ["", "Standby"];
}

function Row({ d, mark, manage, onEdit }) {
  const [kind, text] = liveState(d);
  const messages = d.live.reduce((n, s) => n + (s.messages || 0), 0);
  const last = d.live.map((s) => s.lastSuccessAt).filter(Boolean).sort().pop();
  const err = d.live.find((s) => s.state === "down" && s.lastError)?.lastError;
  const at = useOrgPath();
  return (
    <tr className={d.usedBy.length ? "" : "off"} {...mark}>
      <td><b>{d.name}</b><small className="mono ep" title={endpoint(d)}>{endpoint(d)}</small></td>
      <td className="nowrap">{serviceOf(d).label}<small>{TRANSPORT[d.transport] ?? d.transport}</small></td>
      <td>
        {d.usedBy.length === 0 && <span className="muted small">No pipeline · no data</span>}
        {d.usedBy.map((u) => (
          <Link key={`${u.id}-${u.role}`} to={at(u.uid ? `/pipelines/${u.uid}` : "/pipelines")} className={`chip${u.active ? " accent" : ""}`} title={u.active ? "Active pipeline" : "Draft or paused pipeline"}>
            {u.name} · {ROLE[u.role]}
          </Link>
        ))}
      </td>
      <td><Status kind={kind}>{text}</Status>{err && <small>{err}</small>}{certNote(d)}</td>
      <td>{d.live.length ? messages.toLocaleString() : "–"}</td>
      <td className="muted" title={full(last)}>{d.live.length ? when(last) : "–"}</td>
      {manage && <td className="r"><button className="btn ghost sm" onClick={onEdit}>Edit</button></td>}
    </tr>
  );
}

/** a client or authority certificate that expired or expires within 30 days */
function certNote(d) {
  const all = [...(d.tlsInfo?.ca ?? []), ...(d.tlsInfo?.cert ?? [])].filter((c) => !c.error);
  const soon = all.filter((c) => c.daysLeft < 30).sort((a, b) => a.daysLeft - b.daysLeft)[0];
  if (!soon) return null;
  return <small className={soon.expired ? "bad" : "warnc"}>{soon.expired ? "Certificate expired" : `Certificate expires in ${soon.daysLeft} days`}</small>;
}

function endpoint(d) {
  if (d.transport === "database") return "Stored by the gateway · shared PostgreSQL";
  if (d.transport === "mqtt") return `${d.brokerUrl ?? ""} -> ${d.topic ?? ""}`;
  if (d.transport === "kafka") return `${d.bootstrapServers ?? ""} -> ${d.topic ?? ""}`;
  return d.url ?? "";
}

// ------------------------------------------------------------------ service catalog

function ServicePicker({ added, onClose, onPick }) {
  const [q, setQ] = useState("");
  const n = q.trim().toLowerCase();
  const match = (s) => !s.hidden && (!n || `${s.label} ${s.description} ${s.category}`.toLowerCase().includes(n));
  return (
    <Modal wide title="Add a destination" subtitle="Choose the service the gateway should deliver to." onClose={onClose}
           footer={<button className="btn ghost" onClick={onClose}>Cancel</button>}>
      <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search services" aria-label="Search services" autoFocus />
      {CATEGORIES.map((c) => {
        const items = SERVICES.filter((s) => s.category === c && match(s));
        if (!items.length) return null;
        return (
          <section key={c}>
            <h3 className="svc-h">{c}</h3>
            <div className="svc-grid">
              {items.map((s) => {
                const once = s.single && added.includes(s.key);
                return (
                  <button key={s.key} type="button" className="svc" onClick={() => onPick(s)} disabled={once}
                          title={once ? "Already added: use it in the pipelines" : undefined}>
                    <b>{s.label}</b><small>{once ? "Already added. Use it in a pipeline on the Pipelines page." : s.description}</small>
                  </button>
                );
              })}
            </div>
          </section>
        );
      })}
      {!SERVICES.some(match) && <p className="empty">No service matches. HTTP webhook, MQTT broker and Apache Kafka reach most other systems.</p>}
    </Modal>
  );
}

// ------------------------------------------------------------------ add / edit

const blank = {
  name: "", transport: "rest", timeoutMs: 5000, retryAfterSeconds: 15,
  url: "", format: "envelope", auth: { type: "none" }, probe: false, probeEverySeconds: 30, esMode: "batch", esApi: "auto",
  brokerUrl: "tcp://", topic: "", qos: 1, clientId: "", username: "", password: "",
  bootstrapServers: "", kafkaMode: "batch", securityProtocol: "PLAINTEXT", saslMechanism: "PLAIN", mqttMode: "batch", template: "",
  healthCheck: true, maxRetryAfterSeconds: 300, maxBatchMessages: 0, compress: false, retain: false, maxInflight: 100,
  proxy: "", proxyUsername: "", proxyPassword: "", tlsCa: "", tlsCert: "", tlsKey: "",
};

const pairs = (o) => Object.entries(o || {}).map(([k, v]) => ({ k, v: String(v ?? "") }));

function freeName(base, names) {
  const taken = new Set(names.map((n) => n.toLowerCase()));
  if (!taken.has(base)) return base;
  for (let i = 2; ; i++) if (!taken.has(`${base}-${i}`)) return `${base}-${i}`;
}

function DestinationDialog({ d, service, mask, names, onClose, onBack, onSaved, onDelete }) {
  const at = useOrgPath();
  const isNew = !d;
  const [f, setF] = useState(() => (isNew
    ? { ...blank, ...service.defaults, auth: { ...blank.auth, ...service.defaults?.auth }, service: service.key, transport: service.transport,
      name: freeName(service.key, names) }
    : { ...blank, ...d, auth: { type: "none", ...d.auth } }));
  const [headers, setHeaders] = useState(() => pairs(isNew ? service.defaults?.headers : d.headers));
  const [props, setProps] = useState(() => pairs(d?.kafka));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [test, setTest] = useState(null);           // null | "running" | { ok, message }
  const [preview, setPreview] = useState(null);     // null | { message, rendered } | { error }
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const setAuth = (k, v) => setF((x) => ({ ...x, auth: { ...x.auth, [k]: v } }));
  const input = (k) => ({ id: `d-${k}`, value: f[k] ?? "", onChange: (e) => set(k, e.target.value), placeholder: service.placeholders?.[k] });
  const show = (path) => !(service.fixed ?? []).includes(path);
  const label = (path, dflt) => service.labels?.[path] ?? dflt;
  const hint = (path, dflt) => service.hints?.[path] ?? dflt;

  const body = () => {
    const obj = (rows) => Object.fromEntries(rows.filter((r) => r.k.trim()).map((r) => [r.k.trim(), r.v]));
    const b = { ...f, name: f.name.trim(), service: service.key, headers: obj(headers), kafka: obj(props) };
    if (f.transport === "eventstreams") b.auth = { type: "bearer", token: f.auth.token ?? "" };
    for (const k of ["id", "status", "usedBy", "live", "role", "enabled", "tlsInfo"]) delete b[k];
    return b;
  };

  const save = async () => {
    setError(null);
    const name = f.name.trim();
    if (isNew && names.some((n) => n.toLowerCase() === name.toLowerCase())) return setError(`A destination named ${name} already exists.`);
    setBusy(true);
    try {
      const r = isNew ? await api("destinations", { method: "POST", body: body() })
        : await api(`destinations/${d.id}`, { method: "PUT", body: body() });
      onSaved(r.name, isNew);
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };

  const runTest = async () => {
    setError(null);
    setTest("running");
    try {
      setTest(await api("destinations/test", { method: "POST", body: { original: d?.id ?? null, destination: body() } }));
    } catch (e) {
      setTest({ ok: false, message: e.message });
    }
  };

  const [sending, setSending] = useState(null);     // null | "ask" | "running" | { ok, message }
  const sendTest = async () => {
    setError(null);
    setSending("running");
    try {
      setSending(await api("destinations/send-test", { method: "POST", body: { original: d?.id ?? null, destination: body() } }));
    } catch (e) {
      setSending({ ok: false, message: e.message });
    }
  };

  const authTypes = service.authTypes ?? ["none", "basic", "bearer", "token", "oauth2"];
  const AUTH = { none: "None (or a header below)", basic: "User name + password", bearer: "Bearer token",
    token: "Token with a scheme (Splunk, Token, ApiKey…)", oauth2: "OAuth2 client credentials" };
  const http = f.transport === "rest" || f.transport === "eventstreams";

  return (
    <Modal wide title={isNew ? `Add ${service.label}` : `Edit ${d.name}`} onClose={onClose}
           subtitle={service.description}
           footer={<>
             {!isNew && <button className="btn danger" style={{ marginRight: "auto" }} onClick={() => onDelete(d)}>Remove…</button>}
             {isNew && onBack && <button className="btn ghost" style={{ marginRight: "auto" }} onClick={onBack}>← Other service</button>}
             <button className="btn ghost" disabled={test === "running"} onClick={runTest}>{test === "running" ? "Testing…" : "Test connection"}</button>
             <button className="btn ghost" disabled={sending === "running"} onClick={() => setSending("ask")}
                     title="Send one test reading end to end">{sending === "running" ? "Sending…" : "Send test message"}</button>
             <button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy} onClick={save}>{isNew ? "Add destination" : "Save"}</button></>}>
      {service.hidden && (
        <Msg kind="warn">{service.label} is no longer offered: the gateway stores readings in the BIMBA database itself. Add the
          {" "}<b>BIMBA database</b> destination, put it in this one's place on the <Link to={at("/pipelines")}>Pipelines</Link> page, then remove this one.</Msg>
      )}
      <div className="field">
        <label htmlFor="d-name">Name</label>
        <input type="text" {...input("name")} autoComplete="off" spellCheck="false" />
        <span className="hint">Letters, digits and . _ - · Saving does not send data: use the destination in a pipeline for that.</span>
      </div>

      {f.transport === "database" && (
        <div className="field">
          <span className="label">How it works</span>
          <p className="small" style={{ margin: 0 }}>
            The gateway writes each batch straight into the BIMBA database over its own database connection (the same one it
            uses for sign-in), with the same rules as every other way in: a reading sent twice is stored once, the latest values
            and the live pages update at once. Readings show as delivered by <b>BIMBA Gateway</b>. No address, sign-in or
            separate receiver is needed. Use it as a pipeline's primary, as a secondary while Boomi is down, or as a mirror.
          </p>
        </div>
      )}

      {f.transport === "eventstreams" && (
        <div className="grid2">
          <div className="field span2">
            <label htmlFor="d-url">Topic REST URL</label>
            <input type="text" {...input("url")} spellCheck="false" placeholder="https://…eventstreams.boomi.com/rest/singlemsg/…/topic" />
            <span className="hint">From Event Streams → the topic → REST endpoint.</span>
          </div>
          <div className="field span2">
            <label htmlFor="d-token">Environment token</label>
            <Secret id="d-token" value={f.auth.token} mask={mask} onChange={(v) => setAuth("token", v)} />
            <EnvHint />
          </div>
          <Choice id="esMode" label="Messages" value={f.esMode} set={set}
                  options={[["batch", "One per batch"], ["message", "One per tag value"]]} />
          <Choice id="esApi" label="API" value={f.esApi} set={set}
                  options={[["auto", "Detect from the URL"], ["single", "Single message"], ["multi", "Multiple messages"]]} />
        </div>
      )}

      {f.transport === "rest" && (
        <div className="grid2">
          <div className="field span2">
            <label htmlFor="d-url">{label("url", "URL")}</label>
            <input type="text" {...input("url")} spellCheck="false" />
            <span className="hint">{hint("url", "Every batch is sent as an HTTP POST.")}</span>
          </div>
          {show("format") && (
            <Choice id="format" label="Body" value={f.format} set={set}
                    options={[["envelope", "One JSON document per batch"], ["json-array", "JSON array of readings"], ["ndjson", "One JSON line per reading"]]} />
          )}
          {show("auth.type") && (
            <div className="field">
              <label htmlFor="d-auth">Sign-in</label>
              <select id="d-auth" value={f.auth.type} onChange={(e) => {
                const t = e.target.value;
                setF((x) => ({ ...x, auth: { ...x.auth, type: t, scheme: t === "token" ? x.auth.scheme || service.tokenScheme || "" : x.auth.scheme } }));
              }}>
                {authTypes.map((t) => <option key={t} value={t}>{AUTH[t]}</option>)}
              </select>
            </div>
          )}
          {f.auth.type === "basic" && (
            <>
              <div className="field">
                <label htmlFor="d-au">{label("auth.username", "User name")}</label>
                <input id="d-au" type="text" value={f.auth.username ?? ""} onChange={(e) => setAuth("username", e.target.value)} autoComplete="off" />
              </div>
              <div className="field">
                <label htmlFor="d-ap">{label("auth.password", "Password")}</label>
                <Secret id="d-ap" value={f.auth.password} mask={mask} onChange={(v) => setAuth("password", v)} />
              </div>
            </>
          )}
          {f.auth.type === "token" && show("auth.scheme") && (
            <div className="field">
              <label htmlFor="d-as">Scheme</label>
              <input id="d-as" type="text" value={f.auth.scheme ?? ""} onChange={(e) => setAuth("scheme", e.target.value)} placeholder="ApiKey" spellCheck="false" />
              <span className="hint">Sent as "Authorization: &lt;scheme&gt; &lt;token&gt;".</span>
            </div>
          )}
          {(f.auth.type === "bearer" || f.auth.type === "token") && (
            <div className="field span2">
              <label htmlFor="d-at">{label("auth.token", "Token")}</label>
              <Secret id="d-at" value={f.auth.token} mask={mask} onChange={(v) => setAuth("token", v)} />
              <EnvHint />
            </div>
          )}
          {f.auth.type === "oauth2" && (
            <>
              <div className="field span2">
                <label htmlFor="d-ot">Token URL</label>
                <input id="d-ot" type="text" value={f.auth.tokenUrl ?? ""} onChange={(e) => setAuth("tokenUrl", e.target.value)} spellCheck="false"
                       placeholder="https://login.example.com/oauth2/token" />
                <span className="hint">Client credentials grant: the gateway gets a token here and renews it before it expires.</span>
              </div>
              <div className="field">
                <label htmlFor="d-oc">Client ID</label>
                <input id="d-oc" type="text" value={f.auth.clientId ?? ""} onChange={(e) => setAuth("clientId", e.target.value)} autoComplete="off" spellCheck="false" />
              </div>
              <div className="field">
                <label htmlFor="d-os">Client secret</label>
                <Secret id="d-os" value={f.auth.clientSecret} mask={mask} onChange={(v) => setAuth("clientSecret", v)} />
              </div>
              <div className="field">
                <label htmlFor="d-osc">Scope <span className="muted">(optional)</span></label>
                <input id="d-osc" type="text" value={f.auth.scope ?? ""} onChange={(e) => setAuth("scope", e.target.value)} spellCheck="false" />
              </div>
              <div className="field">
                <label htmlFor="d-oa">Audience <span className="muted">(optional)</span></label>
                <input id="d-oa" type="text" value={f.auth.audience ?? ""} onChange={(e) => setAuth("audience", e.target.value)} spellCheck="false" />
              </div>
            </>
          )}
          <div className="field span2">
            <span className="label">Headers</span>
            <Pairs rows={headers} setRows={setHeaders} mask={mask} keyLabel="Header" placeholder={["X-API-Key", "value"]} />
          </div>
        </div>
      )}

      {f.transport === "mqtt" && (
        <div className="grid2">
          <div className="field">
            <label htmlFor="d-brokerUrl">Broker URL</label>
            <input type="text" {...input("brokerUrl")} spellCheck="false" />
            <span className="hint">tcp://, ssl:// (TLS), ws:// or wss://</span>
          </div>
          {show("mqttMode") && <Choice id="mqttMode" label="Publish" value={f.mqttMode} set={(k, v) => setF((x) => ({
            ...x, mqttMode: v,
            topic: v === "message" && !x.topic.includes("{machineId}") ? "bimba/{plantId}/{machineId}/{tagName}"
              : v === "batch" && x.topic.includes("{machineId}") ? "bimba/v1/{plantId}/batch" : x.topic,
          }))} options={[["batch", "One message per batch"], ["message", "One message per reading (own topic)"]]} />}
          <div className="field">
            <label htmlFor="d-topic">Topic</label>
            <input type="text" {...input("topic")} spellCheck="false" />
            <span className="hint">{f.mqttMode === "message" ? "Use {plantId}, {machineId}, {tagName}, {unit}, … — e.g. a Unified Namespace: bimba/{plantId}/{machineId}/{tagName}"
              : hint("topic", "{plantId} is replaced by the plant id.")}</span>
          </div>
          <div className="field">
            <label htmlFor="d-username">User name</label>
            <input type="text" {...input("username")} autoComplete="off" />
          </div>
          <div className="field">
            <label htmlFor="d-password">Password</label>
            <Secret id="d-password" value={f.password} mask={mask} onChange={(v) => set("password", v)} />
          </div>
          <Choice id="qos" label="QoS" value={String(f.qos)} set={(k, v) => set(k, Number(v))}
                  options={[["0", "0 · at most once"], ["1", "1 · at least once"], ["2", "2 · exactly once"]]} />
          <label className="check" style={{ alignSelf: "center" }}>
            <input type="checkbox" checked={f.retain} onChange={(e) => set("retain", e.target.checked)} />
            <span>Retained <small className="muted">The broker keeps the last message of each topic, so a new subscriber sees the current value at once (recommended for a Unified Namespace).</small></span>
          </label>
        </div>
      )}

      {f.transport === "kafka" && (
        <div className="grid2">
          <div className="field">
            <label htmlFor="d-bootstrapServers">{label("bootstrapServers", "Bootstrap servers")}</label>
            <input type="text" {...input("bootstrapServers")} spellCheck="false" />
            <span className="hint">host:port, comma-separated.</span>
          </div>
          <div className="field">
            <label htmlFor="d-topic">{label("topic", "Topic")}</label>
            <input type="text" {...input("topic")} spellCheck="false" />
            <span className="hint">{"{plantId}"} is replaced by the plant id.</span>
          </div>
          {show("securityProtocol") && (
            <Choice id="securityProtocol" label="Security" value={f.securityProtocol} set={set}
                    options={[["PLAINTEXT", "None (PLAINTEXT)"], ["SSL", "TLS (SSL)"], ["SASL_SSL", "TLS + sign-in (SASL_SSL)"], ["SASL_PLAINTEXT", "Sign-in without TLS (SASL_PLAINTEXT)"]]} />
          )}
          {(f.securityProtocol || "").startsWith("SASL") && show("saslMechanism") && (
            <Choice id="saslMechanism" label="Sign-in mechanism" value={f.saslMechanism} set={set}
                    options={[["PLAIN", "PLAIN"], ["SCRAM-SHA-256", "SCRAM-SHA-256"], ["SCRAM-SHA-512", "SCRAM-SHA-512"]]} />
          )}
          {(f.securityProtocol || "").startsWith("SASL") && show("username") && (
            <div className="field">
              <label htmlFor="d-username">{label("username", "User name")}</label>
              <input type="text" {...input("username")} autoComplete="off" spellCheck="false" />
              {service.hints?.username && <span className="hint">{service.hints.username}</span>}
            </div>
          )}
          {(f.securityProtocol || "").startsWith("SASL") && (
            <div className={`field${show("username") ? "" : " span2"}`}>
              <label htmlFor="d-password">{label("password", "Password")}</label>
              <Secret id="d-password" value={f.password} mask={mask} onChange={(v) => set("password", v)} />
              {service.hints?.password && <span className="hint">{service.hints.password}</span>}
            </div>
          )}
          <Choice id="kafkaMode" label="Records" value={f.kafkaMode} set={set}
                  options={[["batch", "One per batch (key = edge id)"], ["message", "One per reading (key = machine/tag)"]]} />
        </div>
      )}

      {f.format !== "influx-line" && f.transport !== "database" && (
        <Layout f={f} set={set} preview={preview} setPreview={setPreview} />
      )}

      {f.transport !== "database" && <Certificates f={f} set={set} mask={mask} info={d?.tlsInfo} />}

      <details className="adv">
        <summary>Advanced</summary>
        <div className="grid2">
          <Num id="timeoutMs" label="Timeout (ms)" f={f} set={set} />
          <Num id="retryAfterSeconds" label="After a failure, try again after (s)" f={f} set={set} />
          <Num id="maxRetryAfterSeconds" label="Longest wait between retries (s)" f={f} set={set}
               hint="Failing again and again, the wait doubles up to this." />
          <Num id="maxBatchMessages" label="Readings per request (0 = no limit)" f={f} set={set}
               hint="Larger batches are split, for receivers with a size limit." />
          <label className="check span2">
            <input type="checkbox" checked={f.healthCheck} onChange={(e) => set("healthCheck", e.target.checked)} />
            <span>Health-check while on standby <small className="muted">
              Checks a secondary (or a down destination) every {f.probeEverySeconds || 30} s without sending data, so a broken one is seen before it is needed.</small></span>
          </label>
          {f.healthCheck && <Num id="probeEverySeconds" label="Health check every (s)" f={f} set={set} />}
          {f.transport === "mqtt" && <Num id="maxInflight" label="Messages in flight" f={f} set={set} hint="Sent in parallel before waiting for the broker." />}
          {f.transport === "rest" && (
            <label className="check span2">
              <input type="checkbox" checked={f.compress} onChange={(e) => set("compress", e.target.checked)} />
              <span>Compress requests (gzip) <small className="muted">Less traffic; the receiver must accept Content-Encoding: gzip (Splunk and Elasticsearch do).</small></span>
            </label>
          )}
          {http && (
            <>
              <div className="field span2">
                <label htmlFor="d-proxy">HTTP proxy <span className="muted">(optional)</span></label>
                <input type="text" {...input("proxy")} placeholder="http://proxy.company.local:8080" spellCheck="false" />
                <span className="hint">For networks where internet traffic must go through a proxy.</span>
              </div>
              {f.proxy && (
                <>
                  <div className="field">
                    <label htmlFor="d-proxyUsername">Proxy user name</label>
                    <input type="text" {...input("proxyUsername")} autoComplete="off" />
                  </div>
                  <div className="field">
                    <label htmlFor="d-pp">Proxy password</label>
                    <Secret id="d-pp" value={f.proxyPassword} mask={mask} onChange={(v) => set("proxyPassword", v)} />
                  </div>
                </>
              )}
            </>
          )}
          {(f.transport === "mqtt" || f.transport === "kafka") && (
            <div className="field">
              <label htmlFor="d-clientId">Client id</label>
              <input type="text" {...input("clientId")} spellCheck="false" placeholder={`bimba-${f.name || "name"}`} />
            </div>
          )}
          {f.transport === "rest" && (
            <label className="check span2">
              <input type="checkbox" checked={f.probe} onChange={(e) => set("probe", e.target.checked)} />
              <span>Use the BIMBA health-check request <small className="muted">
                POST with header X-BIMBA-Probe: 1; the receiver answers 2xx without storing anything. Only for receivers that support it (the BIMBA Ingest receiver does).</small></span>
            </label>
          )}
          {f.transport === "kafka" && (
            <div className="field span2">
              <span className="label">More producer properties</span>
              <Pairs rows={props} setRows={setProps} mask={mask} keyLabel="Property" placeholder={["ssl.endpoint.identification.algorithm", "https"]} />
              <span className="hint">Any Kafka producer setting (ssl.*, compression.type, …); these override the ones above.</span>
            </div>
          )}
        </div>
      </details>
      {test && test !== "running" && <Msg kind={test.ok ? "good" : "bad"}>{test.ok ? "✓ " : "✕ "}{test.message}</Msg>}
      {sending === "ask" && (
        <div className="msg warn ask">
          <span>Send <b>one test reading</b> (machine BIMBA-TEST, tag connection_test) to this destination as the form shows it?
            The receiver stores it like a real reading.</span>
          <span className="acts">
            <button className="btn sm" onClick={sendTest}>Send it</button>
            <button className="btn ghost sm" onClick={() => setSending(null)}>Cancel</button>
          </span>
        </div>
      )}
      {sending && typeof sending === "object" && <Msg kind={sending.ok ? "good" : "bad"}>{sending.ok ? "✓ " : "✕ "}{sending.message}</Msg>}
      <Msg>{error}</Msg>
    </Modal>
  );
}

const STARTER = `{
  "site": "{plantId}",
  "asset": "{machineId}",
  "signal": "{tagName}",
  "value": "{tagValue}",
  "unit": "{unit}",
  "quality": "{quality}",
  "ts": "{timestampMs}"
}`;
const FIELDS = ["plantId", "machineId", "tagName", "path", "tagValue", "unit", "quality", "timestamp", "timestampMs", "epochSeconds",
  "dataType", "messageId", "edgeId", "aggregate.min", "aggregate.max", "aggregate.count"];

/** What the BIMBA standard message holds, field by field (collect/Collector.java builds it; delivery/Sampler adds aggregate). */
const GUIDE = [
  ["schemaVersion", "Version of this message format", "1.0"],
  ["messageId", "Unique id of this reading; use it to drop duplicates after a retry", "0b6c…4b5c"],
  ["plantId", "Plant the gateway belongs to", "PLANT01"],
  ["machineId", "Machine (folder in the tag tree)", "PUMP-P1"],
  ["tagName", "Tag of that machine", "Flow"],
  ["tagValue", "The value: number, true / false, text, or null when it could not be read", "42.7"],
  ["dataType", "number · boolean · string · null", "number"],
  ["unit", "Engineering unit from the tag tree (may be empty)", "m3/h"],
  ["timestamp", "When the value was read, ISO 8601 UTC", "2026-10-07T10:15:30.250Z"],
  ["quality", "GOOD, or BAD / UNCERTAIN (then qualityDetail says why)", "GOOD"],
  ["category", "Kind of data", "telemetry"],
  ["source", "Where it was read: protocol (modbus · opcua · expression), gateway, device/address", "modbus · PLC/40001"],
  ["edge", "Which gateway sent it: edgeId, session (one per start) and seq (a gap = a lost reading)", "seq 1842"],
  ["aggregate", "Only from Snapshot / Average pipelines: fn, min, max, count, from, to", "avg · 9 readings"],
  ["sourceTimestamp", "Only from Snapshot / Average pipelines: when the value was read", ""],
];

/** how this destination puts readings on the wire, from the form */
function shape(f) {
  const per = { kind: "reading" };
  if (f.transport === "rest") {
    if (f.format === "json-array") return { kind: "array", text: "Each batch is sent as one JSON array of readings." };
    if (f.format === "ndjson") return { ...per, text: "Each batch is sent as lines of JSON, one reading per line." };
    if (f.format === "splunk-hec") return { ...per, text: "Each reading becomes one Splunk event (the reading is the event's \"event\")." };
    if (f.format === "elastic-bulk") return { ...per, text: "Each reading becomes one Elasticsearch document (plus @timestamp)." };
    return { kind: "batch", text: "Each batch is sent as one JSON document: a few batch fields and the readings in \"messages\"." };
  }
  const mode = f.transport === "kafka" ? f.kafkaMode : f.transport === "mqtt" ? f.mqttMode : f.esMode;
  const unit = f.transport === "kafka" ? "Kafka record" : f.transport === "mqtt" ? "MQTT message" : "Event Streams message";
  return mode === "message" ? { ...per, text: `Each reading is sent as its own ${unit}.` }
    : { kind: "batch", text: `Each batch is sent as one ${unit}: a few batch fields and the readings in "messages".` };
}

/** How each reading is written: the BIMBA message (with an example and a field guide), or the destination's own JSON template. */
function Layout({ f, set, preview, setPreview }) {
  const custom = typeof f.template === "string" && f.template.trim() !== "";
  const [open, setOpen] = useState(custom);
  const [example, setExample] = useState(null);     // the standard message + batch, loaded when first shown
  const ref = useRef(null);
  const sh = shape(f);
  const run = async (template) => {
    try {
      setPreview(await api("destinations/preview", { method: "POST", body: { template } }));
    } catch (e) {
      setPreview({ error: e.message });
    }
  };
  const loadExample = async () => {
    if (example) return;
    try {
      setExample(await api("destinations/preview", { method: "POST", body: { template: "" } }));
    } catch (e) {
      setExample({ error: e.message });
    }
  };
  const insert = (name) => {
    const el = ref.current;
    const text = `"{${name}}"`;
    if (!el) return set("template", (f.template ?? "") + text);
    const a = el.selectionStart;
    const b = el.selectionEnd;
    set("template", f.template.slice(0, a) + text + f.template.slice(b));
    requestAnimationFrame(() => { el.focus(); el.setSelectionRange(a + text.length, a + text.length); });
  };
  const wire = (pv) => (sh.kind === "batch" ? pv.batch : sh.kind === "array" ? arrayOf(pv.batch) : pv.rendered);
  return (
    <fieldset className="field" style={{ border: 0, padding: 0, margin: 0 }}>
      <legend className="label" style={{ marginBottom: 6 }}>Message layout</legend>
      <div className="seg two">
        <label><input type="radio" name="d-layout" checked={!open} onChange={() => { setOpen(false); set("template", ""); setPreview(null); }} />
          <b>BIMBA standard message</b><small>Every field of the reading, as the BIMBA database and the BIMBA tools expect it.</small></label>
        <label><input type="radio" name="d-layout" checked={open} onChange={() => { setOpen(true); if (!custom) set("template", STARTER); run(custom ? f.template : STARTER); }} />
          <b>Custom template</b><small>Your own JSON per reading: rename, pick and nest fields.</small></label>
      </div>

      {!open && (
        <details className="adv std" onToggle={(e) => e.currentTarget.open && loadExample()}>
          <summary>What does the BIMBA standard message look like?</summary>
          <p className="small">{sh.text}</p>
          {example?.error ? <Msg>{example.error}</Msg> : (
            <div className="layout">
              <div className="field">
                <span className="label">{sh.kind === "batch" ? "Example: a batch with 2 readings" : sh.kind === "array" ? "Example: 2 readings" : "Example: one reading"}</span>
                <pre className="code tall">{example ? wire(example) : "Loading…"}</pre>
              </div>
              <div className="field">
                <span className="label">The fields of a reading</span>
                <table className="fields">
                  <tbody>
                    {GUIDE.map(([k, what, eg]) => (
                      <tr key={k}><td className="mono">{k}</td><td>{what}{eg && <small>e.g. {eg}</small>}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </details>
      )}

      {open && (
        <div className="layout">
          <div className="field">
            <textarea ref={ref} rows={10} className="mono" value={f.template ?? ""} spellCheck="false"
                      onChange={(e) => set("template", e.target.value)} aria-label="Template" />
            <div className="tagset">{FIELDS.map((n) => <button type="button" key={n} className="chip" onClick={() => insert(n)} title="Insert at the cursor">{`{${n}}`}</button>)}</div>
            <span className="hint">"{"{tagValue}"}" alone keeps the number type; inside text it becomes text. With Snapshot / Average pipelines,
              {" {aggregate.min}"}, {"{aggregate.max}"}, {"{aggregate.count}"} are filled too.</span>
            <div><button type="button" className="btn ghost sm" onClick={() => run(f.template)}>Preview</button></div>
          </div>
          <div className="field">
            <span className="label">A reading is sent as</span>
            {preview?.error ? <Msg>{preview.error}</Msg>
              : <pre className="code">{preview ? preview.rendered : "Press Preview."}</pre>}
            {preview && !preview.error && (
              <>
                <details className="adv">
                  <summary>The same reading as a BIMBA standard message</summary>
                  <pre className="code">{preview.message}</pre>
                </details>
                {sh.kind !== "reading" && (
                  <details className="adv">
                    <summary>{sh.kind === "batch" ? "A whole batch as sent" : "Readings as sent"}</summary>
                    <p className="small muted">{sh.text}</p>
                    <pre className="code">{wire(preview)}</pre>
                  </details>
                )}
              </>
            )}
          </div>
        </div>
      )}
    </fieldset>
  );
}

/** the "messages" of a batch example, as the JSON array body sends them */
function arrayOf(batchText) {
  try {
    return JSON.stringify(JSON.parse(batchText).messages, null, 2);
  } catch {
    return batchText;
  }
}

/** A stored secret arrives as the mask: the field shows empty with a note and sends the mask back unless replaced. */
function Secret({ id, value, mask, onChange }) {
  const [stored] = useState(value === mask);
  return (
    <>
      <PasswordInput id={id} value={value === mask ? "" : value ?? ""} autoComplete="new-password"
                     onChange={(v) => onChange(v === "" && stored ? mask : v)} />
      {stored && value === mask && <span className="hint">Saved. Leave empty to keep it, or type a new value.</span>}
    </>
  );
}

function EnvHint() {
  return <span className="hint">Stored encrypted. You can also write {"${ENV_VAR}"} to read it from the gateway's environment.</span>;
}

function Pairs({ rows, setRows, mask, keyLabel, placeholder }) {
  const update = (i, k, v) => setRows(rows.map((r, j) => (j === i ? { ...r, [k]: v } : r)));
  return (
    <div className="pairs">
      {rows.map((r, i) => (
        <div className="pair" key={i}>
          <input type="text" value={r.k} onChange={(e) => update(i, "k", e.target.value)} placeholder={placeholder[0]}
                 aria-label={`${keyLabel} name`} spellCheck="false" />
          {SECRET_KEY.test(r.k) || r.v === mask
            ? <div><Secret value={r.v} mask={mask} onChange={(v) => update(i, "v", v)} /></div>
            : <input type="text" value={r.v} onChange={(e) => update(i, "v", e.target.value)} placeholder={placeholder[1]}
                     aria-label={`${keyLabel} value`} spellCheck="false" />}
          <button type="button" className="btn ghost sm" onClick={() => setRows(rows.filter((_, j) => j !== i))} aria-label={`Remove ${r.k || keyLabel}`}>✕</button>
        </div>
      ))}
      <div><button type="button" className="btn ghost sm" onClick={() => setRows([...rows, { k: "", v: "" }])}>+ Add {keyLabel.toLowerCase()}</button></div>
    </div>
  );
}

function Choice({ id, label, value, set, options }) {
  return (
    <div className="field">
      <label htmlFor={`d-${id}`}>{label}</label>
      <select id={`d-${id}`} value={value} onChange={(e) => set(id, e.target.value)}>
        {options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
      </select>
    </div>
  );
}

function Num({ id, label, f, set, hint }) {
  return (
    <div className="field">
      <label htmlFor={`d-${id}`}>{label}</label>
      <input id={`d-${id}`} type="number" min="0" value={f[id] ?? ""} onChange={(e) => set(id, e.target.value)} />
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

/** TLS for this destination: authorities to trust, client certificate + key (the key is a secret). PEM pasted or loaded. */
function Certificates({ f, set, mask, info }) {
  const has = (f.tlsCa ?? "").trim() || (f.tlsCert ?? "").trim() || f.tlsKey === mask;
  const load = (k) => (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    file.text().then((t) => set(k, t));
    e.target.value = "";
  };
  const expiry = (list) => (list ?? []).map((c, i) => (
    <small key={i} className={c.error || c.expired ? "bad" : c.daysLeft < 30 ? "warnc" : "muted"}>
      {c.error ? c.error : `${c.subject} · valid until ${new Date(c.notAfter).toLocaleDateString()}${c.expired ? " · EXPIRED" : c.daysLeft < 30 ? ` · ${c.daysLeft} days left` : ""}`}
    </small>
  ));
  return (
    <details className="adv" open={!!has}>
      <summary>Certificates (TLS){has ? " · set" : ""}</summary>
      <p className="small muted" style={{ marginBottom: 10 }}>
        Only needed for a server with a self-signed or company certificate, or for certificate sign-in (AWS IoT Core, Azure IoT Hub,
        company brokers). PEM text: paste it or load the file. Works for HTTPS, MQTT ssl:// / wss:// and Kafka SSL.
      </p>
      <div className="grid2">
        <div className="field span2">
          <label htmlFor="d-ca">Trusted certificate authorities <span className="muted">(added to the built-in ones)</span></label>
          <textarea id="d-ca" rows={3} className="mono" value={f.tlsCa ?? ""} onChange={(e) => set("tlsCa", e.target.value)}
                    placeholder="-----BEGIN CERTIFICATE-----" spellCheck="false" />
          <div className="row-file"><label className="btn ghost sm">Load file…<input type="file" accept=".pem,.crt,.cer" hidden onChange={load("tlsCa")} /></label>{expiry(info?.ca)}</div>
        </div>
        <div className="field">
          <label htmlFor="d-cc">Client certificate <span className="muted">(certificate sign-in)</span></label>
          <textarea id="d-cc" rows={3} className="mono" value={f.tlsCert ?? ""} onChange={(e) => set("tlsCert", e.target.value)}
                    placeholder="-----BEGIN CERTIFICATE-----" spellCheck="false" />
          <div className="row-file"><label className="btn ghost sm">Load file…<input type="file" accept=".pem,.crt,.cer" hidden onChange={load("tlsCert")} /></label>{expiry(info?.cert)}</div>
        </div>
        <div className="field">
          <label htmlFor="d-ck">Private key of the client certificate</label>
          <textarea id="d-ck" rows={3} className="mono" value={f.tlsKey === mask ? "" : f.tlsKey ?? ""}
                    onChange={(e) => set("tlsKey", e.target.value === "" && f.tlsKey === mask ? mask : e.target.value)}
                    placeholder={f.tlsKey === mask ? "Saved (encrypted). Leave empty to keep it." : "-----BEGIN PRIVATE KEY-----"} spellCheck="false" />
          <div className="row-file"><label className="btn ghost sm">Load file…<input type="file" accept=".pem,.key" hidden onChange={load("tlsKey")} /></label>
            <small className="muted">PKCS#8 or RSA; stored encrypted, never shown again.</small></div>
        </div>
      </div>
    </details>
  );
}
