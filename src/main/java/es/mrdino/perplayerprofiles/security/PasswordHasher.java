package es.mrdino.perplayerprofiles.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** One-way hashes with read compatibility for the original 120000-iteration format. */
public final class PasswordHasher {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    public static boolean verify(String password, String encoded) {
        if (password == null || password.length() > 128) return false;
        Parts parts = parse(encoded);
        if (parts == null) return false;
        byte[] actual = derive(password, parts.salt(), parts.iterations());
        try { return MessageDigest.isEqual(parts.hash(), actual); }
        finally { Arrays.fill(actual, (byte) 0); }
    }

    public static boolean needsUpgrade(String encoded) {
        Parts parts = parse(encoded);
        return parts != null && (!encoded.startsWith("pbkdf2-sha256$") || parts.iterations() < ITERATIONS);
    }

    private static Parts parse(String encoded) {
        if (encoded == null || encoded.length() > 512) return null;
        try {
            boolean modern = encoded.startsWith("pbkdf2-sha256$");
            String[] fields = encoded.split(modern ? "\\$" : ":", -1);
            if (fields.length != (modern ? 4 : 3)) return null;
            int offset = modern ? 1 : 0;
            int iterations = Integer.parseInt(fields[offset]);
            byte[] salt = Base64.getDecoder().decode(fields[offset + 1]);
            byte[] hash = Base64.getDecoder().decode(fields[offset + 2]);
            if (iterations < 120_000 || iterations > 2_000_000 || salt.length < 16 || salt.length > 64 || hash.length != 32) return null;
            return new Parts(iterations, salt, hash);
        } catch (IllegalArgumentException malformed) { return null; }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] characters = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(characters, salt, iterations, 256);
        Arrays.fill(characters, '\0');
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException failure) { throw new IllegalStateException("PBKDF2-SHA256 unavailable", failure); }
        finally { spec.clearPassword(); }
    }
    private record Parts(int iterations, byte[] salt, byte[] hash) { }
}
