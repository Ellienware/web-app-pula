package com.momocircle.bulk;

import com.momocircle.bulk.BulkDeadlinePolicy.Action;
import com.momocircle.domain.enums.BulkGroupStatus;
import com.momocircle.domain.enums.BulkShortfallPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BulkDeadlinePolicyTest {

    @Test
    void cancelPolicyCancelsWhenTargetMissed() {
        assertEquals(Action.CANCEL, BulkDeadlinePolicy.decide(BulkGroupStatus.OPEN, BulkShortfallPolicy.CANCEL, 40, 100, 50, 0, 0));
    }

    @Test
    void extendPolicyExtendsThenCancelsAfterTheLimit() {
        assertEquals(Action.EXTEND, BulkDeadlinePolicy.decide(BulkGroupStatus.OPEN, BulkShortfallPolicy.EXTEND, 40, 100, 50, 0, 0));
        assertEquals(Action.CANCEL, BulkDeadlinePolicy.decide(BulkGroupStatus.OPEN, BulkShortfallPolicy.EXTEND, 40, 100, 50, BulkDeadlinePolicy.MAX_EXTENSIONS, 0));
    }

    @Test
    void askParticipantsMovesToConfirmationOnlyWhenMoqMet() {
        assertEquals(Action.MOVE_TO_CONFIRMATION, BulkDeadlinePolicy.decide(BulkGroupStatus.OPEN, BulkShortfallPolicy.ASK_PARTICIPANTS, 60, 100, 50, 0, 0));
        assertEquals(Action.CANCEL, BulkDeadlinePolicy.decide(BulkGroupStatus.OPEN, BulkShortfallPolicy.ASK_PARTICIPANTS, 40, 100, 50, 0, 0));
    }

    @Test
    void targetReachedButNotFinalisedGetsOneConfirmationWindowThenCancels() {
        assertEquals(Action.MOVE_TO_CONFIRMATION, BulkDeadlinePolicy.decide(BulkGroupStatus.TARGET_REACHED, BulkShortfallPolicy.CANCEL, 120, 100, 50, 0, 0));
        assertEquals(Action.CANCEL, BulkDeadlinePolicy.decide(BulkGroupStatus.TARGET_REACHED, BulkShortfallPolicy.CANCEL, 120, 100, 50, 0, 1));
    }

    @Test
    void confirmationPastDeadlineCancelsAndNothingWasCharged() {
        assertEquals(Action.CANCEL, BulkDeadlinePolicy.decide(BulkGroupStatus.CONFIRMATION, BulkShortfallPolicy.EXTEND, 60, 100, 50, 0, 1));
    }

    @Test
    void groupsWithOrdersAreNeverTouchedByTheDeadline() {
        assertEquals(Action.NONE, BulkDeadlinePolicy.decide(BulkGroupStatus.PAYMENT_PENDING, BulkShortfallPolicy.CANCEL, 60, 100, 50, 0, 0));
        assertEquals(Action.NONE, BulkDeadlinePolicy.decide(BulkGroupStatus.FULFILMENT, BulkShortfallPolicy.CANCEL, 60, 100, 50, 0, 0));
    }

    @Test
    void stateMachineForbidsSkippingAndLeavingTerminalStates() {
        assertEquals(false, BulkGroupStatus.OPEN.canTransitionTo(BulkGroupStatus.PAYMENT_PENDING));
        assertEquals(false, BulkGroupStatus.CANCELLED.canTransitionTo(BulkGroupStatus.OPEN));
        assertEquals(false, BulkGroupStatus.COMPLETED.canTransitionTo(BulkGroupStatus.FULFILMENT));
        assertEquals(true, BulkGroupStatus.CONFIRMATION.canTransitionTo(BulkGroupStatus.PAYMENT_PENDING));
    }
}
