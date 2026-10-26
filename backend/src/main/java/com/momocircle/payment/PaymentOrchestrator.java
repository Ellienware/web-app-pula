package com.momocircle.payment;

import com.momocircle.domain.enums.PaymentPurpose;
import org.springframework.stereotype.Component;

/**
 * Routes a payment PURPOSE to the provider that handles it:
 *
 *   MARKET_PAYMENT  -> Paystack (hosted checkout, verified server-side)
 *   TAXI_PAYMENT    -> existing configured PaymentProvider (unchanged)
 *   SAVINGS_PAYMENT -> existing configured PaymentProvider (unchanged)
 *
 * Taxi and Community Savings services still call the legacy provider directly
 * exactly as before — that path is deliberately untouched so their behaviour
 * cannot regress. Only new PULA Market code goes through this orchestrator.
 * Paystack as an optional taxi provider is NOT implemented.
 */
@Component
public class PaymentOrchestrator {

    private final PaystackGateway paystack;
    private final PaymentProvider legacyProvider;

    public PaymentOrchestrator(PaystackGateway paystack, PaymentProvider legacyProvider) {
        this.paystack = paystack;
        this.legacyProvider = legacyProvider;
    }

    public String providerNameFor(PaymentPurpose purpose) {
        return switch (purpose) {
            case MARKET_PAYMENT -> "paystack";
            case TAXI_PAYMENT, SAVINGS_PAYMENT -> legacyProvider.name();
        };
    }

    /** Value stored on Payment.provider for Market payments, so mock and real never blur together. */
    public String marketProviderLabel() {
        return "PAYSTACK".equals(paystack.mode()) ? "paystack" : "paystack-mock";
    }

    public String marketMode() { return paystack.mode(); }

    /**
     * Transaction-risk backstop (Phase 7): when the gate is wired (always, in a running application) the provider is NOT called
     * unless this exact reference/amount/currency/mode was assessed and currently permits initiation. This protects every present
     * and future caller, including ones that forget to call the gate. Null only in hand-constructed unit tests.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.risk.PaymentGate riskGate;

    public PaystackGateway.CheckoutSession startMarketCheckout(PaystackGateway.InitRequest request) {
        if (riskGate != null) {
            riskGate.requireBound(request.reference(), request.amount(), request.currency(), "SANDBOX_MOCK".equals(paystack.mode()));
        }
        return paystack.initialize(request);
    }

    public PaystackGateway.VerifyResult verifyMarket(String reference) {
        return paystack.verify(reference);
    }

    public PaystackGateway.RefundResult refundMarket(PaystackGateway.RefundRequest request) {
        return paystack.createRefund(request);
    }

    public PaystackGateway.RefundResult fetchMarketRefund(String providerRefundId) {
        return paystack.fetchRefund(providerRefundId);
    }

    public java.util.List<PaystackGateway.RefundResult> listMarketRefunds(String transactionReference) {
        return paystack.listRefunds(transactionReference);
    }

    /** Provider used for synchronous (non-Paystack) refunds: unchanged legacy path for taxi/savings/sandbox. */
    public PaymentProvider legacyProvider() { return legacyProvider; }
}
