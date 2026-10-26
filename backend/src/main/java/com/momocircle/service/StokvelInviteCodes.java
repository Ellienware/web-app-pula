package com.momocircle.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;

/**
 * Invite codes: 16 characters of Crockford base32 (80 random bits), shown as XXXX-XXXX-XXXX-XXXX. The server stores only the
 * SHA-256 of the normalised code, so a database leak does not reveal usable codes. 80 bits is not brute-forceable and the
 * endpoints that accept codes are also rate limited and require authentication.
 */
public final class StokvelInviteCodes {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford: no I, L, O, U
    private static final int LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private StokvelInviteCodes() { }

    /** A fresh random code in normalised form (16 chars, no dashes). */
    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** XXXX-XXXX-XXXX-XXXX for showing and typing. */
    public static String display(String normalised) {
        return normalised.substring(0, 4) + "-" + normalised.substring(4, 8) + "-" + normalised.substring(8, 12) + "-" + normalised.substring(12, 16);
    }

    /**
     * What a user typed or pasted, reduced to the normalised code: case-insensitive, ignores spaces and dashes, and maps the
     * look-alike letters Crockford defines (O to 0, I and L to 1). Empty if it cannot be a code, so garbage never reaches the database.
     */
    public static Optional<String> normalise(String raw) {
        if (raw == null || raw.length() > 64) return Optional.empty();
        StringBuilder sb = new StringBuilder(LENGTH);
        for (char c : raw.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '-' || Character.isWhitespace(c)) continue;
            if (c == 'O') c = '0';
            else if (c == 'I' || c == 'L') c = '1';
            if (ALPHABET.indexOf(c) < 0) return Optional.empty();
            sb.append(c);
        }
        return sb.length() == LENGTH ? Optional.of(sb.toString()) : Optional.empty();
    }

    public static String hash(String normalised) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Last four characters, shown in the admin's link list so a code can be recognised without being recoverable. */
    public static String hint(String normalised) {
        return normalised.substring(LENGTH - 4);
    }
}
