import { api, clone } from "./api.js";
import RouteState from "./RouteState.jsx";
import { ActionBar, Check, Input, Select } from "./ui.jsx";

const ENV_REF = /^\$\{BOOMI_ES_[A-Z0-9_]+\}$/;

/** https://<region host>/rest/<api>/<account>/<environment id>/<topic> */
function parseUrl(url) {
  const m = /^https:\/\/([^/]+)\/rest\/([^/]+)\/([^/]+)\/([^/]+)\/([^/?#]+)/.exec((url ?? "").trim());
  return m ? { host: m[1], api: m[2], account: m[3], environment: m[4], topic: decodeURIComponent(m[5]) } : null;
}

export default function EventStreams({ config, draft, setDraft, reload, dirty, plugin, pluginError }) {
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
      <p className="kicker">IT target</p>
      <h1>Configure Boomi Event Streams</h1>
      <p className="lead">
        The primary path: batches are produced to this topic, and the Boomi listener process stores them in PostgreSQL.
        If Event Streams fails, the plugin fails over to BIMBA Ingest.
      </p>
      {config.eventstreams.literalToken && (
        <p className="note err">
          <b>config.json holds a literal token.</b> Put it in .env as BOOMI_ES_PRODUCE_TOKEN and save the reference{" "}
          <span className="mono">{"${BOOMI_ES_PRODUCE_TOKEN}"}</span> here instead.
        </p>
      )}
      <div className="card">
        <div className="card-b">
          <div className="fields">
            <Check label={`Enabled (${es.role} destination)`} obj={es} k="enabled" onChange={set} wide />
            <Input label="Topic REST URL" obj={es} k="url" onChange={set} mono wide invalid={!String(es.url ?? "").startsWith("https://")}
                   hint="From Event Streams: topic → View REST. The single-message URL contains /singlemsg/." />
            {parts && (
              <div className="field wide">
                <dl className="facts">
                  <div><dt>Region</dt><dd className="mono small">{parts.host}</dd></div>
                  <div><dt>Account ID</dt><dd className="mono small">{parts.account}</dd></div>
                  <div><dt>Environment ID</dt><dd className="mono small">{parts.environment}</dd></div>
                  <div><dt>Topic</dt><dd className="mono small">{parts.topic}</dd></div>
                </dl>
              </div>
            )}
            <Input label="Environment token" obj={es} k="token" onChange={set} mono wide placeholder="${BOOMI_ES_PRODUCE_TOKEN}"
                   invalid={!ENV_REF.test(es.token ?? "")}
                   hint={
                     tokenChanged
                       ? "The gateway resolves this variable from .env when the plugin reloads."
                       : config.eventstreams.tokenEnvSet
                         ? "Reference to .env. The variable is set."
                         : "Reference to .env. The variable is not set: add it to .env and run docker compose up -d gateway django."
                   } />
            <Select label="Messages" obj={es} k="esMode" onChange={set}
                    options={[["batch", "One message per batch"], ["message", "One message per tag value"]]} />
            <Select label="Produce API" obj={es} k="esApi" onChange={set}
                    options={[["auto", "Detect from URL"], ["single", "Single message"], ["multi", "Multi message"]]} />
            <Input label="Timeout (ms)" obj={es} k="timeoutMs" onChange={set} type="number" mono />
            <Input label="Retry after (s)" obj={es} k="retryAfterSeconds" onChange={set} type="number" mono
                   hint="After a failure the plugin uses the failover for this long, then tries Event Streams again." />
          </div>
          <p className="muted small" style={{ margin: "16px 0 0" }}>
            Delivery is at least once: a batch is retried until Event Streams answers 2xx, and receivers de-duplicate on
            messageId. Test connection sends no message.
          </p>
        </div>
        <ActionBar onTest={test} onSave={save}
                   onRevert={() => setDraft((d) => ({ ...d, eventstreams: clone(config.eventstreams) }))}
                   dirty={dirty} invalid={invalid} />
      </div>
      <RouteState plugin={plugin} pluginError={pluginError} name={config.eventstreams.name} enabled={config.eventstreams.enabled} />
    </>
  );
}
