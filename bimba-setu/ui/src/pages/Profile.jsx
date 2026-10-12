import { useRef, useState } from "react";
import { api } from "../api.js";
import { useSession } from "../session.jsx";
import { Avatar, browserName, Card, full, Msg, PageHeader, PasswordForm, usePoll, useToast, when } from "../ui.jsx";

// Your own account: details, password, and where you are signed in.

export default function Profile() {
  const s = useSession();
  const u = s.user;
  const toast = useToast();
  const sessions = usePoll("profile/sessions", 0);
  const [f, setF] = useState({ firstName: u.firstName || "", lastName: u.lastName || "" });
  const [error, setError] = useState(null);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });

  const save = async (e) => {
    e.preventDefault();
    setError(null);
    try {
      await api("profile", { method: "PUT", body: f });
      await s.reload();
      toast("Profile saved.");
    } catch (err) {
      setError(err.message);
    }
  };
  const end = async (path, method, message) => {
    try {
      const r = await api(path, { method });
      toast(message(r));
      sessions.reload();
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <>
      <PageHeader title="Your profile" subtitle={`Signed in as ${u.email}.`} />
      <div className="two">
        <div className="stack">
          <Photo />
          <Card title="Account">
            <dl className="kv">
              <dt>Sign-in e-mail</dt><dd><span className="mono">{u.email}</span><br /><small className="muted">Only an administrator can change it.</small></dd>
              <dt>Access in {s.org?.name ?? "—"}</dt>
              <dd>
                {u.platformAdmin && <span className="chip accent" title="Platform admins have every permission">Platform admin</span>}
                {u.permissions.map((p) => <span className="chip" key={p} title={p}>{p}</span>)}
              </dd>
            </dl>
          </Card>
          <Card flush title="Your organizations" subtitle={s.organizations.length > 1 ? "Switch between them at the top of the menu." : null}>
            <table>
              <thead><tr><th>Organization</th><th>Your roles</th><th /></tr></thead>
              <tbody>
                {s.organizations.map((o) => (
                  <tr key={o.id}>
                    <td><b>{o.name}</b><small>{o.slug}</small></td>
                    <td>{o.roles.length ? o.roles.map((r) => <span className="chip" key={r} title={r}>{r}</span>) : <span className="muted small">Platform admin</span>}</td>
                    <td className="r">{o.id === s.org?.id ? <span className="chip accent">Working here</span>
                      : <button className="btn ghost sm" onClick={() => s.switchOrg(o.id).then(() => toast(`You are working in ${o.name}.`)).catch((e) => setError(e.message))}>Switch</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
          <Card title="Your details">
            <form className="form" onSubmit={save}>
              <div className="grid2">
                <div className="field"><label htmlFor="p-f">First name</label><input id="p-f" type="text" value={f.firstName} onChange={set("firstName")} /></div>
                <div className="field"><label htmlFor="p-l">Last name</label><input id="p-l" type="text" value={f.lastName} onChange={set("lastName")} /></div>
              </div>
              <Msg>{error}</Msg>
              <div><button className="btn">Save</button></div>
            </form>
          </Card>
        </div>
        <Card title="Password" subtitle="Changing it signs out your other browsers.">
          <PasswordForm onDone={async () => { toast("Password changed."); sessions.reload(); }} />
        </Card>
      </div>
      <Card flush title="Where you are signed in"
            subtitle={`Sessions end after ${s.policy.idleMinutes} minutes without activity, or ${s.policy.maxSessionHours} hours after sign-in.`}
            action={<button className="btn ghost sm" onClick={() => end("profile/sessions/sign-out-others", "POST", (r) => `${r.ended} other session(s) ended.`)}>
              Sign out everywhere else</button>}>
        <table>
          <thead><tr><th>Browser</th><th>Signed in</th><th>Last activity</th><th /></tr></thead>
          <tbody>
            {(sessions.data?.sessions ?? []).map((x) => (
              <tr key={x.id}>
                <td>{browserName(x.userAgent)} {x.current && <span className="chip accent">This browser</span>}<small>{x.ip}</small></td>
                <td title={full(x.createdAt)}>{when(x.createdAt)}</td>
                <td title={full(x.lastSeenAt)}>{when(x.lastSeenAt)}</td>
                <td className="r">{!x.current && <button className="btn ghost sm" onClick={() => end(`profile/sessions/${x.id}`, "DELETE", () => "Session ended.")}>Sign out</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}

/** Profile photo: any picture the browser can open is cropped to a square, scaled to 256 x 256 and sent as JPEG. */
function Photo() {
  const s = useSession();
  const toast = useToast();
  const input = useRef(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const pick = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setError(null);
    if (!file.type.startsWith("image/") || file.type === "image/svg+xml") return setError("Choose a picture (PNG, JPEG, WebP, GIF…).");
    if (file.size > 15 * 1024 * 1024) return setError("That picture is larger than 15 MB.");
    setBusy(true);
    try {
      await api("profile/avatar", { method: "PUT", body: await square(file, 256) });
      await s.reload();
      toast("Photo updated.");
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };
  const remove = async () => {
    setBusy(true);
    setError(null);
    try {
      await api("profile/avatar", { method: "DELETE" });
      await s.reload();
      toast("Photo removed.");
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Photo" subtitle="Shown in the menu and to the members of your organizations.">
      <div className="photo">
        <Avatar user={s.user} size={88} />
        <div className="photo-acts">
          <input ref={input} type="file" accept="image/png,image/jpeg,image/webp,image/gif,image/bmp" hidden onChange={pick} />
          <button className="btn" disabled={busy} onClick={() => input.current?.click()}>{s.user.avatarUrl ? "Change photo" : "Upload photo"}</button>
          {s.user.avatarUrl && <button className="btn ghost" disabled={busy} onClick={remove}>Remove</button>}
          <span className="hint">A square crop from the middle of the picture is used.</span>
        </div>
      </div>
      <Msg>{error}</Msg>
    </Card>
  );
}

/** the picture's centre square at size x size, as a JPEG Blob (transparent areas become white) */
function square(file, size) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("The picture could not be read."));
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error("This picture format cannot be opened. Use PNG, JPEG or WebP."));
      img.onload = () => {
        const side = Math.min(img.naturalWidth, img.naturalHeight);
        const canvas = document.createElement("canvas");
        canvas.width = size;
        canvas.height = size;
        const ctx = canvas.getContext("2d");
        ctx.fillStyle = "#fff";
        ctx.fillRect(0, 0, size, size);
        ctx.imageSmoothingQuality = "high";
        ctx.drawImage(img, (img.naturalWidth - side) / 2, (img.naturalHeight - side) / 2, side, side, 0, 0, size, size);
        canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("The picture could not be converted."))), "image/jpeg", 0.88);
      };
      img.src = reader.result;            // a data: URL (the console's Content-Security-Policy allows data: images)
    };
    reader.readAsDataURL(file);
  });
}
