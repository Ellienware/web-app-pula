package com.momocircle.controller;

import com.momocircle.dto.MarketDtos.BusinessProfileCompleteness;
import com.momocircle.dto.MarketDtos.BusinessResponse;
import com.momocircle.dto.MarketDtos.CreateBusinessRequest;
import com.momocircle.dto.MarketDtos.PublicBusinessResponse;
import com.momocircle.dto.MarketDtos.UpdateBusinessRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.BusinessReadinessService;
import com.momocircle.service.BusinessService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusinessController {

    private final BusinessService businessService;
    private final BusinessReadinessService readinessService;
    private final CurrentUserService currentUser;

    public BusinessController(BusinessService businessService, BusinessReadinessService readinessService,
                               CurrentUserService currentUser) {
        this.businessService = businessService;
        this.readinessService = readinessService;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/businesses")
    public ResponseEntity<BusinessResponse> create(@Valid @RequestBody CreateBusinessRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(businessService.create(currentUser.require(), req));
    }

    @PutMapping("/api/businesses/me")
    public BusinessResponse updateMine(@RequestBody UpdateBusinessRequest req) {
        return businessService.update(currentUser.require(), req);
    }

    @GetMapping("/api/businesses/me")
    public BusinessResponse mine() {
        return businessService.myBusiness(currentUser.require());
    }

    /** Brief §8: a plain, real-data checklist for the owner's own business. Never a credit score. */
    @GetMapping("/api/businesses/me/readiness")
    public BusinessProfileCompleteness myReadiness() {
        return readinessService.computeFor(businessService.requireOwn(currentUser.require()));
    }

    /**
     * Public storefront read. Anonymous and other signed-in callers receive {@link PublicBusinessResponse} only; the owner and
     * admins receive the full {@link BusinessResponse}. Inactive businesses are 404 to everyone else.
     */
    @GetMapping("/api/businesses/{businessId}")
    public Object get(@PathVariable String businessId) {
        return businessService.getForViewer(businessId, currentUser.optional().orElse(null));
    }

    @GetMapping("/api/businesses")
    public Page<PublicBusinessResponse> browse(@RequestParam(required = false) String category,
                                          @RequestParam(required = false) com.momocircle.domain.enums.BusinessType businessType,
                                          @RequestParam(required = false) com.momocircle.domain.enums.BusinessCapability capability,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return businessService.browse(category, businessType, capability, pageable);
    }
}
