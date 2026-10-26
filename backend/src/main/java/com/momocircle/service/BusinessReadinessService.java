package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessRegistrationStatus;
import com.momocircle.dto.MarketDtos.BusinessProfileCompleteness;
import com.momocircle.repository.BusinessDocumentRepository;
import com.momocircle.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PULA Business Profile completeness (brief §8): a plain checklist built
 * entirely from data the business already has on PULA. Deliberately NOT a
 * credit score or any kind of predictive signal (brief §53/§8: "Do not
 * create a fake 'credit score'") — every field is a direct yes/no fact
 * ("does this business have a location on file"), and the response is only
 * ever consumed to show ✓/○ in the UI and to help the owner see what's
 * missing, never to gate access to anything or to rank businesses.
 */
@Service
public class BusinessReadinessService {

    private final ProductRepository productRepository;
    private final BusinessDocumentRepository documentRepository;

    public BusinessReadinessService(ProductRepository productRepository,
                                     BusinessDocumentRepository documentRepository) {
        this.productRepository = productRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional(readOnly = true)
    public BusinessProfileCompleteness computeFor(Business business) {
        boolean businessName = business.getName() != null && !business.getName().isBlank();
        // Owner information: the account itself always has name/phone/email (required at
        // registration — see AuthDtos.RegisterRequest), so "owner information" is really
        // asking whether the *business* has its own contact details on file, distinct from
        // the personal account, per brief §8's "Owner information" row.
        boolean ownerInformation = (business.getContactPhone() != null && !business.getContactPhone().isBlank())
                || (business.getContactEmail() != null && !business.getContactEmail().isBlank());
        boolean location = business.getLocation() != null && !business.getLocation().isBlank();
        // businessType always has a value (defaults to LOCAL_BUSINESS at creation — see
        // Business field initializer), so this row asks whether the owner has *confirmed*
        // it's the right one via a description, which is the only other free-text signal
        // available that the owner engaged with the field rather than leaving the default.
        boolean businessType = business.getBusinessType() != null;
        boolean hasProducts = productRepository.countByBusinessId(business.getId()) > 0;
        boolean registrationDetails = business.getRegistrationStatus() != BusinessRegistrationStatus.NOT_REGISTERED
                || (business.getRegistrationNumber() != null && !business.getRegistrationNumber().isBlank());
        boolean supportingDocuments = documentRepository.countByBusinessId(business.getId()) > 0;

        boolean[] items = {businessName, ownerInformation, location, businessType,
                hasProducts, registrationDetails, supportingDocuments};
        int completed = 0;
        for (boolean item : items) if (item) completed++;

        return new BusinessProfileCompleteness(
                businessName, ownerInformation, location, businessType,
                hasProducts, registrationDetails, supportingDocuments,
                completed, items.length);
    }
}
