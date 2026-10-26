package com.momocircle.controller;

import com.momocircle.dto.ServiceDtos.*;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.ServiceBookingService;
import com.momocircle.service.ServiceCatalogService;
import com.momocircle.service.ServiceProviderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin moderation. Every method requires an ADMIN. Admins manage categories, verification, suspension and listing
 * visibility, and can SEE bookings. There is deliberately no endpoint that changes a booking, a quote or any money.
 */
@RestController
@RequestMapping("/api/admin/services")
public class AdminServicesController {

    private final ServiceCatalogService catalog;
    private final ServiceProviderService providers;
    private final ServiceBookingService bookings;
    private final CurrentUserService currentUser;

    public AdminServicesController(ServiceCatalogService catalog, ServiceProviderService providers, ServiceBookingService bookings,
                                   CurrentUserService currentUser) {
        this.catalog = catalog;
        this.providers = providers;
        this.bookings = bookings;
        this.currentUser = currentUser;
    }

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return catalog.listAll(currentUser.requireAdmin());
    }

    @PostMapping("/categories")
    public ResponseEntity<CategoryView> createCategory(@Valid @RequestBody CategoryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.create(currentUser.requireAdmin(), req));
    }

    @PutMapping("/categories/{id}")
    public CategoryView updateCategory(@PathVariable String id, @Valid @RequestBody CategoryRequest req) {
        return catalog.update(currentUser.requireAdmin(), id, req);
    }

    @GetMapping("/providers/pending")
    public List<ProviderProfileView> pending(@RequestParam(defaultValue = "0") int page) {
        currentUser.requireAdmin();
        return providers.pendingVerification(PageRequest.of(Math.max(page, 0), 50));
    }

    @PutMapping("/providers/{businessId}/verification")
    public ProviderProfileView verify(@PathVariable String businessId, @Valid @RequestBody VerificationRequest req) {
        return providers.setVerification(currentUser.requireAdmin(), businessId, req.status());
    }

    @PutMapping("/providers/{businessId}/suspension")
    public ProviderProfileView suspend(@PathVariable String businessId, @Valid @RequestBody SuspendRequest req) {
        return providers.setSuspended(currentUser.requireAdmin(), businessId, req.suspended(), req.reason());
    }

    @PutMapping("/listings/{id}/visibility")
    public ListingView hide(@PathVariable String id, @Valid @RequestBody HideRequest req) {
        return providers.setHidden(currentUser.requireAdmin(), id, req.hidden());
    }

    @GetMapping("/bookings")
    public List<BookingView> bookings(@RequestParam(defaultValue = "0") int page) {
        return bookings.adminList(currentUser.requireAdmin(), PageRequest.of(Math.max(page, 0), 50));
    }
}
