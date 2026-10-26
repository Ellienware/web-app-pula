package com.momocircle.controller;

import com.momocircle.dto.ReceiptDtos.ReceiptResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.ReceiptService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private final ReceiptService receiptService;
    private final CurrentUserService currentUser;

    public ReceiptController(ReceiptService receiptService, CurrentUserService currentUser) {
        this.receiptService = receiptService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{receiptId}")
    public ReceiptResponse get(@PathVariable String receiptId) {
        return receiptService.get(currentUser.require(), receiptId);
    }

    @GetMapping
    public Page<ReceiptResponse> mine(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return receiptService.mine(currentUser.require(), PageRequest.of(page, Math.min(size, 50)));
    }
}
