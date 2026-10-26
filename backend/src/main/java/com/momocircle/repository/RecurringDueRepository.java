package com.momocircle.repository;

import com.momocircle.domain.RecurringDue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringDueRepository extends JpaRepository<RecurringDue, String> {
}
