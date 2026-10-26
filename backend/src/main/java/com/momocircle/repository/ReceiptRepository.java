package com.momocircle.repository;

import com.momocircle.domain.Receipt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReceiptRepository extends JpaRepository<Receipt, String> {
    Optional<Receipt> findByPaymentId(String paymentId);
    Page<Receipt> findByPayerIdOrderByCreatedAtDesc(String payerId, Pageable pageable);
}
