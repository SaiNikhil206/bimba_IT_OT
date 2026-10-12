"""TEST ONLY: DNP3, IEC 104 and SQL sources through the Setu console API, against dnp3-sim, iec104-sim and the SQL
test databases (sql_seed.sh). Local test Setu server only (its self-signed certificate is not verified).

  python utility_check.py <password-file> [agent hostname]

Finds the organization's online agent (1.7+), runs Test connection / Browse like the console, checks that bad settings
are refused, adds four sources and shows their live values.
"""
import http.cookiejar
import json
import ssl
import sys
import time
import urllib.request

pw = sys.argv[1]
host = sys.argv[2] if len(sys.argv) > 2 else None
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
    if "id" not in r:
        return r
    for _ in range(90):
        st = call("GET", f"/agents/{agent}/commands/{r['id']}")
        if st.get("state") in ("done", "failed", "expired"):
            return st
        time.sleep(1)


def show(label, st):
    res = st.get("result") or {}
    print(f"{label}: {st.get('state')} {st.get('error') or ''}".rstrip())
    for k in ("target", "points", "rows", "columns", "note"):
        if k in res:
            print(f"    {k}: {res[k]}")
    for v in res.get("values", []):
        print(f"    {v['name']} {v['address']} = {v['value']} {v['quality']} {v.get('detail') or ''}".rstrip())
    for i in res.get("items", [])[:8]:
        print(f"    item {i.get('nodeId')} | {i.get('name')} | {i.get('value', '')}")


try:
    op.open(gw + "/session")
except Exception:
    pass
login = call("POST", "/login", {"email": "sam@example.com", "password": open(pw).read().strip()})
call("POST", "/session/organization", {"orgId": next(o for o in login["organizations"] if o["slug"] == "northwind-water")["id"]})
agents = call("GET", "/agents")
agents = agents.get("agents", agents) if isinstance(agents, dict) else agents
online = [a for a in agents if a.get("status") == "online" and (host is None or a.get("hostname") == host)]
for a in online:
    print("agent:", a.get("hostname"), a.get("agentVersion"), a.get("status"), a["id"])
agent = next(a["id"] for a in online if str(a.get("agentVersion", "")).startswith("1.7"))

dnp3 = {"host": "dnp3-sim", "port": 20000, "outstation": 10, "master": 1}
iec = {"host": "iec104-sim", "port": 2404, "commonAddress": 1, "giMinutes": 15}
mssql = {"engine": "sqlserver", "host": "sql-mssql", "database": "Runtime", "username": "bimba_reader", "password": "TestOnly_read1",
         "tls": "trust", "layout": "rows",
         "query": "SELECT TagName AS tag, Value AS value, DateTime AS time, OPCQuality AS quality FROM dbo.Live WHERE TagName IN ({tags})"}
pg = {"engine": "postgresql", "host": "sql-pg", "database": "postgres", "username": "bimba_reader", "password": "TestOnly_read1",
      "layout": "columns", "query": "SELECT * FROM line_data ORDER BY time DESC LIMIT 1"}
dnp3tags = [{"name": "level", "address": "AI0", "unit": "%", "rawLow": 0, "rawHigh": 1000, "scaledLow": 0, "scaledHigh": 100},
            {"name": "breaker", "address": "BI0"}, {"name": "pulses", "address": "CT0"}, {"name": "setpoint", "address": "AO0"}]
iectags = [{"name": "temperature", "address": "4001", "unit": "degC"}, {"name": "flow", "address": "4003", "rawLow": -1, "rawHigh": 1,
            "scaledLow": -200, "scaledHigh": 200, "unit": "m3/h"}, {"name": "alarm", "address": "100"},
           {"name": "energy", "address": "300", "unit": "kWh"}, {"name": "station2_temp", "address": "2/4001", "unit": "degC"}]
mssqltags = [{"name": "speed", "address": "Line1.Speed"}, {"name": "temp", "address": "Line1.Temp", "unit": "degC"},
             {"name": "pressure", "address": "Line1.Pressure", "unit": "bar"}]
pgtags = [{"name": "speed", "address": "speed"}, {"name": "running", "address": "running"}, {"name": "batch", "address": "batch"}]

