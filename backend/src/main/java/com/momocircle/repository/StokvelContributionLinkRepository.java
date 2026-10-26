package com.momocircle.repository;

import com.momocircle.domain.StokvelContributionLink;
import com.momocircle.domain.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface StokvelContributionLinkRepository extends JpaRepository<StokvelContributionLink, String> {

    Optional<StokvelContributionLink> findByContributionId(String contributionId);

    boolean existsByCircleId(String circleId);

    /** Rows of [userId, periodIndex, confirmed amount] for every member/period that has linked contributions. */
    @Query("""
        select l.user.id, l.periodIndex, coalesce(sum(c.payment.amount), 0)
        from StokvelContributionLink l join l.contribution c
        where l.circle.id = :circleId and c.payment.status = :status
        group by l.user.id, l.periodIndex
    """)
    List<Object[]> confirmedByMemberAndPeriod(@Param("circleId") String circleId,
                                              @Param("status") PaymentStatus status);

    @Query("""
        select coalesce(sum(c.payment.amount), 0)
        from StokvelContributionLink l join l.contribution c
        where l.circle.id = :circleId and l.periodIndex = :period and c.payment.status = :status
    """)
    BigDecimal confirmedForPeriod(@Param("circleId") String circleId, @Param("period") int period,
                                  @Param("status") PaymentStatus status);
}
