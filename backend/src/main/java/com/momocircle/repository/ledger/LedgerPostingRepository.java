package com.momocircle.repository.ledger;

import com.momocircle.domain.ledger.LedgerPostingEntity;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

/**
 * Deliberately NOT a CrudRepository: postings are immutable, so no save/delete/update operations are exposed. Inserts go
 * through {@code EntityManager.persist} in the ledger store; this interface offers reads only.
 */
public interface LedgerPostingRepository extends Repository<LedgerPostingEntity, String> {

    @Query("select p from LedgerPostingEntity p where p.transactionId = :txId order by p.lineNo")
    List<LedgerPostingEntity> findByTransaction(@Param("txId") String txId);

    /** Batch read for histories and reconciliation: one query for many transactions (no N+1). */
    @Query("select p from LedgerPostingEntity p where p.transactionId in :ids order by p.transactionId, p.lineNo")
    List<LedgerPostingEntity> findByTransactions(@Param("ids") Collection<String> ids);

    /** Sum of posting amounts on one account for one direction; used to reconcile a wallet view with its balance projection. */
    @Query("select coalesce(sum(p.amountMinor), 0L) from LedgerPostingEntity p where p.accountId = :accountId and p.direction = :direction")
    Long sumByAccountAndDirection(@Param("accountId") String accountId, @Param("direction") com.momocircle.ledger.Posting.Direction direction);

    @Query("select count(p) from LedgerPostingEntity p")
    long countAll();
}
