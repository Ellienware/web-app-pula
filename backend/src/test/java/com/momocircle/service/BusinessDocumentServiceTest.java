package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessDocument;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessDocumentType;
import com.momocircle.dto.MarketDtos.BusinessDocumentResponse;
import com.momocircle.dto.MarketDtos.UploadBusinessDocumentRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BusinessDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX: no network access to Maven Central meant
 * ./mvnw test could not be run here — please run this locally before relying
 * on it.
 */
class BusinessDocumentServiceTest {

    private BusinessDocumentRepository documentRepository;
    private BusinessService businessService;
    private BusinessDocumentService service;
    private User owner;
    private Business business;

    @BeforeEach
    void setUp() {
        documentRepository = mock(BusinessDocumentRepository.class);
        businessService = mock(BusinessService.class);
        service = new BusinessDocumentService(documentRepository, businessService);

        owner = new User();
        owner.setId("user-1");
        business = new Business();
        business.setOwner(owner);
        business.setName("Thandi's Spaza");
        business.setCategory("Grocery");
        when(businessService.requireOwn(owner)).thenReturn(business);
        when(documentRepository.save(any(BusinessDocument.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void uploadAttachesDocumentToTheCallersOwnBusiness() {
        UploadBusinessDocumentRequest req = new UploadBusinessDocumentRequest(
                BusinessDocumentType.PROOF_OF_ADDRESS, "https://example.com/proof.pdf", "Municipal bill");

        BusinessDocumentResponse response = service.upload(owner, req);

        assertEquals(business.getId(), response.businessId());
        assertEquals(BusinessDocumentType.PROOF_OF_ADDRESS, response.documentType());
        assertEquals("https://example.com/proof.pdf", response.fileUrl());
    }

    @Test
    void uploadFailsWhenCallerHasNoBusiness() {
        when(businessService.requireOwn(owner)).thenThrow(ApiException.notFound("You don't have a business profile yet"));
        UploadBusinessDocumentRequest req = new UploadBusinessDocumentRequest(
                BusinessDocumentType.ID_DOCUMENT, "https://example.com/id.pdf", null);

        assertThrows(ApiException.class, () -> service.upload(owner, req));
    }

    @Test
    void listMineReturnsOnlyTheCallersOwnDocuments() {
        BusinessDocument doc = new BusinessDocument();
        doc.setBusiness(business);
        doc.setDocumentType(BusinessDocumentType.CIPC_REGISTRATION_CERTIFICATE);
        doc.setFileUrl("https://example.com/cipc.pdf");
        when(documentRepository.findByBusinessIdOrderByCreatedAtDesc(business.getId())).thenReturn(List.of(doc));

        List<BusinessDocumentResponse> result = service.listMine(owner);

        assertEquals(1, result.size());
        assertEquals(business.getId(), result.get(0).businessId());
    }

    @Test
    void deleteRejectsADocumentThatBelongsToAnotherBusiness() {
        // findByIdAndBusinessId scoped to the caller's own business — a document id from
        // someone else's business simply won't be found under this business's id, which
        // is exactly the IDOR protection this method exists to provide.
        when(documentRepository.findByIdAndBusinessId("someone-elses-doc", business.getId()))
                .thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.delete(owner, "someone-elses-doc"));
        verify(documentRepository, never()).delete(any(BusinessDocument.class));
    }

    @Test
    void deleteRemovesTheCallersOwnDocument() {
        BusinessDocument doc = new BusinessDocument();
        doc.setBusiness(business);
        doc.setDocumentType(BusinessDocumentType.OTHER);
        doc.setFileUrl("https://example.com/other.pdf");
        when(documentRepository.findByIdAndBusinessId(doc.getId(), business.getId())).thenReturn(Optional.of(doc));

        service.delete(owner, doc.getId());

        verify(documentRepository).delete(doc);
    }
}
