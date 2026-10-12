package com.bimba.setu.delivery;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Certificates of a destination, as PEM text: extra trusted authorities (added to the JDK's, so public services keep
 * working) and a client certificate + private key for certificate sign-in (AWS IoT Core, Azure IoT Hub, company brokers).
 * Private keys: PKCS#8 ("BEGIN PRIVATE KEY", RSA or EC) or RSA PKCS#1 ("BEGIN RSA PRIVATE KEY"); not password-protected.
 */
public final class TlsSupport {
    private static final Pattern BLOCK = Pattern.compile("-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private TlsSupport() {
    }

    public static boolean has(String pem) {
        return pem != null && !pem.isBlank();
    }

    public static List<X509Certificate> certificates(String pem) {
        List<X509Certificate> out = new ArrayList<>();
        Matcher m = BLOCK.matcher(pem == null ? "" : pem);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            while (m.find()) {
                if ("CERTIFICATE".equals(m.group(1))) {
                    out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der(m.group(2)))));
                }
            }
        } catch (CertificateException | IllegalArgumentException e) {
            throw new IllegalArgumentException("A certificate could not be read: " + e.getMessage());
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("No certificate found. Paste PEM text from -----BEGIN CERTIFICATE----- to -----END CERTIFICATE-----.");
        }
        return out;
    }

    public static PrivateKey privateKey(String pem) {
        Matcher m = BLOCK.matcher(pem == null ? "" : pem);
        while (m.find()) {
            String type = m.group(1);
            byte[] der = der(m.group(2));
            switch (type) {
                case "PRIVATE KEY" -> {
                    for (String alg : List.of("RSA", "EC", "Ed25519")) {
                        try {
                            return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
                        } catch (Exception ignored) {
                            // next algorithm
                        }
                    }
                    throw new IllegalArgumentException("The private key is not an RSA, EC or Ed25519 key.");
                }
                case "RSA PRIVATE KEY" -> {
                    try {
                        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs1ToPkcs8(der)));
                    } catch (Exception e) {
                        throw new IllegalArgumentException("The RSA private key could not be read: " + e.getMessage());
                    }
                }
                case "ENCRYPTED PRIVATE KEY" -> throw new IllegalArgumentException(
                        "The private key is password-protected. Export it without a password: openssl pkcs8 -topk8 -nocrypt -in key.pem");
                case "EC PRIVATE KEY" -> throw new IllegalArgumentException(
                        "Convert the EC key to PKCS#8 first: openssl pkcs8 -topk8 -nocrypt -in key.pem");
                default -> {
                    // a certificate in the same text: skip it
                }
            }
        }
        throw new IllegalArgumentException("No private key found. Paste PEM text from -----BEGIN PRIVATE KEY----- to -----END PRIVATE KEY-----.");
    }

    /** PKCS#8 PEM of the key, as Kafka's PEM key store needs it */
    public static String pkcs8Pem(String pem) {
        PrivateKey k = privateKey(pem);
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(k.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }

    /** checks what the form sends: certificates readable, key readable and belonging to the certificate */
    public static void validate(String ca, String cert, String key) {
        if (has(ca)) {
            certificates(ca);
        }
        if (has(cert) != has(key)) {
            throw new IllegalArgumentException("Certificate sign-in needs both the client certificate and its private key.");
        }
        if (has(cert)) {
            X509Certificate c = certificates(cert).get(0);
            PrivateKey k = privateKey(key);
            if (!matches(c, k)) {
                throw new IllegalArgumentException("The private key does not belong to the client certificate ("
                        + c.getSubjectX500Principal().getName() + ").");
            }
            try {
                context(ca, cert, key);
            } catch (Exception e) {
                throw new IllegalArgumentException("The certificate and key could not be used together: " + e.getMessage());
            }
        }
    }

    /** the key belongs to the certificate: what it signs, the certificate's public key verifies */
    public static boolean matches(X509Certificate c, PrivateKey k) {
        String alg = switch (k.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> k.getAlgorithm();               // Ed25519
        };
        try {
            byte[] challenge = new byte[32];
            new java.security.SecureRandom().nextBytes(challenge);
            java.security.Signature s = java.security.Signature.getInstance(alg);
            s.initSign(k);
            s.update(challenge);
            byte[] sig = s.sign();
            java.security.Signature v = java.security.Signature.getInstance(alg);
            v.initVerify(c.getPublicKey());
            v.update(challenge);
            return v.verify(sig);
        } catch (Exception e) {
            return false;                              // different key types, or not a signing key
        }
    }

    /** null when nothing is set: the JDK defaults apply */
    public static SSLContext context(String ca, String cert, String key) throws Exception {
        if (!has(ca) && !has(cert)) {
            return null;
        }
        TrustManager[] trust = null;
        if (has(ca)) {
            KeyStore ts = KeyStore.getInstance("PKCS12");
            ts.load(null, null);
            int i = 0;
            for (X509Certificate c : certificates(ca)) {
                ts.setCertificateEntry("ca" + i++, c);
            }
            TrustManagerFactory custom = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            custom.init(ts);
            TrustManagerFactory jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            jdk.init((KeyStore) null);
            trust = new TrustManager[]{either(x509(custom), x509(jdk))};
        }
        KeyManagerFactory keys = null;
        if (has(cert)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            char[] pw = "bimba".toCharArray();
            ks.setKeyEntry("client", privateKey(key), pw, certificates(cert).toArray(new X509Certificate[0]));
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(ks, pw);
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(keys == null ? null : keys.getKeyManagers(), trust, null);
        return ctx;
    }

    /** subject, issuer and expiry of each certificate, for the console */
    public static List<Map<String, Object>> describe(String pem) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (X509Certificate c : certificates(pem)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subject", c.getSubjectX500Principal().getName());
                m.put("issuer", c.getIssuerX500Principal().getName());
                m.put("notAfter", c.getNotAfter().toInstant().toString());
                m.put("expired", c.getNotAfter().getTime() < System.currentTimeMillis());
                m.put("daysLeft", (c.getNotAfter().getTime() - System.currentTimeMillis()) / 86_400_000L);
                out.add(m);
            }
        } catch (IllegalArgumentException e) {
            out.add(Map.of("error", e.getMessage()));
        }
        return out;
    }

    private static X509TrustManager x509(TrustManagerFactory f) {
        for (TrustManager t : f.getTrustManagers()) {
            if (t instanceof X509TrustManager x) {
                return x;
            }
        }
        throw new IllegalStateException("no X509 trust manager");
    }

    /** trusts what either the destination's authorities or the JDK's trust */
    private static X509TrustManager either(X509TrustManager a, X509TrustManager b) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    a.checkClientTrusted(chain, authType);
                } catch (CertificateException e) {
                    b.checkClientTrusted(chain, authType);
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    a.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    b.checkServerTrusted(chain, authType);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                List<X509Certificate> l = new ArrayList<>(List.of(a.getAcceptedIssuers()));
                l.addAll(List.of(b.getAcceptedIssuers()));
                return l.toArray(new X509Certificate[0]);
            }
        };
    }

    private static byte[] der(String base64) {
        return Base64.getMimeDecoder().decode(base64.replaceAll("\\s", ""));
    }

    /** wraps an RSA PKCS#1 key in a PKCS#8 structure (SEQUENCE{version 0, rsaEncryption, OCTET STRING key}) */
    static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] algo = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octet = tlv(0x04, pkcs1);
        byte[] body = new byte[version.length + algo.length + octet.length];
        System.arraycopy(version, 0, body, 0, version.length);
        System.arraycopy(algo, 0, body, version.length, algo.length);
        System.arraycopy(octet, 0, body, version.length + algo.length, octet.length);
        return tlv(0x30, body);
    }

    private static byte[] tlv(int tag, byte[] value) {
        int n = value.length;
        byte[] len;
        if (n < 0x80) {
            len = new byte[]{(byte) n};
        } else if (n < 0x100) {
            len = new byte[]{(byte) 0x81, (byte) n};
        } else if (n < 0x10000) {
            len = new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        } else {
            len = new byte[]{(byte) 0x83, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
        }
        byte[] out = new byte[1 + len.length + n];
        out[0] = (byte) tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(value, 0, out, 1 + len.length, n);
        return out;
    }
}
