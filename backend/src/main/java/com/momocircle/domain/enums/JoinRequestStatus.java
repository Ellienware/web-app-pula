package com.momocircle.domain.enums;

/** Lifecycle of a request to join a stokvel. PENDING is the only open state; the other three are final. */
public enum JoinRequestStatus { PENDING, APPROVED, REJECTED, CANCELLED }
