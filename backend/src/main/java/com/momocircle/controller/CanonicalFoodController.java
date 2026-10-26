package com.momocircle.controller;

import com.momocircle.dto.FoodDtos.CanonicalFoodResponse;
import com.momocircle.dto.FoodDtos.CanonicalSuggestionResponse;
import com.momocircle.dto.FoodDtos.UpsertCanonicalFoodRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.CanonicalFoodService;
import com.momocircle.service.FoodSuggestionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PULA Food — canonical food meaning. Editing attributes/aliases is ADMIN-only because it changes
 * what PULA calls an "exact match" for every customer; reading and merchant suggestions are open
 * to authenticated users (these are not in SecurityConfig's public list).
 */
@RestController
public class CanonicalFoodController {

    private final CanonicalFoodService service;
    private final FoodSuggestionService suggestions;
    private final CurrentUserService currentUser;

    public CanonicalFoodController(CanonicalFoodService service, FoodSuggestionService suggestions,
                                   CurrentUserService currentUser) {
        this.service = service;
        this.suggestions = suggestions;
        this.currentUser = currentUser;
    }

    @PutMapping("/api/canonical-products/{id}/food")
    public CanonicalFoodResponse upsert(@PathVariable String id, @Valid @RequestBody UpsertCanonicalFoodRequest req) {
        currentUser.requireAdmin();
        return service.upsert(id, req);
    }

    @GetMapping("/api/canonical-products/{id}/food")
    public CanonicalFoodResponse get(@PathVariable String id) {
        currentUser.require();
        return service.get(id);
    }

    /** Merchant: canonical foods that resemble one of my menu items. Suggestions only; nothing is linked. */
    @GetMapping("/api/market/food/items/{productId}/canonical-suggestions")
    public List<CanonicalSuggestionResponse> suggest(@PathVariable String productId) {
        return suggestions.suggest(currentUser.require(), productId);
    }
}
