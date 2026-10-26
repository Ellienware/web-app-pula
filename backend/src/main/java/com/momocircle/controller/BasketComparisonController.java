package com.momocircle.controller;

import com.momocircle.dto.MarketDtos.BasketComparisonResponse;
import com.momocircle.dto.MarketDtos.CompareBasketRequest;
import com.momocircle.service.BasketComparisonService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BasketComparisonController {

    private final BasketComparisonService basketComparisonService;

    public BasketComparisonController(BasketComparisonService basketComparisonService) {
        this.basketComparisonService = basketComparisonService;
    }

    @PostMapping("/api/baskets/compare")
    public BasketComparisonResponse compare(@Valid @RequestBody CompareBasketRequest request) {
        return basketComparisonService.compare(request);
    }
}
