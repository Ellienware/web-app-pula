package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.User;
import com.momocircle.domain.WholesaleProduct;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.domain.enums.MatchClassification;
import com.momocircle.dto.StockDtos.CreateWholesaleProductRequest;
import com.momocircle.dto.StockDtos.UpdateWholesaleProductRequest;
import com.momocircle.dto.StockDtos.WholesaleProductResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.WholesaleProductRepository;
import com.momocircle.repository.CanonicalProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Manages a supplier's wholesale product catalogue (brief §12-13). Only businesses
 * with CAN_SUPPLY_BUSINESSES may create/edit/delete products here. Buyers (with
 * CAN_BUY_STOCK) may browse and view but not modify.
 */
@Service
public class WholesaleProductService {

    private final WholesaleProductRepository repo;
    private final BusinessService businessService;
    private final CanonicalProductRepository canonicalProductRepository;

    public WholesaleProductService(WholesaleProductRepository repo, BusinessService businessService,
                                   CanonicalProductRepository canonicalProductRepository) {
        this.repo = repo;
        this.businessService = businessService;
        this.canonicalProductRepository = canonicalProductRepository;
    }

    // ---- Supplier-facing (CAN_SUPPLY_BUSINESSES required) ----

    @Transactional
    public WholesaleProductResponse create(User supplier, CreateWholesaleProductRequest req) {
        Business business = requireSupplierBusiness(supplier);
        if (req.sku() != null && !req.sku().isBlank()) {
            if (repo.existsBySupplierBusinessIdAndSkuAndIdNot(business.getId(), req.sku().trim(), "")) {
                throw ApiException.conflict("A product with SKU '" + req.sku() + "' already exists in your catalogue");
            }
        }
        WholesaleProduct p = new WholesaleProduct();
        p.setSupplierBusiness(business);
        p.setName(req.name().trim());
        p.setDescription(req.description());
        p.setSku(req.sku() == null || req.sku().isBlank() ? null : req.sku().trim());
        p.setCategory(req.category());
        p.setPackSize(req.packSize());
        p.setMoq(req.moq());
        p.setPricePerPack(req.pricePerPack());
        String sellingMode = normalizeSellingMode(req.sellingMode());
        validateSellingPrices(sellingMode, req.pricePerPack(), req.pricePerUnit());
        p.setSellingMode(sellingMode);
        p.setPricePerUnit(req.pricePerUnit());
        p.setAvailableStock(req.availableStock());
        p.setImageUrls(validImages(req.imageUrls()));
        return toResponse(repo.save(p));
    }

