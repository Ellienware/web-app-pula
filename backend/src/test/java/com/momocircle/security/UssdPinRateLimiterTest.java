package com.momocircle.security;

import com.momocircle.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves USSD driver-PIN lockout after repeated wrong attempts, and that success clears it. */
class UssdPinRateLimiterTest {

    private UssdPinRateLimiter newLimiter(int maxAttempts) {
        return new UssdPinRateLimiter(maxAttempts, 15);
    }

    @Test
    void locksOutAfterConfiguredFailures() {
        UssdPinRateLimiter limiter = newLimiter(3);
        limiter.recordFailure("driver-1");
        limiter.recordFailure("driver-1");
        limiter.recordFailure("driver-1");

        assertThrows(ApiException.class, () -> limiter.checkAllowed("driver-1"));
    }

    @Test
    void successClearsPriorFailures() {
        UssdPinRateLimiter limiter = newLimiter(3);
        limiter.recordFailure("driver-1");
        limiter.recordFailure("driver-1");
        limiter.recordSuccess("driver-1");

        limiter.recordFailure("driver-1");
        limiter.recordFailure("driver-1");
        assertDoesNotThrow(() -> limiter.checkAllowed("driver-1"));
    }

    @Test
    void isKeyedPerDriver() {
        UssdPinRateLimiter limiter = newLimiter(1);
        limiter.recordFailure("driver-1");

        assertThrows(ApiException.class, () -> limiter.checkAllowed("driver-1"));
        assertDoesNotThrow(() -> limiter.checkAllowed("driver-2"));
    }
}
