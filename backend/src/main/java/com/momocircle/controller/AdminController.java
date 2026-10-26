package com.momocircle.controller;

import com.momocircle.dto.AdminDtos.AdminUserResponse;
import com.momocircle.dto.AdminDtos.AuditLogResponse;
import com.momocircle.dto.AdminDtos.PlatformStatsResponse;
import com.momocircle.dto.MarketDtos.OrderResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.AdminService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Every method resolves an ADMIN-role caller via CurrentUserService.requireAdmin()
 * first — a non-admin (or unauthenticated) caller never reaches AdminService.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final CurrentUserService currentUser;

    public AdminController(AdminService adminService, CurrentUserService currentUser) {
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @GetMapping("/users")
    public Page<AdminUserResponse> users(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdmin();
        return adminService.listUsers(PageRequest.of(page, Math.min(size, 100)));
    }

    @PostMapping("/users/{userId}/suspend")
    public void suspend(@PathVariable String userId) {
        adminService.setUserActive(currentUser.requireAdmin(), userId, false);
    }

    @PostMapping("/users/{userId}/reactivate")
    public void reactivate(@PathVariable String userId) {
        adminService.setUserActive(currentUser.requireAdmin(), userId, true);
    }

    @PostMapping("/businesses/{businessId}/deactivate")
    public void deactivateBusiness(@PathVariable String businessId) {
        adminService.setBusinessActive(currentUser.requireAdmin(), businessId, false);
    }

    @PostMapping("/businesses/{businessId}/reactivate")
    public void reactivateBusiness(@PathVariable String businessId) {
        adminService.setBusinessActive(currentUser.requireAdmin(), businessId, true);
    }

    @GetMapping("/orders")
    public Page<OrderResponse> orders(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdmin();
        return adminService.listOrders(PageRequest.of(page, Math.min(size, 100)));
    }

    @PostMapping("/orders/{orderId}/refund")
    public OrderResponse refund(@PathVariable String orderId, @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return adminService.forceRefund(currentUser.requireAdmin(), orderId, reason);
    }

    @PostMapping("/reviews/{reviewId}/hide")
    public void hideReview(@PathVariable String reviewId) {
        adminService.hideReview(currentUser.requireAdmin(), reviewId);
    }

    @GetMapping("/audit-logs")
    public Page<AuditLogResponse> auditLogs(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdmin();
        return adminService.listAuditLogs(PageRequest.of(page, Math.min(size, 100)));
    }

    @GetMapping("/stats")
    public PlatformStatsResponse stats() {
        currentUser.requireAdmin();
        return adminService.stats();
    }
}
