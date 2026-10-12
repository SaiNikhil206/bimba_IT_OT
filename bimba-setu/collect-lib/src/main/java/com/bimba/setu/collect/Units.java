package com.bimba.setu.collect;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Engineering unit conversion: a tag whose device reports in one unit (sourceUnit) is delivered in its own unit
 * (unit / engUnit), e.g. degF -> degC or psi -> bar. Units convert only within their quantity (temperature, pressure,
 * flow, ...). Names are matched exactly first, then ignoring case when that is unambiguous (MW and mW are not).
 */
public final class Units {
    /** value in the quantity's base unit = value * factor + offset */
    private record Unit(String quantity, double factor, double offset) {
    }

    private static final Map<String, Unit> EXACT = new HashMap<>();
    private static final Map<String, Unit> FOLDED = new HashMap<>();
    private static final Set<String> AMBIGUOUS = new HashSet<>();

    static {
        // temperature (base degC)
        def("temperature", 1, 0, "degC", "°C", "C", "celsius", "deg C");
        def("temperature", 5.0 / 9.0, -160.0 / 9.0, "degF", "°F", "F", "fahrenheit", "deg F");
        def("temperature", 1, -273.15, "K", "kelvin");
        // pressure (base Pa)
        def("pressure", 1, 0, "Pa");
        def("pressure", 100, 0, "hPa");
        def("pressure", 1e3, 0, "kPa");
        def("pressure", 1e6, 0, "MPa");
        def("pressure", 1e5, 0, "bar");
        def("pressure", 100, 0, "mbar");
        def("pressure", 6894.757293168, 0, "psi");
        def("pressure", 101325, 0, "atm");
        def("pressure", 133.322387415, 0, "mmHg");
        def("pressure", 249.08891, 0, "inH2O");
        def("pressure", 9806.65, 0, "mH2O");
        // volume flow (base m3/s)
        def("volume flow", 1, 0, "m3/s", "m³/s");
        def("volume flow", 1.0 / 3600, 0, "m3/h", "m³/h");
        def("volume flow", 1e-3, 0, "L/s", "l/s");
        def("volume flow", 1e-3 / 60, 0, "L/min", "l/min", "lpm");
        def("volume flow", 1e-3 / 3600, 0, "L/h", "l/h");
        def("volume flow", 3.785411784e-3 / 60, 0, "gpm", "gal/min", "USgpm");
        def("volume flow", 0.028316846592 / 60, 0, "cfm", "ft3/min", "ft³/min");
        // mass flow (base kg/s)
        def("mass flow", 1, 0, "kg/s");
        def("mass flow", 1.0 / 3600, 0, "kg/h");
        def("mass flow", 1000.0 / 3600, 0, "t/h");
        def("mass flow", 0.45359237 / 3600, 0, "lb/h");
        // length (base m)
        def("length", 1, 0, "m");
        def("length", 1e-3, 0, "mm");
        def("length", 1e-2, 0, "cm");
        def("length", 1e3, 0, "km");
        def("length", 1e-6, 0, "µm", "um");
        def("length", 0.0254, 0, "in");
        def("length", 0.3048, 0, "ft");
        // volume (base m3)
        def("volume", 1, 0, "m3", "m³");
        def("volume", 1e-3, 0, "L", "l", "litre", "liter");
        def("volume", 1e-6, 0, "mL", "ml");
        def("volume", 3.785411784e-3, 0, "gal", "USgal");
        def("volume", 0.028316846592, 0, "ft3", "ft³");
        // mass (base kg)
        def("mass", 1, 0, "kg");
        def("mass", 1e-3, 0, "g");
        def("mass", 1e3, 0, "t", "tonne");
        def("mass", 0.45359237, 0, "lb");
        def("mass", 0.028349523125, 0, "oz");
        // speed (base m/s)
        def("speed", 1, 0, "m/s");
        def("speed", 1.0 / 3.6, 0, "km/h");
        def("speed", 0.44704, 0, "mph");
        def("speed", 0.3048, 0, "ft/s");
        def("speed", 0.00508, 0, "ft/min");
        def("speed", 1.0 / 60, 0, "m/min");
        // rotation (base rpm)
        def("rotation", 1, 0, "rpm", "RPM", "1/min");
        def("rotation", 60, 0, "rps");
        // frequency (base Hz)
        def("frequency", 1, 0, "Hz");
        def("frequency", 1e3, 0, "kHz");
        // power (base W)
        def("power", 1e-3, 0, "mW");
        def("power", 1, 0, "W");
        def("power", 1e3, 0, "kW");
        def("power", 1e6, 0, "MW");
        def("power", 745.69987158227, 0, "hp");
        def("power", 0.29307107, 0, "BTU/h");
        // energy (base J)
        def("energy", 1, 0, "J");
        def("energy", 1e3, 0, "kJ");
        def("energy", 1e6, 0, "MJ");
        def("energy", 3600, 0, "Wh");
        def("energy", 3.6e6, 0, "kWh");
        def("energy", 3.6e9, 0, "MWh");
        def("energy", 1055.05585262, 0, "BTU");
        // electrical
        def("voltage", 1, 0, "V");
        def("voltage", 1e-3, 0, "mV");
        def("voltage", 1e3, 0, "kV");
        def("current", 1, 0, "A");
        def("current", 1e-3, 0, "mA");
        def("current", 1e3, 0, "kA");
        // time (base s)
        def("time", 1, 0, "s", "sec");
        def("time", 1e-3, 0, "ms");
        def("time", 60, 0, "min");
        def("time", 3600, 0, "h", "hr");
        // ratio (base fraction)
        def("ratio", 1e-2, 0, "%", "percent");
        def("ratio", 1e-6, 0, "ppm");
    }

