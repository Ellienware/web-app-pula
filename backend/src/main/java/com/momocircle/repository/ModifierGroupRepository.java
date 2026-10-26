package com.momocircle.repository;

import com.momocircle.domain.ModifierGroup;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModifierGroupRepository extends JpaRepository<ModifierGroup, String> {
    List<ModifierGroup> findByBusinessIdOrderByNameAsc(String businessId);
}
