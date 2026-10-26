package com.momocircle.dto;

import com.momocircle.domain.enums.ExpenseStatus;
import com.momocircle.domain.enums.SettlementMethod;
import com.momocircle.domain.enums.ShareStatus;
import com.momocircle.domain.enums.SplitType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Split bills and shared expenses. Everything here is a RECORD of who owes whom plus, where a participant chose to settle
 * through the demo wallet, a link to the simulated shared-ledger transaction. Nothing is real-world settlement.
 */
public final class ExpenseDtos {

    private ExpenseDtos() {}

    /** Shown wherever a split bill can be settled. Mirrors the wallet's notice and adds the expense-specific point. */
    public static final String SIMULATED_SETTLEMENT_NOTICE =
            "Simulated PULA Money demo funds. Paying a share moves demo-wallet value only; it does not pay the real bill, "
                    + "and it is not cash, not held in a bank and not real-world settlement.";

    /**
     * One participant. EQUAL ignores both numbers. CUSTOM needs {@code amount}. PERCENTAGE needs {@code percentage}
     * (at most two decimals, e.g. 33.34). ITEM_BASED ignores both (the items decide).
     */
    public record ShareInput(
            @NotBlank String userId,
            @PositiveOrZero BigDecimal amount,
            @PositiveOrZero BigDecimal percentage
    ) {
        public ShareInput(String userId, BigDecimal amount) { this(userId, amount, null); }
    }

    /** A line item and the participants who share it (item-based splits only). */
    public record ItemInput(
            @NotBlank @Size(max = 120) String name,
            @NotNull @Positive BigDecimal amount,
            @NotEmpty List<String> assigneeUserIds
    ) {}

    public record CreateExpenseRequest(
            @NotBlank @Size(max = 120) String description,
            @Size(max = 1000) String note,
            @NotNull @Positive BigDecimal amount,
            /** Optional; must equal the circle's currency when given. */
            String currency,
            @NotNull SplitType splitType,
            /** Optional payer; defaults to the current user. Anyone other than the caller needs circle OWNER/ADMIN. */
            String paidByUserId,
            LocalDate expenseDate,
            LocalDate dueDate,
            @NotEmpty @Valid List<ShareInput> participants,
            @Valid List<ItemInput> items
    ) {
        /** The original request shape (EQUAL/CUSTOM, no note, dates or items). */
        public CreateExpenseRequest(String description, BigDecimal amount, SplitType splitType, String paidByUserId,
                                    List<ShareInput> participants) {
            this(description, null, amount, null, splitType, paidByUserId, null, null, participants, null);
        }
    }

    /** An edit. {@code expectedRevision} is the revision the editor was looking at; a stale edit is refused. */
    public record UpdateExpenseRequest(
            @NotNull Integer expectedRevision,
            @NotBlank @Size(max = 120) String description,
            @Size(max = 1000) String note,
            @NotNull @Positive BigDecimal amount,
            String currency,
            @NotNull SplitType splitType,
            String paidByUserId,
            LocalDate expenseDate,
            LocalDate dueDate,
            @NotEmpty @Valid List<ShareInput> participants,
            @Valid List<ItemInput> items
    ) {}

    /** An optional reason or note attached to a response, cancellation, re-open or similar action. */
    public record NoteRequest(@Size(max = 300) String note) {}

    /**
     * The participant's explicit confirmation of a simulated transfer. {@code confirm} must be true and the amount and currency
     * must equal what the participant was shown; otherwise nothing is debited.
     */
    public record ConfirmRequest(
            @NotNull Boolean confirm,
            @NotNull BigDecimal expectedAmount,
            String expectedCurrency,
            @Size(max = 300) String note
    ) {}

    // ------------------------------------------------------------------ views

    public record PreviewShare(String userId, String userName, BigDecimal amount, long amountMinor, BigDecimal percentage) {}

