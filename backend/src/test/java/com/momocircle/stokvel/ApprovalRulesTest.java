package com.momocircle.stokvel;

import static com.momocircle.stokvel.ApprovalRules.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.enums.ApprovalRuleType;
import org.junit.jupiter.api.Test;

class ApprovalRulesTest {

    @Test
    void singleNeedsOneApproverButNeedsAnApproverToExist() {
        assertEquals(1, ApprovalRules.requiredApprovals(ApprovalRuleType.SINGLE, null, 4));
        assertThrows(IllegalArgumentException.class,
                () -> ApprovalRules.requiredApprovals(ApprovalRuleType.SINGLE, null, 0));
    }

    @Test
    void threeOfFive() {
        assertEquals(3, ApprovalRules.requiredApprovals(ApprovalRuleType.THRESHOLD, 3, 5));
        assertEquals(PENDING, ApprovalRules.evaluate(3, 5, 2, 0));
        assertEquals(APPROVED, ApprovalRules.evaluate(3, 5, 3, 0));
    }

    @Test
    void thresholdCannotExceedEligibleVoters() {
        assertThrows(IllegalArgumentException.class,
                () -> ApprovalRules.requiredApprovals(ApprovalRuleType.THRESHOLD, 4, 3));
        assertThrows(IllegalArgumentException.class,
                () -> ApprovalRules.requiredApprovals(ApprovalRuleType.THRESHOLD, null, 3));
        assertThrows(IllegalArgumentException.class,
                () -> ApprovalRules.requiredApprovals(ApprovalRuleType.THRESHOLD, 0, 3));
    }

    @Test
    void majorityIsMoreThanHalf() {
        assertEquals(1, ApprovalRules.requiredApprovals(ApprovalRuleType.MAJORITY, null, 1));
        assertEquals(2, ApprovalRules.requiredApprovals(ApprovalRuleType.MAJORITY, null, 2));
        assertEquals(2, ApprovalRules.requiredApprovals(ApprovalRuleType.MAJORITY, null, 3));
        assertEquals(3, ApprovalRules.requiredApprovals(ApprovalRuleType.MAJORITY, null, 4));
        assertEquals(3, ApprovalRules.requiredApprovals(ApprovalRuleType.MAJORITY, null, 5));
    }

    @Test
    void rejectedOnceApprovalIsMathematicallyImpossible() {
        // 3 of 5: three rejections leave only two possible approvals.
        assertEquals(PENDING, ApprovalRules.evaluate(3, 5, 0, 2));
        assertEquals(REJECTED, ApprovalRules.evaluate(3, 5, 0, 3));
        // single approver, one voter: a single rejection ends it.
        assertEquals(REJECTED, ApprovalRules.evaluate(1, 1, 0, 1));
        // with 2 voters and single rule, one rejection is not final.
        assertEquals(PENDING, ApprovalRules.evaluate(1, 2, 0, 1));
    }

    @Test
    void enoughApprovalsWinEvenIfSomeRejected() {
        assertEquals(APPROVED, ApprovalRules.evaluate(2, 3, 2, 1));
    }
}
