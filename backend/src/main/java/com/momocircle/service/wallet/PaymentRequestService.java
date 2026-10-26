package com.momocircle.service.wallet;

import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.wallet.BusinessWalletRole.Permission;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.domain.wallet.PaymentRequestStatus;
import com.momocircle.domain.wallet.WalletPaymentRequestEntity;
import com.momocircle.dto.WalletDtos.PageView;
import com.momocircle.dto.WalletDtos.PaymentRequestView;
import com.momocircle.dto.WalletDtos.PaymentResult;
import com.momocircle.ledger.Account;
import com.momocircle.ledger.Actor;
import com.momocircle.ledger.LedgerEngine;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.LedgerLimits;
import com.momocircle.ledger.Money;
import com.momocircle.ledger.TxStatus;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.ledger.LedgerTransactionRepository;
import com.momocircle.repository.wallet.WalletPaymentRequestRepository;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.ledger.LedgerAccountService;
import com.momocircle.service.ledger.LedgerOperationsService;
import com.momocircle.wallet.QrTokenService;
import com.momocircle.wallet.WalletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.momocircle.dto.WalletDtos.SIMULATED_NOTICE;
import static com.momocircle.domain.wallet.PaymentRequestStatus.CANCELLED;
import static com.momocircle.domain.wallet.PaymentRequestStatus.EXPIRED;
import static com.momocircle.domain.wallet.PaymentRequestStatus.OPEN;
import static com.momocircle.domain.wallet.PaymentRequestStatus.PAID;
import static com.momocircle.domain.wallet.PaymentRequestStatus.PROCESSING;
import static com.momocircle.domain.wallet.PaymentRequestStatus.REJECTED;

/**
 * Payment REQUESTS. Three things are kept deliberately separate:
 * <ol>
 *   <li>the request (this class, table {@code wallet_payment_requests}): who asks for how much, and its lifecycle;</li>
 *   <li>the ledger transfer (posted only by {@link LedgerOperationsService}, only after the payer's explicit confirmation);</li>
 *   <li>any external business obligation (none exists here: demo value never settles real orders, payouts or fares).</li>
 * </ol>
 * Scanning or resolving a code is read-only for funds. The only path that moves value is {@link #pay}.
 *
 * <p>Double-payment safety: the payer first CLAIMS the request with a single guarded UPDATE (OPEN to PROCESSING), so at most
 * one payer can ever reach the ledger. The ledger key is {@code payreq:<request id>}, so a retry by that payer replays the
 * same ledger transaction instead of posting twice. A claim is released only when the ledger definitively refused the
 * payment; if the outcome is unknown the claim stays and the same payer's retry recovers it. Not {@code @Transactional}
 * (the ledger owns its transactions and retries).
 */
