import { useEffect } from "react";
import { Link } from "react-router-dom";
import { useSession } from "../session.jsx";
import { Msg } from "../ui.jsx";
import { DESTINATIONS, FEATURES, PLATFORMS, SOURCES } from "./About.jsx";

// Signed out, / and /about: what Setu does for a plant, written for a prospective client, and how to reach the vendor.
// The contact details come from Setu's settings (bimba.contact.*, web/ContactInfo.java): only what is set is shown, and
// without an e-mail or phone the page has no contact section, only "Sign in" for existing users.

const PAINS = [
  { title: "Data stuck in silos", text: "Every SCADA, PLC and historian speaks its own language, and your business systems cannot hear any of them." },
  { title: "Fragile point-to-point scripts", text: "One-off integrations break when a network blinks, and readings are lost without anyone noticing." },
  { title: "Each new system is a new project", text: "Adding a cloud platform, MES or data lake means another round of custom code at every site." },
];

const STEPS = [
  { title: "Install a site agent", text: `A small agent on a plant computer (${PLATFORMS.join(", ")}) enrolls with a one-time token and connects over mutual TLS.` },
  { title: "Connect your machines", text: "Pick the protocol, scan the network, choose the tags. Setu checks every reading and turns it into one standard message." },
  { title: "Deliver where it matters", text: "Pipelines route, filter and shape the data for each system, with failover and buffering so nothing is lost." },
];

const INDUSTRIES = [
  { icon: "≈", title: "Water and wastewater", text: "Pumps, levels and quality readings from every station, in one place." },
  { icon: "ϟ", title: "Energy and utilities", text: "DNP3 and IEC 104 telemetry from substations and plants, delivered securely." },
  { icon: "⚙", title: "Manufacturing", text: "Line and machine data for MES, OEE and maintenance systems." },
  { icon: "⚗", title: "Labs and process plants", text: "Instrument and batch data, including HL7 v2 for laboratory systems." },
];

const TRUST = [
  { title: "Secure by design", text: "Mutual TLS from every site, roles for each person, second-person approval for safety-relevant writes, and a full audit log." },
  { title: "Nothing lost", text: "Batches wait on disk while a destination is down and are delivered when it is back; a second destination can take over." },
  { title: "No lock-in", text: "Open protocols and standard formats. Keep your SCADA, your cloud and your vendors; Setu sits between them." },
  { title: "Low upkeep", text: "Site agents update themselves, and one console serves every site and every organization you run." },
];

const SUBJECT = "BIMBA Setu: request a demo";
const BODY = "Hello,\n\nI would like to see BIMBA Setu.\n\nCompany:\nSites and machines:\nSystems we want to connect:\n\nThank you.";

