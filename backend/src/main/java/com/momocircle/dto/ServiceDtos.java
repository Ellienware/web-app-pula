package com.momocircle.dto;

import com.momocircle.domain.enums.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ServiceDtos {

    private ServiceDtos() {}

    // ------------------------------------------------------------ catalogue / provider
    public record CategoryView(String id, String parentId, String name, String slug, int sortOrder, boolean active) {}
    public record CategoryRequest(String parentId, @NotBlank @Size(max = 80) String name, @Size(max = 80) String slug,
                                  Integer sortOrder, Boolean active) {}

    public record ProviderProfileRequest(
            @Size(max = 160) String headline, @Size(max = 2000) String about, @Size(max = 160) String contactEmail,
            @Size(max = 300) String availabilityNote, Boolean acceptingRequests,
            @Size(max = 30) List<String> serviceAreas,
            @Size(max = 12) List<String> portfolioUrls) {}

    public record ProviderProfileView(
            String businessId, String businessName, String contactPhone, String contactEmail, String headline,
            String about, String availabilityNote, boolean acceptingRequests, ProviderVerificationStatus verificationStatus,
            boolean suspended, List<String> serviceAreas, List<String> portfolioUrls,
            BigDecimal ratingAverage, int ratingCount, List<ListingView> listings) {}

    public record VerificationRequest(@NotNull ProviderVerificationStatus status) {}
    public record SuspendRequest(@NotNull Boolean suspended, @Size(max = 300) String reason) {}
    public record HideRequest(@NotNull Boolean hidden) {}

    // ------------------------------------------------------------ listings
    public record ListingRequest(
            @NotBlank String categoryId, @NotBlank @Size(max = 120) String name, @Size(max = 2000) String description,
            @NotNull ServicePricingModel pricingModel, @Positive BigDecimal price, @Positive Integer durationMinutes,
            @NotNull MaterialsResponsibility materialsResponsibility,
            @Size(max = 30) List<String> serviceAreas, @Size(max = 8) List<String> imageUrls,
            ServiceListingStatus status) {}

    public record ListingView(
            String id, String businessId, String businessName, String categoryId, String categoryName, String name,
            String description, ServicePricingModel pricingModel, BigDecimal price, String currency, Integer durationMinutes,
            MaterialsResponsibility materialsResponsibility, ServiceListingStatus status, boolean hiddenByAdmin,
            List<String> serviceAreas, List<String> imageUrls, BigDecimal ratingAverage, int ratingCount,
            ProviderVerificationStatus providerVerification, boolean acceptingRequests) {}

    // ------------------------------------------------------------ requests
    public record CreateServiceRequestRequest(
            @NotBlank String listingId, @NotBlank @Size(max = 2000) String description,
            @NotBlank @Size(max = 300) String location, LocalDate preferredDate, @Size(max = 40) String preferredTime,
            @Size(max = 1000) String requirements, MaterialsResponsibility materialsResponsibility,
            /** Submit straight away (default) or keep as a draft. */
            Boolean submit, String idempotencyKey) {}

    public record RequestView(
            String id, String listingId, String listingName, String businessId, String businessName, String customerId,
            String customerName, ServiceRequestStatus status, String description, String location, LocalDate preferredDate,
            String preferredTime, String requirements, MaterialsResponsibility materialsResponsibility,
            ServicePricingModel pricingModel, BigDecimal listingPrice, String currency,
            List<QuoteView> quotes, List<MaterialLineView> materials, String bookingId, Instant createdAt) {}

    // ------------------------------------------------------------ quotes
    public record SubmitQuoteRequest(
            /** HOURLY listings: the server computes labour = listing price x hours. */
            @Positive BigDecimal hours,
            /** FIXED: must equal the listing price (or be omitted). STARTING_FROM: at least the listing price. QUOTE_REQUIRED: required. */
            @PositiveOrZero BigDecimal labourAmount,
            @PositiveOrZero BigDecimal deliveryAmount, @PositiveOrZero BigDecimal otherAmount,
            @Size(max = 200) String otherDescription, @NotNull Instant validUntil, @Size(max = 1000) String notes) {}

    public record ClarificationRequest(@NotBlank @Size(max = 500) String message) {}

    public record QuoteView(
            String id, String requestId, int versionNo, ServiceQuoteStatus status, BigDecimal hours, BigDecimal labourAmount,
            BigDecimal materialsAmount, BigDecimal deliveryAmount, BigDecimal otherAmount, String otherDescription,
            BigDecimal total, String currency, Instant validUntil, String notes, String customerMessage, Instant createdAt) {}

    // ------------------------------------------------------------ materials
    public record AddMaterialRequest(@NotBlank String wholesaleProductId, @NotNull @Min(1) @Max(10000) Integer quantityPacks,
                                     @NotNull MaterialSupplier suppliedBy) {}
    public record LinkStockOrderRequest(@NotBlank String stockOrderId) {}
    public record MaterialLineView(
            String id, String wholesaleProductId, String productName, String supplierBusinessId, int quantityPacks,
            MaterialSupplier suppliedBy, BigDecimal unitPrice, BigDecimal lineTotal, String stockOrderId, String addedById) {}

    // ------------------------------------------------------------ bookings
    public record CancelRequest(@NotBlank @Size(max = 300) String reason) {}
    public record PayRequest(String saveToBuyRequestId) {}
    public record PaymentSessionView(String bookingId, String paymentReference, String checkoutUrl, PaymentStatus paymentStatus,
                                     BigDecimal amount, String currency) {}

    public record BookingView(
            String id, String requestId, String quoteId, String listingId, String listingName, String businessId,
            String businessName, String customerId, String customerName, ServiceBookingStatus status, BigDecimal total,
            String currency, LocalDate scheduledDate, String scheduledTime, Instant startedAt, Instant completedAt,
            Instant cancelledAt, String cancelReason,
            /** UNPAID / PROCESSING / PAID / FAILED, from the existing payment record. */
            String paymentStatus, String saveToBuyRequestId, boolean canReview, boolean reviewed,
            List<MaterialLineView> materials, Instant createdAt) {}

    // ------------------------------------------------------------ reviews
    public record ServiceReviewRequest(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 1000) String text,
                                       /** Also rate this specific listing (the provider is always rated). */
                                       Boolean rateListing) {}

    public record InsightsView(String summary, List<String> points, String notice) {}
}
