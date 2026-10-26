package com.momocircle.stokvel;

import com.momocircle.domain.enums.ApprovalRuleType;

/** Pure approval maths. Counts are of ELIGIBLE voters (the requester never counts: no self-approval). */
public final class ApprovalRules {

    private ApprovalRules() {}

    public enum Outcome { PENDING, APPROVED, REJECTED }

    /** Approvals required, or an IllegalArgumentException when the rule cannot be satisfied by the voters. */
    public static int requiredApprovals(ApprovalRuleType type, Integer threshold, int eligibleVoters) {
        if (eligibleVoters < 1) {
            throw new IllegalArgumentException("No eligible approver is available for this request");
        }
        int required = switch (type) {
            case SINGLE -> 1;
            case THRESHOLD -> {
                if (threshold == null || threshold < 1) {
                    throw new IllegalArgumentException("Approval threshold must be at least 1");
                }
                yield threshold;
            }
            case MAJORITY -> eligibleVoters / 2 + 1;
        };
        if (required > eligibleVoters) {
            throw new IllegalArgumentException(
                    "The approval rule needs " + required + " approvers but only " + eligibleVoters + " are eligible");
        }
        return required;
    }

    /** Approved as soon as enough approvals; rejected as soon as approval can no longer be reached. */
    public static Outcome evaluate(int required, int eligibleVoters, int approvals, int rejections) {
        if (approvals >= required) {
            return Outcome.APPROVED;
        }
        if (rejections > eligibleVoters - required) {
            return Outcome.REJECTED;
        }
        return Outcome.PENDING;
    }
}
