package com.momocircle.payout;

import com.momocircle.domain.enums.PayoutStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SANDBOX payout provider — NO real money moves and NO external call is made. It exists
 * so the driver-earnings flow (delivery -> payout record -> status) can be demonstrated
 * end to end. No real payout provider exists (Stitch was never implemented); setting
 * app.payouts.provider to anything else fails startup (ProductionSafetyCheck).
 */
@Component
@ConditionalOnProperty(name = "app.payouts.provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPayoutProvider implements PayoutProvider {
    @Override public String name() { return "sandbox"; }
    @Override public boolean sandbox() { return true; }

    @Override
    public PayoutResult pay(PayoutRequest r) {
        if (r.amount() == null || r.amount().signum() <= 0) {
            return new PayoutResult(PayoutStatus.FAILED, null, "Invalid payout amount");
        }
        // Deterministic reference: same idempotency key always yields the same reference.
        return new PayoutResult(PayoutStatus.SUCCESSFUL, "SANDBOX-" + Integer.toHexString(r.idempotencyKey().hashCode()), null);
    }
}
