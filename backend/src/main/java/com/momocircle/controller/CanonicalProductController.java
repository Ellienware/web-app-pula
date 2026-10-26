package com.momocircle.controller;

import com.momocircle.dto.MarketDtos.CanonicalProductResponse;
import com.momocircle.dto.MarketDtos.CreateCanonicalProductRequest;
import com.momocircle.dto.MarketDtos.ProductPriceComparisonResponse;
import com.momocircle.service.CanonicalProductService;
import com.momocircle.service.PriceComparisonService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CanonicalProductController {

    private final CanonicalProductService canonicalProductService;
    private final PriceComparisonService priceComparisonService;

    public CanonicalProductController(CanonicalProductService canonicalProductService,
                                       PriceComparisonService priceComparisonService) {
        this.canonicalProductService = canonicalProductService;
        this.priceComparisonService = priceComparisonService;
    }

    @PostMapping("/api/canonical-products")
    public ResponseEntity<CanonicalProductResponse> create(@Valid @RequestBody CreateCanonicalProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(canonicalProductService.create(req));
    }

    @GetMapping("/api/canonical-products")
    public Page<CanonicalProductResponse> search(@RequestParam(required = false) String q,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return canonicalProductService.search(q, PageRequest.of(page, Math.min(size, 50)));
    }

    @GetMapping("/api/canonical-products/{id}")
    public CanonicalProductResponse get(@PathVariable String id) {
        return canonicalProductService.get(id);
    }

    @GetMapping("/api/canonical-products/{id}/comparison")
    public ProductPriceComparisonResponse compare(@PathVariable String id) {
        return priceComparisonService.compare(id);
    }
}