    private static void def(String quantity, double factor, double offset, String... names) {
        Unit u = new Unit(quantity, factor, offset);
        for (String n : names) {
            EXACT.put(n, u);
            String k = n.toLowerCase(Locale.ROOT);
            Unit prev = FOLDED.putIfAbsent(k, u);
            if (prev != null && !prev.equals(u)) {
                AMBIGUOUS.add(k);
            }
        }
    }

    private Units() {
    }

    private static Unit find(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        Unit u = EXACT.get(n);
        if (u != null) {
            return u;
        }
        String k = n.toLowerCase(Locale.ROOT);
        return AMBIGUOUS.contains(k) ? null : FOLDED.get(k);
    }

    public static boolean known(String name) {
        return find(name) != null;
    }

    /** the quantity a unit measures ("pressure"), null for an unknown unit */
    public static String quantity(String name) {
        Unit u = find(name);
        return u == null ? null : u.quantity();
    }

    /** both units are known and measure the same quantity */
    public static boolean convertible(String from, String to) {
        Unit a = find(from);
        Unit b = find(to);
        return a != null && b != null && a.quantity().equals(b.quantity());
    }

    /** value in unit from -> unit to; IllegalArgumentException when they do not convert */
    public static double convert(double value, String from, String to) {
        Unit a = find(from);
        Unit b = find(to);
        if (a == null || b == null || !a.quantity().equals(b.quantity())) {
            throw new IllegalArgumentException("cannot convert " + from + " to " + to);
        }
        if (a.equals(b)) {
            return value;
        }
        return (value * a.factor() + a.offset() - b.offset()) / b.factor();
    }

    /** why from -> to cannot be converted, null when it can */
    public static String problem(String from, String to) {
        if (!known(from)) {
            return "unknown unit \"" + from + "\"";
        }
        if (!known(to)) {
            return "unknown unit \"" + to + "\"";
        }
        if (!convertible(from, to)) {
            return from + " (" + quantity(from) + ") does not convert to " + to + " (" + quantity(to) + ")";
        }
        return null;
    }
}
