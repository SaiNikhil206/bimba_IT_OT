"""TEST ONLY: MQTT sources through the Setu console API, against mqtt-test-broker + MqttSim.java.

  python mqtt_check.py <setu> <email> <password-file> <org-slug> <agentId>
test connection + browse for each message format, add three sources, show live values. Local test Setu server only.
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
BROKER = "mqtt://mqtt-test-broker:1883"


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
        return {"httpError": e.code, "body": e.read().decode()[:300]}


def ask(body):
    r = call("POST", f"/agents/{agent}/commands", body)
    if "id" not in r:
        return r
    for _ in range(60):
        st = call("GET", f"/agents/{agent}/commands/{r['id']}")
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

plain = {"kind": "mqtt", "settings": {"url": BROKER, "payload": "value", "qos": 1}}
js = {"kind": "mqtt", "settings": {"url": BROKER, "payload": "json", "qos": 1}}
spb = {"kind": "mqtt", "settings": {"url": BROKER, "payload": "sparkplug", "qos": 1, "rebirth": True}}
print("test plain:", json.dumps(ask({"kind": "read", "source": plain, "tags": [{"name": "t", "address": "sim/line1/temperature"},
                                                                          {"name": "x", "address": "sim/none"}]}).get("result"))[:400])
print("test json:", json.dumps(ask({"kind": "read", "source": js, "tags": [{"name": "s", "address": "sim/line1/status#motor.speed"}]}).get("result"))[:300])
print("test spb:", json.dumps(ask({"kind": "read", "source": spb, "tags": [{"name": "p", "address": "SimPlant/Line1/Press1#Hydraulic/Pressure"}]}).get("result"))[:300])
print("browse root:", json.dumps(ask({"kind": "browse", "source": plain, "nodeId": None, "limit": 50}).get("result"))[:300])
print("browse sim/line1/:", json.dumps(ask({"kind": "browse", "source": js, "nodeId": "sim/line1/", "limit": 50}).get("result"))[:400])
print("browse json fields:", json.dumps(ask({"kind": "browse", "source": js, "nodeId": "sim/line1/status#", "limit": 50}).get("result"))[:400])
print("browse spb node:", json.dumps(ask({"kind": "browse", "source": spb, "nodeId": "spb:SimPlant/Line1", "limit": 50}).get("result"))[:500])
print("bad address:", call("POST", f"/agents/{agent}/sources", {"name": "MQTT-BAD", "kind": "mqtt", "settings": spb["settings"],
                                                                "tags": [{"name": "x", "address": "SimPlant#x/+"}]}))

d = call("GET", f"/agents/{agent}")
have = {s["name"] for s in d.get("sources", [])}
for name, src, tags in [
    ("MQTT-LINE1", plain, [{"name": "temperature", "address": "sim/line1/temperature", "unit": "degC"},
                           {"name": "running", "address": "sim/line1/running"}]),
    ("MQTT-MOTOR", js, [{"name": "speed", "address": "sim/line1/status#motor.speed", "unit": "rpm"},
                        {"name": "current", "address": "sim/line1/status#motor.current", "unit": "A"},
                        {"name": "mode", "address": "sim/line1/status#mode"}]),
    ("SPB-PRESS1", spb, [{"name": "pressure", "address": "SimPlant/Line1/Press1#Hydraulic/Pressure", "unit": "bar"},
                         {"name": "cycles", "address": "SimPlant/Line1/Press1#Cycles"},
                         {"name": "line_speed", "address": "SimPlant/Line1#Line/Speed"},
                         {"name": "line_mode", "address": "SimPlant/Line1#Line/Mode"}]),
]:
    if name not in have:
        r = call("POST", f"/agents/{agent}/sources", {"name": name, "kind": "mqtt", "pollMs": 1000, "enabled": True,
                                                     "settings": src["settings"], "tags": tags})
        print("add", name, "->", r.get("httpError") or "ok")
time.sleep(25)
for v in call("GET", f"/agents/{agent}/live").get("values", []):
    if v["machine"].startswith(("MQTT-", "SPB-")):
        print("live:", v["machine"], v["tag"], "=", v["value"], v["unit"], v["quality"], v["detail"] or "", v["timestamp"])
