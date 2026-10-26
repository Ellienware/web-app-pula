package com.momocircle.controller;

import com.momocircle.dto.SellerDashboardDtos.SellerDashboardResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.SellerDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seller/dashboard")
public class SellerDashboardController {

    private final SellerDashboardService sellerDashboardService;
    private final CurrentUserService currentUser;

    public SellerDashboardController(SellerDashboardService sellerDashboardService, CurrentUserService currentUser) {
        this.sellerDashboardService = sellerDashboardService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public SellerDashboardResponse summary() {
        return sellerDashboardService.summary(currentUser.require());
    }
}
