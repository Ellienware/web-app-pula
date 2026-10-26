package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.Product;
import com.momocircle.domain.enums.MatchClassification;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.dto.MarketDtos.BasketComparisonResponse;
import com.momocircle.dto.MarketDtos.BasketItemRequest;
import com.momocircle.dto.MarketDtos.CompareBasketRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this.
 */
class BasketComparisonServiceTest {

    private ProductRepository productRepository;
    private CanonicalProductService canonicalProductService;
    private BasketComparisonService service;

    private CanonicalProduct milk;
    private CanonicalProduct bread;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        canonicalProductService = mock(CanonicalProductService.class);
        service = new BasketComparisonService(productRepository, canonicalProductService);

        milk = new CanonicalProduct();
        milk.setName("Full Cream Milk 2L");
        bread = new CanonicalProduct();
        bread.setName("White Bread");

        when(canonicalProductService.requireById(milk.getId())).thenReturn(milk);
        when(canonicalProductService.requireById(bread.getId())).thenReturn(bread);
    }

    private Product listing(Business business, CanonicalProduct canonical, String priceStr) {
        Product product = new Product();
        product.setBusiness(business);
        product.setPrice(new BigDecimal(priceStr));
        product.setCurrency("ZAR");
        product.setStatus(ProductStatus.ACTIVE);
        product.setMatchClassification(MatchClassification.EXACT_MATCH);
        product.setCanonicalProduct(canonical);
        return product;
    }

    private Business business(String name) {
        Business b = new Business();
        b.setName(name);
        return b;
    }

    @Test
    void rejectsEmptyBasket() {
        assertThrows(ApiException.class, () -> service.compare(new CompareBasketRequest(List.of())));
    }

    @Test
    void identifiesCheapestCompleteStoreAndSavings() {
        Business shoprite = business("Shoprite");
        Business localShop = business("Local Shop");
        Product shopriteMilk = listing(shoprite, milk, "32.99");
        Product shopriteBread = listing(shoprite, bread, "18.00");
        Product localShopMilk = listing(localShop, milk, "31.00");
        Product localShopBread = listing(localShop, bread, "20.00");

        when(productRepository.findExactMatchesForCanonicalProduct(milk.getId()))
                .thenReturn(List.of(shopriteMilk, localShopMilk));
        when(productRepository.findExactMatchesForCanonicalProduct(bread.getId()))
                .thenReturn(List.of(shopriteBread, localShopBread));

        CompareBasketRequest request = new CompareBasketRequest(List.of(
                new BasketItemRequest(milk.getId(), 1),
                new BasketItemRequest(bread.getId(), 1)));

        BasketComparisonResponse response = service.compare(request);

        // Shoprite: 32.99 + 18.00 = 50.99, Local Shop: 31.00 + 20.00 = 51.00
        assertEquals(2, response.storeTotals().size());
        assertEquals("Shoprite", response.cheapestCompleteStore().businessName());
        assertEquals(0, new BigDecimal("50.99").compareTo(response.cheapestCompleteStore().estimatedSubtotal()));
        assertTrue(response.cheapestCompleteStore().completeBasket());
        assertEquals(0, new BigDecimal("0.01").compareTo(response.potentialSavings()));
    }

    @Test
    void partialStoreIsMarkedIncompleteAndListsMissingItems() {
        Product cornerShopMilk = listing(business("Corner Shop"), milk, "33.00");
        when(productRepository.findExactMatchesForCanonicalProduct(milk.getId()))
                .thenReturn(List.of(cornerShopMilk));
        when(productRepository.findExactMatchesForCanonicalProduct(bread.getId()))
                .thenReturn(List.of());

        CompareBasketRequest request = new CompareBasketRequest(List.of(
                new BasketItemRequest(milk.getId(), 1),
                new BasketItemRequest(bread.getId(), 1)));

        BasketComparisonResponse response = service.compare(request);

        assertEquals(1, response.storeTotals().size());
        assertFalse(response.storeTotals().get(0).completeBasket());
        assertEquals(List.of(bread.getId()), response.storeTotals().get(0).missingCanonicalProductIds());
        assertNull(response.cheapestCompleteStore());
        assertNull(response.potentialSavings());
    }

    @Test
    void quantityMultipliesLineTotal() {
        Product milkListing = listing(business("Shoprite"), milk, "32.99");
        when(productRepository.findExactMatchesForCanonicalProduct(milk.getId())).thenReturn(List.of(milkListing));

        CompareBasketRequest request = new CompareBasketRequest(List.of(new BasketItemRequest(milk.getId(), 3)));
        BasketComparisonResponse response = service.compare(request);

        assertEquals(0, new BigDecimal("98.97").compareTo(response.storeTotals().get(0).estimatedSubtotal()));
    }

    @Test
    void deliveryFeeIsAddedToEstimatedTotalButNotEstimatedSubtotal() {
        Business shoprite = business("Shoprite");
        shoprite.setDeliveryFee(new BigDecimal("30.00"));
        Product milkListing = listing(shoprite, milk, "32.99");
        when(productRepository.findExactMatchesForCanonicalProduct(milk.getId())).thenReturn(List.of(milkListing));

        BasketComparisonResponse response = service.compare(
                new CompareBasketRequest(List.of(new BasketItemRequest(milk.getId(), 1))));

        assertEquals(0, new BigDecimal("32.99").compareTo(response.storeTotals().get(0).estimatedSubtotal()));
        assertEquals(0, new BigDecimal("62.99").compareTo(response.storeTotals().get(0).estimatedTotal()));
    }

    @Test
    void minimumOrderAmountIsSurfacedNotSilentlyExcluded() {
        Business corner = business("Corner Shop");
        corner.setMinimumOrderAmount(new BigDecimal("100.00"));
        Product milkListing = listing(corner, milk, "32.99");
        when(productRepository.findExactMatchesForCanonicalProduct(milk.getId())).thenReturn(List.of(milkListing));

        BasketComparisonResponse response = service.compare(
                new CompareBasketRequest(List.of(new BasketItemRequest(milk.getId(), 1))));

        // Store still appears in the results (not hidden) — just flagged as not meeting the minimum yet.
        assertEquals(1, response.storeTotals().size());
        assertFalse(response.storeTotals().get(0).meetsMinimumOrder());
        assertEquals(0, new BigDecimal("100.00").compareTo(response.storeTotals().get(0).minimumOrderAmount()));
    }

    @Test
    void fewestDeliveriesPlanCoversTheBasketFromTwoStoresWhenNoSingleStoreHasEverything() {
        // Corner Shop has milk only; Bakery has bread only — no single store is
        // complete, but the two together cover the whole basket.
        Product cornerMilk = listing(business("Corner Shop"), milk, "33.00");
        Product bakeryBread = listing(business("Bakery"), bread, "18.00");
        when(productRepository.findExactMatchesForCanonicalProduct(milk.getId())).thenReturn(List.of(cornerMilk));
        when(productRepository.findExactMatchesForCanonicalProduct(bread.getId())).thenReturn(List.of(bakeryBread));

        CompareBasketRequest request = new CompareBasketRequest(List.of(
                new BasketItemRequest(milk.getId(), 1), new BasketItemRequest(bread.getId(), 1)));

        BasketComparisonResponse response = service.compare(request);

        assertNull(response.cheapestCompleteStore()); // no single store is complete
        assertEquals(2, response.fewestDeliveriesPlan().size());
        assertEquals(0, new BigDecimal("51.00").compareTo(response.fewestDeliveriesPlanTotal()));
    }

    @Test
    void fewestDeliveriesPlanPrefersASingleStoreWhenOneCoversEverything() {
        Business shoprite = business("Shoprite");
        Product shopriteMilk = listing(shoprite, milk, "32.99");
        Product shopriteBread = listing(shoprite, bread, "18.00");
        when(productRepository.findExactMatchesForCanonicalProduct(milk.getId())).thenReturn(List.of(shopriteMilk));
        when(productRepository.findExactMatchesForCanonicalProduct(bread.getId())).thenReturn(List.of(shopriteBread));

        CompareBasketRequest request = new CompareBasketRequest(List.of(
                new BasketItemRequest(milk.getId(), 1), new BasketItemRequest(bread.getId(), 1)));

        BasketComparisonResponse response = service.compare(request);

        assertEquals(1, response.fewestDeliveriesPlan().size());
        assertEquals("Shoprite", response.fewestDeliveriesPlan().get(0).businessName());
    }

    @Test
    void fewestDeliveriesPlanIsNullWhenNoCombinationCanCoverTheWholeBasket() {
        Product cornerMilk = listing(business("Corner Shop"), milk, "33.00");
        when(productRepository.findExactMatchesForCanonicalProduct(milk.getId())).thenReturn(List.of(cornerMilk));
        when(productRepository.findExactMatchesForCanonicalProduct(bread.getId())).thenReturn(List.of()); // nobody has bread

        CompareBasketRequest request = new CompareBasketRequest(List.of(
                new BasketItemRequest(milk.getId(), 1), new BasketItemRequest(bread.getId(), 1)));

        BasketComparisonResponse response = service.compare(request);

        assertNull(response.fewestDeliveriesPlan());
        assertNull(response.fewestDeliveriesPlanTotal());
    }
}
