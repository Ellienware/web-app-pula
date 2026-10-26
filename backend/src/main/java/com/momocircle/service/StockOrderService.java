package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.StockCartItem;
import com.momocircle.domain.StockOrder;
import com.momocircle.domain.StockOrderItem;
import com.momocircle.domain.User;
import com.momocircle.domain.WholesaleProduct;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.domain.enums.StockFulfilmentOption;
import com.momocircle.domain.enums.StockOrderStatus;
import com.momocircle.dto.StockDtos.PlaceStockOrderRequest;
import com.momocircle.dto.StockDtos.StockOrderItemResponse;
import com.momocircle.dto.StockDtos.StockOrderResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.StockOrderRepository;
import com.momocircle.repository.WholesaleProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stock order checkout and lifecycle (brief §15).
 *
 * Integrity rules (PULA hardening):
 *  - Checkout idempotency is scoped to the BUYER business: a client key can never
 *    return another business's order, and replaying a multi-supplier checkout
 *    returns all of its supplier orders.
 *  - Stock is reserved with an atomic conditional UPDATE (no oversell under concurrency)
 *    and returned when an unpaid order is cancelled or expires.
 *  - Suppliers can only move an order through fulfilment states. PAID / payment
 *    states are set only by StockPaymentService after server-side provider verification;
 *    OUT_FOR_DELIVERY / DELIVERED (delivery orders) only by the delivery engine.
 */
@Service
public class StockOrderService {

    /** Statuses a supplier may set directly. Everything else is system-driven. */
    private static final Set<StockOrderStatus> SUPPLIER_SETTABLE = EnumSet.of(
            StockOrderStatus.CONFIRMED, StockOrderStatus.PREPARING,
            StockOrderStatus.READY_FOR_COLLECTION, StockOrderStatus.DELIVERED);

    private final StockOrderRepository orderRepo;
    private final StockCartService cartService;
    private final BusinessService businessService;
    private final WholesaleProductRepository productRepo;
    private final StockDeliveryService deliveryService;

