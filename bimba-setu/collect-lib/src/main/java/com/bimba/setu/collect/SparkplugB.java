package com.bimba.setu.collect;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sparkplug B payloads (Eclipse Sparkplug 3.0, sparkplug_b.proto) without a protobuf library: the fields BIMBA reads
 * (payload timestamp + seq, metric name / alias / timestamp / datatype / is_null and the scalar values) and the one
 * payload it may write (Node Control/Rebirth). Other fields (properties, datasets, templates) are skipped.
 */
public final class SparkplugB {
    /** Sparkplug data types (subset with scalar values) */
    public static final int INT8 = 1, INT16 = 2, INT32 = 3, INT64 = 4, UINT8 = 5, UINT16 = 6, UINT32 = 7, UINT64 = 8,
            FLOAT = 9, DOUBLE = 10, BOOLEAN = 11, STRING = 12, DATETIME = 13, TEXT = 14, UUID = 15;

    /** one metric: name may be null when only the alias is sent (DATA after a BIRTH that defined the alias) */
    public record Metric(String name, Long alias, Long timestamp, int datatype, boolean isNull, Object value) {
    }

    public record Payload(Long timestamp, Long seq, List<Metric> metrics) {
        /** bdSeq metric of an NBIRTH / NDEATH, or null */
        public Long bdSeq() {
            for (Metric m : metrics) {
                if ("bdSeq".equals(m.name()) && m.value() instanceof Number n) {
                    return n.longValue();
                }
            }
            return null;
        }
    }

    private SparkplugB() {
    }

    // ------------------------------------------------------------------ decode

