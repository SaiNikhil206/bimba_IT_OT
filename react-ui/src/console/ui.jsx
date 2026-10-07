import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";

export function Field({ label, hint, wide, children }) {
  const id = useId();
  const child = typeof children === "function" ? children(id) : children;
  return (
    <div className={`field${wide ? " wide" : ""}`}>
      <label htmlFor={id}>{label}</label>
      {child}
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

/** Text/number/password input bound to obj[k]; onChange(k, value). */
export function Input({ label, hint, wide, obj, k, onChange, type = "text", mono, disabled, placeholder, invalid }) {
  return (
    <Field label={label} hint={hint} wide={wide}>
      {(id) => (
        <input
          id={id}
          type={type}
          className={`${mono ? "m" : ""}${invalid ? " invalid" : ""}`}
          value={obj[k] ?? ""}
          placeholder={placeholder}
          disabled={disabled}
          autoComplete={type === "password" ? "new-password" : "off"}
          spellCheck={false}
          onChange={(e) => onChange(k, type === "number" ? (e.target.value === "" ? "" : Number(e.target.value)) : e.target.value)}
        />
      )}
    </Field>
  );
}

/** options: array of strings or [value, label] pairs */
export function Select({ label, hint, wide, obj, k, onChange, options, disabled }) {
  return (
    <Field label={label} hint={hint} wide={wide}>
      {(id) => (
        <select id={id} value={obj[k] ?? ""} disabled={disabled} onChange={(e) => onChange(k, e.target.value)}>
          {options.map((o) => {
            const [v, l] = Array.isArray(o) ? o : [o, o];
            return <option key={String(v)} value={v}>{l}</option>;
          })}
        </select>
      )}
    </Field>
  );
}

export function Check({ label, hint, obj, k, onChange, wide }) {
  const id = useId();
  return (
    <div className={`field${wide ? " wide" : ""}`}>
      <label className="check" htmlFor={id}>
        <input id={id} type="checkbox" checked={!!obj[k]} onChange={(e) => onChange(k, e.target.checked)} />
        {label}
      </label>
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

export function Seg({ items, value, onChange, label }) {
  if (items.length < 2) return null;
  return (
    <div className="seg" role="group" aria-label={label}>
      {items.map((it) => (
        <button key={it} type="button" aria-pressed={it === value} onClick={() => onChange(it)}>{it}</button>
      ))}
    </div>
  );
}

/** Card footer: Test connection + result, then Revert / Save. */
export function ActionBar({ onTest, onSave, onRevert, dirty, invalid }) {
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(false);

  const run = async (fn, busyText) => {
    setBusy(true);
    setMsg({ cls: "busy", text: busyText });
    try {
      const r = await fn();
      setMsg({ cls: r?.ok === false ? "err" : "ok", text: `${r?.ok === false ? "✕" : "✓"} ${r?.message ?? "Done."}` });
    } catch (e) {
      setMsg({ cls: "err", text: `✕ ${e.message}` });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="test">
      {onTest && (
        <button className="btn" type="button" disabled={busy} onClick={() => run(onTest, "Testing…")}>
          Test connection
        </button>
      )}
      <span className={`result ${msg?.cls ?? ""}`} aria-live="polite">
        {msg?.text ?? (invalid ? <span className="result err">{invalid}</span> : dirty ? <span className="muted">Unsaved changes</span> : null)}
      </span>
      <span className="spacer" />
      {onRevert && (
        <button className="btn" type="button" disabled={busy || !dirty} onClick={() => { onRevert(); setMsg(null); }}>
          Revert
        </button>
      )}
      {onSave && (
        <button className="btn primary" type="button" disabled={busy || !dirty || !!invalid}
                onClick={() => run(onSave, "Saving…")}>
          Save
        </button>
      )}
    </div>
  );
}

/** Table cell that flashes when `flash` changes (a new value arrived). */
export function TickCell({ flash, className = "v", children }) {
  const ref = useRef(null);
  const first = useRef(true);
  useLayoutEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    const el = ref.current;
    if (!el || !flash) return;
    el.classList.add("on");
    let b;
    const a = requestAnimationFrame(() => (b = requestAnimationFrame(() => el.classList.remove("on"))));
    return () => {
      cancelAnimationFrame(a);
      cancelAnimationFrame(b);
    };
  }, [flash]);
  return <td ref={ref} className={`${className} tick`}>{children}</td>;
}

export function Quality({ q }) {
  if (!q || q === "GOOD") return null;
  return <span className={q === "BAD" ? "q-bad" : "q-unc"}>{q}</span>;
}

/** Re-render every `ms` so relative times ("3 s ago") stay current. */
export function useNow(ms = 1000) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

/** Syntax-highlighted JSON (input is escaped before markup is added). */
export function Json({ value }) {
  const html = esc(JSON.stringify(value, null, 2)).replace(
    /(&quot;(?:[^&]|&(?!quot;))*?&quot;)(\s*:)?|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|\b(?:true|false|null)\b/g,
    (m, s, c) => (s ? (c ? `<span class="j-k">${s}</span>${c}` : `<span class="j-s">${s}</span>`) : `<span class="j-n">${m}</span>`)
  );
  return <pre className="json" dangerouslySetInnerHTML={{ __html: html }} />;
}
