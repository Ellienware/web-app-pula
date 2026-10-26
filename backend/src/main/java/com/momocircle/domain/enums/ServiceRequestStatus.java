package com.momocircle.domain.enums;

public enum ServiceRequestStatus {
    DRAFT,
    SUBMITTED,
    QUOTED,
    BOOKED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    DECLINED,
    EXPIRED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == DECLINED || this == EXPIRED;
    }
}
