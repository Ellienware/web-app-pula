package com.momocircle.service;

import com.momocircle.domain.enums.SaveToBuyVertical;
import com.momocircle.domain.enums.RelatedOrderKind;
import com.momocircle.domain.Business;
import com.momocircle.domain.CartItem;
import com.momocircle.domain.Order;
import com.momocircle.domain.OrderItem;
import com.momocircle.domain.Payment;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.config.FoodProperties;
import com.momocircle.domain.enums.OrderKind;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.RestaurantDecision;
import com.momocircle.domain.enums.CancellationCause;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.dto.MarketDtos.CheckoutRequest;
import com.momocircle.dto.MarketDtos.OrderItemResponse;
import com.momocircle.dto.MarketDtos.OrderResponse;
import com.momocircle.dto.MarketDtos.UpdateOrderStatusRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.ChargeRequest;
import com.momocircle.payment.ChargeResult;
import com.momocircle.payment.PaymentIdempotencyService;
import com.momocircle.payment.PaymentProvider;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {
    /** Phase 7 transaction-risk gate. Always injected in a running application; null only in hand-constructed unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.risk.PaymentGate riskGate;


    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CartService cartService;
    private final BusinessService businessService;
    private final ProductService productService;
    private final PaymentProvider paymentProvider;
    private final PaymentIdempotencyService paymentIdempotencyService;
    private final NotificationService notificationService;
    private final ReceiptService receiptService;
    private final FoodCheckoutService foodCheckout;
    private final FoodProperties foodProperties;
    private final OrderRefundService refundService;
    private final FoodDeliveryService foodDelivery;
    private final FoodSettlementService settlementService;
    /** Optional (field-injected so existing construction is unchanged): branch stock for non-food orders that name a branch. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BranchStockService branchStock;


    public OrderService(OrderRepository orderRepository,
                         PaymentRepository paymentRepository,
                         CartService cartService,
                         BusinessService businessService,
                         ProductService productService,
                         PaymentProvider paymentProvider,
                         PaymentIdempotencyService paymentIdempotencyService,
                         NotificationService notificationService,
                         ReceiptService receiptService,
                         FoodCheckoutService foodCheckout,
                         FoodProperties foodProperties,
                         OrderRefundService refundService,
                         FoodDeliveryService foodDelivery,
                         FoodSettlementService settlementService) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.cartService = cartService;
        this.businessService = businessService;
        this.productService = productService;
        this.paymentProvider = paymentProvider;
        this.paymentIdempotencyService = paymentIdempotencyService;
        this.notificationService = notificationService;
        this.receiptService = receiptService;
        this.foodCheckout = foodCheckout;
        this.foodProperties = foodProperties;
        this.refundService = refundService;
        this.foodDelivery = foodDelivery;
        this.settlementService = settlementService;
    }

    /**
     * Checks out every cart item the buyer has against one business as a single
     * order. Prices, stock and totals are always recomputed here from the
     * current Product rows — the client's cart view is a convenience, never
     * the source of truth.
     */
    /** Optional (field-injected) so existing construction is unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SaveToBuyService saveToBuy;

    /** Shared marketplace settlement engine (retail orders; food keeps its own hooks through FoodSettlementService). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.settlement.MarketplaceSettlementService marketplaceSettlement;

    private void retailPaid(Order order) {
        if (marketplaceSettlement != null && !order.isFood()) {
            marketplaceSettlement.openSafely(com.momocircle.domain.enums.SettlementSourceType.RETAIL_ORDER, order.getId());
        }
    }

    private void retailCompleted(Order order) {
        if (marketplaceSettlement != null && !order.isFood()) {
            marketplaceSettlement.onEarnedSafely(com.momocircle.domain.enums.SettlementSourceType.RETAIL_ORDER, order.getId());
        }
    }

    @Transactional
    public OrderResponse checkout(User buyer, CheckoutRequest req, String idempotencyKey) {
        Business business = businessService.requireById(req.businessId());
        List<CartItem> items = cartService.itemsForBusiness(buyer, business.getId());
        if (items.isEmpty()) {
            throw ApiException.badRequest("Your cart has no items from this seller");
        }

        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                ? UUID.randomUUID().toString()
                : idempotencyKey;

        // PULA Food: a food business goes through FoodCheckoutService (modifiers, branch,
        // fulfilment chosen now, delivery fee). Every other business takes the original retail
        // path below, unchanged.
        boolean food = FoodCheckoutService.isFoodBusiness(business);
        FoodCheckoutService.PricedFoodOrder priced = food ? foodCheckout.price(business, items, req) : null;
        if (food) foodCheckout.requireExpectedTotal(priced, req);

        String currency = food ? priced.currency() : items.get(0).getProduct().getCurrency();
        BigDecimal subtotal = BigDecimal.ZERO;
        // Non-food order for a chosen branch: that branch's price and stock apply. No branchId keeps the original central-stock path.
        com.momocircle.domain.BusinessBranch retailBranch =
                (!food && branchStock != null) ? branchStock.resolveForCheckout(business, req.branchId()) : null;
        java.util.Map<String, BigDecimal> branchPrices = new java.util.HashMap<>();
        if (!food) {
            for (CartItem cartItem : items) {
                if (!cartItem.getProduct().getCurrency().equals(currency)) {
                    throw ApiException.badRequest("All items in one order must share a currency");
                }
                BigDecimal unit = retailBranch != null
                        ? branchStock.unitPriceAt(retailBranch, cartItem.getProduct()) : cartItem.getProduct().getPrice();
                branchPrices.put(cartItem.getProduct().getId(), unit);
                subtotal = subtotal.add(unit.multiply(BigDecimal.valueOf(cartItem.getQuantity())));
            }
        }
        if (retailBranch != null) branchStock.requireExpectedTotal(subtotal, req.expectedTotal(), currency);
        BigDecimal chargeAmount = food ? priced.total() : subtotal;

        // Phase 7 transaction-risk gate: BEFORE any reservation (reserve() commits in its own transaction, so gating later would
        // leave a stuck PENDING payment behind a hold/block).
        boolean riskSimulated = com.momocircle.risk.PaymentGate.isSimulatedProvider(paymentProvider.name());
        com.momocircle.risk.GateTicket riskTicket = riskGate == null ? null : riskGate.authorise(buyer,
                new com.momocircle.risk.GateRequest(com.momocircle.risk.RiskType.LEGACY_ORDER, key, chargeAmount, currency,
                        business.getId(), business.getId(), true, business.isActive(), riskSimulated));
        // Save-to-Buy (optional): validated BEFORE anything is reserved or charged; the charge is still chargeAmount.
        com.momocircle.domain.SaveToBuyRequest s2b = saveToBuy == null ? null : saveToBuy.validateForCheckout(buyer,
                req.saveToBuyRequestId(), food ? SaveToBuyVertical.FOOD : SaveToBuyVertical.RETAIL, chargeAmount, key);

        // Portions are claimed before the payment reservation so a sold-out item never leaves an
        // orphaned reservation behind; if the key turns out to be a duplicate they are given back.
        java.util.List<FoodCheckoutService.Claim> claims = food ? foodCheckout.claimCapacity(priced) : java.util.List.of();

        // Concurrency-safe idempotency: reserve() runs in its own transaction and
        // resolves any race against a simultaneous duplicate checkout via the DB's
        // unique constraint on idempotency_key, not just an in-app check-then-insert.
        // Doing this before touching stock/Order means a losing duplicate request
        // never provisionally reserves stock it will just have to release.
        var reservation = paymentIdempotencyService.reserve(key, chargeAmount, currency, paymentProvider.name());
        if (reservation.alreadyExisted()) {
            foodCheckout.releaseClaims(claims);
            Order existingOrder = orderRepository.findByPaymentId(reservation.payment().getId()).orElse(null);
            if (existingOrder != null) {
                // Phase 8: an idempotency key must never replay another user's order.
                if (existingOrder.getBuyer() == null || !existingOrder.getBuyer().getId().equals(buyer.getId())) {
                    throw ApiException.notFound("Order not found");
                }
                return toResponse(existingOrder);
            }
            // Payment exists but no order reached that point yet (crashed mid-checkout, or a
            // concurrent request that is still in the middle of creating it) — safest is to
            // refuse the retry rather than risk a second charge under the same key.
            throw ApiException.conflict("A checkout with this idempotency key is already in progress");
        }
        Payment payment = reservation.payment();

        Order order = new Order();
        order.setBuyer(buyer);
        order.setBusiness(business);
        order.setCurrency(currency);

        if (food) {
            foodCheckout.applyToOrder(order, priced);
        } else {
            for (CartItem cartItem : items) {
                Product product = cartItem.getProduct();
                if (retailBranch != null) branchStock.reserveForOrder(retailBranch, product, cartItem.getQuantity(), order.getId());
                else productService.reserveStock(product, cartItem.getQuantity());

                OrderItem orderItem = new OrderItem();
                orderItem.setOrder(order);
                orderItem.setProduct(product);
                orderItem.setProductNameSnapshot(product.getName());
                orderItem.setUnitPriceSnapshot(branchPrices.get(product.getId()));
                orderItem.setQuantity(cartItem.getQuantity());
                orderItem.setLineTotal(branchPrices.get(product.getId()).multiply(BigDecimal.valueOf(cartItem.getQuantity())));
                order.getItems().add(orderItem);
            }
            order.setSubtotal(subtotal);
            order.setTotal(subtotal); // no fees/discounts in this slice
            if (retailBranch != null) order.setBranch(retailBranch);
        }
        order.setStatus(OrderStatus.PAYMENT_PROCESSING);
        order.setPayment(payment);
        if (s2b != null) order.setSaveToBuyRequestId(s2b.getId());
        order = orderRepository.save(order);
        if (s2b != null) saveToBuy.attachOrder(s2b, RelatedOrderKind.ORDER, order.getId(), key);

        if (riskGate != null) riskGate.bind(buyer, riskTicket, key, order.getTotal(), order.getCurrency(), riskSimulated);
        ChargeResult result = paymentProvider.charge(new ChargeRequest(
                order.getTotal(),
                order.getCurrency(),
                buyer.getPhoneNumber(),
                key,
                "PULA Market order at " + business.getName()));
        if (riskGate != null) riskGate.recordOutcome(riskTicket, result.status() != PaymentStatus.FAILED, "charge " + result.status());

        payment.transitionTo(result.status());
        payment.setProviderReference(result.providerReference());
        payment.setFailureReason(result.failureReason());
        paymentRepository.save(payment);

        if (result.status() == PaymentStatus.SUCCESSFUL) {
            order.setStatus(OrderStatus.PAID);
            startAcceptWindow(order);
        } else if (result.status() == PaymentStatus.FAILED) {
            order.setStatus(OrderStatus.CANCELLED);
            if (order.isFood()) order.setCancellationCause(CancellationCause.PAYMENT_FAILURE);
            // Payment didn't go through — release what we provisionally reserved above.
            releaseHeldInventory(order);
        }
        order = orderRepository.save(order);
        if (s2b != null) {
            if (order.getStatus() == OrderStatus.PAID) {
                String sid = saveToBuy.onOrderPaid(RelatedOrderKind.ORDER, order.getId(), order.getTotal());
                if (sid != null) { order.setStokvelRequestId(sid); order = orderRepository.save(order); }
            } else if (order.getStatus() == OrderStatus.CANCELLED) {
                saveToBuy.onOrderFailed(RelatedOrderKind.ORDER, order.getId());
            }
        }
        // PULA Food: the customer's money is in, so the restaurant's settlement row exists (NOT_ELIGIBLE: nothing payable yet).
        if (order.isFood() && order.getStatus() == OrderStatus.PAID) settlementService.openForPaidOrder(order);
        if (order.getStatus() == OrderStatus.PAID) retailPaid(order);

        cartService.clearForBusiness(buyer, business.getId());

        if (order.getStatus() == OrderStatus.PAID) {
            notificationService.notifyOrderReceived(order);
            receiptService.issue(payment, buyer, business.getName(),
                    "Order at " + business.getName() + " (" + order.getItems().size() + " item(s))");
        }

        return toResponse(order);
    }

    // ------------------------------------------------------------------ PULA Food helpers

    /** Read-only price preview for a food checkout. Same validation and pricing as {@link #checkout}; no side effects. */
    @Transactional(readOnly = true)
    public com.momocircle.dto.MarketDtos.CheckoutQuoteResponse quoteFoodCheckout(User buyer, CheckoutRequest req) {
        Business business = businessService.requireById(req.businessId());
        if (!FoodCheckoutService.isFoodBusiness(business)) {
            throw ApiException.badRequest("Quotes are only available for food orders");
        }
        List<CartItem> items = cartService.itemsForBusiness(buyer, business.getId());
        if (items.isEmpty()) {
            throw ApiException.badRequest("Your cart has no items from this seller");
        }
        FoodCheckoutService.PricedFoodOrder p = foodCheckout.price(business, items, req);
        List<com.momocircle.dto.MarketDtos.CheckoutQuoteLine> lines = p.lines().stream()
                .map(l -> new com.momocircle.dto.MarketDtos.CheckoutQuoteLine(
                        l.product().getId(), l.product().getName(), l.quantity(), l.unitPrice(),
                        l.modifiers().stream().map(m -> new com.momocircle.dto.MarketDtos.OrderModifierResponse(
                                m.getName(), m.getPriceDelta())).toList(),
                        l.lineTotal()))
                .toList();
        return new com.momocircle.dto.MarketDtos.CheckoutQuoteResponse(business.getId(), business.getName(),
                p.branch() == null ? null : p.branch().getId(), p.branch() == null ? null : p.branch().getName(),
                p.fulfilment(), p.deliveryAddress(), lines, p.subtotal(), p.deliveryFee(), p.total(), p.currency(), p.note());
    }

    /** Gives back stock (retail) or portions (food) that an unsuccessful order was holding. */
    private void releaseHeldInventory(Order order) {
        if (order.isFood()) {
            foodCheckout.releaseCapacity(order);
        } else {
            if (order.getBranch() != null && branchStock != null) {
                branchStock.releaseForOrder(order); // the units came from this branch's allocation, not the central pool
            } else {
                for (OrderItem item : order.getItems()) {
                    productService.releaseStock(item.getProduct(), item.getQuantity());
                }
            }
        }
    }

    /** A paid food order must be answered by the restaurant before this deadline or it is refunded. */
    private void startAcceptWindow(Order order) {
        if (!order.isFood()) return;
        Integer own = order.getBusiness().getAcceptTimeoutMinutes();
        int minutes = own == null ? foodProperties.getAcceptTimeoutMinutes()
                : Math.min(own, foodProperties.getMaxAcceptTimeoutMinutes());
        order.setAcceptDeadlineAt(Instant.now().plus(Duration.ofMinutes(minutes)));
    }

    /** Restaurant accepts a paid food order: it moves to PROCESSING ("Preparing"). Idempotent. */
    @Transactional
    public OrderResponse acceptFoodOrder(User seller, String orderId) {
        Order order = requireFoodOrderForDecision(seller, orderId);
        if (order.getRestaurantDecision() == RestaurantDecision.ACCEPTED) return toResponse(order);
        requireAwaitingDecision(order);
        order.setRestaurantDecision(RestaurantDecision.ACCEPTED);
        order.setDecisionAt(Instant.now());
        order.setStatus(OrderStatus.PROCESSING);
        order = orderRepository.save(order);
        notificationService.notifyOrderStatusChanged(order);
        return toResponse(order);
    }

    /** Restaurant rejects a paid food order: portions are released and the refund starts. Idempotent. */
    @Transactional
    public OrderResponse rejectFoodOrder(User seller, String orderId, String reason) {
        Order order = requireFoodOrderForDecision(seller, orderId);
        if (order.getRestaurantDecision() == RestaurantDecision.REJECTED) return toResponse(order);
        requireAwaitingDecision(order);
        String why = reason == null || reason.isBlank() ? "The restaurant could not accept this order" : reason.trim();
        return toResponse(rejectAndRefund(order, why, CancellationCause.RESTAURANT_REJECTED));
    }

    /**
     * Called by the maintenance task for each food order past its accept deadline. Re-checks under
     * a row lock, so an accept that landed first wins and this becomes a no-op.
     */
    @Transactional
    public void autoRejectIfExpired(String orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || !order.isFood()
                || order.getStatus() != OrderStatus.PAID
                || order.getRestaurantDecision() != RestaurantDecision.PENDING
                || order.getAcceptDeadlineAt() == null
                || order.getAcceptDeadlineAt().isAfter(Instant.now())) {
            return;
        }
        rejectAndRefund(order, "The restaurant did not respond in time", CancellationCause.RESTAURANT_EXPIRED);
    }

    /**
     * Customer cancels their own food order, with a full refund, in the two cases the rules allow:
     * before the restaurant has decided, or once a PULA Ride delivery has gone unclaimed past the
     * no-driver timeout. Anything else (being prepared, a driver assigned, on its way) is refused.
     * Idempotent. Partial refunds (e.g. delivery fee only) are not supported by the payment provider
     * interface, so there is no "switch to collection" refund path yet.
     */
    @Transactional
    public OrderResponse cancelFoodOrderByCustomer(User buyer, String orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!order.getBuyer().getId().equals(buyer.getId())) throw ApiException.notFound("Order not found");
        if (!order.isFood()) throw ApiException.badRequest("Only food orders can be cancelled here");
        if (order.getRestaurantDecision() == RestaurantDecision.WITHDRAWN) return toResponse(order); // already done

        if (order.getStatus() == OrderStatus.PAID && order.getRestaurantDecision() == RestaurantDecision.PENDING) {
            order.setRestaurantDecision(RestaurantDecision.WITHDRAWN);
            order.setDecisionAt(Instant.now());
            order.setRejectionReason("Cancelled by the customer");
            order.setCancellationCause(CancellationCause.CUSTOMER_CANCELLED); // before preparation: settlement cancelled, no compensation
            foodCheckout.releaseCapacity(order);
        } else if (order.getStatus() == OrderStatus.READY
                && order.getFulfilmentMethod() == FulfilmentOption.MOMORIDE_DELIVERY) {
            // Throws unless the delivery still has no driver and has waited past the timeout.
            // The food was already prepared, so portions are NOT returned.
            foodDelivery.cancelUnclaimedDeliveryForCustomer(order);
            order.setRestaurantDecision(RestaurantDecision.WITHDRAWN);
            order.setRejectionReason("Cancelled by the customer: no driver was found");
            // The system verified PULA found no driver within the window before allowing this cancel, so the
            // cause is PULA's fulfilment failure, not a customer's change of mind.
            order.setCancellationCause(CancellationCause.PULA_DELIVERY_FAILURE);
        } else {
            throw ApiException.badRequest("This order can't be cancelled now. Contact the restaurant or PULA support.");
        }
        order.setStatus(OrderStatus.REFUND_PENDING);
        order = orderRepository.save(order);
        refundService.attemptRefund(order);
        notificationService.notifyOrderStatusChanged(order);
        notificationService.notify(order.getBusiness().getOwner(),
                com.momocircle.domain.enums.NotificationType.SYSTEM,
                "A customer cancelled an order.", order.getId());
        return toResponse(order);
    }

    private Order rejectAndRefund(Order order, String reason, CancellationCause cause) {
        order.setRestaurantDecision(RestaurantDecision.REJECTED);
        order.setCancellationCause(cause);
        order.setDecisionAt(Instant.now());
        order.setRejectionReason(reason);
        foodCheckout.releaseCapacity(order);
        order.setStatus(OrderStatus.REFUND_PENDING);
        order = orderRepository.save(order);
        // Tries the refund now; if it can't complete the order stays REFUND_PENDING and is retried.
        refundService.attemptRefund(order);
        notificationService.notifyOrderStatusChanged(order);
        return order;
    }

    private Order requireFoodOrderForDecision(User seller, String orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        businessService.requireOwnership(order.getBusiness(), seller);
        if (!order.isFood()) throw ApiException.badRequest("Only food orders can be accepted or rejected");
        return order;
    }

    private void requireAwaitingDecision(Order order) {
        if (order.getStatus() != OrderStatus.PAID || order.getRestaurantDecision() != RestaurantDecision.PENDING) {
            throw ApiException.badRequest("This order is not waiting for a decision");
        }
    }

    @Transactional
    public OrderResponse updateStatus(User seller, String orderId, UpdateOrderStatusRequest req) {
        Order order = requireById(orderId);
        businessService.requireOwnership(order.getBusiness(), seller);
        return toResponse(applyStatusChange(order, req.status()));
    }

    /**
     * Fulfilment step taken by BRANCH staff (authorised by BranchOrderService: FULFIL_ORDERS at the order's own branch). Retail orders
     * only, and only the three fulfilment steps: no cancellation, refund or food handling. The state machine and side effects are the
     * same as the owner's {@link #updateStatus}.
     */
    @Transactional
    public Order fulfilBranchOrder(Order order, OrderStatus status) {
        if (order.isFood() || order.getBranch() == null) throw ApiException.badRequest("Only branch retail orders can be fulfilled here");
        if (status != OrderStatus.PROCESSING && status != OrderStatus.READY && status != OrderStatus.COMPLETED) {
            throw ApiException.badRequest("Branch staff can only mark an order processing, ready or completed");
        }
        return applyStatusChange(order, status);
    }

    private Order applyStatusChange(Order order, OrderStatus newStatus) {
        if (order.isFood()) {
            // PULA Food orders are decided through accept/reject, refunded through the refund flow,
            // and PULA Ride deliveries are completed by the driver — not by a status PATCH.
            if (order.getStatus() == OrderStatus.PAID) {
                throw ApiException.badRequest("Accept or reject this order first");
            }
            if (newStatus == OrderStatus.REFUND_PENDING || newStatus == OrderStatus.REFUNDED) {
                throw ApiException.badRequest("Refunds are handled through the reject flow or by PULA support");
            }
            if (newStatus == OrderStatus.COMPLETED && order.getFulfilmentMethod() == FulfilmentOption.MOMORIDE_DELIVERY) {
                throw ApiException.badRequest("PULA Ride deliveries are completed when the driver confirms delivery");
            }
        }
        if (!order.getStatus().canTransitionTo(newStatus)) {
            throw ApiException.badRequest(
                    "Cannot move order from " + order.getStatus() + " to " + newStatus);
        }
        order.setStatus(newStatus);
        if (order.isFood() && newStatus == OrderStatus.READY) order.setReadyAt(Instant.now()); // the food is prepared
        order = orderRepository.save(order);
        if (order.isFood() && newStatus == OrderStatus.COMPLETED) settlementService.onOrderCompleted(order); // collection
        if (newStatus == OrderStatus.COMPLETED) retailCompleted(order);
        if (newStatus == OrderStatus.READY) {
            // PULA Food: the customer chose PULA Ride delivery at checkout, so when the restaurant marks
            // the order ready, driver matching starts automatically — no further customer or seller action.
            // Collection and own-rider orders are ignored by the service. Same transaction: if dispatch
            // fails, the READY change rolls back with it.
            foodDelivery.dispatchIfEligible(order);
        }
        notificationService.notifyOrderStatusChanged(order);
        return order;
    }

    /**
     * Seller sets (or changes) how this order will be fulfilled. Only the
     * seller can set this — the buyer chooses at checkout time is a
     * reasonable future extension, not implemented in this slice, since
     * today's checkout doesn't collect fulfilment preferences yet.
     */
    @Transactional
    public OrderResponse setFulfilment(User seller, String orderId, com.momocircle.dto.MarketDtos.SetOrderFulfilmentRequest req) {
        Order order = requireById(orderId);
        businessService.requireOwnership(order.getBusiness(), seller);
        if (order.isFood()) {
            throw ApiException.badRequest("The customer chose collection or delivery at checkout; it cannot be changed here");
        }

        if ((req.fulfilmentMethod() == com.momocircle.domain.enums.FulfilmentOption.SELLER_DELIVERY
                || req.fulfilmentMethod() == com.momocircle.domain.enums.FulfilmentOption.MOMORIDE_DELIVERY)
                && (req.deliveryAddress() == null || req.deliveryAddress().isBlank())) {
            throw ApiException.badRequest("A delivery address is required for this fulfilment method.");
        }
        if (!order.getBusiness().getFulfilmentOptions().contains(req.fulfilmentMethod())) {
            throw ApiException.badRequest("This seller does not support that fulfilment method.");
        }
        order.setFulfilmentMethod(req.fulfilmentMethod());
        order.setDeliveryAddress(req.deliveryAddress());
        return toResponse(orderRepository.save(order));
    }

    /** Package-visible: only DeliveryRequestService should be completing an order this way — see its javadoc. */
    Order requireOrderEntity(String orderId) {
        return requireById(orderId);
    }

    /**
     * Called only by DeliveryRequestService once a driver confirms delivery.
     * Deliberately bypasses the seller-ownership check that {@link #updateStatus}
     * enforces — the driver, not the seller, is the legitimate actor completing
     * this specific transition, having already been verified as the delivery's
     * assigned driver by the caller. The OrderStatus transition whitelist itself
     * is NOT bypassed: this still only succeeds if READY -> COMPLETED (or
     * whatever the order's current state legally allows) is a valid transition.
     */
    @Transactional
    void markCompletedByDelivery(Order order) {
        if (!order.getStatus().canTransitionTo(OrderStatus.COMPLETED)) {
            throw ApiException.badRequest(
                    "Cannot complete order from status " + order.getStatus() + ".");
        }
        order.setStatus(OrderStatus.COMPLETED);
        order = orderRepository.save(order);
        // Restaurant settlement becomes eligible (after the configured dispute window). Never paid inline.
        if (order.isFood()) settlementService.onOrderCompleted(order);
        retailCompleted(order);
        notificationService.notifyOrderStatusChanged(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(User actor, String orderId) {
        Order order = requireById(orderId);
        boolean isBuyer = order.getBuyer().getId().equals(actor.getId());
        boolean isSeller = order.getBusiness().getOwner().getId().equals(actor.getId());
        if (!isBuyer && !isSeller) {
            throw ApiException.forbidden("You cannot view this order");
        }
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> myOrders(User buyer, Pageable pageable) {
        return orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyer.getId(), pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> sellerOrders(User seller, Pageable pageable) {
        Business business = businessService.requireOwn(seller);
        return orderRepository.findByBusinessIdOrderByCreatedAtDesc(business.getId(), pageable).map(this::toResponse);
    }

    private Order requireById(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
    }

    /**
     * Applies the same side effects a synchronous PAID transition gets in
     * {@link #checkout}, for an order whose payment just resolved out of
     * PROCESSING via reconciliation (see PaymentReconciliationService).
     * Caller must have already set payment.status to SUCCESSFUL and saved it.
     */
    @Transactional
    public void applyResolvedSuccess(Order order, Payment payment) {
        order.setStatus(OrderStatus.PAID);
        startAcceptWindow(order);
        order = orderRepository.save(order);
        if (saveToBuy != null && order.getSaveToBuyRequestId() != null) {
            String sid = saveToBuy.onOrderPaid(RelatedOrderKind.ORDER, order.getId(), order.getTotal());
            if (sid != null) { order.setStokvelRequestId(sid); order = orderRepository.save(order); }
        }
        if (order.isFood()) settlementService.openForPaidOrder(order);
        retailPaid(order);
        notificationService.notifyOrderReceived(order);
        receiptService.issue(payment, order.getBuyer(), order.getBusiness().getName(),
                "Order at " + order.getBusiness().getName() + " (" + order.getItems().size() + " item(s))");
    }

    /**
     * Mirror of the checkout() failure branch: releases the stock that was
     * provisionally reserved, since the charge never actually completed.
     */
    @Transactional
    public void applyResolvedFailure(Order order) {
        order.setStatus(OrderStatus.CANCELLED);
        if (order.isFood()) order.setCancellationCause(CancellationCause.PAYMENT_FAILURE);
        releaseHeldInventory(order);
        orderRepository.save(order);
        if (saveToBuy != null && order.getSaveToBuyRequestId() != null) {
            saveToBuy.onOrderFailed(RelatedOrderKind.ORDER, order.getId());
        }
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(i -> new OrderItemResponse(
                        i.getProduct().getId(), i.getProductNameSnapshot(),
                        i.getUnitPriceSnapshot(), i.getQuantity(), i.getLineTotal(),
                        i.getModifiers().stream()
                                .map(m -> new com.momocircle.dto.MarketDtos.OrderModifierResponse(
                                        m.getNameSnapshot(), m.getPriceDeltaSnapshot()))
                                .toList()))
                .toList();
        return new OrderResponse(
                order.getId(), order.getBuyer().getId(), order.getBuyer().getFullName(),
                order.getBusiness().getId(), order.getBusiness().getName(),
                items, order.getSubtotal(), order.getTotal(), order.getCurrency(),
                order.getStatus(), order.getFulfilmentMethod(), order.getDeliveryAddress(), order.getCreatedAt(),
                order.getKind(), order.getBranch() == null ? null : order.getBranch().getId(),
                order.getRestaurantDecision(), order.getRejectionReason(), order.getAcceptDeadlineAt(),
                order.getDeliveryFeeSnapshot(), order.getCustomerNote());
    }
}
