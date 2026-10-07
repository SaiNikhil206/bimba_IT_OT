import { useId } from "react";
import { KIND_LABEL, band, fmtNum, frac, good, isBool, isNum, since, specOf, unit } from "./lib.js";
import { Badge, Dial, Spark } from "./viz.jsx";

// One card per machine: an animated illustration of the asset + its tags as readouts with sparklines.

const HERO = { line: "speed", pump: "discharge_pressure", tank: "level", meter: "active_power" };

export default function AssetCard({ m, kind, series, selected, onSelect, href, onOpen }) {
  const tags = Object.values(m.tags).sort((a, b) => a.tag.localeCompare(b.tag));
  const bad = tags.filter((t) => !good(t)).length;
  const lost = tags.length > 0 && bad === tags.length;
  const health = lost ? "critical" : bad ? "warning" : "good";
  const heroTag = m.tags[HERO[kind]];

  return (
    <article className={`cr-asset ${health}${lost ? " lost" : ""}`}>
      <header>
        <div>
          <div className="k">{KIND_LABEL[kind]} · {m.plantId}</div>
          <h3><a href={href} onClick={onOpen} className="open">{m.machineId}</a></h3>
        </div>
        <div className="hd-r">
          <Badge state={health}>{lost ? "Signal lost" : bad ? `${bad} bad` : "Healthy"}</Badge>
          <a href={href} onClick={onOpen} className="open-btn" aria-label={`Open ${m.machineId} dashboard`}>Dashboard →</a>
        </div>
      </header>

      <div className="stage">
        <a className="art" href={href} onClick={onOpen} tabIndex={-1} aria-hidden="true">
          <Art m={m} kind={kind} />
        </a>
        {heroTag && isNum(heroTag) && good(heroTag) && (
          <Dial tag={heroTag.tag} value={heroTag.num} u={heroTag.unit} state={band(heroTag.tag, heroTag.num)} />
        )}
        {lost && (
          <div className="static">
            <b>NO SIGNAL</b>
            <span>BAD quality from Ignition · last value {since(m.lastSeen)} ago</span>
          </div>
        )}
      </div>

      <ul className="reads">
        {tags.map((t) => {
          const numeric = isNum(t) && good(t);
          const sel = selected?.tagId === t.tagId;
          const f = numeric ? frac(t.tag, t.num) : null;
          const st = numeric ? band(t.tag, t.num) : good(t) ? "good" : "critical";
          const fresh = t.flash && Date.now() - t.flash < 1200;
          return (
            <li key={t.tag}>
              <button className={`read${sel ? " sel" : ""}`} disabled={!numeric}
                      onClick={() => onSelect({ tagId: t.tagId, machineId: m.machineId, tag: t.tag, unit: t.unit })}
                      title={numeric ? "Open in the oscilloscope" : undefined}>
                <span className="nm">{specOf(t.tag).label}</span>
                <span className={`val${fresh ? " fresh" : ""}`}>
                  {!good(t) ? <em className="q">{t.quality ?? "?"}</em>
                    : isBool(t) ? <span className={`pill ${t.num ? "on" : ""}`}>{t.num ? "ON" : "OFF"}</span>
                    : numeric ? <>{fmtNum(t.num)}<small>{unit(t.unit)}</small></>
                    : <span className="txt">{t.text ?? "–"}</span>}
                </span>
                {numeric && <Spark points={series.get(t.tagId)} w={96} h={24} cls={st === "good" ? "s1" : st} />}
                {f != null && <span className="gauge"><i className={st} style={{ width: `${f * 100}%` }} /></span>}
              </button>
            </li>
          );
        })}
      </ul>
    </article>
  );
}

/** The animated illustration for a machine, driven by its live tags. */
export function Art({ m, kind }) {
  const v = (name) => (good(m.tags[name]) ? m.tags[name].num : null);
  const on = (name) => good(m.tags[name]) && !!m.tags[name].num;
  if (kind === "line") return <Line speed={v("speed")} running={on("running")} state={m.tags.state?.text} />;
  if (kind === "pump") return <Pump running={on("running")} tripped={on("tripped")} flow={v("inlet_flow")} />;
  if (kind === "tank") return <Tank level={v("level")} out={v("outlet_flow")} />;
  if (kind === "meter") return <Meter kw={v("active_power")} volt={v("bus_voltage")} closed={on("breaker_closed")} />;
  return <Generic />;
}

/** Conveyor: belt speed follows the line speed tag. */
function Line({ speed, running, state }) {
  const moving = running && speed > 0;
  const dur = moving ? Math.max(0.6, 240 / speed) * 2.2 : 0;
  const style = moving ? { "--belt": `${dur}s`, "--roll": `${dur / 4}s` } : undefined;
  return (
    <svg viewBox="0 0 220 130" className={`il line${moving ? " run" : ""}`} style={style}>
      <rect x="18" y="20" width="44" height="30" rx="4" className="metal" />
      <rect x="26" y="50" width="28" height="20" className="metal dim" />
      <text x="40" y="39" className="il-t">FILL</text>
      <g className="boxes">
        {[0, 1, 2, 3].map((i) => (
          <g key={i} className="box" style={{ animationDelay: `calc(var(--belt) * ${-i / 4})` }}>
            <rect x="-11" y="-20" width="22" height="20" rx="3" />
            <path d="M-11 -12h22" />
          </g>
        ))}
      </g>
      <rect x="10" y="78" width="200" height="16" rx="8" className="belt" />
      <line x1="18" x2="202" y1="86" y2="86" className="belt-marks" />
      {[22, 62, 102, 142, 182, 198].map((x) => (
        <g key={x} transform={`translate(${x} 86)`}><g className="roller"><circle r="6" /><path d="M-6 0h12M0 -6v12" /></g></g>
      ))}
      <path d="M30 94l-8 26M190 94l8 26M110 94v26" className="leg" />
      <circle cx="200" cy="30" r="6" className={`lamp ${running ? "on" : ""}`} />
      <text x="188" y="34" className="il-s end">{state ?? (running ? "RUNNING" : "STOPPED")}</text>
    </svg>
  );
}

