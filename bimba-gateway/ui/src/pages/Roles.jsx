import { useState } from "react";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Modal, Msg, PageHeader, useOrgPath, usePoll, useRecordAddress, useToast } from "../ui.jsx";

// Roles: sets of permissions (roles.manage edits; users.manage can see them to assign them). Built-in roles are shared by
// every organization (only platform admins change them); custom roles belong to the organization that created them.

export default function Roles() {
  const { can, org } = useSession();
  const toast = useToast();
  const { data, error, reload } = usePoll("roles", 0);
  const [deleting, setDeleting] = useState(null);
  const manage = can("roles.manage");
  const rec = useRecordAddress(useOrgPath()("/roles"), data?.roles, "role");
  const editing = manage && (rec.record === "new" || rec.record?.editable) ? rec.record : null;   // null | "new" | role
  const label = (k) => data?.permissions.find((p) => p.key === k)?.label ?? k;
  const why = (r) => (r.locked ? "The Administrator role always has every permission"
    : r.shared && !r.editable ? "Built-in roles are shared by every organization; only platform admins change them" : undefined);

  return (
    <>
      <PageHeader title="Roles" subtitle={`A role is a set of permissions. A user's access in ${org.name} is everything their roles here allow.`}>
        {manage && <button className="btn" onClick={() => rec.open("new")} disabled={!data}>New role</button>}
      </PageHeader>
      <Msg>{error}</Msg>
      {!data ? <p className="muted">Loading…</p> : (
        <div className="roles">
          {data.roles.map((r) => (
            <Card key={r.id} className="role-card" {...rec.mark(r)}>
              <div>
                <h2>{r.name} {r.builtin ? <span className="chip">Built-in · every organization</span> : <span className="chip accent" title={`${org.name} only`}>{org.name} only</span>}</h2>
                <p className="sub">{r.description || "—"}</p>
              </div>
              <div>{r.permissions.length ? r.permissions.map((p) => <span className="chip accent" key={p} title={label(p)}>{p}</span>)
                : <span className="muted small">No permissions</span>}</div>
              <p className="muted small">{r.members} user(s) in {org.name}</p>
              {manage && (
                <div className="acts">
                  <button className="btn ghost sm" disabled={!r.editable} title={why(r)} onClick={() => rec.open(r)}>Edit</button>
                  {!r.builtin && r.editable && <button className="btn danger sm" onClick={() => setDeleting(r)}>Delete…</button>}
                </div>
              )}
            </Card>
          ))}
        </div>
      )}
      {editing && (
        <RoleDialog role={editing === "new" ? null : editing} catalog={data.permissions} can={can} org={org}
                    onClose={rec.close} onSaved={() => { rec.close(); toast("Role saved."); reload(); }} />
      )}
      {deleting && (
        <Confirm title={`Delete the role ${deleting.name}?`} danger confirm="Delete"
                 subtitle={deleting.members ? `${deleting.members} user(s) lose the permissions it gives.` : "No user has this role."}
                 action={() => api(`roles/${deleting.id}`, { method: "DELETE" })}
                 onDone={() => { setDeleting(null); toast("Role deleted."); reload(); }} onClose={() => setDeleting(null)} />
      )}
    </>
  );
}

function RoleDialog({ role, catalog, can, org, onClose, onSaved }) {
  const [name, setName] = useState(role?.name ?? "");
  const [description, setDescription] = useState(role?.description ?? "");
  const [perms, setPerms] = useState(role?.permissions ?? []);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const groups = [...new Set(catalog.map((p) => p.group))];
  const toggle = (k) => setPerms(perms.includes(k) ? perms.filter((x) => x !== k) : [...perms, k]);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = { name: name.trim(), description, permissions: perms };
      await (role ? api(`roles/${role.id}`, { method: "PUT", body }) : api("roles", { method: "POST", body }));
      onSaved();
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };

  return (
    <Modal title={role ? `Edit role ${role.name}` : `New role for ${org.name}`} onClose={onClose}
           subtitle={role?.shared ? "This built-in role is shared: a change applies in every organization." : role ? null : `Only ${org.name} can see and use this role.`}
           footer={<><button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy} onClick={save}>{role ? "Save" : "Create role"}</button></>}>
      <div className="field">
        <label htmlFor="r-n">Name</label>
        <input id="r-n" type="text" value={name} onChange={(e) => setName(e.target.value)} disabled={role?.builtin} />
        {role?.builtin && <span className="hint">Built-in roles keep their name.</span>}
      </div>
      <div className="field"><label htmlFor="r-d">Description</label><input id="r-d" type="text" value={description} onChange={(e) => setDescription(e.target.value)} /></div>
      <div className="field">
        <span className="label">Permissions</span>
        {groups.map((g) => (
          <div className="perm-grp" key={g}>
            <h3>{g}</h3>
            {catalog.filter((p) => p.group === g).map((p) => (
              <label className="check" key={p.key}>
                <input type="checkbox" checked={perms.includes(p.key)} disabled={!can(p.key)} onChange={() => toggle(p.key)} />
                <span><b className="mono">{p.key}</b><small>{p.label}{can(p.key) ? "" : " — you cannot grant it"}</small></span>
              </label>
            ))}
          </div>
        ))}
      </div>
      <Msg>{error}</Msg>
    </Modal>
  );
}