export default function Promo() {
  const s = useSession();
  const c = s.contact ?? {};
  const mail = c.email ? `mailto:${c.email}?subject=${encodeURIComponent(SUBJECT)}&body=${encodeURIComponent(BODY)}` : null;
  const tel = c.phone ? `tel:${c.phone.replace(/[^0-9+]/g, "")}` : null;
  const reach = mail || tel;
  const by = c.company ? ` by ${c.company}` : "";

  useEffect(() => {
    document.title = `BIMBA Setu${by}: connect every machine to every system`;
  }, [by]);

  return (
    <div className="promo">
      <header className="promo-top">
        <Link className="brand" to="/" title={`BIMBA Setu${by}`}>
          <span className="mark" aria-hidden="true">B</span>
          <span><b>BIMBA Setu</b>{c.company && <small className="ellipsis">by {c.company}</small>}</span>
        </Link>
        <nav className="promo-links" aria-label="Sections">
          <a href="#how">How it works</a>
          <a href="#features">Features</a>
          {reach && <a href="#contact">Contact</a>}
        </nav>
        <div className="promo-acts">
          <Link className="btn ghost sm" to="/login">Sign in</Link>
          {reach && <a className="btn sm promo-wide" href={mail ?? tel}>Talk to us</a>}
        </div>
      </header>

      <main id="main">
        {s.message && <div className="promo-in promo-msg"><Msg kind="info">{s.message}</Msg></div>}

        <section className="promo-hero promo-in">
          <div className="promo-hero-t">
            <span className="promo-eyebrow">Industrial data integration</span>
            <h1>Your plant's data, <em>where your business needs it.</em></h1>
            <p className="promo-lead">
              Setu means bridge. It connects every machine, of any vendor and behind any SCADA, to your cloud, MES, ERP and
              analytics: reliably, securely and without lock-in.
            </p>
            <div className="promo-cta">
              {mail && <a className="btn" href={mail}>Book a demo</a>}
              {!mail && tel && <a className="btn" href={tel}>Call us</a>}
              <a className="btn ghost" href="#how">See how it works</a>
            </div>
            <ul className="promo-facts" aria-label="At a glance">
              <li><b>{SOURCES.length}</b> machine protocols</li>
              <li><b>{DESTINATIONS.length}</b> kinds of destination</li>
              <li><b>{PLATFORMS.length}</b> site platforms</li>
            </ul>
          </div>
          <Bridge />
        </section>

        <section className="promo-sec promo-in" aria-labelledby="pain">
          <h2 id="pain" className="promo-h">Sound familiar?</h2>
          <div className="promo-grid3">
            {PAINS.map((p) => (
              <div className="promo-card pain" key={p.title}><h3>{p.title}</h3><p>{p.text}</p></div>
            ))}
          </div>
          <p className="promo-answer">Setu replaces all of it with <b>one bridge</b>: connect a machine once, use its data everywhere.</p>
        </section>

        <section className="promo-sec promo-in" id="how" aria-labelledby="how-h">
          <span className="promo-eyebrow">How it works</span>
          <h2 id="how-h" className="promo-h">From machine to business system in three steps</h2>
          <ol className="promo-steps">
            {STEPS.map((st, i) => (
              <li key={st.title}><span className="promo-num" aria-hidden="true">{i + 1}</span><div><h3>{st.title}</h3><p>{st.text}</p></div></li>
            ))}
          </ol>
        </section>

        <section className="promo-sec promo-in" id="features" aria-labelledby="feat-h">
          <span className="promo-eyebrow">Features</span>
          <h2 id="feat-h" className="promo-h">Everything between the machine and the dashboard</h2>
          <div className="promo-grid3">
            {FEATURES.map((f) => (
              <div className="promo-card" key={f.title}>
                <span className="about-ic" aria-hidden="true">{f.icon}</span>
                <h3>{f.title}</h3><p>{f.text}</p>
              </div>
            ))}
          </div>
        </section>

        <section className="promo-band" aria-labelledby="trust-h">
          <div className="promo-in">
            <h2 id="trust-h" className="promo-h">Built for operations that cannot stop</h2>
            <div className="promo-grid4">
              {TRUST.map((t) => <div key={t.title}><h3>{t.title}</h3><p>{t.text}</p></div>)}
            </div>
          </div>
        </section>

        <section className="promo-sec promo-in" aria-labelledby="ind-h">
          <span className="promo-eyebrow">Who it is for</span>
          <h2 id="ind-h" className="promo-h">Made for the plants that run the world</h2>
          <div className="promo-grid4">
            {INDUSTRIES.map((x) => (
              <div className="promo-card" key={x.title}>
                <span className="about-ic" aria-hidden="true">{x.icon}</span>
                <h3>{x.title}</h3><p>{x.text}</p>
              </div>
            ))}
          </div>
        </section>

        <section className="promo-sec promo-in" aria-labelledby="with-h">
          <h2 id="with-h" className="promo-h">Works with what you already have</h2>
          <div className="promo-with">
            <div><h3>Reads from</h3><div>{SOURCES.map((x) => <span className="chip" key={x}>{x}</span>)}</div></div>
            <div><h3>Delivers to</h3><div>{DESTINATIONS.map((x) => <span className="chip" key={x}>{x}</span>)}</div></div>
          </div>
        </section>

        {reach && (
          <section className="promo-sec promo-in" id="contact" aria-labelledby="contact-h">
            <div className="promo-contact">
              <div className="promo-contact-t">
                <h2 id="contact-h">Let's connect your first plant</h2>
                <p>Tell us about your sites, machines and the systems you want to feed. We will show you Setu on your own use case.</p>
              </div>
              <div className="promo-contact-l">
                {mail && (
                  <a className="promo-reach" href={mail}>
                    <span aria-hidden="true">✉</span><span><small>E-mail</small><b className="ellipsis" title={c.email}>{c.email}</b></span>
                  </a>
                )}
                {tel && (
                  <a className="promo-reach" href={tel}>
                    <span aria-hidden="true">☏</span><span><small>Phone</small><b className="ellipsis" title={c.phone}>{c.phone}</b></span>
                  </a>
                )}
                {c.website && (
                  <a className="promo-reach" href={c.website} target="_blank" rel="noopener noreferrer">
                    <span aria-hidden="true">↗</span><span><small>Website</small><b className="ellipsis" title={c.website}>{c.website.replace(/^https?:\/\//, "")}</b></span>
                  </a>
                )}
              </div>
            </div>
          </section>
        )}
      </main>

      <footer className="promo-foot promo-in">
        <span>© {new Date().getFullYear()} {c.company ?? "BIMBA Setu"} · v{s.version}</span>
        <span>Already a customer? <Link to="/login">Sign in</Link></span>
      </footer>
    </div>
  );
}

/** the hero picture: machines on the left, Setu in the middle, business systems on the right, data moving across */
function Bridge() {
  const left = ["PLC", "RTU", "Historian", "SCADA"];
  const right = ["Cloud", "MES", "ERP", "Analytics"];
  return (
    <div className="bridge" aria-hidden="true">
      <div className="bridge-col">{left.map((x) => <span key={x}>{x}</span>)}</div>
      <div className="bridge-mid">
        <i className="bridge-line" /><i className="bridge-dot" /><i className="bridge-dot d2" /><i className="bridge-dot d3" />
        <span className="bridge-hub"><span className="mark">B</span><b>Setu</b></span>
      </div>
      <div className="bridge-col right">{right.map((x) => <span key={x}>{x}</span>)}</div>
    </div>
  );
}
