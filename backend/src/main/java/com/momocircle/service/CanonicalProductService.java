package com.momocircle.service;

import com.momocircle.domain.CanonicalProduct;
import com.momocircle.dto.MarketDtos.CanonicalProductResponse;
import com.momocircle.dto.MarketDtos.CreateCanonicalProductRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CanonicalProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Any authenticated user can search and create canonical products — there is
 * no separate curator role in this slice, and the search-before-create UX
 * (see the frontend's canonical-product picker) is the only de-duplication
 * mechanism today. See CanonicalProduct's own javadoc for why that's an
 * intentional, documented limitation rather than an oversight.
 */
@Service
public class CanonicalProductService {

    private final CanonicalProductRepository canonicalProductRepository;

    public CanonicalProductService(CanonicalProductRepository canonicalProductRepository) {
        this.canonicalProductRepository = canonicalProductRepository;
    }

    @Transactional
    public CanonicalProductResponse create(CreateCanonicalProductRequest req) {
        if (req.barcode() != null && !req.barcode().isBlank()
                && canonicalProductRepository.findByBarcode(req.barcode()).isPresent()) {
            throw ApiException.conflict("A canonical product with this barcode already exists");
        }
        CanonicalProduct canonicalProduct = new CanonicalProduct();
        canonicalProduct.setName(req.name().trim());
        canonicalProduct.setBrand(req.brand());
        canonicalProduct.setCategory(req.category());
        canonicalProduct.setSubcategory(req.subcategory());
        canonicalProduct.setSize(req.size());
        canonicalProduct.setUnit(req.unit());
        canonicalProduct.setBarcode(req.barcode() == null || req.barcode().isBlank() ? null : req.barcode());
        return toResponse(canonicalProductRepository.save(canonicalProduct));
    }

    @Transactional(readOnly = true)
    public Page<CanonicalProductResponse> search(String term, Pageable pageable) {
        return canonicalProductRepository.search(term == null ? "" : term, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CanonicalProductResponse get(String id) {
        return toResponse(requireById(id));
    }

    public CanonicalProduct requireById(String id) {
        return canonicalProductRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Canonical product not found"));
    }

    private CanonicalProductResponse toResponse(CanonicalProduct c) {
        return new CanonicalProductResponse(
                c.getId(), c.getName(), c.getBrand(), c.getCategory(), c.getSubcategory(),
                c.getSize(), c.getUnit(), c.getBarcode(), c.getCreatedAt());
    }
}
