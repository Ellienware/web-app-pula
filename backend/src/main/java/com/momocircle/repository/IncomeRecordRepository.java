package com.momocircle.repository;

import com.momocircle.domain.IncomeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IncomeRecordRepository extends JpaRepository<IncomeRecord, String> {
    Optional<IncomeRecord> findByIdAndUserId(String id, String userId);
    List<IncomeRecord> findByUserIdAndReceivedDateBetweenOrderByReceivedDateAsc(
            String userId, LocalDate from, LocalDate to);
    List<IncomeRecord> findByUserIdOrderByReceivedDateDesc(String userId);
}
