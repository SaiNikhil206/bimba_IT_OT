import { useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Modal, Msg, PageHeader, Status, useOrgPath, usePoll, useRecordAddress, useRemembered, useToast } from "../ui.jsx";

// Roles: sets of permissions (roles.manage edits; users.view / users.manage can see them, with who holds each). Built-in
// roles are shared by every organization (only platform admins change them) and follow the personas of the BRD; custom
// roles belong to the organization that created them. Three views: the roles, a grid of roles against permissions, and
// the duty separation rules Setu enforces.

const TABS = [["roles", "Roles"], ["grid", "Permission grid"], ["duties", "Duty separation"]];

export default function Roles() {
  const { can, everywhere, org } = useSession();
  const toast = useToast();
  const { data, error, reload } = usePoll("roles", 0);
  const [view, setView] = useRemembered("roles.view", { tab: "roles" });
  const tab = view.tab;
  const setTab = (t) => setView({ tab: t });
  const [deleting, setDeleting] = useState(null);
  const [cloning, setCloning] = useState(null);
  const manage = can("roles.manage");
  const rec = useRecordAddress(useOrgPath()("/roles"), data?.roles, "role");
  const editing = manage && (rec.record === "new" || rec.record?.editable) ? rec.record : null;   // null | "new" | role
  const why = (r) => (r.locked ? "The Administrator role always has every permission"
    : r.shared && !r.editable ? "Built-in roles are shared by every organization; only platform admins change them" : undefined);

  return (
    <>
      <PageHeader title="Roles" subtitle={`A role is a set of permissions. A user's access in ${org.name} is everything their roles here allow, where each role is given.`}>
        {manage && <button className="btn" onClick={() => rec.open("new")} disabled={!data}>New role</button>}
      </PageHeader>
      <div className="tabs" role="tablist">
        {TABS.map(([k, label]) => (
          <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? "on" : ""} onClick={() => setTab(k)}>{label}</button>
        ))}
      </div>
      <Msg>{error}</Msg>
      {!data ? <p className="muted">Loading…</p> : tab === "grid" ? <Grid data={data} org={org} onOpen={(r) => { setTab("roles"); rec.open(r); }} />
        : tab === "duties" ? <Duties /> : (
          <div className="roles">
            {data.roles.map((r) => (
              <RoleCard key={r.id} r={r} data={data} org={org} manage={manage} mark={rec.mark(r)} why={why(r)}
                        onEdit={() => rec.open(r)} onClone={() => setCloning(r)} onDelete={() => setDeleting(r)} />
            ))}
          </div>
        )}
      {(editing || cloning) && (
        <RoleDialog role={editing && editing !== "new" ? editing : null} from={cloning} catalog={data.permissions} can={can} everywhere={everywhere} org={org}
                    onClose={() => { if (cloning) setCloning(null); else rec.close(); }}
                    onSaved={() => { setCloning(null); rec.close(); toast("Role saved."); reload(); }} />
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

function RoleCard({ r, data, org, manage, mark, why, onEdit, onClone, onDelete }) {
  const at = useOrgPath();
  const [all, setAll] = useState(false);
  const perm = (k) => data.permissions.find((p) => p.key === k);
  const holders = r.holders ?? null;
  const shown = holders && (all ? holders : holders.slice(0, 4));
  return (
    <Card className="role-card" {...mark}>
      <div>
        <h2 className="role-h">
          <span className="ellipsis" title={r.name}>{r.name}</span>
          {r.persona && <span className="chip" title={`BRD persona: ${r.persona.name}`}>Persona {r.persona.code}</span>}
          {r.builtin ? <span className="chip">Built-in</span> : <span className="chip accent" title={`${org.name} only`}>{org.name} only</span>}
        </h2>
        <p className="sub">{r.description || "—"}</p>
      </div>
      <div className="perm-chips">
        {r.locked ? <span className="chip accent">Every permission, including new ones</span>
          : r.permissions.length ? r.permissions.map((k) => (
            <span className="chip accent" key={k} title={perm(k)?.label ?? k}>{k}{perm(k)?.scoped && <span className="scoped-dot" aria-label="can be limited to places" />}</span>
          )) : <span className="muted small">No permissions</span>}
      </div>
      {holders ? (
        <div className="holders">
          <span className="label">{holders.length ? `${holders.length} member(s) in ${org.name}` : `Nobody in ${org.name} has this role`}</span>
          {shown.map((h) => (
            <Link key={`${h.id}`} className="holder" to={at(`/users`)} title={h.email}>
              <span className="ellipsis">{h.name}</span>
              <small className="ellipsis" title={h.scopeText ?? "Whole organization"}>{h.scopeText ?? "Whole organization"}</small>
            </Link>
          ))}
          {holders.length > 4 && <button className="link" onClick={() => setAll(!all)}>{all ? "Show fewer" : `Show all ${holders.length}`}</button>}
        </div>
      ) : <p className="muted small">{r.members} user(s) in {org.name}</p>}
      {manage && (
        <div className="acts">
          <button className="btn ghost sm" disabled={!r.editable} title={why} onClick={onEdit}>Edit</button>
          {!r.locked && <button className="btn ghost sm" onClick={onClone} title="A new role of this organization with the same permissions">Clone</button>}
          {!r.builtin && r.editable && <button className="btn danger sm" onClick={onDelete}>Delete…</button>}
        </div>
      )}
    </Card>
  );
}

/** roles across, permissions down: who can do what at a glance */
function Grid({ data, org, onOpen }) {
  const groups = [...new Set(data.permissions.map((p) => p.group))];
  const has = (r, k) => r.locked || r.permissions.includes(k);
  return (
    <Card flush>
      <div className="grid-wrap">
        <table className="perm-grid">
          <thead>
            <tr>
              <th className="pg-perm">Permission</th>
              {data.roles.map((r) => (
                <th key={r.id} className="pg-role">
                  <button className="link ellipsis" title={`${r.name}${r.persona ? ` (persona ${r.persona.code})` : ""}`} onClick={() => onOpen(r)}>{r.name}</button>
                  <small>{r.persona ? r.persona.code : r.builtin ? "built-in" : org.name}</small>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {groups.map((g) => [
              <tr key={`g-${g}`} className="pg-group"><th colSpan={data.roles.length + 1}>{g}</th></tr>,
              ...data.permissions.filter((p) => p.group === g).map((p) => (
                <tr key={p.key}>
                  <th className="pg-perm" scope="row">
                    <b className="mono ellipsis" title={p.key}>{p.key}</b>
                    <small title={p.label}>{p.label}{p.scoped ? " · can be limited to places" : ""}</small>
                  </th>
                  {data.roles.map((r) => (
                    <td key={r.id} className={`pg-cell${has(r, p.key) ? " on" : ""}`} title={`${r.name}: ${has(r, p.key) ? "yes" : "no"}`}>
                      {has(r, p.key) ? <span aria-label="yes">✓</span> : <span className="muted" aria-label="no">·</span>}
                    </td>
                  ))}
                </tr>
              )),
            ])}
            <tr className="pg-group"><th scope="row" className="pg-perm">Members</th>
              {data.roles.map((r) => <td key={r.id} className="pg-cell">{r.members}</td>)}
            </tr>
          </tbody>
        </table>
      </div>
    </Card>
  );
}

/** the duty separation rules (BRD 4.5), members who break one, and the personas behind the built-in roles */
function Duties() {
  const at = useOrgPath();
  const { data, error } = usePoll("access/duties", 0);
  if (error) return <Msg>{error}</Msg>;
  if (!data) return <p className="muted">Loading…</p>;
  return (
    <>
      {data.violations.length > 0 && (
        <Msg kind="warn">
          {data.violations.length} member(s) break a rule, from changes made before the rule or outside Setu:{" "}
          {data.violations.map((v) => v.email).join(", ")}. Change their roles under <Link to={at("/users")}>Users</Link>.
        </Msg>
      )}
      <Card title="Rules" subtitle="Setu checks these itself on every change; they cannot be switched off.">
        <ul className="duty-rules">
          {data.rules.map((r) => (
            <li key={r.id}>
              <Status kind={r.enforced ? "good" : ""}>{r.enforced ? "Enforced" : "Planned"}</Status>
              <div><b>{r.title}</b><small>{r.detail}</small></div>
            </li>
          ))}
        </ul>
      </Card>
      <Card title="Personas" subtitle="The built-in roles follow the personas of the BIMBA OT/IT Bridge BRD (section 4.2).">
        <table>
          <thead><tr><th>Code</th><th>Persona</th><th>Built-in role</th></tr></thead>
          <tbody>
            {data.personas.map((p) => (
              <tr key={p.code}><td className="mono">{p.code}</td><td>{p.name}</td><td>{p.role}</td></tr>
            ))}
            {data.personasNotShipped.map((p) => (
              <tr key={p.code}><td className="mono">{p.code}</td><td>{p.name}</td><td className="muted">Not yet: {p.why}</td></tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}

function RoleDialog({ role, from, catalog, can, everywhere, org, onClose, onSaved }) {
  // from = a role to clone: a new role of this organization with its permissions (those this user may give)
  const start = from ? from.permissions.filter((k) => grantable(k)) : role?.permissions ?? [];
  const [name, setName] = useState(from ? `${from.name} (copy)` : role?.name ?? "");
  const [description, setDescription] = useState(from ? from.description : role?.description ?? "");
  const [perms, setPerms] = useState(start);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const groups = [...new Set(catalog.map((p) => p.group))];
  const toggle = (k) => setPerms(perms.includes(k) ? perms.filter((x) => x !== k) : [...perms, k]);
  const had = new Set(role?.permissions ?? []);
  const dropped = from ? from.permissions.filter((k) => !grantable(k)) : [];

  // what is being added must be held, and a site-bound permission held for the whole organization
  function grantable(k) {
    const p = catalog.find((x) => x.key === k);
    return can(k) && (!p?.scoped || everywhere(k));
  }
  const why = (p) => (!can(p.key) ? " — you cannot grant it" : p.scoped && !everywhere(p.key) ? " — you hold it only for some places" : "");

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
    <Modal title={role ? `Edit role ${role.name}` : from ? `Clone ${from.name}` : `New role for ${org.name}`} onClose={onClose}
           subtitle={role?.shared ? "This built-in role is shared: a change applies in every organization." : role ? null : `Only ${org.name} can see and use this role.`}
           footer={<><button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy} onClick={save}>{role ? "Save" : "Create role"}</button></>}>
      <div className="field">
        <label htmlFor="r-n">Name</label>
        <input id="r-n" type="text" value={name} onChange={(e) => setName(e.target.value)} disabled={role?.builtin} />
        {role?.builtin && <span className="hint">Built-in roles keep their name.</span>}
      </div>
      <div className="field"><label htmlFor="r-d">Description</label><input id="r-d" type="text" value={description} onChange={(e) => setDescription(e.target.value)} /></div>
      {dropped.length > 0 && <Msg kind="info">Left out because you cannot give them: {dropped.join(", ")}.</Msg>}
      <div className="field">
        <span className="label">Permissions</span>
        <span className="hint">Site agent, machine and command permissions can be limited to some sites, site agents or machines when the role is given to someone (Users).</span>
        {groups.map((g) => (
          <div className="perm-grp" key={g}>
            <h3>{g}</h3>
            {catalog.filter((p) => p.group === g).map((p) => {
              const ok = had.has(p.key) || grantable(p.key);
              return (
                <label className="check" key={p.key}>
                  <input type="checkbox" checked={perms.includes(p.key)} disabled={!ok} onChange={() => toggle(p.key)} />
                  <span><b className="mono">{p.key}</b><small>{p.label}{p.scoped ? " (can be limited to places)" : ""}{ok ? "" : why(p)}</small></span>
                </label>
              );
            })}
          </div>
        ))}
      </div>
      <Msg>{error}</Msg>
    </Modal>
  );
}
