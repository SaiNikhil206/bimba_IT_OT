package com.bimba.agent;

import java.nio.file.Path;

/**
 * Where the agent keeps its files. Defaults follow the Linux file system layout; the BIMBA_AGENT_* variables move them
 * (tests, containers, a portable run as an ordinary user).
 * <pre>
 *   /opt/bimba-site-agent          program: bin/bimba-site-agent, lib/*.jar, optional runtime/ (bundled Java)
 *   /etc/bimba-site-agent          agent.json: Setu address, agent id, credential (0700 dir, 0600 file, owner bimba-agent)
 *   /var/lib/bimba-site-agent      client certificate, data sources, disk buffer, OPC UA client certificate (0700),
 *                                  update/ (an installer the service downloaded for the updater)
 *   /var/lib/bimba-site-agent-update   the root updater: updater.json (Setu address, on / off), last.json (0755, root)
 * </pre>
 */
final class Paths {
    static final String SERVICE = "bimba-site-agent";
    static final String USER = "bimba-agent";
    static final String DISPLAY_NAME = "BIMBA Site Agent";

    private Paths() {
    }

    static Path home() {
        return env("BIMBA_AGENT_HOME", "/opt/bimba-site-agent");
    }

    static Path configDir() {
        return env("BIMBA_AGENT_CONFIG_DIR", "/etc/bimba-site-agent");
    }

    static Path dataDir() {
        return env("BIMBA_AGENT_DATA_DIR", "/var/lib/bimba-site-agent");
    }

    static Path configFile() {
        return configDir().resolve("agent.json");
    }

    /** readings waiting for Setu (disk buffer) */
    static Path spoolDir() {
        return dataDir().resolve("spool");
    }

    /** the data sources from Setu (may contain OPC UA passwords: 0600) */
    static Path sourcesFile() {
        return dataDir().resolve("sources.json");
    }

    /** the edge collector's OPC UA client certificate */
    static Path edgePkiDir() {
        return dataDir().resolve("edge-pki");
    }

    /** an update the service downloaded, waiting for the root updater (update/ready.json starts it) */
    static Path updateDir() {
        return dataDir().resolve("update");
    }

    /** the root updater's own folder: its settings and the last result (readable, not writable, by the service) */
    static Path updaterDir() {
        return env("BIMBA_AGENT_UPDATER_DIR", "/var/lib/bimba-site-agent-update");
    }

    /** written when Setu has been told about an uninstall */
    static Path uninstallMarker() {
        return dataDir().resolve("uninstall-notified");
    }

    private static Path env(String name, String fallback) {
        String v = System.getenv(name);
        return Path.of(v == null || v.isBlank() ? fallback : v.trim());
    }
}
