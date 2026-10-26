package com.momocircle.dto;

import com.momocircle.domain.enums.BusinessType;
import com.momocircle.domain.enums.FulfilmentOption;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * PULA Food — public discovery, search and comparison. These responses give customers comparable
 * facts; they never rank a "winner". Customer coordinates are used for distance only and are never
 * echoed back or stored.
 */
public final class FoodDiscoveryDtos {

    private FoodDiscoveryDtos() {}

    /** Customer-chosen ordering. RELEVANCE = closeness of the meal match, then distance (if known), then name. */
    public enum FoodSort { RELEVANCE, DISTANCE, PRICE, RATING, NAME }

    public enum FulfilmentFilter { COLLECTION, DELIVERY }

    public record FoodSearchCriteria(
            String q, BigDecimal maxPrice, BigDecimal minRating, FulfilmentFilter fulfilment,
            BigDecimal maxDeliveryFee, Integer maxPrepMinutes, String area, String foodType,
            BusinessType businessType, Double lat, Double lng, Double maxDistanceKm,
            boolean includeUnavailable, FoodSort sort, int page, int size) {

        public boolean hasLocation() { return lat != null && lng != null; }
    }

    /** One orderable meal at one place (a branch, or the single location of a business). */
    public record FoodOfferResponse(
            String productId, String name, String description, String imageUrl,
            String businessId, String businessName, BusinessType businessType,
            String branchId, String branchName, String area, String address,
            BigDecimal price, String currency,
            String foodType, String protein, String starch, String portionSize, String portionDescription,
            Set<String> sides, Set<String> allergens, Set<String> dietaryTags, boolean hasExtras,
            BigDecimal itemRating, int itemRatingCount, BigDecimal restaurantRating, int restaurantRatingCount,
            boolean collectionAvailable, boolean deliveryAvailable, BigDecimal deliveryFee, Integer prepMinutes,
            Double distanceKm, boolean available, String unavailableReason, List<String> openingHours,
            String canonicalProductId, String matchLevel, List<String> matchReasons, boolean matchUncertain) {}

    public record FoodSearchResponse(
            List<FoodOfferResponse> offers, int total, int page, int size,
            List<String> interpretedAs, int excludedForUnknownDistance) {}

    public record FoodComparisonResponse(
            String referenceName, String referenceCanonicalId, String referenceProductId,
            List<FoodOfferResponse> exactMatches, List<FoodOfferResponse> similarMeals,
            List<FoodOfferResponse> alternatives, int excludedForUnknownDistance, String note) {}

    // ---- restaurants ----

    public record RestaurantQuery(
            String q, BusinessType businessType, String area, FulfilmentFilter fulfilment, BigDecimal minRating,
            Double lat, Double lng, Double maxDistanceKm, FoodSort sort, int page, int size) {}

    public record RestaurantSummary(
            String id, String name, BusinessType businessType, String description, String logoUrl, String area,
            int branchCount, BigDecimal rating, int ratingCount, boolean collectionAvailable,
            boolean deliveryAvailable, BigDecimal deliveryFeeFrom, BigDecimal minimumOrder, Integer prepMinutesFrom,
            Double nearestDistanceKm, boolean open, String closedReason) {}

    public record RestaurantListResponse(List<RestaurantSummary> restaurants, int total, int page, int size,
                                         int excludedForUnknownDistance) {}

    public record BranchView(
            String id, String name, String address, String area, java.math.BigDecimal latitude,
            java.math.BigDecimal longitude, List<String> operatingHours, Set<FulfilmentOption> fulfilmentOptions,
            Set<String> deliveryAreas, BigDecimal deliveryFee, BigDecimal minimumOrder, Integer prepMinutes,
            boolean open, String closedReason, Double distanceKm) {}

    public record RestaurantDetail(
            String id, String name, BusinessType businessType, String description, String logoUrl, String location,
            String contactPhone, String contactEmail, List<String> operatingHours, Set<FulfilmentOption> fulfilmentOptions,
            BigDecimal deliveryFee, BigDecimal minimumOrder, BigDecimal rating, int ratingCount,
            boolean open, String closedReason, List<BranchView> branches) {}

    // ---- menu ----

    public record ModifierView(String id, String name, BigDecimal priceDelta, boolean available) {}

    public record ModifierGroupView(String id, String name, int minSelect, int maxSelect, boolean required,
                                    List<ModifierView> modifiers) {}

    public record MenuItemView(
            String id, String name, String description, String imageUrl, BigDecimal price, String currency,
            String foodType, String protein, String starch, String portionSize, String portionDescription,
            Set<String> sides, Set<String> allergens, Set<String> dietaryTags, String spiceLevel, Integer prepMinutes,
            BigDecimal rating, int ratingCount, boolean available, String unavailableReason,
            String canonicalProductId, List<ModifierGroupView> modifierGroups) {}

    public record MenuCategoryView(String id, String name, List<MenuItemView> items) {}

    public record MenuResponse(String businessId, String businessName, String branchId, String branchName,
                               List<MenuCategoryView> categories) {}
}
