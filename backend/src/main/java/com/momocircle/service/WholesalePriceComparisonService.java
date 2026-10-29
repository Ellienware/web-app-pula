package com.momocircle.service;

import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.WholesaleProduct;
import com.momocircle.domain.enums.MatchClassification;
import com.momocircle.dto.MarketDtos.WholesalePriceComparisonEntry;
import com.momocircle.dto.MarketDtos.WholesalePriceComparisonResponse;
import com.momocircle.repository.WholesaleProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Service
public class WholesalePriceComparisonService {

    private final CanonicalProductService canonicalProductService;
    private final WholesaleProductRepository wholesaleProductRepository;

    public WholesalePriceComparisonService(
            CanonicalProductService canonicalProductService,
            WholesaleProductRepository wholesaleProductRepository) {
        this.canonicalProductService = canonicalProductService;
        this.wholesaleProductRepository = wholesaleProductRepository;
    }

    @Transactional(readOnly = true)
    public WholesalePriceComparisonResponse compare(String canonicalProductId) {
        CanonicalProduct canonical =
                canonicalProductService.requireById(canonicalProductId);

        List<WholesalePriceComparisonEntry> exactMatches =
                wholesaleProductRepository
                        .findByCanonicalProductIdAndMatchClassificationAndActiveTrue(
                                canonical.getId(), MatchClassification.EXACT_MATCH)
                        .stream()
                        .map(this::toEntry)
                        .sorted(priceComparator())
                        .toList();

        List<WholesalePriceComparisonEntry> similarAlternatives =
                wholesaleProductRepository
                        .findByCanonicalProductIdAndMatchClassificationAndActiveTrue(
                                canonical.getId(), MatchClassification.SIMILAR_ALTERNATIVE)
                        .stream()
                        .map(this::toEntry)
                        .sorted(priceComparator())
                        .toList();

        return new WholesalePriceComparisonResponse(
                canonical.getId(),
                canonical.getName(),
                exactMatches,
                similarAlternatives
        );
    }

    private WholesalePriceComparisonEntry toEntry(WholesaleProduct product) {
        BigDecimal packPrice = product.getPricePerPack();
        int packSize = product.getPackSize();

        BigDecimal normalizedPricePerItem = null;
        if (packPrice != null && packSize > 0) {
            normalizedPricePerItem = packPrice.divide(
                    BigDecimal.valueOf(packSize), 4, RoundingMode.HALF_UP);
        }

        int stock = product.getAvailableStock();

        return new WholesalePriceComparisonEntry(
                product.getId(),
                product.getSupplierBusiness().getId(),
                product.getSupplierBusiness().getName(),
                product.getName(),
                packPrice,
                product.getCurrency(),
                packSize,
                product.getMoq(),
                stock,
                product.getSellingMode(),
                product.getPricePerUnit(),
                normalizedPricePerItem,
                stock > 0
        );
    }

    private Comparator<WholesalePriceComparisonEntry> priceComparator() {
        return Comparator
                .comparing(
                        WholesalePriceComparisonEntry::normalizedPricePerItem,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                        WholesalePriceComparisonEntry::pricePerPack,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
