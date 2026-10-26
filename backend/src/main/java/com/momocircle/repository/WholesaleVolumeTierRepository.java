package com.momocircle.repository;

import com.momocircle.domain.WholesaleVolumeTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WholesaleVolumeTierRepository extends JpaRepository<WholesaleVolumeTier, String> {
    List<WholesaleVolumeTier> findByWholesaleProductIdOrderByMinPacksAsc(String wholesaleProductId);
    void deleteByWholesaleProductId(String wholesaleProductId);
}
