package com.momocircle.repository;

import com.momocircle.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface ProductRepository extends JpaRepository<Product, String> {
    Page<Product> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    /** Used by BusinessReadinessService (brief §8) — "has this business listed anything yet". */
    long countByBusinessId(String businessId);

    /** PULA Food: blocks deleting a menu category that still has items. */
    long countByMenuCategoryId(String menuCategoryId);

    /**
     * Backs both "browse by category" and "browse everything" (pass category=null) — and filters
     * price server-side rather than the caller fetching a page and discarding rows client-side.
     * {@code category}/{@code maxPrice} being null means "no filter on that field".
     */
    @Query("select p from Product p where p.status = com.momocircle.domain.enums.ProductStatus.ACTIVE "
            + "and (:category is null or p.category = :category) "
            + "and (:maxPrice is null or p.price <= :maxPrice) "
            + "order by p.createdAt desc")
    Page<Product> browse(@Param("category") String category, @Param("maxPrice") BigDecimal maxPrice,
                          Pageable pageable);

    /**
     * Phase 5 — consumer-facing browse scoped to sellers that actually have CAN_SELL_TO_CONSUMERS.
     * Joins through business_capabilities rather than trusting business.businessType alone, so a
     * wholesaler that inadvertently ends up in the consumer feed gets filtered out automatically.
     */
    @Query("select p from Product p "
            + "join p.business b "
            + "join b.capabilities cap "
            + "where p.status = com.momocircle.domain.enums.ProductStatus.ACTIVE "
            + "and cap = com.momocircle.domain.enums.BusinessCapability.CAN_SELL_TO_CONSUMERS "
            + "and (:category is null or p.category = :category) "
            + "and (:maxPrice is null or p.price <= :maxPrice) "
            + "order by p.createdAt desc")
    Page<Product> browseConsumer(@Param("category") String category, @Param("maxPrice") BigDecimal maxPrice,
                                  Pageable pageable);

    @Query("select p from Product p where p.status = com.momocircle.domain.enums.ProductStatus.ACTIVE "
            + "and lower(p.name) like lower(concat('%', :term, '%')) "
            + "and (:maxPrice is null or p.price <= :maxPrice) "
            + "order by p.createdAt desc")
    Page<Product> search(@Param("term") String term, @Param("maxPrice") BigDecimal maxPrice, Pageable pageable);

    @Query("select p from Product p "
            + "join p.business b "
            + "join b.capabilities cap "
            + "where p.id = :productId "
            + "and p.status = com.momocircle.domain.enums.ProductStatus.ACTIVE "
            + "and cap = com.momocircle.domain.enums.BusinessCapability.CAN_SELL_TO_CONSUMERS")
    java.util.Optional<Product> findConsumerProductById(@Param("productId") String productId);

    @Query("select p from Product p "
        + "join p.business b "
        + "join b.capabilities cap "
        + "where p.status = com.momocircle.domain.enums.ProductStatus.ACTIVE "
        + "and cap = com.momocircle.domain.enums.BusinessCapability.CAN_SELL_TO_CONSUMERS "
        + "and lower(p.name) like lower(concat('%', :term, '%')) "
        + "and (:maxPrice is null or p.price <= :maxPrice) "
        + "order by p.createdAt desc")
        Page<Product> searchConsumer(@Param("term") String term,
                             @Param("maxPrice") BigDecimal maxPrice,
                             Pageable pageable);
    /**
     * Phase 5 — returns the N most-used active product categories for the category picker on the
     * consumer market home. Capped at 20 in the service layer; used to populate the category pills
     * so users can browse without having to type. Only considers products from sellers that have
     * CAN_SELL_TO_CONSUMERS so the pill list never shows wholesale-only categories to consumers.
     */
    @Query("select p.category, count(p) as cnt from Product p "
            + "join p.business b "
            + "join b.capabilities cap "
            + "where p.status = com.momocircle.domain.enums.ProductStatus.ACTIVE "
            + "and cap = com.momocircle.domain.enums.BusinessCapability.CAN_SELL_TO_CONSUMERS "
            + "and p.category is not null "
            + "group by p.category order by cnt desc")
    java.util.List<Object[]> findPopularCategories(Pageable pageable);

    @Query("select p from Product p where p.canonicalProduct.id = :canonicalProductId "
            + "and p.matchClassification = com.momocircle.domain.enums.MatchClassification.EXACT_MATCH "
            + "order by p.price asc")
    java.util.List<Product> findExactMatchesForCanonicalProduct(@Param("canonicalProductId") String canonicalProductId);

    @Query("select p from Product p where p.canonicalProduct.id = :canonicalProductId "
            + "and p.matchClassification = com.momocircle.domain.enums.MatchClassification.SIMILAR_ALTERNATIVE "
            + "order by p.price asc")
    java.util.List<Product> findSimilarAlternativesForCanonicalProduct(@Param("canonicalProductId") String canonicalProductId);

    /**
     * PULA Food discovery: active items of active food businesses. Price, branch and availability
     * rules are applied in FoodDiscoveryService because they depend on branch overrides. The page
     * caps the candidate set; see the service's note on scale.
     */
    @Query("select p from Product p where p.status = com.momocircle.domain.enums.ProductStatus.ACTIVE "
            + "and p.business.active = true and p.business.businessType in :types order by p.name asc")
    java.util.List<Product> findFoodCandidates(
            @Param("types") java.util.Collection<com.momocircle.domain.enums.BusinessType> types,
            org.springframework.data.domain.Pageable pageable);

    java.util.List<Product> findByBusinessIdAndStatusOrderByNameAsc(String businessId, com.momocircle.domain.enums.ProductStatus status);

    /** Row lock used when moving a product's central stock to/from a branch. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    java.util.Optional<Product> findByIdForUpdate(@Param("id") String id);

    java.util.List<Product> findByBusinessIdOrderByNameAsc(String businessId);
}
