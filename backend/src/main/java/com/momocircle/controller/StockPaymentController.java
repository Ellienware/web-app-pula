package com.momocircle.controller;

import com.momocircle.domain.Payment;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.MockPaystackGateway;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.StockPaymentService;
import com.momocircle.service.StockPaymentService.StockPaymentSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StockPaymentController {

    private final StockPaymentService service;
    private final CurrentUserService currentUser;
    private final ObjectProvider<MockPaystackGateway> mockGateway;

    public StockPaymentController(StockPaymentService service, CurrentUserService currentUser,
                                  ObjectProvider<MockPaystackGateway> mockGateway) {
        this.service = service;
        this.currentUser = currentUser;
        this.mockGateway = mockGateway;
    }

    /** Start or resume payment. The amount is server-side; the client sends nothing but the order id. */
    @PostMapping("/api/stock/orders/mine/{orderId}/pay")
    public StockPaymentSession pay(@PathVariable String orderId,
                                   @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean acknowledged) {
        return service.start(currentUser.require(), orderId, acknowledged);
    }

    /** Ask PULA to re-verify with the provider. Never accepts a client-supplied payment result. */
    @PostMapping("/api/stock/orders/mine/{orderId}/payment/refresh")
    public StockPaymentSession refresh(@PathVariable String orderId) {
        return service.refresh(currentUser.require(), orderId);
    }

    /**
     * SANDBOX ONLY. Exists only when the mock gateway is active (no Paystack credentials).
     * Simulates the customer finishing checkout for their OWN payment; PULA still confirms
     * through the normal server-side verify path. No real money moves.
     */
    @PostMapping("/api/payments/sandbox/{reference}/complete")
    public java.util.Map<String, String> sandboxComplete(@PathVariable String reference) {
        MockPaystackGateway mock = mockGateway.getIfAvailable();
        if (mock == null) throw ApiException.notFound("Not found");
        Payment payment = service.requireOwnPaymentByReference(currentUser.require(), reference);
        if (com.momocircle.payment.PaymentRails.isWallet(payment)) {
            // A wallet order is paid only by a verified ledger debit; there is no hosted checkout to complete.
            throw ApiException.badRequest("This order is paid from the demo wallet, not through hosted checkout");
        }
        mock.simulateCustomerPaid(reference);
        service.confirmByReference(reference);
        return java.util.Map.of("reference", payment.getProviderReference(), "status", "CHECKED");
    }
}
