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

public interface ServiceCategoryRepository extends JpaRepository<ServiceCategory, String> {
    Optional<ServiceCategory> findBySlug(String slug);
    List<ServiceCategory> findAllByOrderBySortOrderAscNameAsc();
    List<ServiceCategory> findByActiveTrueOrderBySortOrderAscNameAsc();
}
