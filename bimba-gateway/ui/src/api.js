// Calls to the gateway console API (/api/gw/...). Spring Security's SPA CSRF protection: the server sets the
// XSRF-TOKEN cookie and every change must send it back as the X-XSRF-TOKEN header. The session itself is an
// HttpOnly cookie the browser sends on its own. Every call also names the organization the page shows (X-BIMBA-Org), so a
// tab that still shows organization A cannot change anything after another tab switched the session to organization B.

export class ApiError extends Error {
  constructor(message, status, code, body) {
    super(message);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

const handlers = { signedOut: () => {}, passwordRequired: () => {}, orgChanged: () => {} };
let shownOrg = "";

/** the organization the page shows (set by the session provider) */
export function setShownOrg(id) {
  shownOrg = id == null ? "" : String(id);
}

/** The session provider reacts when the server says the session ended or a new password is needed first. */
export function onSessionEvents(h) {
  Object.assign(handlers, h);
}

function cookie(name) {
  const m = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return m ? decodeURIComponent(m[1]) : "";
}

/** background: automatic refreshes, which must not count as user activity (idle timeout). */
export async function api(path, { method = "GET", body, background = false } = {}) {
  const headers = {};
  const raw = typeof Blob !== "undefined" && body instanceof Blob;     // a file (profile photo) is sent as it is
  if (body !== undefined) headers["Content-Type"] = raw ? body.type || "application/octet-stream" : "application/json";
  if (method !== "GET") headers["X-XSRF-TOKEN"] = cookie("XSRF-TOKEN");
  if (background) headers["X-BIMBA-Background"] = "1";
  if (shownOrg) headers["X-BIMBA-Org"] = shownOrg;
  const r = await fetch(`/api/gw/${path}`, {
    method,
    headers,
    credentials: "same-origin",
    body: body === undefined ? undefined : raw ? body : JSON.stringify(body),
  });
  let data = {};
  try {
    data = await r.json();
  } catch {
    /* empty body */
  }
  if (!r.ok) {
    const err = new ApiError(data.error || `The gateway answered HTTP ${r.status}.`, r.status, data.code, data);
    if (r.status === 401 && data.code === "signed_out" && path !== "login") handlers.signedOut("Your session ended. Sign in again.");
    if (r.status === 403 && data.code === "password_change_required") handlers.passwordRequired();
    if (r.status === 409 && data.code === "org_changed") handlers.orgChanged(data.error);
    throw err;
  }
  return data;
}
