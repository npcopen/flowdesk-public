package com.flowdesk.iam.service;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA256 password hashing (OWASP-recommended parameter class).
 *
 * <p>Encoded form: {@code pbkdf2_sha256$<iterations>$<saltBase64>$<hashBase64>}.
 * Verification is constant-time and unknown/malformed stored hashes fail closed.</p>
 */
@Component
public final class PasswordHashService {

    public static final String SCHEME = "pbkdf2_sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final byte[] TIMING_DUMMY_HASH = new PasswordHashService()
            .encode("flowdesk-timing-dummy-password");

    public byte[] encode(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = derive(rawPassword, salt, ITERATIONS, HASH_BYTES);
        String encoded = SCHEME + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Performs one full PBKDF2 derivation against a fixed dummy verifier. Login uses this
     * when no credential candidate existed, keeping the observable cost similar to a real
     * wrong-password attempt and avoiding a username-existence timing oracle.
     */
    public void consumeTimingDummy(String rawPassword) {
        matches(rawPassword, TIMING_DUMMY_HASH);
    }

    public boolean matches(String rawPassword, byte[] stored) {
        if (rawPassword == null || stored == null || stored.length == 0) {
            return false;
        }
        String encoded = new String(stored, StandardCharsets.UTF_8);
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !SCHEME.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations <= 0 || iterations > 10_000_000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length == 0 || salt.length > 64 || expected.length == 0 || expected.length > 128) {
                return false;
            }
            byte[] actual = derive(rawPassword, salt, iterations, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        } catch (IllegalStateException e) {
            // Fail closed on malformed/unverifiable stored material.
            return false;
        }
    }

    private static byte[] derive(String rawPassword, byte[] salt, int iterations, int hashBytes) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, hashBytes * 8);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password derivation failed", e);
        }
    }
}
