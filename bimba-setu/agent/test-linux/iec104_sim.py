"""TEST ONLY: IEC 60870-5-104 outstation for the driver tests (lib60870 through c104, an independent implementation).

  docker run -d --name iec104-sim --network bimba_default -v <this folder>:/sim python:3.12-slim \
      sh -c "pip install -q c104 && python -u /sim/iec104_sim.py"

Common address 1, port 2404. Objects: 4001 short float (spontaneous, changes every second), 4002 scaled, 4003
normalized (0.5), 4004 short float with time tag, 100 single point (toggles every 5 s), 101 single point on,
200 double point ON, 300 integrated total (counts up). Common address 2: 4001 short float 99.5.
"""
import time

import c104

server = c104.Server(ip="0.0.0.0", port=2404)
st = server.add_station(common_address=1)
temp = st.add_point(io_address=4001, type=c104.Type.M_ME_NC_1)
scaled = st.add_point(io_address=4002, type=c104.Type.M_ME_NB_1)
norm = st.add_point(io_address=4003, type=c104.Type.M_ME_NA_1)
timed = st.add_point(io_address=4004, type=c104.Type.M_ME_TF_1)
sp = st.add_point(io_address=100, type=c104.Type.M_SP_NA_1)
sp_on = st.add_point(io_address=101, type=c104.Type.M_SP_NA_1)
dp = st.add_point(io_address=200, type=c104.Type.M_DP_NA_1)
total = st.add_point(io_address=300, type=c104.Type.M_IT_NA_1)
st2 = server.add_station(common_address=2)
other = st2.add_point(io_address=4001, type=c104.Type.M_ME_NC_1)


def put(point, value):
    try:
        point.value = value
    except Exception as e:  # noqa: BLE001 - shows which c104 value type a point wants
        print("cannot set", point.io_address, repr(value), e, flush=True)


put(scaled, c104.Int16(1234))
put(norm, c104.NormalizedFloat(0.5))
put(timed, 7.25)
put(sp_on, True)
put(dp, c104.Double.ON)
put(other, 99.5)
server.start()
print("IEC 104 outstation on :2404, common addresses 1 and 2", flush=True)

i = 0
while True:
    i += 1
    put(temp, 20.0 + (i % 10) / 4)
    put(sp, i // 5 % 2 == 1)
    put(total, c104.BinaryCounterReading(i)) if hasattr(c104, "BinaryCounterReading") else put(total, i)
    for p in (temp, sp, total):
        try:
            p.transmit(cause=c104.Cot.SPONTANEOUS)
        except Exception as e:  # noqa: BLE001 - no client connected yet
            if "connected" not in str(e).lower():
                print("transmit", p.io_address, e, flush=True)
    time.sleep(1)
