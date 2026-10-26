package com.momocircle.service;

import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.Product;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.dto.MarketDtos.ProductPriceComparisonEntry;
import com.momocircle.dto.MarketDtos.ProductPriceComparisonResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Implements the master brief's "Compare My Product" surface: given a
 * CanonicalProduct, list every seller's EXACT_MATCH price for it, cheapest
 * first — plus, separately, any SIMILAR_ALTERNATIVE listings a seller has
 * explicitly linked (never automatically classified — see
 * ProductService#linkCanonicalProduct's javadoc). The two lists are never
 * merged: exactMatches is what "compare prices" is asking about,
 * similarAlternatives is "you might also consider this, but it's not
 * confirmed the same product" — mixing them would risk exactly the false
 * price-comparison the app's design elsewhere goes out of its way to avoid.
 * Deliberately does not attempt cross-currency comparison — if this
 * deployment ever supports more than one currency at once, entries in a
 * currency other than the caller's would need explicit conversion or
 * exclusion, not silent same-list mixing; today ProductRepository's query
 * already only returns EXACT_MATCH rows, and this app's CurrencyProperties
 * currently supports exactly one currency (ZAR) anyway, so this is a
 * documented forward-looking note rather than an active bug.
 */
@Service
public class PriceComparisonService {

    private final CanonicalProductService canonicalProductService;
    private final com.momocircle.repository.ProductRepository productRepository;

    public PriceComparisonService(CanonicalProductService canonicalProductService,
                                   com.momocircle.repository.ProductRepository productRepository) {
        this.canonicalProductService = canonicalProductService;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public ProductPriceComparisonResponse compare(String canonicalProductId) {
        CanonicalProduct canonicalProduct = canonicalProductService.requireById(canonicalProductId);
        List<Product> matches = productRepository.findExactMatchesForCanonicalProduct(canonicalProductId);
        List<Product> alternatives = productRepository.findSimilarAlternativesForCanonicalProduct(canonicalProductId);

        List<ProductPriceComparisonEntry> exactEntries = toEntries(matches);
        List<ProductPriceComparisonEntry> alternativeEntries = toEntries(alternatives);

        return new ProductPriceComparisonResponse(
                canonicalProduct.getId(), canonicalProduct.getName(), exactEntries, alternativeEntries);
    }

    private List<ProductPriceComparisonEntry> toEntries(List<Product> products) {
        return products.stream()
                .sorted(Comparator.comparing(Product::getPrice))
                .map(p -> new ProductPriceComparisonEntry(
                        p.getId(), p.getBusiness().getId(), p.getBusiness().getName(),
                        p.getPrice(), p.getCurrency(),
                        p.getStatus() == ProductStatus.ACTIVE && (p.isService() || p.getStock() > 0)))
                .toList();
    }
}
