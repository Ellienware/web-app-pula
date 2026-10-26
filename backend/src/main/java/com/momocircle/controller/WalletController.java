package com.momocircle.controller;

import com.momocircle.domain.User;
import com.momocircle.dto.WalletDtos.AmountRequest;
import com.momocircle.dto.WalletDtos.CodeRequest;
import com.momocircle.dto.WalletDtos.ConfirmPaymentRequest;
import com.momocircle.dto.WalletDtos.CreatePaymentRequest;
import com.momocircle.dto.WalletDtos.PageView;
import com.momocircle.dto.WalletDtos.PaymentRequestView;
import com.momocircle.dto.WalletDtos.PaymentResult;
import com.momocircle.dto.WalletDtos.RecipientLookupRequest;
import com.momocircle.dto.WalletDtos.RecipientView;
import com.momocircle.dto.WalletDtos.TransactionView;
import com.momocircle.dto.WalletDtos.TransferRequest;
import com.momocircle.dto.WalletDtos.WalletOverview;
import com.momocircle.exception.ApiException;
import com.momocircle.ledger.Posting;
import com.momocircle.ledger.TxStatus;
import com.momocircle.ledger.TxType;
import com.momocircle.security.CurrentUserService;
import com.momocircle.security.ServiceRateLimiter;
import com.momocircle.service.wallet.PaymentRequestService;
import com.momocircle.service.wallet.WalletHistoryQuery;
import com.momocircle.service.wallet.WalletService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * PULA Money DEMO wallet. Every endpoint acts as {@code currentUser.require()}: there is no owner id, user id or wallet id in
 * any request that grants access (a wallet id in a body only NAMES a counterparty, and is resolved server-side to an active
 * user). All value here is simulated and every response says so. Wallet balances come only from the shared ledger.
 */
@RestController
@RequestMapping("/api/money/wallet")
public class WalletController {

    private final CurrentUserService currentUser;
    private final WalletService wallet;
    private final PaymentRequestService paymentRequests;
    private final ServiceRateLimiter rateLimiter;

    public WalletController(CurrentUserService currentUser, WalletService wallet, PaymentRequestService paymentRequests,
                            ServiceRateLimiter rateLimiter) {
        this.currentUser = currentUser;
        this.wallet = wallet;
        this.paymentRequests = paymentRequests;
        this.rateLimiter = rateLimiter;
    }

    // ---------------------------------------------------------------- overview & history

    @GetMapping
    public WalletOverview overview() {
        return wallet.overview(currentUser.require());
    }

