package com.momocircle.repository;

import com.momocircle.domain.Business;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.domain.enums.BusinessType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, String> {
    Optional<Business> findByOwnerId(String ownerId);
    Page<Business> findByActiveTrue(Pageable pageable);
    Page<Business> findByActiveTrueAndCategory(String category, Pageable pageable);
    Page<Business> findByActiveTrueAndBusinessType(BusinessType businessType, Pageable pageable);
    Page<Business> findByActiveTrueAndCategoryAndBusinessType(String category, BusinessType businessType, Pageable pageable);
    /** Used by PULA Market — Stock to find suppliers (CAN_SUPPLY_BUSINESSES) and by
     *  Shop to find sellers (CAN_SELL_TO_CONSUMERS), independent of BusinessType. */
    Page<Business> findByActiveTrueAndCapabilitiesContaining(BusinessCapability capability, Pageable pageable);
    Page<Business> findByActiveTrueAndCategoryAndCapabilitiesContaining(String category, BusinessCapability capability, Pageable pageable);

    /** PULA Food discovery: active food businesses (type decided by BusinessType.isFood()). */
    Page<Business> findByActiveTrueAndBusinessTypeIn(java.util.Collection<BusinessType> types, Pageable pageable);
}
