package com.momocircle.payment;

import com.momocircle.domain.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SandboxPaymentProviderTest {

    private final SandboxPaymentProvider provider = new SandboxPaymentProvider();

    private ChargeRequest req(String msisdn) {
        return new ChargeRequest(new BigDecimal("5000"), "XAF", msisdn, "key-" + msisdn, "test");
    }

    @Test
    void succeedsForNormalNumber() {
        ChargeResult result = provider.charge(req("237650123456"));
        assertEquals(PaymentStatus.SUCCESSFUL, result.status());
        assertNotNull(result.providerReference());
        assertNull(result.failureReason());
    }

    @Test
    void failsForInsufficientFundsMagicSuffix() {
        ChargeResult result = provider.charge(req("237650120000"));
        assertEquals(PaymentStatus.FAILED, result.status());
        assertNotNull(result.failureReason());
    }

    @Test
    void staysProcessingForAsyncMagicSuffix() {
        ChargeResult result = provider.charge(req("237650120001"));
        assertEquals(PaymentStatus.PROCESSING, result.status());
    }

    @Test
    void refundReturnsRefundedStatus() {
        ChargeResult result = provider.refund("sbx_ref", "key");
        assertEquals(PaymentStatus.REFUNDED, result.status());
    }
}
