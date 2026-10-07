import { useEffect, useState } from "react";

// Chart colors (validated reference palette, light/dark steps). SVG presentation
// attributes can't read CSS variables reliably, so charts take hex from here.
const LIGHT = {
  surface: "#fcfcfb", grid: "#e1e0d9", axis: "#c3c2b7", muted: "#898781",
  text: "#0b0b0b", text2: "#52514e",
  series: ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4"],
};
const DARK = {
  surface: "#1a1a19", grid: "#2c2c2a", axis: "#383835", muted: "#898781",
  text: "#ffffff", text2: "#c3c2b7",
  series: ["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181"],
};

// Each end-system path keeps its own fixed slot: color follows the entity, never its rank.
export const VIA_ORDER = ["boomi-es", "bimba-rest", "boomi-rest", "boomi-mqtt", "bimba-mqtt"];
export const VIA_LABEL = {
  "boomi-es": "Boomi · Event Streams",
  "boomi-rest": "Boomi · REST",
  "boomi-mqtt": "Boomi · MQTT",
  "bimba-rest": "BIMBA Ingest · REST",
  "bimba-mqtt": "BIMBA Ingest · MQTT",
};

export function useChartTheme() {
  const query = "(prefers-color-scheme: dark)";
  const [dark, setDark] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const on = (e) => setDark(e.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  return dark ? DARK : LIGHT;
}
