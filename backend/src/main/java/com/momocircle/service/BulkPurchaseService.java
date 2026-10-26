package com.momocircle.service;

import com.momocircle.bulk.BulkDeadlinePolicy;
import com.momocircle.bulk.BulkPricing;
import com.momocircle.bulk.BulkShortageAllocator;
import com.momocircle.domain.BulkAllocation;
import com.momocircle.domain.BulkGroupEvent;
import com.momocircle.domain.BulkPurchaseGroup;
import com.momocircle.domain.BulkTier;
import com.momocircle.domain.Business;
import com.momocircle.domain.SaveToBuyRequest;
import com.momocircle.domain.StockOrder;
import com.momocircle.domain.StockOrderItem;
import com.momocircle.domain.User;
import com.momocircle.domain.WholesaleProduct;
import com.momocircle.domain.WholesaleVolumeTier;
import com.momocircle.domain.enums.BulkAllocationStatus;
import com.momocircle.domain.enums.BulkGroupStatus;
import com.momocircle.domain.enums.BulkShortfallPolicy;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.RelatedOrderKind;
import com.momocircle.domain.enums.SaveToBuyVertical;
import com.momocircle.domain.enums.StockFulfilmentOption;
import com.momocircle.domain.enums.StockOrderStatus;
import com.momocircle.dto.BulkDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.PaymentOrchestrator;
import com.momocircle.repository.BulkAllocationRepository;
import com.momocircle.repository.BulkGroupEventRepository;
import com.momocircle.repository.BulkPurchaseGroupRepository;
import com.momocircle.repository.StockOrderRepository;
import com.momocircle.repository.WholesaleProductRepository;
import com.momocircle.repository.WholesaleVolumeTierRepository;
import com.momocircle.security.ServiceRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bulk-purchase allocation between businesses.
 *
 * Design rules (see docs/PHASE_6_BULK_PURCHASE_REPORT.md):
 *  - The group is a COORDINATION record. It never holds money. Each business gets its OWN {@link StockOrder}, created with
 *    the same fields as the existing stock checkout and paid through the existing {@code StockPaymentService}.
 *  - Every mutation of a group runs under a pessimistic lock on the group row, so joins/confirmations/withdrawals serialise.
 *  - The server computes every price. A client states the price it ACKNOWLEDGES; a mismatch is returned, never applied.
 *  - A business is never charged more than it agreed: when a change would raise its price it drops to PENDING_RECONFIRMATION.
 *  - Paid-ness is OBSERVED from the order's confirmed payment, never set by this class.
 *  - Refunds for stock orders are not supported by the existing payment code, so this class records "refund due" and moves the
 *    order to REFUND_PENDING. It never marks anything REFUNDED.
 */
@Service
public class BulkPurchaseService {

    private static final Logger log = LoggerFactory.getLogger(BulkPurchaseService.class);

    private static final Set<StockOrderStatus> PAID_OR_BEYOND = EnumSet.of(
            StockOrderStatus.PAID, StockOrderStatus.CONFIRMED, StockOrderStatus.PREPARING,
            StockOrderStatus.READY_FOR_COLLECTION, StockOrderStatus.OUT_FOR_DELIVERY, StockOrderStatus.DELIVERED);
    private static final Set<StockOrderStatus> IN_FULFILMENT = EnumSet.of(
            StockOrderStatus.PREPARING, StockOrderStatus.READY_FOR_COLLECTION,
            StockOrderStatus.OUT_FOR_DELIVERY, StockOrderStatus.DELIVERED);
    private static final Set<String> ISSUE_TYPES = Set.of("DAMAGED", "MISSING", "PARTIAL_DELIVERY");
    private static final Duration MAX_GROUP_LIFETIME = Duration.ofDays(90);

    private final BulkPurchaseGroupRepository groups;
    private final BulkAllocationRepository allocations;
    private final BulkGroupEventRepository events;
    private final WholesaleProductRepository products;
    private final WholesaleVolumeTierRepository tierRepo;
    private final StockOrderRepository orderRepo;
    private final StockOrderService stockOrders;
    private final BusinessService businessService;
    private final ServiceRateLimiter limiter;

    /** Optional, exactly like the existing stock checkout: bulk purchasing works without Save-to-Buy being wired. */
    @Autowired(required = false)
    private SaveToBuyService saveToBuy;
    /** Optional: only used to tell the UI whether the market payment mode is the sandbox. */
    @Autowired(required = false)
    private PaymentOrchestrator orchestrator;

    public BulkPurchaseService(BulkPurchaseGroupRepository groups, BulkAllocationRepository allocations,
                               BulkGroupEventRepository events, WholesaleProductRepository products,
                               WholesaleVolumeTierRepository tierRepo, StockOrderRepository orderRepo,
                               StockOrderService stockOrders, BusinessService businessService, ServiceRateLimiter limiter) {
        this.groups = groups;
        this.allocations = allocations;
        this.events = events;
        this.products = products;
        this.tierRepo = tierRepo;
        this.orderRepo = orderRepo;
        this.stockOrders = stockOrders;
        this.businessService = businessService;
        this.limiter = limiter;
    }

    // =====================================================================================================
    // Supplier-configured volume tiers
    // =====================================================================================================

    @Transactional(readOnly = true)
    public List<TierView> tiers(String productId) {
        products.findById(productId).filter(WholesaleProduct::isActive)
                .orElseThrow(() -> ApiException.notFound("Wholesale product not found"));
        return tierRepo.findByWholesaleProductIdOrderByMinPacksAsc(productId).stream()
                .map(t -> new TierView(t.getMinPacks(), t.getPricePerPack())).toList();
    }

