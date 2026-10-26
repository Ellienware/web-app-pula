package com.momocircle.repository;

import com.momocircle.domain.CanonicalProduct;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CanonicalProductRepository extends JpaRepository<CanonicalProduct, String> {

    Optional<CanonicalProduct> findByBarcode(String barcode);

    /** Used by ProductService#suggestCanonicalProducts — capped in-memory in the service, not here. */
    java.util.List<CanonicalProduct> findByCategory(String category);

    @Query("select c from CanonicalProduct c where lower(c.name) like lower(concat('%', :term, '%')) "
            + "order by c.name asc")
    Page<CanonicalProduct> search(@Param("term") String term, Pageable pageable);
}