    @Transactional
    public WholesaleProductResponse update(User supplier, String productId, UpdateWholesaleProductRequest req) {
        Business business = requireSupplierBusiness(supplier);
        WholesaleProduct p = repo.findByIdAndSupplierBusinessId(productId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Wholesale product not found"));
        if (req.name() != null) p.setName(req.name().trim());
        if (req.description() != null) p.setDescription(req.description());
        if (req.sku() != null) {
            String sku = req.sku().isBlank() ? null : req.sku().trim();
            if (sku != null && repo.existsBySupplierBusinessIdAndSkuAndIdNot(business.getId(), sku, productId)) {
                throw ApiException.conflict("A product with SKU '" + sku + "' already exists in your catalogue");
            }
            p.setSku(sku);
        }
        if (req.category() != null) p.setCategory(req.category());
        if (req.packSize() != null) p.setPackSize(req.packSize());
        if (req.moq() != null) p.setMoq(req.moq());
        if (req.pricePerPack() != null) p.setPricePerPack(req.pricePerPack());
        if (req.sellingMode() != null || req.pricePerUnit() != null || req.pricePerPack() != null) {
            String mode = normalizeSellingMode(req.sellingMode() == null ? p.getSellingMode() : req.sellingMode());
            BigDecimal packPrice = req.pricePerPack() == null ? p.getPricePerPack() : req.pricePerPack();
            BigDecimal unitPrice = req.pricePerUnit() == null ? p.getPricePerUnit() : req.pricePerUnit();
            validateSellingPrices(mode, packPrice, unitPrice);
            p.setSellingMode(mode);
            if (req.pricePerUnit() != null) p.setPricePerUnit(req.pricePerUnit());
        }
        if (req.availableStock() != null) p.setAvailableStock(req.availableStock());
        if (req.imageUrls() != null) p.setImageUrls(validImages(req.imageUrls()));
        if (req.active() != null) p.setActive(req.active());
        return toResponse(repo.save(p));
    }

    @Transactional
    public void delete(User supplier, String productId) {
        Business business = requireSupplierBusiness(supplier);
        WholesaleProduct p = repo.findByIdAndSupplierBusinessId(productId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Wholesale product not found"));
        // Soft-delete: deactivate rather than hard-delete so historical stock-order items retain their FK reference.
        p.setActive(false);
        repo.save(p);
    }

    @Transactional(readOnly = true)
    public Page<WholesaleProductResponse> listMine(User supplier, Pageable pageable) {
        Business business = requireSupplierBusiness(supplier);
        return repo.findBySupplierBusinessIdAndActiveTrue(business.getId(), pageable).map(this::toResponse);
    }

    /**
     * Explicit supplier-controlled canonical linking. No fuzzy or automatic link is performed.
     * Passing a null canonical ID unlinks the listing.
     */
    @Transactional
    public WholesaleProductResponse linkCanonicalProduct(User supplier, String productId,
                                                          String canonicalProductId,
                                                          MatchClassification classification) {
        Business business = requireSupplierBusiness(supplier);
        WholesaleProduct product = repo.findByIdAndSupplierBusinessId(productId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Wholesale product not found"));

        if (canonicalProductId == null || canonicalProductId.isBlank()) {
            product.setCanonicalProduct(null);
            product.setMatchClassification(MatchClassification.UNIQUE_PRODUCT);
        } else {
            if (classification == MatchClassification.UNIQUE_PRODUCT) {
                throw ApiException.badRequest("Linked listings must be classified EXACT_MATCH or SIMILAR_ALTERNATIVE");
            }
            CanonicalProduct canonical = canonicalProductRepository.findById(canonicalProductId)
                    .orElseThrow(() -> ApiException.notFound("Canonical product not found"));
            product.setCanonicalProduct(canonical);
            product.setMatchClassification(classification == null
                    ? MatchClassification.EXACT_MATCH : classification);
        }
        return toResponse(repo.save(product));
    }

    /** Search existing canonical products for an explicit supplier choice; never auto-links. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.momocircle.dto.MarketDtos.CanonicalProductResponse>
    searchCanonicalProducts(String query, org.springframework.data.domain.Pageable pageable) {
        if (query == null || query.isBlank()) {
            return canonicalProductRepository.findAll(pageable).map(this::canonicalResponse);
        }
        return canonicalProductRepository.search(query.trim(), pageable).map(this::canonicalResponse);
    }

    private com.momocircle.dto.MarketDtos.CanonicalProductResponse canonicalResponse(CanonicalProduct c) {
        return new com.momocircle.dto.MarketDtos.CanonicalProductResponse(
                c.getId(), c.getName(), c.getBrand(), c.getCategory(), c.getSubcategory(),
                c.getSize(), c.getUnit(), c.getBarcode(), c.getCreatedAt());
    }

    // ---- Buyer-facing (CAN_BUY_STOCK required, or public for basic browse) ----

    @Transactional(readOnly = true)
    public Page<WholesaleProductResponse> browse(String category, String q, Pageable pageable) {
        String norm = (category == null || category.isBlank()) ? null : category;
        if (q != null && !q.isBlank()) {
            return repo.search(q.trim(), pageable).map(this::toResponse);
        }
        return repo.browse(norm, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<WholesaleProductResponse> listForSupplier(String supplierBusinessId, Pageable pageable) {
        return repo.findBySupplierBusinessIdAndActiveTrue(supplierBusinessId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public WholesaleProductResponse get(String productId) {
        return toResponse(repo.findById(productId)
                .filter(WholesaleProduct::isActive)
                .orElseThrow(() -> ApiException.notFound("Wholesale product not found")));
    }

    // ---- Internals ----

    public WholesaleProduct requireById(String id) {
        return repo.findById(id)
                .filter(WholesaleProduct::isActive)
                .orElseThrow(() -> ApiException.notFound("Wholesale product not found"));
    }


    private static java.util.List<String> validImages(java.util.List<String> raw) {
        try {
            return com.momocircle.services.ServiceInput.imageUrls(raw, 4);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private String normalizeSellingMode(String raw) {
        String mode = raw == null || raw.isBlank() ? "BULK_ONLY" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("INDIVIDUAL_ONLY", "BULK_ONLY", "BOTH").contains(mode)) {
            throw ApiException.badRequest("Selling mode must be INDIVIDUAL_ONLY, BULK_ONLY, or BOTH");
        }
        return mode;
    }

    private void validateSellingPrices(String mode, java.math.BigDecimal packPrice, java.math.BigDecimal unitPrice) {
        if ((mode.equals("BULK_ONLY") || mode.equals("BOTH")) && packPrice == null) {
            throw ApiException.badRequest("A price per pack is required for bulk selling");
        }
        if ((mode.equals("INDIVIDUAL_ONLY") || mode.equals("BOTH"))
                && (unitPrice == null || unitPrice.signum() < 0)) {
            throw ApiException.badRequest("A non-negative price per unit is required for individual selling");
        }
        if (packPrice != null && packPrice.signum() < 0) {
            throw ApiException.badRequest("Price per pack cannot be negative");
        }
    }

    private Business requireSupplierBusiness(User supplier) {
        Business business = businessService.requireOwn(supplier);
        if (!business.hasCapability(BusinessCapability.CAN_SUPPLY_BUSINESSES)) {
            throw ApiException.forbidden("Your business needs the 'supply other businesses' capability to manage a wholesale catalogue");
        }
        return business;
    }

    WholesaleProductResponse toResponse(WholesaleProduct p) {
        return new WholesaleProductResponse(
                p.getId(),
                p.getSupplierBusiness().getId(),
                p.getSupplierBusiness().getName(),
                p.getName(),
                p.getDescription(),
                p.getSku(),
                p.getCategory(),
                p.getPackSize(),
                p.getMoq(),
                p.getPricePerPack(),
                p.getCurrency(),
                p.getAvailableStock(),
                p.getSellingMode(),
                p.getPricePerUnit(),
                p.isActive(),
                p.getCreatedAt(),
                p.getImageUrls(),
                p.getCanonicalProduct() == null ? null : p.getCanonicalProduct().getId(),
                p.getCanonicalProduct() == null ? null : p.getCanonicalProduct().getName(),
                p.getMatchClassification()
        );
    }
}