    public record PreviewResponse(
            BigDecimal amount,
            long amountMinor,
            String currency,
            SplitType splitType,
            List<PreviewShare> shares,
            /** Plain-language statement of the rounding rule applied, shown to the user. */
            String roundingRule,
            boolean simulated,
            String simulatedNotice
    ) {}

    public record ExpenseItemResponse(String id, String name, BigDecimal amount, long amountMinor, List<String> assigneeUserIds,
                                      List<String> assigneeNames) {}

    public record ExpenseShareResponse(
            String id,
            String debtorId,
            String debtorName,
            BigDecimal amount,
            boolean settled,
            Instant settledAt,
            long amountMinor,
            BigDecimal percentage,
            ShareStatus status,
            SettlementMethod settlementMethod,
            boolean overdue,
            /** True when this row is the viewer's own share. */
            boolean mine,
            /** The participant is no longer a member of the circle (history is kept; they can still respond and settle). */
            boolean debtorFormerMember,
            /** Only shown to the two parties of the share. */
            String ledgerReference,
            String refundReference,
            String responseNote,
            Instant respondedAt,
            boolean canAccept,
            boolean canDispute,
            boolean canDecline,
            boolean canPay,
            /** Why {@code canPay} is false for the debtor (e.g. currency not supported by the demo wallet); else null. */
            String payBlockedReason,
            boolean canReopen,
            boolean canMarkReceived,
            boolean canRetractReceived,
            boolean canRefund,
            /** Always true: every settlement here is simulated demo-wallet value. */
            boolean simulated
    ) {}

    public record ExpenseResponse(
            String id,
            String circleId,
            String paidById,
            String paidByName,
            String description,
            BigDecimal amount,
            String currency,
            SplitType splitType,
            BigDecimal settledAmount,
            BigDecimal outstandingAmount,
            List<ExpenseShareResponse> shares,
            Instant createdAt,
            // ---- Phase 9C additions ----
            String circleName,
            String note,
            long amountMinor,
            String createdById,
            String createdByName,
            LocalDate expenseDate,
            LocalDate dueDate,
            ExpenseStatus status,
            int revision,
            /** What the bill cost, as recorded. Not money held or moved by PULA. */
            BigDecimal recordedAmount,
            /** Simulated demo-wallet funds actually moved through the shared ledger (net of refunds). */
            BigDecimal transferredAmount,
            /** Marked as received outside PULA by the payer. PULA moved and verified nothing. */
            BigDecimal externalSettledAmount,
            /** Simulated funds returned by a compensating transfer or an operator reversal. */
            BigDecimal refundedAmount,
            /** OPEN, PARTIALLY_SETTLED, SETTLED, DISPUTED, OVERDUE or CANCELLED. */
            String settlementState,
            boolean overdue,
            List<ExpenseItemResponse> items,
            boolean canEdit,
            boolean canCancel,
            String cancelBlockedReason,
            Instant cancelledAt,
            String cancelReason,
            boolean simulated,
            String simulatedNotice
    ) {}

    /** One row of "what I owe / what I am owed" across circles. */
    public record ObligationResponse(
            String expenseId,
            String circleId,
            String circleName,
            String title,
            /** OWE: the viewer is the debtor. OWED: the viewer fronted the cost and the other person owes them. */
            String role,
            String counterpartyName,
            ExpenseShareResponse share,
            String currency,
            LocalDate dueDate,
            ExpenseStatus expenseStatus,
            Instant createdAt
    ) {}

    public record EventResponse(
            String id,
            String shareId,
            String actorName,
            String eventType,
            int revision,
            String details,
            String ledgerReference,
            Instant createdAt
    ) {}

    /** Result of a simulated settlement or refund: the updated expense plus the shared-ledger transaction that moved the value. */
    public record SettlementResponse(
            ExpenseResponse expense,
            ExpenseShareResponse share,
            WalletDtos.TransactionView transaction,
            /** True when this call replayed an earlier identical request instead of posting again. */
            boolean replayed,
            boolean simulated,
            String simulatedNotice
    ) {}
}
