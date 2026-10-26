package com.momocircle.future;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoachRateLimiterTest {
    @Test void blocksAfterMaxWithinWindowAndRecovers() {
        var l = new CoachRateLimiter(2, 1000);
        assertTrue(l.tryAcquire("u", 0)); assertTrue(l.tryAcquire("u", 10)); assertFalse(l.tryAcquire("u", 20));
        assertTrue(l.tryAcquire("u", 1001));
    }
    @Test void usersAreIndependent() {
        var l = new CoachRateLimiter(1, 1000);
        assertTrue(l.tryAcquire("a", 0)); assertTrue(l.tryAcquire("b", 0)); assertFalse(l.tryAcquire("a", 1));
    }
}
