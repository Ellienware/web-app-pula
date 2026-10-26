package com.momocircle.ledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves that balance projections equal the sum of immutable postings, that every transaction balances, and that value is
 * conserved (all balances in a currency sum to zero). Pure function: feed it a consistent snapshot. Returns an empty list
 * when everything agrees.
 */
public final class Reconciler {

    public record Discrepancy(String kind, String subject, String detail) { }

    private Reconciler() { }

    public static List<Discrepancy> reconcile(Collection<Account> accounts, Map<String, Long> projectedBalances,
                                              Collection<LedgerTransaction> allTransactions) {
        List<Discrepancy> out = new ArrayList<>();
        Map<String, Account> byId = new HashMap<>();
        for (Account a : accounts) byId.put(a.id(), a);
        Map<String, Long> derived = new HashMap<>();

        for (LedgerTransaction tx : allTransactions) {
            try {
                LedgerEngine.assertBalanced(tx.postings(), tx.currency());
            } catch (LedgerException e) {
                out.add(new Discrepancy("UNBALANCED_TX", tx.id(), e.getMessage()));
            }
            for (Posting p : tx.postings()) {
                Account a = byId.get(p.accountId());
                if (a == null) {
                    out.add(new Discrepancy("UNKNOWN_ACCOUNT", tx.id(), p.accountId()));
                } else if (!a.currency().equals(p.currency())) {
                    out.add(new Discrepancy("CURRENCY_MISMATCH", tx.id(), p.accountId()));
                }
                derived.merge(p.accountId(), p.delta(), Long::sum);
            }
        }

        Map<String, Long> perCurrency = new HashMap<>();
        for (Account a : accounts) {
            long d = derived.getOrDefault(a.id(), 0L);
            long projected = projectedBalances.getOrDefault(a.id(), 0L);
            if (d != projected) {
                out.add(new Discrepancy("BALANCE_MISMATCH", a.id(), "ledger=" + d + " projection=" + projected));
            }
            if (!a.allowNegative() && d < 0) {
                out.add(new Discrepancy("NEGATIVE_BALANCE", a.id(), "ledger=" + d));
            }
            perCurrency.merge(a.currency(), d, Long::sum);
        }
        for (Map.Entry<String, Long> e : perCurrency.entrySet()) {
            if (e.getValue() != 0L) {
                out.add(new Discrepancy("VALUE_NOT_CONSERVED", e.getKey(), "sum=" + e.getValue()));
            }
        }
        checkReversals(allTransactions, out);
        return out;
    }

    /**
     * Reversal audit: every REVERSAL points at exactly one existing, non-reversal original whose postings it mirrors
     * exactly (opposite direction, same account/amount/currency); each original has at most one reversal; and every
     * original that is REVERSED, FAILED or CANCELLED has its compensating reversal.
     */
    private static void checkReversals(Collection<LedgerTransaction> all, List<Discrepancy> out) {
        Map<String, LedgerTransaction> byId = new HashMap<>();
        for (LedgerTransaction t : all) byId.put(t.id(), t);
        Map<String, Integer> reversalsPerOriginal = new HashMap<>();

        for (LedgerTransaction t : all) {
            if (t.type() != TxType.REVERSAL) {
                if (t.reversesTxId() != null) {
                    out.add(new Discrepancy("REVERSAL_INVALID", t.id(), "non-reversal transaction references an original"));
                }
                continue;
            }
            LedgerTransaction original = t.reversesTxId() == null ? null : byId.get(t.reversesTxId());
            if (original == null) {
                out.add(new Discrepancy("REVERSAL_INVALID", t.id(), "original transaction missing"));
                continue;
            }
            reversalsPerOriginal.merge(original.id(), 1, Integer::sum);
            if (original.type() == TxType.REVERSAL) {
                out.add(new Discrepancy("REVERSAL_INVALID", t.id(), "a reversal cannot reverse a reversal"));
            }
            TxStatus s = original.status();
            if (s != TxStatus.REVERSED && s != TxStatus.FAILED && s != TxStatus.CANCELLED) {
                out.add(new Discrepancy("REVERSAL_INVALID", t.id(), "original is " + s + ", not reversed/failed/cancelled"));
            }
            if (!mirrors(original.postings(), t.postings())) {
                out.add(new Discrepancy("REVERSAL_INVALID", t.id(), "postings do not mirror the original"));
            }
        }
        for (Map.Entry<String, Integer> e : reversalsPerOriginal.entrySet()) {
            if (e.getValue() > 1) {
                out.add(new Discrepancy("DUPLICATE_REVERSAL", e.getKey(), e.getValue() + " reversals"));
            }
        }
        for (LedgerTransaction t : all) {
            boolean unwound = t.status() == TxStatus.REVERSED || t.status() == TxStatus.FAILED || t.status() == TxStatus.CANCELLED;
            if (t.type() != TxType.REVERSAL && unwound && !reversalsPerOriginal.containsKey(t.id())) {
                out.add(new Discrepancy("REVERSAL_MISSING", t.id(), "status " + t.status() + " has no compensating reversal"));
            }
        }
    }

    private static boolean mirrors(List<Posting> original, List<Posting> reversal) {
        if (original.size() != reversal.size()) return false;
        List<Posting> remaining = new ArrayList<>(reversal);
        for (Posting p : original) {
            if (!remaining.remove(p.opposite())) return false;
        }
        return remaining.isEmpty();
    }
}
