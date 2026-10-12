"""Test helper for the Linux site agent: drives the Setu console API like the React app does.

  python console.py <setu> <email> <password-file> <org-slug> token            -> prints a new enrollment token
  python console.py <setu> <email> <password-file> <org-slug> sources <agentId>  -> adds the simulator sources
  python console.py <setu> <email> <password-file> <org-slug> check <agentId>    -> test command + collector state
  python console.py <setu> <email> <password-file> <org-slug> revoke <agentId>

Talks to the local test Setu server only (self-signed certificate is not verified).
"""
import http.cookiejar
import json
import ssl
import sys
import time
import urllib.request

gw, email, pw_file, org_slug, action = sys.argv[1:6]
arg = sys.argv[6] if len(sys.argv) > 6 else None
ctx = ssl._create_unverified_context()
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx), urllib.request.HTTPCookieProcessor(jar))


def call(method, path, body=None):
    xsrf = next((c.value for c in jar if c.name == "XSRF-TOKEN"), None)
    req = urllib.request.Request(gw + "/api/gw" + path, method=method,
                                 data=None if body is None else json.dumps(body).encode())
    req.add_header("Content-Type", "application/json")
    if xsrf:
        req.add_header("X-XSRF-TOKEN", xsrf)
    try:
        with opener.open(req, timeout=60) as r:
            t = r.read().decode()
            return json.loads(t) if t else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path}: HTTP {e.code} {e.read().decode()[:300]}")


try:
    opener.open(gw + "/api/gw/session", timeout=20)          # sets the XSRF-TOKEN cookie
except Exception:
    pass
login = call("POST", "/login", {"email": email, "password": open(pw_file).read().strip()})
org = next(o for o in login["organizations"] if o.get("slug") == org_slug)
call("POST", "/session/organization", {"orgId": org["id"]})

if action == "token":
    t = call("POST", "/agents/tokens", {"label": "linux agent test", "maxUses": 5, "days": 1})
    print(t.get("token"))
elif action == "sources":
    call("POST", f"/agents/{arg}/sources", {
        "name": "LINUX-PLC-" + arg[:4].upper(), "kind": "modbus", "pollMs": 1000, "enabled": True,
        "settings": {"host": "plc-sim", "port": 5020, "unitId": 1, "timeoutMs": 1500, "zeroBased": False},
        "tags": [{"name": "level", "address": "HR1", "dataType": "Float8"},
                 {"name": "running", "address": "C1", "dataType": "Boolean"}]})
    call("POST", f"/agents/{arg}/sources", {
        "name": "LINUX-METER-" + arg[:4].upper(), "kind": "opcua", "pollMs": 1000, "enabled": True,
        "settings": {"endpoint": "opc.tcp://opcua-sim:4840/scada/", "policy": "None", "mode": "None", "auth": "Anonymous",
                     "timeoutMs": 4000},
        "tags": [{"name": "bus_voltage", "address": "ns=2;s=Meter.Voltage", "dataType": "Float8"}]})
    print("sources added")
elif action == "check":
    cmd = call("POST", f"/agents/{arg}/commands", {"kind": "test", "source": {
        "kind": "modbus", "settings": {"host": "plc-sim", "port": 5020, "unitId": 1, "timeoutMs": 1500}},
        "tags": [{"name": "level", "address": "HR1"}]})
    cid = cmd.get("id")
    for _ in range(30):
        st = call("GET", f"/agents/{arg}/commands/{cid}")
        if st.get("state") not in ("pending", "sent", "queued"):
            break
        time.sleep(1)
    print("console test command:", json.dumps(st)[:400])
    d = call("GET", f"/agents/{arg}")
    a = d.get("agent", d)
    col = a.get("collector") or {}
    print("os:", a.get("os"), "| version:", a.get("agentVersion"), "| sign-in:", a.get("signIn"))
    print("collector:", json.dumps({k: col.get(k) for k in ("configVersion", "sources", "sent", "rejected", "lastUpload", "lastUploadError")}))
    print("edge:", json.dumps({k: (col.get("edge") or {}).get(k) for k in ("state", "java", "devices", "error")})[:600])
    live = call("GET", f"/agents/{arg}/live")
    print("live:", json.dumps(live)[:600])
elif action == "revoke":
    print(call("POST", f"/agents/{arg}/revoke", {}))