    @GetMapping("/transactions")
    public PageView<TransactionView> transactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        User user = currentUser.require();
        Posting.Direction dir = null;
        if (direction != null && !direction.isBlank()) {
            dir = switch (direction.toUpperCase()) {
                case "IN" -> Posting.Direction.CREDIT;
                case "OUT" -> Posting.Direction.DEBIT;
                default -> throw ApiException.badRequest("direction must be IN or OUT");
            };
        }
        return wallet.history(user, new WalletHistoryQuery.Filter(parse(TxType.class, type), parse(TxStatus.class, status), dir, from, to), page, size);
    }

    @GetMapping("/transactions/{reference}")
    public TransactionView transaction(@PathVariable String reference) {
        return wallet.detail(currentUser.require(), reference);
    }

    // ---------------------------------------------------------------- money-changing (all idempotent, all rate limited)

    @PostMapping("/demo-funding")
    public ResponseEntity<TransactionView> demoFunding(@Valid @RequestBody AmountRequest req,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        User user = currentUser.require();
        rateLimiter.check("wallet-fund", user.getId(), 10, Duration.ofMinutes(1));
        return posted(wallet.fund(user, req.amount(), req.currency(), key));
    }

    @PostMapping("/recipients/lookup")
    public RecipientView lookup(@Valid @RequestBody RecipientLookupRequest req) {
        User user = currentUser.require();
        rateLimiter.check("wallet-lookup", user.getId(), 15, Duration.ofMinutes(1)); // blunts user enumeration
        return wallet.lookupRecipient(user, req.identifier());
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransactionView> transfer(@Valid @RequestBody TransferRequest req,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        User user = currentUser.require();
        rateLimiter.check("wallet-transfer", user.getId(), 30, Duration.ofMinutes(1));
        return posted(wallet.transfer(user, req.recipientId(), req.amount(), req.currency(), key));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<TransactionView> requestWithdrawal(@Valid @RequestBody AmountRequest req,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        User user = currentUser.require();
        rateLimiter.check("wallet-withdraw", user.getId(), 10, Duration.ofMinutes(1));
        return posted(wallet.requestWithdrawal(user, req.amount(), req.currency(), key));
    }

    @PostMapping("/withdrawals/{reference}/cancel")
    public TransactionView cancelWithdrawal(@PathVariable String reference) {
        User user = currentUser.require();
        rateLimiter.check("wallet-withdraw", user.getId(), 10, Duration.ofMinutes(1));
        return wallet.cancelWithdrawal(user, reference);
    }

    // ---------------------------------------------------------------- payment requests

    @PostMapping("/payment-requests")
    public ResponseEntity<PaymentRequestView> createRequest(@Valid @RequestBody CreatePaymentRequest req) {
        User user = currentUser.require();
        rateLimiter.check("wallet-request-create", user.getId(), 20, Duration.ofMinutes(1));
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentRequests.create(user, req.amount(), req.currency(),
                req.purpose(), req.payerRecipientId(), req.expiresInMinutes()));
    }

    /** {@code box} is "outgoing" (requests I made, default) or "incoming" (requests addressed to me). */
    @GetMapping("/payment-requests")
    public PageView<PaymentRequestView> listRequests(@RequestParam(defaultValue = "outgoing") String box,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return paymentRequests.list(currentUser.require(), "incoming".equalsIgnoreCase(box), page, size);
    }

    @GetMapping("/payment-requests/{requestId}")
    public PaymentRequestView getRequest(@PathVariable String requestId) {
        return paymentRequests.get(currentUser.require(), requestId);
    }

    /** Review what a scanned or pasted code refers to. Read-only for funds: a scan alone never pays. POST keeps the code out of URLs and logs. */
    @PostMapping("/payment-requests/resolve")
    public PaymentRequestView resolve(@Valid @RequestBody CodeRequest req) {
        User user = currentUser.require();
        rateLimiter.check("wallet-code-resolve", user.getId(), 60, Duration.ofMinutes(1));
        return paymentRequests.resolve(user, req.code());
    }

    /** The explicit confirmation step: the only endpoint that can move value for a request. */
    @PostMapping("/payment-requests/pay")
    public PaymentResult pay(@Valid @RequestBody ConfirmPaymentRequest req) {
        User user = currentUser.require();
        rateLimiter.check("wallet-request-pay", user.getId(), 20, Duration.ofMinutes(1));
        return paymentRequests.pay(user, req.code(), req.expectedAmount(), req.expectedCurrency());
    }

    @PostMapping("/payment-requests/reject")
    public PaymentRequestView reject(@Valid @RequestBody CodeRequest req) {
        User user = currentUser.require();
        rateLimiter.check("wallet-code-resolve", user.getId(), 60, Duration.ofMinutes(1));
        return paymentRequests.reject(user, req.code());
    }

    @PostMapping("/payment-requests/{requestId}/cancel")
    public PaymentRequestView cancelRequest(@PathVariable String requestId) {
        return paymentRequests.cancel(currentUser.require(), requestId);
    }

    // ---------------------------------------------------------------- helpers

    private static ResponseEntity<TransactionView> posted(WalletService.Posted p) {
        return ResponseEntity.status(p.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", String.valueOf(p.replayed())).body(p.view());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown " + type.getSimpleName() + " filter");
        }
    }
}
