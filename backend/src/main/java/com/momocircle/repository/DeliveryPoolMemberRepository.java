package com.momocircle.repository;

import com.momocircle.domain.DeliveryPoolMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryPoolMemberRepository extends JpaRepository<DeliveryPoolMember, String> {
    List<DeliveryPoolMember> findByBusinessIdOrderByCreatedAtAsc(String businessId);
    Optional<DeliveryPoolMember> findByIdAndBusinessId(String id, String businessId);
    Optional<DeliveryPoolMember> findByBusinessIdAndDriverId(String businessId, String driverId);
    long countByBusinessIdAndActiveTrue(String businessId);
    boolean existsByBusinessIdAndDriverIdAndActiveTrue(String businessId, String driverId);
    List<DeliveryPoolMember> findByDriverIdAndActiveTrue(String driverId);
    List<DeliveryPoolMember> findByDriverIdAndStatus(String driverId, String status);
    Optional<DeliveryPoolMember> findByIdAndDriverId(String id, String driverId);
}
