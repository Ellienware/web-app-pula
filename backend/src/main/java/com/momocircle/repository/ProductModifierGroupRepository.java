package com.momocircle.repository;

import com.momocircle.domain.ProductModifierGroup;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductModifierGroupRepository extends JpaRepository<ProductModifierGroup, String> {
    List<ProductModifierGroup> findByProductId(String productId);
    List<ProductModifierGroup> findByProductIdIn(java.util.Collection<String> productIds);
    Optional<ProductModifierGroup> findByProductIdAndGroupId(String productId, String groupId);
}
