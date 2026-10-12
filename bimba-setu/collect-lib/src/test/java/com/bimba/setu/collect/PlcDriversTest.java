package com.bimba.setu.collect;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S7 and Logix addresses and value decoding (the protocols themselves are tested against the simulators in agent/test-linux). */
class PlcDriversTest {
    @Test
    void s7Addresses() {
        assertEquals(new S7Driver.Item(S7Driver.AREA_DB, 1, 0, 0, "REAL", 4), S7Driver.parse("DB1.DBD0"));
        assertEquals(new S7Driver.Item(S7Driver.AREA_DB, 1, 4, 0, "INT", 2), S7Driver.parse("db1.dbw4"));
        assertEquals(new S7Driver.Item(S7Driver.AREA_DB, 1, 8, 3, "BOOL", 1), S7Driver.parse("DB1.DBX8.3"));
        assertEquals(new S7Driver.Item(S7Driver.AREA_DB, 2, 8, 0, "DINT", 4), S7Driver.parse("DB2.DBD8:DINT"));
        assertEquals(new S7Driver.Item(S7Driver.AREA_M, 0, 10, 0, "INT", 2), S7Driver.parse("MW10"));
        assertEquals(new S7Driver.Item(S7Driver.AREA_M, 0, 0, 1, "BOOL", 1), S7Driver.parse("M0.1"));
        assertEquals(new S7Driver.Item(S7Driver.AREA_I, 0, 0, 0, "BOOL", 1), S7Driver.parse("E0.0"), "German input");
        assertEquals(new S7Driver.Item(S7Driver.AREA_Q, 0, 64, 0, "WORD", 2), S7Driver.parse("AW64:WORD"), "German output");
        assertEquals(new S7Driver.Item(S7Driver.AREA_DB, 3, 0, 0, "STRING", 22), S7Driver.parse("DB3.DBB0:STRING[20]"));
        assertThrows(IllegalArgumentException.class, () -> S7Driver.parse("MW10.1"));
        assertThrows(IllegalArgumentException.class, () -> S7Driver.parse("DB1.DBX8.0:INT"));
        assertThrows(IllegalArgumentException.class, () -> S7Driver.parse("DB1.DBW2:FLOAT"));
        assertThrows(IllegalArgumentException.class, () -> S7Driver.parse("X100"));
    }

    @Test
    void s7Values() {
        assertEquals(21.5, S7Driver.decode(S7Driver.parse("DB1.DBD0"), ByteBuffer.allocate(4).putFloat(21.5f).array()));
        assertEquals(-2.0, S7Driver.decode(S7Driver.parse("MW0"), new byte[]{(byte) 0xFF, (byte) 0xFE}));
        assertEquals(65534.0, S7Driver.decode(S7Driver.parse("MW0:WORD"), new byte[]{(byte) 0xFF, (byte) 0xFE}));
        assertEquals(true, S7Driver.decode(S7Driver.parse("M0.0"), new byte[]{1}));
        assertEquals("ABC", S7Driver.decode(S7Driver.parse("DB1.DBB0:STRING[10]"), new byte[]{10, 3, 'A', 'B', 'C', 0, 0, 0, 0, 0, 0, 0}));
        assertTrue(S7Driver.returnCode(0x0A).contains("does not exist"));
    }

    @Test
    void logixPaths() {
        assertArrayEquals(new byte[]{(byte) 0x91, 5, 'S', 'p', 'e', 'e', 'd', 0}, LogixDriver.path("Speed"), "odd names are padded");
        assertArrayEquals(new byte[]{(byte) 0x91, 6, 'L', 'e', 'v', 'e', 'l', 's', 0x28, 3}, LogixDriver.path("Levels[3]"));
        assertArrayEquals(new byte[]{(byte) 0x91, 2, 'M', '1', (byte) 0x91, 3, 'R', 'u', 'n', 0}, LogixDriver.path("M1.Run"));
        assertArrayEquals(new byte[]{(byte) 0x91, 2, 'A', 'b', 0x29, 0, 0x2C, 0x01}, LogixDriver.path("Ab[300]"));
        byte[] program = LogixDriver.path("Program:Main.Count");
        assertEquals(0x91, program[0] & 0xFF);
        assertEquals("Program:Main".length(), program[1]);
        assertThrows(IllegalArgumentException.class, () -> LogixDriver.path("1bad"));
        assertThrows(IllegalArgumentException.class, () -> LogixDriver.path("A..B"));
        assertThrows(IllegalArgumentException.class, () -> LogixDriver.path("A.Program:X"));
    }

    @Test
    void logixValues() {
        ByteBuffer real = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0xCA).putFloat(12.5f).flip();
        assertEquals(12.5, LogixDriver.decode(real));
        ByteBuffer dint = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0xC4).putInt(-7).flip();
        assertEquals(-7.0, LogixDriver.decode(dint));
        ByteBuffer bool = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0xC1).put((byte) 0xFF).flip();
        assertEquals(true, LogixDriver.decode(bool));
        ByteBuffer str = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0x02A0).putShort((short) 0x0FCE)
                .putInt(4).put("Line".getBytes()).flip();
        assertEquals("Line", LogixDriver.decode(str));
        assertTrue(LogixDriver.status(0x05).contains("not found"));
    }
}