show("test dnp3", ask({"kind": "test", "source": {"kind": "dnp3", "settings": dnp3}, "tags": dnp3tags}))
show("browse dnp3", ask({"kind": "browse", "source": {"kind": "dnp3", "settings": dnp3}, "limit": 1000}))
show("browse dnp3 AI", ask({"kind": "browse", "source": {"kind": "dnp3", "settings": dnp3}, "nodeId": "AI", "limit": 1000}))
show("test iec104", ask({"kind": "test", "source": {"kind": "iec104", "settings": iec}, "tags": iectags}))
show("browse iec104 IT", ask({"kind": "browse", "source": {"kind": "iec104", "settings": iec}, "nodeId": "IT", "limit": 1000}))
show("test mssql", ask({"kind": "test", "source": {"kind": "sql", "settings": mssql}, "tags": mssqltags}))
show("browse pg", ask({"kind": "browse", "source": {"kind": "sql", "settings": pg}, "limit": 1000}))

for label, body in [
    ("bad DNP3 address", {"name": "X-BAD", "kind": "dnp3", "settings": dnp3, "tags": [{"name": "x", "address": "AX5"}]}),
    ("master = outstation", {"name": "X-BAD", "kind": "dnp3", "settings": {**dnp3, "master": 10}, "tags": dnp3tags}),
    ("bad IOA", {"name": "X-BAD", "kind": "iec104", "settings": iec, "tags": [{"name": "x", "address": "99999999"}]}),
    ("bad time zone", {"name": "X-BAD", "kind": "iec104", "settings": {**iec, "timeZone": "Mars/Base"}, "tags": iectags}),
    ("writing query", {"name": "X-BAD", "kind": "sql", "settings": {**mssql, "query": "DELETE FROM dbo.Live"}, "tags": mssqltags}),
    ("host injection", {"name": "X-BAD", "kind": "sql", "settings": {**mssql, "host": "db;integratedSecurity=true"}, "tags": mssqltags}),
    ("half scaling", {"name": "X-BAD", "kind": "dnp3", "settings": dnp3, "tags": [{"name": "x", "address": "AI1", "rawLow": 0}]}),
]:
    r = call("POST", f"/agents/{agent}/sources", body)
    print(f"refused ({label}):", r.get("httpError"), json.loads(r.get("body", "{}")).get("error") if r.get("httpError") else "NOT REFUSED")
    if not r.get("httpError"):
        call("DELETE", f"/agents/{agent}/sources/{r['id']}")

names = ("DNP3-SUB1", "IEC104-SUB2", "HIST-MSSQL", "HIST-PG")
have = {s["name"] for s in call("GET", f"/agents/{agent}").get("sources", [])}
for name, kind, st, tags, poll in [("DNP3-SUB1", "dnp3", dnp3, dnp3tags, 1000), ("IEC104-SUB2", "iec104", iec, iectags, 1000),
                                   ("HIST-MSSQL", "sql", mssql, mssqltags, 5000), ("HIST-PG", "sql", pg, pgtags, 5000)]:
    if name not in have:
        r = call("POST", f"/agents/{agent}/sources", {"name": name, "kind": kind, "pollMs": poll, "enabled": True, "settings": st, "tags": tags})
        print("add", name, "->", r.get("httpError") or "ok", r.get("body", ""))
time.sleep(30)
for s in call("GET", f"/agents/{agent}").get("sources", []):
    if s["name"] in names:
        print("source:", s["name"], "password stored:", s.get("hasPassword"), "settings password:", s.get("settings", {}).get("password"))
for d in (call("GET", f"/agents/{agent}").get("collector") or {}).get("devices", []):
    if any(m in names for m in d.get("machines", [])):
        print("device:", d.get("kind"), d.get("target"), "connected" if d.get("connected") else "NOT connected", d.get("goodTags"), "/", d.get("tags"), d.get("error") or "")
for v in call("GET", f"/agents/{agent}/live")["values"]:
    if v["machine"] in names:
        print("live:", v["machine"], v["tag"], "=", v["value"], v["unit"] or "", v["quality"], v["detail"] or "", v["timestamp"])
