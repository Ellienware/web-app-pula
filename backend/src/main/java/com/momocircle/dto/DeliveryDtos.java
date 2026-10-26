package com.momocircle.dto;

import com.momocircle.domain.enums.DeliveryStatus;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.Instant;

public final class DeliveryDtos {

    private DeliveryDtos() {}

    public record RequestDeliveryRequest(
            @NotBlank String orderId
    ) {}

    public record DeliveryRequestResponse(
            String id,
            String orderId,
            String businessName,
            String driverId,
            String driverName,
            DeliveryStatus status,
            String pickupLocation,
            String dropoffLocation,
            BigDecimal estimatedFee,
            String currency,
            Instant acceptedAt,
            Instant pickedUpAt,
            Instant deliveredAt,
            Instant createdAt
    ) {}
}
