import { useState } from "react";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Avatar, Card, Confirm, Empty, full, Modal, Msg, PageHeader, SecretDialog, Status, usePoll, useToast, when } from "../ui.jsx";

// Users (users.manage): the members of the organization the session works in, and their roles here. One account can be a
// member of several organizations (different roles in each). The account itself (name, e-mail, password, enabled) of
// someone who is also in another organization can only be changed by a platform admin.

export default function Users() {
  const { user: me, org } = useSession();
  const toast = useToast();
  const users = usePoll("users", 0);
  const roles = usePoll("roles", 0);
  const [q, setQ] = useState("");
  const [editing, setEditing] = useState(null);     // null | "new" | user
  const [confirm, setConfirm] = useState(null);     // { user, action, title, subtitle, danger, method, path, label }
  const [secret, setSecret] = useState(null);       // { title, subtitle, value }

  const n = q.trim().toLowerCase();
  const list = (users.data?.users ?? []).filter((u) => !n || [u.email, u.firstName, u.lastName].join(" ").toLowerCase().includes(n));
  const editable = (u) => !u.platformAdmin || me.platformAdmin;

  return (
    <>
      <PageHeader title="Users" subtitle={`Members of ${org.name} and their roles here. One account can belong to several organizations.`}>
        <button className="btn" onClick={() => setEditing("new")} disabled={!roles.data}>Add user</button>
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
                <tr key={u.id}>
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
                  <td>
                    {u.platformAdmin && <span className="chip accent">Platform admin</span>}
                    {u.roles.map((r) => <span className="chip" key={r.id} title={r.name}>{r.name}</span>)}
                    {!u.platformAdmin && !u.roles.length && <span className="muted small">No gateway access here</span>}
                  </td>
                  <td><Status kind={kind}>{text}</Status>{u.activeSessions > 0 && <small>{u.activeSessions} active session(s)</small>}</td>
                  <td title={full(u.lastLogin)}>{when(u.lastLogin)}{u.lastLoginIp && <small>{u.lastLoginIp}</small>}</td>
                  <td className="r">
                    <div className="row-acts">
                      <button className="btn ghost sm" disabled={!ok} title={ok ? undefined : "Only platform admins can change platform admin accounts"}
                              onClick={() => setEditing(u)}>Edit</button>
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
        <UserDialog user={editing === "new" ? null : editing} roles={roles.data.roles} me={me} org={org}
                    onClose={() => setEditing(null)}
                    onSaved={(r) => {
                      setEditing(null);
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
      {secret && <SecretDialog title={secret.title} subtitle={secret.subtitle} secret={secret.value} onClose={() => setSecret(null)} />}
    </>
  );
}

function UserDialog({ user, roles, me, org, onClose, onSaved }) {
  const isNew = !user;
  const [existing, setExisting] = useState(false);   // new: add an account that already exists (e.g. from another organization)
  const [f, setF] = useState({
    firstName: user?.firstName ?? "", lastName: user?.lastName ?? "", email: user?.email ?? "",
    active: user ? user.active : true, roleIds: (user?.roles ?? []).map((r) => r.id),
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === "checkbox" ? e.target.checked : e.target.value });
  const mine = new Set(me.permissions);
  const toggleRole = (id) => setF({ ...f, roleIds: f.roleIds.includes(id) ? f.roleIds.filter((x) => x !== id) : [...f.roleIds, id] });
  const account = isNew ? !existing : user.accountEditable;     // name / e-mail / enabled can be changed
  const lockActive = isNew || user.id === me.id || user.platformAdmin || !user.accountEditable;

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = isNew && existing
        ? { existing: true, email: f.email.trim(), roleIds: f.roleIds }
        : account ? { ...f, email: f.email.trim() } : { roleIds: f.roleIds };
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
             <button className="btn" disabled={busy} onClick={save}>{isNew ? (existing ? "Add to organization" : "Create user") : "Save"}</button></>}>
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
        {roles.map((r) => {
          const missing = r.permissions.filter((p) => !mine.has(p));
          const has = f.roleIds.includes(r.id);
          return (
            <label className="check" key={r.id}>
              <input type="checkbox" checked={has} disabled={missing.length > 0 && !has} onChange={() => toggleRole(r.id)} />
              <span><b>{r.name}</b> {!r.shared && <span className="chip" title={org.name}>{org.name}</span>} <small className="muted">{r.description}</small>
                {missing.length > 0 && !has && <small className="muted"> — you cannot grant it</small>}</span>
            </label>
          );
        })}
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
