package com.momocircle.service.wallet;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.ledger.LedgerAccountEntity;
import com.momocircle.domain.ledger.LedgerPostingEntity;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.dto.WalletDtos.LimitsView;
import com.momocircle.dto.WalletDtos.PageView;
import com.momocircle.dto.WalletDtos.ReconciliationView;
import com.momocircle.dto.WalletDtos.RecipientView;
import com.momocircle.dto.WalletDtos.TransactionView;
import com.momocircle.dto.WalletDtos.WalletOverview;
import com.momocircle.ledger.Account;
import com.momocircle.ledger.Actor;
import com.momocircle.ledger.LedgerEngine;
import com.momocircle.ledger.LedgerLimits;
import com.momocircle.ledger.LedgerTransaction;
import com.momocircle.ledger.Money;
import com.momocircle.ledger.Posting;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.ledger.LedgerAccountRepository;
import com.momocircle.repository.ledger.LedgerPostingRepository;
import com.momocircle.repository.ledger.LedgerTransactionRepository;
import com.momocircle.service.ledger.LedgerAccountService;
import com.momocircle.service.ledger.LedgerOperationsService;
import com.momocircle.wallet.WalletException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.momocircle.dto.WalletDtos.SIMULATED_NOTICE;

/**
 * The PULA Money DEMO wallet. This class holds NO balance of its own: every balance shown is read from the shared ledger's
 * projection and every change is posted by {@link LedgerOperationsService}. All value is simulated.
 *
 * <p>The acting user is always a parameter derived from the authenticated principal by the controller; no method here reads
 * an owner id from request data. Deliberately not {@code @Transactional}: the ledger owns its transactions and retries.
 */
