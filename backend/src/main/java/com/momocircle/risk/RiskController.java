package com.momocircle.risk;

import com.momocircle.domain.User;
import com.momocircle.risk.RiskDtos.*;
import com.momocircle.security.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Users can read the verdict of THEIR OWN assessment (without score or rule ids) and re-confirm a step-up.
 * Administrators can search, inspect and decide REVIEW items. Nothing here can create, approve or move a payment, and there is no
 * endpoint (or flag) that disables risk assessment.
 */
@RestController
public class RiskController {

    private final RiskAssessmentRepository assessments;
    private final TransactionIntentRepository intents;
    private final RiskEventRepository events;
    private final RiskAssessmentService service;
    private final CurrentUserService currentUser;

    public RiskController(RiskAssessmentRepository assessments, TransactionIntentRepository intents, RiskEventRepository events,
                          RiskAssessmentService service, CurrentUserService currentUser) {
        this.assessments = assessments;
        this.intents = intents;
        this.events = events;
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/risk/assessments/{id}")
    public UserView mine(@PathVariable String id) {
        User u = currentUser.require();
        RiskAssessment a = assessments.findById(id).filter(x -> x.getUserId().equals(u.getId()))
                .orElseThrow(() -> com.momocircle.exception.ApiException.notFound("Assessment not found"));
        return userView(a);
    }

    @PostMapping("/api/risk/assessments/{id}/confirm-step-up")
    public UserView confirmStepUp(@PathVariable String id, @Valid @RequestBody StepUpRequest req) {
        return userView(service.confirmStepUp(currentUser.require(), id, req.amount(), req.currency()));
    }

    // ---- admin ----

    @GetMapping("/api/admin/risk/assessments")
    public Page<AdminView> search(@RequestParam(required = false) RiskType type, @RequestParam(required = false) RiskDecision decision,
                                  @RequestParam(required = false) ReviewStatus reviewStatus, @RequestParam(required = false) String userId,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        currentUser.requireAdmin();
        return assessments.search(type, decision, reviewStatus, userId, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))))
                .map(a -> adminView(a, false));
    }

    @GetMapping("/api/admin/risk/assessments/{id}")
    public AdminView adminGet(@PathVariable String id) {
        currentUser.requireAdmin();
        return adminView(assessments.findById(id).orElseThrow(() -> com.momocircle.exception.ApiException.notFound("Assessment not found")), true);
    }

    @PostMapping("/api/admin/risk/assessments/{id}/review")
    public AdminView review(@PathVariable String id, @Valid @RequestBody ReviewRequest req) {
        User admin = currentUser.requireAdmin();
        return adminView(service.review(admin, id, req.approve(), req.note()), true);
    }

    private UserView userView(RiskAssessment a) {
        Instant now = Instant.now();
        return new UserView(a.getId(), a.getIntentId(), a.getType(), a.getAmount(), a.getCurrency(), a.getDecision(), a.getLevel(),
                a.getExplanation(), a.getStepUpStatus() == StepUpStatus.REQUIRED, a.getStepUpStatus() == StepUpStatus.CONFIRMED,
                a.getReviewStatus(), a.effectivelyAllowed(now), a.getExpiresAt());
    }

    private AdminView adminView(RiskAssessment a, boolean withEvents) {
        TransactionIntent i = intents.findById(a.getIntentId()).orElse(null);
        return new AdminView(a.getId(), a.getIntentId(), a.getUserId(), a.getBusinessId(), a.getType(), a.getAmount(), a.getCurrency(),
                a.getScore(), a.getLevel(), a.getDecision(), RiskAssessmentService.csv(a.getRuleIds()),
                RiskAssessmentService.csv(a.getUnavailableSignals()), a.getExplanation(), a.getRuleSetVersion(), a.getMode(),
                a.getExpiresAt(), a.isSimulated(), a.getReviewStatus(), a.getStepUpStatus(), a.getReviewedBy(), a.getReviewedAt(),
                a.getReviewNote(), a.getCreatedAt(), i == null ? null : i.getStatus(), i == null ? null : i.getPaymentReference(),
                withEvents ? events.findByAssessmentIdOrderByCreatedAtAsc(a.getId()).stream()
                        .map(e -> new EventView(e.getType(), e.getActorUserId(), e.getDetail(), e.getCreatedAt())).toList() : java.util.List.of());
    }
}
