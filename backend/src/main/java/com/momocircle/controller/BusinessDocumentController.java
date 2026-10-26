package com.momocircle.controller;

import com.momocircle.dto.MarketDtos.BusinessDocumentResponse;
import com.momocircle.dto.MarketDtos.UploadBusinessDocumentRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.BusinessDocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Every route here is scoped to "my own business's documents" — there is no
 * businessId-in-the-path variant, deliberately, since these can include ID
 * documents and other sensitive material (brief §44).
 */
@RestController
public class BusinessDocumentController {

    private final BusinessDocumentService documentService;
    private final CurrentUserService currentUser;

    public BusinessDocumentController(BusinessDocumentService documentService, CurrentUserService currentUser) {
        this.documentService = documentService;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/businesses/me/documents")
    public ResponseEntity<BusinessDocumentResponse> upload(@Valid @RequestBody UploadBusinessDocumentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.upload(currentUser.require(), req));
    }

    @GetMapping("/api/businesses/me/documents")
    public List<BusinessDocumentResponse> mine() {
        return documentService.listMine(currentUser.require());
    }

    @DeleteMapping("/api/businesses/me/documents/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable String documentId) {
        documentService.delete(currentUser.require(), documentId);
        return ResponseEntity.noContent().build();
    }
}
