package com.bimba.setu.agents;

import com.bimba.setu.Json;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The BIMBA Site Agent installers offered for download in the console, from site-agent/dist (mounted read-only at
 * BIMBA_INSTALLER_DIR, default /installers; built by scripts/build-site-agent.ps1):
 * <ul>
 *   <li>exe: the setup wizard (also the silent installer). An UNSIGNED build gets Setu address and certificate
 *       fingerprint appended on download (site-agent/src/Preset.cs). A code-signed build is served unchanged, because
 *       appending would break its signature; the person installing types the address.</li>
 *   <li>msi: for Intune / SCCM / Group Policy. Served unchanged; the address is an MSI property (SERVER=...).</li>
 *   <li>deb: Linux agent for Debian, Ubuntu and Raspberry Pi OS (x86-64, arm64, armhf; scripts/build-linux-agent.ps1).</li>
 *   <li>tar: the same agent for any systemd Linux (RHEL, SUSE, ...), installed with its install.sh.</li>
 * </ul>
 * The Linux packages are served unchanged: the address (and certificate pin) go on the install command line.
 * release.json / release-linux.json (written by the builds) describe the files. No secret is ever put into a file.
 */
@Service
public class InstallerFile {
    public static final String EXE = "BIMBA-Site-Agent-Setup.exe";
    public static final String MSI = "BIMBA-Site-Agent.msi";
    public static final String DEB = "bimba-site-agent_all.deb";
    public static final String TAR = "bimba-site-agent-linux.tar.gz";

    private final Path dir;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> stamps = new ConcurrentHashMap<>();

    public InstallerFile() {
        String d = System.getenv("BIMBA_INSTALLER_DIR");
        dir = Path.of(d == null || d.isBlank() ? "/installers" : d);
    }

    public static String fileName(String kind) {
        return switch (kind == null ? "" : kind) {
            case "msi" -> MSI;
            case "deb" -> DEB;
            case "tar" -> TAR;
            default -> EXE;
        };
    }

    /** the installer file of a kind (it may not exist) */
    public Path path(String kind) {
        return dir.resolve(fileName(kind));
    }

    /** {exe, msi, deb, tar: {available, fileName, size, sha256, builtAt, signed}, release: {...}, releaseLinux: {...}} */
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exe", describe(EXE));
        m.put("msi", describe(MSI));
        m.put("deb", describe(DEB));
        m.put("tar", describe(TAR));
        release(m, "release", "release.json");
        release(m, "releaseLinux", "release-linux.json");
        return m;
    }

    private void release(Map<String, Object> m, String key, String file) {
        try {
            Path r = dir.resolve(file);
            if (Files.isRegularFile(r)) {
                m.put(key, Json.toJava(Files.readString(r, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
            // no release information
        }
    }

    private Map<String, Object> describe(String name) {
        Path file = dir.resolve(name);
        try {
            if (!Files.isRegularFile(file)) {
                return Map.of("available", false, "fileName", name);
            }
            long stamp = Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
            Map<String, Object> c = cache.get(name);
            if (c == null || !stamps.getOrDefault(name, -1L).equals(stamp)) {
                byte[] b = Files.readAllBytes(file);
                c = new LinkedHashMap<>();
                c.put("available", true);
                c.put("fileName", name);
                c.put("size", b.length);
                c.put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(b)));
                c.put("builtAt", Files.getLastModifiedTime(file).toInstant().toString());
                if (name.equals(EXE)) {
                    c.put("signed", authenticodeSigned(b));
                }
                cache.put(name, c);
                stamps.put(name, stamp);
            }
            return c;
        } catch (Exception e) {
            return Map.of("available", false, "fileName", name, "error", String.valueOf(e.getMessage()));
        }
    }

    /** the file to download; for an unsigned exe with the preset appended (server null = served unchanged) */
    public byte[] download(String kind, String server, String certSha256) throws IOException {
        Path file = dir.resolve(fileName(kind));
        if (!Files.isRegularFile(file)) {
            throw new IOException("installer not found");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (!EXE.equals(file.getFileName().toString()) || server == null || authenticodeSigned(bytes)) {
            return bytes;
        }
        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("server", server);
        if (certSha256 != null && server.toLowerCase(Locale.ROOT).startsWith("https://")) {
            preset.put("trustCert", certSha256);
        }
        String trailer = "\r\n<<BIMBA-PRESET:" + Base64.getEncoder().encodeToString(Json.GSON.toJson(preset)
                .getBytes(StandardCharsets.UTF_8)) + ">>\r\n";
        byte[] t = trailer.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[bytes.length + t.length];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        System.arraycopy(t, 0, out, bytes.length, t.length);
        return out;
    }

    /** true when the PE file has an Authenticode signature (certificate table in the data directory) */
    static boolean authenticodeSigned(byte[] pe) {
        try {
            ByteBuffer b = ByteBuffer.wrap(pe).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getShort(0) != 0x5A4D) {                       // "MZ"
                return false;
            }
            int nt = b.getInt(0x3C);
            if (b.getInt(nt) != 0x00004550) {                    // "PE\0\0"
                return false;
            }
            int opt = nt + 24;
            int magic = b.getShort(opt) & 0xFFFF;
            int dirs = opt + (magic == 0x20B ? 112 : 96);        // PE32+ / PE32
            int securitySize = b.getInt(dirs + 4 * 8 + 4);       // data directory 4 = certificate table
            return securitySize > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** "gw.example.com:8443" / "https://gw:8443/" -> "https://gw.example.com:8443"; null when not a usable address */
    public static String normalizeServer(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 300) {
            return null;
        }
        if (!s.matches("(?i)https?://.*")) {
            s = "https://" + s;
        }
        try {
            URI u = new URI(s);
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            if (u.getHost() == null || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null
                    || !(u.getPath() == null || u.getPath().isEmpty() || u.getPath().equals("/"))) {
                return null;
            }
            String host = u.getHost().contains(":") && !u.getHost().startsWith("[") ? "[" + u.getHost() + "]" : u.getHost();
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return null;
        }
    }

    /** "AA:BB:..." -> "aabb..." */
    public static String plainFingerprint(String colons) {
        return colons == null ? null : colons.replace(":", "").toLowerCase(Locale.ROOT);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
