package com.bimba.setu.web;

import com.bimba.setu.delivery.TlsSupport;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.Period;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTPS certificate of Setu (port 8443).
 * <ul>
 *   <li>Production: the customer's certificate, from their own certificate authority or a public one:
 *       BIMBA_TLS_CERT (PEM: the certificate, then its intermediates) + BIMBA_TLS_KEY (PEM private key), or
 *       BIMBA_TLS_PFX + BIMBA_TLS_PFX_PASSWORD. Site agents then trust it like any web site, no pinning needed.</li>
 *   <li>Otherwise (or when that certificate cannot be used): a self-signed certificate created on first start
 *       (data/tls/gateway.p12). Site agents pin its SHA-256 fingerprint.</li>
 * </ul>
 * A changed certificate is picked up on the next restart.
 */
public final class Tls {
    private static final Logger log = LoggerFactory.getLogger("BIMBA");

    public static volatile File keyStore;
    public static volatile String password;
    /** SHA-256 of the certificate, AA:BB:..., or null when HTTPS is off */
    public static volatile String fingerprint;
    /** true = Setu's own self-signed certificate (agents must pin it) */
    public static volatile boolean selfSigned = true;
    /** "self-signed" | "pem" | "pfx" */
    public static volatile String source = "self-signed";
    /** why the configured certificate was not used (the self-signed one is used instead), or null */
    public static volatile String error;
    private static volatile X509Certificate current;

    private Tls() {
    }

