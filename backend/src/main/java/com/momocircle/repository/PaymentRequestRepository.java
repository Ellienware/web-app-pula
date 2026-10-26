package com.momocircle.repository;

import com.momocircle.domain.PaymentRequest;
import com.momocircle.domain.enums.PaymentRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, String> {
    List<PaymentRequest> findByCircleIdOrderByCreatedAtDesc(String circleId);

    /** Open requests whose due date has passed — candidates for auto-expiry. */
    List<PaymentRequest> findByStatusAndDueDateBefore(PaymentRequestStatus status, LocalDate date);
}
