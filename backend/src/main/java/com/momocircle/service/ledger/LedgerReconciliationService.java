package com.momocircle.service.ledger;

import com.momocircle.domain.ledger.LedgerAccountEntity;
import com.momocircle.domain.ledger.LedgerPostingEntity;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.ledger.Account;
import com.momocircle.ledger.LedgerTransaction;
import com.momocircle.ledger.Reconciler;
import com.momocircle.repository.ledger.LedgerAccountRepository;
import com.momocircle.repository.ledger.LedgerPostingRepository;
import com.momocircle.repository.ledger.LedgerTransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the framework-free {@link Reconciler} over a consistent snapshot of the database. Service-level only: there is
 * deliberately no HTTP endpoint in Phase 9A. Memory use is proportional to the number of transactions (loaded in pages of
 * {@value #PAGE}, postings fetched per page in ONE query); suitable for demo volumes, to be replaced by a SQL aggregate
 * job if the ledger grows large.
 */
@Service
public class LedgerReconciliationService {

    static final int PAGE = 500;

    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerPostingRepository postings;

    public LedgerReconciliationService(LedgerAccountRepository accounts, LedgerTransactionRepository transactions,
                                       LedgerPostingRepository postings) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.postings = postings;
    }

    /** @return discrepancies; an empty list means projections, postings and relationships all agree. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Reconciler.Discrepancy> reconcile() {
        List<Account> domainAccounts = new ArrayList<>();
        Map<String, Long> projections = new HashMap<>();
        for (LedgerAccountEntity a : accounts.findAll()) {
            domainAccounts.add(a.toDomain());
            projections.put(a.getId(), a.getBalanceMinor());
        }

        List<Reconciler.Discrepancy> extra = new ArrayList<>();
        List<LedgerTransaction> all = new ArrayList<>();
        int page = 0;
        Page<LedgerTransactionEntity> current;
        do {
            current = transactions.findAll(PageRequest.of(page++, PAGE, Sort.by("id")));
            List<String> ids = new ArrayList<>();
            for (LedgerTransactionEntity t : current.getContent()) ids.add(t.getId());
            Map<String, List<LedgerPostingEntity>> byTx = ids.isEmpty() ? Map.of()
                    : LedgerMapper.groupByTransaction(postings.findByTransactions(ids));
            for (LedgerTransactionEntity t : current.getContent()) {
                if (!t.isSimulated()) {
                    extra.add(new Reconciler.Discrepancy("NOT_SIMULATED", t.getId(), "ledger transaction is not marked simulated"));
                }
                all.add(LedgerMapper.toDomain(t, byTx.getOrDefault(t.getId(), List.of())));
            }
        } while (current.hasNext());

        List<Reconciler.Discrepancy> out = new ArrayList<>(Reconciler.reconcile(domainAccounts, projections, all));
        out.addAll(extra);
        return out;
    }
}
