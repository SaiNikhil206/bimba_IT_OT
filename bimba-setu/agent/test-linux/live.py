"""TEST ONLY: prints an agent's live values whose machine starts with a prefix; waits until one has the wanted quality.

  python live.py <password-file> <agentId> <machine prefix> [wanted quality] [max seconds]
"""
import http.cookiejar
import json
import ssl
import sys
import time
import urllib.request

pw, agent, prefix = sys.argv[1:4]
wanted = sys.argv[4] if len(sys.argv) > 4 else None
deadline = time.time() + (int(sys.argv[5]) if len(sys.argv) > 5 else 0)
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
    with op.open(r) as f:
        t = f.read().decode()
        return json.loads(t) if t else None


try:
    op.open(gw + "/session")
except Exception:
    pass
login = call("POST", "/login", {"email": "sam@example.com", "password": open(pw).read().strip()})
call("POST", "/session/organization", {"orgId": next(o for o in login["organizations"] if o["slug"] == "northwind-water")["id"]})
while True:
    rows = [v for v in call("GET", f"/agents/{agent}/live")["values"] if v["machine"].startswith(prefix)]
    if not wanted or any(v["quality"] == wanted for v in rows) or time.time() > deadline:
        break
    time.sleep(3)
for v in rows:
    print(v["machine"], v["tag"], "=", v["value"], v["quality"], v["detail"] or "", v["timestamp"])
