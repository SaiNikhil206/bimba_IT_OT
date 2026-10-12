"""TEST ONLY: DNP3 outstation for the driver tests (opendnp3 through dnp3-python, an independent implementation).

  docker run -d --name dnp3-sim --network bimba_default -v <this folder>:/sim python:3.10-slim \
      sh -c "pip install -q dnp3-python && python -u /sim/dnp3_sim.py"

Outstation address 10, master address 1, port 20000. Points: AI0 counts up (g30v5 float), AI1 = 21.5 (g30v1, so
sent as the integer 21 or 22), AI2 offline, BI0 toggles every 5 s, BI1 on, CT0 counts up, AO0 = 42. Unsolicited
responses are allowed (the agent must confirm them).
"""
import time

from dnp3_python.dnp3station.outstation import MyOutStation
from pydnp3 import opendnp3

out = MyOutStation(outstation_ip="0.0.0.0", port=20000, master_id=1, outstation_id=10)
out.start()
print("DNP3 outstation 10 on :20000 (master 1)", flush=True)

ONLINE = 0x01
i = 0
while True:
    i += 1
    out.apply_update(opendnp3.Analog(float(i), opendnp3.Flags(ONLINE)), 0)
    out.apply_update(opendnp3.Analog(21.5, opendnp3.Flags(ONLINE)), 1)
    out.apply_update(opendnp3.Analog(7.0, opendnp3.Flags(0)), 2)
    out.apply_update(opendnp3.Binary(i // 5 % 2 == 1, opendnp3.Flags(ONLINE)), 0)
    out.apply_update(opendnp3.Binary(True, opendnp3.Flags(ONLINE)), 1)
    try:
        out.apply_update(opendnp3.Counter(i, opendnp3.Flags(ONLINE)), 0)
    except Exception as e:  # noqa: BLE001 - the wrapper's own bookkeeping may not know counters
        if i == 1:
            print("counter:", e, flush=True)
    out.apply_update(opendnp3.AnalogOutputStatus(42.0, opendnp3.Flags(ONLINE)), 0)
    time.sleep(1)
