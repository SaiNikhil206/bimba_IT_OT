"""Writable OPC UA server for the machine command tests (the meter sim's nodes are read only).

Endpoint: opc.tcp://<host>:4841/write/   namespace urn:bimba:write-test (ns=2), all writable by clients:
  ns=2;s=Line.Setpoint   Double   bar
  ns=2;s=Line.Speed      Int16    rpm
  ns=2;s=Line.Run        Boolean
  ns=2;s=Line.Mode       String
  ns=2;s=Line.Limited    Double   the "machine" caps it at 50 (a write above reads back as 50)

  docker run -d --name opcua-write-sim --network bimba_default -v <this folder>:/t bimba-opcua-sim python -u /t/opcua_write_sim.py
"""
import asyncio
import logging

from asyncua import Server, ua

logging.basicConfig(level=logging.INFO, format="%(asctime)s opcua-write-sim %(message)s")
logging.getLogger("asyncua").setLevel(logging.WARNING)
log = logging.getLogger("opcua-write-sim")


async def main():
    server = Server()
    await server.init()
    server.set_endpoint("opc.tcp://0.0.0.0:4841/write/")
    server.set_server_name("BIMBA write test")
    idx = await server.register_namespace("urn:bimba:write-test")
    line = await server.nodes.objects.add_object(ua.NodeId("Line", idx), "Line")
    nodes = {}
    for name, value, vtype in [("Setpoint", 4.0, ua.VariantType.Double), ("Speed", 1200, ua.VariantType.Int16),
                               ("Run", False, ua.VariantType.Boolean), ("Mode", "AUTO", ua.VariantType.String),
                               ("Limited", 10.0, ua.VariantType.Double)]:
        v = await line.add_variable(ua.NodeId(f"Line.{name}", idx), name, ua.Variant(value, vtype))
        await v.set_writable()
        nodes[name] = v
    async with server:
        log.info("listening on opc.tcp://0.0.0.0:4841/write/ (ns=%d)", idx)
        last = None
        while True:
            limited = await nodes["Limited"].read_value()
            if limited > 50:
                await nodes["Limited"].write_value(50.0)
            state = [await nodes[n].read_value() for n in ("Setpoint", "Speed", "Run", "Mode", "Limited")]
            if state != last:
                log.info("Setpoint=%s Speed=%s Run=%s Mode=%s Limited=%s", *state)
                last = state
            await asyncio.sleep(0.05)


if __name__ == "__main__":
    asyncio.run(main())
