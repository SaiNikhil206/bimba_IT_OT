package com.bimba.setu.delivery;

import com.bimba.setu.config.BimbaConfig;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * SFTP for {@link FileDestination}: password or private key sign-in; the server's host key is checked against the
 * fingerprint in "hostKey" (SHA256:..., as ssh-keygen -l prints it) when one is set. The session stays open between
 * files and is opened again after an error.
 */
final class SftpRemote implements FileDestination.Remote {
    private final BimbaConfig.Destination cfg;
    private final FileDestination.Address at;
    private Session session;
    private ChannelSftp sftp;
    /** the fingerprint of the server's key at the last connection */
    private volatile String seen;

    SftpRemote(BimbaConfig.Destination cfg) {
        this.cfg = cfg;
        this.at = FileDestination.Address.of(cfg.url, 22);
    }

    @Override
    public synchronized void put(String name, byte[] content) throws Exception {
        try {
            ChannelSftp c = channel();
            mkdirs(c, at.folder());
            String part = at.join(FileDestination.partName(name));
            String target = at.join(name);
            c.put(new ByteArrayInputStream(content), part, ChannelSftp.OVERWRITE);
            try {
                c.rename(part, target);
            } catch (SftpException e) {
                if (!exists(c, target)) {
                    throw e;
                }
                c.rm(target);                                         // sent before (a retry): replace it
                c.rename(part, target);
            }
        } catch (Exception e) {
            close();
            throw describe(e);
        }
    }

    @Override
    public synchronized String test() throws Exception {
        close();
        try {
            ChannelSftp c = channel();
            String folder = at.folder().isEmpty() ? c.pwd() : at.folder();
            String where = exists(c, folder) ? "folder " + folder + " is there" : "folder " + folder
                    + " does not exist yet; it is created with the first file";
            return "Signed in to " + at.host() + " as " + cfg.username + "; " + where + ". " + hostKeyNote() + " Nothing was written.";
        } catch (Exception e) {
            throw describe(e);
        } finally {
            close();
        }
    }

    private String hostKeyNote() {
        if (pinned()) {
            return "The server's host key matches.";
        }
        return "The server's host key is " + seen + ": enter it under Host key so only this server is accepted.";
    }

    private boolean pinned() {
        return cfg.hostKey != null && !cfg.hostKey.isBlank();
    }

    private ChannelSftp channel() throws Exception {
        if (sftp != null && sftp.isConnected() && session != null && session.isConnected()) {
            return sftp;
        }
        close();
        JSch jsch = new JSch();
        if (cfg.sshKey != null && !cfg.sshKey.isBlank()) {
            byte[] pass = cfg.sshKeyPassphrase == null || cfg.sshKeyPassphrase.isEmpty() ? null : cfg.sshKeyPassphrase.getBytes(StandardCharsets.UTF_8);
            jsch.addIdentity("bimba-" + cfg.name, cfg.sshKey.trim().getBytes(StandardCharsets.UTF_8), null, pass);
        }
        Session s = jsch.getSession(cfg.username, at.host(), at.port());
        if (cfg.password != null && !cfg.password.isEmpty()) {
            s.setPassword(cfg.password);
        }
        s.setHostKeyRepository(new Pinned());
        s.setConfig("StrictHostKeyChecking", pinned() ? "yes" : "no");
        s.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");
        s.setTimeout(cfg.timeoutMs);
        s.connect(cfg.timeoutMs);
        ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
        c.connect(cfg.timeoutMs);
        session = s;
        sftp = c;
        return c;
    }

    private static boolean exists(ChannelSftp c, String path) throws SftpException {
        try {
            c.stat(path);
            return true;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw e;
        }
    }

    private static void mkdirs(ChannelSftp c, String folder) throws SftpException {
        if (folder.isEmpty() || exists(c, folder)) {
            return;
        }
        StringBuilder p = new StringBuilder(folder.startsWith("/") ? "/" : "");
        for (String part : folder.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            p.append(p.length() == 0 || p.charAt(p.length() - 1) == '/' ? "" : "/").append(part);
            if (!exists(c, p.toString())) {
                c.mkdir(p.toString());
            }
        }
    }

    /** the reason in plain words: host key, sign-in, folder rights */
    private Exception describe(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof JSchException) {
            if (m.contains("reject HostKey") || m.contains("HostKey has been changed")) {
                return new java.io.IOException("The server's host key " + seen + " is not the one entered under Host key ("
                        + cfg.hostKey.trim() + "): another server answered, or its key changed.", e);
            }
            if (m.contains("Auth fail") || m.contains("Auth cancel")) {
                return new java.io.IOException("The SFTP server rejected the user name, password or key.", e);
            }
            if (m.contains("invalid privatekey")) {
                return new java.io.IOException("The private key cannot be read (OpenSSH or PEM text; is the passphrase right?).", e);
            }
            return new java.io.IOException("SFTP: " + m, e);
        }
        if (e instanceof SftpException s) {
            String what = s.id == ChannelSftp.SSH_FX_PERMISSION_DENIED ? "permission denied" : s.id == ChannelSftp.SSH_FX_NO_SUCH_FILE ? "no such folder" : m;
            return new java.io.IOException("SFTP: " + what + " (folder " + (at.folder().isEmpty() ? "of the user" : at.folder()) + ")", e);
        }
        return e;
    }

    /** "SHA256:<base64 without padding>" of an SSH public key blob, as OpenSSH prints it */
    static String fingerprint(byte[] key) {
        try {
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** the entered fingerprint in OpenSSH form (with or without SHA256:, padding ignored) */
    static String normalize(String fp) {
        String f = fp.trim();
        if (f.regionMatches(true, 0, "SHA256:", 0, 7)) {
            f = f.substring(7);
        }
        return "SHA256:" + f.replaceAll("=+$", "");
    }

    /** accepts exactly the pinned key (or, without one, any key: StrictHostKeyChecking no), and remembers what it saw */
    private final class Pinned implements HostKeyRepository {
        @Override
        public int check(String host, byte[] key) {
            seen = fingerprint(key);
            if (!pinned()) {
                return NOT_INCLUDED;
            }
            return normalize(cfg.hostKey).equals(seen) ? OK : CHANGED;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
        }

        @Override
        public void remove(String host, String type) {
        }

        @Override
        public void remove(String host, String type, byte[] key) {
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "bimba-pinned";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    @Override
    public synchronized void close() {
        if (sftp != null) {
            sftp.disconnect();
            sftp = null;
        }
        if (session != null) {
            session.disconnect();
            session = null;
        }
    }
}
