package com.momocircle.controller;

import com.momocircle.security.CurrentUserService;
import com.momocircle.service.StockPaymentService;
import com.momocircle.service.StockPaymentService.StockPaymentSession;
import com.momocircle.service.wallet.StockWalletCheckoutService;
import com.momocircle.service.wallet.StockWalletCheckoutService.StockWalletPaymentResult;
import com.momocircle.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Wholesale stock orders paid from the buying business's SIMULATED wallet, in two explicit steps: start (no charge, returns the
 * authoritative amount) then pay (debits after explicit confirmation of that amount). Same feature flag as the retail wallet checkout.
 */
@RestController
public class StockWalletCheckoutController {

    public record StockWalletPayRequest(boolean confirm, BigDecimal expectedAmount) { }

    private final StockPaymentService stockPayments;
    private final StockWalletCheckoutService walletCheckout;
    private final CurrentUserService currentUser;
    private final boolean enabled;

    public StockWalletCheckoutController(StockPaymentService stockPayments, StockWalletCheckoutService walletCheckout,
                                         CurrentUserService currentUser,
                                         @Value("${app.payments.wallet-checkout.enabled:true}") boolean enabled) {
        this.stockPayments = stockPayments;
        this.walletCheckout = walletCheckout;
        this.currentUser = currentUser;
        this.enabled = enabled;
    }

    @PostMapping("/api/stock/orders/mine/{orderId}/pay/wallet/start")
    public StockPaymentSession start(@PathVariable String orderId, @RequestParam(defaultValue = "false") boolean acknowledged) {
        requireEnabled();
        return stockPayments.startWallet(currentUser.require(), orderId, acknowledged);
    }

    @PostMapping("/api/stock/orders/mine/{orderId}/pay/wallet")
    public StockWalletPaymentResult pay(@PathVariable String orderId, @RequestBody StockWalletPayRequest req) {
        requireEnabled();
        return walletCheckout.pay(currentUser.require(), orderId, req.confirm(), req.expectedAmount());
    }

    private void requireEnabled() {
        if (!enabled) throw ApiException.notFound("Not found");
    }
}
