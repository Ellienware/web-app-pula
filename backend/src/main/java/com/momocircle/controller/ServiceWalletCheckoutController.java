package com.momocircle.controller;

import com.momocircle.dto.ServiceDtos.PayRequest;
import com.momocircle.dto.ServiceDtos.PaymentSessionView;
import com.momocircle.exception.ApiException;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.ServiceBookingService;
import com.momocircle.service.wallet.ServiceWalletCheckoutService;
import com.momocircle.service.wallet.ServiceWalletCheckoutService.ServiceWalletPaymentResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/** Service bookings paid from the customer's SIMULATED wallet: start (no charge) then confirm the server amount. */
@RestController
public class ServiceWalletCheckoutController {

    public record ServiceWalletPayRequest(boolean confirm, BigDecimal expectedAmount) { }

    private final ServiceBookingService bookings;
    private final ServiceWalletCheckoutService walletCheckout;
    private final CurrentUserService currentUser;
    private final boolean enabled;

    public ServiceWalletCheckoutController(ServiceBookingService bookings, ServiceWalletCheckoutService walletCheckout,
                                           CurrentUserService currentUser,
                                           @Value("${app.payments.wallet-checkout.enabled:true}") boolean enabled) {
        this.bookings = bookings;
        this.walletCheckout = walletCheckout;
        this.currentUser = currentUser;
        this.enabled = enabled;
    }

    @PostMapping("/api/services/bookings/{id}/pay/wallet/start")
    public PaymentSessionView start(@PathVariable String id, @RequestBody(required = false) PayRequest req) {
        requireEnabled();
        return bookings.startWalletPayment(currentUser.require(), id, req);
    }

    @PostMapping("/api/services/bookings/{id}/pay/wallet")
    public ServiceWalletPaymentResult pay(@PathVariable String id, @RequestBody ServiceWalletPayRequest req) {
        requireEnabled();
        return walletCheckout.pay(currentUser.require(), id, req.confirm(), req.expectedAmount());
    }

    private void requireEnabled() {
        if (!enabled) throw ApiException.notFound("Not found");
    }
}
