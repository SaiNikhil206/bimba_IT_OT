"""TEST ONLY: Siemens S7 + Allen-Bradley Logix sources through the Setu console API, against s7-sim and eip-sim.

  python plc_check.py <password-file> <agentId>
test connection, a refused address, add two sources, show live values. Local test Setu server only.
"""
import http.cookiejar
import json
import ssl
import sys
import time
import urllib.request

pw, agent = sys.argv[1:3]
ctx = ssl._create_unverified_context()
jar = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx), urllib.request.HTTPCookieProcessor(jar))
gw = "https://localhost:8443/api/gw"


def call(m, p, b=None):
    x = next((c.value for c in jar if c.name == "XSRF-TOKEN"), None)
    r = urllib.request.Request(gw + p, method=m, data=None if b is None else json.dumps(b).encode())
    r.add_header("Content-Type", "application/json")
    if x:
        r.add_header("X-XSRF-TOKEN", x)
    try:
        with op.open(r, timeout=60) as f:
            t = f.read().decode()
            return json.loads(t) if t else None
    except urllib.error.HTTPError as e:
        return {"httpError": e.code, "body": e.read().decode()[:300]}


def ask(body):
    r = call("POST", f"/agents/{agent}/commands", body)
    for _ in range(60):
        st = call("GET", f"/agents/{agent}/commands/{r['id']}")
        if st.get("state") in ("done", "failed", "expired"):
            return st
        time.sleep(1)


try:
    op.open(gw + "/session")
except Exception:
    pass
login = call("POST", "/login", {"email": "sam@example.com", "password": open(pw).read().strip()})
call("POST", "/session/organization", {"orgId": next(o for o in login["organizations"] if o["slug"] == "northwind-water")["id"]})

s7 = {"host": "s7-sim", "cpu": "S7_300", "rack": 0, "slot": 2}
eip = {"host": "eip-sim", "backplane": 1, "slot": 0}
s7tags = [{"name": "temperature", "address": "DB1.DBD0", "unit": "degC"}, {"name": "counter", "address": "DB1.DBW4"},
          {"name": "running", "address": "DB1.DBX6.0"}, {"name": "total", "address": "DB1.DBD8:DINT"},
          {"name": "setpoint", "address": "MW10"}, {"name": "alarm", "address": "M12.0"}]
eiptags = [{"name": "speed", "address": "Line1_Speed", "unit": "m/min"}, {"name": "count", "address": "Count"},
           {"name": "running", "address": "Running"}, {"name": "level3", "address": "Levels[3]"}]
print("test s7:", json.dumps(ask({"kind": "read", "source": {"kind": "s7", "settings": s7}, "tags": s7tags}).get("result"))[:500])
print("test eip:", json.dumps(ask({"kind": "read", "source": {"kind": "eip", "settings": eip}, "tags": eiptags + [{"name": "x", "address": "Nope"}]}).get("result"))[:500])
print("test unreachable:", json.dumps(ask({"kind": "test", "source": {"kind": "s7", "settings": {"host": "10.255.255.1", "cpu": "S7_1500"}}}))[:300])
print("bad address:", call("POST", f"/agents/{agent}/sources", {"name": "S7-BAD", "kind": "s7", "settings": s7,
                                                                "tags": [{"name": "x", "address": "MW10.1"}]}))
have = {s["name"] for s in call("GET", f"/agents/{agent}").get("sources", [])}
for name, kind, st, tags in [("S7-LINE2", "s7", s7, s7tags), ("LOGIX-LINE1", "eip", eip, eiptags)]:
    if name not in have:
        r = call("POST", f"/agents/{agent}/sources", {"name": name, "kind": kind, "pollMs": 1000, "enabled": True, "settings": st, "tags": tags})
        print("add", name, "->", r.get("httpError") or "ok", r.get("body", ""))
time.sleep(25)
for v in call("GET", f"/agents/{agent}/live")["values"]:
    if v["machine"] in ("S7-LINE2", "LOGIX-LINE1"):
        print("live:", v["machine"], v["tag"], "=", v["value"], v["unit"], v["quality"], v["detail"] or "", v["timestamp"])
