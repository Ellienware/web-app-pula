package com.momocircle.repository;

import com.momocircle.domain.Review;
import com.momocircle.domain.enums.ReviewTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, String> {

    Page<Review> findByTargetTypeAndTargetIdAndHiddenFalseOrderByCreatedAtDesc(
            ReviewTargetType targetType, String targetId, Pageable pageable);

    boolean existsByAuthorIdAndTargetTypeAndTargetId(String authorId, ReviewTargetType targetType, String targetId);

    Optional<Review> findByOrderIdAndTargetTypeAndTargetId(String orderId, ReviewTargetType targetType, String targetId);

    @Query("select avg(r.rating) from Review r where r.targetType = :type and r.targetId = :id and r.hidden = false")
    BigDecimal averageRating(@Param("type") ReviewTargetType type, @Param("id") String targetId);

    long countByTargetTypeAndTargetIdAndHiddenFalse(ReviewTargetType targetType, String targetId);

    boolean existsByDedupeKey(String dedupeKey);

    /**
     * One row: avg+count for taste, portion, value, quality, packaging, delivery experience, then
     * the number of verified-order reviews. Hidden reviews are excluded.
     */
    @Query("select avg(r.taste), count(r.taste), avg(r.portionSize), count(r.portionSize), "
            + "avg(r.valueForMoney), count(r.valueForMoney), avg(r.foodQuality), count(r.foodQuality), "
            + "avg(r.packaging), count(r.packaging), avg(r.deliveryExperience), count(r.deliveryExperience), "
            + "sum(case when r.verifiedOrder = true then 1 else 0 end) "
            + "from Review r where r.targetType = :type and r.targetId = :id and r.hidden = false")
    java.util.List<Object[]> dimensionAverages(@Param("type") ReviewTargetType type, @Param("id") String targetId);
}