    /** Only the product's own supplier may define its volume prices. Existing groups keep their snapshot. */
    @Transactional
    public List<TierView> setTiers(User supplierUser, String productId, SetTiersRequest req) {
        Business supplier = requireSupplier(supplierUser);
        WholesaleProduct p = products.findById(productId)
                .filter(x -> x.getSupplierBusiness().getId().equals(supplier.getId()))
                .orElseThrow(() -> ApiException.notFound("Wholesale product not found"));
        List<BulkPricing.Tier> ladder;
        try {
            ladder = BulkPricing.validateLadder(p.getPricePerPack(), req.tiers().stream()
                    .map(t -> new BulkPricing.Tier(t.minPacks(), BulkPricing.money(t.pricePerPack()))).toList());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        tierRepo.deleteByWholesaleProductId(productId);
        tierRepo.flush();
        for (BulkPricing.Tier t : ladder) {
            WholesaleVolumeTier row = new WholesaleVolumeTier();
            row.setWholesaleProduct(p);
            row.setMinPacks(t.minPacks());
            row.setPricePerPack(t.pricePerPack());
            tierRepo.save(row);
        }
        return ladder.stream().map(t -> new TierView(t.minPacks(), t.pricePerPack())).toList();
    }

    // =====================================================================================================
    // Group lifecycle (organiser)
    // =====================================================================================================

    @Transactional
    public GroupDetail createGroup(User user, CreateGroupRequest req) {
        Business creator = requireBuyer(user);
        writeLimit(user);
        WholesaleProduct p = products.findById(req.wholesaleProductId()).filter(WholesaleProduct::isActive)
                .orElseThrow(() -> ApiException.notFound("Wholesale product not found"));
        Business supplier = p.getSupplierBusiness();
        if (!supplier.isActive() || !supplier.hasCapability(BusinessCapability.CAN_SUPPLY_BUSINESSES)) {
            throw ApiException.badRequest("That supplier is not currently able to supply other businesses");
        }
        if (supplier.getId().equals(creator.getId())) {
            throw ApiException.badRequest("A supplier cannot organise a group to buy from itself");
        }
        if (req.targetPacks() < p.getMoq()) {
            throw ApiException.badRequest("The target must be at least the supplier's minimum order of " + p.getMoq() + " pack(s)");
        }
        Instant now = Instant.now();
        if (!req.deadline().isAfter(now.plus(Duration.ofHours(1)))) {
            throw ApiException.badRequest("The deadline must be at least one hour from now");
        }
        if (req.deadline().isAfter(now.plus(MAX_GROUP_LIFETIME))) {
            throw ApiException.badRequest("The deadline cannot be more than 90 days away");
        }
        if (!req.allowSelfCollection() && !req.allowSupplierDelivery() && !req.allowPulaRide()) {
            throw ApiException.badRequest("Allow at least one collection or delivery option");
        }

        BulkPurchaseGroup g = new BulkPurchaseGroup();
        g.setCreatorBusiness(creator);
        g.setSupplierBusiness(supplier);
        g.setWholesaleProduct(p);
        g.setName(req.name().trim());
        g.setDescription(req.description());
        g.setProcurementReference("BPG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        g.setCurrency(p.getCurrency());
        g.setBasePricePerPack(BulkPricing.money(p.getPricePerPack()));
        g.setPackSizeSnapshot(p.getPackSize());
        g.setMoqPacks(p.getMoq());
        g.setTargetPacks(req.targetPacks());
        g.setDeadline(req.deadline());
        g.setShortfallPolicy(req.shortfallPolicy() != null ? req.shortfallPolicy() : BulkShortfallPolicy.CANCEL);
        if (req.extensionHours() != null) g.setExtensionHours(req.extensionHours());
        g.setAllowSelfCollection(req.allowSelfCollection());
        g.setAllowSupplierDelivery(req.allowSupplierDelivery());
        g.setAllowPulaRide(req.allowPulaRide());
        g.setCollectionNote(req.collectionNote());
        // Tiers are a snapshot of what the supplier has configured. Nothing is invented.
        for (WholesaleVolumeTier t : tierRepo.findByWholesaleProductIdOrderByMinPacksAsc(p.getId())) {
            g.getTiers().add(new BulkTier(t.getMinPacks(), BulkPricing.money(t.getPricePerPack())));
        }
        groups.save(g);
        event(g, user, null, "GROUP_CREATED", "Group created by " + creator.getName(), g.getTargetPacks(), null);
        return detail(g, creator);
    }

    @Transactional
    public GroupDetail updateGroup(User user, String groupId, UpdateGroupRequest req) {
        Business me = requireBuyer(user);
        writeLimit(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        requireCreator(g, me);
        if (g.getStatus().isTerminal() || g.getStatus().ordinal() > BulkGroupStatus.CONFIRMATION.ordinal()) {
            throw ApiException.conflict("This group can no longer be edited");
        }
        if (req.name() != null && !req.name().isBlank()) g.setName(req.name().trim());
        if (req.description() != null) g.setDescription(req.description());
        if (req.collectionNote() != null) g.setCollectionNote(req.collectionNote());
        if (req.shortfallPolicy() != null) g.setShortfallPolicy(req.shortfallPolicy());
        if (req.deadline() != null) {
            if (g.getStatus() != BulkGroupStatus.DRAFT && !req.deadline().isAfter(g.getDeadline())) {
                throw ApiException.badRequest("A live group's deadline can only be moved later");
            }
            if (req.deadline().isAfter(Instant.now().plus(MAX_GROUP_LIFETIME))) {
                throw ApiException.badRequest("The deadline cannot be more than 90 days away");
            }
            g.setDeadline(req.deadline());
        }
        if (req.targetPacks() != null) {
            if (req.targetPacks() < g.getMoqPacks()) {
                throw ApiException.badRequest("The target must be at least the supplier's minimum order of " + g.getMoqPacks() + " pack(s)");
            }
            if (g.getStatus() == BulkGroupStatus.CONFIRMATION) {
                throw ApiException.conflict("The target cannot change during confirmation");
            }
            g.setTargetPacks(req.targetPacks());
        }
        groups.save(g);
        if (g.getStatus() != BulkGroupStatus.DRAFT) afterParticipationChange(g, user);
        event(g, user, null, "GROUP_UPDATED", "Group terms updated by the organiser", null, null);
        return detail(g, me);
    }

    @Transactional
    public GroupDetail open(User user, String groupId) {
        Business me = requireBuyer(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        requireCreator(g, me);
        if (g.getStatus() == BulkGroupStatus.OPEN) return detail(g, me);
        requireTransition(g, BulkGroupStatus.OPEN);
        if (!g.getDeadline().isAfter(Instant.now())) throw ApiException.badRequest("Set a deadline in the future before opening");
        g.setStatus(BulkGroupStatus.OPEN);
        groups.save(g);
        event(g, user, null, "GROUP_OPENED", "Group opened for participation", null, null);
        return detail(g, me);
    }

    /** Organiser closes joining and asks each business to explicitly confirm quantity and price. */
    @Transactional
    public GroupDetail startConfirmation(User user, String groupId) {
        Business me = requireBuyer(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        requireCreator(g, me);
        if (g.getStatus() == BulkGroupStatus.CONFIRMATION) return detail(g, me);
        if (g.getStatus() != BulkGroupStatus.OPEN && g.getStatus() != BulkGroupStatus.TARGET_REACHED) {
            throw ApiException.conflict("Confirmation can only start while the group is open");
        }
        int agg = activePacks(g);
        if (agg < g.getMoqPacks()) {
            throw ApiException.conflict("The supplier's minimum order of " + g.getMoqPacks() + " pack(s) has not been reached (" + agg + ")");
        }
        g.setStatus(BulkGroupStatus.CONFIRMATION);
        Instant floor = Instant.now().plus(Duration.ofHours(24));
        if (g.getDeadline().isBefore(floor)) g.setDeadline(floor); // businesses always get a real confirmation window
        groups.save(g);
        event(g, user, null, "CONFIRMATION_STARTED", "Joining closed; each business must now confirm its own quantity and price", agg, null);
        return detail(g, me);
    }

    @Transactional
    public FinaliseOutcome finalise(User user, String groupId, FinaliseRequest req) {
        Business me = requireBuyer(user);
        writeLimit(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        requireCreator(g, me);
        if (g.getFinalisedAt() != null) { // replay
            return new FinaliseOutcome("FINALISED", "This group has already been finalised", detail(g, me), 0, 0);
        }
        if (g.getStatus() != BulkGroupStatus.CONFIRMATION) {
            throw ApiException.conflict("Only a group in the confirmation stage can be finalised");
        }
        List<BulkAllocation> all = allocations.findByGroupIdOrderByCreatedAtAsc(g.getId());
        List<BulkAllocation> confirmed = all.stream().filter(a -> a.getStatus() == BulkAllocationStatus.CONFIRMED).toList();
        List<BulkAllocation> unconfirmed = all.stream().filter(a -> a.getStatus() == BulkAllocationStatus.JOINED
                || a.getStatus() == BulkAllocationStatus.PENDING_RECONFIRMATION).toList();

        if (!unconfirmed.isEmpty() && !req.dropUnconfirmed()) {
            return new FinaliseOutcome("UNCONFIRMED_PARTICIPANTS",
                    unconfirmed.size() + " business(es) have not confirmed. Wait for them, or finalise without them.",
                    detail(g, me), unconfirmed.size(), 0);
        }
        int agg = confirmed.stream().mapToInt(BulkAllocation::getConfirmedPacks).sum();
        if (agg < g.getMoqPacks()) {
            return new FinaliseOutcome("BELOW_MINIMUM", "Confirmed quantity (" + agg + ") is below the supplier's minimum order of "
                    + g.getMoqPacks(), detail(g, me), unconfirmed.size(), 0);
        }
        if (agg < g.getTargetPacks() && !req.acceptBelowTarget()) {
            return new FinaliseOutcome("BELOW_TARGET", "Confirmed quantity (" + agg + ") is below the target of " + g.getTargetPacks()
                    + ". Finalise explicitly below target to proceed at the price for " + agg + " packs.",
                    detail(g, me), unconfirmed.size(), 0);
        }

        if (!unconfirmed.isEmpty()) {
            // Dropping people can raise the price. Apply the drop, re-price, and let affected businesses re-confirm.
            for (BulkAllocation u : unconfirmed) {
                u.setStatus(BulkAllocationStatus.LAPSED);
                allocations.save(u);
                event(g, user, u.getBusiness().getId(), "LAPSED", "Not confirmed before finalisation; no obligation created", u.getRequestedPacks(), null);
            }
            afterParticipationChange(g, user);
            long pending = allocations.findByGroupIdOrderByCreatedAtAsc(g.getId()).stream()
                    .filter(a -> a.getStatus() == BulkAllocationStatus.PENDING_RECONFIRMATION).count();
            if (pending > 0) {
                return new FinaliseOutcome("RECONFIRMATION_REQUIRED",
                        "Removing unconfirmed businesses changes the price. " + pending + " business(es) must re-confirm before this can be finalised.",
                        detail(g, me), 0, (int) pending);
            }
        }

        BigDecimal price = BulkPricing.priceFor(g.getBasePricePerPack(), ladder(g), agg);
        List<BulkAllocation> stale = confirmed.stream().filter(a -> a.getAgreedMaxPricePerPack().compareTo(price) < 0).toList();
        if (!stale.isEmpty()) { // defensive: invariants should already have caught this
            for (BulkAllocation a : stale) markReconfirm(g, user, a, price);
            return new FinaliseOutcome("RECONFIRMATION_REQUIRED", stale.size() + " business(es) must re-confirm the new price",
                    detail(g, me), 0, stale.size());
        }

        WholesaleProduct p = products.findById(g.getWholesaleProduct().getId())
                .orElseThrow(() -> ApiException.notFound("Wholesale product not found"));
        if (!p.isActive() || p.getPricePerPack().compareTo(g.getBasePricePerPack()) != 0) {
            throw ApiException.conflict("The supplier has changed this product since the group was created. Create a new group on the current terms.");
        }

        // Pre-check Save-to-Buy references; a request that is no longer valid sends that business back to re-confirm.
        Map<String, SaveToBuyRequest> s2b = new LinkedHashMap<>();
        List<BulkAllocation> s2bProblems = new ArrayList<>();
        for (BulkAllocation a : confirmed) {
            if (a.getSaveToBuyRequestId() == null) continue;
            try {
                if (saveToBuy == null) throw ApiException.badRequest("Save-to-Buy is not available");
                SaveToBuyRequest r = saveToBuy.validateForCheckout(a.getBusiness().getOwner(), a.getSaveToBuyRequestId(),
                        SaveToBuyVertical.STOCK, null, checkoutKey(a));
                saveToBuy.requireWithinApproval(r, expectedTotal(g, a, price));
                s2b.put(a.getId(), r);
            } catch (ApiException e) {
                s2bProblems.add(a);
                a.setSaveToBuyRequestId(null);
                a.setStatus(BulkAllocationStatus.PENDING_RECONFIRMATION);
                allocations.save(a);
                event(g, user, a.getBusiness().getId(), "SAVE_TO_BUY_INVALID",
                        "The linked Save-to-Buy request can no longer be used: " + e.getMessage(), null, null);
            }
        }
        if (!s2bProblems.isEmpty()) {
            return new FinaliseOutcome("RECONFIRMATION_REQUIRED", s2bProblems.size()
                    + " business(es) must re-confirm because their Save-to-Buy request is no longer valid",
                    detail(g, me), 0, s2bProblems.size());
        }

        // ---- Commit: one real StockOrder per business ----
        for (BulkAllocation a : confirmed) {
            int packs = a.getConfirmedPacks();
            StockOrder order = createAllocationOrder(g, a, p, price, packs);
            a.setStockOrder(order);
            a.setFinalPricePerPack(price);
            a.setDiscountPerPack(BulkPricing.discountPerPack(g.getBasePricePerPack(), price));
            a.setSubtotal(order.getSubtotal());
            a.setDeliveryFee(order.getDeliveryFeeSnapshot());
            a.setTotal(order.getTotal());
            a.setAllocatedPacks(packs);
            a.setStatus(BulkAllocationStatus.ORDERED);
            allocations.save(a);
            SaveToBuyRequest r = s2b.get(a.getId());
            if (r != null) {
                order.setSaveToBuyRequestId(r.getId());
                orderRepo.save(order);
                saveToBuy.attachOrder(r, RelatedOrderKind.STOCK_ORDER, order.getId(), checkoutKey(a));
            }
            event(g, user, a.getBusiness().getId(), "ORDER_CREATED",
                    "Order created for " + packs + " pack(s) at " + price + " per pack; payable by this business only", packs, order.getTotal());
        }
        g.setFinalPricePerPack(price);
        g.setFinalAggregatePacks(agg);
        g.setFinalisedAt(Instant.now());
        g.setStatus(BulkGroupStatus.PAYMENT_PENDING);
        groups.save(g);
        event(g, user, null, "GROUP_FINALISED", "Finalised at " + agg + " pack(s), " + price + " per pack. Each business pays its own share.", agg, null);
        return new FinaliseOutcome("FINALISED", "Finalised. Each business now pays its own share.", detail(g, me), 0, 0);
    }

    /** Places the combined demand with the supplier once the paid allocations are settled (or the organiser explicitly proceeds). */
    @Transactional
    public PlaceOrderOutcome placeOrder(User user, String groupId, PlaceOrderRequest req) {
        Business me = requireBuyer(user);
        writeLimit(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        requireCreator(g, me);
        if (g.getStatus() != BulkGroupStatus.PAYMENT_PENDING) {
            if (g.getOrderPlacedAt() != null) return new PlaceOrderOutcome("ORDER_PLACED", "Already placed with the supplier", detail(g, me), 0);
            throw ApiException.conflict("The order can only be placed after the group is finalised");
        }
        syncPayments(g, user);
        if (g.getStatus() == BulkGroupStatus.ORDER_PLACED) {
            return new PlaceOrderOutcome("ORDER_PLACED", "Every share is paid; the order has been placed with the supplier", detail(g, me), 0);
        }
        List<BulkAllocation> live = allocations.findByGroupIdOrderByCreatedAtAsc(g.getId()).stream()
                .filter(a -> a.getStatus() == BulkAllocationStatus.ORDERED || a.getStatus() == BulkAllocationStatus.PAID).toList();
        List<BulkAllocation> unpaid = live.stream().filter(a -> a.getStatus() == BulkAllocationStatus.ORDERED).toList();
        if (live.isEmpty()) {
            cancelInternal(g, user, "No paid shares remain");
            return new PlaceOrderOutcome("CANCELLED", "Nothing was paid, so the group was cancelled", detail(g, me), 0);
        }
        if (!req.proceedWithPaidOnly()) {
            return new PlaceOrderOutcome("NOT_ALL_PAID", unpaid.size() + " business(es) have not paid yet", detail(g, me), unpaid.size());
        }
        int paidPacks = live.stream().filter(a -> a.getStatus() == BulkAllocationStatus.PAID).mapToInt(BulkAllocation::getConfirmedPacks).sum();
        if (paidPacks < g.getMoqPacks()) {
            return new PlaceOrderOutcome("BELOW_MINIMUM", "Paid quantity (" + paidPacks + ") is below the supplier's minimum order of "
                    + g.getMoqPacks(), detail(g, me), unpaid.size());
        }
        BigDecimal priceAtPaid = BulkPricing.priceFor(g.getBasePricePerPack(), ladder(g), paidPacks);
        if (priceAtPaid.compareTo(g.getFinalPricePerPack()) > 0) {
            // The agreed price depended on volume that is no longer paid for. Never silently charge more.
            return new PlaceOrderOutcome("PRICE_WOULD_INCREASE", "Proceeding with only the paid shares would lose the agreed volume price. "
                    + "Wait for the remaining payments or cancel the group.", detail(g, me), unpaid.size());
        }
        for (BulkAllocation a : unpaid) {
            StockOrder o = a.getStockOrder();
            requireNotInFlight(o);
            stockOrders.cancelAndRelease(o);
            a.setStatus(BulkAllocationStatus.CANCELLED);
            allocations.save(a);
            event(g, user, a.getBusiness().getId(), "DROPPED_UNPAID", "Share cancelled because it was not paid; stock released", a.getConfirmedPacks(), null);
        }
        g.setFinalAggregatePacks(paidPacks);
        markOrderPlaced(g, user, paidPacks, req.note());
        return new PlaceOrderOutcome("ORDER_PLACED", "Order placed for the paid shares (" + paidPacks + " packs)", detail(g, me), 0);
    }

    @Transactional
    public GroupDetail cancelGroup(User user, String groupId, CancelRequest req) {
        Business me = requireBuyer(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        requireCreator(g, me);
        if (g.getStatus() == BulkGroupStatus.CANCELLED) return detail(g, me);
        if (g.getStatus().isTerminal() || g.getStatus().ordinal() >= BulkGroupStatus.ORDER_PLACED.ordinal()) {
            throw ApiException.conflict("Once the order is with the supplier, only the supplier can cancel it");
        }
        cancelInternal(g, user, req != null && req.reason() != null ? req.reason() : "Cancelled by the organiser");
        return detail(g, me);
    }

    // =====================================================================================================
    // Participation (each business acts only on its OWN allocation)
    // =====================================================================================================

    @Transactional(readOnly = true)
    public Quote quote(User user, String groupId, int packs, StockFulfilmentOption method) {
        Business me = requireBuyer(user);
        BulkPurchaseGroup g = groups.findById(groupId).orElseThrow(() -> ApiException.notFound("Bulk-purchase group not found"));
        requireVisible(g, me);
        if (packs < 1) throw ApiException.badRequest("Quantity must be at least 1 pack");
        boolean counted = allocations.findByGroupIdAndBusinessId(g.getId(), me.getId())
                .map(a -> a.getStatus().isActiveInterest()).orElse(false);
        int existing = allocations.findByGroupIdAndBusinessId(g.getId(), me.getId())
                .filter(a -> a.getStatus().isActiveInterest()).map(BulkAllocation::getRequestedPacks).orElse(0);
        return buildQuote(g, packs, method != null ? method : StockFulfilmentOption.SELF_COLLECTION, counted ? existing : 0);
    }

    @Transactional
    public AllocationView join(User user, String groupId, JoinRequest req) {
        Business me = requireBuyer(user);
        writeLimit(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        requireVisible(g, me);
        if (g.getStatus() != BulkGroupStatus.OPEN && g.getStatus() != BulkGroupStatus.TARGET_REACHED) {
            throw ApiException.conflict("This group is not open for new participants");
        }
        if (!g.getDeadline().isAfter(Instant.now())) throw ApiException.conflict("The deadline for this group has passed");
        if (g.getSupplierBusiness().getId().equals(me.getId())) throw ApiException.badRequest("The supplier cannot join its own group");
        requireFulfilmentAllowed(g, req.fulfilmentMethod(), req.deliveryAddress());
        if (req.requestedPacks() > g.getTargetPacks()) {
            throw ApiException.badRequest("You cannot request more than the group's target of " + g.getTargetPacks() + " pack(s)");
        }

        BulkAllocation a = allocations.findByGroupIdAndBusinessId(g.getId(), me.getId()).orElse(null);
        if (a != null && a.getStatus().isActiveInterest()) {
            boolean same = a.getRequestedPacks() == req.requestedPacks() && a.getFulfilmentMethod() == req.fulfilmentMethod();
            if (same) return allocationView(a, true); // idempotent replay
            throw ApiException.conflict("You have already joined this group. Update your allocation instead.");
        }
        if (a != null && a.getStatus() != BulkAllocationStatus.WITHDRAWN && a.getStatus() != BulkAllocationStatus.LAPSED) {
            throw ApiException.conflict("Your participation in this group has already moved on (" + a.getStatus() + ")");
        }
        if (a == null) {
            a = new BulkAllocation();
            a.setGroup(g);
            a.setBusiness(me);
        }
        a.setStatus(BulkAllocationStatus.JOINED);
        a.setRequestedPacks(req.requestedPacks());
        a.setConfirmedPacks(null);
        a.setAgreedMaxPricePerPack(null);
        a.setAgreedAt(null);
        a.setFulfilmentMethod(req.fulfilmentMethod());
        a.setDeliveryAddress(req.fulfilmentMethod() == StockFulfilmentOption.SELF_COLLECTION ? null : req.deliveryAddress());
        a.setSaveToBuyRequestId(null);
        if (req.saveToBuyRequestId() != null && !req.saveToBuyRequestId().isBlank()) {
            if (saveToBuy == null) throw ApiException.badRequest("Save-to-Buy is not available");
            SaveToBuyRequest r = saveToBuy.validateForCheckout(user, req.saveToBuyRequestId(), SaveToBuyVertical.STOCK, null, null);
            a.setSaveToBuyRequestId(r.getId());
        }
        allocations.save(a);
        event(g, user, me.getId(), "JOINED", me.getName() + " joined with " + req.requestedPacks() + " pack(s)", req.requestedPacks(), null);
        afterParticipationChange(g, user);
        return allocationView(a, true);
    }

    @Transactional
    public AllocationView updateAllocation(User user, String groupId, UpdateAllocationRequest req) {
        Business me = requireBuyer(user);
        writeLimit(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        BulkAllocation a = requireOwnAllocation(g, me);
        if (!g.getStatus().acceptsParticipationChanges() || !a.getStatus().isActiveInterest()) {
            throw ApiException.conflict("Your allocation can no longer be changed");
        }
        boolean changed = false;
        if (req.requestedPacks() != null && req.requestedPacks() != a.getRequestedPacks()) {
            if (req.requestedPacks() > g.getTargetPacks()) {
                throw ApiException.badRequest("You cannot request more than the group's target of " + g.getTargetPacks() + " pack(s)");
            }
            a.setRequestedPacks(req.requestedPacks());
            changed = true;
        }
        StockFulfilmentOption method = req.fulfilmentMethod() != null ? req.fulfilmentMethod() : a.getFulfilmentMethod();
        String address = req.fulfilmentMethod() != null || req.deliveryAddress() != null ? req.deliveryAddress() : a.getDeliveryAddress();
        if (method != a.getFulfilmentMethod() || (address != null && !address.equals(a.getDeliveryAddress()))) {
            requireFulfilmentAllowed(g, method, address);
            a.setFulfilmentMethod(method);
            a.setDeliveryAddress(method == StockFulfilmentOption.SELF_COLLECTION ? null : address);
            changed = true;
        }
        if (!changed) return allocationView(a, true);
        // Any change to what this business is paying for voids its earlier agreement: it must confirm again.
        a.setStatus(BulkAllocationStatus.JOINED);
        a.setConfirmedPacks(null);
        a.setAgreedMaxPricePerPack(null);
        a.setAgreedAt(null);
        allocations.save(a);
        event(g, user, me.getId(), "QUANTITY_CHANGED", me.getName() + " changed its request to " + a.getRequestedPacks()
                + " pack(s); re-confirmation required", a.getRequestedPacks(), null);
        afterParticipationChange(g, user);
        return allocationView(a, true);
    }

    @Transactional
    public ConfirmOutcome confirm(User user, String groupId, ConfirmRequest req) {
        Business me = requireBuyer(user);
        writeLimit(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        BulkAllocation a = requireOwnAllocation(g, me);
        if (g.getStatus() != BulkGroupStatus.CONFIRMATION) {
            throw ApiException.conflict("Confirmation opens when the organiser starts the confirmation stage");
        }
        if (a.getStatus() != BulkAllocationStatus.JOINED && a.getStatus() != BulkAllocationStatus.PENDING_RECONFIRMATION
                && a.getStatus() != BulkAllocationStatus.CONFIRMED) {
            throw ApiException.conflict("Your allocation cannot be confirmed (" + a.getStatus() + ")");
        }
        Quote q = buildQuote(g, a.getRequestedPacks(), a.getFulfilmentMethod(), a.getRequestedPacks());
        if (req.acceptedPacks() != a.getRequestedPacks()) {
            return new ConfirmOutcome(false, "QUANTITY_CHANGED", allocationView(a, true), q);
        }
        if (req.acceptedPricePerPack().compareTo(q.pricePerPack()) != 0) {
            return new ConfirmOutcome(false, "PRICE_CHANGED", allocationView(a, true), q);
        }
        if (a.getStatus() == BulkAllocationStatus.CONFIRMED) { // idempotent replay at the same terms
            return new ConfirmOutcome(true, null, allocationView(a, true), q);
        }
        a.setStatus(BulkAllocationStatus.CONFIRMED);
        a.setConfirmedPacks(a.getRequestedPacks());
        a.setAgreedMaxPricePerPack(q.pricePerPack());
        a.setAgreedAt(Instant.now());
        allocations.save(a);
        event(g, user, me.getId(), "CONFIRMED", me.getName() + " confirmed " + a.getRequestedPacks() + " pack(s), at most "
                + q.pricePerPack() + " per pack", a.getRequestedPacks(), q.pricePerPack());
        return new ConfirmOutcome(true, null, allocationView(a, true), q);
    }

    @Transactional
    public AllocationView withdraw(User user, String groupId) {
        Business me = requireBuyer(user);
        writeLimit(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        BulkAllocation a = requireOwnAllocation(g, me);
        switch (a.getStatus()) {
            case WITHDRAWN, CANCELLED, LAPSED -> { return allocationView(a, true); } // idempotent
            case JOINED, CONFIRMED, PENDING_RECONFIRMATION -> {
                if (!g.getStatus().acceptsParticipationChanges()) throw ApiException.conflict("You can no longer withdraw from this group");
                a.setStatus(BulkAllocationStatus.WITHDRAWN);
                a.setConfirmedPacks(null);
                a.setAgreedMaxPricePerPack(null);
                allocations.save(a);
                event(g, user, me.getId(), "WITHDRAWN", me.getName() + " withdrew before any order was placed", a.getRequestedPacks(), null);
                afterParticipationChange(g, user);
            }
            case ORDERED -> {
                // Unpaid share after finalisation: cancel only this business's own order. The others stay locked at their price.
                if (g.getStatus() != BulkGroupStatus.PAYMENT_PENDING) throw ApiException.conflict("You can no longer withdraw from this group");
                requireNotInFlight(a.getStockOrder());
                stockOrders.cancelAndRelease(a.getStockOrder());
                a.setStatus(BulkAllocationStatus.CANCELLED);
                allocations.save(a);
                event(g, user, null, "WITHDRAWN_AFTER_ORDER", me.getName() + " cancelled its unpaid share of " + a.getConfirmedPacks()
                        + " pack(s). The organiser must decide whether to proceed.", a.getConfirmedPacks(), null);
            }
            default -> throw ApiException.conflict("A paid share cannot be withdrawn here. Contact the organiser or supplier; "
                    + "refunds for stock orders are handled manually.");
        }
        return allocationView(a, true);
    }

    @Transactional
    public AllocationView reportIssue(User user, String groupId, ReportIssueRequest req) {
        Business me = requireBuyer(user);
        writeLimit(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        BulkAllocation a = requireOwnAllocation(g, me);
        String type = req.type().toUpperCase();
        if (!ISSUE_TYPES.contains(type)) throw ApiException.badRequest("Issue type must be DAMAGED, MISSING or PARTIAL_DELIVERY");
        StockOrder o = a.getStockOrder();
        if (a.getStatus() != BulkAllocationStatus.PAID || o == null
                || !EnumSet.of(StockOrderStatus.READY_FOR_COLLECTION, StockOrderStatus.OUT_FOR_DELIVERY, StockOrderStatus.DELIVERED)
                        .contains(o.getStatus())) {
            throw ApiException.conflict("Issues can only be reported once your goods are ready, on the way or delivered");
        }
        int deliverable = a.getAllocatedPacks() == null ? 0 : a.getAllocatedPacks();
        if (req.packs() + a.getIssuePacks() > deliverable) {
            throw ApiException.badRequest("You can report at most " + (deliverable - a.getIssuePacks()) + " more pack(s)");
        }
        BigDecimal claim = BulkPricing.lineTotal(a.getFinalPricePerPack(), req.packs());
        a.setIssuePacks(a.getIssuePacks() + req.packs());
        a.setRefundDueAmount(a.getRefundDueAmount().add(claim));
        allocations.save(a);
        event(g, user, me.getId(), "ISSUE_" + type, me.getName() + " reported " + req.packs() + " pack(s) " + type.toLowerCase().replace('_', ' ')
                + (req.note() == null ? "" : ": " + req.note()) + ". Refund/credit claim recorded; not paid automatically.", req.packs(), claim);
        return allocationView(a, true);
    }

    // =====================================================================================================
    // Supplier
    // =====================================================================================================

    @Transactional
    public GroupDetail supplierConfirm(User supplierUser, String groupId, SupplierConfirmRequest req) {
        Business supplier = requireSupplier(supplierUser);
        writeLimit(supplierUser);
        BulkPurchaseGroup g = lockGroup(groupId);
        if (!g.getSupplierBusiness().getId().equals(supplier.getId())) throw ApiException.notFound("Bulk-purchase group not found");
        if (g.getStatus() == BulkGroupStatus.SUPPLIER_CONFIRMED || g.getStatus() == BulkGroupStatus.FULFILMENT) {
            return detail(g, supplier); // replay
        }
        if (g.getStatus() != BulkGroupStatus.ORDER_PLACED) {
            throw ApiException.conflict("The supplier can confirm only after every paid share has been placed as one order");
        }
        List<BulkAllocation> paid = allocations.findByGroupIdOrderByCreatedAtAsc(g.getId()).stream()
                .filter(a -> a.getStatus() == BulkAllocationStatus.PAID).toList();
        int ordered = paid.stream().mapToInt(BulkAllocation::getConfirmedPacks).sum();
        if (req.confirmedPacks() > ordered) throw ApiException.badRequest("You cannot confirm more than the " + ordered + " pack(s) ordered");
        g.setSupplierReference(req.supplierReference());
        g.setSupplierNote(req.note());

        if (req.confirmedPacks() == 0) {
            g.setSupplierConfirmedPacks(0);
            cancelInternal(g, supplierUser, "The supplier could not fulfil this order" + (req.note() == null ? "" : ": " + req.note()));
            return detail(g, supplier);
        }

        // Documented shortage policy: pro-rata, whole packs, largest remainder, ties to the earliest confirmation.
        List<BulkShortageAllocator.Claim> claims = paid.stream()
                .map(a -> new BulkShortageAllocator.Claim(a.getId(), a.getConfirmedPacks(), a.getAgreedAt())).toList();
        Map<String, Integer> split = BulkShortageAllocator.allocate(req.confirmedPacks(), claims);
        boolean shortage = req.confirmedPacks() < ordered;
        for (BulkAllocation a : paid) {
            int got = split.get(a.getId());
            int short_ = a.getConfirmedPacks() - got;
            a.setAllocatedPacks(got);
            a.setShortagePacks(short_);
            if (short_ > 0) {
                // The price they paid is honoured for what they get; the unsupplied part is owed back (not auto-refunded).
                BigDecimal owed = BulkPricing.lineTotal(a.getFinalPricePerPack(), short_);
                if (got == 0) owed = a.getTotal(); // nothing supplied: the delivery fee is owed back too
                a.setRefundDueAmount(a.getRefundDueAmount().add(owed));
                event(g, supplierUser, a.getBusiness().getId(), "SHORTAGE_ALLOCATED",
                        "Supplier shortage: " + got + " of " + a.getConfirmedPacks() + " pack(s) allocated to " + a.getBusiness().getName()
                                + ". " + owed + " " + g.getCurrency() + " is owed back; refunds are handled manually.", short_, owed);
            }
            if (got == 0) {
                a.setStatus(BulkAllocationStatus.REFUND_DUE);
                markOrderRefundPending(a.getStockOrder());
            }
            allocations.save(a);
        }
        g.setSupplierConfirmedPacks(req.confirmedPacks());
        g.setStatus(BulkGroupStatus.SUPPLIER_CONFIRMED);
        groups.save(g);
        event(g, supplierUser, null, "SUPPLIER_CONFIRMED", shortage
                ? "Supplier confirmed " + req.confirmedPacks() + " of " + ordered + " pack(s); shortage split pro-rata"
                : "Supplier confirmed the full " + ordered + " pack(s)", req.confirmedPacks(), null);
        // Reuse the existing supplier flow for each business's own order.
        for (BulkAllocation a : paid) {
            if (a.getAllocatedPacks() != null && a.getAllocatedPacks() > 0 && a.getStockOrder().getStatus() == StockOrderStatus.PAID) {
                stockOrders.advanceStatus(supplierUser, a.getStockOrder().getId(), StockOrderStatus.CONFIRMED);
            }
        }
        return detail(g, supplier);
    }

    @Transactional(readOnly = true)
    public SupplierGroupView supplierView(User supplierUser, String groupId) {
        Business supplier = requireSupplier(supplierUser);
        BulkPurchaseGroup g = groups.findById(groupId).orElseThrow(() -> ApiException.notFound("Bulk-purchase group not found"));
        if (!g.getSupplierBusiness().getId().equals(supplier.getId()) || g.getStatus() == BulkGroupStatus.DRAFT) {
            throw ApiException.notFound("Bulk-purchase group not found");
        }
        List<BulkAllocation> live = allocations.findByGroupIdOrderByCreatedAtAsc(g.getId()).stream()
                .filter(a -> a.getStatus().hasOrder() || a.getStatus() == BulkAllocationStatus.CANCELLED && a.getStockOrder() != null).toList();
        List<SupplierLine> lines = live.stream().map(a -> new SupplierLine(a.getBusiness().getName(),
                a.getConfirmedPacks() == null ? 0 : a.getConfirmedPacks(), a.getFulfilmentMethod(), a.getDeliveryAddress(),
                a.getStockOrder() == null ? null : a.getStockOrder().getId(),
                a.getStockOrder() == null ? null : a.getStockOrder().getStatus(), paymentStatus(a),
                a.getShortagePacks(), a.getIssuePacks())).toList();
        int ordered = live.stream().filter(a -> a.getStatus() != BulkAllocationStatus.CANCELLED)
                .mapToInt(a -> a.getConfirmedPacks() == null ? 0 : a.getConfirmedPacks()).sum();
        int paid = live.stream().filter(a -> a.getStatus() == BulkAllocationStatus.PAID || isPaid(a))
                .mapToInt(a -> a.getConfirmedPacks() == null ? 0 : a.getConfirmedPacks()).sum();
        return new SupplierGroupView(detail(g, supplier), ordered, paid, lines);
    }

    // =====================================================================================================
    // Reads
    // =====================================================================================================

    @Transactional(readOnly = true)
    public Page<GroupSummary> listOpen(User user, Pageable pageable) {
        requireBuyer(user);
        Page<BulkPurchaseGroup> page = groups.findByStatusInOrderByDeadlineAsc(
                EnumSet.of(BulkGroupStatus.OPEN, BulkGroupStatus.TARGET_REACHED), pageable);
        return page.map(this::summary);
    }

    @Transactional(readOnly = true)
    public Page<GroupSummary> listMine(User user, Pageable pageable) {
        Business me = requireBuyer(user);
        return groups.findInvolving(me.getId(), pageable).map(this::summary);
    }

    @Transactional(readOnly = true)
    public Page<GroupSummary> listForSupplier(User supplierUser, Pageable pageable) {
        Business supplier = requireSupplier(supplierUser);
        return groups.findBySupplierBusinessIdAndStatusNotOrderByCreatedAtDesc(supplier.getId(), BulkGroupStatus.DRAFT, pageable)
                .map(this::summary);
    }

    @Transactional(readOnly = true)
    public GroupDetail get(User user, String groupId) {
        Business me = businessService.requireOwn(user);
        BulkPurchaseGroup g = groups.findById(groupId).orElseThrow(() -> ApiException.notFound("Bulk-purchase group not found"));
        requireVisible(g, me);
        return detail(g, me);
    }

    @Transactional(readOnly = true)
    public AllocationView myAllocation(User user, String groupId) {
        Business me = requireBuyer(user);
        BulkPurchaseGroup g = groups.findById(groupId).orElseThrow(() -> ApiException.notFound("Bulk-purchase group not found"));
        return allocationView(requireOwnAllocation(g, me), true);
    }

    @Transactional(readOnly = true)
    public Page<AllocationView> myAllocations(User user, Pageable pageable) {
        Business me = requireBuyer(user);
        return allocations.findByBusinessIdOrderByCreatedAtDesc(me.getId(), pageable).map(a -> allocationView(a, false));
    }

    @Transactional(readOnly = true)
    public Page<EventView> events(User user, String groupId, Pageable pageable) {
        Business me = businessService.requireOwn(user);
        BulkPurchaseGroup g = groups.findById(groupId).orElseThrow(() -> ApiException.notFound("Bulk-purchase group not found"));
        String role = roleOf(g, me);
        Page<BulkGroupEvent> page = switch (role) {
            case "CREATOR", "SUPPLIER" -> events.findByGroupIdOrderByCreatedAtDesc(g.getId(), pageable);
            case "PARTICIPANT" -> events.findVisibleToBusiness(g.getId(), me.getId(), pageable);
            default -> throw ApiException.forbidden("Only the organiser, supplier and participants can see a group's history");
        };
        return page.map(e -> new EventView(e.getId(), e.getType(), e.getMessage(), e.getPacks(), e.getAmount(),
                e.getBusinessId(), e.getCreatedAt()));
    }

    /** Admin read-only oversight: allocations where money is owed back and has not been handled automatically. */
    @Transactional(readOnly = true)
    public Page<AllocationView> adminRefundDue(Pageable pageable) {
        return allocations.findWithRefundDue(pageable).map(a -> allocationView(a, false));
    }

    // =====================================================================================================
    // Sync (payments + fulfilment) — idempotent, safe to call from reads-by-button and the scheduler
    // =====================================================================================================

    @Transactional
    public GroupDetail refresh(User user, String groupId) {
        Business me = businessService.requireOwn(user);
        BulkPurchaseGroup g = lockGroup(groupId);
        if (roleOf(g, me).equals("BROWSER")) throw ApiException.notFound("Bulk-purchase group not found");
        syncPayments(g, user);
        syncFulfilment(g, user);
        return detail(g, me);
    }

    /** Scheduler entry: deadlines and order-state sync for one group. */
    @Transactional
    public void maintain(String groupId) {
        BulkPurchaseGroup g = groups.findForUpdate(groupId).orElse(null);
        if (g == null) return;
        try {
            if (EnumSet.of(BulkGroupStatus.OPEN, BulkGroupStatus.TARGET_REACHED, BulkGroupStatus.CONFIRMATION).contains(g.getStatus())
                    && !g.getDeadline().isAfter(Instant.now())) {
                applyDeadline(g);
            } else if (EnumSet.of(BulkGroupStatus.PAYMENT_PENDING, BulkGroupStatus.ORDER_PLACED,
                    BulkGroupStatus.SUPPLIER_CONFIRMED, BulkGroupStatus.FULFILMENT).contains(g.getStatus())) {
                syncPayments(g, null);
                syncFulfilment(g, null);
            }
        } catch (ApiException e) {
            log.warn("Bulk group {} maintenance skipped: {}", groupId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<String> groupsNeedingMaintenance() {
        List<String> ids = new ArrayList<>();
        groups.findByStatusInAndDeadlineBefore(EnumSet.of(BulkGroupStatus.OPEN, BulkGroupStatus.TARGET_REACHED,
                BulkGroupStatus.CONFIRMATION), Instant.now()).forEach(g -> ids.add(g.getId()));
        groups.findByStatusIn(EnumSet.of(BulkGroupStatus.PAYMENT_PENDING, BulkGroupStatus.ORDER_PLACED,
                BulkGroupStatus.SUPPLIER_CONFIRMED, BulkGroupStatus.FULFILMENT)).forEach(g -> ids.add(g.getId()));
        return ids;
    }

    private void applyDeadline(BulkPurchaseGroup g) {
        BulkDeadlinePolicy.Action action = BulkDeadlinePolicy.decide(g.getStatus(), g.getShortfallPolicy(), activePacks(g),
                g.getTargetPacks(), g.getMoqPacks(), g.getExtensionCount(), g.getConfirmationWindowsUsed());
        switch (action) {
            case CANCEL -> cancelInternal(g, null, "Deadline passed without enough confirmed demand; nothing was charged");
            case EXTEND -> {
                g.setDeadline(g.getDeadline().plus(Duration.ofHours(g.getExtensionHours())));
                g.setExtensionCount(g.getExtensionCount() + 1);
                groups.save(g);
                event(g, null, null, "DEADLINE_EXTENDED", "Target not reached; deadline extended by " + g.getExtensionHours() + "h as configured", null, null);
            }
            case MOVE_TO_CONFIRMATION -> {
                g.setStatus(BulkGroupStatus.CONFIRMATION);
                g.setConfirmationWindowsUsed(g.getConfirmationWindowsUsed() + 1);
                g.setDeadline(Instant.now().plus(Duration.ofHours(Math.max(24, g.getExtensionHours()))));
                groups.save(g);
                event(g, null, null, "CONFIRMATION_STARTED", "Deadline reached; each business must now confirm its quantity and the current price", activePacks(g), null);
            }
            case NONE -> { }
        }
    }

    // =====================================================================================================
    // Internals
    // =====================================================================================================

    /** Re-evaluates price/target after any participation change and sends affected businesses back to re-confirm. */
    private void afterParticipationChange(BulkPurchaseGroup g, User actor) {
        int agg = activePacks(g);
        BigDecimal price = BulkPricing.priceFor(g.getBasePricePerPack(), ladder(g), agg);
        for (BulkAllocation a : allocations.findByGroupIdOrderByCreatedAtAsc(g.getId())) {
            if (a.getStatus() == BulkAllocationStatus.CONFIRMED && a.getAgreedMaxPricePerPack().compareTo(price) < 0) {
                markReconfirm(g, actor, a, price);
            }
        }
        if (g.getStatus() == BulkGroupStatus.OPEN && agg >= g.getTargetPacks()) {
            g.setStatus(BulkGroupStatus.TARGET_REACHED);
            event(g, actor, null, "TARGET_REACHED", "Target of " + g.getTargetPacks() + " pack(s) reached", agg, null);
        } else if (g.getStatus() == BulkGroupStatus.TARGET_REACHED && agg < g.getTargetPacks()) {
            g.setStatus(BulkGroupStatus.OPEN);
            event(g, actor, null, "TARGET_LOST", "Group fell back below its target", agg, null);
        }
        groups.save(g);
    }

    private void markReconfirm(BulkPurchaseGroup g, User actor, BulkAllocation a, BigDecimal newPrice) {
        a.setStatus(BulkAllocationStatus.PENDING_RECONFIRMATION);
        allocations.save(a);
        event(g, actor, a.getBusiness().getId(), "RECONFIRMATION_REQUIRED",
                "The price per pack is now " + newPrice + ", above the " + a.getAgreedMaxPricePerPack()
                        + " you agreed to. You will not be charged more unless you confirm again.", a.getRequestedPacks(), newPrice);
    }

    private Quote buildQuote(BulkPurchaseGroup g, int packs, StockFulfilmentOption method, int alreadyCountedPacks) {
        int agg = activePacks(g) - alreadyCountedPacks + packs;
        List<BulkPricing.Tier> ladder = ladder(g);
        BigDecimal price = BulkPricing.priceFor(g.getBasePricePerPack(), ladder, agg);
        BigDecimal subtotal = BulkPricing.lineTotal(price, packs);
        BigDecimal fee = deliveryFee(g, method);
        BulkPricing.Tier next = BulkPricing.nextTier(ladder, agg);
        return new Quote(packs, agg, g.getBasePricePerPack(), price, BulkPricing.discountPerPack(g.getBasePricePerPack(), price),
                subtotal, fee, subtotal.add(fee), g.getCurrency(),
                next == null ? null : new TierView(next.minPacks(), next.pricePerPack()),
                next == null ? 0 : next.minPacks() - agg, agg >= g.getMoqPacks(), agg >= g.getTargetPacks());
    }

    private BigDecimal expectedTotal(BulkPurchaseGroup g, BulkAllocation a, BigDecimal price) {
        return BulkPricing.lineTotal(price, a.getConfirmedPacks()).add(deliveryFee(g, a.getFulfilmentMethod()));
    }

    private BigDecimal deliveryFee(BulkPurchaseGroup g, StockFulfilmentOption method) {
        if (method == StockFulfilmentOption.SELF_COLLECTION) return BulkPricing.money(BigDecimal.ZERO);
        BigDecimal fee = g.getSupplierBusiness().getDeliveryFee();
        return BulkPricing.money(fee != null ? fee : BigDecimal.ZERO);
    }

    private StockOrder createAllocationOrder(BulkPurchaseGroup g, BulkAllocation a, WholesaleProduct p, BigDecimal price, int packs) {
        // Same atomic reservation the normal checkout uses: the conditional UPDATE is the guard against overselling.
        if (products.reserve(p.getId(), packs) != 1) {
            throw ApiException.conflict(p.getName() + ": the supplier does not have enough stock for every confirmed share");
        }
        BigDecimal line = BulkPricing.lineTotal(price, packs);
        BigDecimal fee = deliveryFee(g, a.getFulfilmentMethod());

        StockOrder order = new StockOrder();
        order.setBuyerBusiness(a.getBusiness());
        order.setSupplierBusiness(g.getSupplierBusiness());
        order.setSubtotal(line);
        order.setDeliveryFeeSnapshot(fee);
        order.setTotal(line.add(fee));
        order.setCurrency(g.getCurrency());
        order.setFulfilmentMethod(a.getFulfilmentMethod());
        order.setDeliveryAddress(a.getDeliveryAddress());
        order.setCheckoutGroupKey(checkoutKey(a));
        order.setIdempotencyKey(sha256(checkoutKey(a)));

        StockOrderItem item = new StockOrderItem();
        item.setWholesaleProduct(p);
        item.setProductNameSnapshot(p.getName());
        item.setSkuSnapshot(p.getSku());
        item.setPackSizeSnapshot(p.getPackSize());
        item.setQuantityPacks(packs);
        item.setPricePerPackSnapshot(price);
        item.setLineTotal(line);
        item.setStockOrder(order);
        order.getItems().add(item);
        return orderRepo.save(order);
    }

    /** Marks allocations paid ONLY when the order shows a provider-confirmed payment; notes dead orders. */
    private void syncPayments(BulkPurchaseGroup g, User actor) {
        if (g.getStatus() != BulkGroupStatus.PAYMENT_PENDING && g.getStatus() != BulkGroupStatus.ORDER_PLACED
                && g.getStatus() != BulkGroupStatus.SUPPLIER_CONFIRMED && g.getStatus() != BulkGroupStatus.FULFILMENT) return;
        List<BulkAllocation> all = allocations.findByGroupIdOrderByCreatedAtAsc(g.getId());
        for (BulkAllocation a : all) {
            if (a.getStatus() != BulkAllocationStatus.ORDERED || a.getStockOrder() == null) continue;
            StockOrder o = a.getStockOrder();
            if (isPaid(a)) {
                a.setStatus(BulkAllocationStatus.PAID);
                allocations.save(a);
                event(g, actor, a.getBusiness().getId(), "PAYMENT_CONFIRMED", "Payment confirmed for this business's share", a.getConfirmedPacks(), a.getTotal());
            } else if (o.getStatus() == StockOrderStatus.CANCELLED) {
                a.setStatus(BulkAllocationStatus.CANCELLED);
                allocations.save(a);
                event(g, actor, null, "SHARE_EXPIRED", a.getBusiness().getName() + "'s share was cancelled or expired unpaid", a.getConfirmedPacks(), null);
            }
        }
        if (g.getStatus() == BulkGroupStatus.PAYMENT_PENDING) {
            List<BulkAllocation> live = all.stream().filter(a -> a.getStatus() == BulkAllocationStatus.ORDERED
                    || a.getStatus() == BulkAllocationStatus.PAID).toList();
            boolean allPaid = !live.isEmpty() && live.stream().allMatch(a -> a.getStatus() == BulkAllocationStatus.PAID);
            int packs = live.stream().mapToInt(BulkAllocation::getConfirmedPacks).sum();
            // Automatic placement only when EVERY remaining share is paid and the original volume (so price) still holds.
            if (allPaid && g.getFinalAggregatePacks() != null && packs == g.getFinalAggregatePacks()) {
                markOrderPlaced(g, actor, packs, null);
            }
        }
    }

    private void markOrderPlaced(BulkPurchaseGroup g, User actor, int packs, String note) {
        g.setStatus(BulkGroupStatus.ORDER_PLACED);
        g.setOrderPlacedAt(Instant.now());
        if (note != null) g.setSupplierNote(note);
        groups.save(g);
        event(g, actor, null, "ORDER_PLACED", "Combined order for " + packs + " pack(s) placed with " + g.getSupplierBusiness().getName()
                + ". Every included share is paid.", packs, null);
    }

    private void syncFulfilment(BulkPurchaseGroup g, User actor) {
        if (g.getStatus() != BulkGroupStatus.SUPPLIER_CONFIRMED && g.getStatus() != BulkGroupStatus.FULFILMENT) return;
        List<BulkAllocation> active = allocations.findByGroupIdOrderByCreatedAtAsc(g.getId()).stream()
                .filter(a -> a.getStatus() == BulkAllocationStatus.PAID && a.getAllocatedPacks() != null && a.getAllocatedPacks() > 0
                        && a.getStockOrder() != null).toList();
        if (active.isEmpty()) return;
        boolean allDelivered = active.stream().allMatch(a -> a.getStockOrder().getStatus() == StockOrderStatus.DELIVERED);
        boolean anyMoving = active.stream().anyMatch(a -> IN_FULFILMENT.contains(a.getStockOrder().getStatus()));
        if (allDelivered) {
            boolean exceptions = allocations.findByGroupIdOrderByCreatedAtAsc(g.getId()).stream()
                    .anyMatch(a -> a.getShortagePacks() > 0 || a.getIssuePacks() > 0 || a.getStatus() == BulkAllocationStatus.REFUND_DUE);
            g.setStatus(exceptions ? BulkGroupStatus.PARTIALLY_FULFILLED : BulkGroupStatus.COMPLETED);
            groups.save(g);
            event(g, actor, null, exceptions ? "PARTIALLY_FULFILLED" : "COMPLETED",
                    exceptions ? "All goods delivered, with shortages or issues that still need follow-up" : "Every business has received its share", null, null);
        } else if (anyMoving && g.getStatus() == BulkGroupStatus.SUPPLIER_CONFIRMED) {
            g.setStatus(BulkGroupStatus.FULFILMENT);
            groups.save(g);
            event(g, actor, null, "FULFILMENT_STARTED", "Fulfilment has started for at least one business", null, null);
        }
    }

    /** Cancels a group that has not reached the supplier. Paid shares become REFUND_DUE (never silently refunded). */
    private void cancelInternal(BulkPurchaseGroup g, User actor, String reason) {
        for (BulkAllocation a : allocations.findByGroupIdOrderByCreatedAtAsc(g.getId())) {
            switch (a.getStatus()) {
                case JOINED, CONFIRMED, PENDING_RECONFIRMATION -> {
                    a.setStatus(BulkAllocationStatus.CANCELLED);
                    a.setConfirmedPacks(null);
                }
                case ORDERED -> {
                    if (isPaid(a)) {
                        refundDue(a);
                    } else {
                        requireNotInFlight(a.getStockOrder());
                        stockOrders.cancelAndRelease(a.getStockOrder());
                        a.setStatus(BulkAllocationStatus.CANCELLED);
                    }
                }
                case PAID -> refundDue(a);
                default -> { }
            }
            allocations.save(a);
        }
        g.setStatus(BulkGroupStatus.CANCELLED);
        g.setCancelReason(reason);
        groups.save(g);
        event(g, actor, null, "GROUP_CANCELLED", reason, null, null);
    }

    private void refundDue(BulkAllocation a) {
        StockOrder o = a.getStockOrder();
        a.setStatus(BulkAllocationStatus.REFUND_DUE);
        a.setRefundDueAmount(a.getTotal());
        if (o != null) {
            for (StockOrderItem item : o.getItems()) products.release(item.getWholesaleProduct().getId(), item.getQuantityPacks());
            markOrderRefundPending(o);
        }
    }

    /** PAID -> REFUND_PENDING only: the existing code has no refund executor, so this states "owed", not "refunded". */
    private void markOrderRefundPending(StockOrder o) {
        if (o != null && o.getStatus().canTransitionTo(StockOrderStatus.REFUND_PENDING)) {
            o.setStatus(StockOrderStatus.REFUND_PENDING);
            orderRepo.save(o);
        }
    }

    private void requireNotInFlight(StockOrder o) {
        if (o != null && o.getStatus() == StockOrderStatus.PAYMENT_PROCESSING) {
            throw ApiException.conflict("A payment for this share is still being confirmed. Try again shortly.");
        }
    }

    // ---- Derived payment/fulfilment (single source of truth is the order + payment) ----

    private boolean isPaid(BulkAllocation a) {
        StockOrder o = a.getStockOrder();
        return o != null && PAID_OR_BEYOND.contains(o.getStatus())
                && o.getPayment() != null && o.getPayment().getStatus() == PaymentStatus.SUCCESSFUL;
    }

    private String paymentStatus(BulkAllocation a) {
        StockOrder o = a.getStockOrder();
        if (o == null) return "NOT_STARTED";
        if (o.getStatus() == StockOrderStatus.REFUND_PENDING) return "REFUND_PENDING";
        if (o.getStatus() == StockOrderStatus.REFUNDED) return "REFUNDED";
        if (o.getPayment() == null) return o.getStatus() == StockOrderStatus.CANCELLED ? "CANCELLED" : "UNPAID";
        return switch (o.getPayment().getStatus()) {
            case SUCCESSFUL -> "PAID";
            case PENDING -> "PENDING";
            case PROCESSING -> "PROCESSING";
            case FAILED -> "FAILED";
            case REFUNDED -> "REFUNDED";
        };
    }

    // ---- Access control ----

    private Business requireBuyer(User user) {
        Business b = businessService.requireOwn(user);
        if (!b.isActive() || !b.hasCapability(BusinessCapability.CAN_BUY_STOCK)) {
            throw ApiException.forbidden("Your business needs the 'buy stock from suppliers' capability");
        }
        return b;
    }

    private Business requireSupplier(User user) {
        Business b = businessService.requireOwn(user);
        if (!b.hasCapability(BusinessCapability.CAN_SUPPLY_BUSINESSES)) {
            throw ApiException.forbidden("Your business needs the 'supply other businesses' capability");
        }
        return b;
    }

    private void requireCreator(BulkPurchaseGroup g, Business me) {
        if (!g.getCreatorBusiness().getId().equals(me.getId())) {
            throw ApiException.forbidden("Only the group organiser can do this");
        }
    }

    /** Another business's DRAFT/closed group does not exist as far as this business is concerned (no IDOR, no enumeration). */
    private void requireVisible(BulkPurchaseGroup g, Business me) {
        if (roleOf(g, me).equals("BROWSER")
                && g.getStatus() != BulkGroupStatus.OPEN && g.getStatus() != BulkGroupStatus.TARGET_REACHED) {
            throw ApiException.notFound("Bulk-purchase group not found");
        }
        if (g.getStatus() == BulkGroupStatus.DRAFT && !g.getCreatorBusiness().getId().equals(me.getId())) {
            throw ApiException.notFound("Bulk-purchase group not found");
        }
    }

    private String roleOf(BulkPurchaseGroup g, Business me) {
        if (g.getCreatorBusiness().getId().equals(me.getId())) return "CREATOR";
        if (g.getSupplierBusiness().getId().equals(me.getId())) return g.getStatus() == BulkGroupStatus.DRAFT ? "BROWSER" : "SUPPLIER";
        if (allocations.findByGroupIdAndBusinessId(g.getId(), me.getId()).isPresent()) return "PARTICIPANT";
        return "BROWSER";
    }

    /** The allocation is looked up from the CALLER's business, never from a client-supplied allocation id. */
    private BulkAllocation requireOwnAllocation(BulkPurchaseGroup g, Business me) {
        return allocations.findByGroupIdAndBusinessId(g.getId(), me.getId())
                .orElseThrow(() -> ApiException.notFound("You are not part of this group"));
    }

    private BulkPurchaseGroup lockGroup(String id) {
        return groups.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Bulk-purchase group not found"));
    }

    private void requireTransition(BulkPurchaseGroup g, BulkGroupStatus next) {
        if (!g.getStatus().canTransitionTo(next)) {
            throw ApiException.conflict("A group cannot move from " + g.getStatus() + " to " + next);
        }
    }

    private void requireFulfilmentAllowed(BulkPurchaseGroup g, StockFulfilmentOption m, String address) {
        boolean ok = switch (m) {
            case SELF_COLLECTION -> g.isAllowSelfCollection();
            case WHOLESALER_DELIVERY -> g.isAllowSupplierDelivery();
            case PULA_RIDE_DELIVERY -> g.isAllowPulaRide();
        };
        if (!ok) throw ApiException.badRequest("This group does not offer that collection/delivery option");
        if (m != StockFulfilmentOption.SELF_COLLECTION && (address == null || address.isBlank())) {
            throw ApiException.badRequest("A delivery address is required for delivery");
        }
    }

    private void writeLimit(User user) {
        limiter.check("bulk-write", user.getId(), 60, Duration.ofMinutes(1));
    }

    // ---- Mapping ----

    private int activePacks(BulkPurchaseGroup g) {
        return (int) allocations.sumActiveRequestedPacks(g.getId());
    }

    private List<BulkPricing.Tier> ladder(BulkPurchaseGroup g) {
        return g.getTiers().stream().map(t -> new BulkPricing.Tier(t.getMinPacks(), t.getPricePerPack())).toList();
    }

    private GroupSummary summary(BulkPurchaseGroup g) {
        int agg = g.getFinalAggregatePacks() != null ? g.getFinalAggregatePacks() : activePacks(g);
        long participants = allocations.findByGroupIdOrderByCreatedAtAsc(g.getId()).stream()
                .filter(a -> a.getStatus() != BulkAllocationStatus.WITHDRAWN && a.getStatus() != BulkAllocationStatus.LAPSED
                        && a.getStatus() != BulkAllocationStatus.CANCELLED).count();
        WholesaleProduct p = g.getWholesaleProduct();
        return new GroupSummary(g.getId(), g.getProcurementReference(), g.getName(), g.getDescription(), g.getStatus(),
                g.getSupplierBusiness().getId(), g.getSupplierBusiness().getName(),
                g.getCreatorBusiness().getId(), g.getCreatorBusiness().getName(),
                p.getId(), p.getName(), g.getPackSizeSnapshot(), g.getMoqPacks(), g.getTargetPacks(), agg, (int) participants,
                g.getBasePricePerPack(), BulkPricing.priceFor(g.getBasePricePerPack(), ladder(g), agg), g.getCurrency(),
                g.getTiers().stream().map(t -> new TierView(t.getMinPacks(), t.getPricePerPack())).toList(),
                g.getDeadline(), g.getShortfallPolicy(), g.isAllowSelfCollection(), g.isAllowSupplierDelivery(),
                g.isAllowPulaRide(), g.getCollectionNote());
    }

    private GroupDetail detail(BulkPurchaseGroup g, Business viewer) {
        String role = roleOf(g, viewer);
        AllocationView mine = allocations.findByGroupIdAndBusinessId(g.getId(), viewer.getId())
                .map(a -> allocationView(a, true)).orElse(null);
        List<ParticipantView> participants = null;
        if (role.equals("CREATOR") || role.equals("SUPPLIER")) {
            participants = allocations.findByGroupIdOrderByCreatedAtAsc(g.getId()).stream()
                    .map(a -> new ParticipantView(a.getBusiness().getId(), a.getBusiness().getName(), a.getStatus(),
                            a.getRequestedPacks(), a.getConfirmedPacks())).toList();
        }
        return new GroupDetail(summary(g), role, mine, participants, g.getFinalPricePerPack(), g.getFinalAggregatePacks(),
                g.getSupplierConfirmedPacks(), g.getSupplierReference(), g.getSupplierNote(), g.getCancelReason(),
                g.getFinalisedAt(), g.getOrderPlacedAt());
    }

    private AllocationView allocationView(BulkAllocation a, boolean withQuote) {
        BulkPurchaseGroup g = a.getGroup();
        StockOrder o = a.getStockOrder();
        Quote q = null;
        if (withQuote && a.getStatus().isActiveInterest() && g.getStatus().acceptsParticipationChanges()) {
            q = buildQuote(g, a.getRequestedPacks(), a.getFulfilmentMethod(), a.getRequestedPacks());
        }
        // A wallet payment is ALWAYS simulated (demo funds), whatever mode the card provider is in.
        boolean simulated = o != null && (com.momocircle.payment.PaymentRails.isWallet(o.getPayment())
                || (orchestrator != null && "SANDBOX_MOCK".equals(orchestrator.marketMode())));
        return new AllocationView(a.getId(), g.getId(), g.getProcurementReference(), g.getName(),
                a.getBusiness().getId(), a.getBusiness().getName(), a.getStatus(),
                a.getRequestedPacks(), a.getConfirmedPacks(), a.getAllocatedPacks(), a.getShortagePacks(), a.getIssuePacks(),
                a.getAgreedMaxPricePerPack(), a.getFinalPricePerPack(), a.getDiscountPerPack(), a.getSubtotal(),
                a.getDeliveryFee(), a.getTotal(), a.getRefundDueAmount(), g.getCurrency(),
                a.getFulfilmentMethod(), a.getDeliveryAddress(),
                o == null ? null : o.getId(), o == null ? null : o.getStatus(), paymentStatus(a), simulated,
                a.getSaveToBuyRequestId(), q);
    }

    private void event(BulkPurchaseGroup g, User actor, String businessId, String type, String message, Integer packs, BigDecimal amount) {
        BulkGroupEvent e = new BulkGroupEvent();
        e.setGroupId(g.getId());
        e.setBusinessId(businessId);
        e.setActorUserId(actor == null ? null : actor.getId());
        e.setType(type);
        e.setMessage(message != null && message.length() > 500 ? message.substring(0, 500) : message);
        e.setPacks(packs);
        e.setAmount(amount);
        events.save(e);
    }

    private static String checkoutKey(BulkAllocation a) {
        return "bulk:" + a.getId();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
