package com.momocircle.repository;

import com.momocircle.domain.CanonicalFoodAttributes;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CanonicalFoodAttributesRepository extends JpaRepository<CanonicalFoodAttributes, String> {
    Optional<CanonicalFoodAttributes> findByCanonicalProductId(String canonicalProductId);
}
