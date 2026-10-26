package com.momocircle.service;

import com.momocircle.payment.PaymentOrchestrator;
import com.momocircle.payment.PaystackGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class OrchestratorServicePaymentGateway implements ServicePaymentGateway {

    private final PaymentOrchestrator orchestrator;
    private final String callbackUrl;

    public OrchestratorServicePaymentGateway(PaymentOrchestrator orchestrator,
                                             @Value("${app.payments.paystack.callback-url:}") String callbackUrl) {
        this.orchestrator = orchestrator;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public Session start(String reference, BigDecimal amount, String currency, String payerEmail) {
        PaystackGateway.CheckoutSession s = orchestrator.startMarketCheckout(
                new PaystackGateway.InitRequest(reference, amount, currency, payerEmail, callbackUrl));
        return new Session(reference, s.authorizationUrl());
    }

    @Override
    public String providerLabel() {
        return orchestrator.marketProviderLabel();
    }
}
