package com.momocircle.controller;

import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.PayoutStatus;
import com.momocircle.repository.DriverPayoutRepository;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.security.CurrentUserService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Read-only admin visibility into money that needs a human: payments held for review (provider confirmed
 * a different amount/currency than the order) and payouts that failed. Resolution is done outside the app
 * for now; nothing here changes any state. ADMIN role required on every call.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminPaymentsController {

    public record ReviewPayment(String paymentId, String providerReference, String provider, BigDecimal expectedAmount,
                                String currency, String note, Instant updatedAt) {}
    public record FailedPayout(String payoutId, String deliveryRequestId, String driverId, BigDecimal amount, String currency,
                               String provider, int attempts, String failureReason, Instant updatedAt) {}

    private final PaymentRepository payments;
    private final DriverPayoutRepository payouts;
    private final CurrentUserService currentUser;

    public AdminPaymentsController(PaymentRepository payments, DriverPayoutRepository payouts, CurrentUserService currentUser) {
        this.payments = payments;
        this.payouts = payouts;
        this.currentUser = currentUser;
    }

    @GetMapping("/payments/review")
    public List<ReviewPayment> paymentsNeedingReview() {
        currentUser.requireAdmin();
        return payments.findByStatusAndFailureReasonStartingWith(PaymentStatus.PROCESSING, "REVIEW:").stream()
                .map(p -> new ReviewPayment(p.getId(), p.getProviderReference(), p.getProvider(), p.getAmount(),
                        p.getCurrency(), p.getFailureReason(), p.getUpdatedAt())).toList();
    }

    @GetMapping("/payouts/failed")
    public List<FailedPayout> failedPayouts() {
        currentUser.requireAdmin();
        return payouts.findByStatusOrderByUpdatedAtDesc(PayoutStatus.FAILED, PageRequest.of(0, 100)).stream()
                .map(p -> new FailedPayout(p.getId(), p.getDeliveryRequest().getId(), p.getDriver().getId(), p.getAmount(),
                        p.getCurrency(), p.getProvider(), p.getAttempts(), p.getFailureReason(), p.getUpdatedAt())).toList();
    }
}
