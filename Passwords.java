package com.petsa.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing with PBKDF2-HMAC-SHA256 (built into the JDK), random per-password salt.
 * Stored format:  pbkdf2_sha256$ITERATIONS$BASE64_SALT$BASE64_HASH
 */
public final class Passwords {
    private Passwords() {}

    private static final int ITERATIONS = 600_000; // OWASP guidance for PBKDF2-HMAC-SHA256
    private static final int SALT_BYTES = 16, KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY = hash("dummy-password-for-timing");

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] h = derive(password, salt, ITERATIONS);
        Base64.Encoder b = Base64.getEncoder();
        return "pbkdf2_sha256$" + ITERATIONS + "$" + b.encodeToString(salt) + "$" + b.encodeToString(h);
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] p = stored.split("\\$");
            if (p.length != 4 || !p[0].equals("pbkdf2_sha256")) return false;
            int iterations = Integer.parseInt(p[1]);
            if (iterations < 10_000 || iterations > 5_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations)); // constant-time
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Burns the same time as a real check, so unknown login IDs are not distinguishable by timing. */
    public static void verifyDummy(String password) {
        verify(password, DUMMY);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    public static String newToken() {
        byte[] t = new byte[32];
        RANDOM.nextBytes(t);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(t);
    }

    public static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
