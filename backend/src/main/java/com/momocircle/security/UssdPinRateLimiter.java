package com.momocircle.security;

import com.momocircle.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lockout for repeated wrong USSD driver-PIN attempts, keyed by driver id.
 * A USSD session has no JWT/password to fall back on for brute-force
 * protection the way the web login flow does, so the PIN itself needs its
 * own throttle. Same single-instance in-memory approach as
 * {@link LoginRateLimiter} / {@link PaymentCodeRateLimiter}.
 */
@Component
public class UssdPinRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final ConcurrentHashMap<String, Attempts> attemptsByDriver = new ConcurrentHashMap<>();

    public UssdPinRateLimiter(
            @Value("${app.momoride.pin-max-attempts:5}") int maxAttempts,
            @Value("${app.momoride.pin-lockout-minutes:15}") long windowMinutes) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    private static final class Attempts {
        int count;
        Instant windowStart = Instant.now();
    }

    public void checkAllowed(String driverId) {
        Attempts attempts = attemptsByDriver.get(driverId);
        if (attempts == null) return;
        synchronized (attempts) {
            if (windowExpired(attempts)) return;
            if (attempts.count >= maxAttempts) {
                throw ApiException.tooManyRequests(
                        "Too many incorrect PIN attempts. Try again later.");
            }
        }
    }

    public void recordFailure(String driverId) {
        attemptsByDriver.compute(driverId, (key, attempts) -> {
            if (attempts == null || windowExpired(attempts)) {
                attempts = new Attempts();
            }
            attempts.count++;
            return attempts;
        });
    }

    public void recordSuccess(String driverId) {
        attemptsByDriver.remove(driverId);
    }

    private boolean windowExpired(Attempts attempts) {
        return Instant.now().isAfter(attempts.windowStart.plus(window));
    }
}
