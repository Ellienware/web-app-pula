package com.momocircle.service;

import com.momocircle.domain.Circle;
import com.momocircle.domain.CircleGoal;
import com.momocircle.domain.Contribution;
import com.momocircle.domain.Payment;
import com.momocircle.domain.PaymentRequest;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.PaymentRequestStatus;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.dto.ContributionDtos.ContributeRequest;
import com.momocircle.dto.ContributionDtos.ContributionResponse;
import com.momocircle.dto.ContributionDtos.CreatePaymentRequestRequest;
import com.momocircle.dto.ContributionDtos.PaymentRequestResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.ChargeRequest;
import com.momocircle.payment.ChargeResult;
import com.momocircle.payment.PaymentIdempotencyService;
import com.momocircle.payment.PaymentProvider;
import com.momocircle.repository.ContributionRepository;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.PaymentRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ContributionService {
    /** Phase 7 transaction-risk gate. Always injected in a running application; null only in hand-constructed unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.risk.PaymentGate riskGate;


    private final ContributionRepository contributionRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final CircleService circleService;
    private final PaymentProvider paymentProvider;
    private final PaymentIdempotencyService paymentIdempotencyService;
    private final ActivityService activityService;
    private final GoalService goalService;
    private final ReceiptService receiptService;
    private final FinancialValidationService financialValidationService;

    public ContributionService(ContributionRepository contributionRepository,
                               PaymentRepository paymentRepository,
                               PaymentRequestRepository paymentRequestRepository,
                               CircleService circleService,
                               PaymentProvider paymentProvider,
                               PaymentIdempotencyService paymentIdempotencyService,
                               ActivityService activityService,
                               GoalService goalService,
                               ReceiptService receiptService,
                               FinancialValidationService financialValidationService) {
        this.contributionRepository = contributionRepository;
        this.paymentRepository = paymentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.circleService = circleService;
        this.paymentProvider = paymentProvider;
        this.paymentIdempotencyService = paymentIdempotencyService;
        this.activityService = activityService;
        this.goalService = goalService;
        this.receiptService = receiptService;
        this.financialValidationService = financialValidationService;
    }

    @Transactional
    public ContributionResponse contribute(String circleId, User contributor, ContributeRequest req) {
        Circle circle = circleService.requireMembership(circleId, contributor).getCircle();
        financialValidationService.requireValidAmount(req.amount(), "amount");

        String idempotencyKey = (req.idempotencyKey() == null || req.idempotencyKey().isBlank())
                ? UUID.randomUUID().toString()
                : req.idempotencyKey();

        // Phase 7 transaction-risk gate for this USER-INITIATED contribution (before reserve(), which commits on its own).
        // Scheduled recurring dues use charge() below and are not interactive, so they are not gated here (see report).
        boolean riskSimulated = com.momocircle.risk.PaymentGate.isSimulatedProvider(paymentProvider.name());
        com.momocircle.risk.GateTicket riskTicket = riskGate == null ? null : riskGate.authorise(contributor,
                new com.momocircle.risk.GateRequest(com.momocircle.risk.RiskType.CONTRIBUTION, idempotencyKey, req.amount(),
                        circle.getCurrency(), circle.getId(), null, true, null, riskSimulated));
        if (riskGate != null) riskGate.bind(contributor, riskTicket, idempotencyKey, req.amount(), circle.getCurrency(), riskSimulated);

        // Concurrency-safe idempotency: reserve() runs in its own transaction and
        // resolves any race against a simultaneous duplicate request via the DB's
        // unique constraint on idempotency_key, not just an in-app check-then-insert.
        var reservation = paymentIdempotencyService.reserve(
                idempotencyKey, req.amount(), circle.getCurrency(), paymentProvider.name());
        if (reservation.alreadyExisted()) {
            Contribution dup = contributionRepository.findByCircleIdOrderByCreatedAtDesc(circleId).stream()
                    .filter(c -> c.getPayment().getId().equals(reservation.payment().getId()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.conflict(
                            "A charge for this idempotency key is already in progress or was already recorded"));
            // Phase 8: an idempotency key must never replay another member's contribution.
            if (dup.getContributor() == null || !dup.getContributor().getId().equals(contributor.getId())) {
                throw ApiException.notFound("Contribution not found");
            }
            return toResponse(dup);
        }

        PaymentRequest paymentRequest = null;
        if (req.paymentRequestId() != null && !req.paymentRequestId().isBlank()) {
            paymentRequest = paymentRequestRepository.findById(req.paymentRequestId())
                    .orElseThrow(() -> ApiException.notFound("Payment request not found"));
            if (!paymentRequest.getCircle().getId().equals(circleId)) {
                throw ApiException.badRequest("Payment request does not belong to this circle");
            }
        }

        CircleGoal goal = null;
        if (req.goalId() != null && !req.goalId().isBlank()) {
            goal = goalService.requireGoal(circleId, req.goalId());
            goalService.requireAcceptingContributions(goal);
        }

        Payment payment = reservation.payment();

        ChargeResult result = paymentProvider.charge(new ChargeRequest(
                req.amount(),
                circle.getCurrency(),
                contributor.getPhoneNumber(),
                idempotencyKey,
                "Contribution to " + circle.getName()));
        if (riskGate != null) riskGate.recordOutcome(riskTicket, result.status() != PaymentStatus.FAILED, "charge " + result.status());

        payment.transitionTo(result.status());
        payment.setProviderReference(result.providerReference());
        payment.setFailureReason(result.failureReason());
        paymentRepository.save(payment);

        Contribution contribution = new Contribution();
        contribution.setCircle(circle);
        contribution.setContributor(contributor);
        contribution.setPaymentRequest(paymentRequest);
        contribution.setGoal(goal);
        contribution.setPayment(payment);
        contribution = contributionRepository.save(contribution);

        if (paymentRequest != null && result.status() == PaymentStatus.SUCCESSFUL) {
            paymentRequest.setStatus(PaymentRequestStatus.FULFILLED);
            paymentRequestRepository.save(paymentRequest);
        }

        if (result.status() == PaymentStatus.SUCCESSFUL) {
            activityService.record(circle, contributor, ActivityType.CONTRIBUTION_MADE,
                    contributor.getFullName() + " contributed "
                            + req.amount().toPlainString() + " " + circle.getCurrency());
            if (goal != null) {
                goalService.refreshStatus(goal, contributor);
            }
            receiptService.issue(payment, contributor, circle.getName(),
                    "Contribution to " + circle.getName());
        }

        return toResponse(contribution);
    }

    /**
     * Core charge routine shared by ad-hoc contributions and recurring dues.
     * Creates a Payment, attempts to collect it via the provider, and records a
     * Contribution earmarked to an optional goal. Idempotent on {@code idempotencyKey}.
     */
    @Transactional
    public Contribution charge(Circle circle, User payer, CircleGoal goal,
                               java.math.BigDecimal amount, String idempotencyKey, String narrative) {
        goalService.requireAcceptingContributions(goal);
        financialValidationService.requireValidAmount(amount, "amount");
        var reservation = paymentIdempotencyService.reserve(
                idempotencyKey, amount, circle.getCurrency(), paymentProvider.name());
        if (reservation.alreadyExisted()) {
            Contribution replay = contributionRepository.findByCircleIdOrderByCreatedAtDesc(circle.getId()).stream()
                    .filter(c -> c.getPayment().getId().equals(reservation.payment().getId()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.conflict(
                            "A charge for this idempotency key is already in progress or was already recorded"));
            // Phase 8: an idempotency key must never replay another member's contribution.
            if (replay.getContributor() == null || !replay.getContributor().getId().equals(payer.getId())) {
                throw ApiException.notFound("Contribution not found");
            }
            return replay;
        }

        Payment payment = reservation.payment();

        ChargeResult result = paymentProvider.charge(new ChargeRequest(
                amount, circle.getCurrency(), payer.getPhoneNumber(), idempotencyKey, narrative));

        payment.transitionTo(result.status());
        payment.setProviderReference(result.providerReference());
        payment.setFailureReason(result.failureReason());
        paymentRepository.save(payment);

        Contribution contribution = new Contribution();
        contribution.setCircle(circle);
        contribution.setContributor(payer);
        contribution.setGoal(goal);
        contribution.setPayment(payment);
        contribution = contributionRepository.save(contribution);

        if (result.status() == PaymentStatus.SUCCESSFUL) {
            activityService.record(circle, payer, ActivityType.CONTRIBUTION_MADE,
                    payer.getFullName() + " contributed "
                            + amount.toPlainString() + " " + circle.getCurrency());
            if (goal != null) {
                goalService.refreshStatus(goal, payer);
            }
            receiptService.issue(payment, payer, circle.getName(), narrative);
        }
        return contribution;
    }

    @Transactional(readOnly = true)
    public List<ContributionResponse> list(String circleId, User viewer) {
        circleService.requireMembership(circleId, viewer);
        return contributionRepository.findByCircleIdOrderByCreatedAtDesc(circleId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PaymentRequestResponse createPaymentRequest(String circleId, User actor, CreatePaymentRequestRequest req) {
        var membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);
        financialValidationService.requireValidAmount(req.amountPerMember(), "amountPerMember");

        PaymentRequest request = new PaymentRequest();
        request.setCircle(membership.getCircle());
        request.setRequestedBy(actor);
        request.setAmountPerMember(req.amountPerMember());
        request.setNote(req.note());
        request.setDueDate(req.dueDate());
        request = paymentRequestRepository.save(request);

        activityService.record(membership.getCircle(), actor, ActivityType.PAYMENT_REQUEST_CREATED,
                actor.getFullName() + " requested "
                        + req.amountPerMember().toPlainString() + " "
                        + membership.getCircle().getCurrency() + " per member");

        return toResponse(request);
    }

    @Transactional(readOnly = true)
    public List<PaymentRequestResponse> listPaymentRequests(String circleId, User viewer) {
        circleService.requireMembership(circleId, viewer);
        return paymentRequestRepository.findByCircleIdOrderByCreatedAtDesc(circleId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Admin cancels an open payment request. */
    @Transactional
    public PaymentRequestResponse cancelPaymentRequest(String circleId, String requestId, User actor) {
        var membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);

        PaymentRequest request = requirePaymentRequest(circleId, requestId);
        if (request.getStatus() != PaymentRequestStatus.OPEN) {
            throw ApiException.badRequest("Only open requests can be cancelled");
        }
        request.setStatus(PaymentRequestStatus.CANCELLED);
        paymentRequestRepository.save(request);

        activityService.record(request.getCircle(), actor, ActivityType.PAYMENT_REQUEST_CANCELLED,
                actor.getFullName() + " cancelled a payment request");
        return toResponse(request);
    }

    /** Admin nudges members about an open request (records an activity event). */
    @Transactional
    public PaymentRequestResponse remindPaymentRequest(String circleId, String requestId, User actor) {
        var membership = circleService.requireMembership(circleId, actor);
        circleService.requireAdmin(membership);

        PaymentRequest request = requirePaymentRequest(circleId, requestId);
        if (request.getStatus() != PaymentRequestStatus.OPEN) {
            throw ApiException.badRequest("Only open requests can be reminded");
        }
        activityService.record(request.getCircle(), actor, ActivityType.PAYMENT_REQUEST_REMINDED,
                actor.getFullName() + " sent a reminder for "
                        + request.getAmountPerMember().toPlainString() + " "
                        + request.getCircle().getCurrency() + " per member");
        return toResponse(request);
    }

    /** Daily job: expire open payment requests whose due date has passed. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 30 2 * * *")
    @Transactional
    public void expireOverdueRequests() {
        java.time.LocalDate today = java.time.LocalDate.now();
        for (PaymentRequest request : paymentRequestRepository
                .findByStatusAndDueDateBefore(PaymentRequestStatus.OPEN, today)) {
            request.setStatus(PaymentRequestStatus.EXPIRED);
            paymentRequestRepository.save(request);
            activityService.record(request.getCircle(), null, ActivityType.PAYMENT_REQUEST_EXPIRED,
                    "A payment request expired");
        }
    }

    private PaymentRequest requirePaymentRequest(String circleId, String requestId) {
        PaymentRequest request = paymentRequestRepository.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Payment request not found"));
        if (!request.getCircle().getId().equals(circleId)) {
            throw ApiException.badRequest("Payment request does not belong to this circle");
        }
        return request;
    }

    /**
     * Applies the same side effects a synchronous SUCCESSFUL charge gets in
     * {@link #contribute}, for a contribution whose payment just resolved out
     * of PROCESSING via reconciliation (see PaymentReconciliationService).
     * Caller must have already set payment.status to SUCCESSFUL and saved it.
     */
    @Transactional
    public void applyResolvedSuccess(Contribution contribution) {
        Payment payment = contribution.getPayment();
        Circle circle = contribution.getCircle();
        User contributor = contribution.getContributor();

        PaymentRequest paymentRequest = contribution.getPaymentRequest();
        if (paymentRequest != null) {
            paymentRequest.setStatus(PaymentRequestStatus.FULFILLED);
            paymentRequestRepository.save(paymentRequest);
        }

        activityService.record(circle, contributor, ActivityType.CONTRIBUTION_MADE,
                contributor.getFullName() + " contributed "
                        + payment.getAmount().toPlainString() + " " + circle.getCurrency());

        CircleGoal goal = contribution.getGoal();
        if (goal != null) {
            goalService.refreshStatus(goal, contributor);
        }

        receiptService.issue(payment, contributor, circle.getName(), "Contribution to " + circle.getName());
    }

    private ContributionResponse toResponse(Contribution c) {
        Payment p = c.getPayment();
        CircleGoal g = c.getGoal();
        return new ContributionResponse(
                c.getId(),
                c.getCircle().getId(),
                c.getContributor().getId(),
                c.getContributor().getFullName(),
                p.getAmount(),
                p.getCurrency(),
                p.getStatus(),
                p.getProviderReference(),
                g == null ? null : g.getId(),
                g == null ? null : g.getTitle(),
                c.getCreatedAt());
    }

    private PaymentRequestResponse toResponse(PaymentRequest r) {
        return new PaymentRequestResponse(
                r.getId(),
                r.getCircle().getId(),
                r.getRequestedBy().getFullName(),
                r.getAmountPerMember(),
                r.getNote(),
                r.getDueDate(),
                r.getStatus(),
                r.getCreatedAt());
    }
}
