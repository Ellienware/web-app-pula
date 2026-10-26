package com.momocircle.controller;

import com.momocircle.dto.MarketDtos.CheckoutRequest;
import com.momocircle.dto.MarketDtos.OrderResponse;
import com.momocircle.dto.MarketDtos.UpdateOrderStatusRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orderService;
    private final CurrentUserService currentUser;
    private final com.momocircle.service.ConsumerPaystackCheckoutService paystackCheckout;
    private final String marketCheckout;
    private final com.momocircle.service.StockPaymentService stockPayments;

    public OrderController(OrderService orderService, CurrentUserService currentUser,
                           com.momocircle.service.ConsumerPaystackCheckoutService paystackCheckout,
                           com.momocircle.service.StockPaymentService stockPayments,
                           @org.springframework.beans.factory.annotation.Value("${app.payments.market-checkout:legacy}") String marketCheckout) {
        this.paystackCheckout = paystackCheckout;
        this.stockPayments = stockPayments;
        this.marketCheckout = marketCheckout;
        this.orderService = orderService;
        this.currentUser = currentUser;
    }

    /**
     * The Idempotency-Key header lets a client safely retry a checkout call
     * (e.g. after a network timeout) without risking a duplicate order/charge.
     */
    @PostMapping("/api/orders/checkout")
    public ResponseEntity<OrderResponse> checkout(@Valid @RequestBody CheckoutRequest req,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.checkout(currentUser.require(), req, idempotencyKey));
    }

    /** PULA Food: preview the full total (items, extras, delivery fee) before confirming. Charges nothing. */
    @PostMapping("/api/orders/checkout/quote")
    public com.momocircle.dto.MarketDtos.CheckoutQuoteResponse quote(@Valid @RequestBody CheckoutRequest req) {
        return orderService.quoteFoodCheckout(currentUser.require(), req);
    }

    /** Paystack checkout for consumer Market orders. Only available when app.payments.market-checkout=paystack. */
    @PostMapping("/api/orders/checkout/paystack")
    public ResponseEntity<com.momocircle.service.ConsumerPaystackCheckoutService.ConsumerPaymentSession> checkoutPaystack(
            @Valid @RequestBody CheckoutRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (!"paystack".equalsIgnoreCase(marketCheckout)) {
            throw com.momocircle.exception.ApiException.notFound("Not found");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paystackCheckout.start(currentUser.require(), req, idempotencyKey));
    }

    /** Ask PULA to re-verify the buyer's own Paystack payment with the provider. Never accepts a client-supplied result. */
    @PostMapping("/api/orders/{orderId}/payment/refresh")
    public com.momocircle.service.ConsumerPaystackCheckoutService.ConsumerPaymentSession refreshPayment(@PathVariable String orderId) {
        var mine = paystackCheckout.session(currentUser.require(), orderId); // ownership check
        stockPayments.confirmByReference(mine.reference());
        return paystackCheckout.session(currentUser.require(), orderId);
    }

    /** Resume an unpaid Paystack order's payment session (own orders only). */
    @GetMapping("/api/orders/{orderId}/payment-session")
    public com.momocircle.service.ConsumerPaystackCheckoutService.ConsumerPaymentSession paymentSession(@PathVariable String orderId) {
        return paystackCheckout.session(currentUser.require(), orderId);
    }

    @GetMapping("/api/orders/{orderId}")
    public OrderResponse get(@PathVariable String orderId) {
        return orderService.get(currentUser.require(), orderId);
    }

    @GetMapping("/api/orders/mine")
    public Page<OrderResponse> mine(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return orderService.myOrders(currentUser.require(), PageRequest.of(page, Math.min(size, 50)));
    }

    @GetMapping("/api/orders/selling")
    public Page<OrderResponse> selling(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return orderService.sellerOrders(currentUser.require(), PageRequest.of(page, Math.min(size, 50)));
    }

    /** PULA Food: the restaurant accepts a paid order and starts preparing it. */
    @PostMapping("/api/orders/{orderId}/accept")
    public OrderResponse accept(@PathVariable String orderId) {
        return orderService.acceptFoodOrder(currentUser.require(), orderId);
    }

    /** PULA Food: the restaurant rejects a paid order; the customer is refunded. */
    @PostMapping("/api/orders/{orderId}/reject")
    public OrderResponse reject(@PathVariable String orderId,
                                @Valid @RequestBody(required = false) com.momocircle.dto.MarketDtos.RejectOrderRequest req) {
        return orderService.rejectFoodOrder(currentUser.require(), orderId, req == null ? null : req.reason());
    }

    /** PULA Food: the customer cancels their own order where the rules allow (full refund). */
    @PostMapping("/api/orders/{orderId}/cancel")
    public OrderResponse cancel(@PathVariable String orderId) {
        return orderService.cancelFoodOrderByCustomer(currentUser.require(), orderId);
    }

    @PatchMapping("/api/orders/{orderId}/status")
    public OrderResponse updateStatus(@PathVariable String orderId, @Valid @RequestBody UpdateOrderStatusRequest req) {
        return orderService.updateStatus(currentUser.require(), orderId, req);
    }

    @PatchMapping("/api/orders/{orderId}/fulfilment")
    public OrderResponse setFulfilment(@PathVariable String orderId,
                                        @Valid @RequestBody com.momocircle.dto.MarketDtos.SetOrderFulfilmentRequest req) {
        return orderService.setFulfilment(currentUser.require(), orderId, req);
    }
}
