package com.momocircle.dto;

import com.momocircle.domain.enums.ReviewTargetType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public final class ReviewDtos {

    private ReviewDtos() {}

    public record CreateReviewRequest(
            @NotNull ReviewTargetType targetType,
            @NotBlank String targetId,
            @NotBlank String orderId,
            @Min(1) @Max(5) int rating,
            String text,
            /** PULA Food only (rejected on other orders). All optional, 1-5. */
            @Min(1) @Max(5) Integer taste,
            @Min(1) @Max(5) Integer portionSize,
            @Min(1) @Max(5) Integer valueForMoney,
            @Min(1) @Max(5) Integer foodQuality,
            @Min(1) @Max(5) Integer packaging,
            /** Only for orders delivered by PULA Ride or a restaurant rider. Not part of the food rating. */
            @Min(1) @Max(5) Integer deliveryExperience
    ) {
        public CreateReviewRequest(ReviewTargetType targetType, String targetId, String orderId, int rating, String text) {
            this(targetType, targetId, orderId, rating, text, null, null, null, null, null, null);
        }
    }

    public record ReviewResponse(
            String id,
            String authorName,
            ReviewTargetType targetType,
            String targetId,
            int rating,
            String text,
            Instant createdAt,
            Integer taste, Integer portionSize, Integer valueForMoney, Integer foodQuality,
            Integer packaging, Integer deliveryExperience,
            /** "Verified PULA order": the author completed an order with this business. */
            boolean verifiedOrder,
            /** Always "PULA". External reviews are never mixed into PULA scores. */
            String source
    ) {
        public ReviewResponse(String id, String authorName, ReviewTargetType targetType, String targetId, int rating,
                              String text, Instant createdAt) {
            this(id, authorName, targetType, targetId, rating, text, createdAt, null, null, null, null, null, null,
                    true, "PULA");
        }
    }

    /** Average of one optional sub-rating over the reviews that gave it. */
    public record DimensionSummary(String dimension, java.math.BigDecimal average, long count) {}

    public record ReviewSummaryResponse(
            ReviewTargetType targetType, String targetId,
            java.math.BigDecimal overallAverage, long overallCount,
            /** taste, portion size, value for money, food quality, packaging (not delivery). */
            java.util.List<DimensionSummary> foodDimensions,
            /** Reported separately: it describes the delivery, not the food. */
            DimensionSummary deliveryExperience,
            long verifiedOrderCount, String source
    ) {}
}
