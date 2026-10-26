package com.momocircle.service;

import com.momocircle.branch.BranchStatus;
import com.momocircle.branch.BranchStockMath;
import com.momocircle.domain.BranchProductStock;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.Product;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.dto.BranchOpsDtos.DirectoryBranch;
import com.momocircle.dto.BranchOpsDtos.DirectoryProduct;
import com.momocircle.dto.BranchStockDtos.AvailabilityItem;
import com.momocircle.dto.BranchStockDtos.BranchAvailabilityView;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BranchProductStockRepository;
import com.momocircle.repository.BusinessBranchRepository;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Customer-facing branch directory. Public routes expose ONLY active branches of an active business and ONLY what the business entered for
 * customers (name, address, hours, contact). Stock is reported as in/out of stock, never as a quantity, and nothing is verified by PULA.
 * Read-only: viewing never changes stock.
 */
@Service
public class BranchDirectoryService {

    private final BusinessRepository businesses;
    private final BusinessBranchRepository branches;
    private final BranchProductStockRepository stock;
    private final ProductRepository products;

    public BranchDirectoryService(BusinessRepository businesses, BusinessBranchRepository branches,
                                  BranchProductStockRepository stock, ProductRepository products) {
        this.businesses = businesses;
        this.branches = branches;
        this.stock = stock;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<DirectoryBranch> list(String businessId) {
        Business b = publicBusiness(businessId);
        return branches.findByBusinessIdOrderByCreatedAtAsc(b.getId()).stream()
                .filter(br -> br.getStatus() == BranchStatus.ACTIVE)
                .map(br -> new DirectoryBranch(br.getId(), br.getName(), br.getBranchCode(), br.getAddress(), br.getArea(), br.getCity(),
                        br.getProvince(), br.getPostalCode(), br.getLatitude(), br.getLongitude(), br.getPhone(), br.getEmail(),
                        List.copyOf(br.getOperatingHours()), br.isTemporarilyClosed(), br.getBrandName(), br.getOperatingModel().name(),
                        br.getOperatorName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DirectoryProduct> products(String businessId, String branchId) {
        Business b = publicBusiness(businessId);
        if (b.getBusinessType().isFood()) return List.of(); // food menus are served by the food endpoints
        BusinessBranch br = activeBranch(b, branchId);
        List<DirectoryProduct> out = new ArrayList<>();
        for (BranchProductStock row : stock.findByBranchIdOrderByCreatedAtAsc(br.getId())) {
            Product p = row.getProduct();
            if (!row.isAvailable() || p.getStatus() == ProductStatus.ARCHIVED) continue;
            out.add(new DirectoryProduct(p.getId(), p.getName(), p.getCurrency(),
                    BranchStockMath.unitPrice(p.getPrice(), row.getPriceOverride()), row.getQuantity() > 0));
        }
        return out;
    }

    /** For a basket: which branches could fill all of it right now, and at what total. Authenticated; the cart picks the branch from this. */
    @Transactional(readOnly = true)
    public List<BranchAvailabilityView> check(String businessId, List<AvailabilityItem> items) {
        Business b = publicBusiness(businessId);
        if (b.getBusinessType().isFood()) throw ApiException.badRequest("Food orders choose a branch through the food checkout");
        List<BranchAvailabilityView> out = new ArrayList<>();
        for (BusinessBranch br : branches.findByBusinessIdOrderByCreatedAtAsc(b.getId())) {
            if (br.getStatus() != BranchStatus.ACTIVE || br.isTemporarilyClosed()) continue;
            List<String> problems = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            String currency = null;
            for (AvailabilityItem it : items) {
                Product p = products.findById(it.productId()).filter(x -> x.getBusiness().getId().equals(b.getId())).orElse(null);
                if (p == null) { problems.add("A product in your basket is not sold by this seller"); continue; }
                currency = p.getCurrency();
                BranchProductStock row = stock.findByBranchIdAndProductId(br.getId(), p.getId()).orElse(null);
                if (row == null || !row.isAvailable()) { problems.add(p.getName() + " is not sold at this branch"); continue; }
                if (row.getQuantity() < it.quantity()) { problems.add("Not enough " + p.getName() + " at this branch"); continue; }
                total = total.add(BranchStockMath.unitPrice(p.getPrice(), row.getPriceOverride()).multiply(BigDecimal.valueOf(it.quantity())));
            }
            out.add(new BranchAvailabilityView(br.getId(), br.getName(), br.getCity(), br.getArea(), problems.isEmpty(),
                    problems.isEmpty() ? total : null, currency, problems));
        }
        return out;
    }

    private Business publicBusiness(String businessId) {
        return businesses.findById(businessId == null ? "" : businessId).filter(Business::isActive)
                .orElseThrow(() -> ApiException.notFound("Business not found"));
    }

    private BusinessBranch activeBranch(Business b, String branchId) {
        return branches.findById(branchId == null ? "" : branchId)
                .filter(x -> x.getBusiness().getId().equals(b.getId()) && x.getStatus() == BranchStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
    }
}
