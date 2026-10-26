package com.momocircle.repository;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ServiceListingRepository extends JpaRepository<ServiceListing, String> {
    List<ServiceListing> findByBusinessIdAndStatusNotOrderByCreatedAtDesc(String businessId, ServiceListingStatus status);
    Optional<ServiceListing> findByIdAndBusinessId(String id, String businessId);

    @Query("""
        select l from ServiceListing l, ServiceProviderProfile p
        where p.business = l.business
          and l.status = com.momocircle.domain.enums.ServiceListingStatus.ACTIVE
          and l.hiddenByAdmin = false and p.suspended = false and p.acceptingRequests = true
          and l.business.active = true
          and (:categoryId is null or l.category.id = :categoryId or l.category.parent.id = :categoryId)
          and (:term is null or lower(l.name) like :term or lower(l.description) like :term)
          and (:pricingModel is null or l.pricingModel = :pricingModel)
          and (:maxPrice is null or (l.price is not null and l.price <= :maxPrice))
          and (:minRating is null or l.ratingAverage >= :minRating)
          and (:verifiedOnly = false or p.verificationStatus = com.momocircle.domain.enums.ProviderVerificationStatus.VERIFIED)
          and (:area is null or :area member of l.serviceAreas or :area member of p.serviceAreas)
    """)
    Page<ServiceListing> search(@Param("categoryId") String categoryId, @Param("term") String term,
                                @Param("pricingModel") ServicePricingModel pricingModel,
                                @Param("maxPrice") BigDecimal maxPrice, @Param("minRating") BigDecimal minRating,
                                @Param("verifiedOnly") boolean verifiedOnly, @Param("area") String area,
                                Pageable pageable);

    Page<ServiceListing> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
