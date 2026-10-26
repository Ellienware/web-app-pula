package com.momocircle.future;

/** Lifecycle of an allocation PLANNING rule. Deliberately has no INVESTED / TRANSFERRED / SETTLED state. */
public enum PlanStatus { DRAFT, ACTIVE, PAUSED, ARCHIVED }
