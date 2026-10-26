package com.momocircle.ledger;

import com.momocircle.ledger.LedgerException.Code;
import com.momocircle.ledger.Posting.Direction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * All shared-ledger business rules, independent of any framework or database. Everything here is SIMULATED value:
 * nothing in this class can contact a bank, card network or mobile-money operator, and there is no code path that marks a
 * transaction as real money.
 *
 * <p>Rules enforced: integer minor units; every transaction balances (debits = credits) in one currency; user wallets never
 * go negative; the actor must own the source wallet (never a client-supplied user id); idempotency is scoped to
 * (initiator, key) and a replay with different parameters is rejected; posted entries are never edited, corrections are
 * compensating entries; state changes follow {@link TxStatus#canTransitionTo}.
 */
public final class LedgerEngine {

    public record Result(LedgerTransaction tx, boolean replayed) { }

    private final LedgerStore store;
    private final LedgerLimits limits;
    private final Supplier<String> ids;
    private final Clock clock;

    public LedgerEngine(LedgerStore store, LedgerLimits limits, Supplier<String> ids, Clock clock) {
        this.store = store;
        this.limits = limits;
        this.ids = ids;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- demo value

    /** Issues clearly-labelled demo value to the actor's own wallet. This is not a deposit and not a bank transfer. */
    public Result issueDemoValue(Actor actor, String walletId, Money amount, String key) {
        requirePositive(amount);
        if (amount.minor() > limits.maxIssuancePerRequestMinor()) {
            throw new LedgerException(Code.LIMIT_EXCEEDED, "Demo value per request is limited");
        }
        String fundingId = store.systemAccountId(Account.Kind.DEMO_FUNDING, amount.currency())
                .orElseThrow(() -> new LedgerException(Code.ACCOUNT_NOT_FOUND, "Demo funding is not configured"));
        List<Posting> postings = List.of(
                new Posting(fundingId, Direction.DEBIT, amount.minor(), amount.currency()),
                new Posting(walletId, Direction.CREDIT, amount.minor(), amount.currency()));
        return post(actor, TxType.DEMO_ISSUANCE, TxStatus.POSTED, key, amount.currency(), postings, w -> {
            Account wallet = requireWallet(w, walletId, amount.currency());
            requireOwner(actor, wallet);
            java.time.Instant windowStart = clock.instant().minus(limits.issuanceWindow());
            if (w.issuedTotalMinor(actor.userId(), amount.currency(), windowStart) + amount.minor()
                    > limits.maxIssuanceTotalPerUserMinor()) {
                throw new LedgerException(Code.LIMIT_EXCEEDED, "Demo value limit for this period has been reached");
            }
        });
    }

    // ---------------------------------------------------------------- internal transfer

    public Result transfer(Actor actor, String fromWalletId, String toWalletId, Money amount, String key) {
        requirePositive(amount);
        if (fromWalletId.equals(toWalletId)) {
            throw new LedgerException(Code.SELF_TRANSFER, "Cannot transfer to the same wallet");
        }
        if (amount.minor() > limits.maxTransferMinor()) {
            throw new LedgerException(Code.LIMIT_EXCEEDED, "Transfer exceeds the per-transfer limit");
        }
        List<Posting> postings = List.of(
                new Posting(fromWalletId, Direction.DEBIT, amount.minor(), amount.currency()),
                new Posting(toWalletId, Direction.CREDIT, amount.minor(), amount.currency()));
        return post(actor, TxType.INTERNAL_TRANSFER, TxStatus.POSTED, key, amount.currency(), postings, w -> {
            Account from = requireWallet(w, fromWalletId, amount.currency());
            requireWallet(w, toWalletId, amount.currency());
            requireOwner(actor, from);
        });
    }

    // ---------------------------------------------------------------- simulated withdrawal

    /** Holds funds (wallet -> clearing) in PENDING state. No real cash or bank transfer ever happens. */
    public Result requestWithdrawal(Actor actor, String walletId, Money amount, String key) {
        requirePositive(amount);
        if (amount.minor() > limits.maxWithdrawalMinor()) {
            throw new LedgerException(Code.LIMIT_EXCEEDED, "Withdrawal exceeds the limit");
        }
        String clearingId = store.systemAccountId(Account.Kind.WITHDRAWAL_CLEARING, amount.currency())
                .orElseThrow(() -> new LedgerException(Code.ACCOUNT_NOT_FOUND, "Withdrawal clearing is not configured"));
        List<Posting> postings = List.of(
                new Posting(walletId, Direction.DEBIT, amount.minor(), amount.currency()),
                new Posting(clearingId, Direction.CREDIT, amount.minor(), amount.currency()));
        return post(actor, TxType.SIMULATED_WITHDRAWAL, TxStatus.PENDING, key, amount.currency(), postings, w -> {
            Account wallet = requireWallet(w, walletId, amount.currency());
            requireOwner(actor, wallet);
        });
    }

    /** Simulated outcome: success. System or admin only. Idempotent. */
    public LedgerTransaction completeWithdrawal(Actor actor, String txId) {
        requireOperator(actor);
        LedgerTransaction peek = peek(txId);
        return store.atomically(accountsOf(peek), w -> {
            LedgerTransaction tx = requireWithdrawal(w, txId);
            if (tx.status() == TxStatus.POSTED) return tx;
            w.updateStatus(txId, TxStatus.POSTED, null);
            return tx.withStatus(TxStatus.POSTED, null);
        });
    }

    /** Simulated outcome: failure. Funds return to the wallet through compensating entries. System or admin only. */
    public LedgerTransaction failWithdrawal(Actor actor, String txId, String reason) {
        requireOperator(actor);
        return unwind(actor, txId, TxStatus.FAILED, reason);
    }

    /** The wallet owner (or an admin) cancels a still-pending withdrawal. */
    public LedgerTransaction cancelWithdrawal(Actor actor, String txId) {
        LedgerTransaction peek = peek(txId);
        if (!actor.admin() && !actor.system() && !actor.userId().equals(peek.initiatorUserId())) {
            throw new LedgerException(Code.NOT_OWNER, "Not your transaction");
        }
        return unwind(actor, txId, TxStatus.CANCELLED, "Cancelled by requester");
    }

    /** Admin/system correction of a POSTED transaction via compensating entries. Fails closed if funds were already spent. */
    public LedgerTransaction reverse(Actor actor, String txId, String reason) {
        requireOperator(actor);
        return unwind(actor, txId, TxStatus.REVERSED, reason);
    }

    /**
     * Operator/system only. Returns {@code amount} of a POSTED payment transfer to the payer by posting a NEW compensating
     * transfer from the payee back to the payer; the original is never edited. Unlike {@link #reverse} this can be called
     * several times for one original (partial refunds). The ENGINE enforces only that the amount is positive, no larger
     * than the original and that the payee can fund it (no overdraft); the CUMULATIVE cap across refunds is the caller's
     * responsibility (OrderRefundService, under the payment row lock). Idempotent per (initiator, key).
     */
    public Result refundTransfer(Actor operator, String originalTxId, Money amount, String key) {
        requireOperator(operator);
        requirePositive(amount);
        LedgerTransaction peek = peek(originalTxId);
        Posting debit = null, credit = null;
        if (peek.type() == TxType.INTERNAL_TRANSFER && peek.postings().size() == 2) {
            for (Posting p : peek.postings()) {
                if (p.direction() == Direction.DEBIT) debit = p; else credit = p;
            }
        }
        if (debit == null || credit == null) {
            throw new LedgerException(Code.ILLEGAL_STATE, "Only a two-party transfer can be refunded");
        }
        if (!peek.currency().equals(amount.currency())) {
            throw new LedgerException(Code.CURRENCY_MISMATCH, "Refund currency differs from the payment currency");
        }
        if (amount.minor() > debit.minor()) {
            throw new LedgerException(Code.INVALID_AMOUNT, "Refund cannot exceed the original payment");
        }
        final String payer = debit.accountId(), payee = credit.accountId();
        List<Posting> postings = List.of(
                new Posting(payee, Direction.DEBIT, amount.minor(), amount.currency()),
                new Posting(payer, Direction.CREDIT, amount.minor(), amount.currency()));
        return post(operator, TxType.INTERNAL_TRANSFER, TxStatus.POSTED, key, amount.currency(), postings, w -> {
            LedgerTransaction original = w.find(originalTxId)
                    .orElseThrow(() -> new LedgerException(Code.NOT_FOUND, "Transaction not found"));
            if (original.status() != TxStatus.POSTED) {
                throw new LedgerException(Code.ILLEGAL_STATE, "Only a posted payment can be refunded");
            }
        });
    }

    // ---------------------------------------------------------------- internals

    private LedgerTransaction unwind(Actor actor, String txId, TxStatus target, String reason) {
        LedgerTransaction peek = peek(txId);
        List<String> locks = accountsOf(peek);
        return store.atomically(locks, w -> {
            LedgerTransaction original = w.find(txId)
                    .orElseThrow(() -> new LedgerException(Code.NOT_FOUND, "Transaction not found"));
            if (original.status() == target) return original; // idempotent repeat
            if (original.type() == TxType.REVERSAL) {
                throw new LedgerException(Code.ILLEGAL_STATE, "A reversal cannot itself be reversed");
            }
            if (!original.status().canTransitionTo(target)) {
                throw new LedgerException(Code.ILLEGAL_STATE, "Cannot move " + original.status() + " to " + target);
            }
            List<Posting> opposite = new ArrayList<>();
            for (Posting p : original.postings()) opposite.add(p.opposite());
            checkOverdraft(w, opposite);
            String id = ids.get();
            LedgerTransaction comp = new LedgerTransaction(id, TxType.REVERSAL, TxStatus.POSTED, actor.userId(),
                    "reversal:" + txId, fingerprint(TxType.REVERSAL, actor.userId(), opposite), original.currency(),
                    opposite, txId, reference(id), null, clock.instant());
            w.insertAndApply(comp);
            w.updateStatus(txId, target, reason);
            return original.withStatus(target, reason);
        });
    }

    private Result post(Actor actor, TxType type, TxStatus status, String key, String currency, List<Posting> postings,
                        Consumer<LedgerStore.Work> checks) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new LedgerException(Code.IDEMPOTENCY_CONFLICT, "An idempotency key (max 128 chars) is required");
        }
        assertBalanced(postings, currency);
        String fp = fingerprint(type, actor.userId(), postings);
        Set<String> lockIds = new HashSet<>();
        for (Posting p : postings) lockIds.add(p.accountId());
        return store.atomically(lockIds, w -> {
            var existing = w.findByIdempotency(actor.userId(), key);
            if (existing.isPresent()) {
                if (!existing.get().fingerprint().equals(fp)) {
                    throw new LedgerException(Code.IDEMPOTENCY_CONFLICT,
                            "This idempotency key was already used with different parameters");
                }
                return new Result(existing.get(), true);
            }
            checks.accept(w);
            checkOverdraft(w, postings);
            String id = ids.get();
            LedgerTransaction tx = new LedgerTransaction(id, type, status, actor.userId(), key, fp, currency, postings,
                    null, reference(id), null, clock.instant());
            w.insertAndApply(tx);
            return new Result(tx, false);
        });
    }

    private static void checkOverdraft(LedgerStore.Work w, List<Posting> postings) {
        Map<String, Long> delta = new HashMap<>();
        for (Posting p : postings) delta.merge(p.accountId(), p.delta(), Long::sum);
        for (Map.Entry<String, Long> e : delta.entrySet()) {
            Account a = w.account(e.getKey())
                    .orElseThrow(() -> new LedgerException(Code.ACCOUNT_NOT_FOUND, "Account not found"));
            if (!a.allowNegative() && w.balance(a.id()) + e.getValue() < 0) {
                throw new LedgerException(Code.INSUFFICIENT_FUNDS, "Insufficient funds");
            }
        }
    }

    static void assertBalanced(List<Posting> postings, String currency) {
        if (postings.size() < 2) {
            throw new LedgerException(Code.UNBALANCED, "A transaction needs at least two postings");
        }
        long debits = 0, credits = 0;
        for (Posting p : postings) {
            if (!p.currency().equals(currency)) {
                throw new LedgerException(Code.CURRENCY_MISMATCH, "All postings must share the transaction currency");
            }
            if (p.direction() == Direction.DEBIT) debits = Math.addExact(debits, p.minor());
            else credits = Math.addExact(credits, p.minor());
        }
        if (debits != credits) {
            throw new LedgerException(Code.UNBALANCED, "Debits must equal credits");
        }
    }

    private LedgerTransaction peek(String txId) {
        return store.atomically(List.of(), w -> w.find(txId)
                .orElseThrow(() -> new LedgerException(Code.NOT_FOUND, "Transaction not found")));
    }

    private static List<String> accountsOf(LedgerTransaction tx) {
        List<String> out = new ArrayList<>();
        for (Posting p : tx.postings()) out.add(p.accountId());
        return out;
    }

    private static LedgerTransaction requireWithdrawal(LedgerStore.Work w, String txId) {
        LedgerTransaction tx = w.find(txId).orElseThrow(() -> new LedgerException(Code.NOT_FOUND, "Transaction not found"));
        if (tx.type() != TxType.SIMULATED_WITHDRAWAL) {
            throw new LedgerException(Code.ILLEGAL_STATE, "Not a withdrawal");
        }
        return tx;
    }

    private static Account requireWallet(LedgerStore.Work w, String id, String currency) {
        Account a = w.account(id).orElseThrow(() -> new LedgerException(Code.ACCOUNT_NOT_FOUND, "Wallet not found"));
        if (a.kind() != Account.Kind.USER_WALLET && a.kind() != Account.Kind.BUSINESS_WALLET) {
            throw new LedgerException(Code.ACCOUNT_NOT_FOUND, "Wallet not found");
        }
        if (!a.currency().equals(currency)) {
            throw new LedgerException(Code.CURRENCY_MISMATCH, "Wallet currency differs from the amount currency");
        }
        return a;
    }

    /**
     * A user wallet is debited only by its owner acting as themselves. A business wallet is debited only by an actor that
     * was authorised for exactly that business (see {@link Actor#forBusiness}); an actor acting for a business can never
     * touch a personal wallet through that context, and a plain user actor can never touch a business wallet.
     */
    private static void requireOwner(Actor actor, Account wallet) {
        if (wallet.kind() == Account.Kind.BUSINESS_WALLET) {
            if (actor.actingBusinessId() == null || !actor.actingBusinessId().equals(wallet.ownerBusinessId())) {
                throw new LedgerException(Code.NOT_OWNER, "You do not have access to this wallet");
            }
            return;
        }
        if (actor.actingBusinessId() != null || !actor.userId().equals(wallet.ownerUserId())) {
            throw new LedgerException(Code.NOT_OWNER, "You do not own this wallet");
        }
    }

    private static void requireOperator(Actor actor) {
        if (!actor.admin() && !actor.system()) {
            throw new LedgerException(Code.NOT_OWNER, "Operator access required");
        }
    }

    private static void requirePositive(Money m) {
        if (!m.isPositive()) {
            throw new LedgerException(Code.INVALID_AMOUNT, "Amount must be greater than zero");
        }
    }

    static String reference(String txId) {
        String compact = txId.replace("-", "").toUpperCase();
        return "SIM-" + compact.substring(0, Math.min(12, compact.length()));
    }

    static String fingerprint(TxType type, String userId, List<Posting> postings) {
        StringBuilder sb = new StringBuilder(type.name()).append('|').append(userId);
        for (Posting p : postings) {
            sb.append('|').append(p.accountId()).append(':').append(p.direction()).append(':').append(p.minor())
                    .append(':').append(p.currency());
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
