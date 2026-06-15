package com.helix.common.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2-WITH-HMAC-SHA256 password hashing — JDK-only, no Spring Security dependency.
 * Stored form is {@code <iterations>:<base64 salt>:<base64 hash>}; verification is
 * constant-time. Iteration count is high enough to be a real (if modest) work factor
 * for the demo without slowing login perceptibly.
 */
public final class PasswordHasher {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder ENC = Base64.getEncoder();
    private static final Base64.Decoder DEC = Base64.getDecoder();

    private PasswordHasher() { }

    /** Hashes a plaintext password into the storable {@code iterations:salt:hash} form. */
    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RNG.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS);
        return ITERATIONS + ":" + ENC.encodeToString(salt) + ":" + ENC.encodeToString(hash);
    }

    /** Constant-time verification of a plaintext password against a stored hash. */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] parts = stored.split(":");
        if (parts.length != 3) return false;
        int iterations;
        byte[] salt, expected;
        try {
            iterations = Integer.parseInt(parts[0]);
            salt = DEC.decode(parts[1]);
            expected = DEC.decode(parts[2]);
        } catch (RuntimeException e) {
            return false;
        }
        byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 failure", e);
        }
    }
}
