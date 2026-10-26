package com.momocircle.domain.enums;

/** How many eligible approvers must approve a stokvel request. */
public enum ApprovalRuleType {
    /** One eligible approver (other than the requester) is enough. */
    SINGLE,
    /** A fixed number N of eligible approvers (e.g. 3 of 5). */
    THRESHOLD,
    /** More than half of the eligible approvers. */
    MAJORITY
}
