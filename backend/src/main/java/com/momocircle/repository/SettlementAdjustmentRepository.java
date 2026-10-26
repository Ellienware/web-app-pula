package com.momocircle.repository;

import com.momocircle.domain.SettlementAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface SettlementAdjustmentRepository extends JpaRepository<SettlementAdjustment, String> {
    List<SettlementAdjustment> findBySettlementIdOrderByCreatedAtAsc(String settlementId);
    Optional<SettlementAdjustment> findBySettlementIdAndIdempotencyKey(String settlementId, String key);

    @Query("select coalesce(sum(a.amount), 0) from SettlementAdjustment a where a.settlement.id = :settlementId")
    BigDecimal sumForSettlement(@Param("settlementId") String settlementId);
}