    public static Payload decode(byte[] b) {
        Reader r = new Reader(b, 0, b.length);
        Long timestamp = null, seq = null;
        List<Metric> metrics = new ArrayList<>();
        while (r.more()) {
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 1 && wire == 0) {
                timestamp = r.varint();
            } else if (field == 2 && wire == 2) {
                int len = (int) r.varint();
                metrics.add(metric(new Reader(b, r.pos, r.pos + len)));
                r.pos += len;
            } else if (field == 3 && wire == 0) {
                seq = r.varint();
            } else {
                r.skip(wire);
            }
        }
        return new Payload(timestamp, seq, metrics);
    }

    private static Metric metric(Reader r) {
        String name = null;
        Long alias = null, timestamp = null;
        int datatype = 0;
        boolean isNull = false;
        long intValue = 0, longValue = 0;
        Float floatValue = null;
        Double doubleValue = null;
        Boolean boolValue = null;
        String stringValue = null;
        int valueField = 0;
        while (r.more()) {
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            switch (field) {
                case 1 -> name = wire == 2 ? r.string() : skip(r, wire);
                case 2 -> alias = wire == 0 ? r.varint() : skipL(r, wire);
                case 3 -> timestamp = wire == 0 ? r.varint() : skipL(r, wire);
                case 4 -> datatype = wire == 0 ? (int) r.varint() : skipI(r, wire);
                case 7 -> isNull = wire == 0 ? r.varint() != 0 : skipB(r, wire);
                case 10 -> { intValue = r.varint(); valueField = 10; }
                case 11 -> { longValue = r.varint(); valueField = 11; }
                case 12 -> { floatValue = Float.intBitsToFloat((int) r.fixed32()); valueField = 12; }
                case 13 -> { doubleValue = Double.longBitsToDouble(r.fixed64()); valueField = 13; }
                case 14 -> { boolValue = r.varint() != 0; valueField = 14; }
                case 15 -> { stringValue = r.string(); valueField = 15; }
                default -> r.skip(wire);
            }
        }
        Object value = null;
        if (!isNull) {
            value = switch (valueField) {
                case 10 -> switch (datatype) {
                    case INT8 -> (double) (byte) intValue;
                    case INT16 -> (double) (short) intValue;
                    case INT32 -> (double) (int) intValue;
                    default -> (double) (intValue & 0xFFFFFFFFL);          // UInt8/16/32
                };
                case 11 -> datatype == UINT64 ? Double.parseDouble(Long.toUnsignedString(longValue))
                        : datatype == DATETIME ? java.time.Instant.ofEpochMilli(longValue).toString() : (double) longValue;
                case 12 -> floatValue.doubleValue();
                case 13 -> doubleValue;
                case 14 -> boolValue;
                case 15 -> stringValue;
                default -> null;
            };
        }
        return new Metric(name, alias, timestamp, datatype, isNull, value);
    }

    private static String skip(Reader r, int wire) {
        r.skip(wire);
        return null;
    }

    private static Long skipL(Reader r, int wire) {
        r.skip(wire);
        return null;
    }

    private static int skipI(Reader r, int wire) {
        r.skip(wire);
        return 0;
    }

    private static boolean skipB(Reader r, int wire) {
        r.skip(wire);
        return false;
    }

    private static final class Reader {
        final byte[] b;
        int pos;
        final int end;

        Reader(byte[] b, int pos, int end) {
            if (end > b.length || pos < 0 || pos > end) {
                throw new IllegalArgumentException("not a Sparkplug B payload (truncated)");
            }
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        long varint() {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) {
                    throw new IllegalArgumentException("not a Sparkplug B payload (truncated varint)");
                }
                byte x = b[pos++];
                v |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) {
                    return v;
                }
            }
            throw new IllegalArgumentException("not a Sparkplug B payload (bad varint)");
        }

        long fixed32() {
            need(4);
            long v = (b[pos] & 0xFFL) | (b[pos + 1] & 0xFFL) << 8 | (b[pos + 2] & 0xFFL) << 16 | (b[pos + 3] & 0xFFL) << 24;
            pos += 4;
            return v;
        }

        long fixed64() {
            long lo = fixed32(), hi = fixed32();
            return lo | hi << 32;
        }

        String string() {
            int len = (int) varint();
            need(len);
            String s = new String(b, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        void skip(int wire) {
            switch (wire) {
                case 0 -> varint();
                case 1 -> { need(8); pos += 8; }
                case 2 -> { int len = (int) varint(); need(len); pos += len; }
                case 5 -> { need(4); pos += 4; }
                default -> throw new IllegalArgumentException("not a Sparkplug B payload (wire type " + wire + ")");
            }
        }

        private void need(int n) {
            if (n < 0 || pos + n > end) {
                throw new IllegalArgumentException("not a Sparkplug B payload (truncated)");
            }
        }
    }

    // ------------------------------------------------------------------ encode

    /** the NCMD that asks an edge node to publish its NBIRTH / DBIRTHs again */
    public static byte[] rebirth(long now) {
        return encode(now, null, List.of(new Metric("Node Control/Rebirth", null, now, BOOLEAN, false, true)));
    }

    /** a payload with scalar metrics (Double, Boolean, String, Long values); also used by tests and the simulator */
    public static byte[] encode(long timestamp, Long seq, List<Metric> metrics) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tag(out, 1, 0);
        varint(out, timestamp);
        for (Metric m : metrics) {
            ByteArrayOutputStream mo = new ByteArrayOutputStream();
            if (m.name() != null) {
                bytes(mo, 1, m.name().getBytes(StandardCharsets.UTF_8));
            }
            if (m.alias() != null) {
                tag(mo, 2, 0);
                varint(mo, m.alias());
            }
            if (m.timestamp() != null) {
                tag(mo, 3, 0);
                varint(mo, m.timestamp());
            }
            tag(mo, 4, 0);
            varint(mo, m.datatype());
            if (m.isNull() || m.value() == null) {
                tag(mo, 7, 0);
                varint(mo, 1);
            } else {
                Object v = m.value();
                switch (m.datatype()) {
                    case BOOLEAN -> { tag(mo, 14, 0); varint(mo, Boolean.TRUE.equals(v) ? 1 : 0); }
                    case STRING, TEXT, UUID -> bytes(mo, 15, v.toString().getBytes(StandardCharsets.UTF_8));
                    case DOUBLE -> { tag(mo, 13, 1); fixed64(mo, Double.doubleToLongBits(((Number) v).doubleValue())); }
                    case FLOAT -> { tag(mo, 12, 5); fixed32(mo, Float.floatToIntBits(((Number) v).floatValue())); }
                    case INT64, UINT64, DATETIME -> { tag(mo, 11, 0); varint(mo, ((Number) v).longValue()); }
                    default -> { tag(mo, 10, 0); varint(mo, ((Number) v).longValue() & 0xFFFFFFFFL); }
                }
            }
            bytes(out, 2, mo.toByteArray());
        }
        if (seq != null) {
            tag(out, 3, 0);
            varint(out, seq);
        }
        return out.toByteArray();
    }

    private static void tag(ByteArrayOutputStream o, int field, int wire) {
        varint(o, (long) field << 3 | wire);
    }

    private static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) {
            o.write((int) (v & 0x7F) | 0x80);
            v >>>= 7;
        }
        o.write((int) v);
    }

    private static void bytes(ByteArrayOutputStream o, int field, byte[] b) {
        tag(o, field, 2);
        varint(o, b.length);
        o.writeBytes(b);
    }

    private static void fixed32(ByteArrayOutputStream o, int v) {
        for (int i = 0; i < 4; i++) {
            o.write(v >>> (8 * i) & 0xFF);
        }
    }

    private static void fixed64(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 8; i++) {
            o.write((int) (v >>> (8 * i) & 0xFF));
        }
    }
}
