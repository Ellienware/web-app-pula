package com.momocircle.service;

import com.momocircle.domain.CanonicalProduct;
import com.momocircle.dto.MarketDtos.CanonicalProductResponse;
import com.momocircle.dto.MarketDtos.CreateCanonicalProductRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CanonicalProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this.
 */
class CanonicalProductServiceTest {

    private CanonicalProductRepository repository;
    private CanonicalProductService service;

    @BeforeEach
    void setUp() {
        repository = mock(CanonicalProductRepository.class);
        service = new CanonicalProductService(repository);
        when(repository.save(any(CanonicalProduct.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createSucceedsWithoutBarcode() {
        CreateCanonicalProductRequest req = new CreateCanonicalProductRequest(
                "Full Cream Milk 2L", "Clover", "Dairy", "Milk", "2", "L", null);

        CanonicalProductResponse response = service.create(req);

        assertEquals("Full Cream Milk 2L", response.name());
        assertNull(response.barcode());
    }

    @Test
    void createRejectsDuplicateBarcode() {
        when(repository.findByBarcode("6001234567890")).thenReturn(Optional.of(new CanonicalProduct()));
        CreateCanonicalProductRequest req = new CreateCanonicalProductRequest(
                "Full Cream Milk 2L", "Clover", "Dairy", null, null, null, "6001234567890");

        assertThrows(ApiException.class, () -> service.create(req));
    }
}