@Service
public class PaymentRequestService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestService.class);
    private static final Set<LedgerException.Code> DEFINITIVE_REFUSALS = EnumSet.of(
            LedgerException.Code.INSUFFICIENT_FUNDS, LedgerException.Code.LIMIT_EXCEEDED, LedgerException.Code.INVALID_AMOUNT,
            LedgerException.Code.INVALID_CURRENCY, LedgerException.Code.CURRENCY_MISMATCH, LedgerException.Code.SELF_TRANSFER,
            LedgerException.Code.NOT_OWNER, LedgerException.Code.ACCOUNT_NOT_FOUND);

    private final WalletPaymentRequestRepository requests;
    private final QrTokenService qr;
    private final WalletIdentityService identity;
    private final WalletService wallet;
    private final LedgerAccountService accountService;
    private final LedgerOperationsService ledger;
    private final LedgerTransactionRepository txRepo;
    private final UserRepository users;
    private final BusinessRepository businesses;
    private final BusinessWalletAccessService businessAccess;
    private final AuditLogService audit;
    private final LedgerLimits limits;
    private final int defaultExpiryMinutes;
    private final int minExpiryMinutes;
    private final int maxExpiryMinutes;
    private final Duration staleProcessing;

    public PaymentRequestService(WalletPaymentRequestRepository requests, QrTokenService qr, WalletIdentityService identity,
                                 WalletService wallet, LedgerAccountService accountService, LedgerOperationsService ledger,
                                 LedgerTransactionRepository txRepo, UserRepository users, BusinessRepository businesses,
                                 BusinessWalletAccessService businessAccess, AuditLogService audit, LedgerLimits limits,
                                 @Value("${app.wallet.request-default-expiry-minutes:60}") int defaultExpiryMinutes,
                                 @Value("${app.wallet.request-min-expiry-minutes:5}") int minExpiryMinutes,
                                 @Value("${app.wallet.request-max-expiry-minutes:10080}") int maxExpiryMinutes,
                                 @Value("${app.wallet.stale-processing-minutes:60}") int staleProcessingMinutes) {
        this.requests = requests;
        this.qr = qr;
        this.identity = identity;
        this.wallet = wallet;
        this.accountService = accountService;
        this.ledger = ledger;
        this.txRepo = txRepo;
        this.users = users;
        this.businesses = businesses;
        this.businessAccess = businessAccess;
        this.audit = audit;
        this.limits = limits;
        this.defaultExpiryMinutes = defaultExpiryMinutes;
        this.minExpiryMinutes = minExpiryMinutes;
        this.maxExpiryMinutes = maxExpiryMinutes;
        this.staleProcessing = Duration.ofMinutes(staleProcessingMinutes);
    }

    // ---------------------------------------------------------------- create / read / cancel

    public PaymentRequestView create(User requester, BigDecimal amount, String requestedCurrency, String purposeRaw,
                                     String payerWalletId, Integer expiresInMinutes) {
        return createInternal(requester, null, amount, requestedCurrency, purposeRaw, payerWalletId, expiresInMinutes);
    }

    /** A request that pays into the business wallet. The caller has verified the member's REQUEST permission. */
    public PaymentRequestView createForBusiness(User creator, BusinessWalletAccessService.Access access, BigDecimal amount,
                                                String requestedCurrency, String purposeRaw, String payerWalletId, Integer expiresInMinutes) {
        return createInternal(creator, access, amount, requestedCurrency, purposeRaw, payerWalletId, expiresInMinutes);
    }

    private PaymentRequestView createInternal(User requester, BusinessWalletAccessService.Access business, BigDecimal amount,
                                              String requestedCurrency, String purposeRaw, String payerWalletId, Integer expiresInMinutes) {
        String currency = wallet.currency(requestedCurrency);
        Money money = Money.ofDecimal(amount, currency);
        if (!money.isPositive()) throw WalletException.badRequest("INVALID_AMOUNT", "Amount must be greater than zero");
        if (money.minor() > limits.maxTransferMinor()) {
            throw new WalletException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_EXCEEDED",
                    "Amount exceeds the per-payment demo limit");
        }
        String purpose = cleanPurpose(purposeRaw);
        String targetId = null;
        if (payerWalletId != null && !payerWalletId.isBlank()) {
            User target = identity.userForPublicId(payerWalletId).orElseThrow(() ->
                    WalletException.notFound("RECIPIENT_NOT_FOUND", "No eligible PULA user matches that wallet id"));
            if (target.getId().equals(requester.getId())
                    || (business != null && businessAccess.findByBusinessId(target, business.businessId()).isPresent())) {
                throw WalletException.badRequest("SELF_PAYMENT", "You cannot request a payment from yourself");
            }
            targetId = target.getId();
        }
        int minutes = expiresInMinutes == null ? defaultExpiryMinutes : Math.max(minExpiryMinutes, Math.min(expiresInMinutes, maxExpiryMinutes));
        if (business == null) {
            accountService.getOrCreateWallet(requester.getId(), currency); // the requester must be able to receive
        } else {
            accountService.getOrCreateBusinessWallet(business.businessId(), currency);
        }
        Instant now = Instant.now();
        WalletPaymentRequestEntity e = requests.save(WalletPaymentRequestEntity.open(UUID.randomUUID().toString(), qr.newReference(),
                requester.getId(), business == null ? null : business.businessId(), targetId, money.minor(), currency, purpose,
                now.plus(Duration.ofMinutes(minutes)), now));
        audit(requester, "WALLET_PAYMENT_REQUEST_CREATED", e,
                (business == null ? "SIMULATED " : "SIMULATED business=" + business.walletPublicId() + " ") + money.toDecimal() + " " + currency);
        return view(e, requester, Optional.ofNullable(business));
    }

    public PageView<PaymentRequestView> list(User user, boolean incoming, int page, int size) {
        int safe = Math.max(1, Math.min(size, 50));
        PageRequest pr = PageRequest.of(Math.max(page, 0), safe);
        Page<WalletPaymentRequestEntity> p = incoming ? requests.findIncoming(user.getId(), pr) : requests.findOutgoing(user.getId(), pr);
        return new PageView<>(p.getContent().stream().map(e -> view(e, user)).toList(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    /** A request the caller created or that is addressed to them. Anyone else gets "not found". */
    public PaymentRequestView get(User user, String requestId) {
        WalletPaymentRequestEntity e = requests.findByPublicRef(requestId == null ? "" : requestId)
                .filter(r -> (r.getRequesterUserId().equals(user.getId()) && r.getRequesterBusinessId() == null)
                        || user.getId().equals(r.getTargetPayerUserId()))
                .orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Payment request not found"));
        return view(refresh(e), user);
    }

    // ---- business side (callers have verified the member's permission via BusinessWalletAccessService) ----

    public PageView<PaymentRequestView> listForBusiness(User user, BusinessWalletAccessService.Access access, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)));
        Page<WalletPaymentRequestEntity> p = requests.findForBusiness(access.businessId(), pr);
        return new PageView<>(p.getContent().stream().map(e -> view(e, user, Optional.of(access))).toList(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    public PaymentRequestView getForBusiness(User user, BusinessWalletAccessService.Access access, String requestId) {
        WalletPaymentRequestEntity e = requests.findByPublicRef(requestId == null ? "" : requestId)
                .filter(r -> access.businessId().equals(r.getRequesterBusinessId()))
                .orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Payment request not found"));
        return view(refresh(e), user, Optional.of(access));
    }

    public PaymentRequestView cancelForBusiness(User user, BusinessWalletAccessService.Access access, String requestId) {
        WalletPaymentRequestEntity e = requests.findByPublicRef(requestId == null ? "" : requestId)
                .filter(r -> access.businessId().equals(r.getRequesterBusinessId()))
                .orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Payment request not found"));
        e = refresh(e);
        if (requests.cancelForBusiness(e.getId(), access.businessId(), Instant.now(), OPEN, CANCELLED) == 1) {
            WalletPaymentRequestEntity after = reload(e);
            audit(user, "WALLET_PAYMENT_REQUEST_CANCELLED", after, "business=" + access.walletPublicId());
            return view(after, user, Optional.of(access));
        }
        WalletPaymentRequestEntity after = reload(e);
        if (after.getStatus() == CANCELLED) return view(after, user, Optional.of(access));
        throw WalletException.conflict("NOT_CANCELLABLE", "This request can no longer be cancelled (" + after.getStatus().name().toLowerCase() + ")");
    }

    /** Open a scanned/pasted code for REVIEW. Never moves funds. */
    public PaymentRequestView resolve(User viewer, String code) {
        WalletPaymentRequestEntity e = authorized(byCode(code), viewer);
        return view(refresh(e), viewer);
    }

    public PaymentRequestView cancel(User requester, String requestId) {
        WalletPaymentRequestEntity e = requests.findByPublicRef(requestId == null ? "" : requestId)
                .filter(r -> r.getRequesterUserId().equals(requester.getId()) && r.getRequesterBusinessId() == null)
                .orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Payment request not found"));
        e = refresh(e);
        if (requests.cancel(e.getId(), requester.getId(), Instant.now(), OPEN, CANCELLED) == 1) {
            WalletPaymentRequestEntity after = reload(e);
            audit(requester, "WALLET_PAYMENT_REQUEST_CANCELLED", after, "");
            return view(after, requester);
        }
        WalletPaymentRequestEntity after = reload(e);
        if (after.getStatus() == CANCELLED) return view(after, requester); // repeat is harmless
        throw WalletException.conflict("NOT_CANCELLABLE", "This request can no longer be cancelled (" + after.getStatus().name().toLowerCase() + ")");
    }

    public PaymentRequestView reject(User payer, String code) {
        WalletPaymentRequestEntity e = authorized(byCode(code), payer);
        if (!payer.getId().equals(e.getTargetPayerUserId())) {
            throw WalletException.conflict("NOT_REJECTABLE", "Only a request addressed to you can be declined. You can simply not pay an open request.");
        }
        e = refresh(e);
        if (requests.reject(e.getId(), payer.getId(), Instant.now(), OPEN, REJECTED) == 1) {
            WalletPaymentRequestEntity after = reload(e);
            audit(payer, "WALLET_PAYMENT_REQUEST_REJECTED", after, "");
            return view(after, payer);
        }
        WalletPaymentRequestEntity after = reload(e);
        if (after.getStatus() == REJECTED) return view(after, payer);
        throw stateError(after.getStatus());
    }

    // ---------------------------------------------------------------- the only path that moves value

    /** Explicit confirmation by the payer. {@code expectedAmount/Currency} are what the payer saw and are re-checked server-side. */
    public PaymentResult pay(User payer, String code, BigDecimal expectedAmount, String expectedCurrency) {
        return pay(payer, code, expectedAmount, expectedCurrency, 0);
    }

    private PaymentResult pay(User payer, String code, BigDecimal expectedAmount, String expectedCurrency, int depth) {
        WalletPaymentRequestEntity e = authorized(byCode(code), payer);
        if (e.getRequesterUserId().equals(payer.getId()) || isBusinessMember(e, payer)) {
            throw WalletException.badRequest("SELF_PAYMENT", "You cannot pay your own request");
        }
        Money expected = Money.ofDecimal(expectedAmount, expectedCurrency);
        if (expected.minor() != e.getAmountMinor() || !expected.currency().equals(e.getCurrency())) {
            throw WalletException.conflict("REQUEST_CHANGED", "The amount or currency does not match this request. Review it again.");
        }
        e = refresh(e);
        switch (e.getStatus()) {
            case PAID -> {
                if (payer.getId().equals(e.getPayerUserId())) return result(e, payer, true);
                throw WalletException.conflict("ALREADY_PAID", "This request has already been paid");
            }
            case EXPIRED -> throw WalletException.gone("REQUEST_EXPIRED", "This payment request has expired");
            case CANCELLED, REJECTED -> throw stateError(e.getStatus());
            case PROCESSING -> {
                if (!payer.getId().equals(e.getPayerUserId())) {
                    throw WalletException.conflict("IN_PROGRESS", "Another payment for this request is being processed");
                }
            }
            case OPEN -> {
                if (requests.claim(e.getId(), payer.getId(), Instant.now(), OPEN, PROCESSING) != 1) {
                    if (depth >= 1) throw WalletException.conflict("IN_PROGRESS", "This request is being processed. Please check its status.");
                    return pay(payer, code, expectedAmount, expectedCurrency, depth + 1); // lost a race: re-evaluate once
                }
            }
        }
        // From here this payer, and only this payer, holds the claim.
        Money money = new Money(e.getAmountMinor(), e.getCurrency());
        Account from = accountService.getOrCreateWallet(payer.getId(), e.getCurrency());
        Account to;
        if (e.getRequesterBusinessId() != null) {
            if (!businesses.findById(e.getRequesterBusinessId()).map(Business::isActive).orElse(false)) {
                requests.release(e.getId(), payer.getId(), Instant.now(), OPEN, PROCESSING); // nothing was posted
                throw WalletException.conflict("BUSINESS_UNAVAILABLE", "This business can no longer receive payments");
            }
            to = accountService.getOrCreateBusinessWallet(e.getRequesterBusinessId(), e.getCurrency());
        } else {
            to = accountService.getOrCreateWallet(e.getRequesterUserId(), e.getCurrency());
        }
        LedgerEngine.Result r;
        try {
            r = ledger.transfer(Actor.ofUser(payer.getId()), from.id(), to.id(), money, ledgerKey(e));
        } catch (LedgerException le) {
            if (DEFINITIVE_REFUSALS.contains(le.code())) {
                requests.release(e.getId(), payer.getId(), Instant.now(), OPEN, PROCESSING); // nothing was posted
            } // otherwise the outcome may be unknown: keep the claim; this payer's retry replays the same ledger key
            throw le;
        }
        if (requests.markPaid(e.getId(), payer.getId(), r.tx().id(), Instant.now(), PROCESSING, PAID) != 1) {
            WalletPaymentRequestEntity now = reload(e);
            if (now.getStatus() != PAID) {
                log.error("Ledger posted {} but request {} could not be marked paid (status {}); the sweeper will finalise it",
                        r.tx().providerReference(), e.getId(), now.getStatus());
            }
        }
        WalletPaymentRequestEntity after = reload(e);
        audit(payer, "WALLET_PAYMENT_REQUEST_PAID", after, "SIMULATED ref=" + r.tx().providerReference());
        return result(after, payer, r.replayed());
    }

    // ---------------------------------------------------------------- maintenance

    /** Expires overdue OPEN requests and settles stale claims. Safe to run on every node and to repeat. */
    public int sweep() {
        Instant now = Instant.now();
        int changed = 0;
        for (WalletPaymentRequestEntity e : requests.findDue(List.of(OPEN), now, PageRequest.of(0, 200))) {
            changed += requests.expire(e.getId(), now, OPEN, EXPIRED);
        }
        for (WalletPaymentRequestEntity e : requests.findStaleProcessing(PROCESSING, now.minus(staleProcessing), PageRequest.of(0, 200))) {
            Optional<LedgerTransactionEntity> tx = txRepo.findByInitiatorAndKey(e.getPayerUserId(), ledgerKey(e));
            if (tx.isPresent() && tx.get().getStatus() == TxStatus.POSTED) {
                changed += requests.markPaid(e.getId(), e.getPayerUserId(), tx.get().getId(), now, PROCESSING, PAID);
            } else if (tx.isEmpty()) {
                changed += requests.release(e.getId(), e.getPayerUserId(), now, OPEN, PROCESSING);
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- helpers

    private static String ledgerKey(WalletPaymentRequestEntity e) {
        return "payreq:" + e.getId();
    }

    private WalletPaymentRequestEntity byCode(String code) {
        return qr.verify(code).flatMap(requests::findByPublicRef)
                .orElseThrow(() -> WalletException.notFound("INVALID_CODE", "This payment code is not valid"));
    }

    /** A targeted request is invisible to everyone except its requester and its target. */
    private WalletPaymentRequestEntity authorized(WalletPaymentRequestEntity e, User viewer) {
        boolean ok = e.getTargetPayerUserId() == null || e.getTargetPayerUserId().equals(viewer.getId())
                || e.getRequesterUserId().equals(viewer.getId()) || isBusinessMember(e, viewer);
        if (!ok) throw WalletException.notFound("INVALID_CODE", "This payment code is not valid");
        return e;
    }

    private WalletPaymentRequestEntity reload(WalletPaymentRequestEntity e) {
        return requests.findById(e.getId()).orElse(e);
    }

    /** Persists expiry the first time an overdue OPEN request is touched; returns the current row. */
    private WalletPaymentRequestEntity refresh(WalletPaymentRequestEntity e) {
        if (e.getStatus() == OPEN && !e.getExpiresAt().isAfter(Instant.now())) {
            requests.expire(e.getId(), Instant.now(), OPEN, EXPIRED);
            return reload(e);
        }
        return e;
    }

    private static WalletException stateError(PaymentRequestStatus s) {
        return switch (s) {
            case EXPIRED -> WalletException.gone("REQUEST_EXPIRED", "This payment request has expired");
            case CANCELLED -> WalletException.conflict("REQUEST_CANCELLED", "This payment request was cancelled");
            case REJECTED -> WalletException.conflict("REQUEST_REJECTED", "This payment request was declined");
            case PAID -> WalletException.conflict("ALREADY_PAID", "This request has already been paid");
            default -> WalletException.conflict("IN_PROGRESS", "This request is being processed");
        };
    }

    private PaymentResult result(WalletPaymentRequestEntity e, User viewer, boolean replayed) {
        var txView = e.getLedgerTransactionId() == null ? null : wallet.viewOf(viewer, e.getLedgerTransactionId());
        return new PaymentResult(view(e, viewer), txView, replayed, true, SIMULATED_NOTICE);
    }

    private boolean isBusinessMember(WalletPaymentRequestEntity e, User user) {
        return e.getRequesterBusinessId() != null && businessAccess.findByBusinessId(user, e.getRequesterBusinessId()).isPresent();
    }

    private PaymentRequestView view(WalletPaymentRequestEntity e, User viewer) {
        return view(e, viewer, Optional.empty());
    }

    /** {@code known} is the viewer's already-verified access to the request's business (saves a lookup per row in lists). */
    private PaymentRequestView view(WalletPaymentRequestEntity e, User viewer, Optional<BusinessWalletAccessService.Access> known) {
        Instant now = Instant.now();
        PaymentRequestStatus status = e.getStatus() == OPEN && !e.getExpiresAt().isAfter(now) ? EXPIRED : e.getStatus();
        boolean personal = e.getRequesterBusinessId() == null;
        boolean mine = personal && e.getRequesterUserId().equals(viewer.getId());
        Optional<BusinessWalletAccessService.Access> member = personal ? Optional.empty()
                : known.isPresent() ? known : businessAccess.findByBusinessId(viewer, e.getRequesterBusinessId());
        boolean ownSide = mine || member.isPresent();
        boolean target = viewer.getId().equals(e.getTargetPayerUserId());
        String direction = ownSide ? "OUTGOING" : target ? "INCOMING" : "OPEN_LINK";
        String requesterName = personal ? (mine ? "You" : name(e.getRequesterUserId())) : businessName(e.getRequesterBusinessId());
        String targetName = ownSide && e.getTargetPayerUserId() != null ? name(e.getTargetPayerUserId()) : null;
        String txRef = e.getLedgerTransactionId() == null ? null
                : txRepo.findById(e.getLedgerTransactionId()).map(LedgerTransactionEntity::getProviderReference).orElse(null);
        boolean canPay = status == OPEN && !ownSide && (e.getTargetPayerUserId() == null || target);
        boolean canCancel = status == OPEN && (mine || (member.isPresent() && member.get().allows(Permission.REQUEST)));
        return new PaymentRequestView(e.getPublicRef(), ownSide || target ? qr.tokenFor(e.getPublicRef()) : null, direction, status.name(),
                new Money(e.getAmountMinor(), e.getCurrency()).toDecimal().toPlainString(), e.getCurrency(), e.getPurpose(),
                requesterName, targetName, e.getExpiresAt(), e.getCreatedAt(), e.getResolvedAt(), txRef, canPay,
                status == OPEN && target, canCancel, true, SIMULATED_NOTICE);
    }

    private String businessName(String businessId) {
        return businesses.findById(businessId).map(Business::getName).orElse("Business");
    }

    private String name(String userId) {
        return users.findById(userId).map(u -> WalletIdentityService.maskName(u.getFullName())).orElse("PULA user");
    }

    private static String cleanPurpose(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) throw WalletException.badRequest("INVALID_PURPOSE", "Please say what the payment is for");
        return s.length() > 140 ? s.substring(0, 140) : s;
    }

    private void audit(User actor, String action, WalletPaymentRequestEntity e, String details) {
        try {
            audit.record(actor, action, "WALLET_PAYMENT_REQUEST", e.getId(), details);
        } catch (RuntimeException ex) {
            log.error("Audit entry for {} could not be written ({})", action, ex.getClass().getSimpleName());
        }
    }
}
