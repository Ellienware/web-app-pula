package com.momocircle.repository.ledger;

import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.ledger.Posting;
import com.momocircle.ledger.TxStatus;
import com.momocircle.ledger.TxType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, String> {

    /** Public lookup by the SIM-... reference shown to users (unique). Callers must still authorise against the wallet. */
    Optional<LedgerTransactionEntity> findByProviderReference(String providerReference);

    @Query("select t from LedgerTransactionEntity t where t.initiatorUserId = :initiator and t.idempotencyKey = :key")
    Optional<LedgerTransactionEntity> findByInitiatorAndKey(@Param("initiator") String initiator, @Param("key") String key);

    /**
     * Demo value credited to wallets by this user's POSTED issuances created at or after {@code since}. Reversed issuances
     * (status REVERSED) no longer count. Expressed in JPQL so the Instant parameter is bound exactly like the column.
     */
    @Query("select coalesce(sum(p.amountMinor), 0L) from LedgerPostingEntity p, LedgerTransactionEntity t "
            + "where t.id = p.transactionId and t.txType = :type and t.status = :status and t.initiatorUserId = :user "
            + "and t.currency = :currency and p.direction = :direction and t.createdAt >= :since")
    Long sumIssued(@Param("type") TxType type, @Param("status") TxStatus status, @Param("user") String user,
                   @Param("currency") String currency, @Param("direction") Posting.Direction direction,
                   @Param("since") Instant since);
}
