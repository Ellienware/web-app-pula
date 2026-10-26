package com.momocircle.branch;

import com.momocircle.branch.BranchAccessPolicy.Action;
import com.momocircle.branch.BranchAccessPolicy.Relation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchAccessPolicyTest {

    @Test
    void ownerMayDoEverythingOnTheirOwnBusinessBranches() {
        for (Action a : Action.values()) assertTrue(BranchAccessPolicy.allows(Relation.OWNER, a), a.name());
    }

    @Test
    void managerCanViewUpdateAndManageStaffButNeverStatusOrManagers() {
        assertTrue(BranchAccessPolicy.allows(Relation.MANAGER, Action.VIEW));
        assertTrue(BranchAccessPolicy.allows(Relation.MANAGER, Action.UPDATE_DETAILS));
        assertTrue(BranchAccessPolicy.allows(Relation.MANAGER, Action.MANAGE_STAFF));
        assertFalse(BranchAccessPolicy.allows(Relation.MANAGER, Action.CHANGE_STATUS));
        assertFalse(BranchAccessPolicy.allows(Relation.MANAGER, Action.MANAGE_MANAGERS));
    }

    @Test
    void staffAreReadOnlyAndOutsidersHaveNothing() {
        assertTrue(BranchAccessPolicy.allows(Relation.STAFF, Action.VIEW));
        for (Action a : Action.values()) {
            if (a != Action.VIEW) assertFalse(BranchAccessPolicy.allows(Relation.STAFF, a), a.name());
            assertFalse(BranchAccessPolicy.allows(Relation.NONE, a), a.name());
        }
        assertFalse(BranchAccessPolicy.allows(null, Action.VIEW));
    }

    @Test
    void nobodyBelowOwnerCanCreateAManagerSoThereIsNoEscalation() {
        assertTrue(BranchAccessPolicy.mayAssign(Relation.OWNER, BranchRole.MANAGER));
        assertTrue(BranchAccessPolicy.mayAssign(Relation.OWNER, BranchRole.STAFF));
        assertFalse(BranchAccessPolicy.mayAssign(Relation.MANAGER, BranchRole.MANAGER));
        assertTrue(BranchAccessPolicy.mayAssign(Relation.MANAGER, BranchRole.STAFF));
        assertFalse(BranchAccessPolicy.mayAssign(Relation.STAFF, BranchRole.STAFF));
        assertFalse(BranchAccessPolicy.mayAssign(Relation.NONE, BranchRole.STAFF));
        assertFalse(BranchAccessPolicy.mayChangeOwnAssignment(Relation.MANAGER));
    }
}
