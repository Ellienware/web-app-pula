package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.WholesaleProduct;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.dto.StockDtos.CreateWholesaleProductRequest;
import com.momocircle.dto.StockDtos.UpdateWholesaleProductRequest;
import com.momocircle.dto.StockDtos.WholesaleProductResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.WholesaleProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public WholesaleProductService(WholesaleProductRepository repo, BusinessService businessService) {
        this.repo = repo;
        this.businessService = businessService;
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
        p.setAvailableStock(req.availableStock());
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
        if (req.availableStock() != null) p.setAvailableStock(req.availableStock());
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
                p.isActive(),
                p.getCreatedAt()
        );
    }
}
