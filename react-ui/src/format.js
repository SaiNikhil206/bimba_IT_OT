export function ago(iso) {
  if (!iso) return "–";
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 2) return "now";
  if (s < 60) return `${Math.round(s)}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  return `${Math.round(s / 3600)}h ago`;
}

export function clock(iso) {
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function fmtValue(t) {
  if (t.num === null || t.num === undefined) return t.text ?? "–";
  if (t.dataType === "boolean" || t.text === "true" || t.text === "false") return t.num ? "ON" : "OFF";
  const n = Number(t.num);
  const abs = Math.abs(n);
  return n.toLocaleString(undefined, { maximumFractionDigits: abs >= 1000 ? 0 : abs >= 100 ? 1 : 2 });
}
