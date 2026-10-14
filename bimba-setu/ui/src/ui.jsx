import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { api } from "./api.js";
import { useSession } from "./session.jsx";

// The console's small UI kit: every page is built from these pieces so the app looks the same everywhere.

// ------------------------------------------------------------------ formatting

export function when(iso) {
  if (!iso) return "never";
  const d = new Date(iso);
  const s = (Date.now() - d.getTime()) / 1000;
  if (s < 45) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return d.toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
}

export const full = (iso) => (iso ? new Date(iso).toLocaleString([], { dateStyle: "medium", timeStyle: "medium" }) : "");

export function browserName(ua) {
  if (!ua) return "Unknown browser";
  const b = /Edg\//.test(ua) ? "Edge" : /Chrome\//.test(ua) ? "Chrome" : /Firefox\//.test(ua) ? "Firefox" : /Safari\//.test(ua) ? "Safari" : "Browser";
  const os = /Windows/.test(ua) ? "Windows" : /Mac OS/.test(ua) ? "macOS" : /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Linux/.test(ua) ? "Linux" : "";
  return os ? `${b} on ${os}` : b;
}

export const initials = (u) => (u.name || u.email || u.username || "?").split(/\s+/).map((w) => w[0]).join("").slice(0, 2).toUpperCase();

/** The user's photo, or their initials. size in px; decorative (the name is always shown next to it). */
export function Avatar({ user, size = 32, className = "" }) {
  const style = size === 32 ? undefined : { width: size, height: size, fontSize: Math.round(size * 0.4) };
  return user.avatarUrl
    ? <img className={`avatar ${className}`} style={style} src={user.avatarUrl} alt="" aria-hidden="true" />
    : <span className={`avatar ${className}`} style={style} aria-hidden="true">{initials(user)}</span>;
}

// ------------------------------------------------------------------ layout pieces

export function Brand({ sub }) {
  const { version } = useSession();
  return (
    <div className="auth-brand">
      <span className="mark" aria-hidden="true">B</span>
      <div><b>BIMBA Setu</b><span>{sub ?? `SCADA edge server · v${version}`}</span></div>
    </div>
  );
}

export function PageHeader({ title, subtitle, children }) {
  return (
    <header className="ph">
      <div>
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {children && <div className="acts">{children}</div>}
    </header>
  );
}

