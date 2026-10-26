package com.momocircle.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit test (no Spring, no database) for invite code generation, normalisation and hashing. */
class StokvelInviteCodesTest {

    @Test
    void generatedCodesAreSixteenCrockfordCharactersAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            String c = StokvelInviteCodes.generate();
            assertTrue(c.matches("[0-9A-HJKMNP-TV-Z]{16}"), c);
            assertTrue(seen.add(c), "collision at " + i);
        }
    }

    @Test
    void displayAndNormaliseRoundTrip() {
        String c = StokvelInviteCodes.generate();
        String shown = StokvelInviteCodes.display(c);
        assertTrue(shown.matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}"), shown);
        assertEquals(c, StokvelInviteCodes.normalise(shown).orElseThrow());
        assertEquals(c, StokvelInviteCodes.normalise(shown.toLowerCase()).orElseThrow(), "case-insensitive");
        assertEquals(c, StokvelInviteCodes.normalise("  " + shown.replace("-", " ") + " \n").orElseThrow(), "spaces and dashes ignored");
    }

    @Test
    void lookAlikeLettersAreMappedTheWayCrockfordDefines() {
        assertEquals("0000000000000000", StokvelInviteCodes.normalise("OOOO-OOOO-OOOO-OOOO").orElseThrow());
        assertEquals("1111111111111111", StokvelInviteCodes.normalise("IIII-LLLL-iiii-llll").orElseThrow());
    }

    @Test
    void anythingThatCannotBeACodeIsRejectedBeforeTheDatabase() {
        for (String bad : new String[]{null, "", "   ", "ABC", "0123-4567-89AB-CDE", "0123-4567-89AB-CDEFG", "0123-4567-89AB-CDEU",
                "0123-4567-89AB-CDE!", "'; DROP TABLE users; --", "é".repeat(16), "A".repeat(200), "0123-4567-89AB-CDE\u0000"}) {
            assertFalse(StokvelInviteCodes.normalise(bad).isPresent(), String.valueOf(bad));
        }
    }

    @Test
    void hashIsStableHexAndDoesNotRevealTheCode() {
        String c = "0123456789ABCDEF";
        String h = StokvelInviteCodes.hash(c);
        assertEquals(h, StokvelInviteCodes.hash(c));
        assertTrue(h.matches("[0-9a-f]{64}"));
        assertFalse(h.contains("0123456789abcdef"));
        assertNotEquals(h, StokvelInviteCodes.hash("0123456789ABCDEG"));
        assertEquals("CDEF", StokvelInviteCodes.hint(c));
    }
}
