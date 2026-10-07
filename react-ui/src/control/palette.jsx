import { useEffect, useMemo, useRef, useState } from "react";
import { fmtNum, good, isBool, specOf, unit } from "./lib.js";
import { machinePath } from "./shared.jsx";

// Ctrl+K search over machines and tags; Enter opens the machine's dashboard.

export function useHotkey(open) {
  useEffect(() => {
    const on = (e) => {
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target?.tagName ?? "");
      if ((e.key === "k" || e.key === "K") && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        open();
      } else if (e.key === "/" && !typing) {
        e.preventDefault();
        open();
      }
    };
    window.addEventListener("keydown", on);
    return () => window.removeEventListener("keydown", on);
  }, [open]);
}

export default function Palette({ machines, onClose, go }) {
  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const input = useRef(null);
  useEffect(() => input.current?.focus(), []);

  const items = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const out = [];
    for (const m of machines) {
      const hitM = !needle || m.machineId.toLowerCase().includes(needle) || m.kind.includes(needle);
      if (hitM) out.push({ key: m.machineId, m, title: m.machineId, sub: `${m.plantId} · ${Object.keys(m.tags).length} tags` });
      if (!needle) continue;
      for (const t of Object.values(m.tags)) {
        const label = specOf(t.tag).label;
        if (t.tag.toLowerCase().includes(needle) || label.toLowerCase().includes(needle)) {
          const v = !good(t) ? t.quality : isBool(t) ? (t.num ? "ON" : "OFF") : t.num != null ? `${fmtNum(t.num)} ${unit(t.unit)}` : t.text;
          out.push({ key: `${m.machineId}/${t.tag}`, m, title: label, sub: m.machineId, val: v });
        }
      }
    }
    return out.slice(0, 30);
  }, [q, machines]);

  const pick = (it) => {
    onClose();
    go(machinePath(it.m.machineId));
  };
  const key = (e) => {
    if (e.key === "Escape") onClose();
    else if (e.key === "ArrowDown") { e.preventDefault(); setSel((s) => Math.min(items.length - 1, s + 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setSel((s) => Math.max(0, s - 1)); }
    else if (e.key === "Enter" && items[sel]) pick(items[sel]);
  };

  return (
    <div className="cr-pal-bg" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="cr-pal" role="dialog" aria-modal="true" aria-label="Search machines and tags">
        <input ref={input} value={q} onChange={(e) => { setQ(e.target.value); setSel(0); }} onKeyDown={key}
               placeholder="Search machines and tags…" aria-label="Search" />
        <ul role="listbox">
          {items.length === 0 && <li className="none">Nothing matches “{q}”.</li>}
          {items.map((it, i) => (
            <li key={it.key} role="option" aria-selected={i === sel}>
              <button className={i === sel ? "on" : ""} onMouseEnter={() => setSel(i)} onClick={() => pick(it)}>
                <i className={it.m.health} aria-hidden="true" />
                <span><b>{it.title}</b><span className="sub">{it.sub}</span></span>
                <span className="val">{it.val ?? "open →"}</span>
              </button>
            </li>
          ))}
        </ul>
        <div className="hint">↑ ↓ to move · Enter to open the machine dashboard · Esc to close</div>
      </div>
    </div>
  );
}
