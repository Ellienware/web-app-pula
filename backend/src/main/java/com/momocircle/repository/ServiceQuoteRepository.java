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

public interface ServiceQuoteRepository extends JpaRepository<ServiceQuote, String> {
    List<ServiceQuote> findByRequestIdOrderByVersionNoDesc(String requestId);
    Optional<ServiceQuote> findByIdAndRequestId(String id, String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from ServiceQuote q where q.id = :id")
    Optional<ServiceQuote> findForUpdate(@Param("id") String id);

    @Query("select q.id from ServiceQuote q where q.status in :statuses and q.validUntil < :now")
    List<String> findIdsPastValidity(@Param("statuses") Collection<ServiceQuoteStatus> statuses, @Param("now") Instant now);
}
