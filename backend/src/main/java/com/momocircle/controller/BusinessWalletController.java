package com.momocircle.controller;

import com.momocircle.domain.User;
import com.momocircle.domain.wallet.BusinessWalletRole.Permission;
import com.momocircle.dto.WalletDtos.AddMemberRequest;
import com.momocircle.dto.WalletDtos.AmountRequest;
import com.momocircle.dto.WalletDtos.BusinessAccessView;
import com.momocircle.dto.WalletDtos.BusinessOverview;
import com.momocircle.dto.WalletDtos.ChangeRoleRequest;
import com.momocircle.dto.WalletDtos.CreatePaymentRequest;
import com.momocircle.dto.WalletDtos.MemberView;
import com.momocircle.dto.WalletDtos.PageView;
import com.momocircle.dto.WalletDtos.PaymentRequestView;
import com.momocircle.dto.WalletDtos.RecipientLookupRequest;
import com.momocircle.dto.WalletDtos.RecipientView;
import com.momocircle.dto.WalletDtos.TransactionView;
import com.momocircle.dto.WalletDtos.TransferRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.ledger.Posting;
import com.momocircle.ledger.TxStatus;
import com.momocircle.ledger.TxType;
import com.momocircle.security.CurrentUserService;
import com.momocircle.security.ServiceRateLimiter;
import com.momocircle.service.wallet.BusinessWalletAccessService;
import com.momocircle.service.wallet.BusinessWalletAccessService.Access;
import com.momocircle.service.wallet.BusinessWalletMemberService;
import com.momocircle.service.wallet.PaymentRequestService;
import com.momocircle.service.wallet.WalletHistoryQuery;
import com.momocircle.service.wallet.WalletService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PULA Money BUSINESS wallets (simulated demo funds). A business wallet is addressed by its opaque public id
 * ({@code BW-...}); the acting user always comes from the authenticated principal and every call first passes
 * {@link BusinessWalletAccessService#require}, which checks business ownership/membership and the role's permission. A user
 * who is not an owner/member gets 404 (as if the wallet did not exist). Paying a business request is done by customers through
 * the normal {@code /api/money/wallet/payment-requests/pay} endpoint.
 */
@RestController
@RequestMapping("/api/money/business-wallets")
public class BusinessWalletController {

    private final CurrentUserService currentUser;
    private final BusinessWalletAccessService access;
    private final BusinessWalletMemberService memberService;
    private final WalletService wallet;
    private final PaymentRequestService paymentRequests;
    private final ServiceRateLimiter rateLimiter;

    public BusinessWalletController(CurrentUserService currentUser, BusinessWalletAccessService access, BusinessWalletMemberService memberService,
                                    WalletService wallet, PaymentRequestService paymentRequests, ServiceRateLimiter rateLimiter) {
        this.currentUser = currentUser;
        this.access = access;
        this.memberService = memberService;
        this.wallet = wallet;
        this.paymentRequests = paymentRequests;
        this.rateLimiter = rateLimiter;
    }

    // ---------------------------------------------------------------- which business wallets can I act on?

    @GetMapping
    public List<BusinessAccessView> mine() {
        User user = currentUser.require();
        return access.accessibleTo(user).stream()
                .map(a -> new BusinessAccessView(a.walletPublicId(), a.businessName(), a.role().name(), permissionNames(a)))
                .toList();
    }

    // ---------------------------------------------------------------- overview & history (VIEW)

    @GetMapping("/{walletId}")
    public BusinessOverview overview(@PathVariable String walletId) {
        User user = currentUser.require();
        Access a = access.require(user, walletId, Permission.VIEW);
        return new BusinessOverview(wallet.overview(wallet.business(user, a)), a.businessName(), a.role().name(), permissionNames(a));
    }

    @GetMapping("/{walletId}/transactions")
    public PageView<TransactionView> transactions(@PathVariable String walletId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type, @RequestParam(required = false) String status,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        User user = currentUser.require();
        Access a = access.require(user, walletId, Permission.VIEW);
        Posting.Direction dir = null;
        if (direction != null && !direction.isBlank()) {
            dir = switch (direction.toUpperCase()) {
                case "IN" -> Posting.Direction.CREDIT;
                case "OUT" -> Posting.Direction.DEBIT;
                default -> throw ApiException.badRequest("direction must be IN or OUT");
            };
        }
        return wallet.history(wallet.business(user, a),
                new WalletHistoryQuery.Filter(parse(TxType.class, type), parse(TxStatus.class, status), dir, from, to), page, size);
    }

    @GetMapping("/{walletId}/transactions/{reference}")
    public TransactionView transaction(@PathVariable String walletId, @PathVariable String reference) {
        User user = currentUser.require();
        return wallet.detail(wallet.business(user, access.require(user, walletId, Permission.VIEW)), reference);
    }

    // ---------------------------------------------------------------- money-changing (idempotent, rate limited, permission-gated)

    @PostMapping("/{walletId}/demo-funding")
    public ResponseEntity<TransactionView> fund(@PathVariable String walletId, @Valid @RequestBody AmountRequest req,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        User user = currentUser.require();
        rateLimiter.check("wallet-fund", user.getId(), 10, Duration.ofMinutes(1));
        Access a = access.require(user, walletId, Permission.FUND);
        return posted(wallet.fund(wallet.business(user, a), req.amount(), req.currency(), key));
    }

    @PostMapping("/{walletId}/recipients/lookup")
    public RecipientView lookup(@PathVariable String walletId, @Valid @RequestBody RecipientLookupRequest req) {
        User user = currentUser.require();
        rateLimiter.check("wallet-lookup", user.getId(), 15, Duration.ofMinutes(1));
        Access a = access.require(user, walletId, Permission.SEND);
        return wallet.lookupRecipient(wallet.business(user, a), req.identifier());
    }

    @PostMapping("/{walletId}/transfers")
    public ResponseEntity<TransactionView> transfer(@PathVariable String walletId, @Valid @RequestBody TransferRequest req,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        User user = currentUser.require();
        rateLimiter.check("wallet-transfer", user.getId(), 30, Duration.ofMinutes(1));
        Access a = access.require(user, walletId, Permission.SEND);
        return posted(wallet.transfer(wallet.business(user, a), req.recipientId(), req.amount(), req.currency(), key));
    }

    @PostMapping("/{walletId}/withdrawals")
    public ResponseEntity<TransactionView> withdraw(@PathVariable String walletId, @Valid @RequestBody AmountRequest req,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        User user = currentUser.require();
        rateLimiter.check("wallet-withdraw", user.getId(), 10, Duration.ofMinutes(1));
        Access a = access.require(user, walletId, Permission.WITHDRAW);
        return posted(wallet.requestWithdrawal(wallet.business(user, a), req.amount(), req.currency(), key));
    }

    @PostMapping("/{walletId}/withdrawals/{reference}/cancel")
    public TransactionView cancelWithdrawal(@PathVariable String walletId, @PathVariable String reference) {
        User user = currentUser.require();
        rateLimiter.check("wallet-withdraw", user.getId(), 10, Duration.ofMinutes(1));
        Access a = access.require(user, walletId, Permission.WITHDRAW);
        return wallet.cancelWithdrawal(wallet.business(user, a), reference);
    }

    // ---------------------------------------------------------------- payment requests on behalf of the business (REQUEST)

    @PostMapping("/{walletId}/payment-requests")
    public ResponseEntity<PaymentRequestView> createRequest(@PathVariable String walletId, @Valid @RequestBody CreatePaymentRequest req) {
        User user = currentUser.require();
        rateLimiter.check("wallet-request-create", user.getId(), 20, Duration.ofMinutes(1));
        Access a = access.require(user, walletId, Permission.REQUEST);
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentRequests.createForBusiness(user, a, req.amount(), req.currency(),
                req.purpose(), req.payerRecipientId(), req.expiresInMinutes()));
    }

    @GetMapping("/{walletId}/payment-requests")
    public PageView<PaymentRequestView> listRequests(@PathVariable String walletId, @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        User user = currentUser.require();
        return paymentRequests.listForBusiness(user, access.require(user, walletId, Permission.VIEW), page, size);
    }

    @GetMapping("/{walletId}/payment-requests/{requestId}")
    public PaymentRequestView getRequest(@PathVariable String walletId, @PathVariable String requestId) {
        User user = currentUser.require();
        return paymentRequests.getForBusiness(user, access.require(user, walletId, Permission.VIEW), requestId);
    }

    @PostMapping("/{walletId}/payment-requests/{requestId}/cancel")
    public PaymentRequestView cancelRequest(@PathVariable String walletId, @PathVariable String requestId) {
        User user = currentUser.require();
        return paymentRequests.cancelForBusiness(user, access.require(user, walletId, Permission.REQUEST), requestId);
    }

    // ---------------------------------------------------------------- members (owner only: MANAGE_MEMBERS)

    @GetMapping("/{walletId}/members")
    public List<MemberView> members(@PathVariable String walletId) {
        User user = currentUser.require();
        return memberService.list(access.require(user, walletId, Permission.MANAGE_MEMBERS));
    }

    @PostMapping("/{walletId}/members")
    public ResponseEntity<MemberView> addMember(@PathVariable String walletId, @Valid @RequestBody AddMemberRequest req) {
        User user = currentUser.require();
        rateLimiter.check("wallet-lookup", user.getId(), 15, Duration.ofMinutes(1));
        Access a = access.require(user, walletId, Permission.MANAGE_MEMBERS);
        return ResponseEntity.status(HttpStatus.CREATED).body(memberService.add(user, a, req.identifier(), req.role()));
    }

    @PutMapping("/{walletId}/members/{memberId}")
    public MemberView changeRole(@PathVariable String walletId, @PathVariable String memberId, @Valid @RequestBody ChangeRoleRequest req) {
        User user = currentUser.require();
        return memberService.changeRole(user, access.require(user, walletId, Permission.MANAGE_MEMBERS), memberId, req.role());
    }

    @DeleteMapping("/{walletId}/members/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable String walletId, @PathVariable String memberId) {
        User user = currentUser.require();
        memberService.remove(user, access.require(user, walletId, Permission.MANAGE_MEMBERS), memberId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> permissionNames(Access a) {
        return a.role().permissions().stream().map(Enum::name).sorted().toList();
    }

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
