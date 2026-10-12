package com.bimba.setu.collect;

import com.bimba.setu.collect.Canonical.Quality;
import com.bimba.setu.config.TagModel;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DNP3 (IEEE 1815) outstation over TCP, our own master: link layer (CRC-16/DNP in 16-byte blocks), transport layer
 * (segments of up to 249 bytes) and the application layer's READ. Every read cycle is one Class 0 poll: the
 * outstation's static data, every point. Events are never read, so the outstation's event buffers stay with the SCADA
 * master. Read only: the driver sends READ requests and the confirmations the protocol requires (responses in several
 * fragments, unsolicited responses sent to this master address), nothing else.
 * <p>
 * Settings: host, port (20000), outstation (its DNP3 address, 10), master (this master's address, 1), timeoutMs (5000).
 * Most outstations accept several masters, each with its own address (often on its own port): give the agent one that
 * the SCADA master does not use.
 * <p>
 * Addresses, point type + index: AI5 (analog input, group 30), BI3 (binary input, 1), DBI2 (double-bit input, 3),
 * BO1 (binary output status, 10), AO2 (analog output status, 40), CT4 (counter, 20), FC4 (frozen counter, 21),
 * OS1 (octet string, 110). Point flags become the quality: not ONLINE, RESTART or COMM_LOST = BAD; forced, over range,
 * reference error, chatter filter or discontinuity = UNCERTAIN.
 */
public final class Dnp3Driver implements Driver {
    private static final Pattern ADDRESS = Pattern.compile("^(AI|BI|DBI|BO|AO|CT|FC|OS)\\s*(\\d{1,5})$");
    private static final int MAX_SEGMENT = 249;

    /** point types: their static object group and their name in the console */
    enum Type {
        BI(1, "Binary inputs"), DBI(3, "Double-bit inputs"), BO(10, "Binary outputs"), CT(20, "Counters"),
        FC(21, "Frozen counters"), AI(30, "Analog inputs"), AO(40, "Analog outputs"), OS(110, "Octet strings");

        final int group;
        final String label;

        Type(int group, String label) {
            this.group = group;
            this.label = label;
        }

        static Type of(int group) {
            for (Type t : values()) {
                if (t.group == group) {
                    return t;
                }
            }
            return null;
        }
    }

    record Point(Type type, int index) {
        @Override
        public String toString() {
            return type.name() + index;
        }
    }

    /** one Class 0 answer: the points and what could not be decoded (null = everything was) */
    record Answer(Map<Point, Reading> points, String problem) {
    }

    private record Frame(int ctrl, int dest, int src, byte[] data) {
    }

    private final String host;
    private final int port;
    private final int outstation;
    private final int master;
    private final int timeoutMs;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int appSeq;
    private int tpSeq;
    /** an answer from another link address than the outstation's (for the error message) */
    private Integer otherSource;

    public Dnp3Driver(JsonObject s) {
        host = S7Driver.str(s, "host");
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("DNP3 source without host");
        }
        port = S7Driver.num(s, "port", 20000);
        outstation = S7Driver.num(s, "outstation", 10);
        master = S7Driver.num(s, "master", 1);
        timeoutMs = Math.max(500, S7Driver.num(s, "timeoutMs", 5000));
    }

    // ------------------------------------------------------------------ addresses

    /** AI5 -> point; throws IllegalArgumentException with a readable message */
    public static Point parse(String raw) {
        String a = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        Matcher m = ADDRESS.matcher(a);
        if (!m.matches()) {
            throw new IllegalArgumentException("DNP3 address \"" + raw + "\": point type and index, e.g. AI5, BI3, DBI2, BO1, AO2, CT4, FC4 or OS1");
        }
        int index = Integer.parseInt(m.group(2));
        if (index > 65535) {
            throw new IllegalArgumentException("DNP3 address \"" + raw + "\": index 0–65535");
        }
        return new Point(Type.valueOf(m.group(1)), index);
    }

    // ------------------------------------------------------------------ Driver

    @Override
    public synchronized List<Reading> read(List<TagModel.Tag> tags) throws Exception {
        Answer a = poll();
        List<Reading> out = new ArrayList<>(tags.size());
        for (TagModel.Tag t : tags) {
            Point p;
            try {
                p = parse(t.address());
            } catch (IllegalArgumentException e) {
                out.add(Reading.bad("invalid DNP3 address"));
                continue;
            }
            Reading r = a.points().get(p);
            out.add(r != null ? r : Reading.bad(p + " is not in the outstation's static data (Class 0)"
                    + (a.problem() == null ? "" : "; its answer also had " + a.problem())));
        }
        return out;
    }

    /** one Class 0 poll; connects first when needed. Throws when the outstation cannot be reached or does not answer. */
    private Answer poll() throws Exception {
        connect();
        try {
            return class0();
        } catch (IOException e) {
            close();
            throw new Exception("read from " + describe() + " failed: " + S7Driver.why(e) + hint());
        }
    }

    private String hint() {
        return otherSource != null && otherSource != outstation
                ? " (answers come from DNP3 address " + otherSource + ", not " + outstation + ": check the outstation address)" : "";
    }

    private Answer class0() throws IOException {
        int seq = appSeq++ & 0x0F;
        sendApp(new byte[]{(byte) (0xC0 | seq), 0x01, 60, 1, 0x06});           // READ g60v1 (Class 0), all
        Map<Point, Reading> points = new HashMap<>();
        String problem = null;
        boolean first = true;
        long deadline = System.currentTimeMillis() + timeoutMs * 3L;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new java.net.SocketTimeoutException();
            }
            byte[] a = fragment();
            if (a.length < 2) {
                continue;
            }
            int ac = a[0] & 0xFF, fc = a[1] & 0xFF;
            boolean fir = (ac & 0x80) != 0, fin = (ac & 0x40) != 0, con = (ac & 0x20) != 0, uns = (ac & 0x10) != 0;
            int aseq = ac & 0x0F;
            if (fc == 0x82 || uns) {                                            // unsolicited: not what was asked
                if (con) {
                    confirm(aseq, true);
                }
                continue;
            }
            if (fc != 0x81 || a.length < 4 || (first && (!fir || aseq != seq))) {
                continue;                                                       // a late answer to an older request
            }
            if (con) {
                confirm(aseq, false);
            }
            int iin2 = a[3] & 0xFF;
            if ((iin2 & 0x01) != 0) {
                throw new IOException("the outstation does not support READ (IIN2.0)");
            }
            if ((iin2 & 0x02) != 0) {
                throw new IOException("the outstation does not know the Class 0 object (IIN2.1)");
            }
            if ((iin2 & 0x04) != 0) {
                throw new IOException("the outstation refused the Class 0 request (parameter error, IIN2.2)");
            }
            String p = objects(ByteBuffer.wrap(a, 4, a.length - 4).order(ByteOrder.LITTLE_ENDIAN), points);
            problem = problem == null ? p : problem;
            first = false;
            if (fin) {
                return new Answer(points, problem);
            }
        }
    }

    // ------------------------------------------------------------------ objects

    /**
     * The objects of one response fragment into points; returns what could not be decoded (parsing stops there, the
     * points before it are kept), or null.
     */
    static String objects(ByteBuffer b, Map<Point, Reading> out) {
        while (b.remaining() >= 3) {
            int g = b.get() & 0xFF, v = b.get() & 0xFF, q = b.get() & 0xFF;
            int prefix = q >> 4 & 7, range = q & 0x0F;
            long start = 0, count;
            try {
                switch (range) {
                    case 0 -> {
                        start = b.get() & 0xFF;
                        count = (b.get() & 0xFF) - start + 1;
                    }
                    case 1 -> {
                        start = b.getShort() & 0xFFFF;
                        count = (b.getShort() & 0xFFFF) - start + 1;
                    }
                    case 2 -> {
                        start = b.getInt() & 0xFFFFFFFFL;
                        count = (b.getInt() & 0xFFFFFFFFL) - start + 1;
                    }
                    case 7 -> count = b.get() & 0xFF;
                    case 8 -> count = b.getShort() & 0xFFFF;
                    case 9 -> count = b.getInt() & 0xFFFFFFFFL;
                    default -> {
                        return String.format("g%dv%d with qualifier 0x%02X, which the agent cannot decode", g, v, q);
                    }
                }
            } catch (java.nio.BufferUnderflowException e) {
                return "a truncated object header";
            }
            if (count < 0 || count > 65536 || prefix > 3) {
                return String.format("g%dv%d with qualifier 0x%02X, which the agent cannot decode", g, v, q);
            }
            boolean packed = (g == 1 || g == 10) && v == 1 || g == 3 && v == 1;
            if (packed) {
                int bits = g == 3 ? 2 : 1;
                int bytes = (int) ((count * bits + 7) / 8);
                if (prefix != 0 || b.remaining() < bytes) {
                    return String.format("g%dv%d that is not as expected", g, v);
                }
                byte[] d = new byte[bytes];
                b.get(d);
                Type type = Type.of(g);
                for (int i = 0; i < count; i++) {
                    int pos = i * bits;
                    int x = d[pos / 8] >> (pos % 8) & (bits == 2 ? 3 : 1);
                    out.put(new Point(type, (int) (start + i)), bits == 2 ? doubleBit(x, 0x01) : Reading.ok(x != 0));
                }
                continue;
            }
            int size = size(g, v);
            if (size < 0) {
                return String.format("g%dv%d, which the agent cannot decode", g, v);
            }
            Type type = Type.of(g);
            for (int i = 0; i < count; i++) {
                int prefixBytes = prefix == 0 ? 0 : prefix == 3 ? 4 : prefix;
                if (b.remaining() < prefixBytes + size) {
                    return String.format("a truncated g%dv%d object", g, v);
                }
                long index = switch (prefix) {
                    case 1 -> b.get() & 0xFF;
                    case 2 -> b.getShort() & 0xFFFF;
                    case 3 -> b.getInt() & 0xFFFFFFFFL;
                    default -> start + i;
                };
                byte[] d = new byte[size];
                b.get(d);
                if (type != null && index <= 65535) {
                    Reading r = decode(g, v, d);
                    if (r != null) {
                        out.put(new Point(type, (int) index), r);
                    }
                }
            }
        }
        return null;
    }

    /** bytes per object (static and event groups, so any answer can be stepped through); -1 = unknown */
    static int size(int g, int v) {
        return switch (g) {
            case 1, 3, 10 -> v == 2 ? 1 : -1;
            case 2, 4 -> v == 1 ? 1 : v == 2 ? 7 : v == 3 ? 3 : -1;
            case 11 -> v == 1 ? 1 : v == 2 ? 7 : -1;
            case 20 -> switch (v) {
                case 1, 3 -> 5;
                case 2, 4 -> 3;
                case 5, 7 -> 4;
                case 6, 8 -> 2;
                default -> -1;
            };
            case 21 -> switch (v) {
                case 1, 3 -> 5;
                case 2, 4 -> 3;
                case 5, 7 -> 11;
                case 6, 8 -> 9;
                case 9, 11 -> 4;
                case 10, 12 -> 2;
                default -> -1;
            };
            case 22, 23 -> switch (v) {
                case 1, 3 -> 5;
                case 2, 4 -> 3;
                case 5, 7 -> 11;
                case 6, 8 -> 9;
                default -> -1;
            };
            case 30 -> switch (v) {
                case 1, 5 -> 5;
                case 2 -> 3;
                case 3 -> 4;
                case 4 -> 2;
                case 6 -> 9;
                default -> -1;
            };
            case 32, 42 -> switch (v) {
                case 1, 5 -> 5;
                case 2 -> 3;
                case 3, 7 -> 11;
                case 4 -> 9;
                case 6 -> 9;
                case 8 -> 15;
                default -> -1;
            };
            case 40 -> switch (v) {
                case 1, 3 -> 5;
                case 2 -> 3;
                case 4 -> 9;
                default -> -1;
            };
            case 50 -> v == 1 || v == 3 ? 6 : v == 2 ? 10 : v == 4 ? 11 : -1;   // time; v4 = time + interval (in Class 0)
            case 51 -> v == 1 || v == 2 ? 6 : -1;
            case 52 -> v == 1 || v == 2 ? 2 : -1;
            case 102 -> v == 1 ? 1 : -1;
            case 110, 111 -> v >= 1 ? v : -1;
            default -> -1;
        };
    }

    /** one static object -> reading (null for objects that are not points: time, events) */
    static Reading decode(int g, int v, byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        return switch (g) {
            case 1, 10 -> flagged((d[0] & 0x80) != 0, d[0] & 0xFF, Type.BI, null);
            case 3 -> doubleBit(d[0] >> 6 & 3, d[0] & 0x3F);
            case 20 -> switch (v) {
                case 1, 3 -> flagged((double) (b.getInt(1) & 0xFFFFFFFFL), d[0] & 0xFF, Type.CT, null);
                case 2, 4 -> flagged((double) (b.getShort(1) & 0xFFFF), d[0] & 0xFF, Type.CT, null);
                case 5, 7 -> Reading.ok((double) (b.getInt(0) & 0xFFFFFFFFL));
                default -> Reading.ok((double) (b.getShort(0) & 0xFFFF));
            };
            case 21 -> switch (v) {
                case 1, 3 -> flagged((double) (b.getInt(1) & 0xFFFFFFFFL), d[0] & 0xFF, Type.CT, null);
                case 2, 4 -> flagged((double) (b.getShort(1) & 0xFFFF), d[0] & 0xFF, Type.CT, null);
                case 5, 7 -> flagged((double) (b.getInt(1) & 0xFFFFFFFFL), d[0] & 0xFF, Type.CT, time48(b, 5));
                case 6, 8 -> flagged((double) (b.getShort(1) & 0xFFFF), d[0] & 0xFF, Type.CT, time48(b, 3));
                case 9, 11 -> Reading.ok((double) (b.getInt(0) & 0xFFFFFFFFL));
                default -> Reading.ok((double) (b.getShort(0) & 0xFFFF));
            };
            case 30 -> switch (v) {
                case 1 -> flagged((double) b.getInt(1), d[0] & 0xFF, Type.AI, null);
                case 2 -> flagged((double) b.getShort(1), d[0] & 0xFF, Type.AI, null);
                case 3 -> Reading.ok((double) b.getInt(0));
                case 4 -> Reading.ok((double) b.getShort(0));
                case 5 -> flagged((double) b.getFloat(1), d[0] & 0xFF, Type.AI, null);
                default -> flagged(b.getDouble(1), d[0] & 0xFF, Type.AI, null);
            };
            case 40 -> switch (v) {
                case 1 -> flagged((double) b.getInt(1), d[0] & 0xFF, Type.AI, null);
                case 2 -> flagged((double) b.getShort(1), d[0] & 0xFF, Type.AI, null);
                case 3 -> flagged((double) b.getFloat(1), d[0] & 0xFF, Type.AI, null);
                default -> flagged(b.getDouble(1), d[0] & 0xFF, Type.AI, null);
            };
            case 110 -> Reading.ok(new String(d, StandardCharsets.ISO_8859_1).replace("\0", ""));
            default -> null;
        };
    }

    /** a double-bit state: 1 = OFF (false), 2 = ON (true), 0 / 3 = in transition / indeterminate (BAD) */
    static Reading doubleBit(int state, int flags) {
        if (state == 0) {
            return Reading.bad("intermediate (in transition)");
        }
        if (state == 3) {
            return Reading.bad("indeterminate");
        }
        return flagged(state == 2, flags | 0x01, Type.BI, null);
    }

    /** the point's flags as quality: ONLINE off, RESTART, COMM_LOST = BAD; forced and the type's own flags = UNCERTAIN */
    static Reading flagged(Object value, int flags, Type type, Instant time) {
        if ((flags & 0x01) == 0) {
            return Reading.bad("offline (the point's ONLINE flag is off)");
        }
        if ((flags & 0x02) != 0) {
            return Reading.bad("not updated since the outstation restarted (RESTART flag)");
        }
        if ((flags & 0x04) != 0) {
            return Reading.bad("communication lost between the outstation and the device (COMM_LOST flag)");
        }
        List<String> why = new ArrayList<>();
        if ((flags & 0x08) != 0) {
            why.add("forced remotely");
        }
        if ((flags & 0x10) != 0) {
            why.add("forced locally");
        }
        if ((flags & 0x20) != 0) {
            why.add(type == Type.AI ? "over range" : type == Type.BI ? "chatter filter on" : "rolled over");
        }
        if ((flags & 0x40) != 0) {
            why.add(type == Type.AI ? "reference error" : type == Type.CT ? "discontinuity" : "flag 0x40");
        }
        return why.isEmpty() ? new Reading(value, Quality.GOOD, null, time) : Reading.uncertain(value, String.join(", ", why), time);
    }

    /** DNP3 time: milliseconds since 1970 UTC, 48 bits little endian */
    static Instant time48(ByteBuffer b, int at) {
        long ms = 0;
        for (int i = 5; i >= 0; i--) {
            ms = ms << 8 | (b.get(at + i) & 0xFF);
        }
        return ms == 0 ? null : Instant.ofEpochMilli(ms);
    }

    // ------------------------------------------------------------------ link + transport layers

    /** CRC-16/DNP (polynomial 0x3D65 reflected, result complemented), sent low byte first */
    static int crc(byte[] b, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= b[i] & 0xFF;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 1) != 0 ? crc >>> 1 ^ 0xA6BC : crc >>> 1;
            }
        }
        return ~crc & 0xFFFF;
    }

    /** a link frame: header (with its CRC), then the user data in blocks of 16 bytes, each followed by its CRC */
    static byte[] frame(int ctrl, int dest, int src, byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] h = {0x05, 0x64, (byte) (5 + data.length), (byte) ctrl, (byte) dest, (byte) (dest >> 8), (byte) src, (byte) (src >> 8)};
        o.write(h, 0, 8);
        int c = crc(h, 0, 8);
        o.write(c);
        o.write(c >> 8);
        for (int off = 0; off < data.length; off += 16) {
            int n = Math.min(16, data.length - off);
            o.write(data, off, n);
            c = crc(data, off, n);
            o.write(c);
            o.write(c >> 8);
        }
        return o.toByteArray();
    }

    private Frame readFrame() throws IOException {
        int skipped = 0;
        int prev = in.readUnsignedByte();
        while (true) {
            int cur = in.readUnsignedByte();
            if (prev == 0x05 && cur == 0x64) {
                break;
            }
            if (++skipped > 600) {
                throw new IOException("not a DNP3 device (no link frame start 05 64)");
            }
            prev = cur;
        }
        byte[] h = new byte[10];
        h[0] = 0x05;
        h[1] = 0x64;
        in.readFully(h, 2, 8);
        if (crc(h, 0, 8) != ((h[8] & 0xFF) | (h[9] & 0xFF) << 8)) {
            throw new IOException("link frame with a wrong CRC");
        }
        int len = h[2] & 0xFF;
        if (len < 5) {
            throw new IOException("link frame too short");
        }
        byte[] data = new byte[len - 5];
        for (int pos = 0; pos < data.length; pos += 16) {
            int n = Math.min(16, data.length - pos);
            byte[] blk = new byte[n + 2];
            in.readFully(blk);
            if (crc(blk, 0, n) != ((blk[n] & 0xFF) | (blk[n + 1] & 0xFF) << 8)) {
                throw new IOException("link frame data with a wrong CRC");
            }
            System.arraycopy(blk, 0, data, pos, n);
        }
        return new Frame(h[3] & 0xFF, (h[4] & 0xFF) | (h[5] & 0xFF) << 8, (h[6] & 0xFF) | (h[7] & 0xFF) << 8, data);
    }

    /** the next application fragment from the outstation (link requests answered, transport segments joined) */
    private byte[] fragment() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean building = false;
        while (true) {
            Frame f = readFrame();
            if (f.dest() != master || f.src() != outstation) {
                otherSource = f.src();
                continue;
            }
            if ((f.ctrl() & 0x40) == 0) {
                continue;                                                    // a secondary frame (ACK, link status)
            }
            switch (f.ctrl() & 0x0F) {
                case 0, 2 -> {                                               // reset link states, test link: ACK
                    link(0x80);
                    continue;
                }
                case 9 -> {                                                  // request link status: link status
                    link(0x8B);
                    continue;
                }
                case 3 -> link(0x80);                                        // confirmed user data: ACK, then use it
                case 4 -> {
                    // unconfirmed user data
                }
                default -> {
                    continue;
                }
            }
            byte[] d = f.data();
            if (d.length < 1) {
                continue;
            }
            int th = d[0] & 0xFF;
            if ((th & 0x40) != 0) {
                buf.reset();
                building = true;
            }
            if (!building) {
                continue;
            }
            buf.write(d, 1, d.length - 1);
            if ((th & 0x80) != 0) {
                return buf.toByteArray();
            }
        }
    }

    private void link(int ctrl) throws IOException {
        out.write(frame(ctrl, outstation, master, new byte[0]));
        out.flush();
    }

    private void confirm(int seq, boolean unsolicited) throws IOException {
        sendApp(new byte[]{(byte) (0xC0 | (unsolicited ? 0x10 : 0) | seq), 0x00});
    }

    /** an application fragment in transport segments, each in an unconfirmed user data frame */
    private void sendApp(byte[] apdu) throws IOException {
        for (int off = 0; off < apdu.length; off += MAX_SEGMENT) {
            int n = Math.min(MAX_SEGMENT, apdu.length - off);
            byte[] d = new byte[n + 1];
            d[0] = (byte) ((off == 0 ? 0x40 : 0) | (off + n >= apdu.length ? 0x80 : 0) | (tpSeq++ & 0x3F));
            System.arraycopy(apdu, off, d, 1, n);
            out.write(frame(0xC4, outstation, master, d));
        }
        out.flush();
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
            otherSource = null;
        } catch (Exception e) {
            close();
            String why = e instanceof java.net.SocketTimeoutException ? "timed out"
                    : e instanceof java.net.ConnectException ? "connection refused" : e.getMessage();
            throw new Exception("cannot connect to " + describe() + ": " + why);
        }
    }

    @Override
    public String describe() {
        return "DNP3 " + host + ":" + port + " outstation " + outstation;
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

    /** Test connection: one Class 0 poll, the given tags' values and what the outstation has: {ok, target, points, values} */
    public static Map<String, Object> test(JsonObject settings, List<TagModel.Tag> tags) throws Exception {
        try (Dnp3Driver d = new Dnp3Driver(settings)) {
            Answer a = d.poll();
            List<Reading> r = new ArrayList<>();
            for (TagModel.Tag t : tags) {
                Point p;
                try {
                    p = parse(t.address());
                } catch (IllegalArgumentException e) {
                    r.add(Reading.bad("invalid DNP3 address"));
                    continue;
                }
                Reading v = a.points().get(p);
                r.add(v != null ? v : Reading.bad(p + " is not in the outstation's static data"));
            }
            Map<String, Object> m = S7Driver.Plc.result(d.describe(), tags, r);
            m.put("points", summary(a.points()));
            if (a.problem() != null) {
                m.put("note", "The answer also had " + a.problem() + ".");
            }
            return m;
        }
    }

    /** Browse: the point types of the outstation (folders), then the points of one type: {items:[..]} */
    public static Map<String, Object> browse(JsonObject settings, String type, int limit) throws Exception {
        try (Dnp3Driver d = new Dnp3Driver(settings)) {
            Answer a = d.poll();
            Map<Type, TreeMap<Integer, Reading>> byType = new EnumMap<>(Type.class);
            a.points().forEach((p, r) -> byType.computeIfAbsent(p.type(), k -> new TreeMap<>()).put(p.index(), r));
            List<Map<String, Object>> items = new ArrayList<>();
            if (type == null || type.isBlank()) {
                byType.forEach((t, pts) -> items.add(node(t.name(), t.label + " (" + pts.size() + ")", "Object", null, null)));
            } else {
                Type t;
                try {
                    t = Type.valueOf(type.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new Exception("unknown DNP3 point type " + type);
                }
                for (Map.Entry<Integer, Reading> e : byType.getOrDefault(t, new TreeMap<>()).entrySet()) {
                    if (items.size() >= limit) {
                        break;
                    }
                    String id = t.name() + e.getKey();
                    items.add(node(id, id, "Variable", e.getValue().value(), e.getValue()));
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("items", items);
            if (a.problem() != null) {
                m.put("note", "Not listed: " + a.problem() + ".");
            }
            return m;
        }
    }

    private static Map<String, Object> node(String id, String name, String cls, Object value, Reading r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", id);
        m.put("name", name);
        m.put("nodeClass", cls);
        if (r != null) {
            m.put("tagName", id);
            m.put("value", value);
            m.put("good", r.good());
        }
        return m;
    }

    /** "12 analog inputs, 8 binary inputs" */
    static String summary(Map<Point, Reading> points) {
        Map<Type, Integer> n = new EnumMap<>(Type.class);
        points.keySet().forEach(p -> n.merge(p.type(), 1, Integer::sum));
        List<String> parts = new ArrayList<>();
        n.forEach((t, c) -> parts.add(c + " " + t.label.toLowerCase(Locale.ROOT)));
        return parts.isEmpty() ? "no points" : String.join(", ", parts);
    }
}
