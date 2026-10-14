package com.bimba.agent;

import com.bimba.agent.SetuClient.SetuException;
import com.bimba.agent.SetuClient.Json;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * bimba-site-agent: the BIMBA Site Agent for Linux (x86-64, Raspberry Pi arm64 / armhf, any Java 17+ platform).
 * Same Setu protocol and exit codes as the Windows BIMBA-Site-Agent-Setup.exe.
 */
public final class AgentMain {
    private static final String USAGE = """
            BIMBA Site Agent %s for Linux

              bimba-site-agent install --server <setu> --token <enrollment token> [options]
              bimba-site-agent install --server <setu> --email <e-mail> [--org <slug>] [--password-stdin] [options]
              bimba-site-agent test --server <setu> (--token <t> | --email <e-mail> ...)   check, install nothing
              bimba-site-agent status | inventory | version
              bimba-site-agent writes [on | off]           machine commands from Setu on this computer (off = always refused)
              bimba-site-agent uninstall [--keep-data]     tell Setu, stop the service, remove the registration
              bimba-site-agent run                         run in the foreground (systemd starts it this way; containers too)

              options: --name <display name>  --site <site / location>
                       --trust-cert <sha256 of a self-signed Setu certificate (shown in the Setu console)>
              The token, server, certificate, site and name can also come from BIMBA_ENROLL_TOKEN, BIMBA_SERVER,
              BIMBA_TRUST_CERT, BIMBA_SITE and BIMBA_NAME (unattended roll-out, containers). `run` enrolls with them
              when the computer is not registered yet. install / uninstall need root (sudo).
              Logs: journalctl -u bimba-site-agent
              Exit codes: 0 ok, 1 failed, 2 usage, 3 credentials refused, 4 choose --org, 5 not root.""";

    private AgentMain() {
    }

