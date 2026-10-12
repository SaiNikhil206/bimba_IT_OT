package com.bimba.setu.collect;

import com.bimba.setu.collect.Canonical.Quality;
import com.bimba.setu.config.TagModel;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DNP3 and IEC 60870-5-104: codecs, and each driver against a small fake outstation in this test (the protocols are
 * also tested against independent outstation implementations in agent/test-linux).
 */
class UtilityDriversTest {

    // ================================================================== DNP3

    @Test
    void dnp3Addresses() {
        assertEquals(new Dnp3Driver.Point(Dnp3Driver.Type.AI, 5), Dnp3Driver.parse("AI5"));
        assertEquals(new Dnp3Driver.Point(Dnp3Driver.Type.DBI, 2), Dnp3Driver.parse(" dbi 2 "));
        assertEquals(new Dnp3Driver.Point(Dnp3Driver.Type.FC, 65535), Dnp3Driver.parse("FC65535"));
        assertThrows(IllegalArgumentException.class, () -> Dnp3Driver.parse("AI"));
        assertThrows(IllegalArgumentException.class, () -> Dnp3Driver.parse("XX1"));
        assertThrows(IllegalArgumentException.class, () -> Dnp3Driver.parse("AI70000"));
    }

    @Test
    void dnp3Crc() {
        // reset link states, master 1024 -> outstation 1 (the common example frame)
        byte[] h = {0x05, 0x64, 0x05, (byte) 0xC0, 0x01, 0x00, 0x00, 0x04};
        assertEquals(0x21E9, Dnp3Driver.crc(h, 0, 8));
        byte[] f = Dnp3Driver.frame(0xC4, 10, 1, new byte[20]);
        assertEquals(10 + 16 + 2 + 4 + 2, f.length, "header + a full block + a 4-byte block, each with CRC");
    }

