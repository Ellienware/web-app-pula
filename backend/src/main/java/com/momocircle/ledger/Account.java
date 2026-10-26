package com.momocircle.ledger;

/**
 * A ledger account. Balance convention for every account: {@code balance = credits - debits} (in minor units).
 * User wallets may never go negative; system accounts that act as a source or sink (demo funding, clearing) may.
 * The sum of all balances across all accounts is always zero, which {@link Reconciler} verifies.
 */
public record Account(String id, Kind kind, String ownerUserId, String ownerBusinessId, String currency, boolean allowNegative) {

    public enum Kind {
        /** A user's PULA Money demo wallet. Owned by exactly one user. */
        USER_WALLET,
        /** A business's wallet. Owned by a business identity (never by its owner's user id); access is by verified membership. */
        BUSINESS_WALLET,
        /** Source of demo value. Goes negative by exactly the amount of demo value issued. */
        DEMO_FUNDING,
        /** Sink for simulated withdrawal requests. No real money leaves the system. */
        WITHDRAWAL_CLEARING
    }

    /** Compatibility constructor for accounts with no business owner (user wallets and system accounts). */
    public Account(String id, Kind kind, String ownerUserId, String currency, boolean allowNegative) {
        this(id, kind, ownerUserId, null, currency, allowNegative);
    }

    public Account {
        if (kind == Kind.BUSINESS_WALLET) {
            if (ownerBusinessId == null || ownerBusinessId.isBlank()) {
                throw new IllegalArgumentException("A business wallet must have a business owner");
            }
            if (ownerUserId != null) {
                throw new IllegalArgumentException("A business wallet is owned by a business, never by a user id");
            }
            if (allowNegative) {
                throw new IllegalArgumentException("A business wallet can never be allowed to go negative");
            }
        } else if (ownerBusinessId != null) {
            throw new IllegalArgumentException("Only a business wallet can have a business owner");
        }
        if (kind == Kind.USER_WALLET && (ownerUserId == null || ownerUserId.isBlank())) {
            throw new IllegalArgumentException("A user wallet must have an owner");
        }
        if (kind == Kind.USER_WALLET && allowNegative) {
            throw new IllegalArgumentException("A user wallet can never be allowed to go negative");
        }
    }
}
