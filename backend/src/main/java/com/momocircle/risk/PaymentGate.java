package com.momocircle.risk;

import com.momocircle.domain.User;
import com.momocircle.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * The single server-side gate every protected payment path calls immediately before it contacts a provider.
 *
 * Call order in a protected path (after authentication, ownership and ordinary validation):
 *   ticket = gate.authorise(user, request);                 // assess + enforce; throws unless the payment may proceed
 *   gate.bind(user, ticket, reference, amount, currency, simulated);  // re-verifies nothing changed; ties the intent to the reference
 *   ...call the provider (the Paystack orchestrator additionally calls requireBound as a backstop)...
 *   gate.recordOutcome(ticket, ok, detail);
 *
 * It is a plain bean (no @Transactional): all of its writes happen in RiskAssessmentService's own transactions so the audit
 * record survives a rollback of the payment transaction. An ALLOW never bypasses the caller's own validation, the provider's
 * authentication, or webhook verification: it only means this layer does not object.
 */
@Service
public class PaymentGate {

    private static final Logger log = LoggerFactory.getLogger(PaymentGate.class);

    private final RiskAssessmentService service;
    private final RiskProperties props;

    public PaymentGate(RiskAssessmentService service, RiskProperties props) {
        this.service = service;
        this.props = props;
    }

    public GateTicket authorise(User user, GateRequest request) {
        if (!props.isEnabledFor(request.type())) return GateTicket.DISABLED; // only PULA Ride can be off; see RiskProperties
        AssessmentOutcome out;
        try {
            out = service.assess(user, request);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // Explicit outage policy: hold. A protected payment is never silently let through because the risk layer failed.
            log.error("Risk assessment unavailable for {}: {}", request.type(), e.toString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RISK_UNAVAILABLE||We could not complete the safety check for this payment. Nothing was charged. Please try again shortly.");
        }
        if (!out.allowed()) throw RiskAssessmentService.failure(out.failureCode(), out.assessmentId());
        return new GateTicket(out.intentId(), out.assessmentId(), out.shadow(), false);
    }

    public void bind(User user, GateTicket ticket, String reference, BigDecimal amount, String currency, boolean simulated) {
        if (ticket == null || ticket.disabled()) return;
        service.bind(user, ticket, reference, amount, currency, simulated);
    }

    /** Orchestrator backstop: throws unless this exact reference/amount/currency/mode was assessed and is currently allowed. */
    public void requireBound(String reference, BigDecimal amount, String currency, boolean simulated) {
        try {
            service.requireBound(reference, amount, currency, simulated);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Risk backstop unavailable: {}", e.toString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RISK_UNAVAILABLE||We could not complete the safety check for this payment. Nothing was charged. Please try again shortly.");
        }
    }

    public void recordOutcome(GateTicket ticket, boolean ok, String detail) {
        if (ticket == null || ticket.disabled()) return;
        try {
            service.recordOutcome(ticket, ok, detail);
        } catch (RuntimeException e) {
            log.warn("Could not record risk outcome: {}", e.toString()); // audit gap is logged, never allowed to break a completed payment
        }
    }

    /** Legacy PaymentProvider implementations named SANDBOX/MOCK move no real money. */
    public static boolean isSimulatedProvider(String providerName) {
        if (providerName == null) return false;
        String n = providerName.toLowerCase();
        return n.contains("sandbox") || n.contains("mock");
    }
}
