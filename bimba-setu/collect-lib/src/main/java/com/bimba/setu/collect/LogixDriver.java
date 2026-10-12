package com.bimba.setu.collect;

import com.bimba.setu.config.TagModel;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allen-Bradley ControlLogix / CompactLogix (and other Logix-family controllers) over EtherNet/IP, our own
 * implementation: RegisterSession, then CIP Read Tag (0x4C) by symbolic name in Multiple Service Packets, sent
 * unconnected (SendRRData) through the Connection Manager's Unconnected Send to the CPU's backplane slot.
 * Every socket operation has a timeout.
 * <p>
 * Settings: host, port (44818), backplane (1; empty / -1 = no routing, for controllers addressed directly),
 * slot (0 = the CPU's slot in the chassis), timeoutMs (3000).
 * Addresses: controller-scoped tag names with UDT members and array elements: Line1_Speed, Motor.Speed, Levels[3],
 * Recipe.Steps[2].Time. Program tags: Program:MainProgram.Count.
 */
public final class LogixDriver implements Driver {
    private static final Pattern SEGMENT = Pattern.compile("(Program:[A-Za-z_][A-Za-z0-9_]*|[A-Za-z_][A-Za-z0-9_]*)((?:\\[\\d+(?:,\\d+)*\\])?)");
    private static final int MAX_REQUEST = 480;                 // bytes of CIP per unconnected request (limit 504)

    private final String host;
    private final int port;
    private final int backplane;
    private final int slot;
    private final int timeoutMs;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int session;
    private boolean multipleService = true;

    public LogixDriver(JsonObject s) {
        host = S7Driver.str(s, "host");
        if (host == null) {
            throw new IllegalArgumentException("EtherNet/IP source without host");
        }
        port = S7Driver.num(s, "port", 44818);
        backplane = S7Driver.num(s, "backplane", 1);
        slot = S7Driver.num(s, "slot", 0);
        timeoutMs = S7Driver.num(s, "timeoutMs", 3000);
    }

    // ------------------------------------------------------------------ addresses

    /** Logix tag name -> CIP request path (ANSI extended symbolic segments + member / element segments) */
    public static byte[] path(String raw) {
        String a = raw == null ? "" : raw.trim();
        if (a.isEmpty() || a.length() > 400) {
            throw new IllegalArgumentException("Logix tag \"" + raw + "\": a tag name such as Line1_Speed, Motor.Speed or Levels[3]");
        }
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        int pos = 0;
        boolean first = true;
        while (pos < a.length()) {
            if (!first) {
                if (a.charAt(pos) != '.') {
                    throw new IllegalArgumentException("Logix tag \"" + raw + "\": unexpected '" + a.charAt(pos) + "'");
                }
                pos++;
            }
            Matcher m = SEGMENT.matcher(a);
            if (!m.find(pos) || m.start() != pos || (!first && m.group(1).startsWith("Program:"))) {
                throw new IllegalArgumentException("Logix tag \"" + raw + "\": a tag name such as Line1_Speed, Motor.Speed or Levels[3]");
            }
            String name = m.group(1);
            if (name.startsWith("Program:") && m.end() < a.length() && a.charAt(m.end()) != '.') {
                throw new IllegalArgumentException("Logix tag \"" + raw + "\": Program:<program>.<tag>");
            }
            byte[] n = name.getBytes(StandardCharsets.US_ASCII);
            p.write(0x91);
            p.write(n.length);
            p.writeBytes(n);
            if (n.length % 2 == 1) {
                p.write(0);
            }
            if (!m.group(2).isEmpty()) {
                for (String idx : m.group(2).replaceAll("[\\[\\]]", "").split(",")) {
                    long i = Long.parseLong(idx);
                    if (i < 256) {
                        p.write(0x28);
                        p.write((int) i);
                    } else if (i < 65536) {
                        p.write(0x29);
                        p.write(0);
                        p.write((int) (i & 0xFF));
                        p.write((int) (i >> 8));
                    } else {
                        p.write(0x2A);
                        p.write(0);
                        for (int k = 0; k < 4; k++) {
                            p.write((int) (i >> (8 * k) & 0xFF));
                        }
                    }
                }
            }
            pos = m.end();
            first = false;
        }
        return p.toByteArray();
    }

    // ------------------------------------------------------------------ Driver

    @Override
    public synchronized List<Reading> read(List<TagModel.Tag> tags) throws Exception {
        List<byte[]> requests = new ArrayList<>(tags.size());
        List<Reading> out = new ArrayList<>(tags.size());
        for (TagModel.Tag t : tags) {
            byte[] req = null;
            try {
                req = readTag(path(t.address()));
            } catch (IllegalArgumentException e) {
                out.add(Reading.bad(e.getMessage()));
                requests.add(null);
                continue;
            }
            requests.add(req);
            out.add(null);
        }
        connect();
        try {
            List<Integer> batch = new ArrayList<>();
            int size = 4;
            for (int i = 0; i < requests.size(); i++) {
                byte[] r = requests.get(i);
                if (r == null) {
                    continue;
                }
                if (!multipleService) {
                    out.set(i, reply(ByteBuffer.wrap(send(r)).order(ByteOrder.LITTLE_ENDIAN)));
                    continue;
                }
                if (!batch.isEmpty() && (batch.size() == 50 || size + 2 + r.length > MAX_REQUEST)) {
                    multiple(requests, batch, out);
                    batch.clear();
                    size = 4;
                }
                batch.add(i);
                size += 2 + r.length;
            }
            if (!batch.isEmpty()) {
                multiple(requests, batch, out);
            }
        } catch (IOException e) {
            close();
            throw new Exception("read from " + describe() + " failed: " + S7Driver.why(e));
        }
        return out;
    }

    private static byte[] readTag(byte[] path) {
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        r.write(0x4C);                                         // Read Tag
        r.write(path.length / 2);
        r.writeBytes(path);
        r.write(1);                                            // one element
        r.write(0);
        return r.toByteArray();
    }

    /** Multiple Service Packet (0x0A) to the Message Router; falls back to single requests when it is not supported */
    private void multiple(List<byte[]> requests, List<Integer> batch, List<Reading> out) throws IOException {
        if (batch.size() == 1) {
            int i = batch.get(0);
            out.set(i, reply(ByteBuffer.wrap(send(requests.get(i))).order(ByteOrder.LITTLE_ENDIAN)));
            return;
        }
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.writeBytes(new byte[]{0x0A, 0x02, 0x20, 0x02, 0x24, 0x01});
        le16(m, batch.size());
        int offset = 2 + 2 * batch.size();
        for (int i : batch) {
            le16(m, offset);
            offset += requests.get(i).length;
        }
        for (int i : batch) {
            m.writeBytes(requests.get(i));
        }
        ByteBuffer r = ByteBuffer.wrap(send(m.toByteArray())).order(ByteOrder.LITTLE_ENDIAN);
        int service = r.get() & 0xFF;
        r.get();
        int status = r.get() & 0xFF;
        int ext = r.get() & 0xFF;
        r.position(r.position() + 2 * ext);
        if (service == (0x0A | 0x80) && status == 0x08) {         // service not supported: one request at a time
            multipleService = false;
            for (int i : batch) {
                out.set(i, reply(ByteBuffer.wrap(send(requests.get(i))).order(ByteOrder.LITTLE_ENDIAN)));
            }
            return;
        }
        if (service != (0x0A | 0x80) || (status != 0 && status != 0x1E)) {
            throw new IOException("the controller refused the request: " + status(status));
        }
        int start = r.position();
        int count = r.getShort() & 0xFFFF;
        int[] offsets = new int[count];
        for (int k = 0; k < count; k++) {
            offsets[k] = r.getShort() & 0xFFFF;
        }
        for (int k = 0; k < batch.size(); k++) {
            if (k >= count) {
                out.set(batch.get(k), Reading.bad("no answer for this tag"));
                continue;
            }
            int end = k + 1 < count ? start + offsets[k + 1] : r.limit();
            ByteBuffer one = r.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            one.position(start + offsets[k]);
            one.limit(end);
            out.set(batch.get(k), reply(one.slice().order(ByteOrder.LITTLE_ENDIAN)));
        }
    }

    /** one Read Tag reply -> reading */
    private static Reading reply(ByteBuffer r) {
        int service = r.get() & 0xFF;
        r.get();
        int status = r.get() & 0xFF;
        int ext = r.get() & 0xFF;
        r.position(r.position() + 2 * ext);
        if (service != (0x4C | 0x80)) {
            return Reading.bad(status != 0 ? status(status) : "unexpected answer (service 0x" + Integer.toHexString(service) + ")");
        }
        if (status != 0 && status != 0x06) {
            return Reading.bad(status(status));
        }
        try {
            return Reading.ok(decode(r));
        } catch (RuntimeException e) {
            return Reading.bad("cannot decode the value: " + e.getMessage());
        }
    }

    static Object decode(ByteBuffer r) {
        int type = r.getShort() & 0xFFFF;
        if (type == 0x02A0) {                                  // structure: Logix STRING (handle, LEN, DATA)
            r.getShort();
            int len = r.getInt();
            int n = Math.max(0, Math.min(len, r.remaining()));
            byte[] b = new byte[n];
            r.get(b);
            return new String(b, StandardCharsets.ISO_8859_1);
        }
        return switch (type) {
            case 0xC1 -> (r.get() & 0xFF) != 0;                // BOOL
            case 0xC2 -> (double) r.get();                     // SINT
            case 0xC3 -> (double) r.getShort();                // INT
            case 0xC4 -> (double) r.getInt();                  // DINT
            case 0xC5 -> (double) r.getLong();                 // LINT
            case 0xC6 -> (double) (r.get() & 0xFF);            // USINT
            case 0xC7 -> (double) (r.getShort() & 0xFFFF);     // UINT
            case 0xC8 -> (double) (r.getInt() & 0xFFFFFFFFL);  // UDINT
            case 0xC9 -> Double.parseDouble(Long.toUnsignedString(r.getLong()));   // ULINT
            case 0xCA -> (double) r.getFloat();                // REAL
            case 0xCB -> r.getDouble();                        // LREAL
            case 0xD1 -> (double) (r.get() & 0xFF);            // BYTE
            case 0xD2 -> (double) (r.getShort() & 0xFFFF);     // WORD
            case 0xD3 -> (double) (r.getInt() & 0xFFFFFFFFL);  // DWORD (also BOOL arrays)
            default -> throw new IllegalArgumentException(String.format("data type 0x%04X is not a single value (read a member)", type));
        };
    }

    static String status(int s) {
        return switch (s) {
            case 0x04 -> "tag not found in the controller (path segment error)";
            case 0x05 -> "tag or member not found (path destination unknown)";
            case 0x06 -> "only part of the data was returned";
            case 0x08 -> "service not supported";
            case 0x0A -> "attribute list error";
            case 0x13 -> "not enough data";
            case 0x1E -> "embedded service error";
            case 0x26 -> "invalid tag path size";
            case 0x01 -> "connection failure (check backplane and slot)";
            default -> String.format("CIP status 0x%02X", s);
        };
    }

    // ------------------------------------------------------------------ transport

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
            session = 0;
            ByteBuffer r = encapsulation(0x0065, new byte[]{1, 0, 0, 0});   // RegisterSession, protocol version 1
            session = r.getInt(4);
            int status = r.getInt(8);
            if (status != 0) {
                throw new IOException(String.format("the device refused the session (status 0x%X)", status));
            }
        } catch (Exception e) {
            close();
            String why = e instanceof java.net.SocketTimeoutException ? "timed out"
                    : e instanceof java.net.ConnectException ? "connection refused" : e.getMessage();
            throw new Exception("cannot connect to " + describe() + ": " + why);
        }
    }

    /** one CIP request, unconnected (through the backplane to the CPU slot unless routing is off); returns the CIP reply */
    private byte[] send(byte[] cip) throws IOException {
        byte[] message = cip;
        if (backplane >= 0) {
            ByteArrayOutputStream u = new ByteArrayOutputStream();
            u.writeBytes(new byte[]{0x52, 0x02, 0x20, 0x06, 0x24, 0x01});      // Unconnected Send to the Connection Manager
            u.write(0x0A);                                                     // priority / time tick
            u.write(0x0E);                                                     // timeout ticks
            le16(u, cip.length);
            u.writeBytes(cip);
            if (cip.length % 2 == 1) {
                u.write(0);
            }
            u.write(1);                                                        // route path: 1 word
            u.write(0);
            u.write(backplane);                                                // port (1 = backplane)
            u.write(slot);                                                     // link address = slot
            message = u.toByteArray();
        }
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        d.writeBytes(new byte[]{0, 0, 0, 0});                                  // interface handle (CIP)
        le16(d, Math.max(1, timeoutMs / 1000));
        le16(d, 2);                                                            // two CPF items
        le16(d, 0x0000);                                                       // null address item
        le16(d, 0);
        le16(d, 0x00B2);                                                       // unconnected data item
        le16(d, message.length);
        d.writeBytes(message);
        ByteBuffer r = encapsulation(0x006F, d.toByteArray());                 // SendRRData
        int status = r.getInt(8);
        if (status != 0) {
            throw new IOException(String.format("the device refused the request (encapsulation status 0x%X)", status));
        }
        r.position(24 + 4 + 2);
        int items = r.getShort() & 0xFFFF;
        for (int k = 0; k < items; k++) {
            int type = r.getShort() & 0xFFFF;
            int len = r.getShort() & 0xFFFF;
            if (type == 0x00B2) {
                byte[] reply = new byte[len];
                r.get(reply);
                if (backplane >= 0 && len >= 4 && (reply[0] & 0xFF) == (0x52 | 0x80)) {
                    throw new IOException("routing to backplane " + backplane + " slot " + slot + " failed: " + status(reply[2] & 0xFF));
                }
                return reply;
            }
            r.position(r.position() + len);
        }
        throw new IOException("no CIP data in the answer");
    }

    /** encapsulation command + data -> the whole answer (header at 0, data at 24), little endian */
    private ByteBuffer encapsulation(int command, byte[] data) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        h.putShort((short) command);
        h.putShort((short) data.length);
        h.putInt(session);
        h.putInt(0);
        h.putLong(0x42494D4241L);                                              // sender context "BIMBA"
        h.putInt(0);
        h.put(data);
        out.write(h.array());
        out.flush();
        while (true) {
            byte[] head = new byte[24];
            try {
                in.readFully(head);
            } catch (EOFException e) {
                throw new IOException("the device closed the connection");
            }
            ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int cmd = hb.getShort(0) & 0xFFFF;
            int len = hb.getShort(2) & 0xFFFF;
            byte[] all = new byte[24 + len];
            System.arraycopy(head, 0, all, 0, 24);
            in.readFully(all, 24, len);
            if (cmd == command) {
                return ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            }
        }
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write(v >> 8 & 0xFF);
    }

    @Override
    public String describe() {
        return "Logix " + host + (backplane >= 0 ? " slot " + slot : "");
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                if (session != 0) {
                    ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                    h.putShort((short) 0x0066);                                // UnRegisterSession
                    h.putShort((short) 0);
                    h.putInt(session);
                    out.write(h.array());
                    out.flush();
                }
            } catch (IOException ignored) {
                // closing anyway
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // gone
            }
        }
        socket = null;
        in = null;
        out = null;
        session = 0;
    }

    /** Test connection: registers a session and reads the given tags once: {ok, target, values:[...]} */
    public static Map<String, Object> test(JsonObject settings, List<TagModel.Tag> tags) throws Exception {
        try (LogixDriver d = new LogixDriver(settings)) {
            List<Reading> r = tags.isEmpty() ? List.of() : d.read(tags);
            if (tags.isEmpty()) {
                d.connect();
            }
            return S7Driver.Plc.result(d.describe(), tags, r);
        }
    }
}
