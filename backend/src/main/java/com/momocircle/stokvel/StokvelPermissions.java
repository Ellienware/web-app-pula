package com.momocircle.stokvel;

import com.momocircle.domain.enums.CircleRole;

/**
 * Explicit stokvel permission matrix. Backend authorisation is authoritative; clients only mirror this.
 *
 * Legacy ADMIN keeps every power it had before stokvels existed (see {@link #isLegacyAdmin}). TREASURER and
 * CHAIRPERSON are new roles and get ONLY what is listed here: they are deliberately NOT "admins" for the
 * existing Savings services (add member, goals, recurring plans, invitations, ...).
 */
public final class StokvelPermissions {

    private StokvelPermissions() {}

    public enum Action {
        UPDATE_SETTINGS,
        OVERRIDE_ROTATION,
        RECORD_PAYOUT,
        CREATE_REQUEST,
        VOTE_ON_REQUEST,
        CANCEL_ANY_REQUEST,
        RECORD_EXTERNAL_EXECUTION
    }

    /** OWNER or ADMIN: the roles that pass the pre-existing Savings admin checks. */
    public static boolean isLegacyAdmin(CircleRole role) {
        return role == CircleRole.OWNER || role == CircleRole.ADMIN;
    }

    public static boolean allows(CircleRole role, Action action, boolean membersCanVote) {
        if (role == null) {
            return false;
        }
        return switch (action) {
            case UPDATE_SETTINGS -> isLegacyAdmin(role);
            // The founder only. ADMIN is not reduced (this is a new power), it is simply not granted.
            case OVERRIDE_ROTATION -> role == CircleRole.OWNER;
            case RECORD_PAYOUT, RECORD_EXTERNAL_EXECUTION ->
                    isLegacyAdmin(role) || role == CircleRole.TREASURER;
            case CREATE_REQUEST -> isLegacyAdmin(role)
                    || role == CircleRole.TREASURER || role == CircleRole.CHAIRPERSON;
            case VOTE_ON_REQUEST -> membersCanVote || isLegacyAdmin(role)
                    || role == CircleRole.TREASURER || role == CircleRole.CHAIRPERSON;
            case CANCEL_ANY_REQUEST -> isLegacyAdmin(role) || role == CircleRole.CHAIRPERSON;
        };
    }
}
