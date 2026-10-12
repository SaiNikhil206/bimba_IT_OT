package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HL7 v2 over MLLP (FR-END-03): every batch becomes HL7 messages ({@link Hl7}: ORU^R01 by default, one per machine or
 * per reading), each framed as &lt;VT&gt; message &lt;FS&gt;&lt;CR&gt; on a kept-open TCP (mllp://) or TLS (mllps://)
 * connection, and each must be acknowledged before the next is sent:
 * <ul>
 *   <li>MSA-1 AA / CA: accepted;</li>
 *   <li>AE / CE (application error): the receiver refused the content; the batch is kept as refused for review;</li>
 *   <li>AR / CR (rejected, e.g. the receiver cannot process now), no answer in time, a broken connection: tried again
 *       with the destination's backoff. A message already acknowledged is sent again with the same MSH-10, so the
 *       receiver can drop it.</li>
 * </ul>
 */
public class MllpDestination extends Destination {
    static final byte VT = 0x0b;
    static final byte FS = 0x1c;
    static final byte CR = 0x0d;

    private final Hl7 hl7;
    private Socket socket;

    public MllpDestination(BimbaConfig.Destination cfg) {
        super(cfg);
        Hl7 h;
        try {
            h = Hl7.compile(cfg.hl7Template);
        } catch (IllegalArgumentException e) {
            org.slf4j.LoggerFactory.getLogger("BIMBA.Delivery").warn("destination {}: HL7 template ignored, {}", cfg.name, e.getMessage());
            h = Hl7.compile(null);
        }
        this.hl7 = h;
    }

    @Override
    public synchronized void send(byte[] batch) throws Exception {
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        List<Hl7.Message> messages = hl7.render(env, cfg.hl7Mode);
        try {
            for (Hl7.Message m : messages) {
                exchange(m);
            }
        } catch (RejectedException e) {
            throw e;
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    /** sends one message and checks its acknowledgement */
    private void exchange(Hl7.Message m) throws Exception {
        Socket s = connected();
        OutputStream out = s.getOutputStream();
        out.write(VT);
        out.write(m.text().getBytes(StandardCharsets.UTF_8));
        out.write(FS);
        out.write(CR);
        out.flush();
        String ack = read(s.getInputStream());
        check(ack, m.controlId());
    }

    /** MSA-1 of the acknowledgement: accepted, refused (content) or to be tried again */
    static void check(String ack, String controlId) throws Exception {
        String msa = null;
        for (String seg : ack.split("\\r\\n|\\r|\\n")) {
            if (seg.startsWith("MSA|")) {
                msa = seg;
                break;
            }
        }
        if (msa == null) {
            throw new IOException("the answer is no HL7 acknowledgement (no MSA segment): " + cut(ack));
        }
        String[] f = msa.split("\\|", -1);
        String code = f.length > 1 ? f[1].trim().toUpperCase(java.util.Locale.ROOT) : "";
        String about = f.length > 2 ? f[2].trim() : "";
        String text = f.length > 3 ? f[3].trim() : "";
        String err = errText(ack);
        if (!about.isEmpty() && !about.equals(controlId)) {
            throw new IOException("the acknowledgement is for message " + about + ", not " + controlId);
        }
        switch (code) {
            case "AA", "CA" -> {
            }
            case "AE", "CE" -> throw new RejectedException("HL7 " + code + " (application error) for " + controlId
                    + (text.isEmpty() ? "" : ": " + text) + (err.isEmpty() ? "" : " " + err));
            case "AR", "CR" -> throw new IOException("HL7 " + code + " (rejected, tried again) for " + controlId
                    + (text.isEmpty() ? "" : ": " + text) + (err.isEmpty() ? "" : " " + err));
            default -> throw new IOException("unknown acknowledgement code \"" + code + "\": " + cut(ack));
        }
    }

    /** ERR-8 (2.5: user message) or ERR-1, when the acknowledgement has an ERR segment */
    private static String errText(String ack) {
        for (String seg : ack.split("\\r\\n|\\r|\\n")) {
            if (seg.startsWith("ERR|")) {
                String[] f = seg.split("\\|", -1);
                String t = f.length > 8 && !f[8].isBlank() ? f[8] : f.length > 1 ? f[1] : "";
                return t.isBlank() ? "" : "(" + t.trim() + ")";
            }
        }
        return "";
    }

    /** one MLLP frame: everything between VT and FS CR (bytes before VT are skipped) */
    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        boolean started = false;
        int prev = -1;
        while (true) {
            int b;
            try {
                b = in.read();
            } catch (SocketTimeoutException e) {
                throw new IOException("no acknowledgement in time", e);
            }
            if (b < 0) {
                throw new IOException("the receiver closed the connection before acknowledging");
            }
            if (!started) {
                started = b == VT;
                continue;
            }
            if (prev == FS && b == CR) {
                byte[] all = buf.toByteArray();
                return new String(all, 0, all.length - 1, StandardCharsets.UTF_8);
            }
            buf.write(b);
            if (buf.size() > 1_000_000) {
                throw new IOException("the acknowledgement is longer than 1 MB");
            }
            prev = b;
        }
    }

    private Socket connected() throws Exception {
        if (socket != null && !socket.isClosed() && socket.isConnected()) {
            return socket;
        }
        socket = open();
        return socket;
    }

    private Socket open() throws Exception {
        URI u = URI.create(cfg.url.trim());
        boolean tls = "mllps".equalsIgnoreCase(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : 2575;
        Socket s;
        if (tls) {
            javax.net.ssl.SSLContext ctx = TlsSupport.context(cfg.tlsCa, cfg.tlsCert, cfg.tlsKey);
            javax.net.ssl.SSLSocketFactory f = (ctx != null ? ctx : javax.net.ssl.SSLContext.getDefault()).getSocketFactory();
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(u.getHost(), port), cfg.timeoutMs);
            javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) f.createSocket(raw, u.getHost(), port, true);
            javax.net.ssl.SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");          // the certificate must be for this host name
            ssl.setSSLParameters(p);
            ssl.setSoTimeout(cfg.timeoutMs);
            ssl.startHandshake();
            s = ssl;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(u.getHost(), port), cfg.timeoutMs);
        }
        s.setSoTimeout(cfg.timeoutMs);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        return s;
    }

    /** the HL7 messages, segments one per line */
    @Override
    protected String wire(byte[] batch) {
        JsonObject env = Json.GSON.fromJson(new String(batch, StandardCharsets.UTF_8), JsonObject.class);
        StringBuilder sb = new StringBuilder();
        for (Hl7.Message m : hl7.render(env, cfg.hl7Mode)) {
            sb.append(sb.length() > 0 ? "\n" : "").append(m.text().replace('\r', '\n'));
        }
        return sb.toString();
    }

    /** Connects (and with mllps:// completes the TLS handshake); HL7 has no message that stores nothing, so none is sent. */
    @Override
    public String test() throws Exception {
        try (Socket s = open()) {
            return "Connected to " + endpoint() + (s instanceof javax.net.ssl.SSLSocket ? " over TLS" : "")
                    + ". Nothing was sent: use Send test message to check the acknowledgements.";
        }
    }

    private static String cut(String s) {
        String t = s.replace('\r', ' ').trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    @Override
    public String endpoint() {
        return cfg.url;
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
            socket = null;
        }
    }
}
