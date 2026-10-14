package com.bimba.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the installer stores on the computer (/etc/bimba-site-agent/agent.json, readable only by the service account):
 * Setu address, agent id and, until the agent has its client certificate, the agent secret. Enrollment tokens and
 * passwords are never stored. Once the certificate has been used, the secret is removed and only the certificate's key
 * store (/var/lib/bimba-site-agent, also private) identifies the computer.
 */
final class AgentConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    String server;
    String agentId;
    /** null once the agent signs in with its certificate */
    String secret;
    String orgSlug;
    String orgName;
    String displayName;
    String site;
    int heartbeatSeconds = 60;
    /** SHA-256 (hex) of a self-signed Setu certificate the installer was told to trust, or null */
    String pinnedCertSha256;
    String enrolledAt;
    /** key store of the client certificate, a file name in the data folder, or null */
    String certFile;
    String certSha256;
    String certNotAfter;
    String keyStorePassword;
    /** read at install (as root): the service account cannot read the DMI serial number */
    String hardwareSerial;
    /** disk buffer cap in MB (default 1024; lower it on small SD cards) */
    Integer spoolMaxMb;
    /**
     * machine commands from Setu: false = refused here whatever Setu allows (null / true = as Setu permits). Only
     * `bimba-site-agent writes` changes it; the service reads it from the file before each command.
     */
    Boolean allowWrites;
    /** this copy sets allowWrites (otherwise save() keeps the file's value: the service must not undo the command) */
    transient boolean writesSet;

    boolean writesAllowed() {
        return !Boolean.FALSE.equals(allowWrites);
    }

    void setAllowWrites(boolean on) {
        allowWrites = on;
        writesSet = true;
    }

    static boolean exists() {
        return Files.isRegularFile(Paths.configFile());
    }

    static AgentConfig load() throws IOException {
        AgentConfig c = GSON.fromJson(Files.readString(Paths.configFile(), StandardCharsets.UTF_8), AgentConfig.class);
        if (c == null || c.server == null || c.agentId == null) {
            throw new IOException(Paths.configFile() + " is incomplete");
        }
        return c;
    }

    static AgentConfig tryLoad() {
        try {
            return exists() ? load() : null;
        } catch (Exception e) {
            return null;
        }
    }

    synchronized void save() throws IOException {
        if (!writesSet) {
            AgentConfig disk = tryLoad();
            if (disk != null) {
                allowWrites = disk.allowWrites;
            }
        }
        Files2.privateDir(Paths.configDir());
        Files2.writePrivate(Paths.configFile(), GSON.toJson(this) + "\n");
    }

    Path certPath() {
        return certFile == null ? null : Paths.dataDir().resolve(certFile);
    }

    long spoolMaxBytes() {
        return (spoolMaxMb == null || spoolMaxMb < 16 ? 1024L : spoolMaxMb) << 20;
    }
}
