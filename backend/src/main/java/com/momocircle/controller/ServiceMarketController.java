package com.momocircle.controller;

import com.momocircle.domain.enums.ServicePricingModel;
import com.momocircle.dto.ServiceDtos.*;
import com.momocircle.service.ServiceCatalogService;
import com.momocircle.service.ServiceDiscoveryService;
import com.momocircle.service.ServiceInsightService;
import com.momocircle.service.ServiceProviderService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** Customer discovery. Authenticated like the rest of the marketplace; only publicly visible listings are returned. */
@RestController
@RequestMapping("/api/services")
public class ServiceMarketController {

    private final ServiceCatalogService catalog;
    private final ServiceDiscoveryService discovery;
    private final ServiceProviderService providers;
    private final ServiceInsightService insights;

    public ServiceMarketController(ServiceCatalogService catalog, ServiceDiscoveryService discovery,
                                   ServiceProviderService providers, ServiceInsightService insights) {
        this.catalog = catalog;
        this.discovery = discovery;
        this.providers = providers;
        this.insights = insights;
    }

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return catalog.listActive();
    }

    @GetMapping("/search")
    public Page<ListingView> search(@RequestParam(required = false) String categoryId, @RequestParam(required = false) String q,
                                    @RequestParam(required = false) ServicePricingModel pricingModel,
                                    @RequestParam(required = false) BigDecimal maxPrice,
                                    @RequestParam(required = false) BigDecimal minRating,
                                    @RequestParam(defaultValue = "false") boolean verifiedOnly,
                                    @RequestParam(required = false) String area,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.max(1, Math.min(size, 50));
        return discovery.search(categoryId, q, pricingModel, maxPrice, minRating, verifiedOnly, area,
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "ratingAverage")));
    }

    @GetMapping("/listings/{id}")
    public ListingView listing(@PathVariable String id) {
        return discovery.listing(id);
    }

    @GetMapping("/compare")
    public List<ListingView> compare(@RequestParam List<String> ids) {
        return discovery.compare(ids);
    }

    @GetMapping("/compare/insights")
    public InsightsView compareInsights(@RequestParam List<String> ids) {
        return insights.compare(ids);
    }

    @GetMapping("/providers/{businessId}")
    public ProviderProfileView provider(@PathVariable String businessId) {
        return providers.publicProfile(businessId);
    }
}
