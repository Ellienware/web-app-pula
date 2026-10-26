package com.momocircle.bulk;

import com.momocircle.domain.enums.BulkGroupStatus;
import com.momocircle.domain.enums.BulkShortfallPolicy;

/** Pure decision for a group whose deadline has passed. The service applies the result; nothing is charged by it. */
public final class BulkDeadlinePolicy {

    public enum Action { NONE, CANCEL, EXTEND, MOVE_TO_CONFIRMATION }

    public static final int MAX_EXTENSIONS = 3;

    private BulkDeadlinePolicy() {}

    public static Action decide(BulkGroupStatus status, BulkShortfallPolicy policy, int activePacks, int targetPacks,
                                int moqPacks, int extensionsUsed, int confirmationExtensionsUsed) {
        boolean preOrder = status == BulkGroupStatus.OPEN || status == BulkGroupStatus.TARGET_REACHED;
        if (preOrder && activePacks >= targetPacks && activePacks >= moqPacks) {
            // Demand is there but the creator has not started confirmation: give a bounded confirmation window.
            return confirmationExtensionsUsed < 1 ? Action.MOVE_TO_CONFIRMATION : Action.CANCEL;
        }
        if (status == BulkGroupStatus.CONFIRMATION) {
            // Past the confirmation window without finalisation: nothing was charged, so cancel rather than guess.
            return Action.CANCEL;
        }
        if (!preOrder) return Action.NONE;
        return switch (policy) {
            case CANCEL -> Action.CANCEL;
            case EXTEND -> extensionsUsed < MAX_EXTENSIONS ? Action.EXTEND : Action.CANCEL;
            case ASK_PARTICIPANTS -> activePacks >= moqPacks ? Action.MOVE_TO_CONFIRMATION : Action.CANCEL;
        };
    }
}
