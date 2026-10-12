package com.bimba.agent;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {
    @Test
    void scadaNamesMatchAtWordStartOnly() {
        assertEquals("Inductive Automation Ignition", Inventory.familyOf("ignition"));
        assertEquals("CODESYS Control", Inventory.familyOf("codesyscontrol"));
        assertEquals("Node-RED", Inventory.familyOf("nodered"));
        assertEquals("Siemens WinCC OA", Inventory.familyOf("WinCC_OA 3.19"));
        assertNull(Inventory.familyOf("preignition-helper"));
        assertNull(Inventory.familyOf("bimba-site-agent"));
        assertNull(Inventory.familyOf("openssh-server"));
    }

    @Test
    void readingsKeepNullValues() {
        com.google.gson.JsonObject m = new com.google.gson.JsonObject();
        m.add("tagValue", com.google.gson.JsonNull.INSTANCE);
        m.addProperty("quality", "BAD");
        assertEquals("{\"tagValue\":null,\"quality\":\"BAD\"}", SetuClient.GSON.toJson(m),
                "Setu quarantines readings without tagValue");
    }

    @Test
    void gatewayAddressIsNormalized() {
        assertEquals("https://gw.example.com:8443", SetuClient.normalize("gw.example.com:8443/"));
        assertEquals("http://10.0.0.5:8090", SetuClient.normalize("HTTP://10.0.0.5:8090"));
        assertThrows(IllegalArgumentException.class, () -> SetuClient.normalize(" "));
        assertTrue(SetuClient.isPlainHttpToRemote("http://10.0.0.5:8090"));
        assertFalse(SetuClient.isPlainHttpToRemote("http://localhost:8090"));
        assertFalse(SetuClient.isPlainHttpToRemote("https://10.0.0.5:8443"));
    }

    @Test
    void spoolKeepsOrderAndRejects(@TempDir Path dir) throws Exception {
        Spool s = new Spool(dir, 1L << 30);
        s.add("{\"a\":1}");
        s.add("{\"a\":2}");
        assertNull(s.oldest());                    // still in memory
        s.flush();
        s.add("{\"a\":3}");
        s.flush();
        Path first = s.oldest();
        assertEquals("{\"a\":1}\n{\"a\":2}\n", Files.readString(first));
        s.reject(first);
        assertTrue(Files.exists(dir.resolve("rejected").resolve(first.getFileName())));
        assertEquals("{\"a\":3}\n", Files.readString(s.oldest()));
        assertEquals(1, s.status().get("files"));
    }

    @Test
    void certificateRequestAndKeyStore(@TempDir Path dir) throws Exception {
        AgentCertificate.Request req = AgentCertificate.newKey("4f6c1a2e-0000-4000-8000-000000000001");
        PKCS10CertificationRequest csr = new PKCS10CertificationRequest(req.csr());
        assertEquals("CN=4f6c1a2e-0000-4000-8000-000000000001", csr.getSubject().toString());

        // a certificate as Setu's agent authority would issue it (here self-signed, 90 days)
        Instant now = Instant.now();
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                new X500Name("CN=test CA"), BigInteger.ONE, Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(89))),
                new X500Name("CN=agent"), req.keys().getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(req.keys().getPrivate())));
        AgentCertificate.Credential c = AgentCertificate.credential(cert, req.keys().getPrivate());
        assertNotNull(c.keyManagers());
        assertFalse(AgentCertificate.needsRenewal(cert));

        Path file = dir.resolve("agent-cert-1.p12");
        String pw = AgentCertificate.newPassword();
        AgentCertificate.save(file, c, pw);
        AgentCertificate.Credential back = AgentCertificate.load(file, pw);
        assertEquals(c.sha256(), back.sha256());
        assertNull(AgentCertificate.load(dir.resolve("missing.p12"), pw));
    }

    @Test
    void certificateRenewsInTheLastThird() throws Exception {
        AgentCertificate.Request req = AgentCertificate.newKey("x");
        Instant now = Instant.now();
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                new X500Name("CN=ca"), BigInteger.TWO, Date.from(now.minus(Duration.ofDays(70))), Date.from(now.plus(Duration.ofDays(20))),
                new X500Name("CN=agent"), req.keys().getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(req.keys().getPrivate())));
        assertTrue(AgentCertificate.needsRenewal(cert));
    }
}
