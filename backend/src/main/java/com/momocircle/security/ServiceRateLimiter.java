package com.momocircle.security;

import com.momocircle.exception.ApiException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window limiter for service marketplace writes (requests, quotes, material edits). Same single-instance,
 * in-memory trade-off as {@link LoginRateLimiter}: it blunts abuse from one account, it is not a distributed limit.
 */
@Component
public class ServiceRateLimiter {

    private static final class Window {
        int count;
        Instant start = Instant.now();
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** Records one use of {@code bucket} by {@code key}; throws 429 when more than {@code max} happen within {@code window}. */
    public void check(String bucket, String key, int max, Duration window) {
        String k = bucket + "|" + key;
        Window w = windows.compute(k, (kk, cur) -> {
            Instant now = Instant.now();
            if (cur == null || now.isAfter(cur.start.plus(window))) {
                Window fresh = new Window();
                fresh.count = 1;
                return fresh;
            }
            cur.count++;
            return cur;
        });
        if (w.count > max) {
            throw ApiException.tooManyRequests("Too many requests. Please try again later.");
        }
    }
}
