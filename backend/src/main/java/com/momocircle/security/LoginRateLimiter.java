package com.momocircle.security;

import com.momocircle.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Basic brute-force protection on login — previously there was none at all:
 * an attacker could try unlimited password guesses against any email with
 * no delay, lockout, or rate limit whatsoever.
 *
 * <p>Deliberately a plain in-memory, per-instance limiter rather than a
 * Redis-backed one: this app is a single-instance modular monolith (see
 * the master engineering brief's own "no unnecessary infrastructure"
 * guidance), and a per-process fixed-window counter is a real, meaningful
 * mitigation for that deployment shape. If this app is ever run behind a
 * load balancer with multiple instances, each instance enforces its own
 * window independently — a real gap worth revisiting at that point, not
 * pretended away here.
 *
 * <p>Keyed by the attempted email (normalized), not IP — deliberately, so
 * an attacker rotating source IPs against one target account is still
 * caught; a follow-up could add IP-based limiting too (would need
 * threading {@code HttpServletRequest} down to {@code AuthService}, which
 * this pass didn't do to keep the change minimal and self-contained).
 */
@Component
public class LoginRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final ConcurrentHashMap<String, Attempts> attemptsByEmail = new ConcurrentHashMap<>();

    // Phase 8: expired windows used to stay in the map forever (a stream of distinct emails grew memory without bound).
    // They are now purged opportunistically, at most once per PURGE_INTERVAL_MS, and only once the map is non-trivial.
    // Only EXPIRED entries are removed, so no active lockout or in-window failure count is ever dropped.
    private static final int PURGE_MIN_SIZE = 1_000;
    private static final long PURGE_INTERVAL_MS = 60_000L;
    private final AtomicLong lastPurgeMs = new AtomicLong(0);
    private Clock clock = Clock.systemUTC();

    public LoginRateLimiter(
            @Value("${app.security.login-max-attempts:8}") int maxAttempts,
            @Value("${app.security.login-window-minutes:15}") long windowMinutes) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    private static final class Attempts {
        int count;
        final Instant windowStart;

        Attempts(Instant windowStart) {
            this.windowStart = windowStart;
        }
    }

    /** Test hook only: lets tests move time without sleeping. Not used in production wiring. */
    LoginRateLimiter withClockForTests(Clock testClock) {
        this.clock = testClock;
        return this;
    }

    /** Number of emails currently tracked (for tests/diagnostics). */
    int trackedEmails() {
        return attemptsByEmail.size();
    }

    /** Call before attempting a password check; throws if this email is currently locked out. */
    public void checkAllowed(String email) {
        Attempts attempts = attemptsByEmail.get(normalize(email));
        if (attempts == null) {
            return;
        }
        synchronized (attempts) {
            if (windowExpired(attempts)) {
                return; // stale window — recordFailure will reset it on the next failure
            }
            if (attempts.count >= maxAttempts) {
                throw ApiException.tooManyRequests(
                        "Too many failed login attempts. Please try again in a few minutes.");
            }
        }
    }

    /** Call on any failed login (unknown email, wrong password, or inactive account). */
    public void recordFailure(String email) {
        attemptsByEmail.compute(normalize(email), (key, attempts) -> {
            if (attempts == null || windowExpired(attempts)) {
                attempts = new Attempts(clock.instant());
            }
            attempts.count++;
            return attempts;
        });
        purgeExpiredIfDue();
    }

    private void purgeExpiredIfDue() {
        if (attemptsByEmail.size() < PURGE_MIN_SIZE) {
            return;
        }
        long now = clock.millis();
        long last = lastPurgeMs.get();
        if (now - last < PURGE_INTERVAL_MS || !lastPurgeMs.compareAndSet(last, now)) {
            return; // purged recently, or another thread is doing it
        }
        attemptsByEmail.values().removeIf(this::windowExpired);
    }

    /** Call on any successful login — a clean login clears this account's failure count. */
    public void recordSuccess(String email) {
        attemptsByEmail.remove(normalize(email));
    }

    private boolean windowExpired(Attempts attempts) {
        return clock.instant().isAfter(attempts.windowStart.plus(window));
    }

    private String normalize(String email) {
        return email.trim().toLowerCase();
    }
}
