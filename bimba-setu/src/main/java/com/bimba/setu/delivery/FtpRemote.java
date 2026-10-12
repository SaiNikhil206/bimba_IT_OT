package com.bimba.setu.delivery;

import com.bimba.setu.config.BimbaConfig;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;

/**
 * FTP for {@link FileDestination}: plain, explicit TLS (AUTH TLS, then a protected data channel) or implicit TLS
 * (FTPS, port 990), with the destination's own certificate authorities; passive mode by default; binary transfers.
 * Without a user name it signs in as anonymous.
 */
final class FtpRemote implements FileDestination.Remote {
    private final BimbaConfig.Destination cfg;
    private final FileDestination.Address at;
    private final String tls;
    private FTPClient ftp;

    FtpRemote(BimbaConfig.Destination cfg) {
        this.cfg = cfg;
        this.tls = cfg.ftpTls == null ? "none" : cfg.ftpTls.toLowerCase(java.util.Locale.ROOT);
        this.at = FileDestination.Address.of(cfg.url, "implicit".equals(tls) ? 990 : 21);
    }

    @Override
    public synchronized void put(String name, byte[] content) throws Exception {
        try {
            FTPClient c = client();
            mkdirs(c, at.folder());
            String part = at.join(FileDestination.partName(name));
            String target = at.join(name);
            if (!c.storeFile(part, new ByteArrayInputStream(content))) {
                throw new IOException("FTP: the server did not store the file: " + reply(c));
            }
            if (!c.rename(part, target)) {
                c.deleteFile(target);                                 // sent before (a retry): replace it
                if (!c.rename(part, target)) {
                    throw new IOException("FTP: could not rename " + part + " to " + target + ": " + reply(c));
                }
            }
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    @Override
    public synchronized String test() throws Exception {
        close();
        try {
            FTPClient c = client();
            String folder = at.folder().isEmpty() ? c.printWorkingDirectory() : at.folder();
            String home = c.printWorkingDirectory();
            boolean there = at.folder().isEmpty() || c.changeWorkingDirectory(at.folder());
            if (home != null) {
                c.changeWorkingDirectory(home);
            }
            return "Signed in to " + at.host() + (user().equals("anonymous") ? " as anonymous" : " as " + user())
                    + ("none".equals(tls) ? "" : " over TLS") + "; folder " + folder
                    + (there ? " is there" : " does not exist yet; it is created with the first file") + ". Nothing was written.";
        } finally {
            close();
        }
    }

    private String user() {
        return cfg.username == null || cfg.username.isBlank() ? "anonymous" : cfg.username;
    }

    private FTPClient client() throws Exception {
        if (ftp != null && ftp.isConnected()) {
            try {
                if (ftp.sendNoOp()) {
                    return ftp;
                }
            } catch (IOException ignored) {
                // closed by the server: connect again
            }
            close();
        }
        FTPClient c;
        if ("none".equals(tls)) {
            c = new FTPClient();
        } else {
            javax.net.ssl.SSLContext ctx = TlsSupport.context(cfg.tlsCa, cfg.tlsCert, cfg.tlsKey);
            FTPSClient s = ctx == null ? new FTPSClient("TLS", "implicit".equals(tls)) : new FTPSClient("implicit".equals(tls), ctx);
            s.setEndpointCheckingEnabled(true);                       // the certificate must be for this host name
            c = s;
        }
        c.setConnectTimeout(cfg.timeoutMs);
        c.setDefaultTimeout(cfg.timeoutMs);
        c.setDataTimeout(Duration.ofMillis(cfg.timeoutMs));
        try {
            c.connect(at.host(), at.port());
            if (!FTPReply.isPositiveCompletion(c.getReplyCode())) {
                throw new IOException("FTP: the server refused the connection: " + reply(c));
            }
            if (c instanceof FTPSClient s) {
                s.execPBSZ(0);
                s.execPROT("P");                                      // the files go encrypted too
            }
            String pass = cfg.password == null ? "" : cfg.password;
            if (!c.login(user(), user().equals("anonymous") && pass.isEmpty() ? "bimba@" : pass)) {
                throw new IOException("The FTP server rejected the user name or password: " + reply(c));
            }
            c.setFileType(FTP.BINARY_FILE_TYPE);
            if (cfg.ftpPassive) {
                c.enterLocalPassiveMode();
            }
        } catch (Exception e) {
            try {
                c.disconnect();
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
        ftp = c;
        return c;
    }

    private static void mkdirs(FTPClient c, String folder) throws IOException {
        if (folder.isEmpty()) {
            return;
        }
        String home = c.printWorkingDirectory();
        if (c.changeWorkingDirectory(folder)) {
            c.changeWorkingDirectory(home);
            return;
        }
        StringBuilder p = new StringBuilder(folder.startsWith("/") ? "/" : "");
        for (String part : folder.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            p.append(p.length() == 0 || p.charAt(p.length() - 1) == '/' ? "" : "/").append(part);
            if (c.changeWorkingDirectory(p.toString())) {
                c.changeWorkingDirectory(home);                       // a relative folder is relative to the sign-in folder
            } else if (!c.makeDirectory(p.toString())) {
                throw new IOException("FTP: could not create folder " + p + ": " + reply(c));
            }
        }
    }

    private static String reply(FTPClient c) {
        String r = c.getReplyString();
        return r == null ? "" : r.trim();
    }

    @Override
    public synchronized void close() {
        if (ftp != null) {
            try {
                if (ftp.isConnected()) {
                    ftp.logout();
                }
            } catch (IOException ignored) {
                // best effort
            }
            try {
                ftp.disconnect();
            } catch (IOException ignored) {
                // best effort
            }
            ftp = null;
        }
    }
}
