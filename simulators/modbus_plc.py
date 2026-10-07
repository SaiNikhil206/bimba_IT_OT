"""Modbus SCADA sim: a virtual PLC for a water pumping station, exposed over Modbus TCP.

Process: pump P1 fills a tank, consumer demand drains it. The PLC runs the pump
between 25 % and 85 % level. Every few minutes the motor overheats and the pump
trips for 60 s, the level falls and alarms fire downstream.

Register map (unit id 1, 0-based addresses):
  Holding registers (FC3), unsigned 16-bit, scaled integers
    HR0  tank level          % x100
    HR1  inlet flow          m3/h x10
    HR2  outlet flow         m3/h x10
    HR3  discharge pressure  bar x100
    HR4  motor temperature   degC x10
    HR5  motor current       A x10
  Coils (FC1)
    C0   pump P1 running
    C1   pump P1 tripped
"""
import asyncio
import logging
import math
import random
import time

from pymodbus.datastore import (
    ModbusSequentialDataBlock,
    ModbusServerContext,
    ModbusSlaveContext,
)
from pymodbus.server import StartAsyncTcpServer

logging.basicConfig(level=logging.INFO, format="%(asctime)s plc-sim %(message)s")
log = logging.getLogger()

PORT = 5020
FC_COILS, FC_HOLDING = 1, 3


class PumpStation:
    def __init__(self):
        self.level = 50.0
        self.running = True
        self.tripped = False
        self.trip_until = 0.0
        self.overheat = False
        self.temp = 40.0
        self.inflow = self.outflow = self.pressure = self.current = 0.0

    def step(self, dt: float):
        now = time.time()
        self.outflow = max(0.0, 80 + 20 * math.sin(2 * math.pi * now / 600) + random.gauss(0, 2))

        if self.tripped and now > self.trip_until:
            self.tripped = False
            log.info("pump P1 trip reset")

        if self.running and not self.overheat and random.random() < 0.003 * dt:
            self.overheat = True
            log.info("motor starting to overheat")

        target = 92 if self.overheat else (68 if self.running else 28)
        self.temp += (target - self.temp) * dt / 40 + random.gauss(0, 0.15)

        if self.running and self.temp > 88:
            self.running, self.tripped, self.trip_until = False, True, now + 60
            log.info("pump P1 TRIPPED on motor over-temperature")

        if not self.tripped:  # PLC level control with hysteresis
            if self.level < 25:
                self.running = True
            elif self.level > 85:
                self.running = False
        if not self.running:
            self.overheat = False

        self.inflow = max(0.0, 120 + random.gauss(0, 3)) if self.running else 0.0
        self.level = min(100.0, max(0.0, self.level + (self.inflow - self.outflow) * 0.0083 * dt))
        self.pressure = max(0.0, (4.5 + random.gauss(0, 0.08)) if self.running else 0.3 + random.gauss(0, 0.02))
        self.current = max(0.0, 42 + (self.temp - 68) * 0.15 + random.gauss(0, 0.5)) if self.running else 0.0

    def holding_registers(self):
        return [
            int(self.level * 100),
            int(self.inflow * 10),
            int(self.outflow * 10),
            int(self.pressure * 100),
            int(self.temp * 10),
            int(self.current * 10),
        ]

    def coils(self):
        return [self.running, self.tripped]


async def simulate(context: ModbusServerContext, plant: PumpStation):
    slave = context[0]
    while True:
        plant.step(1.0)
        slave.setValues(FC_HOLDING, 0, plant.holding_registers())
        slave.setValues(FC_COILS, 0, plant.coils())
        await asyncio.sleep(1.0)


async def main():
    store = ModbusSlaveContext(
        co=ModbusSequentialDataBlock(0, [0] * 32),
        di=ModbusSequentialDataBlock(0, [0] * 32),
        hr=ModbusSequentialDataBlock(0, [0] * 32),
        ir=ModbusSequentialDataBlock(0, [0] * 32),
    )
    context = ModbusServerContext(slaves=store, single=True)
    asyncio.create_task(simulate(context, PumpStation()))
    log.info("Modbus SCADA sim listening on 0.0.0.0:%d", PORT)
    await StartAsyncTcpServer(context=context, address=("0.0.0.0", PORT))


if __name__ == "__main__":
    asyncio.run(main())
