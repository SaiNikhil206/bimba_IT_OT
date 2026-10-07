"""TEST-ONLY stand-in for the Boomi Event Streams *produce* REST API.

Implements the documented contract so the plugin's requests can be verified
without a Boomi tenant. It does NOT store data or trigger any Boomi process.

  POST /topics/<topic>        Authorization: Bearer <MOCK_TOKEN>
       {"messages":[{"payload":"<string>","properties":{...}}]}
  ->   200 {"status":"success","messageIds":"<ledger>:<entry>:0,..."}
  GET  /_received             last 50 requests (topic, message count, properties, payload preview)
  POST /_mode/<ok|fail|slow>  simulate an outage (503) or a timeout
"""
import asyncio
import itertools
import json
import os
import time
from collections import deque

from aiohttp import web

TOKEN = os.environ.get("MOCK_TOKEN", "test-env-token")
MAX_MSG = 5 * 1024 * 1024
MAX_REQ = 10 * 1024 * 1024
received = deque(maxlen=50)
queues = {}  # topic -> deque of {"payload","properties","messageId"} (all subscriptions share it)
ids = itertools.count(1)
state = {"mode": "ok"}


async def produce(request):
    if request.headers.get("Authorization") != f"Bearer {TOKEN}":
        return web.json_response({"status": "error", "message": "invalid environment token"}, status=401)
    if state["mode"] == "fail":
        return web.json_response({"status": "error", "message": "service unavailable (simulated)"}, status=503)
    if state["mode"] == "slow":
        await asyncio.sleep(30)
    raw = await request.read()
    if len(raw) > MAX_REQ:
        return web.json_response({"status": "error", "message": "request too large"}, status=413)
    try:
        body = json.loads(raw)
        messages = body["messages"]
        assert isinstance(messages, list) and messages
        for m in messages:
            assert isinstance(m["payload"], str)
            assert len(m["payload"].encode()) <= MAX_MSG
            assert isinstance(m.get("properties", {}), dict)
    except (ValueError, KeyError, AssertionError, TypeError):
        return web.json_response({"status": "error", "message": "invalid message format"}, status=400)
    msg_ids = [f"744:{next(ids)}:0" for _ in messages]
    first = json.loads(messages[0]["payload"])
    received.appendleft({
        "at": time.strftime("%H:%M:%S"),
        "topic": request.match_info["topic"],
        "esMessages": len(messages),
        "properties": messages[0].get("properties"),
        "payloadKind": "batch" if "messages" in first else "message",
        "canonicalMessages": len(first.get("messages", [])) if "messages" in first else len(messages),
        "bytes": len(raw),
    })
    return web.json_response({"status": "success", "messageIds": ",".join(msg_ids)})


async def produce_single(request):
    """Single-message API: body = payload in its original format, properties = x-msg-props-* headers."""
    if request.headers.get("Authorization") != f"Bearer {TOKEN}":
        return web.json_response({"status": "error", "message": "invalid environment token"}, status=401)
    if state["mode"] == "fail":
        return web.json_response({"status": "error", "message": "service unavailable (simulated)"}, status=503)
    raw = await request.read()
    if not raw:
        return web.json_response({"status": "error", "message": "empty message"}, status=400)
    if len(raw) > MAX_MSG:
        return web.json_response({"status": "error", "message": "message too large"}, status=413)
    props = {k[len("x-msg-props-"):]: v for k, v in request.headers.items() if k.lower().startswith("x-msg-props-")}
    try:
        first = json.loads(raw)
    except ValueError:
        first = {}
    received.appendleft({
        "at": time.strftime("%H:%M:%S"), "api": "singlemsg",
        "path": f"{request.match_info['account']}/{request.match_info['env']}/{request.match_info['topic']}",
        "contentType": request.headers.get("Content-Type"), "properties": props,
        "payloadKind": "batch" if "messages" in first else "message",
        "canonicalMessages": len(first.get("messages", [])) if "messages" in first else 1, "bytes": len(raw),
    })
    mid = f"744:{next(ids)}:0"
    queues.setdefault(request.match_info["topic"], deque(maxlen=100000)).append(
        {"messageId": mid, "payload": raw.decode("utf-8"), "properties": props})
    return web.json_response({"status": "success", "messageIds": mid})


async def consume_batch(request):
    """Batch consume: JSON array of messages, payload as plain string; auto-acknowledged on delivery."""
    if request.headers.get("Authorization") != f"Bearer {TOKEN}":
        return web.json_response({"status": "error", "message": "invalid environment token"}, status=401)
    q = queues.setdefault(request.match_info["topic"], deque(maxlen=100000))
    n = min(int(request.query.get("max_messages", 1)), 100)
    deadline = time.time() + int(request.query.get("timeout_ms", 0)) / 1000
    while not q and time.time() < deadline:
        await asyncio.sleep(0.2)
    return web.json_response([q.popleft() for _ in range(min(n, len(q)))])


async def listing(_):
    return web.json_response(list(received))


async def mode(request):
    state["mode"] = request.match_info["mode"]
    return web.json_response(state)


app = web.Application(client_max_size=MAX_REQ + 1024)
app.router.add_post("/topics/{topic}", produce)
app.router.add_post("/rest/singlemsg/{account}/{env}/{topic}", produce_single)
app.router.add_get("/rest/consume/batch/{account}/{env}/{topic}/{subscription}", consume_batch)
app.router.add_get("/_received", listing)
app.router.add_post("/_mode/{mode}", mode)

if __name__ == "__main__":
    web.run_app(app, port=8095, access_log=None)
