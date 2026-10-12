"""TEST ONLY: Siemens S7 simulator for the PLC driver tests (python-snap7's server, an open-source S7 stack).

  docker run -d --name s7-sim --network bimba_default -v <this folder>:/sim python:3.12-slim \
      sh -c "pip install -q python-snap7 && python -u /sim/s7_sim.py"

DB1 (big endian, like a real S7):  DBD0 REAL temperature   DBW4 INT counter   DBX6.0 BOOL running   DBD8 DINT total
Merker:  MW10 INT setpoint (1234)  M12.0 BOOL alarm
"""
import math
import struct
import time

import snap7

try:                                   # python-snap7 2.x
    from snap7.type import SrvArea
    AREA_DB, AREA_MK = SrvArea.DB, SrvArea.MK
except ImportError:                    # 1.x
    AREA_DB, AREA_MK = snap7.types.srvAreaDB, snap7.types.srvAreaMK

# bytearray: python-snap7 3.x serves it in place (a ctypes array is copied at registration)
db1 = bytearray(64)
mk = bytearray(64)
server = snap7.server.Server()
server.register_area(AREA_DB, 1, db1)
server.register_area(AREA_MK, 0, mk)
try:
    server.start(tcp_port=102)         # python-snap7 2.x / 3.x
except TypeError:
    server.start(tcpport=102)          # 1.x
print("S7 simulator on port 102: DB1 + M", flush=True)


def put(area, offset, fmt, value):
    for i, b in enumerate(struct.pack(fmt, value)):
        area[offset + i] = b


start = time.time()
put(mk, 10, ">h", 1234)
while True:
    s = time.time() - start
    put(db1, 0, ">f", 20 + 5 * math.sin(s / 10))
    put(db1, 4, ">h", int(s) % 32768)
    db1[6] = (db1[6] & 0xFE) | (1 if int(s / 5) % 2 == 0 else 0)
    put(db1, 8, ">i", int(s * 100))
    mk[12] = (mk[12] & 0xFE) | (1 if int(s / 7) % 2 else 0)
    time.sleep(0.5)
