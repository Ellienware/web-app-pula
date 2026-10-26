package com.momocircle.controller;

import com.momocircle.domain.enums.BusinessType;
import com.momocircle.dto.FoodDiscoveryDtos.*;
import com.momocircle.service.FoodDiscoveryService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PULA Food — public, read-only discovery (see SecurityConfig). Optional lat/lng are used only to
 * compute distance and are not stored or returned. Responses give comparable facts and never
 * declare a winner.
 */
@RestController
@RequestMapping("/api/market")
public class FoodDiscoveryController {

    private final FoodDiscoveryService service;

    public FoodDiscoveryController(FoodDiscoveryService service) {
        this.service = service;
    }

    @GetMapping("/food/search")
    public FoodSearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(required = false) FulfilmentFilter fulfilment,
            @RequestParam(required = false) BigDecimal maxDeliveryFee,
            @RequestParam(required = false) Integer maxPrepMinutes,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String foodType,
            @RequestParam(required = false) BusinessType businessType,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double maxDistanceKm,
            @RequestParam(defaultValue = "false") boolean includeUnavailable,
            @RequestParam(required = false) FoodSort sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.search(criteria(q, maxPrice, minRating, fulfilment, maxDeliveryFee, maxPrepMinutes, area, foodType,
                businessType, lat, lng, maxDistanceKm, includeUnavailable, sort, page, size));
    }

    @GetMapping("/food/canonical/{canonicalId}/compare")
    public FoodComparisonResponse compareCanonical(
            @PathVariable String canonicalId,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(required = false) FulfilmentFilter fulfilment,
            @RequestParam(required = false) BigDecimal maxDeliveryFee,
            @RequestParam(required = false) Integer maxPrepMinutes,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double maxDistanceKm,
            @RequestParam(defaultValue = "false") boolean includeUnavailable,
            @RequestParam(required = false) FoodSort sort) {
        return service.compareCanonical(canonicalId, criteria(null, maxPrice, minRating, fulfilment, maxDeliveryFee,
                maxPrepMinutes, area, null, null, lat, lng, maxDistanceKm, includeUnavailable, sort, 0, MAX_COMPARE));
    }

    @GetMapping("/food/items/{productId}/compare")
    public FoodComparisonResponse compareItem(
            @PathVariable String productId,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(required = false) FulfilmentFilter fulfilment,
            @RequestParam(required = false) BigDecimal maxDeliveryFee,
            @RequestParam(required = false) Integer maxPrepMinutes,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double maxDistanceKm,
            @RequestParam(defaultValue = "false") boolean includeUnavailable,
            @RequestParam(required = false) FoodSort sort) {
        return service.compareItem(productId, criteria(null, maxPrice, minRating, fulfilment, maxDeliveryFee,
                maxPrepMinutes, area, null, null, lat, lng, maxDistanceKm, includeUnavailable, sort, 0, MAX_COMPARE));
    }

    @GetMapping("/restaurants")
    public RestaurantListResponse restaurants(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BusinessType businessType,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) FulfilmentFilter fulfilment,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double maxDistanceKm,
            @RequestParam(required = false) FoodSort sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.restaurants(new RestaurantQuery(q, businessType, area, fulfilment, minRating, lat, lng,
                maxDistanceKm, sort, page, size));
    }

    @GetMapping("/restaurants/{businessId}")
    public RestaurantDetail restaurant(@PathVariable String businessId,
                                       @RequestParam(required = false) Double lat,
                                       @RequestParam(required = false) Double lng) {
        return service.restaurant(businessId, lat, lng);
    }

    @GetMapping("/restaurants/{businessId}/menu")
    public MenuResponse menu(@PathVariable String businessId, @RequestParam(required = false) String branchId) {
        return service.menu(businessId, branchId);
    }

    private static final int MAX_COMPARE = 50;

    private static FoodSearchCriteria criteria(String q, BigDecimal maxPrice, BigDecimal minRating, FulfilmentFilter f,
                                               BigDecimal maxFee, Integer maxPrep, String area, String foodType,
                                               BusinessType type, Double lat, Double lng, Double maxKm,
                                               boolean includeUnavailable, FoodSort sort, int page, int size) {
        return new FoodSearchCriteria(q, maxPrice, minRating, f, maxFee, maxPrep, area, foodType, type, lat, lng,
                maxKm, includeUnavailable, sort, page, size);
    }
}
