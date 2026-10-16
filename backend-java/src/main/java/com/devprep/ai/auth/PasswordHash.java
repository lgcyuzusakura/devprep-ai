package com.devprep.ai.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHash {
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private PasswordHash() {}
    public static String hash(String password) {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }
    public static boolean matches(String password, String hash) {
        try {
            String[] parts = hash.split("\\$");
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 1_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            if (salt.length != 16) return false;
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]), derive(password, salt, iterations));
        } catch (IllegalArgumentException ex) { return false; }
    }
    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] chars = password.toCharArray();
        var spec = new PBEKeySpec(chars, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("Password hashing unavailable", ex); }
        finally { spec.clearPassword(); Arrays.fill(chars, '\0'); }
    }
}
