package com.bimba.setu.collect;

import com.bimba.setu.config.TagModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Siemens S7-300 / 400 / 1200 / 1500 and LOGO! over S7comm (ISO-on-TCP, port 102), our own implementation: TPKT +
 * COTP connect, S7 setup communication (PDU size), Read Var with as many items per request as the PDU allows.
 * Every socket operation has a timeout.
 * <p>
 * Settings: host, port (102), rack (0), slot (S7-300: 2, else 1), cpu S7_300 | S7_400 | S7_1200 | S7_1500 | LOGO,
 * timeoutMs (3000). On S7-1200 / 1500 the PLC must allow PUT/GET communication and the data blocks read must not use
 * optimized block access. LOGO!: local TSAP 03.00, remote 02.00 (as set up in LOGO!Soft Comfort).
 * <p>
 * Addresses, Siemens notation (German E / A for inputs / outputs work too), optional ":TYPE":
 * DB1.DBD0 (REAL), DB1.DBW4 (INT), DB1.DBB6 (BYTE), DB1.DBX8.0 (BOOL), MW10, MD20, MB5, M0.1, IW64, I0.0, QW0, Q0.1;
 * types BOOL BYTE SINT USINT CHAR WORD UINT INT DWORD UDINT DINT REAL LREAL LINT ULINT STRING[n] (e.g. DB2.DBB0:STRING[20]).
 */
public final class S7Driver implements Driver {
    private static final Pattern DB = Pattern.compile("^DB(\\d{1,5})\\.DB([XBWD])(\\d{1,7})(?:\\.([0-7]))?$");
    private static final Pattern AREA = Pattern.compile("^([IEQAM])([BWD]?)(\\d{1,7})(?:\\.([0-7]))?$");
    private static final Pattern STRING = Pattern.compile("^STRING\\[(\\d{1,3})]$");
    static final int AREA_I = 0x81, AREA_Q = 0x82, AREA_M = 0x83, AREA_DB = 0x84;

    /** one S7 address: area, data block, start byte, bit, type and the bytes to read */
    record Item(int area, int db, int start, int bit, String type, int bytes) {
        boolean isBit() {
            return "BOOL".equals(type);
        }
    }

    private final String host;
    private final int port;
    private final int localTsap;
    private final int remoteTsap;
    private final int timeoutMs;
    private final String cpu;
    private final int rack;
    private final int slot;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int pdu = 240;
    private int ref = 1;

