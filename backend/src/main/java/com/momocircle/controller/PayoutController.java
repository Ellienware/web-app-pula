package com.momocircle.controller;

import com.momocircle.security.CurrentUserService;
import com.momocircle.service.PayoutService;
import com.momocircle.service.PayoutService.PayoutResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PayoutController {
    private final PayoutService service;
    private final CurrentUserService currentUser;

    public PayoutController(PayoutService service, CurrentUserService currentUser) {
        this.service = service; this.currentUser = currentUser;
    }

    /** A driver sees only their own payouts. */
    @GetMapping("/api/payouts/mine")
    public Page<PayoutResponse> mine(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return service.myPayouts(currentUser.require(), PageRequest.of(page, Math.min(size, 50)));
    }
}
