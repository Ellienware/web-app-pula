package com.momocircle.repository;

import com.momocircle.domain.Expense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExpenseRepository extends JpaRepository<Expense, String> {
    List<Expense> findByCircleIdOrderByCreatedAtDesc(String circleId);

    /** A retried create with the same client key returns the first expense instead of creating a second one. */
    Optional<Expense> findByCreatedByIdAndClientRequestKey(String createdById, String clientRequestKey);
}
