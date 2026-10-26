package com.momocircle.service;

import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.DriverPayout;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.PayoutStatus;
import com.momocircle.domain.momoride.Driver;
import com.momocircle.exception.ApiException;
import com.momocircle.payout.PayoutOrchestrator;
import com.momocircle.payout.PayoutProvider;
import com.momocircle.repository.DriverPayoutRepository;
import com.momocircle.repository.momoride.DriverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Driver earnings payouts. The amount is ALWAYS the delivery fee recorded on the
 * server-side delivery request — never a client value — and there is at most one
 * payout per delivery (idempotency key + unique constraint). A payout failing must
 * never block the delivery from completing, so failures are recorded, not thrown.
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final DriverPayoutRepository payoutRepository;
    private final DriverRepository driverRepository;
    private final PayoutOrchestrator orchestrator;
    private final NotificationService notificationService;

    public PayoutService(DriverPayoutRepository payoutRepository, DriverRepository driverRepository,
                         PayoutOrchestrator orchestrator, NotificationService notificationService) {
        this.payoutRepository = payoutRepository;
        this.driverRepository = driverRepository;
        this.orchestrator = orchestrator;
        this.notificationService = notificationService;
    }

    public record PayoutResponse(String id, String deliveryRequestId, BigDecimal amount, String currency,
                                 PayoutStatus status, String provider, boolean sandbox,
                                 String providerReference, String failureReason, Instant createdAt) {}

    @Transactional
    public void createForCompletedDelivery(DeliveryRequest delivery) {
        createForCompletedDelivery(delivery, delivery.getEstimatedFee());
    }

    /**
     * Same payout, for an explicit server-computed amount. PULA Food passes the driver's 80% share
     * from the delivery-fee allocation; the idempotency key is unchanged, so there is still at most
     * one payout per delivery.
     */
    @Transactional
    public void createForCompletedDelivery(DeliveryRequest delivery, BigDecimal fee) {
        if (delivery.getDriver() == null) return;
        if (fee == null || fee.signum() <= 0) return; // nothing earned (e.g. R0 fee)

        String key = "payout:delivery:" + delivery.getId();
        if (payoutRepository.findByIdempotencyKey(key).isPresent()) return; // duplicate-payout protection

        PayoutProvider provider = orchestrator.providerFor(PayoutOrchestrator.Purpose.DRIVER_EARNINGS);
        DriverPayout payout = new DriverPayout();
        payout.setDeliveryRequest(delivery);
        payout.setDriver(delivery.getDriver());
        payout.setAmount(fee);
        payout.setCurrency(delivery.currency());
        payout.setProvider(provider.name());
        payout.setIdempotencyKey(key);
        payout = payoutRepository.save(payout);

        runProvider(payout, delivery, provider);
        payoutRepository.save(payout);
        if (payout.getStatus() == PayoutStatus.SUCCESSFUL || payout.getStatus() == PayoutStatus.FAILED) {
            notificationService.notify(delivery.getDriver().getUser(),
                    com.momocircle.domain.enums.NotificationType.DELIVERY_UPDATE,
                    payout.getStatus() == PayoutStatus.SUCCESSFUL
                            ? "Your delivery earnings of " + payout.getAmount().toPlainString() + " " + payout.getCurrency()
                                + (provider.sandbox() ? " were recorded (sandbox - no real money)." : " were paid.")
                            : "Your delivery earnings payout could not be completed yet. PULA will retry automatically.",
                    delivery.getId());
        }
    }

    /** Sends the payout to the provider and records exactly what the provider reported. Never throws. */
    private void runProvider(DriverPayout payout, DeliveryRequest delivery, PayoutProvider provider) {
        try {
            payout.transitionTo(PayoutStatus.PROCESSING);
            PayoutProvider.PayoutResult result = provider.pay(new PayoutProvider.PayoutRequest(
                    payout.getIdempotencyKey(), payout.getAmount(), payout.getCurrency(),
                    delivery.getDriver().getUser().getPhoneNumber(), "PULA delivery earnings"));
            payout.setProviderReference(result.providerReference());
            payout.setFailureReason(result.failureReason());
            // Only ever move to the status the provider reported.
            if (result.status() == PayoutStatus.SUCCESSFUL || result.status() == PayoutStatus.FAILED) {
                payout.transitionTo(result.status());
            }
        } catch (Exception e) {
            log.warn("Payout {} failed: {}", payout.getId(), e.getClass().getSimpleName());
            payout.setFailureReason("Payout could not be processed");
            if (payout.getStatus().canTransitionTo(PayoutStatus.FAILED)) payout.transitionTo(PayoutStatus.FAILED);
        }
    }

    /**
     * Bounded automatic retry of FAILED payouts (max 3 attempts, 10 minutes apart). The same idempotency
     * key is reused, so a provider that already paid cannot pay twice.
     */
    @Transactional
    public void retryFailedPayouts() {
        Instant before = Instant.now().minusSeconds(600);
        for (DriverPayout p : payoutRepository.findByStatusAndAttemptsLessThanAndUpdatedAtBefore(PayoutStatus.FAILED, 3, before)) {
            PayoutProvider provider = orchestrator.providerFor(PayoutOrchestrator.Purpose.DRIVER_EARNINGS);
            p.setAttempts(p.getAttempts() + 1);
            runProvider(p, p.getDeliveryRequest(), provider);
            payoutRepository.save(p);
            if (p.getStatus() == PayoutStatus.SUCCESSFUL) {
                notificationService.notify(p.getDriver().getUser(), com.momocircle.domain.enums.NotificationType.DELIVERY_UPDATE,
                        "Your delivery earnings of " + p.getAmount().toPlainString() + " " + p.getCurrency()
                                + (provider.sandbox() ? " were recorded (sandbox - no real money)." : " were paid."), p.getDeliveryRequest().getId());
            }
        }
    }

    @Transactional(readOnly = true)
    public Page<PayoutResponse> myPayouts(User driverUser, Pageable pageable) {
        Driver driver = driverRepository.findByUserId(driverUser.getId())
                .orElseThrow(() -> ApiException.forbidden("You need a driver profile to view payouts."));
        return payoutRepository.findByDriverIdOrderByCreatedAtDesc(driver.getId(), pageable).map(p ->
                new PayoutResponse(p.getId(), p.getDeliveryRequest().getId(), p.getAmount(), p.getCurrency(),
                        p.getStatus(), p.getProvider(), "sandbox".equals(p.getProvider()),
                        p.getProviderReference(), p.getFailureReason(), p.getCreatedAt()));
    }
}
