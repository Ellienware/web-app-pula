package com.momocircle.repository;

import com.momocircle.domain.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, String> {
    boolean existsByDedupeKey(String dedupeKey);
}