    static String version() {
        String v = AgentMain.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    public static void main(String[] args) {
        System.exit(exec(args));
    }

    static int exec(String[] args) {
        String cmd = args.length > 0 ? args[0].replaceFirst("^-+", "").toLowerCase(Locale.ROOT) : "help";
        try {
            Map<String, String> opt = "writes".equals(cmd) ? Map.of() : options(args);     // writes on | off: a word, no options
            switch (cmd) {
                case "install":
                    return install(opt, Mode.INSTALL);
                case "enroll":
                    return install(opt, Mode.ENROLL);
                case "test":
                    return install(opt, Mode.TEST);
                case "run":
                case "service":
                    return run();
                case "status":
                    return status();
                case "writes":
                    return writes(args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null);
                case "inventory":
                    System.out.println(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
                            .toJson(Inventory.collect(opt.get("server"), null)));
                    return 0;
                case "uninstall":
                    if (!isRoot()) {
                        return notRoot();
                    }
                    uninstall(!opt.containsKey("keep-data"));
                    System.out.println("BIMBA Site Agent registration removed.");
                    return 0;
                case "uninstall-notify":              // package removal: tell Setu, keep the files
                    if (!isRoot()) {
                        return notRoot();
                    }
                    notifyUninstall();
                    return 0;
                case "version":
                    System.out.println(version());
                    return 0;
                case "help":
                case "h":
                    System.out.println(String.format(USAGE, version()));
                    return 0;
                default:
                    System.err.println(String.format(USAGE, version()));
                    return 2;
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        } catch (SetuException e) {
            System.err.println("error: " + e.getMessage());
            if (e.untrustedCertSha256 != null) {
                System.err.println("  certificate SHA-256: " + e.untrustedCertSha256
                        + "\n  if this is your gateway's certificate, add --trust-cert " + e.untrustedCertSha256);
            }
            if (e.status == 409) {
                StringBuilder sb = new StringBuilder();
                e.orgs().forEach((slug, name) -> sb.append(sb.length() == 0 ? "" : ", ").append(slug).append(" (").append(name).append(")"));
                System.err.println("  organizations: " + sb + "\n  add --org <slug>");
            }
            return e.status == 409 ? 4 : e.status == 401 || e.status == 403 ? 3 : 1;
        } catch (Exception e) {
            System.err.println("error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 1;
        }
    }

    /** AUTO: `run` without a configuration, enrolling from BIMBA_* variables as whatever user the container uses */
    private enum Mode { INSTALL, ENROLL, TEST, AUTO }

    /**
     * install: enroll + configuration + start the service; enroll: configuration only (package scripts);
     * test: enrollment, one heartbeat and, over HTTPS, the client certificate round trip, with nothing saved.
     */
    private static int install(Map<String, String> opt, Mode mode) throws Exception {
        if ((mode == Mode.INSTALL || mode == Mode.ENROLL) && !isRoot()) {
            return notRoot();
        }
        String server = opt.getOrDefault("server", env("BIMBA_SERVER"));
        if (server == null) {
            throw new IllegalArgumentException("--server is required (Setu's HTTPS address, for example https://setu.example.com:8443)");
        }
        String pin = opt.getOrDefault("trust-cert", env("BIMBA_TRUST_CERT"));
        Map<String, Object> auth = new LinkedHashMap<>();
        String user = opt.containsKey("email") ? opt.get("email") : opt.get("username");
        if (user != null) {
            auth.put("type", "password");
            auth.put("email", user.trim());
            auth.put("password", opt.containsKey("password-stdin") ? readLine() : readSecret("Password for " + user + ": "));
            if (opt.containsKey("org")) {
                auth.put("org", opt.get("org"));
            }
        } else {
            String token = opt.getOrDefault("token", env("BIMBA_ENROLL_TOKEN"));
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("--token or --email is required");
            }
            auth.put("type", "token");
            auth.put("token", token.trim());
        }
        if (SetuClient.isPlainHttpToRemote(server)) {
            System.out.println("warning: " + server + " is plain HTTP, credentials are sent unencrypted. Use Setu's HTTPS address (port 8443).");
        }
        String site = opt.getOrDefault("site", env("BIMBA_SITE"));
        String name = opt.getOrDefault("name", env("BIMBA_NAME"));

        SetuClient client = new SetuClient(server, pin, null);
        Map<String, Object> machine = Inventory.collect(client.server, null);
        System.out.println("Registering " + machine.get("hostname") + " with " + client.server + " ...");
        JsonObject enrolled = client.enroll(auth, name == null || name.isBlank() ? (String) machine.get("hostname") : name, site, machine);
        AgentConfig cfg = configFrom(client, enrolled, site, pin);
        cfg.hardwareSerial = (String) ((Map<?, ?>) machine.get("hardware")).get("serialNumber");
        System.out.println("Registered to organization " + cfg.orgName + " (" + cfg.orgSlug + ") as \"" + cfg.displayName
                + "\", agent " + cfg.agentId);
        if (mode == Mode.TEST) {
            client.heartbeat(cfg, machine, "running", null);
            System.out.println("Heartbeat accepted (agent secret).");
            if (Json.bool(enrolled, "certificates") && client.server.startsWith("https://")) {
                testCertificate(cfg);
            }
            System.out.println("Test only: nothing was installed (the computer shows as offline after a few minutes).");
            return 0;
        }
        AgentConfig old = AgentConfig.tryLoad();
        if (old != null && old.certFile != null) {
            Files.deleteIfExists(old.certPath());        // a new enrollment starts with the new secret
        }
        cfg.spoolMaxMb = old != null ? old.spoolMaxMb : null;
        cfg.save();
        Files2.privateDir(Paths.dataDir());
        Files.deleteIfExists(Paths.uninstallMarker());     // registered again after an uninstall
        boolean owned = Files2.chownTree(Paths.configDir(), Paths.USER) & Files2.chownTree(Paths.dataDir(), Paths.USER);
        System.out.println("Configuration saved in " + Paths.configFile() + (owned ? " (owner " + Paths.USER + ")" : ""));
        if (mode == Mode.ENROLL || mode == Mode.AUTO) {
            return 0;
        }
        if (!Platform.systemd()) {
            System.out.println("No systemd on this computer: start the agent with `bimba-site-agent run` (as " + Paths.USER
                    + ") from your init system or container.");
            return 0;
        }
        if (!Platform.unitInstalled()) {
            System.out.println("The systemd unit " + Paths.SERVICE + ".service is not installed: install the .deb package or run install.sh.");
            return 1;
        }
        Platform.systemctl("enable", Paths.SERVICE);
        Platform.systemctl("restart", Paths.SERVICE);
        System.out.println("BIMBA Site Agent installed and running (systemctl status " + Paths.SERVICE + ", journalctl -u " + Paths.SERVICE + ").");
        return 0;
    }

    static AgentConfig configFrom(SetuClient client, JsonObject enrolled, String site, String pin) {
        JsonObject org = Json.obj(enrolled, "org");
        AgentConfig c = new AgentConfig();
        c.server = client.server;
        c.agentId = Json.str(enrolled, "agentId");
        c.secret = Json.str(enrolled, "agentSecret");
        c.orgSlug = Json.str(org, "slug");
        c.orgName = Json.str(org, "name");
        c.displayName = Json.str(enrolled, "displayName");
        c.site = site == null ? "" : site;
        c.heartbeatSeconds = Json.integer(enrolled, "heartbeatSeconds", 60);
        c.pinnedCertSha256 = pin == null || pin.isBlank() ? null : pin.trim().toLowerCase(Locale.ROOT).replace(":", "");
        c.enrolledAt = Instant.now().toString();
        return c;
    }

    /** the service's certificate steps in memory: first certificate with the secret, secret refused, renewal */
    private static void testCertificate(AgentConfig cfg) throws Exception {
        Session s = new Session(cfg, false);
        s.updateCertificate();
        System.out.println("Client certificate issued and used (mutual TLS): " + s.credential().certificate().getSubjectX500Principal()
                + ", valid until " + s.credential().certificate().getNotAfter().toInstant());
        try {
            new SetuClient(cfg.server, cfg.pinnedCertSha256, null).heartbeat(cfg, null, "running", null);
            System.out.println("WARNING: Setu still accepts the agent secret.");
        } catch (SetuException e) {
            if (e.status != 401) {
                throw e;
            }
            System.out.println("Agent secret refused now that the computer has a certificate (as intended).");
        }
        String first = cfg.certSha256;
        s.updateCertificate();
        System.out.println("Certificate renewed and used: " + first.substring(0, 16) + "... -> " + cfg.certSha256.substring(0, 16) + "...");
        s.heartbeat(null, null);
        System.out.println("Heartbeat accepted (renewed certificate).");
    }

    /** the service (foreground); enrolls from BIMBA_* variables when there is no configuration yet (containers) */
    private static int run() throws Exception {
        if (!AgentConfig.exists() && env("BIMBA_SERVER") != null && env("BIMBA_ENROLL_TOKEN") != null) {
            Log.info("not registered yet: enrolling with " + env("BIMBA_SERVER"));
            int rc = install(new LinkedHashMap<>(), Mode.AUTO);
            if (rc != 0) {
                return rc;
            }
        }
        AgentConfig cfg;
        try {
            cfg = AgentConfig.load();
        } catch (Exception e) {
            Log.error("cannot read " + Paths.configFile() + ": " + e.getMessage()
                    + ". Register this computer with: sudo bimba-site-agent install --server <setu> --token <token>");
            return AgentService.REVOKED;
        }
        if (Files.exists(Paths.uninstallMarker())) {
            Log.warn("this computer was removed (" + Paths.uninstallMarker() + "); register it again with bimba-site-agent install");
            return AgentService.REVOKED;
        }
        return new AgentService().run(cfg);
    }

    private static int status() {
        AgentConfig cfg = AgentConfig.tryLoad();
        String svc = Platform.systemd() ? Platform.output("systemctl", "is-active", Paths.SERVICE) : null;
        if (cfg == null && !AgentConfig.exists()) {
            System.out.println("BIMBA Site Agent " + version() + " is not registered on this computer" + (isRoot() ? "." : " (or run with sudo)."));
            if (svc != null) {
                System.out.println("service:      " + svc.trim());
            }
            return 1;
        }
        System.out.println("version:      " + version() + " (Java " + System.getProperty("java.version") + ", " + Inventory.arch() + ")");
        System.out.println("service:      " + (svc == null ? "no systemd" : svc.trim()));
        if (cfg == null) {
            System.out.println("configuration: run with sudo to read " + Paths.configFile());
            return 0;
        }
        System.out.println("organization: " + cfg.orgName + " (" + cfg.orgSlug + ")");
        System.out.println("computer:     " + cfg.displayName + (cfg.site == null || cfg.site.isEmpty() ? "" : " at " + cfg.site));
        System.out.println("gateway:      " + cfg.server + (cfg.pinnedCertSha256 != null ? " (pinned certificate)" : ""));
        System.out.println("agent id:     " + cfg.agentId);
        System.out.println("sign-in:      " + (cfg.certFile != null ? "client certificate " + cfg.certSha256 + " (valid until " + cfg.certNotAfter + ")"
                : "agent secret (the service asks for a certificate over HTTPS)"));
        System.out.println("enrolled:     " + cfg.enrolledAt);
        System.out.println("commands:     " + (cfg.writesAllowed() ? "allowed (as Setu permits)" : "refused on this computer (bimba-site-agent writes on)"));
        System.out.println("log:          journalctl -u " + Paths.SERVICE);
        return 0;
    }

    /**
     * Machine commands (values Setu writes to the machines) on this computer: shows the setting, or turns it on / off.
     * Off wins over every setting in Setu; the service reads it before each command, so no restart is needed.
     */
    private static int writes(String to) throws Exception {
        if (to != null && !isRoot()) {
            return notRoot();
        }
        AgentConfig cfg = AgentConfig.tryLoad();
        if (cfg == null) {
            System.err.println(AgentConfig.exists() ? "run with sudo to read " + Paths.configFile() : "the agent is not registered on this computer");
            return 1;
        }
        if (to == null) {
            System.out.println(cfg.writesAllowed() ? "on: machine commands from Setu are carried out (as Setu permits)"
                    : "off: machine commands from Setu are refused on this computer");
            return 0;
        }
        if (!"on".equals(to) && !"off".equals(to)) {
            throw new IllegalArgumentException("writes on | off");
        }
        cfg.setAllowWrites("on".equals(to));
        cfg.save();
        Files2.chownTree(Paths.configDir(), Paths.USER);     // saved as root: the service account must still read it
        System.out.println("Machine commands " + ("on".equals(to) ? "allowed" : "refused") + " on this computer.");
        return 0;
    }

    /** tells Setu (the computer shows as uninstalled) and deletes the certificate; files stay */
    static void notifyUninstall() {
        AgentConfig cfg = AgentConfig.tryLoad();
        if (cfg == null) {
            return;
        }
        try {
            new Session(cfg, false).client().heartbeat(cfg, null, "uninstalled", null);
            System.out.println("  told Setu that this computer is being removed");
        } catch (Exception e) {
            System.out.println("  could not notify Setu: " + e.getMessage());
        }
        try {
            if (cfg.certFile != null) {
                Files.deleteIfExists(cfg.certPath());
            }
            AgentCertificate.deleteOthers(null);
            if (Files.isDirectory(Paths.dataDir())) {
                Files.writeString(Paths.uninstallMarker(), Instant.now().toString());
            }
        } catch (Exception e) {
            System.out.println("  " + e.getMessage());
        }
    }

    private static void uninstall(boolean removeData) throws Exception {
        if (Platform.systemd() && Platform.unitInstalled()) {
            Platform.systemctl("disable", "--now", Paths.SERVICE);
        }
        notifyUninstall();
        if (removeData) {
            Files2.deleteTree(Paths.configDir());
            Files2.deleteTree(Paths.dataDir());
            System.out.println("  removed " + Paths.configDir() + " and " + Paths.dataDir());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> options(String[] args) {
        Map<String, String> o = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + args[i]);
            }
            String key = args[i].substring(2).toLowerCase(Locale.ROOT);
            boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
            o.put(key, hasValue ? args[++i] : "");
        }
        return o;
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    static boolean isRoot() {
        for (String line : Inventory.lines("/proc/self/status")) {
            if (line.startsWith("Uid:")) {
                return line.split("\\s+")[1].equals("0");
            }
        }
        return "root".equals(System.getProperty("user.name"));
    }

    private static int notRoot() {
        System.err.println("error: run this as root (sudo bimba-site-agent ...).");
        return 5;
    }

    private static String readLine() throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String l = r.readLine();
        return l == null ? "" : l;
    }

    private static String readSecret(String prompt) throws Exception {
        Console c = System.console();
        if (c == null) {
            return readLine();                     // no terminal: read a line
        }
        char[] pw = c.readPassword("%s", prompt);
        return pw == null ? "" : new String(pw);
    }
}
