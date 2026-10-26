package com.momocircle.future;

/** Business planning line types. EMERGENCY_RESERVE counts as a reserve; every other kind counts as a declared commitment. */
public enum PlanItemKind {
    OPERATING_EXPENSES(false), SUPPLIER_COMMITMENTS(false), RENT_UTILITIES(false), PAYROLL(false), TAXES(false),
    EMERGENCY_RESERVE(true), PLANNED_INVENTORY(false);

    private final boolean reserve;
    PlanItemKind(boolean reserve) { this.reserve = reserve; }
    public boolean isReserve() { return reserve; }
}
