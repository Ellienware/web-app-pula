package com.momocircle.dto;

import com.momocircle.domain.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public final class ReceiptDtos {

    private ReceiptDtos() {}

    public record ReceiptResponse(
            String id,
            String receiptNumber,
            String payerName,
            String payeeLabel,
            String description,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            String providerReference,
            Instant createdAt
    ) {}
}
