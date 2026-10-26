package com.momocircle.repository;

import com.momocircle.domain.RecurringPlan;
import com.momocircle.domain.enums.RecurringStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RecurringPlanRepository extends JpaRepository<RecurringPlan, String> {

    List<RecurringPlan> findByCircleIdOrderByCreatedAtDesc(String circleId);

    /** Plans that are due for a new period to be generated. */
    List<RecurringPlan> findByStatusAndNextDueDateLessThanEqual(RecurringStatus status, LocalDate date);
}
