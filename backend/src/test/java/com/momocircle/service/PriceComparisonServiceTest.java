package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.Product;
import com.momocircle.domain.enums.MatchClassification;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.dto.MarketDtos.ProductPriceComparisonResponse;
import com.momocircle.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this.
 */
class PriceComparisonServiceTest {

    private ProductRepository productRepository;
    private CanonicalProductService canonicalProductService;
    private PriceComparisonService service;
    private CanonicalProduct canonicalProduct;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        canonicalProductService = mock(CanonicalProductService.class);
        service = new PriceComparisonService(canonicalProductService, productRepository);

        canonicalProduct = new CanonicalProduct();
        canonicalProduct.setName("Full Cream Milk 2L");
        when(canonicalProductService.requireById(canonicalProduct.getId())).thenReturn(canonicalProduct);
    }

    private Product productWithPrice(String businessName, String priceStr, ProductStatus status, int stock) {
        Business business = new Business();
        business.setName(businessName);
        Product product = new Product();
        product.setBusiness(business);
        product.setPrice(new BigDecimal(priceStr));
        product.setCurrency("ZAR");
        product.setStatus(status);
        product.setStock(stock);
        product.setMatchClassification(MatchClassification.EXACT_MATCH);
        product.setCanonicalProduct(canonicalProduct);
        return product;
    }

    @Test
    void sortsCheapestFirst() {
        Product expensive = productWithPrice("Pick n Pay", "36.99", ProductStatus.ACTIVE, 10);
        Product cheapest = productWithPrice("Local Shop", "31.00", ProductStatus.ACTIVE, 10);
        Product middle = productWithPrice("Shoprite", "32.99", ProductStatus.ACTIVE, 10);
        when(productRepository.findExactMatchesForCanonicalProduct(canonicalProduct.getId()))
                .thenReturn(List.of(expensive, cheapest, middle));

        ProductPriceComparisonResponse response = service.compare(canonicalProduct.getId());

        assertEquals(3, response.exactMatches().size());
        assertEquals("Local Shop", response.exactMatches().get(0).businessName());
        assertEquals("Shoprite", response.exactMatches().get(1).businessName());
        assertEquals("Pick n Pay", response.exactMatches().get(2).businessName());
    }

    @Test
    void outOfStockListingIsMarkedUnavailableNotHidden() {
        Product outOfStock = productWithPrice("Corner Shop", "30.00", ProductStatus.OUT_OF_STOCK, 0);
        when(productRepository.findExactMatchesForCanonicalProduct(canonicalProduct.getId()))
                .thenReturn(List.of(outOfStock));

        ProductPriceComparisonResponse response = service.compare(canonicalProduct.getId());

        assertEquals(1, response.exactMatches().size());
        assertFalse(response.exactMatches().get(0).available());
    }

    @Test
    void serviceListingIsAlwaysAvailableRegardlessOfStock() {
        Product service0 = productWithPrice("Corner Mechanic", "150.00", ProductStatus.ACTIVE, 0);
        service0.setService(true);
        when(productRepository.findExactMatchesForCanonicalProduct(canonicalProduct.getId()))
                .thenReturn(List.of(service0));

        ProductPriceComparisonResponse response = service.compare(canonicalProduct.getId());

        assertTrue(response.exactMatches().get(0).available());
    }

    @Test
    void similarAlternativesAreNeverMixedIntoExactMatches() {
        Product exact = productWithPrice("Shoprite", "32.99", ProductStatus.ACTIVE, 10);
        Product similar = productWithPrice("Corner Shop", "35.00", ProductStatus.ACTIVE, 10);
        when(productRepository.findExactMatchesForCanonicalProduct(canonicalProduct.getId()))
                .thenReturn(List.of(exact));
        when(productRepository.findSimilarAlternativesForCanonicalProduct(canonicalProduct.getId()))
                .thenReturn(List.of(similar));

        ProductPriceComparisonResponse response = service.compare(canonicalProduct.getId());

        assertEquals(1, response.exactMatches().size());
        assertEquals("Shoprite", response.exactMatches().get(0).businessName());
        assertEquals(1, response.similarAlternatives().size());
        assertEquals("Corner Shop", response.similarAlternatives().get(0).businessName());
    }

    @Test
    void noSimilarAlternativesStubbedMeansEmptyNotNull() {
        when(productRepository.findExactMatchesForCanonicalProduct(canonicalProduct.getId())).thenReturn(List.of());

        ProductPriceComparisonResponse response = service.compare(canonicalProduct.getId());

        assertTrue(response.similarAlternatives().isEmpty());
    }
}
