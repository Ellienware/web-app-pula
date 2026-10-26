package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.Business;
import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.MatchClassification;
import com.momocircle.dto.MarketDtos.ProductResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CanonicalProductRepository;
import com.momocircle.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this.
 */
class ProductServiceTest {

    private ProductRepository productRepository;
    private BusinessService businessService;
    private CanonicalProductRepository canonicalProductRepository;
    private ProductService service;
    private User owner;
    private Business business;
    private Product product;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        businessService = mock(BusinessService.class);
        canonicalProductRepository = mock(CanonicalProductRepository.class);
        CurrencyProperties currencyProperties = new CurrencyProperties();
        currencyProperties.setDefaultCurrency("ZAR");
        currencyProperties.setSupported(List.of("ZAR"));
        FinancialValidationService financialValidationService =
                new FinancialValidationService(currencyProperties, new BigDecimal("10000000"));
        service = new ProductService(productRepository, businessService, currencyProperties,
                financialValidationService, canonicalProductRepository);

        owner = new User();
        owner.setId("owner-1");
        business = new Business();
        business.setOwner(owner);

        product = new Product();
        product.setBusiness(business);
        product.setName("Full Cream Milk 2L");
        product.setCategory("Dairy");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void linkingDefaultsToExactMatchWhenClassificationOmitted() {
        CanonicalProduct canonical = new CanonicalProduct();
        when(canonicalProductRepository.findById(canonical.getId())).thenReturn(Optional.of(canonical));

        ProductResponse response = service.linkCanonicalProduct(owner, product.getId(), canonical.getId(), null);

        assertEquals(MatchClassification.EXACT_MATCH, response.matchClassification());
    }

    @Test
    void sellerCanExplicitlyChooseSimilarAlternative() {
        CanonicalProduct canonical = new CanonicalProduct();
        when(canonicalProductRepository.findById(canonical.getId())).thenReturn(Optional.of(canonical));

        ProductResponse response = service.linkCanonicalProduct(
                owner, product.getId(), canonical.getId(), MatchClassification.SIMILAR_ALTERNATIVE);

        assertEquals(MatchClassification.SIMILAR_ALTERNATIVE, response.matchClassification());
    }

    @Test
    void rejectsExplicitlyPassingUniqueProductAsTheClassificationForALinkedProduct() {
        CanonicalProduct canonical = new CanonicalProduct();
        when(canonicalProductRepository.findById(canonical.getId())).thenReturn(Optional.of(canonical));

        assertThrows(ApiException.class, () -> service.linkCanonicalProduct(
                owner, product.getId(), canonical.getId(), MatchClassification.UNIQUE_PRODUCT));
    }

    @Test
    void unlinkingAlwaysResetsToUniqueProductRegardlessOfClassificationArgument() {
        ProductResponse response = service.linkCanonicalProduct(
                owner, product.getId(), null, MatchClassification.EXACT_MATCH);
        assertEquals(MatchClassification.UNIQUE_PRODUCT, response.matchClassification());
    }

    @Test
    void nonOwnerCannotLinkAnotherSellersProduct() {
        User stranger = new User();
        stranger.setId("stranger-1");
        CanonicalProduct canonical = new CanonicalProduct();

        org.mockito.Mockito.doThrow(ApiException.forbidden("not yours"))
                .when(businessService).requireOwnership(business, stranger);

        assertThrows(ApiException.class, () -> service.linkCanonicalProduct(
                stranger, product.getId(), canonical.getId(), MatchClassification.EXACT_MATCH));
    }

    @Test
    void suggestionsAreTransparentTokenOverlapNotAnOpaqueModel() {
        CanonicalProduct closeMatch = new CanonicalProduct();
        closeMatch.setName("Full Cream Milk 2L");
        closeMatch.setCategory("Dairy");
        CanonicalProduct unrelated = new CanonicalProduct();
        unrelated.setName("Bread White Loaf");
        unrelated.setCategory("Dairy");

        when(canonicalProductRepository.findByCategory("Dairy")).thenReturn(List.of(closeMatch, unrelated));

        List<ProductService.CanonicalProductSuggestion> suggestions =
                service.suggestCanonicalProducts(owner, product.getId());

        assertEquals(1, suggestions.size());
        assertEquals(closeMatch.getId(), suggestions.get(0).canonicalProduct().getId());
        assertTrue(suggestions.get(0).score() > 0.2);
    }

    @Test
    void suggestionsExcludeTheAlreadyLinkedCanonicalProduct() {
        CanonicalProduct alreadyLinked = new CanonicalProduct();
        alreadyLinked.setName("Full Cream Milk 2L");
        alreadyLinked.setCategory("Dairy");
        product.setCanonicalProduct(alreadyLinked);

        when(canonicalProductRepository.findByCategory("Dairy")).thenReturn(List.of(alreadyLinked));

        List<ProductService.CanonicalProductSuggestion> suggestions =
                service.suggestCanonicalProducts(owner, product.getId());

        assertTrue(suggestions.isEmpty());
    }
}
