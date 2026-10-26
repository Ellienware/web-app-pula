package com.momocircle.dto;

import com.momocircle.domain.enums.FulfilmentOption;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** PULA Food — merchant-side menu, branch and modifier management DTOs. */
public final class FoodDtos {

    private FoodDtos() {}

    // ---- Menu categories ----
    public record CreateMenuCategoryRequest(@NotBlank @Size(max = 100) String name, Integer sortOrder) {}
    public record UpdateMenuCategoryRequest(@Size(max = 100) String name, Integer sortOrder) {}
    public record MenuCategoryResponse(String id, String businessId, String name, int sortOrder) {}
    /** Sets (or clears, when categoryId is null) a menu item's category. */
    public record AssignMenuCategoryRequest(String categoryId) {}

    // ---- Food details (all optional) ----
    public record UpsertFoodDetailsRequest(
            @Size(max = 64) String foodType,
            @Size(max = 64) String protein,
            @Size(max = 64) String starch,
            @Size(max = 32) String portionSize,
            @Size(max = 64) String servingSize,
            @Size(max = 32) String spiceLevel,
            @Size(max = 255) String portionDescription,
            @Min(0) @Max(480) Integer prepTimeMinutes,
            @Min(0) Integer dailyQuantity,
            Set<String> sides,
            Set<String> dietaryTags,
            Set<String> allergens
    ) {}
    public record FoodDetailsResponse(
            String productId, String foodType, String protein, String starch, String portionSize,
            String servingSize, String spiceLevel, String portionDescription, Integer prepTimeMinutes,
            Integer dailyQuantity, Set<String> sides, Set<String> dietaryTags, Set<String> allergens
    ) {}

    // ---- Branches ----
    public record CreateBranchRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 500) String address,
            @Size(max = 255) String area,
            BigDecimal latitude,
            BigDecimal longitude,
            @Min(0) @Max(480) Integer prepTimeMinutes,
            BigDecimal deliveryFee,
            BigDecimal minimumOrderAmount,
            List<String> operatingHours,
            Set<FulfilmentOption> fulfilmentOptions,
            Set<String> deliveryAreas
    ) {}
    /** Null fields are left unchanged. */
    public record UpdateBranchRequest(
            @Size(max = 255) String name,
            @Size(max = 500) String address,
            @Size(max = 255) String area,
            BigDecimal latitude,
            BigDecimal longitude,
            @Min(0) @Max(480) Integer prepTimeMinutes,
            BigDecimal deliveryFee,
            BigDecimal minimumOrderAmount,
            List<String> operatingHours,
            Set<FulfilmentOption> fulfilmentOptions,
            Set<String> deliveryAreas,
            Boolean active,
            Boolean temporarilyClosed
    ) {}
    public record BranchResponse(
            String id, String businessId, String name, String address, String area,
            BigDecimal latitude, BigDecimal longitude, boolean active, boolean temporarilyClosed,
            Integer prepTimeMinutes, BigDecimal deliveryFee, BigDecimal minimumOrderAmount,
            List<String> operatingHours, Set<FulfilmentOption> fulfilmentOptions, Set<String> deliveryAreas
    ) {}

    // ---- Per-branch item availability ----
    public record SetBranchItemRequest(@NotNull Boolean available, BigDecimal priceOverride) {}
    public record BranchItemResponse(String branchId, String productId, boolean available, BigDecimal priceOverride) {}

    // ---- Modifiers ----
    public record CreateModifierGroupRequest(
            @NotBlank @Size(max = 100) String name,
            @Min(0) Integer minSelect,
            @Min(1) Integer maxSelect
    ) {}
    public record UpdateModifierGroupRequest(
            @Size(max = 100) String name,
            @Min(0) Integer minSelect,
            @Min(1) Integer maxSelect
    ) {}
    public record CreateModifierRequest(@NotBlank @Size(max = 100) String name, BigDecimal priceDelta) {}
    public record UpdateModifierRequest(@Size(max = 100) String name, BigDecimal priceDelta, Boolean available) {}
    public record ModifierResponse(String id, String groupId, String name, BigDecimal priceDelta, boolean available) {}
    public record ModifierGroupResponse(
            String id, String businessId, String name, int minSelect, int maxSelect, boolean required,
            List<ModifierResponse> modifiers
    ) {}

    // ---- Business-level food settings ----
    /** Null fields are left unchanged. acceptTimeoutMinutes is capped by app.food.max-accept-timeout-minutes. */
    public record UpdateFoodSettingsRequest(Boolean temporarilyClosed, @Min(1) Integer acceptTimeoutMinutes) {}
    public record FoodSettingsResponse(boolean temporarilyClosed, Integer acceptTimeoutMinutes,
                                       int effectiveAcceptTimeoutMinutes, int maxAcceptTimeoutMinutes) {}

    // ---- Canonical food (admin) ----
    public record UpsertCanonicalFoodRequest(
            @Size(max = 64) String foodType, @Size(max = 64) String protein, @Size(max = 64) String starch,
            @Size(max = 32) String portionClass, Set<String> sides, Set<String> aliases
    ) {}
    public record CanonicalFoodResponse(
            String canonicalProductId, String name, String foodType, String protein, String starch,
            String portionClass, Set<String> sides, Set<String> aliases
    ) {}
    public record CanonicalSuggestionResponse(String canonicalProductId, String name, double score) {}
}
