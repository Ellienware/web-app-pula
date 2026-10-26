package com.momocircle.wallet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit test (no Spring, no database): the QR/share code format and its tamper detection. */
class QrTokenServiceTest {

    private final QrTokenService qr = new QrTokenService("a-test-secret-that-is-long-enough-0123456789");

    @Test
    void roundTripsAndIsOpaque() {
        String ref = qr.newReference();
        String code = qr.tokenFor(ref);
        assertEquals(ref, qr.verify(code).orElseThrow());
        assertTrue(code.matches("PULAPAY1\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{16}"));
        assertNotEquals(ref, qr.newReference());
    }

    @Test
    void toleratesSurroundingWhitespaceButNothingElse() {
        String code = qr.tokenFor(qr.newReference());
        assertTrue(qr.verify("  " + code + "\n").isPresent());
        assertFalse(qr.verify(code + "x").isPresent());
        assertFalse(qr.verify("x" + code).isPresent());
    }

    @Test
    void anyChangedCharacterInRefOrTagIsRejected() {
        String code = qr.tokenFor(qr.newReference());
        int start = "PULAPAY1.".length();
        for (int i = start; i < code.length(); i++) {
            if (code.charAt(i) == '.') continue;
            char c = code.charAt(i) == 'A' ? 'B' : 'A';
            String tampered = code.substring(0, i) + c + code.substring(i + 1);
            assertFalse(qr.verify(tampered).isPresent(), "tampered at " + i);
        }
    }

    @Test
    void aCodeSignedWithAnotherSecretIsRejected() {
        QrTokenService other = new QrTokenService("a-different-secret-that-is-long-enough-987654321");
        assertFalse(qr.verify(other.tokenFor(other.newReference())).isPresent());
    }

    @Test
    void malformedInputNeverThrows() {
        for (String s : new String[]{null, "", " ", "PULAPAY1", "PULAPAY1..", "PULAPAY1.a.b", "....", "\u0000", "é".repeat(100),
                "PULAPAY1." + "A".repeat(22) + "." + "B".repeat(16) + ".extra"}) {
            assertFalse(qr.verify(s).isPresent(), String.valueOf(s));
        }
    }
}
