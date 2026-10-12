package com.bimba.agent;

import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * The agent's client certificate for mutual TLS with Setu. The key (EC P-256: quick on a Raspberry Pi) is made on
 * this computer and never leaves it: only a certificate signing request goes to Setu, which signs it with its
 * agent authority. Key + certificate are kept in a PKCS#12 file in the data folder, readable only by the service account
 * (the Linux counterpart of the Windows agent's non-exportable key in the service account's store).
 */
final class AgentCertificate {
    static final String ALIAS = "bimba-agent";

    /** a key with its certificate, ready for TLS */
    record Credential(X509Certificate certificate, PrivateKey key, KeyManager[] keyManagers) {
        String sha256() {
            try {
                return SetuClient.sha256Hex(certificate.getEncoded());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    record Request(KeyPair keys, byte[] csr) {
    }

    private AgentCertificate() {
    }

    /** new key + PKCS#10 request (DER); the subject is only a hint, Setu sets its own */
    static Request newKey(String agentId) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        byte[] csr = new JcaPKCS10CertificationRequestBuilder(new X500Principal("CN=" + agentId), kp.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate()))
                .getEncoded();
        return new Request(kp, csr);
    }

    static X509Certificate parse(String base64Der) throws Exception {
        byte[] der = Base64.getDecoder().decode(base64Der == null ? "" : base64Der.trim());
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    static Credential credential(X509Certificate cert, PrivateKey key) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        char[] pw = "in-memory".toCharArray();
        ks.setKeyEntry(ALIAS, key, pw, new Certificate[]{cert});
        KeyManagerFactory f = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        f.init(ks, pw);
        return new Credential(cert, key, f.getKeyManagers());
    }

    /** writes key + certificate as a private PKCS#12 file */
    static void save(Path file, Credential c, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, c.key(), password.toCharArray(), new Certificate[]{c.certificate()});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, password.toCharArray());
        Files2.writePrivate(file, out.toByteArray());
    }

    /** the stored credential, or null when the file is missing */
    static Credential load(Path file, String password) throws Exception {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            ks.load(in, password == null ? null : password.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(ALIAS, password == null ? null : password.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
        return key == null || cert == null ? null : credential(cert, key);
    }

    /** renew when less than a third of the lifetime is left */
    static boolean needsRenewal(X509Certificate c) {
        Duration life = Duration.between(c.getNotBefore().toInstant(), c.getNotAfter().toInstant());
        return Duration.between(Instant.now(), c.getNotAfter().toInstant()).compareTo(life.dividedBy(3)) < 0;
    }

    static String newPassword() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** removes key stores other than the one in use (left over from renewals or re-installs) */
    static void deleteOthers(String keep) {
        try (var files = Files.list(Paths.dataDir())) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith("agent-cert-") && n.endsWith(".p12") && !n.equals(keep)) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (Exception e) {
            Log.warn("could not clean up old certificates: " + e.getMessage());
        }
    }
}
