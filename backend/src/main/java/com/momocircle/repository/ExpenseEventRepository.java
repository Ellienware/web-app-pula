package com.momocircle.repository;

import com.momocircle.domain.ExpenseEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseEventRepository extends JpaRepository<ExpenseEvent, String> {
    List<ExpenseEvent> findByExpenseIdOrderByCreatedAtAsc(String expenseId);
}
