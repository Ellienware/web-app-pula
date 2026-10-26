package com.momocircle.repository;

import com.momocircle.domain.StokvelCycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface StokvelCycleRepository extends JpaRepository<StokvelCycle, String> {
    List<StokvelCycle> findByCircleIdOrderByCycleNumberAsc(String circleId);
    long countByCircleId(String circleId);
    boolean existsByCircleIdAndBeneficiaryId(String circleId, String beneficiaryId);

    @Query("select coalesce(sum(c.collectedAmount), 0) from StokvelCycle c where c.circle.id = :circleId")
    BigDecimal sumCollectedPaidOut(@Param("circleId") String circleId);
}
