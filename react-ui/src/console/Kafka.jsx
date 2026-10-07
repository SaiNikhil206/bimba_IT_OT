import { api, clone } from "./api.js";
import RouteState from "./RouteState.jsx";
import { ActionBar, Check, Input, Select } from "./ui.jsx";

export default function Kafka({ config, draft, setDraft, reload, dirty, plugin, pluginError }) {
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
      <p className="kicker">IT target</p>
      <h1>Configure Kafka</h1>
      <p className="lead">
        The gateway copies every batch to this topic as a mirror route, with its own retry and disk buffer, so Kafka
        being down never holds up the primary path.
      </p>
      {!k.exists && <p className="note">config.json has no Kafka destination yet. Saving creates one (role mirror).</p>}
      <div className="card">
        <div className="card-b">
          <div className="fields">
            <Check label="Enabled (mirror every batch to Kafka)" obj={k} k="enabled" onChange={set} wide />
            <Input label="Bootstrap servers" obj={k} k="bootstrapServers" onChange={set} mono wide
                   hint="Comma separated host:port, as the gateway reaches them (kafka:9092 inside Docker)."
                   invalid={!String(k.bootstrapServers ?? "").trim()} />
            <Select label="Security protocol" obj={k} k="securityProtocol" onChange={set}
                    options={["PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL"]} />
            <Select label="SASL mechanism" obj={k} k="saslMechanism" onChange={set} disabled={!sasl}
                    options={["PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512"]} />
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
          <p className="muted small" style={{ margin: "16px 0 0" }}>
            Acknowledgement: <span className="mono">acks=all</span> with the idempotent producer (fixed by the plugin).
          </p>
        </div>
        <ActionBar onTest={test} onSave={save} onRevert={() => setDraft((d) => ({ ...d, kafka: clone(config.kafka) }))}
                   dirty={dirty} invalid={invalid} />
      </div>
      <RouteState plugin={plugin} pluginError={pluginError} name={config.kafka.name} enabled={config.kafka.enabled} />
    </>
  );
}
