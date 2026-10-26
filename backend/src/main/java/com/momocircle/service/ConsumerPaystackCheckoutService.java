package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.dto.MarketDtos.CheckoutRequest;
import com.momocircle.config.FoodProperties;
import com.momocircle.domain.CartItem;
import com.momocircle.domain.Order;
import com.momocircle.domain.OrderItem;
import com.momocircle.domain.Payment;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.PaymentPurpose;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.PaymentIdempotencyService;
import com.momocircle.payment.PaymentOrchestrator;
import com.momocircle.payment.PaystackGateway;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumer PULA Market checkout through Paystack (MARKET_PAYMENT). Used only when
 * app.payments.market-checkout=paystack; the original synchronous checkout is untouched otherwise.
 * The order is created in PAYMENT_PROCESSING and becomes PAID only after StockPaymentService.confirmByReference
 * verifies the payment with the provider. Prices and totals are recomputed here from the database.
 */
@Service
public class ConsumerPaystackCheckoutService {
    /** Phase 7 transaction-risk gate. Always injected in a running application; null only in hand-constructed unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.risk.PaymentGate riskGate;


    public record ConsumerPaymentSession(String orderId, String reference, String checkoutUrl, String mode,
                                         boolean sandbox, BigDecimal amount, String currency,
                                         PaymentStatus paymentStatus, OrderStatus orderStatus) {}

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CartService cartService;
    private final BusinessService businessService;
    private final ProductService productService;
    private final PaymentIdempotencyService idempotencyService;
    private final PaymentOrchestrator orchestrator;
    private final FoodCheckoutService foodCheckout;
    private final String callbackUrl;
    /** Optional (field-injected so existing construction is unchanged): branch stock for non-food orders that name a branch. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BranchStockService branchStock;


    public ConsumerPaystackCheckoutService(OrderRepository orderRepository, PaymentRepository paymentRepository,
                                           CartService cartService, BusinessService businessService,
                                           ProductService productService, PaymentIdempotencyService idempotencyService,
                                           PaymentOrchestrator orchestrator,
                                           FoodCheckoutService foodCheckout,
                                           @Value("${app.payments.paystack.callback-url:}") String callbackUrl) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.cartService = cartService;
        this.businessService = businessService;
        this.productService = productService;
        this.idempotencyService = idempotencyService;
        this.orchestrator = orchestrator;
        this.foodCheckout = foodCheckout;
        this.callbackUrl = callbackUrl;
    }

    /** Retail callers that only know the business id keep working; food checkouts pass the full request. */
    @Transactional
    public ConsumerPaymentSession start(User buyer, String businessId, String clientKey) {
        return start(buyer, new CheckoutRequest(businessId), clientKey);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SaveToBuyService saveToBuy;

    @Transactional
    public ConsumerPaymentSession start(User buyer, CheckoutRequest req, String clientKey) {
        return startInternal(buyer, req, clientKey, false);
    }

    /**
     * Creates the order for payment from the SIMULATED PULA Money wallet. Pricing, the risk gate, stock reservation and the
     * idempotency reservation are exactly those of {@link #start}; the only difference is that no gateway is contacted.
     * The order stays PAYMENT_PROCESSING until {@code WalletCheckoutService} has posted the ledger debit and the shared
     * confirmation path has verified it. Never call the ledger from inside this transaction.
     */
    @Transactional
    public ConsumerPaymentSession startWallet(User buyer, CheckoutRequest req, String clientKey) {
        return startInternal(buyer, req, clientKey, true);
    }

    private ConsumerPaymentSession startInternal(User buyer, CheckoutRequest req, String clientKey, boolean wallet) {
        String businessId = req.businessId();
        Business business = businessService.requireById(businessId);
        var items = cartService.itemsForBusiness(buyer, business.getId());
        if (items.isEmpty()) throw ApiException.badRequest("Your cart has no items from this seller");

        // Key is scoped to the buyer so one user's key can never resolve to another user's order.
        String key = (wallet ? "mktw:" : "market:") + buyer.getId() + ":" + (clientKey == null || clientKey.isBlank() ? UUID.randomUUID() : clientKey);

        // PULA Food: priced by the same FoodCheckoutService as the synchronous checkout.
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
            for (CartItem ci : items) {
                if (!ci.getProduct().getCurrency().equals(currency)) throw ApiException.badRequest("All items in one order must share a currency");
                BigDecimal unit = retailBranch != null ? branchStock.unitPriceAt(retailBranch, ci.getProduct()) : ci.getProduct().getPrice();
                branchPrices.put(ci.getProduct().getId(), unit);
                subtotal = subtotal.add(unit.multiply(BigDecimal.valueOf(ci.getQuantity())));
            }
        }
        if (retailBranch != null) branchStock.requireExpectedTotal(subtotal, req.expectedTotal(), currency);
        BigDecimal chargeAmount = food ? priced.total() : subtotal;
        // Phase 7 transaction-risk gate: runs BEFORE any reservation so a hold/block leaves nothing behind.
        boolean riskSimulated = wallet || "SANDBOX_MOCK".equals(orchestrator.marketMode());
        com.momocircle.risk.GateTicket riskTicket = riskGate == null ? null : riskGate.authorise(buyer,
                new com.momocircle.risk.GateRequest(com.momocircle.risk.RiskType.CONSUMER_CHECKOUT, key, chargeAmount, currency,
                        business.getId(), business.getId(), true, business.isActive(), riskSimulated));
        // Save-to-Buy (optional): validated before anything is reserved; the charge is still chargeAmount.
        com.momocircle.domain.SaveToBuyRequest s2b = saveToBuy == null ? null : saveToBuy.validateForCheckout(buyer,
                req.saveToBuyRequestId(), food ? com.momocircle.domain.enums.SaveToBuyVertical.FOOD
                        : com.momocircle.domain.enums.SaveToBuyVertical.RETAIL, chargeAmount, key);
        java.util.List<FoodCheckoutService.Claim> claims = food ? foodCheckout.claimCapacity(priced) : java.util.List.of();
        var reservation = idempotencyService.reserve(key, chargeAmount, currency,
                wallet ? com.momocircle.payment.PaymentRails.WALLET : orchestrator.marketProviderLabel());
        if (reservation.alreadyExisted()) {
            foodCheckout.releaseClaims(claims);
            Order existing = orderRepository.findByPaymentId(reservation.payment().getId())
                    .orElseThrow(() -> ApiException.conflict("A checkout with this key is already in progress"));
            if (!existing.getBuyer().getId().equals(buyer.getId())) throw ApiException.notFound("Order not found");
            return toSession(existing, existing.getPayment());
        }
        Payment payment = reservation.payment();
        Order order = new Order();
        order.setBuyer(buyer);
        order.setBusiness(business);
        order.setCurrency(currency);
        if (food) {
            foodCheckout.applyToOrder(order, priced);
        } else {
            for (CartItem ci : items) {
                Product product = ci.getProduct();
                if (retailBranch != null) branchStock.reserveForOrder(retailBranch, product, ci.getQuantity(), order.getId());
                else productService.reserveStock(product, ci.getQuantity());
                OrderItem oi = new OrderItem();
                oi.setOrder(order);
                oi.setProduct(product);
                oi.setProductNameSnapshot(product.getName());
                oi.setUnitPriceSnapshot(branchPrices.get(product.getId()));
                oi.setQuantity(ci.getQuantity());
                oi.setLineTotal(branchPrices.get(product.getId()).multiply(BigDecimal.valueOf(ci.getQuantity())));
                order.getItems().add(oi);
            }
            order.setSubtotal(subtotal);
            order.setTotal(subtotal);
            if (retailBranch != null) order.setBranch(retailBranch);
        }
        order.setStatus(OrderStatus.PAYMENT_PROCESSING);
        order.setPayment(payment);
        if (s2b != null) order.setSaveToBuyRequestId(s2b.getId());
        order = orderRepository.save(order);
        if (s2b != null) saveToBuy.attachOrder(s2b, com.momocircle.domain.enums.RelatedOrderKind.ORDER, order.getId(), key);

        String reference = "PULA-OD-" + order.getId();
        if (riskGate != null) riskGate.bind(buyer, riskTicket, reference, order.getTotal(), currency, riskSimulated);
        PaystackGateway.CheckoutSession session = null; // stays null for a wallet checkout: no gateway is involved
        if (!wallet) {
            try {
                session = orchestrator.startMarketCheckout(
                        new PaystackGateway.InitRequest(reference, order.getTotal(), currency, buyer.getEmail(), callbackUrl));
            } catch (RuntimeException e) {
                if (riskGate != null) riskGate.recordOutcome(riskTicket, false, "provider initiation failed");
                throw e;
            }
        }
        if (riskGate != null) riskGate.recordOutcome(riskTicket, true, "checkout started");

        payment.setProviderReference(reference);
        payment.setPurpose(PaymentPurpose.MARKET_PAYMENT);
        payment.setCheckoutUrl(wallet ? null : session.authorizationUrl());
        payment.transitionTo(PaymentStatus.PROCESSING);
        paymentRepository.save(payment);

        cartService.clearForBusiness(buyer, business.getId());
        return toSession(order, payment);
    }

    /** The buyer re-opens the payment session for their own unpaid order. */
    @Transactional(readOnly = true)
    public ConsumerPaymentSession session(User buyer, String orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!order.getBuyer().getId().equals(buyer.getId())) throw ApiException.notFound("Order not found");
        Payment p = order.getPayment();
        if (p == null || p.getPurpose() != PaymentPurpose.MARKET_PAYMENT) throw ApiException.notFound("No payment session for this order");
        return toSession(order, p);
    }

    private ConsumerPaymentSession toSession(Order o, Payment p) {
        String mode = com.momocircle.payment.PaymentRails.isWallet(p) ? com.momocircle.payment.PaymentRails.WALLET_MODE
                : orchestrator.marketMode();
        return new ConsumerPaymentSession(o.getId(), p.getProviderReference(), p.getCheckoutUrl(), mode,
                "SANDBOX_MOCK".equals(mode) || com.momocircle.payment.PaymentRails.isWallet(p), p.getAmount(), p.getCurrency(), p.getStatus(), o.getStatus());
    }
}
