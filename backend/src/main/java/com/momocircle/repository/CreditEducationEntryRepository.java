package com.momocircle.repository;

import com.momocircle.domain.CreditEducationEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditEducationEntryRepository extends JpaRepository<CreditEducationEntry, String> {
    List<CreditEducationEntry> findByUserIdOrderByCreatedAtDesc(String userId);
}
