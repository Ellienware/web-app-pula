package com.momocircle.repository;

import com.momocircle.domain.FinancialProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FinancialProfileRepository extends JpaRepository<FinancialProfile, String> {
    Optional<FinancialProfile> findByUserId(String userId);
}
