package com.momocircle.repository;

import com.momocircle.domain.BusinessExpenseEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BusinessExpenseEntryRepository extends JpaRepository<BusinessExpenseEntry, String> {
    List<BusinessExpenseEntry> findByBusinessIdAndIncurredDateBetweenOrderByIncurredDateDesc(
            String businessId, LocalDate from, LocalDate to);
    Optional<BusinessExpenseEntry> findByIdAndBusinessId(String id, String businessId);

    @Query("select coalesce(sum(e.amount), 0) from BusinessExpenseEntry e "
            + "where e.business.id = :businessId "
            + "and e.incurredDate >= :from and e.incurredDate <= :to "
            + "and (:category is null or e.category = :category)")
    BigDecimal sumByBusinessInPeriodAndCategory(@Param("businessId") String businessId,
                                                  @Param("from") LocalDate from, @Param("to") LocalDate to,
                                                  @Param("category") String category);
}
