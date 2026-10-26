package com.momocircle.repository;

import com.momocircle.domain.BusinessDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessDocumentRepository extends JpaRepository<BusinessDocument, String> {
    List<BusinessDocument> findByBusinessIdOrderByCreatedAtDesc(String businessId);
    long countByBusinessId(String businessId);
    Optional<BusinessDocument> findByIdAndBusinessId(String id, String businessId);
}
