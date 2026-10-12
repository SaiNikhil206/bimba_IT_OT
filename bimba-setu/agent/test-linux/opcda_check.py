"""TEST ONLY: OPC DA through the Setu console API, against the BIMBA OPC DA test server in the test VM.

  python opcda_check.py <setu> <email> <password-file> <org-slug> <agentId>
discovery -> test connection -> browse -> add source -> live values. Talks to the local test Setu server only.
"""
import http.cookiejar
import json
import ssl
import sys
import time
import urllib.request

gw, email, pw_file, org_slug, agent = sys.argv[1:6]
ctx = ssl._create_unverified_context()
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx), urllib.request.HTTPCookieProcessor(jar))


def call(method, path, body=None):
    xsrf = next((c.value for c in jar if c.name == "XSRF-TOKEN"), None)
    req = urllib.request.Request(gw + "/api/gw" + path, method=method, data=None if body is None else json.dumps(body).encode())
    req.add_header("Content-Type", "application/json")
    if xsrf:
        req.add_header("X-XSRF-TOKEN", xsrf)
    try:
        with opener.open(req, timeout=60) as r:
            t = r.read().decode()
            return json.loads(t) if t else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path}: HTTP {e.code} {e.read().decode()[:300]}")


def ask(body):
    cid = call("POST", f"/agents/{agent}/commands", body)["id"]
    for _ in range(60):
        st = call("GET", f"/agents/{agent}/commands/{cid}")
        if st.get("state") in ("done", "failed", "expired"):
            return st
        time.sleep(1)
    return {"state": "no answer"}


try:
    opener.open(gw + "/api/gw/session", timeout=20)
except Exception:
    pass
login = call("POST", "/login", {"email": email, "password": open(pw_file).read().strip()})
org = next(o for o in login["organizations"] if o.get("slug") == org_slug)
call("POST", "/session/organization", {"orgId": org["id"]})

d = call("GET", f"/agents/{agent}")
print("discovered OPC DA servers:", [s.get("progId") for s in (d.get("details") or {}).get("dataSources", {}).get("opcda", [])])
src = {"kind": "opcda", "settings": {"progId": "BIMBA.OpcDaTest.1", "host": "", "readFrom": "cache"}}
print("test:", json.dumps(ask({"kind": "test", "source": src}))[:300])
print("test+tags:", json.dumps(ask({"kind": "read", "source": src, "tags": [{"name": "ramp", "address": "Simulation.Ramp"},
                                                                         {"name": "bad", "address": "Faults.CommFailure"}]}))[:400])
print("browse root:", json.dumps(ask({"kind": "browse", "source": src, "nodeId": None, "limit": 100}))[:300])
print("browse Simulation:", json.dumps(ask({"kind": "browse", "source": src, "nodeId": "Simulation", "limit": 100}))[:300])

if not any(s["name"] == "DA-TEST" for s in d.get("sources", [])):
    call("POST", f"/agents/{agent}/sources", {
        "name": "DA-TEST", "kind": "opcda", "pollMs": 1000, "enabled": True,
        "settings": {"progId": "BIMBA.OpcDaTest.1", "host": "", "readFrom": "cache"},
        "tags": [{"name": "ramp", "address": "Simulation.Ramp", "unit": "%"},
                 {"name": "switch", "address": "Simulation.Switch"},
                 {"name": "text", "address": "Simulation.Text"},
                 {"name": "counter", "address": "Simulation.Counter"},
                 {"name": "comm_failure", "address": "Faults.CommFailure"},
                 {"name": "uncertain", "address": "Faults.Uncertain"}]})
    print("source DA-TEST added")
time.sleep(25)
live = call("GET", f"/agents/{agent}/live")
for v in live.get("values", []):
    if v["machine"] == "DA-TEST":
        print("live:", v["tag"], "=", v["value"], v["unit"], v["quality"], v["detail"], v["timestamp"])
d = call("GET", f"/agents/{agent}")
edge = (d.get("collector") or {}).get("edge") or {}
print("edge devices:", json.dumps(edge.get("devices"))[:400])
