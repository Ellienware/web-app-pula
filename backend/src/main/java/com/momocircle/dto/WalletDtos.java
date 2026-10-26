package com.momocircle.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Request/response contracts for the PULA Money DEMO wallet. Every response that carries value also carries
 * {@code simulated = true} and a notice; none of these types maps to a JPA entity and none exposes an internal id.
 * Amounts are decimal strings in the response (exact, never floating point) and decimal numbers in requests.
 */
public final class WalletDtos {

    private WalletDtos() { }

    public static final String SIMULATED_NOTICE = "Simulated PULA Money demo funds. Not cash, not held in a bank, and not real-world settlement.";

    // ---------------------------------------------------------------- requests

    public record AmountRequest(
            @NotNull @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
            @Digits(integer = 12, fraction = 2, message = "Amount has too many digits or decimals") BigDecimal amount,
            @Size(min = 3, max = 3) String currency) { }

    public record RecipientLookupRequest(@NotBlank @Size(max = 120) String identifier) { }

    public record TransferRequest(
            @NotBlank @Size(max = 32) String recipientId,
            @NotNull @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
            @Digits(integer = 12, fraction = 2, message = "Amount has too many digits or decimals") BigDecimal amount,
            @Size(min = 3, max = 3) String currency) { }

    public record CreatePaymentRequest(
            @NotNull @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
            @Digits(integer = 12, fraction = 2, message = "Amount has too many digits or decimals") BigDecimal amount,
            @Size(min = 3, max = 3) String currency,
            @NotBlank @Size(max = 140) String purpose,
            /** Optional wallet id of the only user allowed to pay. Omit for a request anyone with the code may pay. */
            @Size(max = 32) String payerRecipientId,
            /** Optional lifetime; the server clamps it to its configured bounds. */
            Integer expiresInMinutes) { }

    public record CodeRequest(@NotBlank @Size(max = 80) String code) { }

    public record ConfirmPaymentRequest(
            @NotBlank @Size(max = 80) String code,
            /** What the payer saw on the confirmation screen; the server re-checks it against the stored request. */
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal expectedAmount,
            @NotBlank @Size(min = 3, max = 3) String expectedCurrency) { }

    public record FailWithdrawalRequest(@Size(max = 300) String reason) { }

    // ---------------------------------------------------------------- responses

    public record LimitsView(String transferMax, String demoFundingPerRequestMax, String demoFundingPerPeriodMax,
                             int demoFundingPeriodDays, String withdrawalMax) { }

    public record WalletOverview(boolean simulated, String notice, String walletId, String currency, String balance,
                                 long balanceMinor, LimitsView limits) { }

    public record RecipientView(String recipientId, String displayName, boolean simulated) { }

    public record TransactionView(String reference, String type, String status, String direction, String amount,
                                  String currency, String counterparty, String reversesReference, String failureReason,
                                  Instant createdAt, boolean simulated, String notice) { }

    public record PageView<T>(List<T> items, int page, int size, long totalItems, int totalPages) { }

    public record PaymentRequestView(String requestId, String code, String direction, String status, String amount,
                                     String currency, String purpose, String requesterName, String targetPayerName,
                                     Instant expiresAt, Instant createdAt, Instant resolvedAt, String transactionReference,
                                     boolean canPay, boolean canReject, boolean canCancel, boolean simulated, String notice) { }

    public record PaymentResult(PaymentRequestView request, TransactionView transaction, boolean replayed, boolean simulated,
                                String notice) { }

    public record ReconciliationView(boolean consistent, long balanceMinor, long postingsMinor) { }

    // ---------------------------------------------------------------- business wallets

    /** One business wallet the user can act on. {@code walletId} (BW-...) is the only handle; no internal ids are exposed. */
    public record BusinessAccessView(String walletId, String businessName, String role, List<String> permissions) { }

    public record BusinessOverview(WalletOverview wallet, String businessName, String role, List<String> permissions) { }

    public record MemberView(String memberId, String displayName, String role, boolean owner) { }

    public record AddMemberRequest(@NotBlank @Size(max = 120) String identifier, @NotBlank @Size(max = 16) String role) { }

    public record ChangeRoleRequest(@NotBlank @Size(max = 16) String role) { }
}
