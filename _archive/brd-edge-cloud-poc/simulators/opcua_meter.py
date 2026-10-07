"""Virtual substation power meter exposed over OPC UA.

Endpoint: opc.tcp://<host>:4840/scada/
Nodes (namespace urn:scada-poc:substation, ns=2):
  ns=2;s=Meter.Voltage      V   (line-line)
  ns=2;s=Meter.Current      A
  ns=2;s=Meter.PowerKW      kW
  ns=2;s=Meter.EnergyKWh    kWh (accumulating counter)
  ns=2;s=Meter.Frequency    Hz
  ns=2;s=Meter.PowerFactor  -
  ns=2;s=Meter.BreakerClosed bool

Every few minutes a 10 s voltage sag down to ~360 V is injected.
"""
import asyncio
import logging
import math
import random
import time

from asyncua import Server, ua

logging.basicConfig(level=logging.INFO, format="%(asctime)s opcua-sim %(message)s")
logging.getLogger("asyncua").setLevel(logging.WARNING)
log = logging.getLogger("opcua-sim")

ENDPOINT = "opc.tcp://0.0.0.0:4840/scada/"
NS_URI = "urn:scada-poc:substation"


async def main():
    server = Server()
    await server.init()
    server.set_endpoint(ENDPOINT)
    server.set_server_name("SCADA POC Substation Meter")
    idx = await server.register_namespace(NS_URI)

    meter = await server.nodes.objects.add_object(ua.NodeId("Meter", idx), "Meter")

    async def var(name, initial):
        return await meter.add_variable(ua.NodeId(f"Meter.{name}", idx), name, initial)

    v_voltage = await var("Voltage", 400.0)
    v_current = await var("Current", 0.0)
    v_power = await var("PowerKW", 0.0)
    v_energy = await var("EnergyKWh", 12500.0)
    v_freq = await var("Frequency", 50.0)
    v_pf = await var("PowerFactor", 0.92)
    v_breaker = await var("BreakerClosed", True)

    energy = 12500.0
    sag_until = 0.0

    async with server:
        log.info("OPC UA server listening on %s (ns=%d)", ENDPOINT, idx)
        while True:
            now = time.time()
            if now > sag_until and random.random() < 0.004:
                sag_until = now + 10
                log.info("injecting voltage sag")
            sag = now < sag_until

            voltage = (360 if sag else 400) + random.gauss(0, 1.5)
            power = max(0.0, 35 + 10 * math.sin(2 * math.pi * now / 900) + random.gauss(0, 1))
            pf = min(1.0, 0.92 + random.gauss(0, 0.005))
            current = power * 1000 / (math.sqrt(3) * voltage * pf)
            energy += power / 3600.0

            await v_voltage.write_value(round(voltage, 2))
            await v_current.write_value(round(current, 2))
            await v_power.write_value(round(power, 2))
            await v_energy.write_value(round(energy, 3))
            await v_freq.write_value(round(50 + random.gauss(0, 0.02), 3))
            await v_pf.write_value(round(pf, 3))
            await v_breaker.write_value(True)
            await asyncio.sleep(1.0)


if __name__ == "__main__":
    asyncio.run(main())
