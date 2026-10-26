package com.momocircle.risk;

import com.momocircle.domain.User;
import com.momocircle.domain.enums.ScamRiskLevel;
import com.momocircle.exception.ApiException;
import com.momocircle.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Persists intents, assessments and the audit trail. Every write runs in its OWN transaction (REQUIRES_NEW) so that a
 * hold/block decision and its audit record survive when the calling payment transaction rolls back.
 *
 * Signals come only from data PULA really has: this layer's own attempt/failure/initiation history and the user's own recent
 * ScamShield results. There is no device, session, geo or external fraud data and none is pretended.
 */
@Service
public class RiskAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentService.class);

    private final TransactionIntentRepository intents;
    private final RiskAssessmentRepository assessments;
    private final RiskEventRepository events;
    private final RiskScamSignalRepository scamSignals;
    private final RiskProperties props;
    private final AuditLogService audit;

    public RiskAssessmentService(TransactionIntentRepository intents, RiskAssessmentRepository assessments, RiskEventRepository events,
                                 RiskScamSignalRepository scamSignals, RiskProperties props, AuditLogService audit) {
        this.intents = intents;
        this.assessments = assessments;
        this.events = events;
        this.scamSignals = scamSignals;
        this.props = props;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- assess

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssessmentOutcome assess(User user, GateRequest r) {
        BigDecimal amount = normaliseAmount(r.amount());
        String currency = r.currency() == null ? "" : r.currency().trim().toUpperCase();
        if (currency.length() != 3) throw ApiException.badRequest("A valid currency is required");
        if (r.referenceKey() == null || r.referenceKey().isBlank()) throw ApiException.badRequest("A transaction reference is required");

        String fp = fingerprint(r.type(), user.getId(), amount, currency, r.recipientKey(), r.simulated());
        TransactionIntent intent = intents.findByUserIdAndTypeAndReferenceKeyAndFingerprint(user.getId(), r.type(), r.referenceKey(), fp)
                .orElseGet(() -> createIntent(user, r, amount, currency, fp));

        Instant now = Instant.now();
        RiskAssessment latest = assessments.findFirstByIntentIdOrderByCreatedAtDesc(intent.getId()).orElse(null);
        if (latest != null && now.isBefore(latest.getExpiresAt())) {
            return outcome(latest, now);               // idempotent: same intent + still valid = same verdict
        }
        RiskPolicy policy = props.toPolicy();
        RiskResult result = RiskEngine.evaluate(buildContext(user, r, amount, currency, now), policy);

        RiskAssessment a = new RiskAssessment();
        a.setIntentId(intent.getId());
        a.setUserId(user.getId());
        a.setBusinessId(r.businessId());
        a.setType(r.type());
        a.setAmount(amount);
        a.setCurrency(currency);
        a.setScore(result.score());
        a.setLevel(result.level());
        a.setDecision(result.decision());
        a.setRuleIds(String.join(",", result.ruleIds()));
        a.setUnavailableSignals(String.join(",", result.unavailableSignals()));
        a.setExplanation(result.safeExplanation());
        a.setRuleSetVersion(policy.version());
        a.setMode(policy.mode().name());
        a.setExpiresAt(now.plus(policy.validity()));
        a.setSimulated(r.simulated());
        if (result.decision() == RiskDecision.REVIEW) a.setReviewStatus(ReviewStatus.PENDING);
        if (result.decision() == RiskDecision.STEP_UP) a.setStepUpStatus(StepUpStatus.REQUIRED);
        assessments.save(a);
        event(a, "ASSESSED", user.getId(), result.decision() + "/" + result.level() + " v" + policy.version() + " " + policy.mode());
        if (result.decision() != RiskDecision.ALLOW) {
            audit.record(user, "RISK_" + result.decision(), "RiskAssessment", a.getId(),
                    r.type() + " " + currency + " rules=" + String.join(",", result.ruleIds()));
        }
        return outcome(a, now);
    }

    // ---------------------------------------------------------------- bind / backstop / outcome

    /** Binds the intent to the provider reference, after re-verifying that nothing material changed since assessment. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bind(User user, GateTicket t, String reference, BigDecimal amount, String currency, boolean simulated) {
        if (t.disabled()) return;
        TransactionIntent intent = intents.findById(t.intentId()).orElseThrow(() -> ApiException.forbidden(code("RISK_NOT_ASSESSED", null)));
        if (!intent.getUserId().equals(user.getId())) throw ApiException.forbidden(code("RISK_NOT_ASSESSED", null));
        requireSameDetails(intent, amount, currency, simulated);
        RiskAssessment a = assessments.findFirstByIntentIdOrderByCreatedAtDesc(intent.getId())
                .orElseThrow(() -> ApiException.forbidden(code("RISK_NOT_ASSESSED", null)));
        String fail = a.failureCode(Instant.now());
        if (fail != null) throw failure(fail, a.getId());
        if (intent.getStatus() == IntentStatus.ASSESSED || intent.getStatus() == IntentStatus.FAILED) intent.setStatus(IntentStatus.AUTHORISED);
        intent.setPaymentReference(reference);
        intents.save(intent);
        event(a, "BOUND", user.getId(), "reference bound");
    }

    /**
     * Last line of defence, called by the Paystack orchestrator itself: the provider is never called for a reference that
     * was not assessed, was assessed for different details, is held/blocked/expired, or crosses the simulated/real boundary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void requireBound(String reference, BigDecimal amount, String currency, boolean simulated) {
        List<TransactionIntent> candidates = intents.findByPaymentReference(reference);
        BigDecimal norm = normaliseAmount(amount);
        String cur = currency == null ? "" : currency.trim().toUpperCase();
        Instant now = Instant.now();
        String lastFailure = "RISK_NOT_ASSESSED";
        for (TransactionIntent i : candidates) {
            if (i.getStatus() != IntentStatus.AUTHORISED && i.getStatus() != IntentStatus.INITIATED) continue;
            if (i.getAmount().compareTo(norm) != 0 || !i.getCurrency().equals(cur)) { lastFailure = "RISK_REASSESSMENT_REQUIRED"; continue; }
            if (i.isSimulated() != simulated) { lastFailure = "RISK_CONTEXT_MISMATCH"; continue; }
            RiskAssessment a = assessments.findFirstByIntentIdOrderByCreatedAtDesc(i.getId()).orElse(null);
            if (a == null) continue;
            String f = a.failureCode(now);
            if (f == null) return;
            lastFailure = f;
        }
        throw failure(lastFailure, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(GateTicket t, boolean ok, String detail) {
        if (t.disabled() || t.intentId() == null) return;
        TransactionIntent intent = intents.findById(t.intentId()).orElse(null);
        if (intent == null) return;
        intent.setStatus(ok ? IntentStatus.INITIATED : IntentStatus.FAILED);
        intent.setOutcomeDetail(detail == null ? null : (detail.length() > 200 ? detail.substring(0, 200) : detail));
        intents.save(intent);
        assessments.findFirstByIntentIdOrderByCreatedAtDesc(intent.getId())
                .ifPresent(a -> event(a, ok ? "INITIATED" : "INITIATION_FAILED", null, detail));
    }

    // ---------------------------------------------------------------- step-up and review

    /**
     * The authenticated owner re-confirms THIS transaction by restating its amount and currency. It is recorded server-side and
     * bound to the assessment; a client-supplied "confirmed" boolean is never accepted.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RiskAssessment confirmStepUp(User user, String assessmentId, BigDecimal amount, String currency) {
        RiskAssessment a = assessments.findById(assessmentId).filter(x -> x.getUserId().equals(user.getId()))
                .orElseThrow(() -> ApiException.notFound("Assessment not found"));
        if (a.getDecision() != RiskDecision.STEP_UP) throw ApiException.conflict("This payment does not need extra confirmation");
        if (!Instant.now().isBefore(a.getExpiresAt())) throw failure("RISK_ASSESSMENT_EXPIRED", a.getId());
        if (a.getStepUpStatus() == StepUpStatus.CONFIRMED) return a; // idempotent
        if (a.getAmount().compareTo(normaliseAmount(amount)) != 0 || !a.getCurrency().equalsIgnoreCase(currency == null ? "" : currency.trim())) {
            throw ApiException.badRequest("The amount you entered does not match this payment");
        }
        a.setStepUpStatus(StepUpStatus.CONFIRMED);
        a.setStepUpConfirmedAt(Instant.now());
        assessments.save(a);
        event(a, "STEP_UP_CONFIRMED", user.getId(), "user re-confirmed amount and currency");
        audit.record(user, "RISK_STEP_UP_CONFIRMED", "RiskAssessment", a.getId(), a.getType().name());
        return a;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RiskAssessment review(User admin, String assessmentId, boolean approve, String note) {
        RiskAssessment a = assessments.findById(assessmentId).orElseThrow(() -> ApiException.notFound("Assessment not found"));
        if (a.getUserId().equals(admin.getId())) throw ApiException.forbidden("You cannot review your own transaction");
        if (a.getDecision() != RiskDecision.REVIEW || a.getReviewStatus() != ReviewStatus.PENDING) {
            throw ApiException.conflict("This assessment is not awaiting review");
        }
        a.setReviewStatus(approve ? ReviewStatus.APPROVED : ReviewStatus.REJECTED);
        a.setReviewedBy(admin.getId());
        a.setReviewedAt(Instant.now());
        a.setReviewNote(note == null ? null : (note.length() > 300 ? note.substring(0, 300) : note));
        assessments.save(a);
        event(a, approve ? "REVIEW_APPROVED" : "REVIEW_REJECTED", admin.getId(), note);
        audit.record(admin, approve ? "RISK_REVIEW_APPROVED" : "RISK_REVIEW_REJECTED", "RiskAssessment", a.getId(), a.getType().name());
        return a;
    }

    // ---------------------------------------------------------------- helpers

    private TransactionIntent createIntent(User user, GateRequest r, BigDecimal amount, String currency, String fp) {
        TransactionIntent i = new TransactionIntent();
        i.setUserId(user.getId());
        i.setBusinessId(r.businessId());
        i.setType(r.type());
        i.setReferenceKey(r.referenceKey());
        i.setAmount(amount);
        i.setCurrency(currency);
        i.setRecipientKey(r.recipientKey());
        i.setFingerprint(fp);
        i.setSimulated(r.simulated());
        try {
            return intents.save(i);
        } catch (DataIntegrityViolationException race) { // a concurrent identical request created it first
            return intents.findByUserIdAndTypeAndReferenceKeyAndFingerprint(user.getId(), r.type(), r.referenceKey(), fp).orElseThrow(() -> race);
        }
    }

    private RiskContext buildContext(User user, GateRequest r, BigDecimal amount, String currency, Instant now) {
        Integer attempts = (int) assessments.countByUserIdAndTypeAndCreatedAtAfter(user.getId(), r.type(), now.minus(Duration.ofHours(1)));
        Integer failures = (int) intents.countByUserIdAndTypeAndStatusAndUpdatedAtAfter(user.getId(), r.type(), IntentStatus.FAILED, now.minus(Duration.ofHours(24)));
        long hc = intents.countByUserIdAndTypeAndStatusAndSimulated(user.getId(), r.type(), IntentStatus.INITIATED, r.simulated());
        Double avg = hc > 0 ? intents.averageInitiatedAmount(user.getId(), r.type(), r.simulated()) : null;
        Boolean firstTime = r.recipientKey() == null ? null
                : intents.countByUserIdAndTypeAndRecipientKeyAndStatus(user.getId(), r.type(), r.recipientKey(), IntentStatus.INITIATED) == 0;
        Integer scam;
        try {
            scam = (int) scamSignals.countByUserIdAndRiskLevelAndCreatedAtAfter(user.getId(), ScamRiskLevel.HIGH, now.minus(Duration.ofHours(24)));
        } catch (RuntimeException e) {
            log.warn("ScamShield signal unavailable: {}", e.getMessage());
            scam = null; // reported as unavailable, never guessed
        }
        return new RiskContext(r.type(), amount, currency, r.ownershipValid(), user.isActive(), r.recipientActive(),
                null /* PULA has no verified-merchant data: registration is self-declared */, null /* provider-hosted checkout cannot know funds */,
                attempts, failures, hc > 0 ? (int) hc : null, avg == null ? null : BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP),
                firstTime, scam, r.simulated());
    }

    private AssessmentOutcome outcome(RiskAssessment a, Instant now) {
        return new AssessmentOutcome(a.getId(), a.getIntentId(), a.getDecision(), a.failureCode(now), message(a, now), "SHADOW".equals(a.getMode()));
    }

    private static String message(RiskAssessment a, Instant now) {
        String f = a.failureCode(now);
        if (f == null) return RiskEngine.explain(RiskDecision.ALLOW);
        return switch (f) {
            case "RISK_ASSESSMENT_EXPIRED" -> "This payment check has expired. Please start the payment again.";
            case "RISK_STEP_UP_REQUIRED" -> RiskEngine.explain(RiskDecision.STEP_UP);
            case "RISK_REVIEW_PENDING" -> RiskEngine.explain(RiskDecision.REVIEW);
            default -> RiskEngine.explain(RiskDecision.BLOCK);
        };
    }

    static ApiException failure(String code, String assessmentId) {
        String msg = switch (code) {
            case "RISK_ASSESSMENT_EXPIRED" -> "This payment check has expired. Please start the payment again.";
            case "RISK_STEP_UP_REQUIRED" -> RiskEngine.explain(RiskDecision.STEP_UP);
            case "RISK_REVIEW_PENDING" -> RiskEngine.explain(RiskDecision.REVIEW);
            case "RISK_REASSESSMENT_REQUIRED" -> "The payment details changed. Please review them and start the payment again.";
            case "RISK_CONTEXT_MISMATCH", "RISK_NOT_ASSESSED" -> "We could not complete the safety check for this payment. Nothing was charged.";
            default -> RiskEngine.explain(RiskDecision.BLOCK);
        };
        String body = code + "|" + (assessmentId == null ? "" : assessmentId) + "|" + msg;
        return switch (code) {
            case "RISK_BLOCKED", "RISK_CONTEXT_MISMATCH", "RISK_NOT_ASSESSED" -> ApiException.forbidden(body);
            default -> ApiException.conflict(body);
        };
    }

    private static String code(String c, String id) { return c + "|" + (id == null ? "" : id) + "|We could not complete the safety check for this payment. Nothing was charged."; }

    private void requireSameDetails(TransactionIntent i, BigDecimal amount, String currency, boolean simulated) {
        if (i.getAmount().compareTo(normaliseAmount(amount)) != 0 || !i.getCurrency().equalsIgnoreCase(currency == null ? "" : currency.trim())) {
            throw failure("RISK_REASSESSMENT_REQUIRED", null);
        }
        if (i.isSimulated() != simulated) throw failure("RISK_CONTEXT_MISMATCH", null);
    }

    private void event(RiskAssessment a, String type, String actor, String detail) {
        RiskEvent e = new RiskEvent();
        e.setAssessmentId(a.getId());
        e.setIntentId(a.getIntentId());
        e.setType(type);
        e.setActorUserId(actor);
        e.setDetail(detail == null ? null : (detail.length() > 300 ? detail.substring(0, 300) : detail));
        events.save(e);
    }

    static BigDecimal normaliseAmount(BigDecimal a) {
        if (a == null || a.signum() <= 0) throw ApiException.badRequest("Amount must be greater than zero");
        return a.setScale(2, RoundingMode.HALF_UP);
    }

    /** SHA-256 over the material details. Same details => same fingerprint; any change => a new intent and a new assessment. */
    public static String fingerprint(RiskType type, String userId, BigDecimal amount, String currency, String recipient, boolean simulated) {
        String canonical = String.join("|", type.name(), userId, normaliseAmount(amount).toPlainString(), currency.toUpperCase(),
                recipient == null ? "" : recipient, simulated ? "SIM" : "REAL");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<String> csv(String s) { return s == null || s.isBlank() ? List.of() : Arrays.asList(s.split(",")); }
}
