package com.momocircle.service;

import com.momocircle.branch.BranchMovementType;
import com.momocircle.branch.BranchPermission;
import com.momocircle.branch.BranchStatus;
import com.momocircle.branch.BranchStockMath;
import com.momocircle.domain.BranchProductStock;
import com.momocircle.domain.BranchStockMovement;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.Order;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.dto.BranchStockDtos.MoveRequest;
import com.momocircle.dto.BranchStockDtos.MovementView;
import com.momocircle.dto.BranchStockDtos.SetItemRequest;
import com.momocircle.dto.BranchStockDtos.StockItemView;
import com.momocircle.dto.BranchStockDtos.TransferRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BranchProductStockRepository;
import com.momocircle.repository.BranchStockMovementRepository;
import com.momocircle.repository.ProductRepository;
import com.momocircle.service.BranchManagementService.Access;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Branch stock, availability and prices for NON-FOOD products (food keeps BranchItemAvailability on the food API).
 *
 * <p>Model: {@code products.stock} is the business's CENTRAL pool and works exactly as before for any order that names no branch.
 * A branch holds an explicit allocation (branch_product_stock) moved out of the pool (owner only), back (owner only) or between
 * branches (needs MANAGE_STOCK at BOTH branches). Every change writes an append-only movement. Stock is never created or destroyed by a move,
 * and nothing changes as a side effect of reading. Row locks (always taken in a fixed order) stop two requests spending the same units.
 *
 * <p>This is simulated-environment bookkeeping of the business's own stock. It does not trigger any supplier order, payment or payout.
 */
@Service
public class BranchStockService {

    private final BranchManagementService branches;
    private final com.momocircle.repository.BusinessBranchRepository branchRepo;
    private final BranchProductStockRepository stock;
    private final BranchStockMovementRepository moves;
    private final ProductRepository products;
    private final AuditLogService audit;

