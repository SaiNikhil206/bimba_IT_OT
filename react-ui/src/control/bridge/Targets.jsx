import { useEffect, useState } from "react";
import { api, clone } from "../../console/api.js";
import { hms } from "../lib.js";
import { RouteState } from "./common.jsx";
import { ActionBar, Input, Select, Toggle } from "./forms.jsx";

// IT target pages: Boomi Event Streams (primary) and the Kafka mirror (same API + rules as /console).

function Header({ kicker, title, lead }) {
  return (
    <div className="bx-title">
      <span className="k">{kicker}</span>
      <h3>{title}</h3>
      <p className="muted">{lead}</p>
    </div>
  );
}

// ---------------------------------------------------------------- Kafka

export function Kafka({ config, draft, setDraft, reload, dirty, plugin }) {
  const k = draft.kafka;
  const set = (key, v) => setDraft((d) => ({ ...d, kafka: { ...d.kafka, [key]: v } }));
  const sasl = k.securityProtocol?.startsWith("SASL");
  const topic = (k.topic ?? "").replaceAll("{plantId}", config.plantId ?? "");
  const invalid =
    (!String(k.bootstrapServers ?? "").trim() && "Bootstrap servers are required") ||
    (!String(k.topic ?? "").trim() && "Topic is required") ||
    null;
  const save = async () => {
    const r = await api("kafka/", { method: "PUT", body: k });
    await reload({ keepDraftFor: "kafka" });
    return r;
  };
  const test = () => api("test/kafka/", { method: "POST", body: k });

  return (
    <>
      <Header kicker="IT target · mirror route" title="Kafka"
              lead="Every batch is copied to this topic on its own route, with its own retry and disk buffer, so Kafka being down never holds up the primary path." />
      {!k.exists && <p className="cr-panel bx-note">config.json has no Kafka destination yet. Saving creates one (role mirror).</p>}
      <div className="bx-split">
        <section className="cr-panel">
          <div className="cr-ph"><h2>Producer</h2><span className="proto kafka">KAFKA</span></div>
          <div className="bx-form">
            <Toggle label="Mirror every batch to Kafka" obj={k} k="enabled" onChange={set} wide />
            <Input label="Bootstrap servers" obj={k} k="bootstrapServers" onChange={set} mono wide
                   hint="Comma separated host:port, as the gateway reaches them (kafka:9092 inside Docker)."
                   invalid={!String(k.bootstrapServers ?? "").trim()} />
            <Select label="Security protocol" obj={k} k="securityProtocol" onChange={set} options={["PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL"]} />
            <Select label="SASL mechanism" obj={k} k="saslMechanism" onChange={set} disabled={!sasl} options={["PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512"]} />
            {sasl && (
              <>
                <Input label="Username" obj={k} k="username" onChange={set} mono />
                <Input label="Password" obj={k} k="password" onChange={set} type="password" mono
                       placeholder={k.passwordSet ? "•••••••• (unchanged)" : ""}
                       hint="Written into config.json (sasl.jaas.config); the plugin reads Kafka secrets only from there." />
              </>
            )}
            <Input label="Topic" obj={k} k="topic" onChange={set} mono invalid={!String(k.topic ?? "").trim()}
                   hint={k.topic?.includes("{plantId}") ? `Resolves to ${topic}` : undefined} />
            <Select label="Record layout" obj={k} k="kafkaMode" onChange={set}
                    options={[["batch", "One record per batch (key = edgeId)"], ["message", "One record per tag value (key = machine/tag)"]]} />
            <Input label="Timeout (ms)" obj={k} k="timeoutMs" onChange={set} type="number" mono />
            <Input label="Retry after (s)" obj={k} k="retryAfterSeconds" onChange={set} type="number" mono
                   hint="How long a failed broker is skipped before the plugin tries again." />
          </div>
          <p className="bx-note">Acknowledgement: <span className="mono">acks=all</span> with the idempotent producer (fixed by the plugin).</p>
          <ActionBar onTest={test} onSave={save} onRevert={() => setDraft((d) => ({ ...d, kafka: clone(config.kafka) }))} dirty={dirty} invalid={invalid} />
        </section>
        <div className="bx-stack">
          <RouteState plugin={plugin} name={config.kafka.name} enabled={config.kafka.enabled} />
          <TopicTail enabled={config.kafka.enabled} />
        </div>
      </div>
    </>
  );
}

