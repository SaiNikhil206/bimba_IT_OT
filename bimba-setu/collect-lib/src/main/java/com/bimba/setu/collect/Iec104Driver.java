package com.bimba.setu.collect;

import com.bimba.setu.collect.Canonical.Quality;
import com.bimba.setu.config.TagModel;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IEC 60870-5-104 outstation (controlled station) over TCP, our own client. The driver keeps one connection with a
 * reader thread: STARTDT, a general interrogation for every common address the tags use, then the outstation's
 * spontaneous and periodic data keep the latest value of every information object; the collector samples them at the
 * source's read rate (only changes are sent). Each interrogation is a general one plus a counter interrogation
 * (integrated totals, read without freezing or resetting them); they are repeated every "giMinutes" and after the
 * outstation restarts (end of initialization). Read only: no commands other than these interrogations (which change
 * nothing on the outstation), no clock synchronization.
 * <p>
 * Settings: host, port (2404), commonAddress (ASDU address, 1), giMinutes (15, 0 = only after connecting),
 * timeZone (of the outstation's time tags, "UTC"), timeoutMs (5000). Fixed IEC 104 sizes: cause of transmission 2
 * octets, common address 2, information object address 3. Timers t1 15 s, t2 10 s, t3 20 s, w 8.
 * <p>
 * Addresses: the information object address (IOA), "4001", or common address/IOA "2/4001" for an outstation with
 * several. Monitor types read: single and double points, step positions, bitstrings, measured values (normalized,
 * scaled, short float) and integrated totals, with and without time tag. Quality: IV = BAD; NT, SB, BL, OV and
 * transient = UNCERTAIN. Normalized values arrive as -1..+1.
 */
public final class Iec104Driver implements Driver {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Iec104");
    private static final Pattern ADDRESS = Pattern.compile("^(?:(\\d{1,5})\\s*[/:]\\s*)?(\\d{1,8})$");
    private static final int T1_MS = 15_000, T2_MS = 10_000, T3_MS = 20_000, W = 8;

    record Point(int ca, int ioa) {
    }

    /** a value as received, with its ASDU type (for browsing) */
    record Value(Reading reading, int type) {
    }

    private final String host;
    private final int port;
    private final int defaultCa;
    private final int timeoutMs;
    private final long giMs;
    private final ZoneId zone;
    private final Map<Point, Value> values = new ConcurrentHashMap<>();
    /** common address -> why its general interrogation failed */
    private final Map<Integer, String> giFailed = new ConcurrentHashMap<>();
    /** interrogations waiting for their end: "100/ca" (general), "101/ca" (counters) */
    private final Set<String> giRunning = ConcurrentHashMap.newKeySet();
    private final Set<Integer> cas = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private volatile Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private volatile boolean closing;
    private volatile String lost;
    private volatile boolean started;
    // sequence numbers and timers: under lock
    private int sendSeq;
    private int recvSeq;
    private int unacked;
    private long firstUnackedAt;
    private long lastRx;
    private long testSentAt;
    private volatile long nextGiAt;

    public Iec104Driver(JsonObject s) {
        host = S7Driver.str(s, "host");
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("IEC 104 source without host");
        }
        port = S7Driver.num(s, "port", 2404);
        defaultCa = S7Driver.num(s, "commonAddress", 1);
        timeoutMs = Math.max(500, S7Driver.num(s, "timeoutMs", 5000));
        giMs = Math.max(0, S7Driver.num(s, "giMinutes", 15)) * 60_000L;
        String z = S7Driver.str(s, "timeZone");
        ZoneId zz;
        try {
            zz = z == null ? ZoneOffset.UTC : ZoneId.of(z);
        } catch (DateTimeException e) {
            zz = ZoneOffset.UTC;
        }
        zone = zz;
    }

    // ------------------------------------------------------------------ addresses

    /** "4001" or "2/4001" -> point; throws IllegalArgumentException with a readable message */
    public static Point parse(String raw, int defaultCa) {
        Matcher m = ADDRESS.matcher(raw == null ? "" : raw.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("IEC 104 address \"" + raw + "\": the information object address, e.g. 4001, or common address/IOA, e.g. 2/4001");
        }
        int ca = m.group(1) == null ? defaultCa : Integer.parseInt(m.group(1));
        long ioa = Long.parseLong(m.group(2));
        if (ca < 1 || ca > 65534) {
            throw new IllegalArgumentException("IEC 104 address \"" + raw + "\": common address 1–65534");
        }
        if (ioa > 0xFFFFFF) {
            throw new IllegalArgumentException("IEC 104 address \"" + raw + "\": information object address 0–16777215");
        }
        return new Point(ca, (int) ioa);
    }

    // ------------------------------------------------------------------ Driver

    @Override
    public synchronized List<Reading> read(List<TagModel.Tag> tags) throws Exception {
        List<Point> points = new ArrayList<>(tags.size());
        Set<Integer> wanted = new LinkedHashSet<>();
        wanted.add(defaultCa);
        for (TagModel.Tag t : tags) {
            Point p;
            try {
                p = parse(t.address(), defaultCa);
                wanted.add(p.ca());
            } catch (IllegalArgumentException e) {
                p = null;
            }
            points.add(p);
        }
        ensure(wanted);
        List<Reading> out = new ArrayList<>(tags.size());
        for (Point p : points) {
            if (p == null) {
                out.add(Reading.bad("invalid IEC 104 address"));
                continue;
            }
            Value v = values.get(p);
            String failed = giFailed.get(p.ca());
            out.add(v != null ? v.reading() : Reading.bad(failed != null ? "general interrogation of common address " + p.ca() + " failed: " + failed
                    : "no value from the outstation for IOA " + p.ioa() + (p.ca() != defaultCa ? " (common address " + p.ca() + ")" : "")
                    + " (not sent in the general interrogation)"));
        }
        return out;
    }

    /** connected, data transfer started, every wanted common address interrogated; throws when the connection is gone */
    private void ensure(Set<Integer> wanted) throws Exception {
        if (socket != null && lost != null) {
            String why = lost;
            close();
            throw new Exception("connection to " + describe() + " lost: " + why);
        }
        if (socket == null) {
            connect();
        }
        List<Integer> fresh = new ArrayList<>();
        for (int ca : wanted) {
            if (cas.add(ca)) {
                fresh.add(ca);
            }
        }
        if (!fresh.isEmpty()) {
            for (int ca : fresh) {
                interrogate(ca);
            }
            waitFor(() -> giRunning.isEmpty() || lost != null, Math.max(10_000, timeoutMs * 3L));
        }
    }

    private void connect() throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setTcpNoDelay(true);
            s.setSoTimeout(1000);
        } catch (Exception e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // not open
            }
            String why = e instanceof SocketTimeoutException ? "timed out" : e instanceof java.net.ConnectException ? "connection refused" : e.getMessage();
            throw new Exception("cannot connect to " + describe() + ": " + why);
        }
        synchronized (lock) {
            socket = s;
            in = new DataInputStream(s.getInputStream());
            out = s.getOutputStream();
            closing = false;
            lost = null;
            started = false;
            sendSeq = recvSeq = unacked = 0;
            lastRx = System.currentTimeMillis();
            testSentAt = 0;
        }
        values.clear();
        giFailed.clear();
        giRunning.clear();
        cas.clear();
        Thread reader = new Thread(() -> loop(s), "BIMBA-Iec104 " + host);
        reader.setDaemon(true);
        reader.start();
        try {
            sendU(0x07);                                                     // STARTDT act
        } catch (IOException e) {
            close();
            throw new Exception("cannot connect to " + describe() + ": " + S7Driver.why(e));
        }
        waitFor(() -> started || lost != null, timeoutMs);
        if (!started) {
            String why = lost;
            close();
            throw new Exception("cannot connect to " + describe() + ": " + (why != null ? why
                    : "no answer to STARTDT (not an IEC 104 server, or it accepts no further connection)"));
        }
        nextGiAt = giMs > 0 ? System.currentTimeMillis() + giMs : Long.MAX_VALUE;
    }

    private void waitFor(java.util.function.BooleanSupplier done, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        synchronized (lock) {
            long left;
            while (!done.getAsBoolean() && (left = until - System.currentTimeMillis()) > 0) {
                lock.wait(left);
            }
        }
    }

    // ------------------------------------------------------------------ reader thread

    private void loop(Socket s) {
        try {
            DataInputStream din;
            synchronized (lock) {
                din = in;
            }
            while (!closing && socket == s) {
                int start;
                try {
                    start = din.read();
                } catch (SocketTimeoutException e) {
                    tick();
                    continue;
                }
                if (start < 0) {
                    throw new EOFException();
                }
                if (start != 0x68) {
                    throw new IOException(String.format("not an IEC 104 frame (start byte 0x%02X)", start));
                }
                s.setSoTimeout(timeoutMs);
                int len = din.readUnsignedByte();
                if (len < 4) {
                    throw new IOException("IEC 104 frame too short");
                }
                byte[] apdu = new byte[len];
                din.readFully(apdu);
                s.setSoTimeout(1000);
                handle(apdu);
                tick();
            }
        } catch (Exception e) {
            String why = e instanceof IOException io ? S7Driver.why(io) : String.valueOf(e.getMessage());
            synchronized (lock) {
                if (socket == s && !closing) {                               // not an earlier connection's thread
                    log.info("IEC 104 {}: {}", host, why);
                    lost = why;
                }
            }
        } finally {
            synchronized (lock) {
                if (socket == s) {
                    started = false;
                    if (lost == null && !closing) {
                        lost = "the connection ended";
                    }
                }
                lock.notifyAll();
            }
            try {
                s.close();
            } catch (IOException ignored) {
                // gone
            }
        }
    }

    private void handle(byte[] apdu) throws IOException {
        int c0 = apdu[0] & 0xFF;
        long now = System.currentTimeMillis();
        synchronized (lock) {
            lastRx = now;
        }
        if ((c0 & 1) == 0) {                                                 // I-format: data
            int ns = c0 >> 1 | (apdu[1] & 0xFF) << 7;
            synchronized (lock) {
                recvSeq = ns + 1 & 0x7FFF;
                if (unacked++ == 0) {
                    firstUnackedAt = now;
                }
                if (unacked >= W) {
                    sendS();
                }
            }
            asdu(ByteBuffer.wrap(apdu, 4, apdu.length - 4).order(ByteOrder.LITTLE_ENDIAN));
        } else if ((c0 & 3) == 3) {                                          // U-format
            if ((c0 & 0x40) != 0) {
                sendU(0x83);                                                 // TESTFR act -> con
            } else if ((c0 & 0x80) != 0) {
                synchronized (lock) {
                    testSentAt = 0;                                          // TESTFR con
                }
            } else if ((c0 & 0x08) != 0) {
                synchronized (lock) {
                    started = true;                                          // STARTDT con
                    lock.notifyAll();
                }
            }
        }
        // S-format: the outstation acknowledges our I-frames; nothing to do
    }

    /** t2 (acknowledge received data), t3 (test an idle connection), t1 (the test frame's answer), periodic interrogation */
    private void tick() throws IOException {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (unacked > 0 && now - firstUnackedAt >= T2_MS) {
                sendS();
            }
            if (testSentAt > 0 && now - testSentAt > T1_MS) {
                throw new IOException("no answer to the test frame within " + T1_MS / 1000 + " s");
            }
            if (testSentAt == 0 && now - lastRx >= T3_MS) {
                testSentAt = now;
                sendU(0x43);                                                 // TESTFR act
            }
        }
        if (now >= nextGiAt) {
            nextGiAt = giMs > 0 ? now + giMs : Long.MAX_VALUE;
            for (int ca : cas) {
                interrogate(ca);
            }
        }
    }

    private void asdu(ByteBuffer b) throws IOException {
        if (b.remaining() < 6) {
            return;
        }
        int type = b.get() & 0xFF, vsq = b.get() & 0xFF, cot = b.get() & 0xFF;
        b.get();                                                             // originator address
        int ca = b.getShort() & 0xFFFF;
        int cause = cot & 0x3F;
        boolean negative = (cot & 0x40) != 0, test = (cot & 0x80) != 0;
        if (type == 100 || type == 101) {                                    // our general / counter interrogation
            boolean refused = cause == 7 && negative || cause >= 44 && cause <= 47;
            if (type == 100 && refused) {
                giFailed.put(ca, cause == 46 ? "unknown common address" : cause == 7 ? "refused by the outstation" : "refused (cause " + cause + ")");
            }
            if (cause == 10 || refused) {
                synchronized (lock) {
                    giRunning.remove(type + "/" + ca);
                    lock.notifyAll();
                }
            }
            return;
        }
        if (type == 70) {                                                    // end of initialization: interrogate again
            log.info("IEC 104 {}: outstation (common address {}) initialized, interrogating again", host, ca);
            nextGiAt = 0;
            return;
        }
        if (test) {
            return;
        }
        int size = elementSize(type);
        if (size < 0) {
            return;                                                          // a type not read: the frame's length bounds it
        }
        boolean sq = (vsq & 0x80) != 0;
        int n = vsq & 0x7F;
        int ioa = 0;
        for (int i = 0; i < n; i++) {
            if (!sq || i == 0) {
                if (b.remaining() < 3) {
                    return;
                }
                ioa = (b.get() & 0xFF) | (b.get() & 0xFF) << 8 | (b.get() & 0xFF) << 16;
            } else {
                ioa++;
            }
            if (b.remaining() < size) {
                return;
            }
            byte[] e = new byte[size];
            b.get(e);
            values.put(new Point(ca, ioa), new Value(decode(type, e, zone), type));
        }
    }

    /** bytes per information element (without its IOA); -1 = a type the driver does not read */
    static int elementSize(int type) {
        return switch (type) {
            case 1, 3 -> 1;                     // M_SP_NA_1, M_DP_NA_1
            case 2, 4 -> 4;                     // ... with CP24 time
            case 5 -> 2;                        // M_ST_NA_1
            case 6 -> 5;
            case 7 -> 5;                        // M_BO_NA_1
            case 8 -> 8;
            case 9, 11 -> 3;                    // M_ME_NA_1 normalized, M_ME_NB_1 scaled
            case 10, 12 -> 6;
            case 13 -> 5;                       // M_ME_NC_1 short float
            case 14 -> 8;
            case 15 -> 5;                       // M_IT_NA_1 integrated totals
            case 16 -> 8;
            case 21 -> 2;                       // M_ME_ND_1 normalized without quality
            case 30, 31 -> 8;                   // M_SP_TB_1, M_DP_TB_1 (CP56 time)
            case 32 -> 9;
            case 33 -> 12;
            case 34, 35 -> 10;
            case 36, 37 -> 12;
            default -> -1;
        };
    }

    /** one information element -> reading */
    static Reading decode(int type, byte[] e, ZoneId zone) {
        ByteBuffer b = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case 1, 2, 30 -> quality((e[0] & 1) != 0, e[0] & 0xF0, null, type == 30 ? cp56(e, 1, zone) : null);
            case 3, 4, 31 -> {
                int dpi = e[0] & 3;
                Instant t = type == 31 ? cp56(e, 1, zone) : null;
                yield dpi == 1 || dpi == 2 ? quality(dpi == 2, e[0] & 0xF0, null, t)
                        : new Reading(null, Quality.BAD, dpi == 0 ? "intermediate (in transition)" : "indeterminate", t);
            }
            case 5, 6, 32 -> quality((double) ((byte) (e[0] << 1) >> 1), e[1] & 0xFF, (e[0] & 0x80) != 0 ? "in transient" : null,
                    type == 32 ? cp56(e, 2, zone) : null);
            case 7, 8, 33 -> quality((double) (b.getInt(0) & 0xFFFFFFFFL), e[4] & 0xFF, null, type == 33 ? cp56(e, 5, zone) : null);
            case 9, 10, 34 -> quality(b.getShort(0) / 32768.0, e[2] & 0xFF, null, type == 34 ? cp56(e, 3, zone) : null);
            case 11, 12, 35 -> quality((double) b.getShort(0), e[2] & 0xFF, null, type == 35 ? cp56(e, 3, zone) : null);
            case 13, 14, 36 -> quality((double) b.getFloat(0), e[4] & 0xFF, null, type == 36 ? cp56(e, 5, zone) : null);
            case 15, 16, 37 -> {
                int bcr = e[4] & 0xFF;
                Instant t = type == 37 ? cp56(e, 5, zone) : null;
                double v = b.getInt(0);
                if ((bcr & 0x80) != 0) {
                    yield new Reading(null, Quality.BAD, "invalid (IV)", t);
                }
                List<String> why = new ArrayList<>();
                if ((bcr & 0x20) != 0) {
                    why.add("counter overflow (CY)");
                }
                if ((bcr & 0x40) != 0) {
                    why.add("counter adjusted (CA)");
                }
                yield why.isEmpty() ? new Reading(v, Quality.GOOD, null, t) : Reading.uncertain(v, String.join(", ", why), t);
            }
            case 21 -> Reading.ok(b.getShort(0) / 32768.0);
            default -> Reading.bad("type " + type + " not read");
        };
    }

    /** the quality descriptor: IV = BAD; NT, SB, BL, OV (and a step position in transient) = UNCERTAIN */
    static Reading quality(Object value, int qds, String extra, Instant time) {
        if ((qds & 0x80) != 0) {
            return new Reading(null, Quality.BAD, "invalid (IV)", time);
        }
        List<String> why = new ArrayList<>();
        if ((qds & 0x40) != 0) {
            why.add("not topical (NT)");
        }
        if ((qds & 0x20) != 0) {
            why.add("substituted (SB)");
        }
        if ((qds & 0x10) != 0) {
            why.add("blocked (BL)");
        }
        if ((qds & 0x01) != 0) {
            why.add("overflow (OV)");
        }
        if (extra != null) {
            why.add(extra);
        }
        return why.isEmpty() ? new Reading(value, Quality.GOOD, null, time) : Reading.uncertain(value, String.join(", ", why), time);
    }

    /** CP56Time2a in the given time zone; null when marked invalid or not a date */
    static Instant cp56(byte[] e, int at, ZoneId zone) {
        int ms = (e[at] & 0xFF) | (e[at + 1] & 0xFF) << 8;
        if ((e[at + 2] & 0x80) != 0) {
            return null;
        }
        try {
            return LocalDateTime.of(2000 + (e[at + 6] & 0x7F), e[at + 5] & 0x0F, e[at + 4] & 0x1F, e[at + 3] & 0x1F, e[at + 2] & 0x3F,
                    ms / 1000, ms % 1000 * 1_000_000).atZone(zone).toInstant();
        } catch (DateTimeException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ sending

    /**
     * General interrogation (C_IC_NA_1, station interrogation QOI 20) and counter interrogation (C_CI_NA_1, general
     * request, no freeze or reset: QCC 5) of one common address. Integrated totals are not part of the general one.
     */
    private void interrogate(int ca) throws IOException {
        giRunning.add("100/" + ca);
        giRunning.add("101/" + ca);
        giFailed.remove(ca);
        sendI(new byte[]{100, 1, 6, 0, (byte) ca, (byte) (ca >> 8), 0, 0, 0, 20});
        sendI(new byte[]{101, 1, 6, 0, (byte) ca, (byte) (ca >> 8), 0, 0, 0, 5});
    }

    private void sendI(byte[] asdu) throws IOException {
        synchronized (lock) {
            byte[] f = new byte[6 + asdu.length];
            f[0] = 0x68;
            f[1] = (byte) (4 + asdu.length);
            f[2] = (byte) (sendSeq << 1);
            f[3] = (byte) (sendSeq >> 7);
            f[4] = (byte) (recvSeq << 1);
            f[5] = (byte) (recvSeq >> 7);
            System.arraycopy(asdu, 0, f, 6, asdu.length);
            write(f);
            sendSeq = sendSeq + 1 & 0x7FFF;
            unacked = 0;
        }
    }

    private void sendS() throws IOException {
        synchronized (lock) {
            write(new byte[]{0x68, 4, 0x01, 0, (byte) (recvSeq << 1), (byte) (recvSeq >> 7)});
            unacked = 0;
        }
    }

    private void sendU(int c) throws IOException {
        synchronized (lock) {
            write(new byte[]{0x68, 4, (byte) c, 0, 0, 0});
        }
    }

    private void write(byte[] f) throws IOException {
        if (out == null) {
            throw new IOException("not connected");
        }
        out.write(f);
        out.flush();
    }

    @Override
    public String describe() {
        return "IEC 104 " + host + ":" + port + " common address " + defaultCa;
    }

    @Override
    public void close() {
        Socket s;
        synchronized (lock) {
            closing = true;
            s = socket;
            socket = null;
            in = null;
            out = null;
            started = false;
            lock.notifyAll();
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // gone
            }
        }
    }

    // ------------------------------------------------------------------ console requests

    private static final String[][] CATEGORIES = {
            {"SP", "Single points"}, {"DP", "Double points"}, {"ST", "Step positions"}, {"BO", "Bitstrings"},
            {"ME", "Measured values"}, {"IT", "Integrated totals"}};

    static String category(int type) {
        return switch (type) {
            case 1, 2, 30 -> "SP";
            case 3, 4, 31 -> "DP";
            case 5, 6, 32 -> "ST";
            case 7, 8, 33 -> "BO";
            case 15, 16, 37 -> "IT";
            default -> "ME";
        };
    }

    /** Test connection: connect, interrogate, the given tags' values and how many objects the outstation sent */
    public static Map<String, Object> test(JsonObject settings, List<TagModel.Tag> tags) throws Exception {
        try (Iec104Driver d = new Iec104Driver(settings)) {
            List<Reading> r = d.read(tags);
            Map<String, Object> m = S7Driver.Plc.result(d.describe(), tags, r);
            m.put("points", summary(d.values));
            String failed = d.giFailed.get(d.defaultCa);
            if (failed != null) {
                m.put("note", "General interrogation of common address " + d.defaultCa + " failed: " + failed + ".");
            }
            return m;
        }
    }

    /** Browse: categories of the objects the outstation sent (folders), then the objects of one: {items:[..]} */
    public static Map<String, Object> browse(JsonObject settings, String category, int limit) throws Exception {
        try (Iec104Driver d = new Iec104Driver(settings)) {
            d.read(List.of());
            List<Map<String, Object>> items = new ArrayList<>();
            if (category == null || category.isBlank()) {
                for (String[] c : CATEGORIES) {
                    long n = d.values.values().stream().filter(v -> category(v.type()).equals(c[0])).count();
                    if (n > 0) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("nodeId", c[0]);
                        f.put("name", c[1] + " (" + n + ")");
                        f.put("nodeClass", "Object");
                        items.add(f);
                    }
                }
            } else {
                d.values.entrySet().stream().filter(e -> category(e.getValue().type()).equals(category))
                        .sorted(Comparator.comparingInt((Map.Entry<Point, Value> e) -> e.getKey().ca()).thenComparingInt(e -> e.getKey().ioa()))
                        .limit(limit)
                        .forEach(e -> {
                            Point p = e.getKey();
                            String id = p.ca() == d.defaultCa ? String.valueOf(p.ioa()) : p.ca() + "/" + p.ioa();
                            Map<String, Object> v = new LinkedHashMap<>();
                            v.put("nodeId", id);
                            v.put("name", "IOA " + id);
                            v.put("nodeClass", "Variable");
                            v.put("tagName", "IOA" + id.replace('/', '_'));
                            v.put("value", e.getValue().reading().value());
                            v.put("good", e.getValue().reading().good());
                            items.add(v);
                        });
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("items", items);
            String failed = d.giFailed.get(d.defaultCa);
            if (failed != null) {
                m.put("note", "General interrogation failed: " + failed + ". Objects appear as the outstation sends them.");
            }
            return m;
        }
    }

    static String summary(Map<Point, Value> values) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (String[] c : CATEGORIES) {
            n.put(c[1], 0);
        }
        values.values().forEach(v -> {
            for (String[] c : CATEGORIES) {
                if (c[0].equals(category(v.type()))) {
                    n.merge(c[1], 1, Integer::sum);
                }
            }
        });
        List<String> parts = new ArrayList<>();
        n.forEach((k, c) -> {
            if (c > 0) {
                parts.add(c + " " + k.toLowerCase(java.util.Locale.ROOT));
            }
        });
        return parts.isEmpty() ? "no objects" : String.join(", ", parts);
    }
}
