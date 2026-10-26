package com.momocircle.future;

import java.util.ArrayList;
import java.util.List;

/**
 * Income basis from the platform's merchant SETTLEMENT records (the single authoritative accounting record per sale).
 *
 * Only settlements that reached PAID count toward the basis, using each settlement's own net amount, which the existing
 * settlement formula already computes as gross - refunds - commission - fees (+ adjustments). Orders, payments,
 * wallet credits and ledger transactions are never added: they are other representations of the same sale
 * (see FinancialRecordReconciler). Settlements still pending, on hold, failed or cancelled are reported but excluded.
 *
 * PAID here means paid out by the platform's payout workflow, which is SIMULATED in this deployment. It is not bank
 * confirmation, so the result is labelled "settled in PULA (simulated)", never "verified real income".
 */
public final class MerchantIncomeBasis {

    public record Input(long paidNetMinor, long paidGrossMinor, long paidRefundedMinor, long pendingNetMinor, int paidCount, int pendingCount) {}

    public record Result(long basisMinor, long pendingExcludedMinor, long grossMinor, long refundedMinor,
                         long feesAndCommissionMinor, int paidCount, int pendingCount, List<String> notes) {}

    private MerchantIncomeBasis() {}

    public static Result of(Input in) {
        if (in.paidNetMinor() < 0 || in.paidGrossMinor() < 0 || in.paidRefundedMinor() < 0 || in.pendingNetMinor() < 0
                || in.paidCount() < 0 || in.pendingCount() < 0) {
            throw new IllegalArgumentException("Amounts and counts must not be negative");
        }
        // gross - refunded - net = commission, fees and adjustments the settlement formula already took off.
        long deductions = Math.max(0, in.paidGrossMinor() - in.paidRefundedMinor() - in.paidNetMinor());
        List<String> notes = new ArrayList<>();
        notes.add("Counts only merchant settlements marked paid in PULA. This payout is simulated, so it is not bank confirmation.");
        notes.add("Each settlement's net already reflects refunds, commission and fees. Orders, payments and wallet credits are not added again.");
        if (in.pendingCount() > 0) {
            notes.add(in.pendingCount() + " settlement(s) are still pending, on hold or failed and are not included.");
        }
        if (in.paidCount() == 0) {
            notes.add("No paid settlements were found for this period.");
        }
        return new Result(in.paidNetMinor(), in.pendingNetMinor(), in.paidGrossMinor(), in.paidRefundedMinor(), deductions,
                in.paidCount(), in.pendingCount(), List.copyOf(notes));
    }
}
