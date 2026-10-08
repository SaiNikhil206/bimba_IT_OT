import { useEffect, useRef, useState } from "react";
import { Link, Navigate, NavLink, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { useSession } from "./session.jsx";
import { Avatar, Brand, Card, initials, PageHeader, PasswordForm, useOrgPath, useToast } from "./ui.jsx";
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
// An organization's pages start with its slug (/northwind-water/pipelines), so a shared link opens the same page in the
// same organization for whoever opens it: after signing in, and after moving their session to that organization.
// The profile and the platform pages (/organizations) belong to no organization. The server forwards these addresses
// to the console (web/SpaController.java), and an organization's slug may not be one of their first segments.

// records: the page also has an address per record (/<org>/pipelines/<uid>, .../new), see useRecordAddress
const PAGES = [
  { path: "/overview", label: "Overview", icon: "◎", perm: ["gateway.view"], element: <Overview /> },
  { path: "/agents", label: "Site agents", icon: "⌂", perm: ["agents.view"], element: <Agents /> },
  { path: "/pipelines", records: true, label: "Pipelines", icon: "⇉", perm: ["gateway.view"], group: "Delivery", element: <Pipelines /> },
  { path: "/destinations", records: true, label: "Destinations", icon: "◇", perm: ["gateway.view"], group: "Delivery", element: <Destinations /> },
  { path: "/users", records: true, label: "Users", icon: "◉", perm: ["users.manage"], group: "Administration", element: <Users /> },
  { path: "/roles", records: true, label: "Roles", icon: "◆", perm: ["roles.manage", "users.manage"], group: "Administration", element: <Roles /> },
  { path: "/audit", label: "Audit log", icon: "≡", perm: ["audit.view"], group: "Administration", element: <Audit /> },
  { path: "/organizations", records: true, label: "Organizations", icon: "▦", platform: true, group: "Platform", element: <Organizations /> },
];

/** first segments that are not an organization: pages of no organization, and the old addresses without one */
const GLOBAL = new Set(["profile", ...PAGES.filter((p) => p.platform).map((p) => p.path.slice(1))]);
const ORG_PAGES = new Set([...PAGES.filter((p) => !p.platform).map((p) => p.path.slice(1)), "delivery"]);

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
  const at = useOrgPath();
  const [menu, setMenu] = useState(false);
  const first = location.pathname.split("/")[1] ?? "";
  const linked = first && !GLOBAL.has(first) && !ORG_PAGES.has(first) ? first : null;     // /<org slug>/...
  const opening = useLinkedOrg(linked);
  // organization pages need an organization; platform pages need a platform admin
  const pages = PAGES.filter((p) => (p.platform ? s.user.platformAdmin : !!s.org && p.perm.some(s.can)));
  const denied = PAGES.filter((p) => !pages.includes(p));
  const href = (p) => (p.platform ? p.path : at(p.path));
  const route = (p) => `${p.platform ? "" : "/:org"}${p.path}${p.records ? "/*" : ""}`;
  const home = pages[0] ? href(pages[0]) : "/profile";
  const sub = linked ? location.pathname.slice(linked.length + 1) : location.pathname;
  const current = [...PAGES, { path: "/profile", label: "Profile" }].find((p) => sub.startsWith(p.path));
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
        <OrgSwitcher />
        <nav className="nav">
          {pages.map((p) => {
            const head = p.group && p.group !== group ? <div className="grp" key={`g-${p.group}`}>{p.group}</div> : null;
            group = p.group || group;
            return [head, (
              <NavLink key={p.path} to={href(p)} className={({ isActive }) => (isActive ? "on" : "")}>
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
          {opening ? <p className="muted">Opening {opening}…</p> : (
          <Routes key={s.org?.id ?? "none"}>
            {pages.map((p) => <Route key={p.path} path={route(p)} element={p.element} />)}
            {denied.map((p) => <Route key={p.path} path={route(p)} element={<NoAccess page={p} home={home} />} />)}
            <Route path="/profile" element={<Profile />} />
            <Route path="/:org/delivery" element={<Navigate to={at("/pipelines")} replace />} />
            <Route path="*" element={<Moved home={home} />} />
          </Routes>
          )}
        </main>
      </div>
    </div>
  );
}

/**
 * The organization in the address (/<slug>/...) is the one the session works in. An address that names another
 * organization (a shared link) moves the session there when the user is a member; otherwise the address gets the
 * session's organization back. Only a slug that differs from the one last seen here counts as a request: after a switch
 * in the sidebar or in another tab, the old slug in the address is just replaced.
 * Returns the name of the organization being opened, or null.
 */
function useLinkedOrg(wanted) {
  const s = useSession();
  const toast = useToast();
  const navigate = useNavigate();
  const [opening, setOpening] = useState(null);
  const synced = useRef(null);
  const busy = useRef(false);       // a switch is running: leave the address alone until it is done
  const slug = s.org?.slug ?? null;

  useEffect(() => {
    if (!slug || busy.current) return;
    if (!wanted) {
      synced.current = slug;
      return;
    }
    // from the live address: a navigation that ran since this render (the sidebar switch goes to the start page) stays
    const show = (to) => {
      const { pathname, search, hash } = window.location;
      const parts = pathname.split("/");
      if (parts[1] !== wanted) return;
      parts[1] = to;
      navigate({ pathname: parts.join("/"), search, hash }, { replace: true });
    };
    if (wanted !== slug && wanted !== synced.current) {
      synced.current = wanted;
      const o = s.organizations.find((x) => x.slug === wanted);
      if (!o) {
        toast(`This link is for an organization you are not a member of. You are working in ${s.org.name}.`);
        show(slug);
        return;
      }
      busy.current = true;
      setOpening(o.name);
      s.switchOrg(o.id)
        .then(() => toast(`You are working in ${o.name}.`))
        .catch((e) => {
          toast(e.message);
          show(slug);
        })
        .finally(() => {
          busy.current = false;
          setOpening(null);
        });
      return;
    }
    synced.current = slug;
    if (wanted !== slug) show(slug);
  }, [wanted, slug, opening, s, toast, navigate]);

  return opening;
}

/**
 * Addresses that match no page: an organization page without its organization (the addresses before organization
 * slugs, e.g. /pipelines/<uid>?org=northwind-water) moves under it; anything else goes to the start page.
 */
function Moved({ home }) {
  const s = useSession();
  const { pathname, search, hash } = useLocation();
  if (!s.org || !ORG_PAGES.has(pathname.split("/")[1])) return <Navigate to={home} replace />;
  const q = new URLSearchParams(search);
  const org = q.get("org") || s.org.slug;
  q.delete("org");
  const rest = q.toString();
  return <Navigate to={`/${org}${pathname}${rest ? `?${rest}` : ""}${hash}`} replace />;
}

/** a page this user's roles do not allow here (opened from a shared link or a bookmark) */
function NoAccess({ page, home }) {
  const s = useSession();
  const why = page.platform ? "Only platform admins open this page."
    : `Your roles in ${s.org?.name ?? "this organization"} do not include this page. Ask one of its administrators for access${
      s.organizations.length > 1 ? ", or switch to another organization in the sidebar" : ""}.`;
  return (
    <>
      <PageHeader title={page.label} />
      <Card title="You do not have access to this page" subtitle={why}>
        <Link className="btn ghost sm" to={home}>Go to your start page</Link>
      </Card>
    </>
  );
}

/** The organization the user works in; with more than one, a menu to switch (the session moves, all tabs follow). */
function OrgSwitcher() {
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
      navigate(`/${o.slug}`, { replace: true });     // matches no page there: its start page
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
