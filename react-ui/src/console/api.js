// Calls to the BIMBA console API (/api/console/...). Writes use the Django session + CSRF cookie.

function cookie(name) {
  const m = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return m ? decodeURIComponent(m[1]) : "";
}

export async function api(path, { method = "GET", body } = {}) {
  const headers = { "X-CSRFToken": cookie("csrftoken") };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const r = await fetch(`/api/console/${path}`, {
    method,
    headers,
    credentials: "same-origin",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let data = {};
  try {
    data = await r.json();
  } catch {
    /* non-JSON error page */
  }
  if (!r.ok) {
    const err = new Error(data.error || (r.status === 403 ? "Request refused (CSRF). Reload the page." : `HTTP ${r.status}`));
    err.status = r.status;
    throw err;
  }
  return data;
}

export const clone = (o) => JSON.parse(JSON.stringify(o));
export const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

export function t3(d) {
  const x = d instanceof Date ? d : new Date(d);
  if (Number.isNaN(x.getTime())) return "–";
  return x.toTimeString().slice(0, 8) + "." + String(x.getMilliseconds()).padStart(3, "0");
}

export function ago(iso) {
  if (!iso) return "never";
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 2) return "just now";
  if (s < 60) return `${Math.round(s)} s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
}

export const PROTO = { modbus: "Modbus TCP", opcua: "OPC UA", expression: "Ignition expression", other: "Ignition tag" };
