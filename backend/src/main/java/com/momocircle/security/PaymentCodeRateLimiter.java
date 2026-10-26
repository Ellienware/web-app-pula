package com.momocircle.security;

import com.momocircle.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection on MoMoRide taxi payment-code lookup/payment
 * (GET /momoride/taxis/by-code/{code}, POST /momoride/payments/taxi). Payment
 * codes are short (5 digits, ~100,000 possibilities) specifically so a
 * passenger can type them quickly — which also makes them guessable if an
 * attacker can try unlimited codes. Keyed by the authenticated caller's user
 * id (every MoMoRide endpoint requires auth), so a single account cannot
 * sweep the code space no matter how many codes it tries.
 *
 * <p>Same single-instance, in-memory caveat as {@link LoginRateLimiter} — see
 * its javadoc for the reasoning; this app is a single-instance modular
 * monolith today.
 */
@Component
public class PaymentCodeRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final ConcurrentHashMap<String, Attempts> attemptsByUser = new ConcurrentHashMap<>();

    public PaymentCodeRateLimiter(
            @Value("${app.momoride.payment-code-max-attempts:20}") int maxAttempts,
            @Value("${app.momoride.payment-code-window-minutes:15}") long windowMinutes) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    private static final class Attempts {
        int count;
        Instant windowStart = Instant.now();
    }

    public void checkAllowed(String userId) {
        Attempts attempts = attemptsByUser.get(userId);
        if (attempts == null) return;
        synchronized (attempts) {
            if (windowExpired(attempts)) return;
            if (attempts.count >= maxAttempts) {
                throw ApiException.tooManyRequests(
                        "Too many taxi code lookups. Please try again in a few minutes.");
            }
        }
    }

    public void recordAttempt(String userId) {
        attemptsByUser.compute(userId, (key, attempts) -> {
            if (attempts == null || windowExpired(attempts)) {
                attempts = new Attempts();
            }
            attempts.count++;
            return attempts;
        });
    }

    private boolean windowExpired(Attempts attempts) {
        return Instant.now().isAfter(attempts.windowStart.plus(window));
    }
}
