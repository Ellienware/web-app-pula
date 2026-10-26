package com.momocircle.risk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransactionIntentRepository extends JpaRepository<TransactionIntent, String> {
    Optional<TransactionIntent> findByUserIdAndTypeAndReferenceKeyAndFingerprint(String userId, RiskType type, String referenceKey, String fingerprint);
    List<TransactionIntent> findByPaymentReference(String paymentReference);
    long countByUserIdAndTypeAndStatusAndUpdatedAtAfter(String userId, RiskType type, IntentStatus status, Instant after);
    long countByUserIdAndTypeAndRecipientKeyAndStatus(String userId, RiskType type, String recipientKey, IntentStatus status);
    long countByUserIdAndTypeAndStatusAndSimulated(String userId, RiskType type, IntentStatus status, boolean simulated);

    @Query("select avg(i.amount) from TransactionIntent i where i.userId = :userId and i.type = :type "
            + "and i.status = com.momocircle.risk.IntentStatus.INITIATED and i.simulated = :simulated")
    Double averageInitiatedAmount(@Param("userId") String userId, @Param("type") RiskType type, @Param("simulated") boolean simulated);
}
