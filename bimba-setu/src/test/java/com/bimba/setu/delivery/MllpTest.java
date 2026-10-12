package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-END-03: HL7 v2 from readings, MLLP framing, ACK / NACK handling */
class MllpTest {

    static JsonObject env() {
        return Json.GSON.fromJson("""
                {"schemaVersion":"1.3","batchId":"b-1","plantId":"P1","edgeId":"gw","createdAt":"2026-10-09T08:00:00Z","count":3,"messages":[
                 {"messageId":"m1","plantId":"P1","machineId":"PUMP-1","tagName":"Flow","tagValue":42.7,"dataType":"number","unit":"m3/h","quality":"GOOD","timestamp":"2026-10-09T08:00:00.250Z"},
                 {"messageId":"m2","plantId":"P1","machineId":"PUMP-1","tagName":"Mode","tagValue":"a|b^c","dataType":"string","unit":"","quality":"UNCERTAIN","timestamp":"2026-10-09T08:00:01Z"},
                 {"messageId":"m3","plantId":"P1","machineId":"TANK-2","tagName":"Level","tagValue":null,"dataType":"null","unit":"%","quality":"BAD","timestamp":"2026-10-09T08:00:02Z"}]}""",
                JsonObject.class);
    }

    @Test
    void defaultTemplateMakesOneOruPerMachine() {
        List<Hl7.Message> ms = Hl7.compile(null).render(env(), "machine");
        assertEquals(2, ms.size(), "PUMP-1 and TANK-2");
        String[] segs = ms.get(0).text().split("\r");
        assertTrue(segs[0].startsWith("MSH|^~\\&|BIMBA|P1|RECEIVER|FACILITY|20261009080000.000+0000||ORU^R01^ORU_R01|" + ms.get(0).controlId() + "|P|2.5"),
                segs[0]);
        assertEquals("OBR|1||" + ms.get(0).controlId() + "|PUMP-1^PUMP-1^BIMBA|||20261009080000.250+0000", segs[1]);
        assertEquals("OBX|1|NM|Flow^Flow^BIMBA||42.7|m3/h|||||F|||20261009080000.250+0000||||PUMP-1", segs[2]);
        assertEquals("OBX|2|ST|Mode^Mode^BIMBA||a\\F\\b\\S\\c||||||R|||20261009080001.000+0000||||PUMP-1", segs[3], "values are escaped");
        assertTrue(ms.get(1).text().contains("OBX|1|ST|Level^Level^BIMBA|||%|||||X|"), "BAD: no value, status X");
        assertEquals(20, ms.get(0).controlId().length());
        assertEquals(ms.get(0).controlId(), Hl7.compile(null).render(env(), "machine").get(0).controlId(), "the same after a retry");
        assertNotEquals(ms.get(0).controlId(), ms.get(1).controlId());
        assertEquals(3, Hl7.compile(null).render(env(), "reading").size());
    }

    @Test
    void templateMistakesAreExplained() {
        assertThrows(IllegalArgumentException.class, () -> Hl7.compile("PID|1"), "must start with MSH");
        assertThrows(IllegalArgumentException.class, () -> Hl7.compile("MSH|^~\\&|X\nPID|1"), "needs an OBX");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Hl7.compile("MSH|^~\\&|{nope}\nOBX|1|{hl7.value}"));
        assertTrue(e.getMessage().contains("{nope}"));
        Hl7 custom = Hl7.compile("MSH|^~\\&|A\nOBX|{hl7.setId}|{tagName}\nNTE|1||{unit}\nZZZ|end");
        String text = custom.render(env(), "machine").get(0).text();
        assertEquals("MSH|^~\\&|A\rOBX|1|Flow\rNTE|1||m3/h\rOBX|2|Mode\rNTE|1||\rZZZ|end\r", text, "OBX + NTE repeat, the rest once");
    }

    /** a receiver that answers each framed message with what the function makes of it */
    private static ServerSocket receiver(List<String> got, Function<String, String> answer) throws Exception {
        ServerSocket ss = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    while (true) {
                        String msg = MllpDestination.read(in);
                        got.add(msg);
                        out.write(0x0b);
                        out.write(answer.apply(msg).getBytes(StandardCharsets.UTF_8));
                        out.write(new byte[]{0x1c, 0x0d});
                        out.flush();
                    }
                } catch (Exception e) {
                    // connection closed: wait for the next
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    private static String controlId(String msg) {
        return msg.split("\r")[0].split("\\|")[9];
    }

    private static MllpDestination dest(int port) {
        BimbaConfig.Destination c = new BimbaConfig.Destination();
        c.name = "hl7";
        c.transport = "mllp";
        c.url = "mllp://127.0.0.1:" + port;
        c.timeoutMs = 3000;
        return new MllpDestination(c);
    }

    @Test
    void acknowledgedMessagesAreDeliveredAndNacksAreSortedOut() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        String[] code = {"AA"};
        try (ServerSocket ss = receiver(got, m -> "MSH|^~\\&|ENGINE|H|BIMBA|P1|20261009||ACK^R01|A1|P|2.5\rMSA|" + code[0] + "|" + controlId(m)
                + "|" + ("AE".equals(code[0]) ? "OBX-5 not numeric" : "") + "\r");
             MllpDestination d = dest(ss.getLocalPort())) {
            byte[] batch = Json.GSON.toJson(env()).getBytes(StandardCharsets.UTF_8);
            d.send(batch);
            assertEquals(2, got.size(), "one message per machine, one connection");
            assertTrue(got.get(0).startsWith("MSH|"));

            code[0] = "AE";
            Destination.RejectedException e = assertThrows(Destination.RejectedException.class, () -> d.send(batch));
            assertTrue(e.getMessage().contains("OBX-5 not numeric"), e.getMessage());

            code[0] = "AR";
            assertThrows(java.io.IOException.class, () -> d.send(batch), "AR: tried again later");
            code[0] = "CA";
            d.send(batch);                                            // reconnects after the failure
        }
    }

    @Test
    void ackChecks() {
        assertThrows(java.io.IOException.class, () -> MllpDestination.check("MSH|^~\\&|X\rMSA|AA|OTHER\r", "MINE"), "for another message");
        assertThrows(java.io.IOException.class, () -> MllpDestination.check("garbage", "MINE"), "no MSA");
        assertThrows(java.io.IOException.class, () -> MllpDestination.check("MSA|ZZ|MINE", "MINE"));
    }

    @Test
    void framesAreReadBetweenVtAndFsCr() throws Exception {
        byte[] wire = "x\u000bMSH|a\rMSA|AA|1\r\u001c\r".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("MSH|a\rMSA|AA|1\r", MllpDestination.read(new ByteArrayInputStream(wire)));
        assertThrows(java.io.IOException.class, () -> MllpDestination.read(new ByteArrayInputStream("\u000bMSH".getBytes(StandardCharsets.ISO_8859_1))));
    }
}
