package com.momocircle.repository;

import com.momocircle.domain.MenuCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, String> {
    List<MenuCategory> findByBusinessIdOrderBySortOrderAscNameAsc(String businessId);
    boolean existsByBusinessIdAndNameIgnoreCase(String businessId, String name);
}
