// URLs of BIMBA Operations. /operations/<page>/<a>/<b>
export const BASE = "/operations";
export const to = (...parts) => [BASE, ...parts.filter(Boolean).map(encodeURIComponent)].join("/");
