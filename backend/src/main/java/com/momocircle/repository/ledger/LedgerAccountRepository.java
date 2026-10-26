package com.momocircle.repository.ledger;

import com.momocircle.domain.ledger.LedgerAccountEntity;
import com.momocircle.ledger.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccountEntity, String> {

    /** Row lock ({@code SELECT ... FOR UPDATE}). Callers must lock several accounts in sorted id order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LedgerAccountEntity a where a.id = :id")
    Optional<LedgerAccountEntity> findByIdForUpdate(@Param("id") String id);

    /** Scalar read that bypasses the persistence-context cache, so it always reflects the locked row. */
    @Query("select a.balanceMinor from LedgerAccountEntity a where a.id = :id")
    Optional<Long> findBalanceMinor(@Param("id") String id);

    @Query("select a from LedgerAccountEntity a where a.kind = :kind and a.currency = :currency and a.ownerUserId is null")
    Optional<LedgerAccountEntity> findSystemAccount(@Param("kind") Account.Kind kind, @Param("currency") String currency);

    @Query("select a from LedgerAccountEntity a where a.ownerUserId = :owner and a.currency = :currency and a.kind = :kind")
    Optional<LedgerAccountEntity> findWallet(@Param("owner") String owner, @Param("currency") String currency,
                                             @Param("kind") Account.Kind kind);

    /** The only statement that changes a balance projection; always run in the same transaction as the postings. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update LedgerAccountEntity a set a.balanceMinor = a.balanceMinor + :delta where a.id = :id")
    int addToBalance(@Param("id") String id, @Param("delta") long delta);

    @Query("select a from LedgerAccountEntity a where a.ownerBusinessId = :business and a.currency = :currency and a.kind = :kind")
    Optional<LedgerAccountEntity> findBusinessWallet(@Param("business") String business, @Param("currency") String currency,
                                                     @Param("kind") Account.Kind kind);
}
