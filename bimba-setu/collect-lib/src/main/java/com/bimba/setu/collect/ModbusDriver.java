package com.bimba.setu.collect;

import com.bimba.setu.config.TagModel;
import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.ModbusSlaveException;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modbus TCP client. Addresses: HR&lt;n&gt; holding register, IR&lt;n&gt; input register (signed 16-bit),
 * C&lt;n&gt; coil, DI&lt;n&gt; discrete input; optional "&lt;unit&gt;." prefix overrides the unit id. Numbers are
 * 1-based unless the connection has zeroBased=true (same convention as the Settings page and its tests).
 */
public final class ModbusDriver implements Driver {
    private static final Pattern ADDR = Pattern.compile("^(?:(\\d+)\\.)?(HR|IR|C|DI)(\\d+)$", Pattern.CASE_INSENSITIVE);

    private final String host;
    private final int port;
    private final int unitId;
    private final int timeoutMs;
    private final boolean zeroBased;
    private ModbusTCPMaster master;

    public ModbusDriver(JsonObject conn) {
        host = str(conn, "host", "localhost");
        port = (int) num(conn, "port", 502);
        unitId = (int) num(conn, "unitId", 1);
        timeoutMs = (int) Math.max(200, num(conn, "timeoutMs", 1500));
        zeroBased = conn != null && conn.has("zeroBased") && conn.get("zeroBased").getAsBoolean();
    }

    @Override
    public String describe() {
        return host + ":" + port + " unit " + unitId;
    }

    @Override
    public List<Reading> read(List<TagModel.Tag> tags) throws Exception {
        if (master == null) {
            try {
                master = new ModbusTCPMaster(host, port, timeoutMs, true);   // resolves the host name
                master.connect();
            } catch (Exception e) {
                close();
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                String why = root instanceof java.net.UnknownHostException ? "unknown host" : root.getMessage();
                throw new Exception("cannot connect to " + host + ":" + port + ": " + why, e);
            }
        }
        List<Reading> out = new ArrayList<>(tags.size());
        for (TagModel.Tag t : tags) {
            Matcher m = ADDR.matcher(t.address());
            if (!m.matches()) {
                out.add(Reading.bad("unsupported Modbus address " + t.address()));
                continue;
            }
            int unit = m.group(1) != null ? Integer.parseInt(m.group(1)) : unitId;
            String area = m.group(2).toUpperCase();
            int ref = Integer.parseInt(m.group(3)) - (zeroBased ? 0 : 1);
            if (ref < 0 || ref > 65535) {
                out.add(Reading.bad("address out of range: " + t.address()));
                continue;
            }
            try {
                out.add(Reading.ok(switch (area) {
                    case "HR" -> (double) (short) master.readMultipleRegisters(unit, ref, 1)[0].getValue();
                    case "IR" -> (double) (short) master.readInputRegisters(unit, ref, 1)[0].getValue();
                    case "C" -> master.readCoils(unit, ref, 1).getBit(0);
                    default -> master.readInputDiscretes(unit, ref, 1).getBit(0);
                }));
            } catch (ModbusSlaveException e) {
                // the device answered with an exception code (e.g. illegal address): only this tag is bad
                out.add(Reading.bad("device exception: " + e.getMessage()));
            } catch (ModbusException e) {
                close();
                throw new Exception("lost connection to " + host + ":" + port + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    @Override
    public void close() {
        if (master != null) {
            try {
                master.disconnect();
            } catch (Exception ignored) {
                // reconnect on next cycle
            }
            master = null;
        }
    }

    private static String str(JsonObject o, String k, String d) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() && !o.get(k).getAsString().isBlank() ? o.get(k).getAsString().trim() : d;
    }

    private static double num(JsonObject o, String k, double d) {
        try {
            return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : d;
        } catch (RuntimeException e) {
            return d;
        }
    }
}
