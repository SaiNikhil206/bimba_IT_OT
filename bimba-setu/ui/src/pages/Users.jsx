import { useState } from "react";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Avatar, Card, Confirm, Empty, full, Modal, Msg, PageHeader, SecretDialog, Status, useOrgPath, usePoll, useRecordAddress, useToast, when } from "../ui.jsx";

// Users (users.manage; users.view looks only): the members of the organization the session works in, and their roles here,
// each for the whole organization or only some sites, site agents or machines. One account can be a member of several
// organizations (different roles in each). The account itself (name, e-mail, password, enabled) of someone who is also in
// another organization can only be changed by a platform admin. Access shows what a member may do and why; the access
// review exports it for everyone.

export default function Users() {
  const { user: me, org, can } = useSession();
  const toast = useToast();
  const users = usePoll("users", 0);
  const roles = usePoll("roles", 0);
  const manage = can("users.manage");
  const [q, setQ] = useState("");
  const [access, setAccess] = useState(null);       // the member whose access is shown
  const [confirm, setConfirm] = useState(null);     // { user, action, title, subtitle, danger, method, path, label }
  const [secret, setSecret] = useState(null);       // { title, subtitle, value }

  const n = q.trim().toLowerCase();
  const list = (users.data?.users ?? []).filter((u) => !n || [u.email, u.firstName, u.lastName].join(" ").toLowerCase().includes(n));
  const editable = (u) => manage && (!u.platformAdmin || me.platformAdmin);
  const rec = useRecordAddress(useOrgPath()("/users"), users.data?.users, "user");
  const editing = rec.record === "new" || (rec.record && editable(rec.record)) ? rec.record : null;   // null | "new" | user

  return (
    <>
      <PageHeader title="Users" subtitle={`Members of ${org.name} and their roles here. One account can belong to several organizations.`}>
        <a className="btn ghost" href="/api/gw/access/review.csv" download title="Every member with their roles, where they hold, what they allow and the last sign-in">Export access review</a>
        {manage && <button className="btn" onClick={() => rec.open("new")} disabled={!roles.data}>Add user</button>}
      </PageHeader>
      <div className="toolbar">
        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter users" aria-label="Filter users" />
      </div>
      <Msg>{users.error || roles.error}</Msg>
      <Card flush>
        <table>
          <thead><tr><th>User</th><th>Roles in {org.name}</th><th>Status</th><th>Last sign-in</th><th className="r" /></tr></thead>
          <tbody>
            {!users.data && <Empty cols={5}>Loading…</Empty>}
            {users.data && list.length === 0 && <Empty cols={5}>{users.data.users.length ? "No user matches the filter." : "No members yet. Add the first one."}</Empty>}
            {list.map((u) => {
              const [kind, text] = !u.active ? ["bad", "Disabled"] : u.locked ? ["warn", "Locked"] : u.mustChangePassword ? ["warn", "Must change password"] : ["good", "Active"];
              const ok = editable(u);
              const account = ok && u.accountEditable;
              const act = (action, title, subtitle, danger) => setConfirm({ user: u, title, subtitle, danger, method: "POST", path: `users/${u.id}/${action}`,
                label: action === "reset-password" ? "Reset password" : "Confirm" });
              return (
                <tr key={u.id} {...rec.mark(u)}>
                  <td className="who">
                    <Avatar user={{ ...u, name: `${u.firstName} ${u.lastName}`.trim() }} />
                    <div>
                    <b>{u.email || u.username}</b>
                    <small>{`${u.firstName} ${u.lastName}`.trim() || "—"}</small>
                    {u.otherOrganizations > 0 && (
                      <small title={u.otherOrganizationNames?.join(", ")}>
                        Also in {u.otherOrganizationNames ? u.otherOrganizationNames.join(", ") : `${u.otherOrganizations} other organization${u.otherOrganizations === 1 ? "" : "s"}`}
                      </small>
                    )}
                    </div>
                  </td>
                  <td className="role-cell">
                    {u.platformAdmin && <span className="chip accent">Platform admin</span>}
                    {u.roles.map((r) => (
                      <span className="chip role-chip" key={r.id} title={r.scopeText ? `${r.name}: only ${r.scopeText}` : `${r.name}: whole organization`}>
                        {r.name}{r.scopeText && <small className="ellipsis">{r.scopeText}</small>}
                      </span>
                    ))}
                    {!u.platformAdmin && !u.roles.length && <span className="muted small">No Setu access here</span>}
                    {u.dutyConflicts?.length > 0 && <small className="bad" title={u.dutyConflicts.join(", ")}>Breaks duty separation (Auditor with operational permissions)</small>}
                  </td>
                  <td><Status kind={kind}>{text}</Status>{u.activeSessions > 0 && <small>{u.activeSessions} active session(s)</small>}</td>
                  <td title={full(u.lastLogin)}>{when(u.lastLogin)}{u.lastLoginIp && <small>{u.lastLoginIp}</small>}</td>
                  <td className="r">
                    <div className="row-acts">
                      <button className="btn ghost sm" onClick={() => setAccess(u)} title="What this member may do, where, and which role gives it">Access</button>
                      {manage && (
                        <button className="btn ghost sm" disabled={!ok} title={ok ? undefined : "Only platform admins can change platform admin accounts"}
                                onClick={() => rec.open(u)}>Edit</button>
                      )}
                      {u.locked && ok && <button className="btn ghost sm" onClick={() => act("unlock", `Unlock ${u.email}?`, "The account can sign in again right away.")}>Unlock</button>}
                      {account && u.id !== me.id && (
                        <button className="btn ghost sm" onClick={() => act("reset-password", `Reset the password of ${u.email}?`,
                          "A temporary password is created and their open sessions end. They choose a new password at their next sign-in.", true)}>Reset password</button>
                      )}
                      {u.activeSessions > 0 && account && <button className="btn ghost sm" onClick={() => act("sign-out", `Sign ${u.email} out?`, "All their open sessions end now.")}>Sign out</button>}
                      {ok && u.id !== me.id && (
                        <button className="btn danger sm" onClick={() => setConfirm({
                          user: u, danger: true, method: "DELETE", path: `users/${u.id}`, label: "Remove",
                          title: `Remove ${u.email} from ${org.name}?`,
                          subtitle: u.otherOrganizations > 0
                            ? `Their roles in ${org.name} end. The account stays, with its access to the other organization(s).`
                            : `Their roles in ${org.name} end. The account itself stays (it can be added again later).`,
                        })}>Remove…</button>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>

      {editing && roles.data && (
        <UserDialog user={editing === "new" ? null : editing} roles={roles.data.roles} catalog={roles.data.permissions} me={me} org={org}
                    onClose={rec.close}
                    onSaved={(r) => {
                      rec.close();
                      users.reload();
                      if (r?.temporaryPassword) setSecret({ title: `${r.email} created`, subtitle: "Give the temporary password to the user over a safe channel.", value: r.temporaryPassword });
                      else toast(r?.added ? `${r.email} added to ${org.name}.` : "Saved.");
                    }} />
      )}
      {confirm && (
        <Confirm title={confirm.title} subtitle={confirm.subtitle} danger={confirm.danger} confirm={confirm.label}
                 action={() => api(confirm.path, { method: confirm.method })}
                 onDone={(r) => {
                   const u = confirm.user;
                   setConfirm(null);
                   users.reload();
                   if (r?.temporaryPassword) setSecret({ title: "Temporary password", subtitle: `Give it to ${u.email} over a safe channel.`, value: r.temporaryPassword });
                   else toast("Done.");
                 }}
                 onClose={() => setConfirm(null)} />
      )}
      {access && <AccessDialog user={access} catalog={roles.data?.permissions ?? []} org={org} onClose={() => setAccess(null)} />}
      {secret && <SecretDialog title={secret.title} subtitle={secret.subtitle} secret={secret.value} onClose={() => setSecret(null)} />}
    </>
  );
}

function UserDialog({ user, roles, catalog, me, org, onClose, onSaved }) {
  const isNew = !user;
  const [existing, setExisting] = useState(false);   // new: add an account that already exists (e.g. from another organization)
  const [f, setF] = useState({
    firstName: user?.firstName ?? "", lastName: user?.lastName ?? "", email: user?.email ?? "",
    active: user ? user.active : true,
  });
  // role id -> scope ({sites, agents, machines}) or null for the whole organization
  const [assigned, setAssigned] = useState(() => Object.fromEntries((user?.roles ?? []).map((r) => [r.id, r.scope ?? null])));
  const places = usePoll("access/places", 0);
  const initial = new Set((user?.roles ?? []).map((r) => r.id));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === "checkbox" ? e.target.checked : e.target.value });
  const mine = new Set(me.permissions);
  const scoped = (k) => catalog.find((p) => p.key === k)?.scoped;
  const toggleRole = (id) => setAssigned((a) => {
    const n = { ...a };
    if (id in n) delete n[id]; else n[id] = null;
    return n;
  });
  const roleBody = Object.entries(assigned).map(([id, scope]) => ({ id: Number(id), scope }));
  const emptyScope = Object.values(assigned).some((sc) => sc && !sc.sites.length && !sc.agents.length && !sc.machines.length);
  const account = isNew ? !existing : user.accountEditable;     // name / e-mail / enabled can be changed
  const lockActive = isNew || user.id === me.id || user.platformAdmin || !user.accountEditable;

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = isNew && existing
        ? { existing: true, email: f.email.trim(), roles: roleBody }
        : account ? { ...f, email: f.email.trim(), roles: roleBody } : { roles: roleBody };
      onSaved(isNew ? await api("users", { method: "POST", body }) : await api(`users/${user.id}`, { method: "PUT", body }));
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };

  return (
    <Modal title={isNew ? `Add a user to ${org.name}` : `Edit ${user.email}`} onClose={onClose}
           subtitle={isNew ? (existing ? "The account keeps its password and its other organizations; it gets the roles you choose here."
             : "A temporary password is created; the user must change it at the first sign-in.") : null}
           footer={<><button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy || emptyScope} title={emptyScope ? "Choose the places for each role given only for some places" : undefined} onClick={save}>{isNew ? (existing ? "Add to organization" : "Create user") : "Save"}</button></>}>
      {isNew && (
        <div className="seg two" role="radiogroup" aria-label="Kind of user">
          <label><input type="radio" name="u-kind" checked={!existing} onChange={() => setExisting(false)} />
            <b>New account</b><small>Someone who has no BIMBA account yet.</small></label>
          <label><input type="radio" name="u-kind" checked={existing} onChange={() => setExisting(true)} />
            <b>Existing account</b><small>Someone who already works in another organization.</small></label>
        </div>
      )}
      <div className="grid2">
        <div className="field span2">
          <label htmlFor="f-e">E-mail address</label>
          <input id="f-e" type="email" value={f.email} onChange={set("email")} disabled={!account && !(isNew && existing)} autoComplete="off" spellCheck="false" />
          <span className="hint">{isNew && existing ? "The e-mail address the person already signs in with (for example in another organization)."
            : isNew ? "The person signs in with this address." : "The sign-in address. Changing it ends the user's open sessions."}</span>
        </div>
        {!(isNew && existing) && (
          <>
            <div className="field"><label htmlFor="f-f">First name</label><input id="f-f" type="text" value={f.firstName} onChange={set("firstName")} disabled={!account} /></div>
            <div className="field"><label htmlFor="f-l">Last name</label><input id="f-l" type="text" value={f.lastName} onChange={set("lastName")} disabled={!account} /></div>
          </>
        )}
      </div>
      {!isNew && !user.accountEditable && (
        <Msg kind="info">{user.email} also belongs to another organization, so only a platform admin can change the account itself. You can change their roles in {org.name}.</Msg>
      )}
      <div className="field">
        <span className="label">Roles in {org.name}</span>
        <span className="hint">A role with site agent, machine or command permissions can be given for the whole organization or only some sites, site agents or machines.</span>
        {roles.map((r) => {
          const missing = r.permissions.filter((p) => !mine.has(p));
          const has = r.id in assigned;
          // duty separation: another member has to give you a role
          const selfAdd = !isNew && user.id === me.id && !me.platformAdmin && !initial.has(r.id);
          const limitable = r.locked || r.permissions.some(scoped);
          return (
            <div className="role-pick" key={r.id}>
              <label className="check">
                <input type="checkbox" checked={has} disabled={(missing.length > 0 || selfAdd) && !has} onChange={() => toggleRole(r.id)} />
                <span><b>{r.name}</b> {r.persona && <span className="chip" title={`BRD persona: ${r.persona.name}`}>{r.persona.code}</span>}
                  {!r.shared && <span className="chip" title={org.name}>{org.name}</span>} <small className="muted">{r.description}</small>
                  {missing.length > 0 && !has && <small className="muted"> — you cannot grant it</small>}
                  {selfAdd && !missing.length && !has && <small className="muted"> — another member has to give you a role</small>}</span>
              </label>
              {has && limitable && (
                <ScopePicker value={assigned[r.id]} places={places.data} onChange={(sc) => setAssigned((a) => ({ ...a, [r.id]: sc }))} />
              )}
            </div>
          );
        })}
        <Msg>{places.error}</Msg>
      </div>
      {!isNew && (
        <label className="check">
          <input type="checkbox" checked={f.active} disabled={lockActive} onChange={set("active")} />
          <span>Account enabled <small className="muted">
            {user.platformAdmin ? " (platform admins are managed in Django admin)" : user.id === me.id ? " (you cannot disable yourself)"
              : !user.accountEditable ? " (shared with another organization)" : " (disabling signs the user out of every organization)"}</small></span>
        </label>
      )}
      <Msg>{error}</Msg>
    </Modal>
  );
}

const EMPTY = { sites: [], agents: [], machines: [] };

/** where a role holds: the whole organization, or the chosen sites, site agents and machines (any of them) */
function ScopePicker({ value, places, onChange }) {
  const limited = !!value;
  const v = value ?? EMPTY;
  const same = (a, b) => a.toLowerCase() === b.toLowerCase();
  const on = (k, x) => v[k].some((y) => same(y, x));
  const flip = (k, x) => onChange({ ...v, [k]: on(k, x) ? v[k].filter((y) => !same(y, x)) : [...v[k], x] });
  const agentName = (id) => places?.agents.find((a) => a.id === id)?.name ?? id;
  const none = limited && !v.sites.length && !v.agents.length && !v.machines.length;
  return (
    <div className="scope-pick">
      <div className="seg two" role="radiogroup" aria-label="Where the role holds">
        <label><input type="radio" checked={!limited} onChange={() => onChange(null)} /><b>Whole organization</b></label>
        <label><input type="radio" checked={limited} onChange={() => onChange({ ...EMPTY })} /><b>Only some places</b></label>
      </div>
      {limited && !places && <p className="muted small">Loading sites, site agents and machines…</p>}
      {limited && places && (
        <>
          <div className="scope-cols">
            <fieldset>
              <legend>Sites</legend>
              {places.sites.length ? places.sites.map((x) => (
                <label className="check" key={x}><input type="checkbox" checked={on("sites", x)} onChange={() => flip("sites", x)} /><span className="ellipsis" title={x}>{x}</span></label>
              )) : <small className="muted">No site agent reports a site yet.</small>}
            </fieldset>
            <fieldset>
              <legend>Site agents</legend>
              {places.agents.length ? places.agents.map((a) => (
                <label className="check" key={a.id}><input type="checkbox" checked={on("agents", a.id)} onChange={() => flip("agents", a.id)} />
                  <span title={`${a.name}${a.site ? ` (${a.site})` : ""}`}><span className="ellipsis">{a.name}</span>{a.site && <small className="ellipsis">{a.site}</small>}</span></label>
              )) : <small className="muted">No site agents yet.</small>}
            </fieldset>
            <fieldset>
              <legend>Machines</legend>
              {places.machines.length ? places.machines.map((m) => (
                <label className="check" key={m.name}><input type="checkbox" checked={on("machines", m.name)} onChange={() => flip("machines", m.name)} />
                  <span className="ellipsis" title={`${m.name} (${agentName(m.agentId)})`}>{m.name}</span></label>
              )) : <small className="muted">No machines yet.</small>}
            </fieldset>
          </div>
          <small className={none ? "bad" : "muted"}>
            {none ? "Choose at least one place, or give the role for the whole organization."
              : "Site agent, machine and command permissions of this role hold only here; its other permissions hold in the whole organization."}
          </small>
        </>
      )}
    </div>
  );
}

/** what one member may do in the organization: each permission, where it holds and which roles give it */
function AccessDialog({ user, catalog, org, onClose }) {
  const groups = [...new Set(catalog.map((p) => p.group))];
  const held = Object.fromEntries((user.permissions ?? []).map((p) => [p.key, p]));
  return (
    <Modal wide title={`Access of ${user.email}`} onClose={onClose}
           subtitle={user.platformAdmin ? "A platform admin may do everything in every organization." : `Everything their roles in ${org.name} allow.`}
           footer={<button className="btn" onClick={onClose}>Close</button>}>
      {user.dutyConflicts?.length > 0 && (
        <Msg kind="warn">Duty separation: this member has the Auditor role and operational permissions ({user.dutyConflicts.join(", ")}). Change their roles.</Msg>
      )}
      <div className="grid-wrap">
        <table className="access">
          <thead><tr><th>Permission</th><th>Where</th><th>Given by</th></tr></thead>
          <tbody>
            {groups.map((g) => [
              <tr key={`g-${g}`} className="pg-group"><th colSpan={3}>{g}</th></tr>,
              ...catalog.filter((p) => p.group === g).map((p) => {
                const h = held[p.key];
                return (
                  <tr key={p.key} className={h ? "" : "off"}>
                    <td><b className="mono">{p.key}</b><small>{p.label}</small></td>
                    <td>{!h ? <span className="muted">No</span> : h.scopeText ? <span title={h.scopeText}>Only {h.scopeText}</span> : "Whole organization"}</td>
                    <td>{h ? h.via.join(", ") : ""}</td>
                  </tr>
                );
              }),
            ])}
          </tbody>
        </table>
      </div>
    </Modal>
  );
}
