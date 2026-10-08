package com.bimba.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts destination secrets (tokens, passwords, API keys) before they go into the shared database:
 * AES-256-GCM, stored as "enc:v1:" + base64(iv | ciphertext). The key is BIMBA_SECRET_KEY (any text, hashed to 256 bits)
 * or, when that is not set, a random key created once in the data folder (data/secret.key). Without the key the stored
 * secrets cannot be read, so keep the data volume (or set BIMBA_SECRET_KEY) when moving the gateway.
 */
@Component
public class SecretBox {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Secrets");
    public static final String PREFIX = "enc:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretBox(@Value("${bimba.data-dir:data}") File dataDir, @Value("${BIMBA_SECRET_KEY:}") String configured) throws Exception {
        byte[] raw;
        if (!configured.isBlank()) {
            raw = MessageDigest.getInstance("SHA-256").digest(configured.trim().getBytes(StandardCharsets.UTF_8));
        } else {
            File f = new File(dataDir, "secret.key");
            if (f.isFile()) {
                raw = Base64.getDecoder().decode(Files.readString(f.toPath()).trim());
            } else {
                Files.createDirectories(dataDir.toPath());
                raw = new byte[32];
                RANDOM.nextBytes(raw);
                Files.writeString(f.toPath(), Base64.getEncoder().encodeToString(raw) + "\n");
                try {
                    Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // Windows
                }
                log.info("created the secret key {} (set BIMBA_SECRET_KEY to use your own)", f);
            }
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public static boolean isSealed(String v) {
        return v != null && v.startsWith(PREFIX);
    }

    /** Encrypts a plain value; empty values, "${ENV_VAR}" references and sealed values are returned as they are. */
    public String seal(String plain) {
        if (plain == null || plain.isEmpty() || isSealed(plain) || (plain.startsWith("${") && plain.endsWith("}"))) {
            return plain;
        }
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("could not encrypt a secret", e);
        }
    }

    /** Decrypts a sealed value; other values are returned as they are. Throws when the key does not fit. */
    public String open(String value) {
        if (!isSealed(value)) {
            return value;
        }
        try {
            byte[] in = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("a stored secret cannot be decrypted with this gateway's key"
                    + " (data/secret.key or BIMBA_SECRET_KEY changed) - enter it again in Delivery", e);
        }
    }
}
