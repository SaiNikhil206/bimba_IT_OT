import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Brand, PasswordInput, usePolicyRules } from "../ui.jsx";

// "Forgot password?": ask for a link by e-mail, then choose a new password with it. The server answers the same
// whether or not the address has an account; the link works once, for a short time.

export function ForgotPassword() {
  const location = useLocation();
  const [email, setEmail] = useState(location.state?.email ?? "");
  const [sent, setSent] = useState(null);           // { email, minutes }
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const ref = useRef(null);

  useEffect(() => {
    document.title = "Forgot password · BIMBA Setu";
    ref.current?.focus();
  }, []);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api("password/forgot", { method: "POST", body: { email: email.trim() } });
      setSent({ email: email.trim(), minutes: r.minutes });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth">
      <div className="auth-card">
        <Brand />
        <h1>Forgot your password?</h1>
        {sent ? (
          <>
            <div className="msg good" role="status">
              If <b>{sent.email}</b> has a BIMBA Setu account, a link to choose a new password is on its way. It works once,
              for {sent.minutes} minutes.
            </div>
            <p className="lead">No e-mail after a few minutes? Check the spam folder, or ask your administrator to reset the password.</p>
            <p className="foot"><Link className="link" to="/">Back to sign-in</Link></p>
          </>
        ) : (
          <>
            <p className="lead">Enter the e-mail address you sign in with. We send you a link to choose a new password.</p>
            <form className="form" onSubmit={submit}>
              <div className="field">
                <label htmlFor="fe">E-mail address</label>
                <input id="fe" ref={ref} type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username"
                       spellCheck="false" autoCapitalize="none" placeholder="name@company.com" required />
              </div>
              {error && <div className="msg bad" role="alert">{error}</div>}
              <button className="btn block" disabled={busy}>{busy ? "Sending…" : "Send reset link"}</button>
            </form>
            <p className="foot"><Link className="link" to="/">Back to sign-in</Link></p>
          </>
        )}
      </div>
    </div>
  );
}

export function ResetPassword() {
  const s = useSession();
  const navigate = useNavigate();
  const [token] = useState(() => new URLSearchParams(window.location.search).get("token") ?? "");
  const [link, setLink] = useState(null);           // { email, expiresAt } | { error }
  const [next, setNext] = useState("");
  const [rep, setRep] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [rules, ok] = usePolicyRules(next, (link?.email ?? "").split("@")[0]);

  useEffect(() => {
    document.title = "Choose a new password · BIMBA Setu";
    // the token leaves the address bar (and the browser history) right away
    window.history.replaceState(null, "", "/reset-password");
    api(`password/reset?${new URLSearchParams({ token })}`).then(setLink).catch((e) => setLink({ error: e.message }));
  }, [token]);

  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    if (!ok) return setError("The new password does not meet the rules yet.");
    if (next !== rep) return setError("The two passwords are not the same.");
    setBusy(true);
    try {
      await api("password/reset", { method: "POST", body: { token, password: next } });
      s.signedOut("Your password was changed. Sign in with the new one.");
      navigate("/", { replace: true });
    } catch (err) {
      setError(err.message);
      setBusy(false);
    }
  };

  return (
    <div className="auth">
      <div className="auth-card">
        <Brand />
        <h1>Choose a new password</h1>
        {!link && <p className="lead">Checking the link…</p>}
        {link?.error && (
          <>
            <div className="msg bad" role="alert">{link.error}</div>
            <p className="foot"><Link className="link" to="/forgot-password">Send a new link</Link> · <Link className="link" to="/">Sign in</Link></p>
          </>
        )}
        {link?.email && (
          <>
            <p className="lead">For <b>{link.email}</b>. Every browser signed in to this account is signed out.</p>
            <form className="form" onSubmit={submit}>
              <input type="email" value={link.email} autoComplete="username" readOnly hidden />
              <div className="field"><label htmlFor="rn">New password</label>
                <PasswordInput id="rn" value={next} onChange={setNext} autoComplete="new-password" />{rules}</div>
              <div className="field"><label htmlFor="rr">Repeat the new password</label>
                <PasswordInput id="rr" value={rep} onChange={setRep} autoComplete="new-password" /></div>
              {error && <div className="msg bad" role="alert">{error}</div>}
              <button className="btn block" disabled={busy}>{busy ? "Saving…" : "Change password"}</button>
            </form>
          </>
        )}
      </div>
    </div>
  );
}
