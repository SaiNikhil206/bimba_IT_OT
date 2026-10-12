package com.bimba.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The computer details shared with the organization (same shape as the Windows agent's): identity, OS, hardware
 * (including the Raspberry Pi model), disks, network addresses and detected SCADA / OT software. No user names, files,
 * process data or credentials are collected.
 */
final class Inventory {
    /** Known SCADA / HMI / OT software that runs on Linux: (family, match at the start of a word in a package / service name) */
    private static final String[][] KNOWN = {
            {"Inductive Automation Ignition", "ignition"},
            {"Siemens WinCC OA", "wincc oa", "wincc_oa", "winccoa", "wincc-oa", "pvss"},
            {"Siemens Industrial Edge / SIMATIC", "industrial edge", "simatic", "iot2050"},
            {"CODESYS Control", "codesys"},
            {"Kepware Edge / KEPServerEX", "kepware", "kepserver"},
            {"Node-RED", "node-red", "nodered"},
            {"Rapid SCADA", "rapid scada", "rapidscada", "scada-server", "scadaserver"},
            {"Mango Automation", "mango automation", "mango-automation", "radix iot", "radixiot"},
            {"FUXA", "fuxa"},
            {"OpenPLC", "openplc"},
            {"ScadaBR", "scadabr"},
            {"atvise", "atvise"},
            {"Beckhoff TwinCAT", "twincat"},
            {"Revolution Pi (PiCtory)", "pictory", "revpi"},
            {"Softing edgeConnector / dataFEED", "edgeconnector", "datafeed", "softing"},
            {"Prosys OPC UA", "prosys"},
            {"Eclipse Kura / Kapua", "kura", "kapua"},
            {"MQTT broker (Mosquitto, EMQX, HiveMQ)", "mosquitto", "emqx", "hivemq"},
            {"Telegraf / InfluxDB", "telegraf", "influxdb"},
    };
    private static final Set<String> REAL_FS = Set.of("ext2", "ext3", "ext4", "xfs", "btrfs", "f2fs", "zfs", "jfs", "reiserfs",
            "vfat", "exfat", "ntfs", "ntfs3", "fuseblk");

    private Inventory() {
    }

    static Map<String, Object> collect(String serverUrl, String knownSerial) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fingerprint", fingerprint());
        m.put("hostname", hostname());
        m.put("domain", safe(Inventory::domain, ""));
        m.put("os", os());
        m.put("hardware", hardware(knownSerial));
        m.put("disks", safe(Inventory::disks, List.of()));
        m.put("network", safe(Inventory::network, List.of()));
        m.put("primaryIp", safe(() -> primaryIp(serverUrl), ""));
        m.put("timezone", ZoneId.systemDefault().getId());
        m.put("scada", safe(Inventory::scada, List.of()));
        m.put("dataSources", safe(Discovery::all, null));      // suggested in the console's "Add data source"
        m.put("platform", "linux");
        m.put("agentVersion", AgentMain.version());
        m.put("collectedAt", Instant.now().toString());
        return m;
    }

    /** SHA-256 of /etc/machine-id: stable per installation, not reversible */
    static String fingerprint() {
        String id = firstLine("/etc/machine-id");
        if (id == null || id.isBlank()) {
            id = firstLine("/var/lib/dbus/machine-id");
        }
        if (id == null || id.isBlank()) {
            id = hostname();
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(("bimba-site-agent:" + id.trim().toLowerCase(Locale.ROOT))
                    .getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hostname() {
        String h = firstLine("/proc/sys/kernel/hostname");
        if (h == null || h.isBlank()) {
            h = System.getenv("HOSTNAME");
        }
        if (h == null || h.isBlank()) {
            h = safe(() -> InetAddress.getLocalHost().getHostName(), "linux");
        }
        return h.trim();
    }

    private static String domain() {
        String d = firstLine("/proc/sys/kernel/domainname");
        if (d != null && !d.isBlank() && !d.equals("(none)")) {
            return d.trim();
        }
        for (String line : lines("/etc/resolv.conf")) {
            String[] p = line.trim().split("\\s+");
            if (p.length > 1 && (p[0].equals("domain") || p[0].equals("search"))) {
                return p[1];
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ OS + hardware

    private static Map<String, Object> os() {
        Map<String, String> rel = keyValues("/etc/os-release");
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", rel.getOrDefault("PRETTY_NAME", rel.getOrDefault("NAME", "Linux")));
        o.put("version", rel.getOrDefault("VERSION_ID", ""));
        o.put("build", System.getProperty("os.version"));          // kernel
        o.put("arch", arch());
        o.put("id", rel.getOrDefault("ID", ""));
        o.put("kernel", "Linux " + System.getProperty("os.version"));
        String up = firstLine("/proc/uptime");
        if (up != null) {
            try {
                double seconds = Double.parseDouble(up.split("\\s+")[0]);
                o.put("lastBoot", Instant.now().minusMillis((long) (seconds * 1000)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
            } catch (RuntimeException ignored) {
                // no boot time
            }
        }
        return o;
    }

    /** x86_64, aarch64 (Raspberry Pi OS 64-bit), armv7l / armv6l (32-bit) ... as uname -m says it */
    static String arch() {
        String m = run(2, "uname", "-m");
        if (m != null && !m.isBlank()) {
            return m.trim();
        }
        String a = System.getProperty("os.arch", "");
        return switch (a) {
            case "amd64" -> "x86_64";
            case "arm" -> "armv7l";
            default -> a;
        };
    }

    private static Map<String, Object> hardware(String knownSerial) {
        Map<String, Object> h = new LinkedHashMap<>();
        String dtModel = clean(firstLine("/proc/device-tree/model"));              // Raspberry Pi and other ARM boards
        Map<String, String> cpu = cpuInfo();
        String vendor = clean(firstLine("/sys/class/dmi/id/sys_vendor"));
        String product = clean(firstLine("/sys/class/dmi/id/product_name"));
        if (dtModel != null) {
            h.put("manufacturer", dtModel.startsWith("Raspberry Pi") ? "Raspberry Pi Ltd" : vendor);
            h.put("model", dtModel);
        } else {
            h.put("manufacturer", vendor);
            h.put("model", product);
        }
        String serial = clean(firstLine("/proc/device-tree/serial-number"));
        if (serial == null) {
            serial = cpu.get("Serial");
        }
        if (serial == null) {
            serial = clean(firstLine("/sys/class/dmi/id/product_serial"));            // root only
        }
        h.put("serialNumber", serial != null ? serial : knownSerial);
        h.put("cpu", cpuName(cpu));
        h.put("cores", cores());
        h.put("logicalProcessors", Runtime.getRuntime().availableProcessors());
        long kb = 0;
        for (String line : lines("/proc/meminfo")) {
            if (line.startsWith("MemTotal:")) {
                kb = Long.parseLong(line.replaceAll("\\D+", ""));
            }
        }
        h.put("memoryGb", Math.round(kb / 1024.0 / 1024.0 * 10) / 10.0);
        return h;
    }

    /** the serial number as root sees it (stored at install: the service account cannot read DMI) */
    static String hardwareSerial() {
        Object s = hardware(null).get("serialNumber");
        return s == null ? null : s.toString();
    }

    private static Map<String, String> cpuInfo() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : lines("/proc/cpuinfo")) {
            int i = line.indexOf(':');
            if (i > 0) {
                m.putIfAbsent(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        }
        return m;
    }

    /** ARM cores by "CPU part" (ARM Ltd. implementer 0x41) */
    private static final Map<String, String> ARM_PARTS = Map.ofEntries(
            Map.entry("0xb76", "ARM1176"), Map.entry("0xc07", "Cortex-A7"), Map.entry("0xc08", "Cortex-A8"),
            Map.entry("0xc09", "Cortex-A9"), Map.entry("0xc0f", "Cortex-A15"), Map.entry("0xd03", "Cortex-A53"),
            Map.entry("0xd04", "Cortex-A35"), Map.entry("0xd05", "Cortex-A55"), Map.entry("0xd07", "Cortex-A57"),
            Map.entry("0xd08", "Cortex-A72"), Map.entry("0xd09", "Cortex-A73"), Map.entry("0xd0a", "Cortex-A75"),
            Map.entry("0xd0b", "Cortex-A76"), Map.entry("0xd0c", "Neoverse-N1"), Map.entry("0xd40", "Neoverse-V1"),
            Map.entry("0xd41", "Cortex-A78"), Map.entry("0xd49", "Neoverse-N2"));

    private static String cpuName(Map<String, String> cpu) {
        String name = cpu.get("model name");
        if (name != null && !name.matches("(?i)ARMv\\d.*")) {
            return name;
        }
        String part = cpu.get("CPU part");
        if (part != null && ARM_PARTS.containsKey(part.toLowerCase(Locale.ROOT))) {
            return "ARM " + ARM_PARTS.get(part.toLowerCase(Locale.ROOT)) + (cpu.containsKey("Hardware") ? " (" + cpu.get("Hardware") + ")" : "");
        }
        return name != null ? name : cpu.getOrDefault("Hardware", System.getProperty("os.arch"));
    }

    private static int cores() {
        Set<String> ids = new LinkedHashSet<>();
        String phys = "0";
        for (String line : lines("/proc/cpuinfo")) {
            if (line.startsWith("physical id")) {
                phys = line.substring(line.indexOf(':') + 1).trim();
            } else if (line.startsWith("core id")) {
                ids.add(phys + "/" + line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return ids.isEmpty() ? Runtime.getRuntime().availableProcessors() : ids.size();
    }

    // ------------------------------------------------------------------ disks + network

    private static List<Object> disks() throws IOException {
        List<Object> out = new ArrayList<>();
        Set<String> devices = new LinkedHashSet<>();
        for (String line : lines("/proc/mounts")) {
            String[] p = line.split("\\s+");
            if (p.length < 3 || !REAL_FS.contains(p[2]) || p[1].startsWith("/snap/") || p[1].startsWith("/boot")
                    || p[1].startsWith("/var/lib/docker") || !devices.add(p[0])) {
                continue;
            }
            String mount = p[1].replace("\\040", " ");
            try {
                FileStore fs = Files.getFileStore(Path.of(mount));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("drive", mount);
                d.put("sizeGb", Math.round(fs.getTotalSpace() / 1073741824.0 * 10) / 10.0);
                d.put("freeGb", Math.round(fs.getUsableSpace() / 1073741824.0 * 10) / 10.0);
                d.put("type", p[2]);
                out.add(d);
            } catch (IOException | RuntimeException ignored) {
                // not readable
            }
        }
        return out;
    }

    private static List<Object> network() throws IOException {
        Map<String, String> gateways = defaultGateways();
        List<Object> out = new ArrayList<>();
        for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            String name = n.getName();
            if (!n.isUp() || n.isLoopback() || n.isVirtual() || name.startsWith("veth") || name.startsWith("docker")
                    || name.startsWith("br-") || name.startsWith("virbr") || name.startsWith("cni") || name.startsWith("flannel")) {
                continue;
            }
            List<String> v4 = new ArrayList<>(), v6 = new ArrayList<>();
            for (InterfaceAddress a : n.getInterfaceAddresses()) {
                InetAddress ip = a.getAddress();
                if (ip instanceof Inet4Address) {
                    v4.add(ip.getHostAddress());
                } else if (ip instanceof Inet6Address i6 && !i6.isLinkLocalAddress()) {
                    v6.add(ip.getHostAddress().replaceFirst("%.*$", ""));
                }
            }
            byte[] mac = n.getHardwareAddress();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; mac != null && i < mac.length; i++) {
                sb.append(i == 0 ? "" : ":").append(String.format("%02X", mac[i]));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", n.getDisplayName());
            m.put("mac", sb.toString());
            m.put("ipv4", v4);
            m.put("ipv6", v6);
            m.put("gateway", gateways.get(name));
            out.add(m);
        }
        return out;
    }

    /** interface -> default gateway from /proc/net/route (little-endian hex) */
    private static Map<String, String> defaultGateways() {
        Map<String, String> m = new TreeMap<>();
        for (String line : lines("/proc/net/route")) {
            String[] p = line.trim().split("\\s+");
            if (p.length > 2 && p[1].equals("00000000") && !p[2].equals("00000000") && p[2].length() == 8) {
                long g = Long.parseLong(p[2], 16);
                m.putIfAbsent(p[0], (g & 0xff) + "." + (g >> 8 & 0xff) + "." + (g >> 16 & 0xff) + "." + (g >> 24 & 0xff));
            }
        }
        return m;
    }

    /** the local address used to reach Setu (falls back to the first address of an interface with a default route) */
    private static String primaryIp(String serverUrl) throws Exception {
        if (serverUrl != null && !serverUrl.isBlank()) {
            URI u = URI.create(serverUrl);
            for (InetAddress target : InetAddress.getAllByName(u.getHost())) {
                if (target instanceof Inet4Address && !target.isLoopbackAddress()) {
                    try (DatagramSocket s = new DatagramSocket()) {
                        s.connect(new InetSocketAddress(target, u.getPort() > 0 ? u.getPort() : 443));   // picks the route, sends nothing
                        return s.getLocalAddress().getHostAddress();
                    }
                }
            }
        }
        Map<String, String> gw = defaultGateways();
        for (String iface : gw.keySet()) {
            NetworkInterface n = NetworkInterface.getByName(iface);
            if (n != null) {
                for (InterfaceAddress a : n.getInterfaceAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) {
                        return a.getAddress().getHostAddress();
                    }
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ SCADA / OT software

    /** installed packages (dpkg / rpm), systemd services and /opt folders whose names match a known SCADA product */
    static List<Object> scada() {
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        // dpkg: Package / Version / Maintainer / Status blocks
        Map<String, String> pkg = new LinkedHashMap<>();
        List<String> status = lines("/var/lib/dpkg/status");
        status.add("");
        for (String line : status) {
            if (line.isEmpty()) {
                String name = pkg.get("Package");
                String family = familyOf(name);
                if (family != null && pkg.getOrDefault("Status", "").endsWith(" installed")) {
                    found.putIfAbsent("pkg:" + name, entry(family, name, pkg.get("Version"), pkg.get("Maintainer"), "installed"));
                }
                pkg.clear();
            } else if (!line.startsWith(" ") && line.indexOf(':') > 0) {
                pkg.put(line.substring(0, line.indexOf(':')), line.substring(line.indexOf(':') + 1).trim());
            }
        }
        // rpm (RHEL, SUSE, ...)
        if (Files.isDirectory(Path.of("/var/lib/rpm")) || Files.isDirectory(Path.of("/usr/lib/sysimage/rpm"))) {
            String rpm = run(10, "rpm", "-qa", "--qf", "%{NAME}\t%{VERSION}\t%{VENDOR}\n");
            for (String line : rpm == null ? new String[0] : rpm.split("\n")) {
                String[] p = line.split("\t");
                String family = p.length > 0 ? familyOf(p[0]) : null;
                if (family != null) {
                    found.putIfAbsent("pkg:" + p[0], entry(family, p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : "", "installed"));
                }
            }
        }
        // systemd services with their state
        String units = run(10, "systemctl", "list-units", "--type=service", "--all", "--no-legend", "--plain", "--no-pager");
        for (String line : units == null ? new String[0] : units.split("\n")) {
            String[] p = line.trim().split("\\s+", 5);
            if (p.length < 4 || !p[0].endsWith(".service")) {
                continue;
            }
            String unit = p[0].substring(0, p[0].length() - ".service".length());
            String description = p.length > 4 ? p[4] : unit;
            String family = familyOf(unit) != null ? familyOf(unit) : familyOf(description);
            if (family != null && !unit.equals(Paths.SERVICE)) {
                Map<String, Object> e = entry(family, description, null, null, "service");
                e.put("service", unit);
                e.put("state", p[3]);               // running / exited / dead / failed
                found.put("service:" + unit, e);
            }
        }
        // programs unpacked under /opt (Ignition, CODESYS, Kepware Edge often install there)
        for (String dir : new String[]{"/opt", "/usr/local"}) {
            try (var s = Files.list(Path.of(dir))) {
                for (Path p : s.filter(Files::isDirectory).toList()) {
                    String name = p.getFileName().toString();
                    String family = familyOf(name);
                    if (family != null && found.values().stream().noneMatch(v -> family.equals(v.get("family")))) {
                        Map<String, Object> e = entry(family, name, null, null, "folder");
                        e.put("path", p.toString());
                        found.put("dir:" + p, e);
                    }
                }
            } catch (IOException ignored) {
                // no such folder
            }
        }
        List<Object> out = new ArrayList<>(found.values());
        out.sort((a, b) -> {
            Map<?, ?> x = (Map<?, ?>) a, y = (Map<?, ?>) b;
            int c = String.valueOf(x.get("family")).compareTo(String.valueOf(y.get("family")));
            return c != 0 ? c : String.valueOf(x.get("source")).compareTo(String.valueOf(y.get("source")));
        });
        return out;
    }

    private static Map<String, Object> entry(String family, String product, String version, String vendor, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("family", family);
        m.put("product", product);
        m.put("version", version == null ? "" : version);
        m.put("vendor", vendor == null ? "" : vendor);
        m.put("source", source);
        return m;
    }

    /** match at the start of a word only ("Ovation" must not match "Innovation"); the end may run on ("KEPServerEX") */
    static String familyOf(String name) {
        if (name == null || name.isBlank() || name.toLowerCase(Locale.ROOT).contains("bimba")) {
            return null;
        }
        for (String[] k : KNOWN) {
            for (int i = 1; i < k.length; i++) {
                if (Pattern.compile("(?<![a-z0-9])" + Pattern.quote(k[i]), Pattern.CASE_INSENSITIVE).matcher(name).find()) {
                    return k[0];
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace("\u0000", "").trim();
        return t.isEmpty() || t.equalsIgnoreCase("To be filled by O.E.M.") || t.equalsIgnoreCase("Default string")
                || t.equalsIgnoreCase("System Product Name") ? null : t;
    }

    static String firstLine(String path) {
        try {
            List<String> l = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
            return l.isEmpty() ? "" : l.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> lines(String path) {
        try {
            return new ArrayList<>(Files.readAllLines(Path.of(path), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** KEY=value files such as /etc/os-release (quotes removed) */
    static Map<String, String> keyValues(String path) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : lines(path)) {
            int i = line.indexOf('=');
            if (i > 0 && !line.startsWith("#")) {
                m.put(line.substring(0, i).trim(), line.substring(i + 1).trim().replaceAll("^[\"']|[\"']$", ""));
            }
        }
        return m;
    }

    /** runs a command and returns its output, or null when it is missing, fails or takes too long */
    static String run(int timeoutSeconds, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        synchronized (sb) {
                            sb.append(l).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            reader.join(1000);
            synchronized (sb) {
                return p.exitValue() == 0 ? sb.toString() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private interface Risky<T> {
        T get() throws Exception;
    }

    private static <T> T safe(Risky<T> f, T fallback) {
        try {
            return f.get();
        } catch (Exception e) {
            return fallback;
        }
    }
}
