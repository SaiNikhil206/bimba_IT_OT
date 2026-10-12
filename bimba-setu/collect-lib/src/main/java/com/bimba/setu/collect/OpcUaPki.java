package com.bimba.setu.collect;

import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Period;

/**
 * This application's OPC UA identity: an RSA 2048 key + self-signed certificate (application URI in the subject
 * alternative name, as OPC UA requires), created once in {@code <dir>/opcua-client.p12}. Signed/encrypted OPC UA
 * policies present it to the server, which must trust it once. Without {@link #configure} only policy None works.
 */
public final class OpcUaPki {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.OpcUa");
    private static final char[] PASSWORD = "bimba-opcua".toCharArray();   // the file is protected by the folder ACL
    private static final String ALIAS = "client";

    public record Identity(String applicationName, String applicationUri, KeyPair keyPair, X509Certificate certificate) {
        public String certificateSha256() {
            return certificate == null ? null : OpcUaDriver.sha256(certificate);
        }
    }

    private static volatile Identity identity = new Identity("BIMBA", "urn:bimba:client", null, null);

    private OpcUaPki() {
    }

    public static Identity identity() {
        return identity;
    }

    /** Loads (or creates) the key + certificate in {@code dir}. Call once at start-up. */
    public static synchronized Identity configure(File dir, String applicationName, String applicationUri) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        File file = new File(dir, "opcua-client.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                ks.load(in, PASSWORD);
            }
            Key key = ks.getKey(ALIAS, PASSWORD);
            Certificate cert = ks.getCertificate(ALIAS);
            if (key instanceof PrivateKey pk && cert instanceof X509Certificate x && uriOf(x).equals(applicationUri)) {
                identity = new Identity(applicationName, applicationUri, new KeyPair(x.getPublicKey(), pk), x);
                return identity;
            }
            log.warn("{} does not match application URI {}; creating a new OPC UA client certificate", file, applicationUri);
        }
        KeyPair kp = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);
        String host = hostName();
        SelfSignedCertificateBuilder b = new SelfSignedCertificateBuilder(kp)
                .setCommonName(applicationName)
                .setOrganization("BIMBA")
                .setOrganizationalUnit("BIMBA OPC UA client")
                .setApplicationUri(applicationUri)
                .setValidityPeriod(Period.ofYears(10))
                .addDnsName(host);
        X509Certificate cert = b.build();
        ks.load(null, PASSWORD);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), PASSWORD, new Certificate[]{cert});
        File tmp = new File(dir, "opcua-client.p12.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            ks.store(out, PASSWORD);
        }
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("cannot replace " + file);
        }
        if (!tmp.renameTo(file)) {
            throw new IllegalStateException("cannot write " + file);
        }
        identity = new Identity(applicationName, applicationUri, kp, cert);
        log.info("created OPC UA client certificate {} (SHA-256 {})", applicationUri, identity.certificateSha256());
        return identity;
    }

    private static String uriOf(X509Certificate c) {
        try {
            if (c.getSubjectAlternativeNames() != null) {
                for (var san : c.getSubjectAlternativeNames()) {
                    if (san.size() >= 2 && Integer.valueOf(6).equals(san.get(0))) {     // uniformResourceIdentifier
                        return String.valueOf(san.get(1));
                    }
                }
            }
        } catch (Exception ignored) {
            // treated as no URI
        }
        return "";
    }

    public static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }
}