    public static void prepare(String dataDir, String httpsPort, String extraNames) throws Exception {
        if (httpsPort != null && httpsPort.trim().equals("0")) {
            return;
        }
        File dir = new File(dataDir == null || dataDir.isBlank() ? "data" : dataDir, "tls");
        Files.createDirectories(dir.toPath());
        try {
            if (custom(dir, System.getenv("BIMBA_TLS_CERT"), System.getenv("BIMBA_TLS_KEY"),
                    System.getenv("BIMBA_TLS_PFX"), System.getenv("BIMBA_TLS_PFX_PASSWORD"))) {
                return;
            }
        } catch (Exception e) {
            error = e.getMessage();
            log.error("HTTPS certificate from BIMBA_TLS_* not used, falling back to the self-signed one: {}", e.getMessage());
        }
        File store = new File(dir, "gateway.p12");
        File passFile = new File(dir, "gateway.p12.password");
        if (!store.isFile() || !passFile.isFile()) {
            create(store, passFile, extraNames);
        }
        String pw = Files.readString(passFile.toPath(), StandardCharsets.UTF_8).trim();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(store)) {
            ks.load(in, pw.toCharArray());
        }
        use(store, pw, (X509Certificate) ks.getCertificate(ks.aliases().nextElement()), true, "self-signed");
    }

    /** the customer's certificate, copied into data/tls/custom.p12 (Tomcat reads a key store); false = none configured */
    private static boolean custom(File dir, String certFile, String keyFile, String pfxFile, String pfxPassword) throws Exception {
        boolean pem = notBlank(certFile) || notBlank(keyFile);
        boolean pfx = notBlank(pfxFile);
        if (!pem && !pfx) {
            return false;
        }
        if (pem && pfx) {
            throw new IllegalArgumentException("set either BIMBA_TLS_CERT + BIMBA_TLS_KEY or BIMBA_TLS_PFX, not both");
        }
        List<X509Certificate> chain;
        PrivateKey key;
        if (pem) {
            if (!notBlank(certFile) || !notBlank(keyFile)) {
                throw new IllegalArgumentException("BIMBA_TLS_CERT and BIMBA_TLS_KEY must both be set");
            }
            chain = TlsSupport.certificates(read(certFile));
            key = TlsSupport.privateKey(read(keyFile));
        } else {
            KeyStore in = KeyStore.getInstance("PKCS12");
            char[] pw = pfxPassword == null ? new char[0] : pfxPassword.toCharArray();
            try (FileInputStream f = new FileInputStream(pfxFile.trim())) {
                in.load(f, pw);
            } catch (Exception e) {
                throw new IllegalArgumentException("cannot open " + pfxFile + " (wrong BIMBA_TLS_PFX_PASSWORD?): " + e.getMessage());
            }
            String alias = Collections.list(in.aliases()).stream().filter(a -> {
                try {
                    return in.isKeyEntry(a);
                } catch (Exception e) {
                    return false;
                }
            }).findFirst().orElseThrow(() -> new IllegalArgumentException(pfxFile + " contains no private key"));
            key = (PrivateKey) in.getKey(alias, pw);
            chain = new ArrayList<>();
            for (Certificate c : in.getCertificateChain(alias)) {
                chain.add((X509Certificate) c);
            }
        }
        X509Certificate leaf = chain.get(0);
        if (!TlsSupport.matches(leaf, key)) {
            throw new IllegalArgumentException("the private key does not belong to the certificate " + leaf.getSubjectX500Principal().getName());
        }
        if (leaf.getNotAfter().toInstant().isBefore(Instant.now())) {
            throw new IllegalArgumentException("the certificate " + leaf.getSubjectX500Principal().getName() + " expired on " + leaf.getNotAfter().toInstant());
        }
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        String pw = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("bimba-setu", key, pw.toCharArray(), chain.toArray(new Certificate[0]));
        File store = new File(dir, "custom.p12");
        try (FileOutputStream out = new FileOutputStream(store)) {
            ks.store(out, pw.toCharArray());
        }
        boolean self = leaf.getSubjectX500Principal().equals(leaf.getIssuerX500Principal());
        use(store, pw, leaf, self, pem ? "pem" : "pfx");
        return true;
    }

    private static void use(File store, String pw, X509Certificate cert, boolean self, String from) throws Exception {
        fingerprint = HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        keyStore = store;
        password = pw;
        selfSigned = self;
        source = from;
        current = cert;
        log.info("HTTPS certificate ({}) {} issued by {}, valid until {}, SHA-256 {}", from, cert.getSubjectX500Principal().getName(),
                cert.getIssuerX500Principal().getName(), cert.getNotAfter().toInstant(), fingerprint);
    }

    /** for the console: what certificate HTTPS uses */
    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        X509Certificate c = current;
        m.put("enabled", c != null);
        if (c == null) {
            return m;
        }
        m.put("source", source);
        m.put("selfSigned", selfSigned);
        m.put("subject", c.getSubjectX500Principal().getName());
        m.put("issuer", c.getIssuerX500Principal().getName());
        m.put("notAfter", c.getNotAfter().toInstant().toString());
        List<String> names = new ArrayList<>();
        try {
            if (c.getSubjectAlternativeNames() != null) {
                c.getSubjectAlternativeNames().forEach(n -> names.add(String.valueOf(n.get(1))));
            }
        } catch (Exception ignored) {
            // no names
        }
        m.put("names", names);
        m.put("fingerprint", fingerprint);
        m.put("error", error);
        return m;
    }

    private static String read(String file) throws Exception {
        return Files.readString(new File(file.trim()).toPath(), StandardCharsets.US_ASCII);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void create(File store, File passFile, String extraNames) throws Exception {
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        String pw = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        KeyPair kp = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);
        SelfSignedCertificateBuilder b = new SelfSignedCertificateBuilder(kp)
                .setCommonName("BIMBA Setu")
                .setOrganization("BIMBA")
                .setApplicationUri("urn:bimba:setu")
                .setValidityPeriod(Period.ofYears(5))
                .addDnsName("localhost")
                .addIpAddress("127.0.0.1");
        try {
            b.addDnsName(InetAddress.getLocalHost().getHostName());
        } catch (Exception ignored) {
            // container without a resolvable host name
        }
        for (String n : (extraNames == null ? "" : extraNames).split(",")) {
            String name = n.trim();
            if (name.matches("[0-9.]+|[0-9a-fA-F:]+")) {
                b.addIpAddress(name);
            } else if (!name.isEmpty()) {
                b.addDnsName(name);
            }
        }
        X509Certificate cert = b.build();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("bimba-setu", kp.getPrivate(), pw.toCharArray(), new Certificate[]{cert});
        try (FileOutputStream out = new FileOutputStream(store)) {
            ks.store(out, pw.toCharArray());
        }
        Files.writeString(passFile.toPath(), pw, StandardCharsets.UTF_8);
        log.info("created self-signed HTTPS certificate {}", store);
    }
}