/** Newest records on the mirror topic (all machines), refreshed every 3 s. */
function TopicTail({ enabled }) {
  const [d, setD] = useState(null);
  useEffect(() => {
    if (!enabled) return undefined;
    let alive = true;
    let timer;
    const load = async () => {
      try {
        const r = await fetch("/api/console/kafka-tail/?depth=12");
        const j = await r.json();
        if (alive) setD(j);
      } catch (e) {
        if (alive) setD({ available: false, error: e.message });
      }
      if (alive) timer = setTimeout(load, 3000);
    };
    load();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [enabled]);
  return (
    <section className="cr-panel">
      <div className="cr-ph"><h2>Latest records on the topic</h2><span className="muted">{d?.topic ?? ""}</span></div>
      {!enabled ? <p className="muted">The Kafka mirror is disabled.</p>
        : !d ? <p className="muted">Reading the topic…</p>
        : d.available === false ? <p className="bx-note bad">{d.error}</p>
        : (
          <div className="bx-tbl">
            <table>
              <thead><tr><th>Broker time</th><th>Partition @ offset</th><th className="r">Messages</th><th>Machines</th></tr></thead>
              <tbody>
                {(d.records ?? []).slice(0, 8).map((r) => (
                  <tr key={`${r.partition}-${r.offset}`}>
                    <td className="mono">{hms(r.timestamp)}</td>
                    <td className="mono">p{r.partition} @ {r.offset}</td>
                    <td className="mono r">{r.count}</td>
                    <td className="muted">{[...new Set(r.messages.map((m) => m.machineId))].join(", ")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
    </section>
  );
}

// ---------------------------------------------------------------- Boomi Event Streams

const ENV_REF = /^\$\{BOOMI_ES_[A-Z0-9_]+\}$/;

/** https://<region host>/rest/<api>/<account>/<environment id>/<topic> */
function parseUrl(url) {
  const m = /^https:\/\/([^/]+)\/rest\/([^/]+)\/([^/]+)\/([^/]+)\/([^/?#]+)/.exec((url ?? "").trim());
  return m ? { host: m[1], api: m[2], account: m[3], environment: m[4], topic: decodeURIComponent(m[5]) } : null;
}

export function EventStreams({ config, draft, setDraft, reload, dirty, plugin }) {
  const es = draft.eventstreams;
  const set = (k, v) => setDraft((d) => ({ ...d, eventstreams: { ...d.eventstreams, [k]: v } }));
  const parts = parseUrl(es.url);
  const tokenChanged = es.token !== config.eventstreams.token;
  const invalid =
    (!String(es.url ?? "").startsWith("https://") && "Topic REST URL must start with https://") ||
    (!ENV_REF.test(es.token ?? "") && "Token must be a reference like ${BOOMI_ES_PRODUCE_TOKEN}") ||
    null;
  // send the token only when it was edited; null keeps what config.json has
  const body = () => ({ ...es, token: tokenChanged ? es.token : null });
  const save = async () => {
    const r = await api("eventstreams/", { method: "PUT", body: body() });
    await reload({ keepDraftFor: "eventstreams" });
    return r;
  };
  const test = () => api("test/eventstreams/", { method: "POST", body: body() });

  return (
    <>
      <Header kicker={`IT target · ${es.role} route`} title="Boomi Event Streams"
              lead="The primary path: batches are produced to this topic and the Boomi listener process stores them in PostgreSQL. If Event Streams fails, the plugin fails over to BIMBA Ingest." />
      {config.eventstreams.literalToken && (
        <p className="cr-panel bx-note bad">
          <b>config.json holds a literal token.</b> Put it in .env as BOOMI_ES_PRODUCE_TOKEN and save the reference{" "}
          <span className="mono">{"${BOOMI_ES_PRODUCE_TOKEN}"}</span> here instead.
        </p>
      )}
      <div className="bx-split">
        <section className="cr-panel">
          <div className="cr-ph"><h2>Topic producer</h2><span className="proto eventstreams">EVENT STREAMS</span></div>
          <div className="bx-form">
            <Toggle label={`Enabled (${es.role} destination)`} obj={es} k="enabled" onChange={set} wide />
            <Input label="Topic REST URL" obj={es} k="url" onChange={set} mono wide invalid={!String(es.url ?? "").startsWith("https://")}
                   hint="From Event Streams: topic → View REST. The single-message URL contains /singlemsg/." />
            {parts && (
              <dl className="bx-facts wide">
                <div><dt>Region</dt><dd className="mono">{parts.host}</dd></div>
                <div><dt>Account ID</dt><dd className="mono">{parts.account}</dd></div>
                <div><dt>Environment ID</dt><dd className="mono">{parts.environment}</dd></div>
                <div><dt>Topic</dt><dd className="mono">{parts.topic}</dd></div>
              </dl>
            )}
            <Input label="Environment token" obj={es} k="token" onChange={set} mono wide placeholder="${BOOMI_ES_PRODUCE_TOKEN}"
                   invalid={!ENV_REF.test(es.token ?? "")}
                   hint={tokenChanged
                     ? "The gateway resolves this variable from .env when the plugin reloads."
                     : config.eventstreams.tokenEnvSet
                       ? "Reference to .env. The variable is set."
                       : "Reference to .env. The variable is not set: add it to .env and run docker compose up -d ignition django."} />
            <Select label="Messages" obj={es} k="esMode" onChange={set} options={[["batch", "One message per batch"], ["message", "One message per tag value"]]} />
            <Select label="Produce API" obj={es} k="esApi" onChange={set} options={[["auto", "Detect from URL"], ["single", "Single message"], ["multi", "Multi message"]]} />
            <Input label="Timeout (ms)" obj={es} k="timeoutMs" onChange={set} type="number" mono />
            <Input label="Retry after (s)" obj={es} k="retryAfterSeconds" onChange={set} type="number" mono
                   hint="After a failure the plugin uses the failover for this long, then tries Event Streams again." />
          </div>
          <p className="bx-note">
            Delivery is at least once: a batch is retried until Event Streams answers 2xx, and receivers de-duplicate on
            messageId. Test connection sends no message.
          </p>
          <ActionBar onTest={test} onSave={save} onRevert={() => setDraft((d) => ({ ...d, eventstreams: clone(config.eventstreams) }))}
                     dirty={dirty} invalid={invalid} />
        </section>
        <div className="bx-stack">
          <RouteState plugin={plugin} name={config.eventstreams.name} enabled={config.eventstreams.enabled} />
          <section className="cr-panel">
            <div className="cr-ph"><h2>Failover</h2></div>
            <FailoverState plugin={plugin} />
          </section>
        </div>
      </div>
    </>
  );
}

function FailoverState({ plugin }) {
  const main = plugin?.delivery?.routes?.find((r) => r.name === "main");
  const list = main?.destinations ?? [];
  if (!list.length) return <p className="muted">Plugin status not reachable.</p>;
  return (
    <ol className="bx-chain">
      {list.map((d, i) => (
        <li key={d.name} className={main.activeDestination === d.name ? "on" : d.state === "down" ? "bad" : ""}>
          <span className="n">{i + 1}</span>
          <div>
            <b>{d.name}</b> <span className="muted">{d.role} · {d.transport}</span>
            <div className="muted">
              {main.activeDestination === d.name ? "carrying the data now" : d.state === "down" ? "down" : d.state === "up" ? "ready" : "not used yet"}
            </div>
          </div>
        </li>
      ))}
    </ol>
  );
}
