package com.momocircle.controller;

import com.momocircle.dto.MarketDtos.CheckoutRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.ConsumerPaystackCheckoutService;
import com.momocircle.service.ConsumerPaystackCheckoutService.ConsumerPaymentSession;
import com.momocircle.service.wallet.WalletCheckoutService;
import com.momocircle.service.wallet.WalletCheckoutService.Discrepancy;
import com.momocircle.service.wallet.WalletCheckoutService.WalletPaymentResult;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pay a PULA Market or PULA Food order from the SIMULATED PULA Money wallet, in two explicit steps:
 * <ol>
 *   <li>{@code POST /api/orders/checkout/wallet} creates the order with a server-computed total and reserves stock; it
 *       charges nothing and returns the authoritative amount to review;</li>
 *   <li>{@code POST /api/orders/{id}/pay/wallet} debits the wallet after an explicit confirmation of that amount.</li>
 * </ol>
 * The acting user always comes from the authenticated principal. The amount in step 2 is only compared with the stored
 * total; it is never used to charge. No real money moves anywhere in this flow.
 */
@RestController
public class WalletCheckoutController {

    /** What the buyer confirms: the amount they were shown. */
    public record WalletPayRequest(boolean confirm, BigDecimal expectedAmount) { }

    private final ConsumerPaystackCheckoutService checkout;
    private final WalletCheckoutService walletCheckout;
    private final CurrentUserService currentUser;
    private final boolean enabled;

    public WalletCheckoutController(ConsumerPaystackCheckoutService checkout, WalletCheckoutService walletCheckout,
                                    CurrentUserService currentUser,
                                    @Value("${app.payments.wallet-checkout.enabled:true}") boolean enabled) {
        this.checkout = checkout;
        this.walletCheckout = walletCheckout;
        this.currentUser = currentUser;
        this.enabled = enabled;
    }

    @PostMapping("/api/orders/checkout/wallet")
    public ResponseEntity<ConsumerPaymentSession> create(@Valid @RequestBody CheckoutRequest req,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireEnabled();
        return ResponseEntity.status(HttpStatus.CREATED).body(checkout.startWallet(currentUser.require(), req, idempotencyKey));
    }

    @PostMapping("/api/orders/{orderId}/pay/wallet")
    public WalletPaymentResult pay(@PathVariable String orderId, @RequestBody WalletPayRequest req) {
        requireEnabled();
        return walletCheckout.pay(currentUser.require(), orderId, req.confirm(), req.expectedAmount());
    }

    /** Operators only: wallet payments whose payment record, ledger transaction and order disagree. Reports; never repairs. */
    @GetMapping("/api/admin/wallet-payments/reconcile")
    public List<Discrepancy> reconcile(@RequestParam(defaultValue = "200") int limit) {
        currentUser.requireAdmin();
        return walletCheckout.reconcile(limit);
    }

    private void requireEnabled() {
        if (!enabled) throw ApiException.notFound("Not found");
    }
}
