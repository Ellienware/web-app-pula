package com.momocircle.controller;

import com.momocircle.domain.enums.StockFulfilmentOption;
import com.momocircle.dto.BulkDtos.*;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.BulkPurchaseService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Bulk purchasing API. Every endpoint is authenticated (SecurityConfig's anyRequest().authenticated()); every ownership
 * decision is made in the service from the caller's own business, never from an id in the body.
 */
@RestController
public class BulkPurchaseController {

    private final BulkPurchaseService service;
    private final CurrentUserService currentUser;

    public BulkPurchaseController(BulkPurchaseService service, CurrentUserService currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    private static Pageable page(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50)));
    }

    // ---- Supplier volume tiers ----

    @GetMapping("/api/stock/products/{productId}/volume-tiers")
    public List<TierView> tiers(@PathVariable String productId) {
        currentUser.require();
        return service.tiers(productId);
    }

    @PutMapping("/api/stock/products/{productId}/volume-tiers")
    public List<TierView> setTiers(@PathVariable String productId, @Valid @RequestBody SetTiersRequest req) {
        return service.setTiers(currentUser.require(), productId, req);
    }

    // ---- Groups: discovery and organiser ----

    @PostMapping("/api/bulk/groups")
    public ResponseEntity<GroupDetail> create(@Valid @RequestBody CreateGroupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createGroup(currentUser.require(), req));
    }

    @GetMapping("/api/bulk/groups")
    public Page<GroupSummary> open(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.listOpen(currentUser.require(), page(page, size));
    }

    @GetMapping("/api/bulk/groups/mine")
    public Page<GroupSummary> mine(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.listMine(currentUser.require(), page(page, size));
    }

    @GetMapping("/api/bulk/groups/supplier")
    public Page<GroupSummary> forSupplier(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.listForSupplier(currentUser.require(), page(page, size));
    }

    @GetMapping("/api/bulk/groups/{id}")
    public GroupDetail get(@PathVariable String id) {
        return service.get(currentUser.require(), id);
    }

    @PatchMapping("/api/bulk/groups/{id}")
    public GroupDetail update(@PathVariable String id, @Valid @RequestBody UpdateGroupRequest req) {
        return service.updateGroup(currentUser.require(), id, req);
    }

    @PostMapping("/api/bulk/groups/{id}/open")
    public GroupDetail openGroup(@PathVariable String id) {
        return service.open(currentUser.require(), id);
    }

    @PostMapping("/api/bulk/groups/{id}/start-confirmation")
    public GroupDetail startConfirmation(@PathVariable String id) {
        return service.startConfirmation(currentUser.require(), id);
    }

    @PostMapping("/api/bulk/groups/{id}/finalise")
    public FinaliseOutcome finalise(@PathVariable String id, @Valid @RequestBody FinaliseRequest req) {
        return service.finalise(currentUser.require(), id, req);
    }

    @PostMapping("/api/bulk/groups/{id}/place-order")
    public PlaceOrderOutcome placeOrder(@PathVariable String id, @Valid @RequestBody PlaceOrderRequest req) {
        return service.placeOrder(currentUser.require(), id, req);
    }

    @PostMapping("/api/bulk/groups/{id}/cancel")
    public GroupDetail cancel(@PathVariable String id, @Valid @RequestBody(required = false) CancelRequest req) {
        return service.cancelGroup(currentUser.require(), id, req);
    }

    @PostMapping("/api/bulk/groups/{id}/refresh")
    public GroupDetail refresh(@PathVariable String id) {
        return service.refresh(currentUser.require(), id);
    }

    @GetMapping("/api/bulk/groups/{id}/events")
    public Page<EventView> events(@PathVariable String id, @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return service.events(currentUser.require(), id, page(page, size));
    }

    // ---- Participation (always the caller's own allocation) ----

    @GetMapping("/api/bulk/groups/{id}/quote")
    public Quote quote(@PathVariable String id, @RequestParam int packs,
                       @RequestParam(required = false) StockFulfilmentOption fulfilmentMethod) {
        return service.quote(currentUser.require(), id, packs, fulfilmentMethod);
    }

    @PostMapping("/api/bulk/groups/{id}/join")
    public ResponseEntity<AllocationView> join(@PathVariable String id, @Valid @RequestBody JoinRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.join(currentUser.require(), id, req));
    }

    @GetMapping("/api/bulk/groups/{id}/allocation")
    public AllocationView myAllocation(@PathVariable String id) {
        return service.myAllocation(currentUser.require(), id);
    }

    @PatchMapping("/api/bulk/groups/{id}/allocation")
    public AllocationView updateAllocation(@PathVariable String id, @Valid @RequestBody UpdateAllocationRequest req) {
        return service.updateAllocation(currentUser.require(), id, req);
    }

    @PostMapping("/api/bulk/groups/{id}/allocation/confirm")
    public ConfirmOutcome confirm(@PathVariable String id, @Valid @RequestBody ConfirmRequest req) {
        return service.confirm(currentUser.require(), id, req);
    }

    @PostMapping("/api/bulk/groups/{id}/allocation/withdraw")
    public AllocationView withdraw(@PathVariable String id) {
        return service.withdraw(currentUser.require(), id);
    }

    @PostMapping("/api/bulk/groups/{id}/allocation/issues")
    public AllocationView reportIssue(@PathVariable String id, @Valid @RequestBody ReportIssueRequest req) {
        return service.reportIssue(currentUser.require(), id, req);
    }

    @GetMapping("/api/bulk/allocations/mine")
    public Page<AllocationView> myAllocations(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.myAllocations(currentUser.require(), page(page, size));
    }

    // ---- Supplier ----

    @GetMapping("/api/bulk/groups/{id}/supplier")
    public SupplierGroupView supplierView(@PathVariable String id) {
        return service.supplierView(currentUser.require(), id);
    }

    @PostMapping("/api/bulk/groups/{id}/supplier/confirm")
    public GroupDetail supplierConfirm(@PathVariable String id, @Valid @RequestBody SupplierConfirmRequest req) {
        return service.supplierConfirm(currentUser.require(), id, req);
    }

    // ---- Admin (read-only oversight; there is deliberately no endpoint that refunds or marks anything paid) ----

    @GetMapping("/api/admin/bulk/refund-due")
    public Page<AllocationView> adminRefundDue(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        currentUser.requireAdmin();
        return service.adminRefundDue(page(page, size));
    }
}