    public StockOrderService(StockOrderRepository orderRepo, StockCartService cartService,
                              BusinessService businessService, WholesaleProductRepository productRepo,
                              StockDeliveryService deliveryService) {
        this.orderRepo = orderRepo;
        this.cartService = cartService;
        this.businessService = businessService;
        this.productRepo = productRepo;
        this.deliveryService = deliveryService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SaveToBuyService saveToBuy;

    /** Creates one StockOrder per supplier in the buyer's cart (brief §14 "group into separate supplier orders"). */
    @Transactional
    public List<StockOrderResponse> checkout(User buyer, PlaceStockOrderRequest req) {
        Business buyerBusiness = requireBuyerBusiness(buyer);

        // Idempotent replay — scoped to THIS buyer business only.
        List<StockOrder> existing = orderRepo.findByBuyerBusinessIdAndCheckoutGroupKey(
                buyerBusiness.getId(), req.idempotencyKey());
        if (!existing.isEmpty()) {
            return existing.stream().map(this::toResponse).toList();
        }

        if (req.fulfilmentMethod() != StockFulfilmentOption.SELF_COLLECTION
                && (req.deliveryAddress() == null || req.deliveryAddress().isBlank())) {
            throw ApiException.badRequest("Delivery address is required for delivery orders");
        }

        List<StockCartItem> cartItems = cartService.rawItems(buyerBusiness);
        if (cartItems.isEmpty()) throw ApiException.badRequest("Your stock cart is empty");

        Map<String, List<StockCartItem>> bySupplier = new LinkedHashMap<>();
        for (StockCartItem item : cartItems) {
            String supplierId = item.getWholesaleProduct().getSupplierBusiness().getId();
            bySupplier.computeIfAbsent(supplierId, k -> new ArrayList<>()).add(item);
        }

        // Save-to-Buy (optional): one request <-> one order, so the cart must be for a single supplier.
        com.momocircle.domain.SaveToBuyRequest s2b = null;
        if (saveToBuy != null && req.saveToBuyRequestId() != null && !req.saveToBuyRequestId().isBlank()) {
            if (bySupplier.size() != 1) {
                throw ApiException.badRequest("A Save-to-Buy purchase must be for a single supplier");
            }
            s2b = saveToBuy.validateForCheckout(buyer, req.saveToBuyRequestId(),
                    com.momocircle.domain.enums.SaveToBuyVertical.STOCK, null, req.idempotencyKey());
        }

        List<StockOrderResponse> created = new ArrayList<>();
        for (Map.Entry<String, List<StockCartItem>> entry : bySupplier.entrySet()) {
            created.add(createOrderForSupplier(buyerBusiness, entry.getValue(), req));
        }
        if (s2b != null) {
            String orderId = created.get(0).id();
            StockOrder o = orderRepo.findById(orderId).orElseThrow();
            // The real total is known now; a stokvel purchase over the approved amount rolls the whole checkout back.
            saveToBuy.requireWithinApproval(s2b, o.getTotal());
            o.setSaveToBuyRequestId(s2b.getId());
            orderRepo.save(o);
            saveToBuy.attachOrder(s2b, com.momocircle.domain.enums.RelatedOrderKind.STOCK_ORDER, orderId, req.idempotencyKey());
        }

        cartService.clearCart(buyerBusiness);
        return created;
    }

    @Transactional(readOnly = true)
    public Page<StockOrderResponse> myOrders(User buyer, Pageable pageable) {
        Business business = requireBuyerBusiness(buyer);
        return orderRepo.findByBuyerBusinessIdOrderByCreatedAtDesc(business.getId(), pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<StockOrderResponse> supplierOrders(User supplier, StockOrderStatus status, Pageable pageable) {
        Business business = requireSupplierBusiness(supplier);
        return status != null
                ? orderRepo.findBySupplierBusinessIdAndStatusOrderByCreatedAtDesc(business.getId(), status, pageable).map(this::toResponse)
                : orderRepo.findBySupplierBusinessIdOrderByCreatedAtDesc(business.getId(), pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public StockOrderResponse getBuyerOrder(User buyer, String orderId) {
        Business business = requireBuyerBusiness(buyer);
        return toResponse(orderRepo.findByIdAndBuyerBusinessId(orderId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Stock order not found")));
    }

    @Transactional(readOnly = true)
    public StockOrderResponse getSupplierOrder(User supplier, String orderId) {
        Business business = requireSupplierBusiness(supplier);
        return toResponse(orderRepo.findByIdAndSupplierBusinessId(orderId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Stock order not found")));
    }

    /**
     * Supplier moves a PAID order through fulfilment (CONFIRMED -> PREPARING -> READY_FOR_COLLECTION).
     * Marking READY on a delivery order hands it to PULA Ride dispatch; the driver — not the
     * supplier — accepts, picks up and delivers.
     */
    @Transactional
    public StockOrderResponse advanceStatus(User supplier, String orderId, StockOrderStatus next) {
        Business business = requireSupplierBusiness(supplier);
        StockOrder order = orderRepo.findByIdAndSupplierBusinessId(orderId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Stock order not found"));
        if (!SUPPLIER_SETTABLE.contains(next)) {
            throw ApiException.forbidden("That status is set automatically by payment or delivery, not by the supplier");
        }
        if (next == StockOrderStatus.DELIVERED && order.getFulfilmentMethod() != StockFulfilmentOption.SELF_COLLECTION) {
            throw ApiException.badRequest("Delivery orders are completed when the driver confirms delivery");
        }
        if (!order.getStatus().canTransitionTo(next)) {
            throw ApiException.badRequest("Cannot transition from " + order.getStatus() + " to " + next);
        }
        if (next == StockOrderStatus.READY_FOR_COLLECTION
                && order.getFulfilmentMethod() != StockFulfilmentOption.SELF_COLLECTION) {
            deliveryService.requireDispatchable(order); // fail before changing state
            order.setStatus(next);
            orderRepo.save(order);
            deliveryService.dispatch(order);
            return toResponse(order);
        }
        order.setStatus(next);
        StockOrder saved = orderRepo.save(order);
        if (next == StockOrderStatus.DELIVERED) supplierEarned(saved);
        return toResponse(saved);
    }

    /** Supplier re-sends a cancelled delivery to drivers. Only READY delivery orders qualify. */
    @Transactional
    public StockOrderResponse redispatch(User supplier, String orderId) {
        Business business = requireSupplierBusiness(supplier);
        StockOrder order = orderRepo.findByIdAndSupplierBusinessId(orderId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Stock order not found"));
        if (order.getStatus() != StockOrderStatus.READY_FOR_COLLECTION
                || order.getFulfilmentMethod() == StockFulfilmentOption.SELF_COLLECTION) {
            throw ApiException.badRequest("Only a delivery order that is ready can be sent to drivers again");
        }
        deliveryService.redispatch(order);
        return toResponse(order);
    }

    /** Buyer can cancel only while unpaid; reserved stock is returned. */
    @Transactional
    public StockOrderResponse cancelByBuyer(User buyer, String orderId) {
        Business business = requireBuyerBusiness(buyer);
        StockOrder order = orderRepo.findByIdAndBuyerBusinessId(orderId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Stock order not found"));
        if (order.getStatus() != StockOrderStatus.PENDING_PAYMENT) {
            throw ApiException.badRequest("This order can no longer be cancelled — contact the supplier directly");
        }
        cancelAndRelease(order);
        return toResponse(order);
    }

    /** Cancels an UNPAID order and returns its reserved stock. Idempotent for already-cancelled orders. */
    @Transactional
    public void cancelAndRelease(StockOrder order) {
        if (order.getStatus() == StockOrderStatus.CANCELLED) return;
        if (!order.getStatus().canTransitionTo(StockOrderStatus.CANCELLED)) {
            throw ApiException.badRequest("Order " + order.getId() + " cannot be cancelled from " + order.getStatus());
        }
        order.setStatus(StockOrderStatus.CANCELLED);
        for (StockOrderItem item : order.getItems()) {
            productRepo.release(item.getWholesaleProduct().getId(), item.getQuantityPacks());
        }
        orderRepo.save(order);
        if (saveToBuy != null && order.getSaveToBuyRequestId() != null) {
            saveToBuy.onOrderFailed(com.momocircle.domain.enums.RelatedOrderKind.STOCK_ORDER, order.getId());
        }
    }

    /** Called by the delivery engine when the driver collects the goods. */
    @Transactional
    public void markOutForDelivery(StockOrder order) {
        if (order.getStatus().canTransitionTo(StockOrderStatus.OUT_FOR_DELIVERY)) {
            order.setStatus(StockOrderStatus.OUT_FOR_DELIVERY);
            orderRepo.save(order);
        }
    }

    /** Called by the delivery engine when the driver confirms delivery. */
    @Transactional
    public void markDeliveredByDelivery(StockOrder order) {
        if (order.getStatus().canTransitionTo(StockOrderStatus.DELIVERED)) {
            order.setStatus(StockOrderStatus.DELIVERED);
            supplierEarned(orderRepo.save(order));
        }
    }

    /** Shared settlement engine: delivery completes the stock sale, so the supplier's settlement can become eligible. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.settlement.MarketplaceSettlementService marketplaceSettlement;

    private void supplierEarned(StockOrder order) {
        if (marketplaceSettlement != null) {
            marketplaceSettlement.onEarnedSafely(com.momocircle.domain.enums.SettlementSourceType.STOCK_ORDER, order.getId());
        }
    }

    // ---- Internals ----

    private StockOrderResponse createOrderForSupplier(Business buyerBusiness, List<StockCartItem> items,
                                                        PlaceStockOrderRequest req) {
        Business supplierBusiness = items.get(0).getWholesaleProduct().getSupplierBusiness();
        BigDecimal subtotal = BigDecimal.ZERO;
        List<StockOrderItem> orderItems = new ArrayList<>();

        for (StockCartItem cartItem : items) {
            WholesaleProduct p = cartItem.getWholesaleProduct();
            if (cartItem.getQuantityPacks() < p.getMoq()) {
                throw ApiException.badRequest(p.getName() + ": minimum order is " + p.getMoq() + " pack(s) — you have " + cartItem.getQuantityPacks());
            }
            if (cartItem.getQuantityPacks() > p.getAvailableStock()) {
                throw ApiException.badRequest(p.getName() + ": only " + p.getAvailableStock() + " pack(s) available — you requested " + cartItem.getQuantityPacks());
            }
            // Atomic reservation: the conditional UPDATE is the real guard against overselling.
            if (productRepo.reserve(p.getId(), cartItem.getQuantityPacks()) != 1) {
                throw ApiException.conflict(p.getName() + ": stock changed while you were checking out — please review your cart");
            }
            BigDecimal line = p.getPricePerPack().multiply(BigDecimal.valueOf(cartItem.getQuantityPacks()));
            subtotal = subtotal.add(line);

            StockOrderItem item = new StockOrderItem();
            item.setWholesaleProduct(p);
            item.setProductNameSnapshot(p.getName());
            item.setSkuSnapshot(p.getSku());
            item.setPackSizeSnapshot(p.getPackSize());
            item.setQuantityPacks(cartItem.getQuantityPacks());
            item.setPricePerPackSnapshot(p.getPricePerPack());
            item.setLineTotal(line);
            orderItems.add(item);
        }

        BigDecimal deliveryFee = req.fulfilmentMethod() == StockFulfilmentOption.SELF_COLLECTION
                ? BigDecimal.ZERO
                : (supplierBusiness.getDeliveryFee() != null ? supplierBusiness.getDeliveryFee() : BigDecimal.ZERO);

        StockOrder order = new StockOrder();
        order.setBuyerBusiness(buyerBusiness);
        order.setSupplierBusiness(supplierBusiness);
        order.setSubtotal(subtotal);
        order.setDeliveryFeeSnapshot(deliveryFee);
        order.setTotal(subtotal.add(deliveryFee));
        order.setCurrency(items.get(0).getWholesaleProduct().getCurrency());
        order.setFulfilmentMethod(req.fulfilmentMethod());
        order.setDeliveryAddress(req.deliveryAddress());
        order.setCheckoutGroupKey(req.idempotencyKey());
        order.setIdempotencyKey(sha256(buyerBusiness.getId() + ":" + supplierBusiness.getId() + ":" + req.idempotencyKey()));
        order.getItems().addAll(orderItems);
        orderItems.forEach(i -> i.setStockOrder(order));

        return toResponse(orderRepo.save(order));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Business requireBuyerBusiness(User buyer) {
        Business business = businessService.requireOwn(buyer);
        if (!business.hasCapability(BusinessCapability.CAN_BUY_STOCK)) {
            throw ApiException.forbidden("Your business needs the 'buy stock from suppliers' capability");
        }
        return business;
    }

    private Business requireSupplierBusiness(User supplier) {
        Business business = businessService.requireOwn(supplier);
        if (!business.hasCapability(BusinessCapability.CAN_SUPPLY_BUSINESSES)) {
            throw ApiException.forbidden("Your business needs the 'supply other businesses' capability");
        }
        return business;
    }

    private StockOrderResponse toResponse(StockOrder o) {
        List<StockOrderItemResponse> items = o.getItems().stream()
                .map(i -> new StockOrderItemResponse(
                        i.getWholesaleProduct().getId(), i.getProductNameSnapshot(), i.getSkuSnapshot(),
                        i.getPackSizeSnapshot(), i.getQuantityPacks(), i.getPricePerPackSnapshot(), i.getLineTotal()))
                .toList();
        return new StockOrderResponse(
                o.getId(),
                o.getBuyerBusiness().getId(), o.getBuyerBusiness().getName(),
                o.getSupplierBusiness().getId(), o.getSupplierBusiness().getName(),
                items, o.getSubtotal(), o.getDeliveryFeeSnapshot(), o.getTotal(), o.getCurrency(),
                o.getStatus(), o.getFulfilmentMethod(), o.getDeliveryAddress(),
                o.getCreatedAt(), o.getUpdatedAt());
    }
}
