import { useCallback, useEffect, useState } from "react";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Card, Empty, full, Msg, PageHeader, Status, when } from "../ui.jsx";

// Audit log (audit.view): sign-ins and every change in the organization, newest first. Platform admins can also look at
// every organization's events at once.

const ACTION = {
  login: "Sign-in", logout: "Sign-out", "account.locked": "Account locked", "session.timeout": "Session timed out",
  "session.end": "Sessions ended", "password.change": "Password changed", "profile.update": "Profile updated",
  "user.create": "User created", "user.update": "User changed", "user.reset_password": "Password reset",
  "user.unlock": "Account unlocked", "user.sign_out": "User signed out", "role.create": "Role created",
  "role.update": "Role changed", "role.delete": "Role deleted", "agent.revoke": "Site agent revoked",
  "user.add": "Member added", "user.remove": "Member removed", "org.switch": "Switched organization",
  "org.moved": "Moved to another organization", "org.create": "Organization created", "org.rename": "Organization renamed",
  "org.delete": "Organization deleted", "org.default": "Default organization set", "machine.assign": "Machine assigned",
  "destination.create": "Destination added", "destination.update": "Destination changed", "destination.delete": "Destination removed",
  "destination.test": "Destination tested", "destination.send-test": "Test reading sent", "pipeline.create": "Pipeline created",
  "pipeline.update": "Pipeline changed", "pipeline.delete": "Pipeline deleted", "pipeline.activate": "Pipeline activated",
  "pipeline.pause": "Pipeline paused",
};

const detail = (d) => Object.entries(d || {}).map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(", ") || "—" : v}`).join(" · ");

export default function Audit() {
  const { org, user } = useSession();
  const [q, setQ] = useState("");
  const [all, setAll] = useState(false);       // platform admins: every organization
  const [events, setEvents] = useState(null);
  const [more, setMore] = useState(false);
  const [error, setError] = useState(null);

  const load = useCallback(async (query, before) => {
    try {
      const params = new URLSearchParams({ q: query });
      if (before) params.set("before", before);
      if (all) params.set("scope", "all");
      const rows = (await api(`audit?${params}`)).events;
      setEvents((old) => (before ? [...(old ?? []), ...rows] : rows));
      setMore(rows.length === 100);
      setError(null);
    } catch (e) {
      if (e.status !== 401) setError(e.message);
    }
  }, [all]);

  useEffect(() => {
    const t = setTimeout(() => load(q.trim(), null), q ? 300 : 0);
    return () => clearTimeout(t);
  }, [q, load]);
  const cols = all ? 8 : 7;

  return (
    <>
      <PageHeader title="Audit log" subtitle={all ? "Every organization's sign-ins and changes, newest first." : `Sign-ins and every change in ${org.name}, newest first.`} />
      <div className="toolbar">
        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search user, action, target or IP" aria-label="Search the audit log" />
        {user.platformAdmin && (
          <label className="check"><input type="checkbox" checked={all} onChange={(e) => { setEvents(null); setAll(e.target.checked); }} />
            <span>All organizations</span></label>
        )}
      </div>
      <Msg>{error}</Msg>
      <Card flush>
        <table>
          <thead><tr><th>When</th>{all && <th>Organization</th>}<th>User</th><th>Action</th><th>Target</th><th>Result</th><th>IP</th><th>Details</th></tr></thead>
          <tbody>
            {!events && <Empty cols={cols}>Loading…</Empty>}
            {events?.length === 0 && <Empty cols={cols}>Nothing found.</Empty>}
            {events?.map((e) => (
              <tr key={e.id}>
                <td className="nowrap" title={full(e.at)}>{when(e.at)}</td>
                {all && <td>{e.org ?? <span className="muted">—</span>}</td>}
                <td>{e.actor || "—"}</td>
                <td>{ACTION[e.action] ?? e.action}</td>
                <td>{e.target}</td>
                <td><Status kind={e.success ? "good" : "bad"}>{e.success ? "OK" : "Refused"}</Status></td>
                <td className="mono nowrap">{e.ip}</td>
                <td className="small muted">{detail(e.detail)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      {more && <div><button className="btn ghost" onClick={() => load(q.trim(), events[events.length - 1].id)}>Load older entries</button></div>}
    </>
  );
}