    @Test
    void dnp3Objects() {
        ByteBuffer b = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{30, 1, 0x01}).putShort((short) 0).putShort((short) 1);           // g30v1, start/stop 0..1
        b.put((byte) 0x01).putInt(1234);
        b.put((byte) 0x21).putInt(-5);                                                       // online + over range
        b.put(new byte[]{1, 2, 0x00, 12, 13, (byte) 0x81, 0x01});                             // g1v2 12..13
        b.put(new byte[]{1, 1, 0x00, 0, 9, 0b0000_0101, 0b0000_0010});                         // g1v1 packed 0..9
        b.put(new byte[]{3, 2, 0x00, 0, 2, (byte) 0x81, 0x41, 0x01});                          // g3v2 0..2
        b.put(new byte[]{20, 1, 0x00, 0, 0, 0x01}).putInt(-1);                                // g20v1: 4294967295
        b.put(new byte[]{40, 3, 0x28}).putShort((short) 1).putShort((short) 7).put((byte) 0x01).putFloat(2.5f); // g40v3 indexed
        b.put(new byte[]{30, 2, 0x00, 6, 6, 0x00, 0x10, 0x00});                                // offline
        b.put(new byte[]{110, 4, 0x00, 1, 1, 'L', 'I', 'N', 'E'});                           // octet string
        b.put(new byte[]{121, 1, 0x00, 0, 0, 1, 2, 3});                                       // unknown: stops here
        b.put(new byte[]{30, 5, 0x00, 9, 9, 0x01, 0, 0, 0, 0});
        b.flip();
        Map<Dnp3Driver.Point, Driver.Reading> out = new HashMap<>();
        String problem = Dnp3Driver.objects(b, out);
        assertEquals("g121v1, which the agent cannot decode", problem);
        assertEquals(1234.0, out.get(Dnp3Driver.parse("AI0")).value());
        assertEquals(Quality.UNCERTAIN, out.get(Dnp3Driver.parse("AI1")).quality());
        assertEquals("over range", out.get(Dnp3Driver.parse("AI1")).detail());
        assertEquals(true, out.get(Dnp3Driver.parse("BI12")).value());
        assertEquals(false, out.get(Dnp3Driver.parse("BI13")).value());
        assertEquals(true, out.get(Dnp3Driver.parse("BI0")).value());
        assertEquals(false, out.get(Dnp3Driver.parse("BI1")).value());
        assertEquals(true, out.get(Dnp3Driver.parse("BI9")).value());
        assertEquals(true, out.get(Dnp3Driver.parse("DBI0")).value(), "state 2 = ON");
        assertEquals(false, out.get(Dnp3Driver.parse("DBI1")).value(), "state 1 = OFF");
        assertEquals(Quality.BAD, out.get(Dnp3Driver.parse("DBI2")).quality(), "state 0 = in transition");
        assertEquals(4294967295.0, out.get(Dnp3Driver.parse("CT0")).value());
        assertEquals(2.5, out.get(Dnp3Driver.parse("AO7")).value());
        assertEquals(Quality.BAD, out.get(Dnp3Driver.parse("AI6")).quality());
        assertTrue(out.get(Dnp3Driver.parse("AI6")).detail().contains("offline"));
        assertEquals("LINE", out.get(Dnp3Driver.parse("OS1")).value());
        assertNull(out.get(Dnp3Driver.parse("AI9")), "after the object that cannot be decoded");
    }

    @Test
    void dnp3AgainstFakeOutstation() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<String> seen = new CopyOnWriteArrayList<>();
            Thread t = new Thread(() -> fakeOutstation(server, seen));
            t.setDaemon(true);
            t.start();
            JsonObject s = new JsonObject();
            s.addProperty("host", "127.0.0.1");
            s.addProperty("port", server.getLocalPort());
            s.addProperty("timeoutMs", 3000);
            try (Dnp3Driver d = new Dnp3Driver(s)) {
                List<Driver.Reading> r = d.read(List.of(tag(TagModel.Kind.DNP3, "AI0"), tag(TagModel.Kind.DNP3, "AI1"),
                        tag(TagModel.Kind.DNP3, "BI2"), tag(TagModel.Kind.DNP3, "AI40")));
                assertEquals(21.5, r.get(0).value());
                assertEquals(-3.0, r.get(1).value());
                assertEquals(true, r.get(2).value(), "from the second fragment");
                assertEquals(Quality.BAD, r.get(3).quality());
                assertTrue(r.get(3).detail().contains("not in the outstation's static data"));
                List<Driver.Reading> again = d.read(List.of(tag(TagModel.Kind.DNP3, "AI0")));
                assertEquals(21.5, again.get(0).value(), "same connection, next poll");
            }
            assertTrue(seen.contains("link status"), "answered the outstation's link status request: " + seen);
            assertTrue(seen.contains("confirm unsolicited"), "confirmed the unsolicited response: " + seen);
            assertTrue(seen.contains("confirm fragment"), "confirmed the first fragment: " + seen);
        }
    }

    /** outstation 10: answers Class 0 polls in two fragments, after a link status request and an unsolicited response */
    private static void fakeOutstation(ServerSocket server, List<String> seen) {
        try (Socket c = server.accept()) {
            DataInputStream in = new DataInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();
            int tp = 0;
            for (int poll = 0; poll < 2; poll++) {
                byte[] req = userData(in, seen);
                assertEquals(0x01, req[1], "READ");
                int seq = req[0] & 0x0F;
                if (poll == 0) {
                    out.write(Dnp3Driver.frame(0x49, 1, 10, new byte[0]));                        // request link status
                    out.write(segment(tp++, new byte[]{(byte) 0xF0, (byte) 0x82, 0, 0}));            // unsolicited, CON
                    byte[] ack = rawFrame(in);
                    if ((ack[3] & 0x0F) == 0x0B) {
                        seen.add("link status");
                    }
                    byte[] uc = userData(in, seen);
                    if (uc[1] == 0 && (uc[0] & 0x10) != 0) {
                        seen.add("confirm unsolicited");
                    }
                }
                ByteBuffer f1 = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
                f1.put(new byte[]{(byte) (0xA0 | seq), (byte) 0x81, 0, 0});                       // FIR, CON
                f1.put(new byte[]{30, 5, 0x00, 0, 1}).put((byte) 1).putFloat(21.5f).put((byte) 1).putFloat(-3f);
                out.write(segment(tp++, java.util.Arrays.copyOf(f1.array(), f1.position())));
                byte[] conf = userData(in, seen);
                if (conf[1] == 0 && (conf[0] & 0x0F) == seq) {
                    seen.add("confirm fragment");
                }
                byte[] f2 = {(byte) (0x40 | (seq + 1 & 0x0F)), (byte) 0x81, 0, 0, 1, 2, 0x00, 2, 2, (byte) 0x81};   // FIN: g1v2 BI2
                out.write(segment(tp++, f2));
                out.flush();
            }
            Thread.sleep(200);
        } catch (Exception e) {
            seen.add("outstation failed: " + e);
        }
    }

    private static byte[] segment(int tp, byte[] apdu) {
        byte[] d = new byte[apdu.length + 1];
        d[0] = (byte) (0xC0 | tp & 0x3F);
        System.arraycopy(apdu, 0, d, 1, apdu.length);
        return Dnp3Driver.frame(0x44, 1, 10, d);
    }

    /** the application fragment of the next user data frame from the master */
    private static byte[] userData(DataInputStream in, List<String> seen) throws IOException {
        while (true) {
            byte[] f = rawFrame(in);
            if ((f[3] & 0x0F) == 4) {
                return java.util.Arrays.copyOfRange(f, 11, f.length);                            // skip header + transport
            }
            seen.add("frame " + Integer.toHexString(f[3] & 0xFF));
        }
    }

    /** header (10) + data without the block CRCs */
    private static byte[] rawFrame(DataInputStream in) throws IOException {
        byte[] h = new byte[10];
        in.readFully(h);
        assertEquals(0x0564, (h[0] & 0xFF) << 8 | h[1] & 0xFF);
        assertEquals(Dnp3Driver.crc(h, 0, 8), (h[8] & 0xFF) | (h[9] & 0xFF) << 8, "master's header CRC");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(h, 0, 10);
        int n = (h[2] & 0xFF) - 5;
        for (int pos = 0; pos < n; pos += 16) {
            int k = Math.min(16, n - pos);
            byte[] blk = new byte[k + 2];
            in.readFully(blk);
            assertEquals(Dnp3Driver.crc(blk, 0, k), (blk[k] & 0xFF) | (blk[k + 1] & 0xFF) << 8, "master's block CRC");
            o.write(blk, 0, k);
        }
        return o.toByteArray();
    }

    // ================================================================== IEC 104

    @Test
    void iec104Addresses() {
        assertEquals(new Iec104Driver.Point(1, 4001), Iec104Driver.parse("4001", 1));
        assertEquals(new Iec104Driver.Point(2, 4001), Iec104Driver.parse("2/4001", 1));
        assertEquals(new Iec104Driver.Point(7, 16777215), Iec104Driver.parse("16777215", 7));
        assertThrows(IllegalArgumentException.class, () -> Iec104Driver.parse("16777216", 1));
        assertThrows(IllegalArgumentException.class, () -> Iec104Driver.parse("0/5", 1));
        assertThrows(IllegalArgumentException.class, () -> Iec104Driver.parse("x", 1));
    }

    @Test
    void iec104Values() {
        ZoneId utc = ZoneOffset.UTC;
        ByteBuffer f = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putFloat(21.5f).put((byte) 0);
        assertEquals(21.5, Iec104Driver.decode(13, f.array(), utc).value());
        ByteBuffer n = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN).putShort((short) -16384).put((byte) 0);
        assertEquals(-0.5, Iec104Driver.decode(9, n.array(), utc).value());
        assertEquals(Quality.UNCERTAIN, Iec104Driver.decode(11, new byte[]{5, 0, 0x01}, utc).quality(), "overflow");
        assertEquals(Quality.BAD, Iec104Driver.decode(11, new byte[]{5, 0, (byte) 0x80}, utc).quality(), "invalid");
        assertEquals(true, Iec104Driver.decode(1, new byte[]{1}, utc).value());
        assertEquals(Quality.UNCERTAIN, Iec104Driver.decode(1, new byte[]{0x41}, utc).quality(), "not topical");
        assertEquals(false, Iec104Driver.decode(3, new byte[]{1}, utc).value(), "DPI 1 = OFF");
        assertEquals(true, Iec104Driver.decode(3, new byte[]{2}, utc).value(), "DPI 2 = ON");
        assertEquals(Quality.BAD, Iec104Driver.decode(3, new byte[]{3}, utc).quality());
        assertEquals(-3.0, Iec104Driver.decode(5, new byte[]{0x7D, 0}, utc).value(), "step position, 7-bit signed");
        ByteBuffer it = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putInt(1000).put((byte) 0x20);
        assertEquals(Quality.UNCERTAIN, Iec104Driver.decode(15, it.array(), utc).quality(), "carry");
        // M_SP_TB_1: ON at 2026-10-10 08:15:30.250 (CP56Time2a)
        byte[] sp = {1, (byte) (30250 & 0xFF), (byte) (30250 >> 8), 15, 8, 10, 10, 26};
        Driver.Reading r = Iec104Driver.decode(30, sp, utc);
        assertEquals(true, r.value());
        assertEquals(Instant.parse("2026-10-10T08:15:30.250Z"), r.sourceTime());
        assertEquals(Instant.parse("2026-10-10T06:15:30.250Z"), Iec104Driver.decode(30, sp, ZoneId.of("Europe/Berlin")).sourceTime());
        sp[3] |= (byte) 0x80;
        assertNull(Iec104Driver.decode(30, sp, utc).sourceTime(), "time marked invalid");
        assertEquals(-1, Iec104Driver.elementSize(45), "commands are not read");
    }

    @Test
    void iec104AgainstFakeOutstation() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<String> seen = new CopyOnWriteArrayList<>();
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            Thread t = new Thread(() -> fake104(server, seen, go));
            t.setDaemon(true);
            t.start();
            JsonObject s = new JsonObject();
            s.addProperty("host", "127.0.0.1");
            s.addProperty("port", server.getLocalPort());
            s.addProperty("commonAddress", 1);
            s.addProperty("timeoutMs", 3000);
            try (Iec104Driver d = new Iec104Driver(s)) {
                List<TagModel.Tag> tags = List.of(tag(TagModel.Kind.IEC104, "4001"), tag(TagModel.Kind.IEC104, "101"),
                        tag(TagModel.Kind.IEC104, "102"), tag(TagModel.Kind.IEC104, "999"), tag(TagModel.Kind.IEC104, "300"));
                List<Driver.Reading> r = d.read(tags);
                assertEquals(21.5, r.get(0).value(), "from the general interrogation");
                assertEquals(false, r.get(1).value(), "SQ=1: the second element is IOA 101");
                assertEquals(true, r.get(2).value());
                assertEquals(Quality.BAD, r.get(3).quality());
                assertEquals(77.0, r.get(4).value(), "integrated total from the counter interrogation");
                go.countDown();                                                                  // send a spontaneous change
                Driver.Reading later = null;
                for (int i = 0; i < 50; i++) {
                    later = d.read(tags).get(0);
                    if (Double.valueOf(22.25).equals(later.value())) {
                        break;
                    }
                    Thread.sleep(50);
                }
                assertNotNull(later);
                assertEquals(22.25, later.value(), "spontaneous update");
            }
            assertTrue(seen.contains("STARTDT"), seen.toString());
            assertTrue(seen.contains("GI 1"), seen.toString());
            assertTrue(seen.contains("CI"), seen.toString());
        }
    }

    private static void fake104(ServerSocket server, List<String> seen, java.util.concurrent.CountDownLatch go) {
        try (Socket c = server.accept()) {
            DataInputStream in = new DataInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();
            int[] ns = {0};
            byte[] a = apdu(in);
            if ((a[0] & 0xFF) == 0x07) {
                seen.add("STARTDT");
                out.write(new byte[]{0x68, 4, 0x0B, 0, 0, 0});
            }
            a = apdu(in);
            if ((a[0] & 1) == 0 && a[4] == 100) {
                int ca = (a[8] & 0xFF) | (a[9] & 0xFF) << 8;
                seen.add("GI " + ca);
                iframe(out, ns, new byte[]{100, 1, 7, 0, 1, 0, 0, 0, 0, 20});                    // actcon
                ByteBuffer me = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
                me.put(new byte[]{13, 1, 20, 0, 1, 0}).put(new byte[]{(byte) 0xA1, 0x0F, 0}).putFloat(21.5f).put((byte) 0);
                iframe(out, ns, me.array());                                                     // IOA 4001 = 21.5
                iframe(out, ns, new byte[]{1, (byte) 0x83, 20, 0, 1, 0, 100, 0, 0, 1, 0, 1});    // SQ: 100..102 = 1, 0, 1
                iframe(out, ns, new byte[]{100, 1, 10, 0, 1, 0, 0, 0, 0, 20});                   // actterm
            }
            a = apdu(in);
            if ((a[0] & 1) == 0 && a[4] == 101 && a[13] == 5) {                                  // counters, no freeze
                seen.add("CI");
                iframe(out, ns, new byte[]{101, 1, 7, 0, 1, 0, 0, 0, 0, 5});
                iframe(out, ns, new byte[]{15, 1, 37, 0, 1, 0, 44, 1, 0, 77, 0, 0, 0, 3});        // IOA 300 = 77
                iframe(out, ns, new byte[]{101, 1, 10, 0, 1, 0, 0, 0, 0, 5});
            }
            out.flush();
            go.await(5, java.util.concurrent.TimeUnit.SECONDS);
            ByteBuffer sp = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
            sp.put(new byte[]{13, 1, 3, 0, 1, 0}).put(new byte[]{(byte) 0xA1, 0x0F, 0}).putFloat(22.25f).put((byte) 0);
            iframe(out, ns, sp.array());
            out.flush();
            c.setSoTimeout(3000);
            try {
                while (true) {
                    apdu(in);                                                                    // S-frames until closed
                }
            } catch (IOException closed) {
                // the driver closed the connection
            }
        } catch (Exception e) {
            seen.add("outstation failed: " + e);
        }
    }

    private static byte[] apdu(DataInputStream in) throws IOException {
        int start = in.readUnsignedByte();
        assertEquals(0x68, start);
        byte[] a = new byte[in.readUnsignedByte()];
        in.readFully(a);
        return a;
    }

    private static void iframe(OutputStream out, int[] ns, byte[] asdu) throws IOException {
        byte[] f = new byte[6 + asdu.length];
        f[0] = 0x68;
        f[1] = (byte) (4 + asdu.length);
        f[2] = (byte) (ns[0] << 1);
        f[3] = (byte) (ns[0] >> 7);
        System.arraycopy(asdu, 0, f, 6, asdu.length);
        ns[0]++;
        out.write(f);
    }

    // ================================================================== helpers

    static TagModel.Tag tag(TagModel.Kind kind, String address) {
        return new TagModel.Tag("M", address.toLowerCase(), "M/" + address, kind, "dev", address, null, null, 0, 0, 0, 0, false,
                null, null, null);
    }
}
