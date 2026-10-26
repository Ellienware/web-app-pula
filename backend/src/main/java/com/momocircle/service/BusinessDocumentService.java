package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessDocument;
import com.momocircle.domain.User;
import com.momocircle.dto.MarketDtos.BusinessDocumentResponse;
import com.momocircle.dto.MarketDtos.UploadBusinessDocumentRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BusinessDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Supporting documents attached to a business's profile (brief §7-8). These
 * can include ID documents and other sensitive material, so every method
 * here requires the caller to already own the business (see
 * {@link BusinessService#requireOwn(User)}) — there is deliberately no
 * businessId-scoped public read path (contrast with products, which are
 * public). Never exposed to PULA AI (brief §44: "financial information is
 * not unnecessarily exposed to AI" — documents are even more sensitive).
 */
@Service
public class BusinessDocumentService {

    private final BusinessDocumentRepository documentRepository;
    private final BusinessService businessService;

    public BusinessDocumentService(BusinessDocumentRepository documentRepository, BusinessService businessService) {
        this.documentRepository = documentRepository;
        this.businessService = businessService;
    }

    @Transactional
    public BusinessDocumentResponse upload(User owner, UploadBusinessDocumentRequest req) {
        Business business = businessService.requireOwn(owner);
        BusinessDocument doc = new BusinessDocument();
        doc.setBusiness(business);
        doc.setDocumentType(req.documentType());
        doc.setFileUrl(req.fileUrl().trim());
        doc.setNote(req.note());
        return toResponse(documentRepository.save(doc));
    }

    @Transactional(readOnly = true)
    public List<BusinessDocumentResponse> listMine(User owner) {
        Business business = businessService.requireOwn(owner);
        return documentRepository.findByBusinessIdOrderByCreatedAtDesc(business.getId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public void delete(User owner, String documentId) {
        Business business = businessService.requireOwn(owner);
        BusinessDocument doc = documentRepository.findByIdAndBusinessId(documentId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Document not found"));
        documentRepository.delete(doc);
    }

    private BusinessDocumentResponse toResponse(BusinessDocument d) {
        return new BusinessDocumentResponse(
                d.getId(), d.getBusiness().getId(), d.getDocumentType(), d.getFileUrl(), d.getNote(), d.getCreatedAt());
    }
}
