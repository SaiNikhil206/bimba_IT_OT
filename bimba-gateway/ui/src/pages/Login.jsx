import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Brand, PasswordInput } from "../ui.jsx";

export default function Login() {
  const s = useSession();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [caps, setCaps] = useState(false);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState(s.message ? { kind: "info", text: s.message } : null);
  const userRef = useRef(null);
  const p = s.policy;

  useEffect(() => {
    document.title = "Sign in · BIMBA Gateway";
    userRef.current?.focus();
  }, []);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setMsg(null);
    try {
      const r = await api("login", { method: "POST", body: { email, password } });
      s.signedIn(r.user, r.organizations);
      navigate("/", { replace: true });
    } catch (err) {
      setMsg({ kind: err.code === "locked" ? "warn" : "bad", text: err.message });
      setPassword("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth">
      <div className="auth-card">
        <Brand />
        <h1>Sign in</h1>
        <p className="lead">Sign in with your work e-mail address.</p>
        <form className="form" onSubmit={submit}>
          <div className="field">
            <label htmlFor="u">E-mail address</label>
            <input id="u" ref={userRef} type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                   autoComplete="username" spellCheck="false" autoCapitalize="none" placeholder="name@company.com" required />
          </div>
          <div className="field">
            <label htmlFor="p">Password</label>
            <PasswordInput id="p" value={password} onChange={setPassword} autoComplete="current-password"
                           onKeyUp={(e) => setCaps(!!e.getModifierState?.("CapsLock"))} />
            {caps && <span className="hint">Caps Lock is on.</span>}
            <Link className="link-sm" to="/forgot-password" state={{ email }}>Forgot password?</Link>
          </div>
          {msg && <div className={`msg ${msg.kind}`} role="alert">{msg.text}</div>}
          <button className="btn block" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
        </form>
        <p className="foot">
          For security, you are signed out after {p.idleMinutes} minutes without activity. After {p.maxFailedLogins} wrong
          passwords the account is locked for {p.lockMinutes} minutes.
        </p>
      </div>
    </div>
  );
}
