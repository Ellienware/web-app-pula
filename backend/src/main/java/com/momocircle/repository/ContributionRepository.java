package com.momocircle.repository;

import com.momocircle.domain.Contribution;
import com.momocircle.domain.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ContributionRepository extends JpaRepository<Contribution, String> {

    List<Contribution> findByCircleIdOrderByCreatedAtDesc(String circleId);

    java.util.Optional<Contribution> findByPaymentId(String paymentId);

    @Query("""
        select coalesce(sum(c.payment.amount), 0)
        from Contribution c
        where c.circle.id = :circleId and c.payment.status = :status
    """)
    BigDecimal sumConfirmedAmount(@Param("circleId") String circleId,
                                  @Param("status") PaymentStatus status);

    @Query("""
        select coalesce(sum(c.payment.amount), 0)
        from Contribution c
        where c.goal.id = :goalId and c.payment.status = :status
    """)
    BigDecimal sumConfirmedAmountForGoal(@Param("goalId") String goalId,
                                         @Param("status") PaymentStatus status);

    @Query("select coalesce(sum(c.payment.amount), 0) from Contribution c where c.payment.status = :status")
    BigDecimal sumAllConfirmedAmount(@Param("status") PaymentStatus status);

    /** The caller's OWN recorded contributions to a goal (same recorded-status rule as the group total). */
    @Query("""
        select coalesce(sum(c.payment.amount), 0)
        from Contribution c
        where c.goal.id = :goalId and c.contributor.id = :userId and c.payment.status = :status
    """)
    BigDecimal sumConfirmedAmountForGoalAndContributor(@Param("goalId") String goalId,
                                                       @Param("userId") String userId,
                                                       @Param("status") PaymentStatus status);
}
