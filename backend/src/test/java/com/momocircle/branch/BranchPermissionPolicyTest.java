package com.momocircle.branch;

import com.momocircle.branch.BranchAccessPolicy.Relation;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchPermissionPolicyTest {

    private static final Set<BranchPermission> NONE = EnumSet.noneOf(BranchPermission.class);

    @Test
    void ownerHasEverythingAndOutsidersNothing() {
        assertEquals(EnumSet.allOf(BranchPermission.class), BranchAccessPolicy.effective(Relation.OWNER, NONE));
        assertEquals(NONE, BranchAccessPolicy.effective(Relation.NONE, EnumSet.allOf(BranchPermission.class)));
    }

    @Test
    void assigneesHaveExactlyTheirStoredSet() {
        Set<BranchPermission> stored = EnumSet.of(BranchPermission.VIEW_ORDERS, BranchPermission.FULFIL_ORDERS);
        assertTrue(BranchAccessPolicy.has(Relation.STAFF, stored, BranchPermission.FULFIL_ORDERS));
        assertFalse(BranchAccessPolicy.has(Relation.STAFF, stored, BranchPermission.MANAGE_STOCK));
        assertFalse(BranchAccessPolicy.has(Relation.STAFF, NONE, BranchPermission.VIEW_ORDERS));
        assertFalse(BranchAccessPolicy.has(Relation.MANAGER, null, BranchPermission.MANAGE_PRICES));
    }

    @Test
    void ownerMayGrantAnyoneAnything() {
        assertTrue(BranchAccessPolicy.mayGrant(Relation.OWNER, NONE, BranchRole.MANAGER, EnumSet.allOf(BranchPermission.class)));
        assertTrue(BranchAccessPolicy.mayGrant(Relation.OWNER, NONE, BranchRole.STAFF, NONE));
    }

    @Test
    void managerMayOnlyGrantStaffWhatTheyHoldThemselves() {
        Set<BranchPermission> mgr = EnumSet.of(BranchPermission.VIEW_ORDERS, BranchPermission.FULFIL_ORDERS);
        assertTrue(BranchAccessPolicy.mayGrant(Relation.MANAGER, mgr, BranchRole.STAFF, EnumSet.of(BranchPermission.VIEW_ORDERS)));
        assertFalse(BranchAccessPolicy.mayGrant(Relation.MANAGER, mgr, BranchRole.STAFF, EnumSet.of(BranchPermission.MANAGE_STOCK)));
        assertFalse(BranchAccessPolicy.mayGrant(Relation.MANAGER, mgr, BranchRole.MANAGER, EnumSet.of(BranchPermission.VIEW_ORDERS)));
    }

    @Test
    void staffAndOutsidersCanNeverGrant() {
        Set<BranchPermission> all = EnumSet.allOf(BranchPermission.class);
        assertFalse(BranchAccessPolicy.mayGrant(Relation.STAFF, all, BranchRole.STAFF, NONE));
        assertFalse(BranchAccessPolicy.mayGrant(Relation.NONE, all, BranchRole.STAFF, NONE));
        assertFalse(BranchAccessPolicy.mayGrant(Relation.OWNER, all, null, NONE));
    }

    @Test
    void managerDefaultsCoverEveryPermission() {
        assertEquals(EnumSet.allOf(BranchPermission.class), BranchPermission.MANAGER_DEFAULTS);
    }
}
