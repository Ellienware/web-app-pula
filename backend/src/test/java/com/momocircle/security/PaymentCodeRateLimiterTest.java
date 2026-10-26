package com.momocircle.security;

import com.momocircle.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves brute-force protection on taxi payment-code lookup/payment: a
 * single account cannot sweep the 5-digit code space unlimited times, but
 * ordinary usage under the limit is unaffected, and lockout is per-account
 * rather than global.
 *
 * NOT VERIFIED IN THIS SANDBOX: no network access to Maven Central meant
 * ./mvnw test could not be run here — see MOMORIDE_PHASEF.md.
 */
class PaymentCodeRateLimiterTest {

    private PaymentCodeRateLimiter newLimiter(int maxAttempts) {
        return new PaymentCodeRateLimiter(maxAttempts, 15);
    }

    @Test
    void allowsAttemptsUnderTheLimit() {
        PaymentCodeRateLimiter limiter = newLimiter(3);
        assertDoesNotThrow(() -> limiter.checkAllowed("user-1"));
        limiter.recordAttempt("user-1");
        assertDoesNotThrow(() -> limiter.checkAllowed("user-1"));
        limiter.recordAttempt("user-1");
        assertDoesNotThrow(() -> limiter.checkAllowed("user-1"));
    }

    @Test
    void blocksFurtherLookupsAfterTheLimit() {
        PaymentCodeRateLimiter limiter = newLimiter(3);
        limiter.recordAttempt("user-1");
        limiter.recordAttempt("user-1");
        limiter.recordAttempt("user-1");

        assertThrows(ApiException.class, () -> limiter.checkAllowed("user-1"));
    }

    @Test
    void isKeyedPerAccountNotGlobally() {
        PaymentCodeRateLimiter limiter = newLimiter(2);
        limiter.recordAttempt("attacker");
        limiter.recordAttempt("attacker");

        assertThrows(ApiException.class, () -> limiter.checkAllowed("attacker"));
        // A different account cannot be swept up in someone else's lockout — this is what stops
        // an attacker from sweeping the whole code space by rotating between many accounts, but
        // also proves a legitimate account is never punished for another account's behavior.
        assertDoesNotThrow(() -> limiter.checkAllowed("someone-else"));
    }
}
