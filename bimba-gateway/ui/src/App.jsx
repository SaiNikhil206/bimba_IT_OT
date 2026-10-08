import { useEffect, useRef, useState } from "react";
import { Navigate, NavLink, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { useSession } from "./session.jsx";
import { Avatar, Brand, initials, PasswordForm, useToast } from "./ui.jsx";
import Login from "./pages/Login.jsx";
import { ForgotPassword, ResetPassword } from "./pages/PasswordReset.jsx";
import Overview from "./pages/Overview.jsx";
import Pipelines from "./pages/Pipelines.jsx";
import Destinations from "./pages/Destinations.jsx";
import Agents from "./pages/Agents.jsx";
import Users from "./pages/Users.jsx";
import Roles from "./pages/Roles.jsx";
import Audit from "./pages/Audit.jsx";
import Profile from "./pages/Profile.jsx";
import Organizations from "./pages/Organizations.jsx";

// BIMBA Gateway console. Signed out -> sign-in; temporary password -> choose a new one; otherwise the shell with
// the pages this user's permissions allow in the organization they work in (the server checks every call again).
// Users who belong to several organizations switch between them at the top of the sidebar.

const PAGES = [
  { path: "/overview", label: "Overview", icon: "◎", perm: ["gateway.view"], element: <Overview /> },
  { path: "/agents", label: "Site agents", icon: "⌂", perm: ["agents.view"], element: <Agents /> },
  { path: "/pipelines", label: "Pipelines", icon: "⇉", perm: ["gateway.view"], group: "Delivery", element: <Pipelines /> },
  { path: "/destinations", label: "Destinations", icon: "◇", perm: ["gateway.view"], group: "Delivery", element: <Destinations /> },
  { path: "/users", label: "Users", icon: "◉", perm: ["users.manage"], group: "Administration", element: <Users /> },
  { path: "/roles", label: "Roles", icon: "◆", perm: ["roles.manage", "users.manage"], group: "Administration", element: <Roles /> },
  { path: "/audit", label: "Audit log", icon: "≡", perm: ["audit.view"], group: "Administration", element: <Audit /> },
  { path: "/organizations", label: "Organizations", icon: "▦", platform: true, group: "Platform", element: <Organizations /> },
];

export default function App() {
  const s = useSession();
  if (s.loading) return <p className="boot">Loading BIMBA Gateway…</p>;
  if (s.error && !s.user) return <div className="auth"><div className="auth-card"><p className="msg bad">{s.error}</p></div></div>;
  if (!s.user) {
    return (
      <Routes>
        <Route path="/forgot-password" element={<ForgotPassword />} />
        <Route path="/reset-password" element={<ResetPassword />} />
        <Route path="*" element={<Login />} />
      </Routes>
    );
  }
  if (s.user.mustChangePassword) return <ForcedPassword />;
  return <Shell />;
}

function ForcedPassword() {
  const s = useSession();
  const toast = useToast();
  useEffect(() => {
    document.title = "Choose a new password · BIMBA Gateway";
  }, []);
  return (
    <div className="auth">
      <div className="auth-card">
        <Brand sub={`Signed in as ${s.user.email}`} />
        <h1>Choose a new password</h1>
        <p className="lead">Your password was set by an administrator. Choose your own before you continue.</p>
        <PasswordForm onDone={async () => { await s.reload(); toast("Password changed."); }} />
        <p className="foot"><button className="link" onClick={s.logout}>Sign out instead</button></p>
      </div>
    </div>
  );
}

function Shell() {
  const s = useSession();
  const toast = useToast();
  const location = useLocation();
  const [menu, setMenu] = useState(false);
  // organization pages need an organization; platform pages need a platform admin
  const pages = PAGES.filter((p) => (p.platform ? s.user.platformAdmin : !!s.org && p.perm.some(s.can)));
  const home = pages[0]?.path ?? "/profile";
  const current = [...pages, { path: "/profile", label: "Profile" }].find((p) => location.pathname.startsWith(p.path));
  useEffect(() => {
    document.title = `${current?.label ?? "BIMBA"}${s.org ? ` · ${s.org.name}` : ""} · BIMBA Gateway`;
    setMenu(false);
  }, [current, s.org]);
  useEffect(() => {
    if (s.notice) {
      toast(s.notice);
      s.clearNotice();
    }
  }, [s, toast]);

  let group = null;
  return (
    <div className="shell">
      <aside className={`side${menu ? " open" : ""}`} aria-label="Navigation">
        <NavLink className="brand" to={home}>
          <span className="mark" aria-hidden="true">B</span>
          <span><b>BIMBA Gateway</b><small>v{s.version}</small></span>
        </NavLink>
        <OrgSwitcher home={home} />
        <nav className="nav">
          {pages.map((p) => {
            const head = p.group && p.group !== group ? <div className="grp" key={`g-${p.group}`}>{p.group}</div> : null;
            group = p.group || group;
            return [head, (
              <NavLink key={p.path} to={p.path} className={({ isActive }) => (isActive ? "on" : "")}>
                <span className="ic" aria-hidden="true">{p.icon}</span>{p.label}
              </NavLink>
            )];
          })}
        </nav>
        <NavLink className={({ isActive }) => `me${isActive ? " on" : ""}`} to="/profile" title="Your profile">
          <Avatar user={s.user} />
          <span><b title={s.user.name}>{s.user.name}</b>
            <small title={s.user.email}>{s.user.platformAdmin ? "Platform admin" : s.user.email}</small></span>
        </NavLink>
        <div className="side-f">
          <button className="btn ghost sm" onClick={toggleTheme} aria-label="Switch light / dark">◐ Theme</button>
          <button className="btn ghost sm" onClick={s.logout}>Sign out</button>
        </div>
      </aside>
      <div className="main">
        <div className="top">
          <button className="btn ghost sm" aria-label="Menu" onClick={() => setMenu(!menu)}>☰</button>
          <b title={s.org?.name}>{s.org?.name ?? "BIMBA Gateway"}</b>
          <Avatar user={s.user} />
        </div>
        <main className="page" id="main">
          {/* keyed by organization: every page loads again for the organization switched to */}
          <Routes key={s.org?.id ?? "none"}>
            {pages.map((p) => <Route key={p.path} path={p.path} element={p.element} />)}
            <Route path="/profile" element={<Profile />} />
            <Route path="/delivery" element={<Navigate to="/pipelines" replace />} />
            <Route path="*" element={<Navigate to={home} replace />} />
          </Routes>
        </main>
      </div>
    </div>
  );
}

/** The organization the user works in; with more than one, a menu to switch (the session moves, all tabs follow). */
function OrgSwitcher({ home }) {
  const s = useSession();
  const toast = useToast();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const ref = useRef(null);
  const orgs = s.organizations;
  const many = orgs.length > 1;

  useEffect(() => {
    if (!open) return undefined;
    const close = (e) => {
      if (e.type === "keydown" ? e.key === "Escape" : !ref.current?.contains(e.target)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, [open]);

  if (!s.org) {
    return <div className="org none"><small>Organization</small><b>None yet</b></div>;
  }
  const mine = orgs.find((o) => o.id === s.org.id);
  const roles = (o) => (o.roles?.length ? o.roles.join(", ") : s.user.platformAdmin ? "Platform admin" : "");

  const pick = async (o) => {
    setOpen(false);
    if (o.id === s.org.id) return;
    setBusy(true);
    try {
      await s.switchOrg(o.id);
      navigate(home, { replace: true });
      toast(`You are working in ${o.name}.`);
    } catch (e) {
      toast(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="org" ref={ref}>
      <button type="button" className="org-btn" disabled={!many || busy} aria-haspopup={many ? "listbox" : undefined} aria-expanded={open}
              onClick={() => setOpen(!open)} title={many ? "Switch organization" : undefined}>
        <span className="org-mark" aria-hidden="true">{initials({ name: s.org.name })}</span>
        <span className="org-txt"><small>Organization</small><b title={s.org.name}>{s.org.name}</b>
          {mine && roles(mine) && <small title={roles(mine)}>{roles(mine)}</small>}</span>
        {many && <span className="org-caret" aria-hidden="true">⇅</span>}
      </button>
      {open && (
        <div className="org-menu" role="listbox" aria-label="Switch organization">
          <div className="org-menu-h">Switch organization</div>
          {orgs.map((o) => (
            <button key={o.id} type="button" role="option" aria-selected={o.id === s.org.id} className={o.id === s.org.id ? "on" : ""}
                    onClick={() => pick(o)}>
              <span className="org-mark" aria-hidden="true">{initials({ name: o.name })}</span>
              <span className="org-txt"><b title={o.name}>{o.name}</b><small title={roles(o) || o.slug}>{roles(o) || o.slug}</small></span>
              {o.id === s.org.id && <span className="org-check" aria-hidden="true">✓</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function toggleTheme() {
  const root = document.documentElement;
  const dark = root.dataset.theme ? root.dataset.theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
  root.dataset.theme = dark ? "light" : "dark";
  try {
    localStorage.setItem("bimba.gw.theme", root.dataset.theme);
  } catch {
    /* not remembered */
  }
}