@Service
public class WalletService {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_.:-]{8,100}$");

    private final LedgerOperationsService ledger;
    private final LedgerAccountService accountService;
    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerPostingRepository postings;
    private final WalletHistoryQuery historyQuery;
    private final WalletIdentityService identity;
    private final UserRepository users;
    private final BusinessRepository businesses;
    private final LedgerLimits limits;
    private final CurrencyProperties currencyProperties;

    public WalletService(LedgerOperationsService ledger, LedgerAccountService accountService, LedgerAccountRepository accounts,
                         LedgerTransactionRepository transactions, LedgerPostingRepository postings, WalletHistoryQuery historyQuery,
                         WalletIdentityService identity, UserRepository users, BusinessRepository businesses, LedgerLimits limits,
                         CurrencyProperties currencyProperties) {
        this.ledger = ledger;
        this.accountService = accountService;
        this.accounts = accounts;
        this.transactions = transactions;
        this.postings = postings;
        this.historyQuery = historyQuery;
        this.identity = identity;
        this.users = users;
        this.businesses = businesses;
        this.limits = limits;
        this.currencyProperties = currencyProperties;
    }

    // ---------------------------------------------------------------- wallet context (personal or business)

    /**
     * The wallet an operation acts on and the ledger {@link Actor} acting on it. Personal: the user's own wallet, acting as
     * themselves. Business: the business's wallet, acting as that user FOR that business (only ever built after
     * {@link BusinessWalletAccessService} verified membership and permission). The service never reads an owner id from a request.
     */
    public record Ctx(Account wallet, Actor actor, String publicId, User user, String businessWalletId) {
        public boolean isBusiness() { return businessWalletId != null; }
        /** Prefix that keeps one client Idempotency-Key from colliding between a user's personal wallet and each business wallet. */
        String keyScope(String op) { return isBusiness() ? "b" + op + ":" + businessWalletId + ":" : op + ":"; }
    }

    public Ctx personal(User user) {
        Account wallet = accountService.getOrCreateWallet(user.getId(), currency(null));
        return new Ctx(wallet, Actor.ofUser(user.getId()), identity.publicIdFor(user.getId()), user, null);
    }

    public Ctx business(User user, BusinessWalletAccessService.Access access) {
        Account wallet = accountService.getOrCreateBusinessWallet(access.businessId(), currency(null));
        return new Ctx(wallet, Actor.forBusiness(user.getId(), access.businessId()), access.walletPublicId(), user, access.walletPublicId());
    }

    // ---------------------------------------------------------------- overview

    public WalletOverview overview(User user) { return overview(personal(user)); }

    public WalletOverview overview(Ctx ctx) {
        String currency = ctx.wallet().currency();
        long balance = accounts.findBalanceMinor(ctx.wallet().id()).orElse(0L);
        return new WalletOverview(true, SIMULATED_NOTICE, ctx.publicId(), currency,
                new Money(balance, currency).toDecimal().toPlainString(), balance, limitsView(currency));
    }

    private LimitsView limitsView(String c) {
        return new LimitsView(dec(limits.maxTransferMinor(), c), dec(limits.maxIssuancePerRequestMinor(), c),
                dec(limits.maxIssuanceTotalPerUserMinor(), c), (int) limits.issuanceWindow().toDays(), dec(limits.maxWithdrawalMinor(), c));
    }

    // ---------------------------------------------------------------- history & detail

    public PageView<TransactionView> history(User user, WalletHistoryQuery.Filter filter, int page, int size) {
        return history(personal(user), filter, page, size);
    }

    public PageView<TransactionView> history(Ctx ctx, WalletHistoryQuery.Filter filter, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        Page<LedgerTransactionEntity> result = historyQuery.history(ctx.wallet().id(), filter, PageRequest.of(Math.max(page, 0), safeSize));
        List<TransactionView> items = toViews(ctx.wallet().id(), result.getContent());
        return new PageView<>(items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /** A transaction the wallet took part in. Anything else is reported as not found, never as forbidden. */
    public TransactionView detail(User user, String reference) { return detail(personal(user), reference); }

    public TransactionView detail(Ctx ctx, String reference) {
        LedgerTransactionEntity tx = transactions.findByProviderReference(reference == null ? "" : reference.trim().toUpperCase())
                .filter(t -> historyQuery.touches(ctx.wallet().id(), t.getId()))
                .orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Transaction not found"));
        return toViews(ctx.wallet().id(), List.of(tx)).get(0);
    }

    /** The view of one ledger transaction from the wallet's perspective (used right after a posting). */
    public TransactionView viewOf(User user, String txId) { return viewOf(personal(user), txId); }

    public TransactionView viewOf(Ctx ctx, String txId) {
        LedgerTransactionEntity tx = transactions.findById(txId)
                .filter(t -> historyQuery.touches(ctx.wallet().id(), t.getId()))
                .orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Transaction not found"));
        return toViews(ctx.wallet().id(), List.of(tx)).get(0);
    }

    // ---------------------------------------------------------------- recipients

    /** Resolves a wallet id (PW- user wallet, BW- business wallet), email or phone to a masked recipient for confirmation. */
    public RecipientView lookupRecipient(User user, String identifier) { return lookupRecipient(personal(user), identifier); }

    public RecipientView lookupRecipient(Ctx ctx, String identifier) {
        String id = identifier == null ? "" : identifier.trim();
        if (id.toUpperCase().startsWith("BW-")) {
            Business b = identity.businessForPublicId(id).orElseThrow(() -> recipientNotFound());
            String publicId = identity.businessPublicIdFor(b.getId());
            if (publicId.equals(ctx.publicId())) throw WalletException.badRequest("SELF_TRANSFER", "That is this wallet");
            return new RecipientView(publicId, b.getName(), true);
        }
        User found = identity.lookup(id).orElseThrow(() -> recipientNotFound());
        if (!ctx.isBusiness() && found.getId().equals(ctx.user().getId())) {
            throw WalletException.badRequest("SELF_TRANSFER", "That is your own wallet");
        }
        return new RecipientView(identity.publicIdFor(found.getId()), WalletIdentityService.maskName(found.getFullName()), true);
    }

    private static WalletException recipientNotFound() {
        return WalletException.notFound("RECIPIENT_NOT_FOUND", "No eligible PULA wallet matches that wallet id, email or phone number");
    }

    /** Destination wallet for a transfer: a PW- user wallet or a BW- business wallet. Inactive/unknown are indistinguishable. */
    private Account destination(Ctx ctx, String recipientWalletId, String currency) {
        String id = recipientWalletId == null ? "" : recipientWalletId.trim().toUpperCase();
        if (id.startsWith("BW-")) {
            Business b = identity.businessForPublicId(id).orElseThrow(() -> recipientNotFound());
            return accountService.getOrCreateBusinessWallet(b.getId(), currency);
        }
        User recipient = identity.userForPublicId(id).orElseThrow(() -> recipientNotFound());
        if (!ctx.isBusiness() && recipient.getId().equals(ctx.user().getId())) {
            throw WalletException.badRequest("SELF_TRANSFER", "You cannot send demo funds to yourself");
        }
        return accountService.getOrCreateWallet(recipient.getId(), currency);
    }

    // ---------------------------------------------------------------- money-changing operations

    public record Posted(TransactionView view, boolean replayed) { }

    public Posted fund(User user, BigDecimal amount, String requestedCurrency, String idempotencyKey) {
        return fund(personal(user), amount, requestedCurrency, idempotencyKey);
    }

    public Posted fund(Ctx ctx, BigDecimal amount, String requestedCurrency, String idempotencyKey) {
        String currency = currency(requestedCurrency);
        Money money = Money.ofDecimal(amount, currency);
        accountService.ensureSystemAccount(Account.Kind.DEMO_FUNDING, currency);
        LedgerEngine.Result r = ledger.issueDemoValue(ctx.actor(), ctx.wallet().id(), money, ctx.keyScope("fund") + requireKey(idempotencyKey));
        return new Posted(viewOf(ctx, r.tx().id()), r.replayed());
    }

    public Posted transfer(User user, String recipientWalletId, BigDecimal amount, String requestedCurrency, String idempotencyKey) {
        return transfer(personal(user), recipientWalletId, amount, requestedCurrency, idempotencyKey);
    }

    public Posted transfer(Ctx ctx, String recipientWalletId, BigDecimal amount, String requestedCurrency, String idempotencyKey) {
        String currency = currency(requestedCurrency);
        Money money = Money.ofDecimal(amount, currency);
        String key = requireKey(idempotencyKey);
        Account to = destination(ctx, recipientWalletId, currency);
        LedgerEngine.Result r = ledger.transfer(ctx.actor(), ctx.wallet().id(), to.id(), money, ctx.keyScope("xfer") + key);
        return new Posted(viewOf(ctx, r.tx().id()), r.replayed());
    }

    public Posted requestWithdrawal(User user, BigDecimal amount, String requestedCurrency, String idempotencyKey) {
        return requestWithdrawal(personal(user), amount, requestedCurrency, idempotencyKey);
    }

    public Posted requestWithdrawal(Ctx ctx, BigDecimal amount, String requestedCurrency, String idempotencyKey) {
        String currency = currency(requestedCurrency);
        Money money = Money.ofDecimal(amount, currency);
        accountService.ensureSystemAccount(Account.Kind.WITHDRAWAL_CLEARING, currency);
        LedgerEngine.Result r = ledger.requestWithdrawal(ctx.actor(), ctx.wallet().id(), money, ctx.keyScope("wd") + requireKey(idempotencyKey));
        return new Posted(viewOf(ctx, r.tx().id()), r.replayed());
    }

    public TransactionView cancelWithdrawal(User user, String reference) { return cancelWithdrawal(personal(user), reference); }

    /** Only the member who requested the withdrawal (or an operator) can cancel it; the engine enforces that. */
    public TransactionView cancelWithdrawal(Ctx ctx, String reference) {
        LedgerTransactionEntity tx = byReference(reference);
        if (!historyQuery.touches(ctx.wallet().id(), tx.getId())) {
            throw WalletException.notFound("NOT_FOUND", "Transaction not found"); // never reveal that it exists
        }
        ledger.cancelWithdrawal(ctx.actor(), tx.getId());
        return viewOf(ctx, tx.getId());
    }

    // ---------------------------------------------------------------- operator (admin) simulated outcomes

    public TransactionView completeWithdrawal(User admin, String reference) {
        LedgerTransactionEntity tx = byReference(reference);
        LedgerTransaction done = ledger.completeWithdrawal(Actor.asAdmin(admin.getId()), tx.getId());
        return operatorView(done.id());
    }

    public TransactionView failWithdrawal(User admin, String reference, String reason) {
        LedgerTransactionEntity tx = byReference(reference);
        LedgerTransaction done = ledger.failWithdrawal(Actor.asAdmin(admin.getId()), tx.getId(), reason == null || reason.isBlank() ? "Simulated failure" : reason);
        return operatorView(done.id());
    }

    /** Operator queue of PENDING simulated withdrawals (oldest first), each shown from the withdrawing wallet's perspective. */
    public PageView<TransactionView> pendingWithdrawals(int page, int size) {
        Page<LedgerTransactionEntity> p = historyQuery.pendingWithdrawals(PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50))));
        List<TransactionView> items = new ArrayList<>();
        for (LedgerTransactionEntity t : p.getContent()) items.add(operatorView(t.getId()));
        return new PageView<>(items, p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    /** Wallet view vs ledger: the balance projection must equal credits - debits of that wallet's postings. */
    public ReconciliationView reconcileWallet(String walletPublicId) {
        String id = walletPublicId == null ? "" : walletPublicId.trim().toUpperCase();
        Account wallet;
        if (id.startsWith("BW-")) {
            Business b = identity.businessForPublicId(id).orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Wallet not found"));
            wallet = accountService.getOrCreateBusinessWallet(b.getId(), currency(null));
        } else {
            User owner = identity.userForPublicId(id).orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Wallet not found"));
            wallet = accountService.getOrCreateWallet(owner.getId(), currency(null));
        }
        long balance = accounts.findBalanceMinor(wallet.id()).orElse(0L);
        long credits = nz(postings.sumByAccountAndDirection(wallet.id(), Posting.Direction.CREDIT));
        long debits = nz(postings.sumByAccountAndDirection(wallet.id(), Posting.Direction.DEBIT));
        return new ReconciliationView(balance == credits - debits, balance, credits - debits);
    }

    // ---------------------------------------------------------------- helpers

    public String currency(String requested) {
        String def = currencyProperties.getDefaultCurrency();
        if (requested != null && !requested.isBlank() && !requested.equals(def)) {
            throw WalletException.badRequest("CURRENCY_MISMATCH", "Only " + def + " demo funds are supported");
        }
        return def;
    }

    private static String requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw WalletException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                    "An Idempotency-Key header of 8-100 letters, digits or . _ : - is required");
        }
        return key;
    }

    private LedgerTransactionEntity byReference(String reference) {
        return transactions.findByProviderReference(reference == null ? "" : reference.trim().toUpperCase())
                .orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Transaction not found"));
    }

    /** Operator view: shown from the first posting's perspective (the wallet side). */
    private TransactionView operatorView(String txId) {
        LedgerTransactionEntity tx = transactions.findById(txId).orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Transaction not found"));
        List<LedgerPostingEntity> ps = postings.findByTransaction(txId);
        String walletId = ps.stream().map(LedgerPostingEntity::getAccountId).filter(id -> accounts.findById(id)
                .map(a -> a.getKind() == Account.Kind.USER_WALLET || a.getKind() == Account.Kind.BUSINESS_WALLET).orElse(false)).findFirst().orElse(ps.get(0).getAccountId());
        return toViews(walletId, List.of(tx)).get(0);
    }

    /** Batch-maps transactions to views from one wallet's perspective: three bulk reads, no N+1. */
    List<TransactionView> toViews(String walletAccountId, List<LedgerTransactionEntity> txs) {
        if (txs.isEmpty()) return List.of();
        List<String> txIds = txs.stream().map(LedgerTransactionEntity::getId).toList();
        Map<String, List<LedgerPostingEntity>> byTx = new HashMap<>();
        Set<String> accountIds = new HashSet<>();
        for (LedgerPostingEntity p : postings.findByTransactions(txIds)) {
            byTx.computeIfAbsent(p.getTransactionId(), k -> new ArrayList<>()).add(p);
            accountIds.add(p.getAccountId());
        }
        Map<String, LedgerAccountEntity> acct = new HashMap<>();
        for (LedgerAccountEntity a : accounts.findAllById(accountIds)) acct.put(a.getId(), a);
        Set<String> ownerIds = new HashSet<>();
        for (LedgerAccountEntity a : acct.values()) if (a.getOwnerUserId() != null) ownerIds.add(a.getOwnerUserId());
        Map<String, String> names = new HashMap<>();
        for (User u : users.findAllById(ownerIds)) names.put(u.getId(), WalletIdentityService.maskName(u.getFullName()));
        Set<String> businessIds = new HashSet<>();
        for (LedgerAccountEntity a : acct.values()) if (a.getOwnerBusinessId() != null) businessIds.add(a.getOwnerBusinessId());
        Map<String, String> businessNames = new HashMap<>();
        for (Business b : businesses.findAllById(businessIds)) businessNames.put(b.getId(), b.getName());
        Set<String> reversedIds = new HashSet<>();
        for (LedgerTransactionEntity t : txs) if (t.getReversesTxId() != null) reversedIds.add(t.getReversesTxId());
        Map<String, String> refs = new HashMap<>();
        for (LedgerTransactionEntity t : transactions.findAllById(reversedIds)) refs.put(t.getId(), t.getProviderReference());

        List<TransactionView> out = new ArrayList<>();
        for (LedgerTransactionEntity t : txs) {
            long net = 0;
            String counterparty = "PULA demo";
            for (LedgerPostingEntity p : byTx.getOrDefault(t.getId(), List.of())) {
                if (p.getAccountId().equals(walletAccountId)) {
                    net += p.getDirection() == Posting.Direction.CREDIT ? p.getAmountMinor() : -p.getAmountMinor();
                } else {
                    LedgerAccountEntity other = acct.get(p.getAccountId());
                    if (other != null) counterparty = counterpartyLabel(other, names, businessNames);
                }
            }
            out.add(new TransactionView(t.getProviderReference(), t.getType().name(), t.getStatus().name(), net >= 0 ? "IN" : "OUT",
                    dec(Math.abs(net), t.getCurrency()), t.getCurrency(), counterparty,
                    t.getReversesTxId() == null ? null : refs.get(t.getReversesTxId()), t.getFailureReason(), t.getCreatedAt(), true,
                    SIMULATED_NOTICE));
        }
        return out;
    }

    private static String counterpartyLabel(LedgerAccountEntity other, Map<String, String> names, Map<String, String> businessNames) {
        return switch (other.getKind()) {
            case USER_WALLET -> names.getOrDefault(other.getOwnerUserId(), "PULA user");
            case BUSINESS_WALLET -> businessNames.getOrDefault(other.getOwnerBusinessId(), "Business");
            case DEMO_FUNDING -> "PULA demo funding";
            case WITHDRAWAL_CLEARING -> "Simulated withdrawal";
        };
    }

    static String dec(long minor, String currency) {
        return new Money(minor, currency).toDecimal().toPlainString();
    }

    private static long nz(Long v) { return v == null ? 0L : v; }
}
