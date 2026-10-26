package com.momocircle.future;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CoachGuardTest {
    @Test void redactsPinsOtpsEmailsButKeepsAmounts() {
        assertEquals("my pin is [number removed] and email [email removed]", CoachGuard.redact("my pin is 123456 and email a@b.com"));
        assertEquals("I save 500 a month", CoachGuard.redact("I save 500 a month"));
        assertTrue(CoachGuard.redact("x".repeat(900)).length() <= 500);
    }
    @Test void rejectsGuaranteesAdviceAndActionClaims() {
        for (String bad : List.of("This fund gives guaranteed returns.", "It is risk-free.", "You should buy this ETF now.",
                "I am a licensed financial adviser.", "I have transferred R500 to your goal.", "  ")) {
            assertFalse(CoachGuard.isSafe(bad), bad);
        }
    }
    @Test void allowsHonestNegatedStatements() {
        assertTrue(CoachGuard.isSafe("Fees reduce growth. Returns are not guaranteed."));
        assertTrue(CoachGuard.isSafe("There is no guarantee of returns."));
    }
    @Test void fallbackExplainsWarningsFromServerFigures() {
        var ctx = new CoachGuard.Context(false, 1_000_000L, 800_000L, 900_000L, 0L, List.of("Proposed allocations exceed the amount available."));
        var a = CoachGuard.fallback("Why does my plan show a warning?", ctx);
        assertEquals("fallback", a.source());
        assertTrue(a.text().contains("exceed the amount available"));
        assertTrue(a.text().contains("not a licensed financial adviser"));
    }
    @Test void everyFallbackAnswerPassesTheSameSafetyGuard() {
        var ctx = new CoachGuard.Context(true, 1L, 1L, 1L, 0L, List.of());
        for (String q : List.of("scam", "fees", "inflation", "compound", "emergency fund", "etf", "how much per month", "explain", "zzz", "")) {
            assertTrue(CoachGuard.isSafe(CoachGuard.fallback(q, ctx).text()), q);
        }
    }
}
