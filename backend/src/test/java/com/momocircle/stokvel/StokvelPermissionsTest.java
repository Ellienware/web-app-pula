package com.momocircle.stokvel;

import static com.momocircle.stokvel.StokvelPermissions.Action.*;
import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.enums.CircleRole;
import com.momocircle.stokvel.StokvelPermissions.Action;
import org.junit.jupiter.api.Test;

class StokvelPermissionsTest {

    private static boolean ok(CircleRole r, Action a) {
        return StokvelPermissions.allows(r, a, false);
    }

    @Test
    void legacyAdminKeepsEveryPowerItHadAndOwnerIsAdmin() {
        assertTrue(StokvelPermissions.isLegacyAdmin(CircleRole.OWNER));
        assertTrue(StokvelPermissions.isLegacyAdmin(CircleRole.ADMIN));
        for (Action a : new Action[] {UPDATE_SETTINGS, RECORD_PAYOUT, CREATE_REQUEST, VOTE_ON_REQUEST, CANCEL_ANY_REQUEST}) {
            assertTrue(ok(CircleRole.ADMIN, a), "ADMIN must keep " + a);
        }
    }

    @Test
    void newRolesAreNotLegacyAdmins() {
        assertFalse(StokvelPermissions.isLegacyAdmin(CircleRole.TREASURER));
        assertFalse(StokvelPermissions.isLegacyAdmin(CircleRole.CHAIRPERSON));
        assertFalse(StokvelPermissions.isLegacyAdmin(CircleRole.MEMBER));
        assertFalse(ok(CircleRole.TREASURER, UPDATE_SETTINGS));
        assertFalse(ok(CircleRole.CHAIRPERSON, UPDATE_SETTINGS));
    }

    @Test
    void onlyTheFounderMayOverrideTheRotation() {
        assertTrue(ok(CircleRole.OWNER, OVERRIDE_ROTATION));
        for (CircleRole r : new CircleRole[] {CircleRole.ADMIN, CircleRole.TREASURER, CircleRole.CHAIRPERSON, CircleRole.MEMBER}) {
            assertFalse(ok(r, OVERRIDE_ROTATION), r + " must not override rotation");
        }
    }

    @Test
    void treasurerRecordsPayoutsAndCreatesRequestsButChairpersonDoesNotRecordPayouts() {
        assertTrue(ok(CircleRole.TREASURER, RECORD_PAYOUT));
        assertTrue(ok(CircleRole.TREASURER, CREATE_REQUEST));
        assertFalse(ok(CircleRole.CHAIRPERSON, RECORD_PAYOUT));
        assertTrue(ok(CircleRole.CHAIRPERSON, CREATE_REQUEST));
        assertTrue(ok(CircleRole.CHAIRPERSON, CANCEL_ANY_REQUEST));
        assertFalse(ok(CircleRole.TREASURER, CANCEL_ANY_REQUEST));
    }

    @Test
    void plainMembersCanDoNothingPrivilegedUnlessVotingIsOpened() {
        for (Action a : Action.values()) {
            assertFalse(ok(CircleRole.MEMBER, a), "MEMBER must not " + a);
        }
        assertTrue(StokvelPermissions.allows(CircleRole.MEMBER, VOTE_ON_REQUEST, true));
        assertFalse(StokvelPermissions.allows(CircleRole.MEMBER, CREATE_REQUEST, true));
    }

    @Test
    void nullRoleIsDenied() {
        for (Action a : Action.values()) {
            assertFalse(StokvelPermissions.allows(null, a, true));
        }
    }
}
