package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessRegistrationStatus;
import com.momocircle.dto.MarketDtos.BusinessProfileCompleteness;
import com.momocircle.repository.BusinessDocumentRepository;
import com.momocircle.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX: no network access to Maven Central meant
 * ./mvnw test could not be run here — please run this locally before relying
 * on it.
 */
class BusinessReadinessServiceTest {

    private ProductRepository productRepository;
    private BusinessDocumentRepository documentRepository;
    private BusinessReadinessService service;
    private Business business;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        documentRepository = mock(BusinessDocumentRepository.class);
        service = new BusinessReadinessService(productRepository, documentRepository);

        User owner = new User();
        owner.setId("user-1");
        business = new Business();
        business.setOwner(owner);
        business.setName("Thandi's Spaza");
        business.setCategory("Grocery");
        // id is generated on construction by BaseEntity, no need to set it explicitly.
    }

    @Test
    void freshBusinessIsMostlyIncomplete() {
        when(productRepository.countByBusinessId(business.getId())).thenReturn(0L);
        when(documentRepository.countByBusinessId(business.getId())).thenReturn(0L);

        BusinessProfileCompleteness result = service.computeFor(business);

        assertTrue(result.businessName());   // set in setUp
        assertTrue(result.businessType());   // always has a default
        assertFalse(result.ownerInformation()); // no contact phone/email set
        assertFalse(result.location());
        assertFalse(result.hasProducts());
        assertFalse(result.registrationDetails());
        assertFalse(result.supportingDocuments());
        assertEquals(2, result.completedCount());
        assertEquals(7, result.totalCount());
    }

    @Test
    void fullyCompleteBusinessReportsAllChecksTrue() {
        business.setContactPhone("+27821234567");
        business.setLocation("Soweto, Johannesburg");
        business.setRegistrationStatus(BusinessRegistrationStatus.FORMALLY_REGISTERED);
        when(productRepository.countByBusinessId(business.getId())).thenReturn(3L);
        when(documentRepository.countByBusinessId(business.getId())).thenReturn(1L);

        BusinessProfileCompleteness result = service.computeFor(business);

        assertEquals(7, result.completedCount());
        assertEquals(7, result.totalCount());
    }

    @Test
    void registrationDetailsCountsAsCompleteWithJustARegistrationNumber() {
        // Owner hasn't declared a status change but has entered a registration number —
        // either signal alone should be enough (brief §8's "Registration details" row).
        business.setRegistrationNumber("ENT2024/123456/07");
        when(productRepository.countByBusinessId(business.getId())).thenReturn(0L);
        when(documentRepository.countByBusinessId(business.getId())).thenReturn(0L);

        BusinessProfileCompleteness result = service.computeFor(business);

        assertTrue(result.registrationDetails());
    }
}
