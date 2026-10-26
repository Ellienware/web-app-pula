package com.momocircle.security;

import com.momocircle.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the two properties that matter for brute-force protection:
 * repeated failures against the same email eventually lock it out, and a
 * successful login clears the count so a legitimate user who mistyped
 * their password a couple of times isn't punished after they get it right.
 */
class LoginRateLimiterTest {

    private LoginRateLimiter newLimiter(int maxAttempts) {
        return new LoginRateLimiter(maxAttempts, 15);
    }

    @Test
    void allowsAttemptsUnderTheLimit() {
        LoginRateLimiter limiter = newLimiter(3);
        assertDoesNotThrow(() -> limiter.checkAllowed("user@example.com"));
        limiter.recordFailure("user@example.com");
        assertDoesNotThrow(() -> limiter.checkAllowed("user@example.com"));
        limiter.recordFailure("user@example.com");
        assertDoesNotThrow(() -> limiter.checkAllowed("user@example.com"));
    }

    @Test
    void locksOutAfterTheConfiguredNumberOfFailures() {
        LoginRateLimiter limiter = newLimiter(3);
        limiter.recordFailure("user@example.com");
        limiter.recordFailure("user@example.com");
        limiter.recordFailure("user@example.com");

        assertThrows(ApiException.class, () -> limiter.checkAllowed("user@example.com"));
    }

    @Test
    void successfulLoginClearsThePriorFailureCount() {
        LoginRateLimiter limiter = newLimiter(3);
        limiter.recordFailure("user@example.com");
        limiter.recordFailure("user@example.com");
        limiter.recordSuccess("user@example.com");

        // Would have been locked out on the 3rd consecutive failure if the count hadn't reset.
        limiter.recordFailure("user@example.com");
        limiter.recordFailure("user@example.com");
        assertDoesNotThrow(() -> limiter.checkAllowed("user@example.com"));
    }

    @Test
    void isKeyedPerEmailNotGlobally() {
        LoginRateLimiter limiter = newLimiter(2);
        limiter.recordFailure("victim@example.com");
        limiter.recordFailure("victim@example.com");

        assertThrows(ApiException.class, () -> limiter.checkAllowed("victim@example.com"));
        // A different email is entirely unaffected by another account's lockout.
        assertDoesNotThrow(() -> limiter.checkAllowed("someone-else@example.com"));
    }

    @Test
    void emailMatchingIsCaseAndWhitespaceInsensitive() {
        LoginRateLimiter limiter = newLimiter(1);
        limiter.recordFailure("  User@Example.com  ");

        assertThrows(ApiException.class, () -> limiter.checkAllowed("user@example.com"),
                "an attacker can't dodge the limiter by varying case/whitespace");
    }

    /** Mutable clock so eviction can be tested without sleeping. */
    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant now = java.time.Instant.parse("2026-01-01T00:00:00Z");
        void advance(java.time.Duration d) { now = now.plus(d); }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return now; }
    }

    @Test
    void expiredWindowsAreEvictedSoMemoryDoesNotGrowWithoutBound() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = newLimiter(3).withClockForTests(clock);
        for (int i = 0; i < 1500; i++) {
            limiter.recordFailure("spray" + i + "@example.com");
        }
        org.junit.jupiter.api.Assertions.assertEquals(1500, limiter.trackedEmails());

        clock.advance(java.time.Duration.ofMinutes(16)); // every window above has now expired
        limiter.recordFailure("trigger@example.com");     // first purge is due immediately (lastPurge starts at 0)

        org.junit.jupiter.api.Assertions.assertEquals(1, limiter.trackedEmails());
    }

    @Test
    void activeLockoutsSurviveEviction() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = newLimiter(2).withClockForTests(clock);
        limiter.recordFailure("victim@example.com");
        limiter.recordFailure("victim@example.com"); // locked
        for (int i = 0; i < 1200; i++) {
            limiter.recordFailure("noise" + i + "@example.com");
        }
        clock.advance(java.time.Duration.ofMinutes(5)); // still inside the 15-minute window
        limiter.recordFailure("trigger@example.com");

        assertThrows(ApiException.class, () -> limiter.checkAllowed("victim@example.com"));
        org.junit.jupiter.api.Assertions.assertTrue(limiter.trackedEmails() >= 1201);
    }

    @Test
    void lockoutEndsWhenTheWindowExpires() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = newLimiter(1).withClockForTests(clock);
        limiter.recordFailure("user@example.com");
        assertThrows(ApiException.class, () -> limiter.checkAllowed("user@example.com"));
        clock.advance(java.time.Duration.ofMinutes(16));
        assertDoesNotThrow(() -> limiter.checkAllowed("user@example.com"));
    }
}
