package com.momocircle.repository;

import com.momocircle.domain.CanonicalFoodAlias;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CanonicalFoodAliasRepository extends JpaRepository<CanonicalFoodAlias, String> {
    List<CanonicalFoodAlias> findByCanonicalProductId(String canonicalProductId);
    void deleteByCanonicalProductId(String canonicalProductId);
}
