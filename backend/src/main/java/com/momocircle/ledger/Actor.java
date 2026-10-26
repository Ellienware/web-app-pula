package com.momocircle.ledger;

import java.util.Objects;

/**
 * Who is performing an operation. MUST be derived server-side from the authenticated principal; it is never read from a
 * request body. {@code system} is for scheduled jobs; {@code admin} for authorised operators.
 */
public record Actor(String userId, boolean admin, boolean system, String actingBusinessId) {

    /** Compatibility constructor: an actor not acting for any business. */
    public Actor(String userId, boolean admin, boolean system) {
        this(userId, admin, system, null);
    }

    public Actor {
        Objects.requireNonNull(userId, "userId");
    }

    /**
     * A user acting on behalf of ONE business wallet. The ledger engine trusts this marker, so it must only ever be created
     * by {@code BusinessWalletAccessService} after it has verified the user's membership and permission for that business.
     * Such an actor can debit only that business's wallet, never a personal wallet.
     */
    public static Actor forBusiness(String userId, String businessId) {
        Objects.requireNonNull(businessId, "businessId");
        return new Actor(userId, false, false, businessId);
    }

    public static Actor ofUser(String userId) {
        return new Actor(userId, false, false);
    }

    public static Actor asAdmin(String userId) {
        return new Actor(userId, true, false);
    }

    public static Actor asSystem() {
        return new Actor("system", false, true);
    }
}