function Pump({ running, tripped, flow }) {
  const dur = running && flow ? Math.max(0.25, 60 / flow) : 0;
  return (
    <svg viewBox="0 0 220 130" className={`il pump${running ? " run" : ""}${tripped ? " trip" : ""}`}
         style={dur ? { "--spin": `${dur}s` } : undefined}>
      <path d="M0 88h60M150 40h70" className="pipe" />
      <path d="M0 88h60M150 40h70" className="pipe-flow" />
      <rect x="120" y="30" width="40" height="20" rx="3" className="metal" />
      <circle cx="100" cy="72" r="40" className="casing" />
      <g transform="translate(100 72)">
        <g className="impeller">
          {[0, 60, 120, 180, 240, 300].map((a) => <path key={a} d="M0 0C8 -10 22 -14 30 -8" transform={`rotate(${a})`} />)}
          <circle r="8" />
        </g>
      </g>
      <rect x="150" y="96" width="60" height="26" rx="4" className="metal" />
      <text x="180" y="113" className="il-t">MOTOR</text>
      <circle cx="16" cy="20" r="6" className={`lamp ${tripped ? "trip" : running ? "on" : ""}`} />
      <text x="28" y="24" className="il-s">{tripped ? "TRIPPED" : running ? "RUNNING" : "STOPPED"}</text>
    </svg>
  );
}

function Tank({ level, out }) {
  const L = level == null ? 0 : Math.max(0, Math.min(100, level));
  const top = 18;
  const bottom = 112;
  const y = bottom - ((bottom - top) * L) / 100;
  const at = (p) => bottom - ((bottom - top) * p) / 100;
  const state = band("level", level);
  const clip = useId();
  return (
    <svg viewBox="0 0 220 130" className={`il tank ${state}`}>
      <defs>
        <clipPath id={clip}><rect x="60" y={top} width="100" height={bottom - top} rx="14" /></clipPath>
      </defs>
      <rect x="60" y={top} width="100" height={bottom - top} rx="14" className="shell" />
      <g clipPath={`url(#${clip})`}>
        <rect x="60" y={y} width="100" height={bottom - y + 2} className="liquid" />
        <g className="wave" style={{ transform: `translateY(${y}px)` }}>
          <path d="M0 0q12.5 -6 25 0t25 0t25 0t25 0t25 0t25 0t25 0t25 0t25 0t25 0v8h-250z" className="crest" />
        </g>
      </g>
      {[25, 85].map((p) => (
        <g key={p}>
          <line x1="54" x2="166" y1={at(p)} y2={at(p)} className="mark" />
          <text x="50" y={at(p) + 3} className="il-s end">{p === 85 ? "HI" : "LO"}</text>
        </g>
      ))}
      <path d="M160 100h60" className="pipe" />
      {out > 0 && <path d="M160 100h60" className="pipe-flow" />}
      <text x="110" y={Math.min(bottom - 6, Math.max(top + 16, y - 8))} className="il-big">
        {level == null ? "–" : `${Math.round(L)}%`}
      </text>
    </svg>
  );
}

function Meter({ kw, volt, closed }) {
  const amp = volt ? Math.max(6, Math.min(26, (volt - 300) / 5)) : 4;
  const d = Array.from({ length: 41 }, (_, i) => `${i ? "L" : "M"}${i * 12} ${Math.sin((i / 40) * Math.PI * 8) * amp}`).join("");
  return (
    <svg viewBox="0 0 220 130" className={`il meter${closed ? " live" : ""}`}>
      <rect x="10" y="14" width="200" height="72" rx="10" className="screen" />
      <svg x="14" y="18" width="192" height="64" viewBox="0 -32 240 64" preserveAspectRatio="none">
        <g className="wave-e"><path d={d} /></g>
      </svg>
      <g transform="translate(40 108)">
        <circle r="4" className="node" />
        <line x1="4" y1="0" x2="34" y2={closed ? 0 : -12} className="blade" />
        <circle cx="38" r="4" className="node" />
      </g>
      <text x="92" y="112" className="il-s">{closed ? "BREAKER CLOSED" : "BREAKER OPEN"}</text>
      <text x="200" y="32" className="il-s end">{kw != null ? `${fmtNum(kw, 1)} kW` : ""}</text>
    </svg>
  );
}

function Generic() {
  return (
    <svg viewBox="0 0 220 130" className="il generic">
      <rect x="70" y="24" width="80" height="80" rx="14" className="metal" />
      <path d="M84 64h14l6 -16l10 32l8 -24l6 8h12" className="ecg" />
    </svg>
  );
}
