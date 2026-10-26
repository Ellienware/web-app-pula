package com.momocircle.repository;

import com.momocircle.domain.ProductFoodDetails;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductFoodDetailsRepository extends JpaRepository<ProductFoodDetails, String> {
    Optional<ProductFoodDetails> findByProductId(String productId);

    java.util.List<ProductFoodDetails> findByProductIdIn(java.util.Collection<String> productIds);

    /**
     * Atomic capacity claim: decrements only when enough portions remain, so two concurrent
     * checkouts can never oversell. Returns the rows changed (0 = not enough left).
     */
    @Modifying(flushAutomatically = true)
    @Query("update ProductFoodDetails d set d.dailyQuantity = d.dailyQuantity - :qty "
            + "where d.product.id = :productId and d.dailyQuantity is not null and d.dailyQuantity >= :qty")
    int claimPortions(@Param("productId") String productId, @Param("qty") int qty);

    /** Compensating release after a failed/cancelled/rejected order. No-op for uncapped items. */
    @Modifying(flushAutomatically = true)
    @Query("update ProductFoodDetails d set d.dailyQuantity = d.dailyQuantity + :qty "
            + "where d.product.id = :productId and d.dailyQuantity is not null")
    int releasePortions(@Param("productId") String productId, @Param("qty") int qty);
}
