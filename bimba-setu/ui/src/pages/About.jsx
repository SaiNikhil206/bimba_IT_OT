import { Link } from "react-router-dom";
import { useSession } from "../session.jsx";
import { Card, Flow, PageHeader } from "../ui.jsx";

// Signed in, /about: what Setu is, how data moves through it, what it works with, and where this user can go from here.
// Belongs to no organization (like the profile); the start page only for users whose roles open no page yet. Only the
// pages this user's roles allow are linked (pages / href come from the shell). Signed out, / and /about show the
// promotion page instead (Promo.jsx), which uses the lists below.

export const FEATURES = [
  { icon: "⌂", title: "Collect", text: "Site agents on the plant's computers read machines over open protocols and send the readings to Setu over mutual TLS." },
  { icon: "▤", title: "Standardize", text: "Every reading becomes one canonical BIMBA message with quality and units. Readings that do not fit are kept in quarantine." },
  { icon: "⇉", title: "Deliver", text: "Pipelines filter, batch and shape the messages for each destination, fail over to a second one and keep batches on disk while it is down." },
  { icon: "▣", title: "Control", text: "Authorized users send commands back to machines. Safety-relevant writes wait for a second person to approve them." },
  { icon: "▦", title: "Separate", text: "Each organization sees only its own machines, pipelines and people. One user can work in several organizations." },
  { icon: "≡", title: "Account for", text: "Roles decide who may do what, and the audit log records every change: who, what and when." },
];

export const SOURCES = ["OPC UA", "OPC DA", "Modbus TCP", "MQTT", "Siemens S7", "EtherNet/IP", "DNP3", "IEC 104", "SQL / historian"];
export const DESTINATIONS = ["HTTP", "Event Streams", "Kafka", "MQTT", "SFTP / FTP files", "MLLP · HL7 v2", "JMS · AMQP", "JDBC databases", "Splunk", "Elasticsearch", "InfluxDB"];
export const PLATFORMS = ["Windows", "Linux", "Raspberry Pi"];

/** one line per page this user can open */
const GUIDE = {
  "/overview": "Is data being collected and delivered right now?",
  "/agents": "The computers at your sites that collect machine data.",
  "/machines": "Each machine: its source, live readings and commands.",
  "/registry": "Every tag Setu knows, with its unit and type.",
  "/pipelines": "What goes where, filtered, batched and shaped.",
  "/destinations": "The systems that receive your data.",
  "/quarantine": "Readings refused because they did not fit the format.",
  "/users": "People in this organization and their roles.",
  "/roles": "What each role may see and do.",
  "/tokens": "One-time tokens to enroll a new site agent.",
  "/audit": "Who changed what, and when.",
  "/organizations": "All organizations on this Setu (platform admins).",
};

export default function About({ pages = [], href }) {
  const to = (path) => {
    const p = pages.find((x) => x.path === path);
    return p ? href(p) : undefined;
  };

  return (
    <>
      <PageHeader title="About BIMBA Setu"
                  subtitle="Setu means bridge. It carries machine data from the plant floor to the systems that use it, and commands back." />

      <Card title="How data moves" subtitle={`From the machine to the systems that use its data.${pages.length ? " Open a step to see it." : ""}`}>
        <div className="about-flow">
          <Flow label="How data moves through Setu" steps={[
            { key: "m", label: "Machines", value: "PLCs, RTUs, historians", sub: "Any vendor, any SCADA", to: to("/machines") },
            { key: "a", label: "Site agents", value: "At your sites", sub: PLATFORMS.join(", "), to: to("/agents") },
            { key: "s", label: "Setu", value: "Checks and standardizes", sub: "Canonical BIMBA message", to: to("/overview") },
            { key: "p", label: "Pipelines", value: "Route and shape", sub: "Filter, batch, transform", to: to("/pipelines") },
            { key: "d", label: "Destinations", value: "Your systems", sub: "Cloud, MES, ERP, databases", to: to("/destinations") },
          ]} />
        </div>
      </Card>

      <Card title="What Setu does">
        <div className="about-feat">
          {FEATURES.map((f) => (
            <div key={f.title}>
              <span className="about-ic" aria-hidden="true">{f.icon}</span>
              <div><h3>{f.title}</h3><p>{f.text}</p></div>
            </div>
          ))}
        </div>
      </Card>

      <Card title="Works with" subtitle="Open protocols and standard formats: no lock-in to one SCADA or cloud vendor.">
        <div className="about-with">
          <Chips title="Reads from" items={SOURCES} />
          <Chips title="Delivers to" items={DESTINATIONS} />
          <Chips title="Site agents run on" items={PLATFORMS} />
        </div>
      </Card>

      <Yours pages={pages} href={href} />
    </>
  );
}

/** signed in: the pages this user can open, and who they are here */
function Yours({ pages, href }) {
  const s = useSession();
  const mine = s.org && s.organizations.find((o) => o.id === s.org.id);
  const roles = mine?.roles?.length ? mine.roles.join(", ") : s.user.platformAdmin ? "Platform admin" : "None";
  const go = pages.filter((p) => GUIDE[p.path]);
  return (
    <div className="about-two">
      {go.length > 0 && (
        <Card title="Where to go next" subtitle="The pages your roles open." flush>
          <nav className="about-go" aria-label="Pages">
            {go.map((p) => (
              <Link key={p.path} to={href(p)}>
                <span className="ic" aria-hidden="true">{p.icon}</span>
                <span><b className="ellipsis" title={p.label}>{p.label}</b><small>{GUIDE[p.path]}</small></span>
              </Link>
            ))}
          </nav>
        </Card>
      )}
      <Card title="You and this Setu">
        <dl className="about-kv">
          <dt>Signed in as</dt><dd title={s.user.email}>{s.user.name}{s.user.email !== s.user.name && <small>{s.user.email}</small>}</dd>
          <dt>Organization</dt><dd title={s.org?.name}>{s.org?.name ?? "None yet"}</dd>
          <dt>Your roles</dt><dd title={roles}>{roles}</dd>
          <dt>Version</dt><dd className="mono">v{s.version}</dd>
        </dl>
        <p className="muted small about-help">
          {go.length ? "Need a page you cannot see? Ask an administrator of your organization for a role that includes it."
            : "Your roles do not open any pages yet. Ask an administrator of your organization for access."}
          {" "}<Link to="/profile">Your profile</Link>
        </p>
      </Card>
    </div>
  );
}

function Chips({ title, items }) {
  return (
    <div>
      <h3>{title}</h3>
      <div>{items.map((i) => <span className="chip" key={i}>{i}</span>)}</div>
    </div>
  );
}
