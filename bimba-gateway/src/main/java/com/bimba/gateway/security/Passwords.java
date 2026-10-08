package com.bimba.gateway.security;

import com.bimba.gateway.config.BimbaConfig;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Django-compatible password hashes ("pbkdf2_sha256$iterations$salt$hash"), so one account signs in to the
 * gateway, Django admin and BIMBA Operations; plus the gateway's password policy and temporary passwords.
 */
public final class Passwords {
    /** Django 4.0's default PBKDF2 cost */
    static final int ITERATIONS = 320_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SALT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    /** checked for unknown usernames, so response time does not reveal which accounts exist */
    public static final String DUMMY = "pbkdf2_sha256$" + ITERATIONS + "$bimba$" + Base64.getEncoder().encodeToString(new byte[32]);

    private Passwords() {
    }

    public static String hash(String password) {
        StringBuilder salt = new StringBuilder();
        for (int i = 0; i < 22; i++) {
            salt.append(SALT_CHARS.charAt(RANDOM.nextInt(SALT_CHARS.length())));
        }
        return "pbkdf2_sha256$" + ITERATIONS + "$" + salt + "$"
                + Base64.getEncoder().encodeToString(pbkdf2(password, salt.toString(), ITERATIONS, 32));
    }

    public static boolean matches(String password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        String[] p = encoded.split("\\$", 4);
        if (p.length != 4 || !"pbkdf2_sha256".equals(p[0])) {
            return false;                     // unusable ("!...") or another hasher
        }
        try {
            byte[] expected = Base64.getDecoder().decode(p[3]);
            return MessageDigest.isEqual(expected, pbkdf2(password, p[2], Integer.parseInt(p[1]), expected.length));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, String salt, int iterations, int bytes) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), iterations, bytes * 8);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Problems with a new password (empty = accepted). */
    public static List<String> check(String password, String username, BimbaConfig.Security s) {
        List<String> out = new ArrayList<>();
        if (password == null || password.length() < s.minPasswordLength) {
            out.add("at least " + s.minPasswordLength + " characters");
        }
        if (password != null) {
            int classes = (password.chars().anyMatch(Character::isUpperCase) ? 1 : 0)
                    + (password.chars().anyMatch(Character::isLowerCase) ? 1 : 0)
                    + (password.chars().anyMatch(Character::isDigit) ? 1 : 0)
                    + (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c)) ? 1 : 0);
            if (classes < s.minCharacterClasses) {
                out.add(s.minCharacterClasses + " of: upper case, lower case, digit, symbol");
            }
            if (username != null && username.length() >= 3
                    && password.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
                out.add("must not contain the user name");
            }
            if (password.length() > 200) {
                out.add("at most 200 characters");
            }
        }
        return out;
    }

    /** Readable temporary password that satisfies the default policy (shown once, must be changed). */
    public static String temporary() {
        String words = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < 4; g++) {
            if (g > 0) {
                sb.append('-');
            }
            for (int i = 0; i < 4; i++) {
                sb.append(words.charAt(RANDOM.nextInt(words.length())));
            }
        }
        // guarantee every class: one upper, one lower, one digit, '-' already a symbol
        sb.setCharAt(0, "ABCDEFGHJKLMNPQRSTUVWXYZ".charAt(RANDOM.nextInt(24)));
        sb.setCharAt(1, "abcdefghijkmnopqrstuvwxyz".charAt(RANDOM.nextInt(25)));
        sb.setCharAt(2, "23456789".charAt(RANDOM.nextInt(8)));
        return sb.toString();
    }
}
