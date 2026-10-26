package com.momocircle.repository;

import org.springframework.transaction.annotation.Transactional;
import com.momocircle.domain.ExpenseShare;
import com.momocircle.domain.enums.SettlementMethod;
import com.momocircle.domain.enums.ShareStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Every state change that matters (consent, claim, settlement, refund) is ONE guarded UPDATE whose WHERE clause names the
 * status it expects. The return value is the number of rows changed: 1 means this caller won, 0 means somebody else already
 * moved the share and the caller must re-read. That is what makes simultaneous attempts on one share safe on every database.
 */
public interface ExpenseShareRepository extends JpaRepository<ExpenseShare, String> {

    // ---- participant responses (consent) ----
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExpenseShare s set s.status = :to, s.respondedAt = :now, s.responseNote = :note, s.updatedAt = :now "
            + "where s.id = :id and s.debtor.id = :debtor and s.status in :from")
    int respond(@Param("id") String id, @Param("debtor") String debtorId, @Param("from") Collection<ShareStatus> from,
                @Param("to") ShareStatus to, @Param("note") String note, @Param("now") Instant now);

    /** The creator stands by the amount and asks again: DISPUTED/DECLINED back to PENDING. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExpenseShare s set s.status = :to, s.respondedAt = null, s.responseNote = :note, s.updatedAt = :now "
            + "where s.id = :id and s.status in :from")
    int reopen(@Param("id") String id, @Param("from") Collection<ShareStatus> from, @Param("to") ShareStatus to,
               @Param("note") String note, @Param("now") Instant now);

    // ---- settlement through the ledger ----

    /** One payer claims the share before the ledger is touched. 1 = this caller holds the claim. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExpenseShare s set s.status = :to, s.claimUserId = :user, s.respondedAt = :now, s.updatedAt = :now "
            + "where s.id = :id and s.debtor.id = :user and s.status in :from and s.amount = :amount")
    int claim(@Param("id") String id, @Param("user") String userId, @Param("from") Collection<ShareStatus> from,
              @Param("to") ShareStatus to, @Param("amount") java.math.BigDecimal amount, @Param("now") Instant now);

    /** The ledger definitively refused (nothing was posted): give the share back, accepted, with no claim. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExpenseShare s set s.status = :to, s.claimUserId = null, s.updatedAt = :now "
            + "where s.id = :id and s.claimUserId = :user and s.status = :from")
    int release(@Param("id") String id, @Param("user") String userId, @Param("from") ShareStatus from,
                @Param("to") ShareStatus to, @Param("now") Instant now);

    /** Called only after the ledger transaction is committed. Names that transaction on the share. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExpenseShare s set s.status = :paid, s.settled = true, s.settledAt = :now, s.settlementMethod = :method, "
            + "s.ledgerTransactionId = :tx, s.settledById = :user, s.claimUserId = null, s.updatedAt = :now "
            + "where s.id = :id and s.claimUserId = :user and s.status = :processing")
    int markPaidByLedger(@Param("id") String id, @Param("user") String userId, @Param("tx") String ledgerTransactionId,
                         @Param("method") SettlementMethod method, @Param("processing") ShareStatus processing,
                         @Param("paid") ShareStatus paid, @Param("now") Instant now);

    /** The payer confirms they were repaid outside PULA. No ledger transaction exists and none is invented. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExpenseShare s set s.status = :paid, s.settled = true, s.settledAt = :now, s.settlementMethod = :method, "
            + "s.settledById = :by, s.updatedAt = :now where s.id = :id and s.status in :from")
    int markPaidExternally(@Param("id") String id, @Param("by") String byUserId, @Param("from") Collection<ShareStatus> from,
                           @Param("method") SettlementMethod method, @Param("paid") ShareStatus paid, @Param("now") Instant now);

    /** A compensating ledger transfer was committed (or an operator reversed the original): the share is no longer settled. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExpenseShare s set s.status = :to, s.settled = false, s.settledAt = null, s.refundTransactionId = :refundTx, "
            + "s.settleRound = s.settleRound + 1, s.updatedAt = :now "
            + "where s.id = :id and s.status = :paid and s.settlementMethod = :ledger")
    int markUnsettled(@Param("id") String id, @Param("refundTx") String refundTransactionId, @Param("to") ShareStatus to,
                      @Param("paid") ShareStatus paid, @Param("ledger") SettlementMethod ledger, @Param("now") Instant now);

    /** The payer retracts "received outside PULA" (it turned out not to be true): back to PENDING, nothing moved either way. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExpenseShare s set s.status = :to, s.settled = false, s.settledAt = null, s.settlementMethod = null, "
            + "s.settledById = null, s.respondedAt = null, s.updatedAt = :now "
            + "where s.id = :id and s.status = :paid and s.settlementMethod in :methods")
    int retractExternal(@Param("id") String id, @Param("paid") ShareStatus paid,
                        @Param("methods") Collection<SettlementMethod> methods, @Param("to") ShareStatus to, @Param("now") Instant now);

    // ---- cancellation ----
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExpenseShare s set s.status = :to, s.updatedAt = :now where s.expense.id = :expense and s.status in :from")
    int cancelOutstanding(@Param("expense") String expenseId, @Param("from") Collection<ShareStatus> from,
                          @Param("to") ShareStatus to, @Param("now") Instant now);

    // ---- queries ----

/**
 * Row-locks every share of an expense so an edit or cancel
 * cannot interleave with a payment claim.
 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ExpenseShare s where s.expense.id = :expense order by s.createdAt")
    List<ExpenseShare> lockByExpense(@Param("expense") String expenseId);

    @Query("select s from ExpenseShare s where s.expense.id = :expense order by s.createdAt")
    List<ExpenseShare> findByExpense(@Param("expense") String expenseId);

    /** Shares stuck in PROCESSING (a crash between the ledger commit and the status update): swept by the recovery job. */
    @Query("select s from ExpenseShare s where s.status = :status and s.updatedAt < :before order by s.updatedAt")
    List<ExpenseShare> findStale(@Param("status") ShareStatus status, @Param("before") Instant before, Pageable page);

    /** What the user owes (they are a debtor and someone else fronted the cost), newest first, across all circles. */
    @Query("select s from ExpenseShare s join fetch s.expense e where s.debtor.id = :user and e.paidBy.id <> :user "
            + "order by e.createdAt desc")
    List<ExpenseShare> findOwedBy(@Param("user") String userId, Pageable page);

    /** What others owe the user (the user fronted the cost). */
    @Query("select s from ExpenseShare s join fetch s.expense e where e.paidBy.id = :user and s.debtor.id <> :user "
            + "order by e.createdAt desc")
    List<ExpenseShare> findOwedTo(@Param("user") String userId, Pageable page);

    @Query("select count(s) from ExpenseShare s where s.expense.id = :expense and s.status in :statuses")
    long countByExpenseAndStatuses(@Param("expense") String expenseId, @Param("statuses") Collection<ShareStatus> statuses);
}
