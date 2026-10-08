import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Confirm, Empty, full, Modal, Msg, PageHeader, usePoll, useRecordAddress, useToast } from "../ui.jsx";

// Organizations (platform admins): the tenants of this gateway and which organization gets each machine's readings.
// Each organization has its own users and roles, destinations, pipelines, site agents and audit log; one account can
// belong to several. A machine belongs to exactly one organization; machines nobody assigned go to the default one.

export default function Organizations() {
  const s = useSession();
  const toast = useToast();
  const navigate = useNavigate();
  const { data, error, reload } = usePoll("orgs", 0);
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(null);
  const [problem, setProblem] = useState(null);

  const orgs = data?.organizations ?? [];
  const rec = useRecordAddress("/organizations", data?.organizations, "organization");
  const editing = rec.record;                          // null | "new" | org
  const machines = data?.machines ?? [];
  const byId = Object.fromEntries(orgs.map((o) => [o.id, o]));
  const def = byId[data?.defaultOrgId];
  const count = (id) => machines.filter((m) => m.orgId === id && m.inTagTree).length;

  const run = async (key, fn, done) => {
    setBusy(key);
    setProblem(null);
    try {
      await fn();
      if (done) toast(done);
      await reload();
    } catch (e) {
      setProblem(e.message);
    } finally {
      setBusy(null);
    }
  };
  const open = async (o) => {
    try {
      if (o.id !== s.org?.id) await s.switchOrg(o.id);
      navigate(`/${o.slug}/users`);
      toast(`You are working in ${o.name}.`);
    } catch (e) {
      setProblem(e.message);
    }
  };

  return (
    <>
      <PageHeader title="Organizations" subtitle="Every organization has its own users, roles, destinations, pipelines, site agents and audit log. One account can belong to several.">
        <button className="btn" onClick={() => rec.open("new")}>New organization</button>
      </PageHeader>
      <Msg>{error || problem}</Msg>
      <Card flush>
        <table>
          <thead><tr><th>Organization</th><th>Users</th><th>Machines</th><th>Pipelines</th><th>Destinations</th><th>Site agents</th><th className="r" /></tr></thead>
          <tbody>
            {!data && <Empty cols={7}>Loading…</Empty>}
            {data && orgs.length === 0 && <Empty cols={7}>No organization yet. Create the first one.</Empty>}
            {orgs.map((o) => (
              <tr key={o.id} {...rec.mark(o)}>
                <td>
                  <b>{o.name}</b> {o.default && <span className="chip accent" title="Gets the machines nobody assigned">Default</span>}
                  {o.id === s.org?.id && <span className="chip">You are working here</span>}
                  <small title={full(o.createdAt)}>{o.slug}</small>
                </td>
                <td>{o.gatewayUsers}{o.members !== o.gatewayUsers && <small>{o.members} member(s) in total</small>}</td>
                <td>{count(o.id)}</td>
                <td>{o.activePipelines} active{o.pipelines !== o.activePipelines && <small>{o.pipelines - o.activePipelines} draft / paused</small>}</td>
                <td>{o.destinations}</td>
                <td>{o.agents}</td>
                <td className="r">
                  <div className="row-acts">
                    <button className="btn ghost sm" onClick={() => open(o)} title="Switch to this organization and manage its users">Open users</button>
                    <button className="btn ghost sm" onClick={() => rec.open(o)}>Rename</button>
                    {!o.default && (
                      <button className="btn ghost sm" disabled={busy === `d${o.id}`}
                              onClick={() => run(`d${o.id}`, () => api("orgs/default", { method: "PUT", body: { orgId: o.id } }), `${o.name} now gets the unassigned machines.`)}>
                        Make default</button>
                    )}
                    {!o.default && <button className="btn danger sm" onClick={() => setDeleting(o)}>Delete…</button>}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <Card flush title="Machines"
            subtitle={`Readings of a machine go only to its organization's pipelines. Machines nobody assigned belong to ${def ? def.name : "the default organization"} (also ones added to the tag tree later).`}>
        <table>
          <thead><tr><th>Machine</th><th>Organization</th><th /></tr></thead>
          <tbody>
            {data && machines.length === 0 && <Empty cols={3}>The gateway reads no machines yet.</Empty>}
            {machines.map((m) => (
              <tr key={m.name}>
                <td><b className="mono">{m.name}</b>{!m.inTagTree && <small>Not in the tag tree right now</small>}</td>
                <td>
                  <select aria-label={`Organization of ${m.name}`} value={m.assigned ? String(m.orgId) : ""} disabled={busy === m.name || !orgs.length}
                          onChange={(e) => run(m.name, () => api("machines", { method: "PUT", body: { machine: m.name, orgId: e.target.value ? Number(e.target.value) : null } }),
                            `${m.name} moved to ${e.target.value ? byId[Number(e.target.value)]?.name : `${def?.name} (default)`}.`)}>
                    <option value="">{def ? `${def.name} (default)` : "Default organization"}</option>
                    {orgs.map((o) => <option key={o.id} value={String(o.id)}>{o.name}</option>)}
                  </select>
                </td>
                <td className="muted small">{m.assigned ? "Assigned" : "Default"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      {editing && (
        <OrgDialog org={editing === "new" ? null : editing} onClose={rec.close}
                   onSaved={async (msg) => { rec.close(); toast(msg); await reload(); await s.reload(); }} />
      )}
      {deleting && (
        <Confirm title={`Delete ${deleting.name}?`} danger confirm="Delete organization"
                 subtitle={`Its members lose their roles there (their accounts and other organizations stay), and its custom roles, enrollment tokens and audit history are removed. Only an organization without destinations, pipelines, site agents or machines can be deleted.`}
                 action={() => api(`orgs/${deleting.id}`, { method: "DELETE" })}
                 onDone={async () => { toast(`${deleting.name} deleted.`); setDeleting(null); await reload(); await s.reload(); }}
                 onClose={() => setDeleting(null)} />
      )}
    </>
  );
}

function OrgDialog({ org, onClose, onSaved }) {
  const [name, setName] = useState(org?.name ?? "");
  const [slug, setSlug] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const auto = name.normalize("NFKD").replace(/\p{M}/gu, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 64);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      if (org) {
        await api(`orgs/${org.id}`, { method: "PUT", body: { name: name.trim() } });
        onSaved("Organization renamed.");
      } else {
        const o = await api("orgs", { method: "POST", body: { name: name.trim(), slug: slug.trim() || undefined } });
        onSaved(`${o.name} created. Open its users to add the first members.`);
      }
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };

  return (
    <Modal title={org ? `Rename ${org.name}` : "New organization"} onClose={onClose}
           subtitle={org ? null : "It starts empty: add members under Users (open it from this list), then destinations and pipelines, and assign it machines."}
           footer={<><button className="btn ghost" onClick={onClose}>Cancel</button>
             <button className="btn" disabled={busy || !name.trim()} onClick={save}>{org ? "Save" : "Create organization"}</button></>}>
      <div className="field">
        <label htmlFor="o-n">Name</label>
        <input id="o-n" type="text" value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" />
      </div>
      {!org && (
        <div className="field">
          <label htmlFor="o-s">Short name</label>
          <input id="o-s" type="text" value={slug} onChange={(e) => setSlug(e.target.value)} placeholder={auto || "acme-water"} autoComplete="off" spellCheck="false" />
          <span className="hint">Lower-case letters, digits and -. Site agent installers use it to pick the organization. Cannot be changed later.</span>
        </div>
      )}
      <Msg>{error}</Msg>
    </Modal>
  );
}
