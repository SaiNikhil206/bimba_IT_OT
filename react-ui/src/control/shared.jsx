import { STATUS_ICON } from "./viz.jsx";

// Bits shared by the Control Room overview and the machine dashboard.

export const VIA = {
  "boomi-es": "Boomi Event Streams",
  "bimba-rest": "BIMBA Ingest · REST",
  "boomi-rest": "Boomi · REST",
  "boomi-mqtt": "Boomi · MQTT",
  "bimba-mqtt": "BIMBA Ingest · MQTT",
  "bimba-gateway": "BIMBA Gateway · direct",
};

export const machinePath = (id) => `/control-room/machine/${encodeURIComponent(id)}`;

export function AlarmList({ alarms, link, empty = "No alarms. Every route is delivering and every tag reads GOOD." }) {
  return (
    <ul className="cr-alarms">
      {alarms.length === 0 && <li className="clear">{empty}</li>}
      {alarms.slice(0, 7).map((a, i) => (
        <li key={i} className={a.s}>
          <span className="ic" aria-hidden="true">{STATUS_ICON[a.s]}</span>
          {a.m && link ? <a href={machinePath(a.m)} onClick={link(machinePath(a.m))}><b>{a.t}</b></a> : <b>{a.t}</b>}
          <span className="muted">{a.d}</span>
        </li>
      ))}
    </ul>
  );
}
