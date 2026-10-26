package com.momocircle.future;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Small in-memory sliding-window limiter (per user) to protect the AI gateway from abuse and runaway cost. */
public final class CoachRateLimiter {
    private final int max;
    private final long windowMs;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public CoachRateLimiter(int max, long windowMs) { this.max = max; this.windowMs = windowMs; }

    /** Returns true and records the call if the user is within the limit. */
    public boolean tryAcquire(String userId, long nowMs) {
        Deque<Long> q = hits.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && nowMs - q.peekFirst() >= windowMs) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(nowMs);
            return true;
        }
    }
}
