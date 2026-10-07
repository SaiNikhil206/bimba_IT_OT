import { useId, useState } from "react";

// Form controls for the Bridge Console pages (Control Room design).

export function Field({ label, hint, wide, children, error }) {
  const id = useId();
  return (
    <div className={`bx-field${wide ? " wide" : ""}${error ? " has-err" : ""}`}>
      <label htmlFor={id}>{label}</label>
      {typeof children === "function" ? children(id) : children}
      {(error || hint) && <span className={error ? "err" : "hint"}>{error || hint}</span>}
    </div>
  );
}

/** Text / number / password input bound to obj[k]; onChange(k, value). */
export function Input({ label, hint, wide, obj, k, onChange, type = "text", mono, disabled, placeholder, invalid }) {
  return (
    <Field label={label} hint={hint} wide={wide} error={typeof invalid === "string" ? invalid : null}>
      {(id) => (
        <input id={id} type={type} className={`${mono ? "mono" : ""}${invalid ? " invalid" : ""}`} value={obj[k] ?? ""}
               placeholder={placeholder} disabled={disabled} spellCheck={false}
               autoComplete={type === "password" ? "new-password" : "off"}
               onChange={(e) => onChange(k, type === "number" ? (e.target.value === "" ? "" : Number(e.target.value)) : e.target.value)} />
      )}
    </Field>
  );
}

/** options: strings or [value, label] pairs */
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

/** On/off switch. */
export function Toggle({ label, hint, obj, k, onChange, wide }) {
  const id = useId();
  return (
    <div className={`bx-field${wide ? " wide" : ""}`}>
      <label className="bx-toggle" htmlFor={id}>
        <input id={id} type="checkbox" checked={!!obj[k]} onChange={(e) => onChange(k, e.target.checked)} />
        <span className="sw" aria-hidden="true"><i /></span>
        {label}
      </label>
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

/** Footer: Test connection + result, then Revert / Save. */
export function ActionBar({ onTest, onSave, onRevert, dirty, invalid, testLabel = "Test connection" }) {
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(null);

  const run = async (kind, fn) => {
    setBusy(kind);
    setMsg(null);
    try {
      const r = await fn();
      setMsg({ ok: r?.ok !== false, text: r?.message ?? "Done." });
    } catch (e) {
      setMsg({ ok: false, text: e.message });
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="bx-actions">
      {onTest && (
        <button className="bx-btn" type="button" disabled={!!busy} onClick={() => run("test", onTest)}>
          {busy === "test" ? <span className="spin" aria-hidden="true" /> : <span aria-hidden="true">⚡</span>} {testLabel}
        </button>
      )}
      <span className={`bx-result${msg ? (msg.ok ? " ok" : " bad") : ""}`} aria-live="polite">
        {busy === "save" ? "Saving…" : busy === "test" ? "Testing…"
          : msg ? <><b>{msg.ok ? "✓" : "✕"}</b> {msg.text}</>
          : invalid ? <span className="bad">{invalid}</span> : null}
      </span>
      <span className="grow" />
      {dirty && !busy && <span className="bx-dirty">● Unsaved changes</span>}
      {onRevert && (
        <button className="bx-btn ghost" type="button" disabled={!!busy || !dirty} onClick={() => { onRevert(); setMsg(null); }}>
          Revert
        </button>
      )}
      {onSave && (
        <button className="bx-btn primary" type="button" disabled={!!busy || !dirty || !!invalid} onClick={() => run("save", onSave)}>
          Save to plugin
        </button>
      )}
    </div>
  );
}

export function Seg({ items, value, onChange, label }) {
  if (items.length < 2) return null;
  return (
    <div className="cr-seg" role="group" aria-label={label}>
      {items.map((it) => (
        <button key={it} type="button" className={it === value ? "on" : ""} aria-pressed={it === value} onClick={() => onChange(it)}>{it}</button>
      ))}
    </div>
  );
}
