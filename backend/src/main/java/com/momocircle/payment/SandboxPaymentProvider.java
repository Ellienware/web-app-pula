package com.momocircle.payment;

import com.momocircle.domain.enums.PaymentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SANDBOX ONLY - simulates a mobile-money provider deterministically so the
 * platform is fully runnable without external credentials. It NEVER moves real
 * money. Magic MSISDN suffixes drive outcomes for testing:
 *
 *   ...0000 -> FAILED (insufficient funds)
 *   ...0001 -> PROCESSING (stays pending; simulate async callback)
 *   anything else -> SUCCESSFUL
 */
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPaymentProvider implements PaymentProvider {

    @Override
    public String name() {
        return "sandbox";
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        String msisdn = request.payerMsisdn() == null ? "" : request.payerMsisdn();
        if (msisdn.endsWith("0000")) {
            return ChargeResult.failed("Insufficient funds (sandbox simulation)");
        }
        if (msisdn.endsWith("0001")) {
            return ChargeResult.of(PaymentStatus.PROCESSING, reference());
        }
        return ChargeResult.of(PaymentStatus.SUCCESSFUL, reference());
    }

    @Override
    public ChargeResult refund(String providerReference, String idempotencyKey) {
        return ChargeResult.of(PaymentStatus.REFUNDED, reference());
    }

    @Override
    public ChargeResult getStatus(String providerReference) {
        // SANDBOX ONLY: a real provider would look up the actual current state by
        // providerReference. Here, anything still PROCESSING is deemed to have
        // resolved successfully by the time it's polled, so demos never get stuck.
        return ChargeResult.of(PaymentStatus.SUCCESSFUL, providerReference);
    }

    private String reference() {
        return "sbx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
