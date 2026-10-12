#!/bin/sh
# TEST ONLY: Allen-Bradley Logix simulator for the PLC driver tests (cpppo, an open-source EtherNet/IP / CIP stack).
#   docker run -d --name eip-sim --network bimba_default -v <this folder>:/sim python:3.12-slim sh /sim/eip_sim.sh
# Controller tags: Line1_Speed REAL, Count DINT, Running SINT, Levels INT[10]; Line1_Speed and Count change every second.
pip install -q cpppo
python -m cpppo.server.enip --address 0.0.0.0:44818 --print Line1_Speed=REAL Count=DINT Running=SINT 'Levels=INT[10]' &
sleep 5
i=0
while true; do
    i=$((i + 1))
    python -m cpppo.server.enip.client --address 127.0.0.1:44818 "Line1_Speed=(REAL)$((100 + i % 50)).5" "Count=(DINT)$i" \
        "Running=(SINT)$((i / 5 % 2))" "Levels[3]=(INT)$((i % 100))" >/dev/null 2>&1
    sleep 1
done