export function Card({ title, subtitle, action, flush, children, className = "", ...rest }) {
  return (
    <section className={`card${flush ? " flush" : ""} ${className}`} {...rest}>
      {(title || action) && (
        <div className="card-h">
          <div>
            {title && <h2>{title}</h2>}
            {subtitle && <p className="sub">{subtitle}</p>}
          </div>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

/** Status: dot + text, never colour alone. kind = good | warn | bad | "" */
export function Status({ kind = "", children }) {
  return <span className={`st ${kind}`}><i aria-hidden="true" />{children}</span>;
}

export function Verdict({ kind, title, children }) {
  return (
    <section className={`verdict ${kind}`}>
      <span className="big" aria-hidden="true">{kind === "good" ? "✓" : "!"}</span>
      <div>
        <h2>{title}</h2>
        {children && <p>{children}</p>}
      </div>
    </section>
  );
}

/**
 * A left-to-right chain of steps (top to bottom on phones): what feeds what. step = {key, label, value, sub, on,
 * onClick | to}; a step with onClick is a button, with to a link, otherwise plain.
 */
export function Flow({ steps, label }) {
  return (
    <ol className="flow" aria-label={label}>
      {steps.map((s, i) => {
        const body = (
          <>
            <small className="flow-l">{s.label}</small>
            <b className="ellipsis" title={typeof s.value === "string" ? s.value : undefined}>{s.value}</b>
            {s.sub && <small className="ellipsis" title={typeof s.sub === "string" ? s.sub : undefined}>{s.sub}</small>}
          </>
        );
        const cls = `flow-i${s.on ? " on" : ""}`;
        return (
          <li key={s.key ?? i}>
            {i > 0 && <span className="flow-a" aria-hidden="true">→</span>}
            {s.onClick ? <button type="button" className={cls} aria-current={s.on || undefined} onClick={s.onClick}>{body}</button>
              : s.to ? <Link className={cls} to={s.to}>{body}</Link>
                : <div className={cls}>{body}</div>}
          </li>
        );
      })}
    </ol>
  );
}

export function Msg({ kind = "bad", children }) {
  if (!children) return null;
  return <div className={`msg ${kind}`} role={kind === "bad" ? "alert" : "status"}>{children}</div>;
}

export function Empty({ children, cols }) {
  return cols ? <tr><td colSpan={cols} className="empty">{children}</td></tr> : <p className="empty">{children}</p>;
}

// ------------------------------------------------------------------ dialog

/** Native modal dialog. footer = buttons; onClose on Esc / backdrop / Cancel. */
export function Modal({ title, subtitle, onClose, footer, children, wide }) {
  const ref = useRef(null);
  useEffect(() => {
    const d = ref.current;
    if (d && !d.open) d.showModal();
  }, []);
  return (
    <dialog ref={ref} className={wide ? "wide" : ""} aria-labelledby="dlg-t" onCancel={(e) => { e.preventDefault(); onClose(); }}>
      <div className="dlg-h">
        <h2 id="dlg-t">{title}</h2>
        {subtitle && <p>{subtitle}</p>}
      </div>
      <div className="dlg-b">{children}</div>
      <div className="dlg-f">{footer}</div>
    </dialog>
  );
}

/** Confirm dialog that runs an async action and shows its error in place. */
export function Confirm({ title, subtitle, confirm = "Confirm", danger, action, onDone, onClose }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const go = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await action();
      onDone(r);
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };
  return (
    <Modal title={title} subtitle={subtitle} onClose={onClose}
           footer={<><button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className={`btn${danger ? " danger" : ""}`} disabled={busy} onClick={go}>{confirm}</button></>}>
      <Msg>{error}</Msg>
    </Modal>
  );
}

export function SecretDialog({ title, subtitle, secret, onClose, note = "It is shown only now. The user must choose their own password when they sign in with it." }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(secret);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  return (
    <Modal title={title} subtitle={subtitle} onClose={onClose} footer={<button className="btn" onClick={onClose}>Done</button>}>
      <div className="secret"><code>{secret}</code><button className="btn ghost sm" onClick={copy}>{copied ? "Copied" : "Copy"}</button></div>
      <p className="muted small">{note}</p>
    </Modal>
  );
}

// ------------------------------------------------------------------ passwords

export function PasswordInput({ id, value, onChange, autoComplete, onKeyUp }) {
  const [show, setShow] = useState(false);
  return (
    <div className="pw">
      <input id={id} type={show ? "text" : "password"} value={value} onChange={(e) => onChange(e.target.value)}
             autoComplete={autoComplete} onKeyUp={onKeyUp} required />
      <button type="button" onClick={() => setShow(!show)} aria-label={show ? "Hide password" : "Show password"}>{show ? "Hide" : "Show"}</button>
    </div>
  );
}

/** The password policy as a live checklist; returns [rules element, ok]. */
/** name = the part of the user's e-mail address before the @ (a password must not contain it) */
export function usePolicyRules(password, name) {
  const { policy: p } = useSession();
  const rules = [
    [`At least ${p.minPasswordLength} characters`, password.length >= p.minPasswordLength],
    [`${p.minCharacterClasses} of: upper case, lower case, digit, symbol`,
      [/[A-Z]/, /[a-z]/, /[0-9]/, /[^A-Za-z0-9]/].filter((r) => r.test(password)).length >= p.minCharacterClasses],
    ["Does not contain your e-mail name", !name || name.length < 3 || !password.toLowerCase().includes(name.toLowerCase())],
  ];
  const node = (
    <ul className="rules" aria-label="Password rules">
      {rules.map(([t, ok]) => <li key={t} className={ok ? "ok" : ""}>{t}</li>)}
    </ul>
  );
  return [node, rules.every(([, ok]) => ok)];
}

/** Current + new + repeat, with the live policy list. */
export function PasswordForm({ onDone }) {
  const { user } = useSession();
  const [cur, setCur] = useState("");
  const [next, setNext] = useState("");
  const [rep, setRep] = useState("");
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [rules, ok] = usePolicyRules(next, (user.email || "").split("@")[0]);
  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    if (!ok) return setError("The new password does not meet the rules yet.");
    if (next !== rep) return setError("The two new passwords are not the same.");
    setBusy(true);
    try {
      await api("profile/password", { method: "POST", body: { current: cur, next } });
      setCur("");
      setNext("");
      setRep("");
      await onDone();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="form" onSubmit={submit}>
      <div className="field"><label htmlFor="pc">Current password</label><PasswordInput id="pc" value={cur} onChange={setCur} autoComplete="current-password" /></div>
      <div className="field"><label htmlFor="pn">New password</label><PasswordInput id="pn" value={next} onChange={setNext} autoComplete="new-password" />{rules}</div>
      <div className="field"><label htmlFor="pr">Repeat the new password</label><PasswordInput id="pr" value={rep} onChange={setRep} autoComplete="new-password" /></div>
      <Msg>{error}</Msg>
      <div><button className="btn" disabled={busy}>Change password</button></div>
    </form>
  );
}

// ------------------------------------------------------------------ toasts + polling

const ToastContext = createContext(() => {});

export function ToastProvider({ children }) {
  const [items, setItems] = useState([]);
  const toast = useCallback((text) => {
    const id = Math.random();
    setItems((l) => [...l, { id, text }]);
    setTimeout(() => setItems((l) => l.filter((t) => t.id !== id)), 3500);
  }, []);
  return (
    <ToastContext.Provider value={toast}>
      {children}
      <div className="toasts" role="status" aria-live="polite">{items.map((t) => <div key={t.id} className="toast">{t.text}</div>)}</div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);

/** GET path now and every `ms` (as background refreshes); a null path loads nothing (yet). Returns { data, error, reload }. */
export function usePoll(path, ms) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const load = useCallback(async (background) => {
    if (!path) return;
    try {
      setData(await api(path, { background }));
      setError(null);
    } catch (e) {
      if (e.status !== 401) setError(e.message);
    }
  }, [path]);
  useEffect(() => {
    load(false);
    if (!ms) return undefined;
    const t = setInterval(() => load(true), ms);
    return () => clearInterval(t);
  }, [load, ms]);
  return { data, error, reload: () => load(false) };
}

/** An organization page's address in the organization the session works in: at("/pipelines") = "/northwind-water/pipelines". */
export function useOrgPath() {
  const slug = useSession().org?.slug ?? "";
  return useCallback((path) => `/${slug}${path}`, [slug]);
}

/**
 * The record a page shows, from its address: <base>/<uid> opens that record (e.g. /northwind-water/pipelines/<uid>),
 * <base>/new the form for a new one.
 * open(record | "new") and close() change the address, so every open record is a link that can be shared and the
 * browser's Back closes it. records = the page's list (undefined while it loads); an address whose record is not in
 * it (deleted, or another organization's) goes back to the list with a notice. The record is scrolled into view
 * (rows and cards carry data-uid), also for users who may only look at it.
 */
export function useRecordAddress(base, records, noun) {
  const key = useParams()["*"] || null;
  const navigate = useNavigate();
  const { search } = useLocation();
  const toast = useToast();
  const uid = (r) => String(r.uid ?? r.id);
  const record = !key ? null : key === "new" ? "new" : records?.find((r) => uid(r) === key) ?? null;
  const missing = !!key && key !== "new" && !!records && !record;
  const told = useRef(null);
  const loaded = !!records;

  useEffect(() => {
    if (!missing || told.current === key) return;
    told.current = key;
    toast(`That ${noun} is not in this organization any more. It may have been deleted.`);
    navigate(`${base}${search}`, { replace: true });
  }, [missing, key, noun, base, search, navigate, toast]);
  useEffect(() => {
    if (loaded && key && key !== "new") document.querySelector(`[data-uid="${CSS.escape(key)}"]`)?.scrollIntoView({ block: "center" });
  }, [loaded, key]);

  return {
    key,
    record,
    open: (r) => navigate(`${base}/${r === "new" ? "new" : uid(r)}${search}`),
    close: () => navigate(`${base}${search}`, { replace: true }),
    /** props for the record's row or card: its address id, highlighted while it is the one in the address */
    mark: (r) => ({ "data-uid": uid(r), "data-open": uid(r) === key || undefined }),
  };
}

// ------------------------------------------------------------------ sorting and filters for tables

/**
 * Sort state for a table, remembered for the browser tab (sessionStorage, per name): {key, dir} with dir 1 = up, -1 = down.
 * Filters work the same way: useRemembered(name, initial).
 */
export function useRemembered(name, initial) {
  const [v, setV] = useState(() => {
    try {
      const s = sessionStorage.getItem(`bimba.gw.${name}`);
      return s ? { ...initial, ...JSON.parse(s) } : initial;
    } catch {
      return initial;
    }
  });
  const set = (next) => setV((cur) => {
    const n = typeof next === "function" ? next(cur) : next;
    try { sessionStorage.setItem(`bimba.gw.${name}`, JSON.stringify(n)); } catch { /* private window */ }
    return n;
  });
  return [v, set];
}

/** rows sorted by sort.key: get[key](row) gives a number or a text; ties keep their order */
export function sortRows(rows, sort, get) {
  const f = get[sort.key];
  if (!f) return rows;
  return rows.map((r, i) => [r, i]).sort(([a, i], [b, j]) => {
    const x = f(a), y = f(b);
    const c = typeof x === "number" && typeof y === "number" ? x - y : String(x ?? "").localeCompare(String(y ?? ""), undefined, { numeric: true, sensitivity: "base" });
    return c * sort.dir || i - j;
  }).map(([r]) => r);
}

/** a column header that sorts its table; a second click reverses the order */
export function SortTh({ k, sort, setSort, children, className }) {
  const on = sort.key === k;
  return (
    <th className={className} aria-sort={on ? (sort.dir > 0 ? "ascending" : "descending") : "none"}>
      <button type="button" className={`th-sort${on ? " on" : ""}`} onClick={() => setSort((cur) => ({ key: k, dir: cur.key === k ? -cur.dir : 1 }))}>
        {children}<span aria-hidden="true" className="th-arrow">{on ? (sort.dir > 0 ? "▲" : "▼") : "↕"}</span>
      </button>
    </th>
  );
}

/** a labelled filter list for a toolbar: options [[value, label]], "" = all */
export function FilterSelect({ label, value, onChange, options, all = "All" }) {
  return (
    <label className="filter">
      <span>{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)} aria-label={label}>
        <option value="">{all}</option>
        {options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
      </select>
    </label>
  );
}
