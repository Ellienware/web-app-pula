package com.momocircle.controller;

import com.momocircle.dto.MarketDtos.WholesalePriceComparisonResponse;
import com.momocircle.service.WholesalePriceComparisonService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WholesalePriceComparisonController {

    private final WholesalePriceComparisonService comparisonService;

    public WholesalePriceComparisonController(
            WholesalePriceComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    @GetMapping("/api/canonical-products/{id}/wholesale-comparison")
    public WholesalePriceComparisonResponse compare(@PathVariable String id) {
        return comparisonService.compare(id);
    }
}
