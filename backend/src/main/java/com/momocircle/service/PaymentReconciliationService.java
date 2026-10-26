package com.momocircle.service;

import com.momocircle.domain.Contribution;
import com.momocircle.domain.Order;
import com.momocircle.domain.Payment;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.payment.ChargeResult;
import com.momocircle.payment.PaymentProvider;
import com.momocircle.repository.ContributionRepository;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Resolves any Payment stuck in PROCESSING — the state a provider returns
 * when a charge is accepted but not yet confirmed (see SandboxPaymentProvider's
 * "...0001" simulation, and MTN MoMo's documented async RequestToPay flow).
 *
 * A client-side "it worked" message is never trusted for money; this poll
 * (PaymentProvider.getStatus, mirroring MTN's GET .../requesttopay/{id}) is
 * what actually reconciles the transaction. A real deployment would prefer a
 * provider webhook where available, but the reconciliation poll here is the
 * same authorized path a webhook handler would ultimately call into, so
 * either integration point is safe to add later without touching this logic.
 *
 * <h2>Processing-timeout policy</h2>
 * A provider is never assumed to eventually resolve. A Payment that has sat
 * in PROCESSING for longer than {@code app.payments.processing-timeout-minutes}
 * (default 30) — whether because it never reached the provider at all
 * ({@code providerReference == null}, e.g. the app crashed mid-charge) or
 * because the provider itself keeps reporting PROCESSING — is auto-expired
 * to FAILED here. That in turn releases any marketplace stock the order
 * provisionally reserved (see {@link OrderService#applyResolvedFailure}) and
 * leaves the Contribution reading FAILED off its Payment, same as any other
 * failure. Stock is deliberately *not* released before that deadline: a
 * payment that is still legitimately in flight must keep its reservation.
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final PaymentRepository paymentRepository;
    private final ContributionRepository contributionRepository;
    private final OrderRepository orderRepository;
    private final PaymentProvider paymentProvider;
    private final ContributionService contributionService;
    private final OrderService orderService;
    private final long processingTimeoutMinutes;

    public PaymentReconciliationService(PaymentRepository paymentRepository,
                                         ContributionRepository contributionRepository,
                                         OrderRepository orderRepository,
                                         PaymentProvider paymentProvider,
                                         ContributionService contributionService,
                                         OrderService orderService,
                                         @Value("${app.payments.processing-timeout-minutes:30}")
                                         long processingTimeoutMinutes) {
        this.paymentRepository = paymentRepository;
        this.contributionRepository = contributionRepository;
        this.orderRepository = orderRepository;
        this.paymentProvider = paymentProvider;
        this.contributionService = contributionService;
        this.orderService = orderService;
        this.processingTimeoutMinutes = processingTimeoutMinutes;
    }

    /** Every 30s — frequent enough for a responsive demo, cheap enough not to matter at this scale. */
    @Scheduled(fixedDelay = 30_000)
    public void reconcilePendingPayments() {
        List<Payment> pending = paymentRepository.findByStatus(PaymentStatus.PROCESSING);
        for (Payment payment : pending) {
            // Paystack (PULA Market) payments are verified with Paystack itself by StockPaymentService,
            // never polled against the legacy provider, which would misreport them.
            if (payment.getProvider() != null && payment.getProvider().startsWith("paystack")) {
                continue;
            }
            // Wallet payments are verified against the shared ledger by StockPaymentService; the legacy sandbox
            // provider knows nothing about them and would report them as paid without any debit.
            if (com.momocircle.payment.PaymentRails.isWallet(payment)) {
                continue;
            }
            try {
                reconcileOne(payment);
            } catch (Exception e) {
                // One stuck/broken payment must never stop the rest of the batch from reconciling.
                log.warn("Reconciliation failed for payment {}: {}", payment.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void reconcileOne(Payment payment) {
        if (payment.getProviderReference() == null) {
            // Never actually reached the provider (e.g. crashed mid-charge) — nothing to
            // poll. Can only ever be resolved by the timeout below, not a provider status.
            expireIfTimedOut(payment, "Payment never reached the provider");
            return;
        }
        ChargeResult result = paymentProvider.getStatus(payment.getProviderReference());
        if (result.status() == PaymentStatus.PROCESSING) {
            expireIfTimedOut(payment, "Provider did not resolve within the processing timeout");
            return; // Still pending and within policy — try again on the next tick.
        }

        resolve(payment, result.status(), result.providerReference(), result.failureReason());
    }

    /**
     * Auto-expires a Payment that has sat in PROCESSING longer than
     * {@link #processingTimeoutMinutes}. No-ops if still within the window —
     * a payment that's legitimately in flight keeps its stock reservation.
     */
    private void expireIfTimedOut(Payment payment, String reasonPrefix) {
        Instant deadline = payment.getUpdatedAt().plus(Duration.ofMinutes(processingTimeoutMinutes));
        if (Instant.now().isBefore(deadline)) {
            return;
        }
        log.warn("Payment {} exceeded {}m PROCESSING timeout — expiring to FAILED", payment.getId(),
                processingTimeoutMinutes);
        resolve(payment, PaymentStatus.FAILED, null,
                reasonPrefix + " (auto-expired after " + processingTimeoutMinutes + " minutes)");
    }

    private void resolve(Payment payment, PaymentStatus resolved, String providerReference, String failureReason) {
        payment.transitionTo(resolved);
        if (providerReference != null) {
            payment.setProviderReference(providerReference);
        }
        payment.setFailureReason(failureReason);
        paymentRepository.save(payment);

        contributionRepository.findByPaymentId(payment.getId()).ifPresent(contribution ->
                applyToContribution(contribution, resolved));

        orderRepository.findByPaymentId(payment.getId()).ifPresent(order ->
                applyToOrder(order, payment, resolved));
    }

    private void applyToContribution(Contribution contribution, PaymentStatus resolved) {
        if (resolved == PaymentStatus.SUCCESSFUL) {
            contributionService.applyResolvedSuccess(contribution);
        }
        // FAILED contributions need no further action: nothing was reserved or promised elsewhere,
        // and the Contribution's status already reads FAILED directly off its Payment.
    }

    private void applyToOrder(Order order, Payment payment, PaymentStatus resolved) {
        if (resolved == PaymentStatus.SUCCESSFUL) {
            orderService.applyResolvedSuccess(order, payment);
        } else if (resolved == PaymentStatus.FAILED) {
            orderService.applyResolvedFailure(order);
        }
    }
}
