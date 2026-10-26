package com.momocircle.controller;

import com.momocircle.dto.FoodDtos.*;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.FoodMenuService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PULA Food — merchant menu management. All routes require authentication (they are not in
 * SecurityConfig's public list) and act only on the caller's own business. Public discovery
 * (search, restaurant pages, comparison) is a later step and uses separate read endpoints.
 */
@RestController
@RequestMapping("/api/market/food")
public class FoodMerchantController {

    private final FoodMenuService service;
    private final CurrentUserService currentUser;

    public FoodMerchantController(FoodMenuService service, CurrentUserService currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    // ---- settings (temporary closure, accept window)
    @GetMapping("/settings")
    public FoodSettingsResponse settings() { return service.getSettings(currentUser.require()); }

    @PatchMapping("/settings")
    public FoodSettingsResponse updateSettings(@Valid @RequestBody UpdateFoodSettingsRequest req) {
        return service.updateSettings(currentUser.require(), req);
    }

    // ---- categories
    @GetMapping("/categories")
    public List<MenuCategoryResponse> categories() { return service.listCategories(currentUser.require()); }

    @PostMapping("/categories")
    public ResponseEntity<MenuCategoryResponse> createCategory(@Valid @RequestBody CreateMenuCategoryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCategory(currentUser.require(), req));
    }

    @PatchMapping("/categories/{id}")
    public MenuCategoryResponse updateCategory(@PathVariable String id, @Valid @RequestBody UpdateMenuCategoryRequest req) {
        return service.updateCategory(currentUser.require(), id, req);
    }

    @DeleteMapping("/categories/{id}")
    public void deleteCategory(@PathVariable String id) { service.deleteCategory(currentUser.require(), id); }

    // ---- menu items (products): category + food details + modifier groups
    @PutMapping("/items/{productId}/category")
    public void assignCategory(@PathVariable String productId, @RequestBody AssignMenuCategoryRequest req) {
        service.assignCategory(currentUser.require(), productId, req.categoryId());
    }

    @PutMapping("/items/{productId}/details")
    public FoodDetailsResponse upsertDetails(@PathVariable String productId, @Valid @RequestBody UpsertFoodDetailsRequest req) {
        return service.upsertFoodDetails(currentUser.require(), productId, req);
    }

    @GetMapping("/items/{productId}/details")
    public FoodDetailsResponse details(@PathVariable String productId) {
        return service.getFoodDetails(currentUser.require(), productId);
    }

    @GetMapping("/items/{productId}/modifier-groups")
    public List<ModifierGroupResponse> itemGroups(@PathVariable String productId) {
        return service.listItemModifierGroups(currentUser.require(), productId);
    }

    @PutMapping("/items/{productId}/modifier-groups/{groupId}")
    public void attachGroup(@PathVariable String productId, @PathVariable String groupId) {
        service.attachModifierGroup(currentUser.require(), productId, groupId);
    }

    @DeleteMapping("/items/{productId}/modifier-groups/{groupId}")
    public void detachGroup(@PathVariable String productId, @PathVariable String groupId) {
        service.detachModifierGroup(currentUser.require(), productId, groupId);
    }

    // ---- branches
    @GetMapping("/branches")
    public List<BranchResponse> branches() { return service.listBranches(currentUser.require()); }

    @PostMapping("/branches")
    public ResponseEntity<BranchResponse> createBranch(@Valid @RequestBody CreateBranchRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBranch(currentUser.require(), req));
    }

    @PatchMapping("/branches/{id}")
    public BranchResponse updateBranch(@PathVariable String id, @Valid @RequestBody UpdateBranchRequest req) {
        return service.updateBranch(currentUser.require(), id, req);
    }

    @GetMapping("/branches/{id}/items")
    public List<BranchItemResponse> branchItems(@PathVariable String id) {
        return service.listBranchItems(currentUser.require(), id);
    }

    @PutMapping("/branches/{id}/items/{productId}")
    public BranchItemResponse setBranchItem(@PathVariable String id, @PathVariable String productId,
                                            @Valid @RequestBody SetBranchItemRequest req) {
        return service.setBranchItem(currentUser.require(), id, productId, req);
    }

    // ---- modifier groups
    @GetMapping("/modifier-groups")
    public List<ModifierGroupResponse> groups() { return service.listModifierGroups(currentUser.require()); }

    @PostMapping("/modifier-groups")
    public ResponseEntity<ModifierGroupResponse> createGroup(@Valid @RequestBody CreateModifierGroupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createModifierGroup(currentUser.require(), req));
    }

    @PatchMapping("/modifier-groups/{id}")
    public ModifierGroupResponse updateGroup(@PathVariable String id, @Valid @RequestBody UpdateModifierGroupRequest req) {
        return service.updateModifierGroup(currentUser.require(), id, req);
    }

    @PostMapping("/modifier-groups/{id}/modifiers")
    public ResponseEntity<ModifierResponse> addModifier(@PathVariable String id, @Valid @RequestBody CreateModifierRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addModifier(currentUser.require(), id, req));
    }

    @PatchMapping("/modifiers/{id}")
    public ModifierResponse updateModifier(@PathVariable String id, @Valid @RequestBody UpdateModifierRequest req) {
        return service.updateModifier(currentUser.require(), id, req);
    }
}
