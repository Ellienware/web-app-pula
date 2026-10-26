package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.dto.FoodDtos.CanonicalSuggestionResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PULA Food — suggests canonical foods for one of the caller's menu items by name resemblance.
 * Suggestions only: linking still goes through the existing canonical-link call after the merchant
 * confirms, so a similar-sounding name can never silently change what PULA compares.
 */
@Service
public class FoodSuggestionService {

    private final BusinessService businessService;
    private final ProductRepository productRepository;
    private final FoodMatchingService matching;

    public FoodSuggestionService(BusinessService businessService, ProductRepository productRepository,
                                 FoodMatchingService matching) {
        this.businessService = businessService;
        this.productRepository = productRepository;
        this.matching = matching;
    }

    @Transactional(readOnly = true)
    public List<CanonicalSuggestionResponse> suggest(User owner, String productId) {
        Business b = businessService.requireOwn(owner);
        Product p = productRepository.findById(productId)
                .filter(x -> x.getBusiness().getId().equals(b.getId()))
                .orElseThrow(() -> ApiException.notFound("Product not found"));
        return matching.suggest(p.getName()).stream()
                .map(s -> new CanonicalSuggestionResponse(s.canonicalId(), s.name(), s.score())).toList();
    }
}
