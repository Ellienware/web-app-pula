package com.momocircle.domain.enums;

public enum StokvelRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
    EXPIRED,
    EXECUTED;

    public boolean isTerminal() {
        return this == REJECTED || this == CANCELLED || this == EXPIRED || this == EXECUTED;
    }
}