    public BranchStockService(BranchManagementService branches, com.momocircle.repository.BusinessBranchRepository branchRepo,
                              BranchProductStockRepository stock, BranchStockMovementRepository moves, ProductRepository products,
                              AuditLogService audit) {
        this.branches = branches;
        this.branchRepo = branchRepo;
        this.stock = stock;
        this.moves = moves;
        this.products = products;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<StockItemView> list(User caller, String businessId, String branchId) {
        Access a = branches.requireAccess(caller, businessId, branchId);
        requireNonFood(a.business());
        List<StockItemView> out = new ArrayList<>();
        for (Product p : products.findByBusinessIdOrderByNameAsc(a.business().getId())) {
            if (p.isService() || p.getStatus() == ProductStatus.ARCHIVED) continue;
            BranchProductStock row = stock.findByBranchIdAndProductId(a.branch().getId(), p.getId()).orElse(null);
            out.add(toView(p, row, a.isOwner()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<MovementView> movements(User caller, String businessId, String branchId, int limit) {
        Access a = branches.requireAccess(caller, businessId, branchId);
        if (!a.has(BranchPermission.MANAGE_STOCK)) throw ApiException.forbidden("You do not have permission to see stock movements");
        return moves.findForBranch(a.branch().getId(), PageRequest.of(0, Math.max(1, Math.min(limit, 200)))).stream().map(this::toView).toList();
    }

    // ------------------------------------------------------------------ availability / price

    @Transactional
    public StockItemView setItem(User caller, String businessId, String branchId, String productId, SetItemRequest req) {
        Access a = branches.requireAccess(caller, businessId, branchId);
        requireNonFood(a.business());
        if (!a.has(BranchPermission.MANAGE_PRICES)) throw ApiException.forbidden("You do not have permission to change prices or availability");
        requireWritable(a.branch());
        Product p = ownProduct(a.business(), productId, false);
        BranchProductStock row = lockOrCreate(a.branch(), p);
        if (req.available() != null) row.setAvailable(req.available());
        if (req.clearPriceOverride()) {
            row.setPriceOverride(null);
        } else if (req.priceOverride() != null) {
            try {
                BranchStockMath.unitPrice(p.getPrice(), req.priceOverride());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(e.getMessage());
            }
            row.setPriceOverride(req.priceOverride().setScale(2, RoundingMode.HALF_UP));
        }
        BranchProductStock saved = stock.save(row);
        audit.record(caller, "BRANCH_ITEM_UPDATED", "BusinessBranch", a.branch().getId(),
                "product=" + p.getId() + " available=" + saved.isAvailable() + " price=" + saved.getPriceOverride());
        return toView(p, saved, a.isOwner());
    }

    // ------------------------------------------------------------------ stock moves

    /** Central pool -> branch. Owner only: the central pool belongs to the business, not to a branch. */
    @Transactional
    public StockItemView allocate(User caller, String businessId, String branchId, MoveRequest req) {
        Access a = branches.requireAccess(caller, businessId, branchId);
        requireNonFood(a.business());
        requireOwner(a);
        requireActive(a.branch());
        Product p = lockedProduct(a.business(), req.productId());
        BranchProductStock row = lockOrCreate(a.branch(), p);
        BranchStockMath.Pair r = apply(() -> BranchStockMath.allocate(p.getStock(), row.getQuantity(), req.quantity()));
        p.setStock(r.from());
        row.setQuantity(r.to());
        products.save(p);
        stock.save(row);
        record(a.business(), p.getId(), BranchMovementType.ALLOCATE, null, a.branch().getId(), req.quantity(), null, caller, req.note());
        audit.record(caller, "BRANCH_STOCK_ALLOCATED", "BusinessBranch", a.branch().getId(), "product=" + p.getId() + " qty=" + req.quantity());
        return toView(p, row, true);
    }

    /** Branch -> central pool. Owner only. Also the way to empty a branch before it is closed. */
    @Transactional
    public StockItemView returnToCentral(User caller, String businessId, String branchId, MoveRequest req) {
        Access a = branches.requireAccess(caller, businessId, branchId);
        requireNonFood(a.business());
        requireOwner(a);
        Product p = lockedProduct(a.business(), req.productId());
        BranchProductStock row = stock.findForUpdate(a.branch().getId(), p.getId())
                .orElseThrow(() -> ApiException.badRequest("This branch holds no stock of that product"));
        BranchStockMath.Pair r = apply(() -> BranchStockMath.returnToCentral(row.getQuantity(), p.getStock(), req.quantity()));
        row.setQuantity(r.from());
        p.setStock(r.to());
        if (p.getStatus() == ProductStatus.OUT_OF_STOCK && p.getStock() > 0) p.setStatus(ProductStatus.ACTIVE);
        stock.save(row);
        products.save(p);
        record(a.business(), p.getId(), BranchMovementType.RETURN_TO_CENTRAL, a.branch().getId(), null, req.quantity(), null, caller, req.note());
        audit.record(caller, "BRANCH_STOCK_RETURNED", "BusinessBranch", a.branch().getId(), "product=" + p.getId() + " qty=" + req.quantity());
        return toView(p, row, true);
    }

    /** Branch -> branch of the SAME business. Needs MANAGE_STOCK at both ends (the owner has it everywhere). */
    @Transactional
    public StockItemView transfer(User caller, String businessId, String fromBranchId, TransferRequest req) {
        Access from = branches.requireAccess(caller, businessId, fromBranchId);
        requireNonFood(from.business());
        Access to = branches.requireAccess(caller, businessId, req.toBranchId()); // a branch of another business is simply "not found"
        if (from.branch().getId().equals(to.branch().getId())) throw ApiException.badRequest("Choose a different destination branch");
        if (!from.has(BranchPermission.MANAGE_STOCK) || !to.has(BranchPermission.MANAGE_STOCK)) {
            throw ApiException.forbidden("Moving stock needs the stock permission at both branches");
        }
        if (from.branch().getStatus() == BranchStatus.CLOSED && !from.isOwner()) throw ApiException.conflict("Only the owner can move stock out of a closed branch");
        requireActive(to.branch());
        Product p = ownProduct(from.business(), req.productId(), false);
        // Fixed lock order (by branch id) so two opposite transfers cannot deadlock.
        boolean fromFirst = from.branch().getId().compareTo(to.branch().getId()) < 0;
        BranchProductStock src, dst;
        if (fromFirst) {
            src = stock.findForUpdate(from.branch().getId(), p.getId()).orElseThrow(() -> ApiException.badRequest("The source branch holds no stock of that product"));
            dst = lockOrCreate(to.branch(), p);
        } else {
            dst = lockOrCreate(to.branch(), p);
            src = stock.findForUpdate(from.branch().getId(), p.getId()).orElseThrow(() -> ApiException.badRequest("The source branch holds no stock of that product"));
        }
        BranchStockMath.Pair r = apply(() -> BranchStockMath.transfer(src.getQuantity(), dst.getQuantity(), req.quantity()));
        src.setQuantity(r.from());
        dst.setQuantity(r.to());
        stock.save(src);
        stock.save(dst);
        record(from.business(), p.getId(), BranchMovementType.TRANSFER, from.branch().getId(), to.branch().getId(), req.quantity(), null, caller, req.note());
        audit.record(caller, "BRANCH_STOCK_TRANSFERRED", "BusinessBranch", from.branch().getId(),
                "product=" + p.getId() + " qty=" + req.quantity() + " to=" + to.branch().getId());
        return toView(p, src, from.isOwner());
    }

    // ------------------------------------------------------------------ used by checkout (same transaction as the order)

    /** Validates a customer's branch choice for a non-food order. Null branchId means "no branch" (central stock, as before). */
    @Transactional(readOnly = true)
    public BusinessBranch resolveForCheckout(Business business, String branchId) {
        if (branchId == null || branchId.isBlank()) return null;
        if (business.getBusinessType().isFood()) return null; // food has its own branch rules in FoodCheckoutService
        BusinessBranch br = branchRepo.findById(branchId)
                .filter(x -> x.getBusiness().getId().equals(business.getId()))
                .orElseThrow(() -> ApiException.badRequest("That branch does not belong to this seller"));
        if (br.getStatus() != BranchStatus.ACTIVE || br.isTemporarilyClosed()) {
            throw ApiException.conflict("This branch is not taking orders right now");
        }
        return br;
    }

    /**
     * A branch order's price can differ from the catalogue price the customer saw in the cart, so the total they reviewed is REQUIRED and
     * must match what the server computed; otherwise checkout is refused instead of charging an amount they have not seen.
     */
    public void requireExpectedTotal(BigDecimal computed, BigDecimal expected, String currency) {
        if (expected == null) throw ApiException.badRequest("Please review the branch total before paying.");
        if (computed.compareTo(expected) != 0) {
            throw ApiException.conflict("The total for this branch has changed to " + currency + " " + computed.toPlainString()
                    + ". Please review your order again.");
        }
    }

    /** Unit price at a branch (override or catalogue). The item must be sold there; quantity is checked when it is reserved. */
    @Transactional(readOnly = true)
    public BigDecimal unitPriceAt(BusinessBranch branch, Product product) {
        BranchProductStock row = stock.findByBranchIdAndProductId(branch.getId(), product.getId()).orElse(null);
        if (row == null || !row.isAvailable()) {
            throw ApiException.badRequest("\"" + product.getName() + "\" is not available at this branch");
        }
        return BranchStockMath.unitPrice(product.getPrice(), row.getPriceOverride());
    }

    @Transactional
    public void reserveForOrder(BusinessBranch branch, Product product, int quantity, String orderId) {
        BranchProductStock row = stock.findForUpdate(branch.getId(), product.getId())
                .orElseThrow(() -> ApiException.badRequest("\"" + product.getName() + "\" is not available at this branch"));
        int after;
        try {
            after = BranchStockMath.reserve(row.getQuantity(), row.isAvailable(), quantity);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Not enough stock for \"" + product.getName() + "\" at this branch");
        }
        row.setQuantity(after);
        stock.save(row);
        BranchStockMovement m = movement(branch.getBusiness().getId(), product.getId(), BranchMovementType.SALE_RESERVE,
                branch.getId(), null, quantity, orderId, null, null);
        moves.save(m);
    }

    /** Compensation for a failed/cancelled order. Idempotent: at most one release per (order, product). */
    @Transactional
    public void releaseForOrder(Order order) {
        for (BranchStockMovement reserved : moves.findByOrderIdAndMovementType(order.getId(), BranchMovementType.SALE_RESERVE)) {
            if (moves.existsByOrderIdAndProductIdAndMovementType(order.getId(), reserved.getProductId(), BranchMovementType.SALE_RELEASE)) continue;
            BranchProductStock row = stock.findForUpdate(reserved.getFromBranchId(), reserved.getProductId()).orElse(null);
            if (row == null) continue;
            row.setQuantity(BranchStockMath.release(row.getQuantity(), reserved.getQuantity()));
            stock.save(row);
            moves.save(movement(reserved.getBusinessId(), reserved.getProductId(), BranchMovementType.SALE_RELEASE, null,
                    reserved.getFromBranchId(), reserved.getQuantity(), order.getId(), null, "order not completed"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void requireNonFood(Business b) {
        if (b.getBusinessType().isFood()) {
            throw ApiException.conflict("Food businesses manage branch availability and prices on the food menu");
        }
    }

    private void requireOwner(Access a) {
        if (!a.isOwner()) throw ApiException.forbidden("Only the business owner can move stock to or from the central pool");
    }

    private void requireWritable(BusinessBranch br) {
        if (br.getStatus() == BranchStatus.CLOSED) throw ApiException.conflict("A closed branch cannot be changed");
    }

    private void requireActive(BusinessBranch br) {
        if (br.getStatus() != BranchStatus.ACTIVE) throw ApiException.conflict("That branch is not active");
    }

    private Product ownProduct(Business b, String productId, boolean lock) {
        Product p = (lock ? products.findByIdForUpdate(productId == null ? "" : productId) : products.findById(productId == null ? "" : productId))
                .filter(x -> x.getBusiness().getId().equals(b.getId()))
                .orElseThrow(() -> ApiException.notFound("Product not found"));
        if (p.isService()) throw ApiException.badRequest("Services have no stock");
        return p;
    }

    private Product lockedProduct(Business b, String productId) {
        return ownProduct(b, productId, true);
    }

    /** Locks the row, creating it (quantity 0, sold here) the first time. A concurrent creator loses on the unique index and is asked to retry. */
    private BranchProductStock lockOrCreate(BusinessBranch br, Product p) {
        var existing = stock.findForUpdate(br.getId(), p.getId());
        if (existing.isPresent()) return existing.get();
        BranchProductStock row = new BranchProductStock();
        row.setBranch(br);
        row.setProduct(p);
        try {
            stock.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Another change to this product is in progress. Please try again.");
        }
        return stock.findForUpdate(br.getId(), p.getId()).orElse(row);
    }

    private static <T> T apply(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private void record(Business b, String productId, BranchMovementType type, String from, String to, int qty, String orderId, User actor, String note) {
        moves.save(movement(b.getId(), productId, type, from, to, qty, orderId, actor == null ? null : actor.getId(), note));
    }

    private BranchStockMovement movement(String businessId, String productId, BranchMovementType type, String from, String to, int qty,
                                         String orderId, String actorId, String note) {
        BranchStockMovement m = new BranchStockMovement();
        m.setBusinessId(businessId);
        m.setProductId(productId);
        m.setMovementType(type);
        m.setFromBranchId(from);
        m.setToBranchId(to);
        m.setQuantity(qty);
        m.setOrderId(orderId);
        m.setActorUserId(actorId);
        m.setNote(note == null || note.isBlank() ? null : note.trim());
        return m;
    }

    private StockItemView toView(Product p, BranchProductStock row, boolean owner) {
        BigDecimal override = row == null ? null : row.getPriceOverride();
        return new StockItemView(p.getId(), p.getName(), p.getCurrency(), p.getPrice(), row != null && row.isAvailable(), override,
                BranchStockMath.unitPrice(p.getPrice(), override), row == null ? 0 : row.getQuantity(), owner ? p.getStock() : null);
    }

    private MovementView toView(BranchStockMovement m) {
        return new MovementView(m.getId(), m.getMovementType(), m.getProductId(), m.getFromBranchId(), m.getToBranchId(),
                m.getQuantity(), m.getOrderId(), m.getActorUserId(), m.getNote(), m.getCreatedAt());
    }
}
