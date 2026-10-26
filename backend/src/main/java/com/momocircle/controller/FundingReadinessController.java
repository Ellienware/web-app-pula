package com.momocircle.controller;

import com.momocircle.dto.FundingReadinessDtos.FundingReadinessReport;
import com.momocircle.dto.FundingReadinessDtos.FundingReadinessResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.FundingReadinessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FundingReadinessController {
    private final FundingReadinessService service;
    private final CurrentUserService currentUser;

    public FundingReadinessController(FundingReadinessService service, CurrentUserService currentUser) {
        this.service = service; this.currentUser = currentUser;
    }

    @GetMapping("/api/funding-readiness")
    public FundingReadinessResponse readiness() { return service.readiness(currentUser.require()); }

    @GetMapping("/api/funding-readiness/report")
    public FundingReadinessReport report() { return service.report(currentUser.require()); }
}
