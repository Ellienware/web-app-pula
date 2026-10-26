package com.momocircle.stokvel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure, deterministic rotation rules. No persistence, no clock, no randomness. */
public final class RotationOrder {

    private RotationOrder() {}

    public record Joiner(String userId, Instant joinedAt, String membershipId) {}

    /** Default payout order: member join order; ties broken by membership id so the result is always the same. */
    public static List<String> joinOrder(List<Joiner> joiners) {
        return joiners.stream()
                .sorted(Comparator.comparing(Joiner::joinedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Joiner::membershipId))
                .map(Joiner::userId)
                .toList();
    }

    /** The founder's override must be exactly the current member set, each once. */
    public static void requirePermutation(Set<String> currentMembers, List<String> proposed) {
        if (proposed == null || proposed.isEmpty()) {
            throw new IllegalArgumentException("Order must list every member");
        }
        Set<String> seen = new HashSet<>();
        for (String id : proposed) {
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Order lists a member more than once");
            }
        }
        if (!seen.equals(currentMembers)) {
            throw new IllegalArgumentException("Order must contain exactly the current members");
        }
    }

    /** Appends members that are not yet in the order (joined later) in their join order. */
    public static List<String> appendMissing(List<String> existingOrder, List<String> joinOrderOfAllMembers) {
        List<String> out = new ArrayList<>(existingOrder);
        Set<String> have = new HashSet<>(existingOrder);
        for (String id : joinOrderOfAllMembers) {
            if (have.add(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Beneficiary for payout cycle {@code cycleNumber} (1-based). The order repeats every round. Slot holders who
     * have since left the stokvel are skipped; returns null only if nobody in the order is still a member.
     */
    public static String beneficiary(List<String> order, Set<String> activeMembers, int cycleNumber) {
        if (cycleNumber < 1) {
            throw new IllegalArgumentException("cycleNumber must be >= 1");
        }
        int n = order.size();
        for (int i = 0; i < n; i++) {
            String candidate = order.get((cycleNumber - 1 + i) % n);
            if (activeMembers.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
