package com.momocircle.stokvel;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.stokvel.RotationOrder.Joiner;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RotationOrderTest {

    private static Joiner j(String user, long epoch, String membership) {
        return new Joiner(user, Instant.ofEpochSecond(epoch), membership);
    }

    @Test
    void defaultOrderIsJoinOrder() {
        List<String> order = RotationOrder.joinOrder(List.of(j("c", 300, "m3"), j("a", 100, "m1"), j("b", 200, "m2")));
        assertEquals(List.of("a", "b", "c"), order);
    }

    @Test
    void joinOrderTiesAreBrokenByMembershipIdSoItIsAlwaysTheSame() {
        var in1 = List.of(j("x", 100, "m2"), j("y", 100, "m1"));
        var in2 = List.of(j("y", 100, "m1"), j("x", 100, "m2"));
        assertEquals(RotationOrder.joinOrder(in1), RotationOrder.joinOrder(in2));
        assertEquals(List.of("y", "x"), RotationOrder.joinOrder(in1));
    }

    @Test
    void founderOverrideMustBeExactlyTheCurrentMembers() {
        Set<String> members = Set.of("a", "b", "c");
        assertDoesNotThrow(() -> RotationOrder.requirePermutation(members, List.of("c", "a", "b")));
        assertThrows(IllegalArgumentException.class, () -> RotationOrder.requirePermutation(members, List.of("a", "b")));
        assertThrows(IllegalArgumentException.class, () -> RotationOrder.requirePermutation(members, List.of("a", "a", "b")));
        assertThrows(IllegalArgumentException.class, () -> RotationOrder.requirePermutation(members, List.of("a", "b", "z")));
        assertThrows(IllegalArgumentException.class, () -> RotationOrder.requirePermutation(members, List.of()));
    }

    @Test
    void lateJoinersAreAppendedAtTheEndKeepingAnOverriddenOrder() {
        List<String> merged = RotationOrder.appendMissing(List.of("c", "a"), List.of("a", "b", "c", "d"));
        assertEquals(List.of("c", "a", "b", "d"), merged);
    }

    @Test
    void beneficiaryFollowsTheOrderAndRepeatsEachRound() {
        List<String> order = List.of("a", "b", "c");
        Set<String> all = Set.of("a", "b", "c");
        assertEquals("a", RotationOrder.beneficiary(order, all, 1));
        assertEquals("b", RotationOrder.beneficiary(order, all, 2));
        assertEquals("c", RotationOrder.beneficiary(order, all, 3));
        assertEquals("a", RotationOrder.beneficiary(order, all, 4));
    }

    @Test
    void membersWhoLeftAreSkippedAndNobodyLeftGivesNull() {
        List<String> order = List.of("a", "b", "c");
        assertEquals("c", RotationOrder.beneficiary(order, Set.of("a", "c"), 2));
        assertNull(RotationOrder.beneficiary(order, Set.of(), 1));
    }

    @Test
    void cycleNumbersStartAtOne() {
        assertThrows(IllegalArgumentException.class, () -> RotationOrder.beneficiary(List.of("a"), Set.of("a"), 0));
    }
}
