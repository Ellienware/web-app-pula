package com.momocircle.repository;

import com.momocircle.domain.WholesaleProduct;
import com.momocircle.domain.enums.MatchClassification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WholesaleProductRepository extends JpaRepository<WholesaleProduct, String> {

    Page<WholesaleProduct> findBySupplierBusinessIdAndActiveTrue(String supplierBusinessId, Pageable pageable);

    /** Browse active wholesale products with optional category filter — for the PULA Market Stock browse. */
    @Query("select p from WholesaleProduct p where p.active = true "
            + "and (:category is null or p.category = :category) "
            + "order by p.createdAt desc")
    Page<WholesaleProduct> browse(@Param("category") String category, Pageable pageable);

    /** Search by name — for the stock-market search bar. */
    @Query("select p from WholesaleProduct p where p.active = true "
            + "and lower(p.name) like lower(concat('%', :term, '%')) "
            + "order by p.createdAt desc")
    Page<WholesaleProduct> search(@Param("term") String term, Pageable pageable);

    Optional<WholesaleProduct> findByIdAndSupplierBusinessId(String id, String supplierBusinessId);

    /** Active listings explicitly linked to a canonical product and classification. */
    java.util.List<WholesaleProduct> findByCanonicalProductIdAndMatchClassificationAndActiveTrue(
            String canonicalProductId, MatchClassification matchClassification);

    /** Used to prevent duplicate SKU within a supplier's catalogue. */
    long countBySupplierBusinessId(String supplierBusinessId);
    boolean existsBySupplierBusinessIdAndSkuAndIdNot(String supplierBusinessId, String sku, String excludeId);
    /** Atomic reservation: succeeds only if enough stock remains, so concurrent checkouts cannot oversell. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("update WholesaleProduct p set p.availableStock = p.availableStock - :qty "
            + "where p.id = :id and p.active = true and p.availableStock >= :qty")
    int reserve(@Param("id") String id, @Param("qty") int qty);

    /** Returns reserved stock (cancelled or expired unpaid orders). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("update WholesaleProduct p set p.availableStock = p.availableStock + :qty where p.id = :id")
    int release(@Param("id") String id, @Param("qty") int qty);
}
