package com.momocircle.wallet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Builds and verifies the code placed in a QR image / shared as text: {@code PULAPAY1.<ref>.<tag>}.
 *
 * <ul>
 *   <li>{@code ref} is 128 random bits, unguessable and meaningless on its own.</li>
 *   <li>{@code tag} is a truncated HMAC-SHA256 of the ref under a key derived from a server secret, so an altered or forged
 *       code is rejected before any database lookup.</li>
 *   <li>The code contains NO amount, recipient, instruction or user data. Scanning it can only open a request for review;
 *       it cannot move value. Amount and status are always read from the server at confirmation time.</li>
 * </ul>
 */
@Service
public class QrTokenService {

    public static final String PREFIX = "PULAPAY1";
    private static final int TAG_BYTES = 12;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();
    private final byte[] key;

    public QrTokenService(@Value("${app.wallet.qr-secret:${app.security.jwt-secret}}") String secret) {
        this.key = hmac(secret.getBytes(StandardCharsets.UTF_8), "pula.wallet.qr.v1".getBytes(StandardCharsets.UTF_8));
    }

    public String newReference() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return B64.encodeToString(b);
    }

    public String tokenFor(String reference) {
        return PREFIX + "." + reference + "." + tag(reference);
    }

    /** The reference if {@code code} is well-formed and its signature is valid; empty otherwise. Never throws on bad input. */
    public Optional<String> verify(String code) {
        if (code == null) return Optional.empty();
        String c = code.trim();
        if (c.length() > 80) return Optional.empty();
        String[] parts = c.split("\\.", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) return Optional.empty();
        String ref = parts[1];
        if (!ref.matches("[A-Za-z0-9_-]{22}") || !parts[2].matches("[A-Za-z0-9_-]{16}")) return Optional.empty();
        byte[] expected = tag(ref).getBytes(StandardCharsets.UTF_8);
        byte[] actual = parts[2].getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual) ? Optional.of(ref) : Optional.empty();
    }

    private String tag(String reference) {
        byte[] full = hmac(key, reference.getBytes(StandardCharsets.UTF_8));
        byte[] cut = new byte[TAG_BYTES];
        System.arraycopy(full, 0, cut, 0, TAG_BYTES);
        return B64.encodeToString(cut);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
