package com.momocircle.repository;

import com.momocircle.domain.BusinessIncomeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BusinessIncomeEntryRepository extends JpaRepository<BusinessIncomeEntry, String> {
    List<BusinessIncomeEntry> findByBusinessIdAndReceivedDateBetweenOrderByReceivedDateDesc(
            String businessId, LocalDate from, LocalDate to);
    Optional<BusinessIncomeEntry> findByIdAndBusinessId(String id, String businessId);

    @Query("select coalesce(sum(e.amount), 0) from BusinessIncomeEntry e "
            + "where e.business.id = :businessId "
            + "and e.receivedDate >= :from and e.receivedDate <= :to")
    BigDecimal sumByBusinessInPeriod(@Param("businessId") String businessId,
                                      @Param("from") LocalDate from, @Param("to") LocalDate to);
}
