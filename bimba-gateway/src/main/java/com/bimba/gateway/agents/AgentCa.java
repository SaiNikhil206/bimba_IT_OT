package com.bimba.gateway.agents;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * The gateway's private certificate authority for site agents (data/tls/agent-ca.p12, created on first start).
 * It signs one client certificate per machine: the agent makes its key pair on the machine (the private key never
 * leaves it) and sends a certificate signing request. The certificate names the agent id (CN) and is used for
 * mutual TLS on the HTTPS port. Tomcat trusts only this authority for client certificates (data/tls/agent-trust.p12).
 * Revocation is not done with CRLs: the gateway checks every request against the agent's row (state + certificate hash).
 */
public final class AgentCa {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Agents");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile PrivateKey caKey;
    private static volatile X509Certificate caCert;
    /** trust store with only the CA certificate, for Tomcat's client certificate check */
    public static volatile File trustStore;
    public static volatile String trustPassword;

    private AgentCa() {
    }

    public static boolean ready() {
        return caCert != null;
    }

    public static X509Certificate certificate() {
        return caCert;
    }

    /** loads or creates the authority; called before the web server starts (needs the trust store) */
    public static void prepare(String dataDir) {
        try {
            File dir = new File(dataDir == null || dataDir.isBlank() ? "data" : dataDir, "tls");
            Files.createDirectories(dir.toPath());
            File store = new File(dir, "agent-ca.p12");
            File passFile = new File(dir, "agent-ca.p12.password");
            if (!store.isFile() || !passFile.isFile()) {
                create(store, passFile);
            }
            String pw = Files.readString(passFile.toPath(), StandardCharsets.UTF_8).trim();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(store)) {
                ks.load(in, pw.toCharArray());
            }
            caKey = (PrivateKey) ks.getKey("agent-ca", pw.toCharArray());
            caCert = (X509Certificate) ks.getCertificate("agent-ca");

            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            trust.setCertificateEntry("agent-ca", caCert);
            File trustFile = new File(dir, "agent-trust.p12");
            String tpw = randomPassword();
            try (FileOutputStream out = new FileOutputStream(trustFile)) {
                trust.store(out, tpw.toCharArray());
            }
            trustStore = trustFile;
            trustPassword = tpw;
            log.info("site agent certificate authority {} (valid until {})", caCert.getSubjectX500Principal().getName(),
                    caCert.getNotAfter().toInstant());
        } catch (Exception e) {
            log.error("site agent certificate authority not available, agents keep using their secret: {}", e.getMessage());
        }
    }

    private static void create(File store, File passFile) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(3072, RANDOM);
        KeyPair kp = g.generateKeyPair();
        X500Name name = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.O, "BIMBA")
                .addRDN(BCStyle.CN, "BIMBA Gateway Agent CA " + UUID.randomUUID().toString().substring(0, 8))
                .build();
        Instant now = Instant.now();
        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, serial(), Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(365L * 20))), name, kp.getPublic())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
                .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
                .addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.getPublic()));
        X509Certificate cert = sign(b, kp.getPrivate());
        String pw = randomPassword();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("agent-ca", kp.getPrivate(), pw.toCharArray(), new Certificate[]{cert});
        try (FileOutputStream out = new FileOutputStream(store)) {
            ks.store(out, pw.toCharArray());
        }
        Files.writeString(passFile.toPath(), pw, StandardCharsets.UTF_8);
        log.info("created the site agent certificate authority {}", store);
    }

    /**
     * Signs the agent's request. The subject is set here (CN = agent id, O = organization), never taken from the request;
     * the request only proves that the agent holds the private key.
     */
    public static X509Certificate issue(byte[] csrDer, UUID agentId, String orgSlug, int days) throws AgentRegistry.ApiError {
        if (!ready()) {
            throw new AgentRegistry.ApiError(503, "The gateway cannot issue certificates right now.");
        }
        PublicKey pub;
        try {
            JcaPKCS10CertificationRequest csr = new JcaPKCS10CertificationRequest(new PKCS10CertificationRequest(csrDer));
            pub = csr.getPublicKey();
            if (!csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(csr.getSubjectPublicKeyInfo()))) {
                throw new AgentRegistry.ApiError(400, "the certificate request signature is not valid");
            }
        } catch (AgentRegistry.ApiError e) {
            throw e;
        } catch (Exception e) {
            throw new AgentRegistry.ApiError(400, "csr must be a PKCS#10 certificate request (base64 DER)");
        }
        if (!(pub instanceof RSAPublicKey r && r.getModulus().bitLength() >= 2048)
                && !(pub instanceof ECPublicKey ec && ec.getParams().getCurve().getField().getFieldSize() >= 256)) {
            throw new AgentRegistry.ApiError(400, "the key must be RSA (2048 bits or more) or EC (P-256 or larger)");
        }
        try {
            X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                    .addRDN(BCStyle.O, orgSlug)
                    .addRDN(BCStyle.OU, "BIMBA Site Agent")
                    .addRDN(BCStyle.CN, agentId.toString())
                    .build();
            Instant now = Instant.now();
            JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(caCert, serial(), Date.from(now.minus(Duration.ofMinutes(5))),
                    Date.from(now.plus(Duration.ofDays(days))), subject, pub)
                    .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                    .addExtension(Extension.keyUsage, true, new KeyUsage(pub instanceof RSAPublicKey
                            ? KeyUsage.digitalSignature | KeyUsage.keyEncipherment : KeyUsage.digitalSignature))
                    .addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
                    .addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(pub))
                    .addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(caCert));
            return sign(b, caKey);
        } catch (Exception e) {
            throw new AgentRegistry.ApiError(500, "issuing the certificate failed: " + e.getMessage());
        }
    }

    /** the agent id in a client certificate this authority issued (CN), or null */
    public static UUID agentIdOf(X509Certificate c) {
        try {
            X500Name n = X500Name.getInstance(c.getSubjectX500Principal().getEncoded());
            return UUID.fromString(n.getRDNs(BCStyle.CN)[0].getFirst().getValue().toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static X509Certificate sign(X509v3CertificateBuilder b, PrivateKey key) throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(key);
        X509CertificateHolder h = b.build(signer);
        return new JcaX509CertificateConverter().getCertificate(h);
    }

    private static BigInteger serial() {
        return new BigInteger(127, RANDOM).add(BigInteger.ONE);
    }

    private static String randomPassword() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
