package com.momocircle.controller;

import com.momocircle.dto.MarketDtos.CreateProductRequest;
import com.momocircle.dto.MarketDtos.ProductResponse;
import com.momocircle.dto.MarketDtos.UpdateProductRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {

    private final ProductService productService;
    private final CurrentUserService currentUser;

    public ProductController(ProductService productService, CurrentUserService currentUser) {
        this.productService = productService;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/products")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(currentUser.require(), req));
    }

    @PutMapping("/api/products/{productId}")
    public ProductResponse update(@PathVariable String productId, @RequestBody UpdateProductRequest req) {
        return productService.update(currentUser.require(), productId, req);
    }

    @PutMapping("/api/products/{productId}/canonical-link")
    public ProductResponse linkCanonicalProduct(@PathVariable String productId,
                                                 @RequestBody com.momocircle.dto.MarketDtos.LinkCanonicalProductRequest req) {
        return productService.linkCanonicalProduct(currentUser.require(), productId, req.canonicalProductId(), req.classification());
    }

    /** Read-only discovery aid for a seller deciding what to link their product to — see ProductService's own javadoc. */
    @GetMapping("/api/products/{productId}/canonical-suggestions")
    public java.util.List<com.momocircle.dto.MarketDtos.CanonicalProductSuggestionResponse> suggestCanonicalProducts(
            @PathVariable String productId) {
        return productService.suggestCanonicalProducts(currentUser.require(), productId).stream()
                .map(s -> new com.momocircle.dto.MarketDtos.CanonicalProductSuggestionResponse(
                        toCanonicalProductResponse(s.canonicalProduct()), s.score()))
                .toList();
    }

    private com.momocircle.dto.MarketDtos.CanonicalProductResponse toCanonicalProductResponse(
            com.momocircle.domain.CanonicalProduct c) {
        return new com.momocircle.dto.MarketDtos.CanonicalProductResponse(
                c.getId(), c.getName(), c.getBrand(), c.getCategory(), c.getSubcategory(),
                c.getSize(), c.getUnit(), c.getBarcode(), c.getCreatedAt());
    }

    @DeleteMapping("/api/products/{productId}")
    public void archive(@PathVariable String productId) {
        productService.archive(currentUser.require(), productId);
    }

    @GetMapping("/api/market/products/{productId}")
    public ProductResponse getConsumerProduct(@PathVariable String productId) {
        return productService.getConsumerProduct(productId);
    }

    @GetMapping("/api/products/{productId}")
    public ProductResponse get(@PathVariable String productId) {
        return productService.get(productId);
    }

    @GetMapping("/api/businesses/{businessId}/products")
    public Page<ProductResponse> forBusiness(@PathVariable String businessId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return productService.listForBusiness(businessId, PageRequest.of(page, Math.min(size, 50)));
    }

    /**
     * General browse — backward-compatible with existing calls (no capability filter applied).
     * The consumer-facing PULA Market — Shop should use {@code /api/market/products} instead.
     */
    @GetMapping("/api/products")
    public Page<ProductResponse> browse(@RequestParam(required = false) String category,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) java.math.BigDecimal maxPrice,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return (q == null || q.isBlank())
                ? productService.browse(category, maxPrice, pageable)
                : productService.search(q, maxPrice, pageable);
    }

    /**
     * Phase 5 — consumer-facing PULA Market — Shop product browse. Scoped to businesses that
     * carry CAN_SELL_TO_CONSUMERS so wholesale-only stock never appears to consumers. Kept at a
     * separate path rather than bolting a capability param onto /api/products to avoid changing
     * the contract of existing calls (basket comparison, seller dashboard, price comparison —
     * all of which rightly see all products).
     */
    @GetMapping("/api/market/products")
    public Page<ProductResponse> browseConsumer(@RequestParam(required = false) String category,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(required = false) java.math.BigDecimal maxPrice,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return (q == null || q.isBlank())
            ? productService.browseConsumer(category, maxPrice, pageable)
            : productService.searchConsumer(q, maxPrice, pageable);
    }

    /**
     * Phase 5 — top product categories from consumer sellers, for the category-pill row on the
     * market home. Publicly accessible (no auth) — used on the unauthed landing page too.
     * Capped at 20 server-side; clients can request fewer with the {@code limit} param.
     */
    @GetMapping("/api/market/categories")
    public java.util.List<String> popularCategories(@RequestParam(defaultValue = "12") int limit) {
        return productService.popularCategories(limit);
    }
}