    public S7Driver(JsonObject s) {
        host = str(s, "host");
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("S7 source without host");
        }
        cpu = str(s, "cpu") == null ? "S7_1500" : str(s, "cpu").toUpperCase(Locale.ROOT);
        port = num(s, "port", 102);
        rack = num(s, "rack", 0);
        slot = num(s, "slot", "S7_300".equals(cpu) ? 2 : 1);
        timeoutMs = num(s, "timeoutMs", 3000);
        if ("LOGO".equals(cpu)) {
            localTsap = 0x0300;
            remoteTsap = 0x0200;
        } else {
            localTsap = 0x0100;                                   // PG
            remoteTsap = 0x0100 | (rack << 5) | slot;              // PG connection to rack / slot
        }
    }

    // ------------------------------------------------------------------ addresses

    /** our address -> item; throws IllegalArgumentException with a readable message */
    public static Item parse(String raw) {
        String a = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        String type = null;
        int colon = a.indexOf(':');
        if (colon > 0) {
            type = a.substring(colon + 1);
            a = a.substring(0, colon);
        }
        Matcher m = DB.matcher(a);
        int area, db = 0, start, bit = 0;
        String size;
        boolean hasBit;
        if (m.matches()) {
            area = AREA_DB;
            db = Integer.parseInt(m.group(1));
            size = m.group(2);
            start = Integer.parseInt(m.group(3));
            hasBit = m.group(4) != null;
            bit = hasBit ? Integer.parseInt(m.group(4)) : 0;
            if ("X".equals(size) != hasBit && !"X".equals(size)) {
                throw new IllegalArgumentException("S7 address \"" + raw + "\": a bit address uses DBX (DB1.DBX8.0)");
            }
        } else if ((m = AREA.matcher(a)).matches()) {
            area = switch (m.group(1)) {
                case "I", "E" -> AREA_I;
                case "Q", "A" -> AREA_Q;
                default -> AREA_M;
            };
            hasBit = m.group(4) != null;
            if (hasBit && !m.group(2).isEmpty()) {
                throw new IllegalArgumentException("S7 address \"" + raw + "\": a bit address has no size letter (M0.1, I0.0)");
            }
            size = hasBit ? "X" : m.group(2).isEmpty() ? "B" : m.group(2);
            start = Integer.parseInt(m.group(3));
            bit = hasBit ? Integer.parseInt(m.group(4)) : 0;
        } else {
            throw new IllegalArgumentException("S7 address \"" + raw + "\": use DB1.DBD0, DB1.DBW4, DB1.DBX8.0, MW10, M0.1, IW64, I0.0, QW0 or Q0.1");
        }
        if (type == null) {
            type = switch (size) {
                case "X" -> "BOOL";
                case "B" -> "BYTE";
                case "W" -> "INT";
                default -> "REAL";
            };
        }
        int bytes;
        Matcher sm = STRING.matcher(type);
        if (sm.matches()) {
            int n = Integer.parseInt(sm.group(1));
            if (n < 1 || n > 254) {
                throw new IllegalArgumentException("S7 address \"" + raw + "\": STRING[1..254]");
            }
            bytes = n + 2;
            type = "STRING";
        } else {
            bytes = switch (type) {
                case "BOOL", "BYTE", "SINT", "USINT", "CHAR" -> 1;
                case "WORD", "UINT", "INT" -> 2;
                case "DWORD", "UDINT", "DINT", "REAL" -> 4;
                case "LREAL", "LINT", "ULINT" -> 8;
                default -> throw new IllegalArgumentException("S7 address \"" + raw + "\": unknown type " + type
                        + " (BOOL, BYTE, INT, WORD, DINT, DWORD, REAL, LREAL, STRING[n], ...)");
            };
        }
        if ("BOOL".equals(type) != "X".equals(size)) {
            throw new IllegalArgumentException("S7 address \"" + raw + "\": BOOL needs a bit address (DB1.DBX8.0, M0.1) and a bit address needs BOOL");
        }
        return new Item(area, db, start, bit, type, bytes);
    }

    // ------------------------------------------------------------------ Driver

    @Override
    public synchronized List<Reading> read(List<TagModel.Tag> tags) throws Exception {
        List<Item> items = new ArrayList<>(tags.size());
        List<Reading> out = new ArrayList<>(tags.size());
        for (TagModel.Tag t : tags) {
            try {
                items.add(parse(t.address()));
            } catch (IllegalArgumentException e) {
                items.add(null);
            }
            out.add(null);
        }
        connect();
        try {
            // as many items per Read Var as fit the PDU: request 12 bytes each, answer 4 + data (+ pad) each, at most 20
            List<Integer> batch = new ArrayList<>();
            int reqSize = 14, resSize = 14;
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                if (it == null) {
                    out.set(i, Reading.bad("invalid S7 address"));
                    continue;
                }
                if (it.bytes() + 4 + 14 > pdu) {
                    out.set(i, Reading.bad("too long for the PLC's PDU (" + pdu + " bytes)"));
                    continue;
                }
                int add = 4 + it.bytes() + (it.bytes() % 2);
                if (!batch.isEmpty() && (batch.size() == 20 || reqSize + 12 > pdu || resSize + add > pdu)) {
                    readBatch(items, batch, out);
                    batch.clear();
                    reqSize = 14;
                    resSize = 14;
                }
                batch.add(i);
                reqSize += 12;
                resSize += add;
            }
            if (!batch.isEmpty()) {
                readBatch(items, batch, out);
            }
        } catch (IOException e) {
            close();
            throw new Exception("read from " + describe() + " failed: " + why(e));
        }
        return out;
    }

    private void readBatch(List<Item> items, List<Integer> batch, List<Reading> out) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0x04);                                     // Read Var
        p.write(batch.size());
        for (int i : batch) {
            Item it = items.get(i);
            p.write(new byte[]{0x12, 0x0A, 0x10});
            p.write(it.isBit() ? 0x01 : 0x02);             // transport size BIT / BYTE
            u16(p, it.isBit() ? 1 : it.bytes());
            u16(p, it.area() == AREA_DB ? it.db() : 0);
            p.write(it.area());
            int address = it.start() * 8 + it.bit();
            p.write(address >> 16 & 0xFF);
            p.write(address >> 8 & 0xFF);
            p.write(address & 0xFF);
        }
        ByteBuffer r = job(p.toByteArray(), new byte[0]);
        int params = r.get() & 0xFF;
        if (params != 0x04) {
            throw new IOException("unexpected answer to Read Var (function 0x" + Integer.toHexString(params) + ")");
        }
        int count = r.get() & 0xFF;
        for (int k = 0; k < batch.size(); k++) {
            int i = batch.get(k);
            if (k >= count || !r.hasRemaining()) {
                out.set(i, Reading.bad("no value in the PLC's answer"));
                continue;
            }
            int code = r.get() & 0xFF;
            int transport = r.get() & 0xFF;
            int len = r.getShort() & 0xFFFF;
            if (code != 0xFF) {
                out.set(i, Reading.bad(returnCode(code)));
                continue;                                   // a failed item carries no data
            }
            int bytes = transport == 0x03 || transport == 0x04 || transport == 0x05 ? (len + 7) / 8 : len;
            byte[] data = new byte[bytes];
            r.get(data);
            if (bytes % 2 == 1 && k < batch.size() - 1 && r.hasRemaining()) {
                r.get();                                    // fill byte between items
            }
            try {
                out.set(i, Reading.ok(decode(items.get(i), data)));
            } catch (RuntimeException e) {
                out.set(i, Reading.bad("cannot decode " + items.get(i).type() + ": " + e.getMessage()));
            }
        }
    }

    static Object decode(Item it, byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d);                   // S7 is big endian
        return switch (it.type()) {
            case "BOOL" -> (d[0] & 1) != 0;
            case "BYTE", "USINT" -> (double) (d[0] & 0xFF);
            case "SINT" -> (double) d[0];
            case "CHAR" -> String.valueOf((char) (d[0] & 0xFF));
            case "WORD", "UINT" -> (double) (b.getShort() & 0xFFFF);
            case "INT" -> (double) b.getShort();
            case "DWORD", "UDINT" -> (double) (b.getInt() & 0xFFFFFFFFL);
            case "DINT" -> (double) b.getInt();
            case "REAL" -> (double) b.getFloat();
            case "LREAL" -> b.getDouble();
            case "LINT" -> (double) b.getLong();
            case "ULINT" -> Double.parseDouble(Long.toUnsignedString(b.getLong()));
            case "STRING" -> {
                int actual = Math.min(d[1] & 0xFF, d.length - 2);
                yield new String(d, 2, actual, StandardCharsets.ISO_8859_1);
            }
            default -> throw new IllegalArgumentException("type " + it.type());
        };
    }

    static String returnCode(int code) {
        return switch (code) {
            case 0x01 -> "hardware fault in the PLC";
            case 0x03 -> "access denied by the PLC (allow PUT/GET, no optimized block access)";
            case 0x05 -> "address out of range (data block shorter than the address)";
            case 0x06 -> "data type not supported";
            case 0x07 -> "data type inconsistent";
            case 0x0A -> "object does not exist (data block missing, or optimized block access on)";
            default -> String.format("the PLC refused the item (code 0x%02X)", code);
        };
    }

    // ------------------------------------------------------------------ connection

    private void connect() throws Exception {
        if (socket != null) {
            return;
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.setTcpNoDelay(true);
            socket = s;
            in = new DataInputStream(s.getInputStream());
            out = s.getOutputStream();
            // COTP connection request: TPDU size 1024, calling / called TSAP
            send(new byte[]{0x11, (byte) 0xE0, 0, 0, 0, 1, 0, (byte) 0xC0, 1, 0x0A,
                    (byte) 0xC1, 2, (byte) (localTsap >> 8), (byte) localTsap, (byte) 0xC2, 2, (byte) (remoteTsap >> 8), (byte) remoteTsap});
            byte[] cc = receive();
            if (cc.length < 2 || (cc[1] & 0xF0) != 0xD0) {
                throw new IOException("the PLC refused the connection (check rack " + rack + " / slot " + slot + ")");
            }
            // setup communication: one job at a time each way, PDU 480 (the PLC may answer with less)
            ByteBuffer r = job(new byte[]{(byte) 0xF0, 0, 0, 1, 0, 1, 0x01, (byte) 0xE0}, new byte[0]);
            r.get();
            r.get();
            r.getShort();
            r.getShort();
            pdu = r.getShort() & 0xFFFF;
            if (pdu < 64) {
                pdu = 240;
            }
        } catch (Exception e) {
            close();
            String why = e instanceof java.net.SocketTimeoutException ? "timed out"
                    : e instanceof java.net.ConnectException ? "connection refused" : e.getMessage();
            throw new Exception("cannot connect to " + describe() + ": " + why);
        }
    }

    /** an S7 job (parameter + data) and its ack-data answer, positioned at the answer's parameter */
    private ByteBuffer job(byte[] param, byte[] data) throws IOException {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.write(new byte[]{0x02, (byte) 0xF0, (byte) 0x80});           // COTP data, last unit
        m.write(0x32);                                                // S7 protocol id
        m.write(0x01);                                                // job
        u16(m, 0);
        int myRef = ref++ & 0xFFFF;
        u16(m, myRef);
        u16(m, param.length);
        u16(m, data.length);
        m.write(param);
        m.write(data);
        send(m.toByteArray());
        while (true) {
            byte[] a = receive();
            ByteBuffer r = ByteBuffer.wrap(a);
            int cotpLen = r.get() & 0xFF;
            r.position(1 + cotpLen);
            if ((r.get() & 0xFF) != 0x32) {
                throw new IOException("not an S7 answer");
            }
            int rosctr = r.get() & 0xFF;
            r.getShort();
            int answerRef = r.getShort() & 0xFFFF;
            int plen = r.getShort() & 0xFFFF;
            r.getShort();
            if (rosctr == 0x02 || rosctr == 0x03) {
                int errClass = r.get() & 0xFF, errCode = r.get() & 0xFF;
                if (errClass != 0 || errCode != 0) {
                    throw new IOException(String.format("the PLC answered error class 0x%02X code 0x%02X%s", errClass, errCode,
                            errClass == 0x81 && errCode == 0x04 ? " (function not supported: allow PUT/GET on S7-1200 / 1500)" : ""));
                }
            }
            if (answerRef != myRef) {
                continue;                                             // a late answer to an older job
            }
            if (plen == 0) {
                throw new IOException("the PLC answered without parameters");
            }
            return r;
        }
    }

    private void send(byte[] payload) throws IOException {
        int len = payload.length + 4;
        byte[] t = new byte[len];
        t[0] = 0x03;
        t[2] = (byte) (len >> 8);
        t[3] = (byte) len;
        System.arraycopy(payload, 0, t, 4, payload.length);
        out.write(t);
        out.flush();
    }

    private byte[] receive() throws IOException {
        int version = in.readUnsignedByte();
        in.readUnsignedByte();
        int len = in.readUnsignedShort();
        if (version != 3 || len < 7 || len > 8192) {
            throw new IOException("not an ISO-on-TCP (TPKT) answer");
        }
        byte[] b = new byte[len - 4];
        try {
            in.readFully(b);
        } catch (EOFException e) {
            throw new IOException("the PLC closed the connection");
        }
        return b;
    }

    private static void u16(ByteArrayOutputStream o, int v) {
        o.write(v >> 8 & 0xFF);
        o.write(v & 0xFF);
    }

    @Override
    public String describe() {
        return "LOGO".equals(cpu) ? "LOGO! " + host : cpu.replace('_', '-') + " " + host + " rack " + rack + " slot " + slot;
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // gone
            }
        }
        socket = null;
        in = null;
        out = null;
    }

    // ------------------------------------------------------------------ console requests

    /** Test connection: connects and reads the given tags once: {ok, target, pdu, values:[...]} */
    public static Map<String, Object> test(JsonObject settings, List<TagModel.Tag> tags) throws Exception {
        try (S7Driver d = new S7Driver(settings)) {
            List<Reading> r = tags.isEmpty() ? List.of() : d.read(tags);
            if (tags.isEmpty()) {
                d.connect();
            }
            Map<String, Object> m = Plc.result(d.describe(), tags, r);
            m.put("pdu", d.pdu);
            return m;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** a readable reason for a lost connection (EOF and timeouts have no message) */
    static String why(IOException e) {
        if (e instanceof EOFException) {
            return "the device closed the connection";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "no answer within the timeout";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() && !e.getAsString().isBlank() ? e.getAsString().trim() : null;
    }

    static int num(JsonObject o, String k, int d) {
        try {
            JsonElement e = o.get(k);
            return e != null && e.isJsonPrimitive() && !e.getAsString().isBlank() ? e.getAsInt() : d;
        } catch (RuntimeException e) {
            return d;
        }
    }

    /** shared by the PLC drivers' Test connection */
    static final class Plc {
        private Plc() {
        }

        static Map<String, Object> result(String target, List<TagModel.Tag> tags, List<Reading> r) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < tags.size(); i++) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("name", tags.get(i).name());
                v.put("address", tags.get(i).address());
                v.put("value", r.get(i).value());
                v.put("good", r.get(i).good());
                v.put("quality", r.get(i).quality().name());
                v.put("detail", r.get(i).detail());
                out.add(v);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("target", target);
            m.put("values", out);
            return m;
        }
    }
}
