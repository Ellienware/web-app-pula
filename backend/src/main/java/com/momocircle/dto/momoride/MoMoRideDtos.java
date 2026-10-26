package com.momocircle.dto.momoride;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

public final class MoMoRideDtos { private MoMoRideDtos(){}

    public record RegisterDriverRequest(
            @NotBlank String registrationNumber,
            @Size(max=64) String makeModel,
            @NotBlank @Pattern(regexp="\\d{4,6}", message="PIN must be 4-6 digits") String pin
    ){}

    public record DriverResponse(String id,String userId,String driverName,String status,boolean hasPin,TaxiResponse taxi){}
    public record TaxiResponse(String id,String publicTaxiId,String registrationNumber,String paymentCode,String qrPayload,String makeModel,String status,String driverName){}

    public record PayTaxiRequest(@NotBlank String paymentCode,@NotNull @DecimalMin("0.01") BigDecimal amount,@NotBlank String currency,String idempotencyKey){}
    public record TaxiPaymentResponse(String paymentId,String taxiId,String publicTaxiId,String driverName,BigDecimal amount,String currency,String status,String providerReference){}

    public record CreateTaxiPaymentRequest(@NotNull @DecimalMin("0.01") BigDecimal amount,@NotBlank String currency,String passengerPhone){}
    public record TaxiPaymentRequestResponse(String id,String taxiId,String publicTaxiId,String driverName,BigDecimal amount,String currency,String status,String paymentId,Instant expiresAt){}
    public record FulfillPaymentRequestRequest(String idempotencyKey){}

    public record CreateRideRequest(@NotBlank String origin,@NotBlank String destination,@NotNull LocalDateTime departureTime,@Min(1) int availableSeats,@NotNull @DecimalMin("0") BigDecimal pricePerPassenger,@NotBlank String currency){}
    public record RideResponse(String id,String driverId,String driverName,String origin,String destination,LocalDateTime departureTime,int availableSeats,BigDecimal pricePerPassenger,String currency,String status){}
    public record BookRideRequest(String idempotencyKey){}
    public record RideBookingResponse(String id,String rideId,String passengerName,String status,String paymentId,String paymentStatus){}

    public record CreateRideRatingRequest(@Min(1) @Max(5) int rating, @Size(max=500) String comment){}
    public record RideRatingResponse(String id,String rideId,String raterName,int rating,String comment,Instant createdAt){}

    public record CreateReportRequest(
            @NotBlank String targetType,
            String rideId, String taxiId, String paymentId, String reportedUserId,
            @NotBlank @Size(max=1000) String reason
    ){}
    public record ReportResponse(String id,String targetType,String rideId,String taxiId,String paymentId,String status,Instant createdAt){}

    public record AdminReportResponse(String id,String reporterName,String targetType,String rideId,String taxiId,String paymentId,String reportedUserName,String reason,String status,Instant createdAt){}
}
