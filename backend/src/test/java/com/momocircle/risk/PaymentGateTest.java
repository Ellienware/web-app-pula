package com.momocircle.risk;

import com.momocircle.domain.BaseEntity;
import com.momocircle.domain.ScamAssessment;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.PlatformRole;
import com.momocircle.domain.enums.ScamRiskLevel;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.PaymentOrchestrator;
import com.momocircle.payment.PaystackGateway;
import com.momocircle.risk.RiskDtos.AdminView;
import com.momocircle.risk.RiskDtos.UserView;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the REAL PaymentGate / RiskAssessmentService / PaymentOrchestrator backstop with in-memory repositories.
 * Proves gate logic, tamper/replay/expiry handling, decisions, outage policy and the provider backstop. It does NOT prove SQL,
 * Hibernate mapping, REQUIRES_NEW transaction semantics or Flyway: those need a real database.
 */
class PaymentGateTest {

    @SuppressWarnings("unchecked")
    private static <T> T repo(Class<T> type, Map<String, Object> store, Map<String, Function<Object[], Object>> custom) {
        InvocationHandler h = (proxy, m, args) -> {
            Object[] a = args == null ? new Object[0] : args;
            Function<Object[], Object> f = custom.get(m.getName());
            if (f != null) return f.apply(a);
            switch (m.getName()) {
                case "save", "saveAndFlush" -> { store.put(((BaseEntity) a[0]).getId(), a[0]); return a[0]; }
                case "findById" -> { return Optional.ofNullable(store.get(a[0])); }
                case "toString" -> { return type.getSimpleName() + "Fake"; }
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "equals" -> { return proxy == a[0]; }
                default -> throw new UnsupportedOperationException(type.getSimpleName() + "." + m.getName());
            }
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, h);
    }

    private final Map<String, Object> intentStore = new LinkedHashMap<>();
    private final Map<String, Object> assessStore = new LinkedHashMap<>();
    private final Map<String, Object> eventStore = new LinkedHashMap<>();
    private final List<String> audits = new ArrayList<>();
    private int scamCount = 0;
    private boolean scamDown = false;
    private boolean dbDown = false;

    private RiskProperties props;
    private RiskAssessmentService service;
    private PaymentGate gate;
    private User alice, bob, admin;

    private List<TransactionIntent> intents() { return intentStore.values().stream().map(o -> (TransactionIntent) o).collect(Collectors.toList()); }
    private List<RiskAssessment> assessments() { return assessStore.values().stream().map(o -> (RiskAssessment) o).collect(Collectors.toList()); }

    private static User user(String id, PlatformRole role) { User u = new User(); u.setId(id); u.setRole(role); return u; }

    @BeforeEach
    void setUp() {
        alice = user("alice", PlatformRole.USER);
        bob = user("bob", PlatformRole.USER);
        admin = user("admin", PlatformRole.ADMIN);
        props = new RiskProperties();

        TransactionIntentRepository intentRepo = repo(TransactionIntentRepository.class, intentStore, Map.of(
                "findByUserIdAndTypeAndReferenceKeyAndFingerprint", a -> {
                    if (dbDown) throw new IllegalStateException("db down");
                    return intents().stream().filter(i -> i.getUserId().equals(a[0]) && i.getType() == a[1] && i.getReferenceKey().equals(a[2]) && i.getFingerprint().equals(a[3])).findFirst();
                },
                "findByPaymentReference", a -> intents().stream().filter(i -> a[0].equals(i.getPaymentReference())).collect(Collectors.toList()),
                "countByUserIdAndTypeAndStatusAndUpdatedAtAfter", a -> intents().stream().filter(i -> i.getUserId().equals(a[0]) && i.getType() == a[1] && i.getStatus() == a[2]).count(),
                "countByUserIdAndTypeAndRecipientKeyAndStatus", a -> intents().stream().filter(i -> i.getUserId().equals(a[0]) && i.getType() == a[1] && a[2].equals(i.getRecipientKey()) && i.getStatus() == a[3]).count(),
                "countByUserIdAndTypeAndStatusAndSimulated", a -> intents().stream().filter(i -> i.getUserId().equals(a[0]) && i.getType() == a[1] && i.getStatus() == a[2] && i.isSimulated() == (Boolean) a[3]).count(),
                "averageInitiatedAmount", a -> intents().stream().filter(i -> i.getUserId().equals(a[0]) && i.getType() == a[1] && i.getStatus() == IntentStatus.INITIATED && i.isSimulated() == (Boolean) a[2])
                        .mapToDouble(i -> i.getAmount().doubleValue()).average().orElse(0)));
        RiskAssessmentRepository assessRepo = repo(RiskAssessmentRepository.class, assessStore, Map.of(
                "findFirstByIntentIdOrderByCreatedAtDesc", a -> {
                    List<RiskAssessment> l = assessments().stream().filter(x -> x.getIntentId().equals(a[0])).collect(Collectors.toList());
                    return l.isEmpty() ? Optional.empty() : Optional.of(l.get(l.size() - 1));
                },
                "countByUserIdAndTypeAndCreatedAtAfter", a -> assessments().stream().filter(x -> x.getUserId().equals(a[0]) && x.getType() == a[1]).count()));
        RiskEventRepository eventRepo = repo(RiskEventRepository.class, eventStore, Map.of(
                "findByAssessmentIdOrderByCreatedAtAsc", a -> eventStore.values().stream().map(o -> (RiskEvent) o).filter(e -> e.getAssessmentId().equals(a[0])).collect(Collectors.toList())));
        RiskScamSignalRepository scamRepo = repo(RiskScamSignalRepository.class, new LinkedHashMap<>(), Map.of(
                "countByUserIdAndRiskLevelAndCreatedAtAfter", a -> { if (scamDown) throw new IllegalStateException("scamshield down"); return (long) scamCount; }));
        AuditLogService audit = new AuditLogService(null) {
            @Override public void record(User actor, String action, String targetType, String targetId, String details) { audits.add(action + ":" + targetId); }
        };
        service = new RiskAssessmentService(intentRepo, assessRepo, eventRepo, scamRepo, props, audit);
        gate = new PaymentGate(service, props);
        controllerAssess = assessRepo; controllerIntents = intentRepo; controllerEvents = eventRepo;
    }

    private RiskAssessmentRepository controllerAssess; private TransactionIntentRepository controllerIntents; private RiskEventRepository controllerEvents;

    private static GateRequest req(RiskType t, String ref, String amount) { return new GateRequest(t, ref, new BigDecimal(amount), "ZAR", "biz-1", "buyer-biz", true, true, false); }
    private int status(Throwable t) { return ((ApiException) t).getStatus().value(); }
    private String code(Throwable t) { return t.getMessage().split("\\|")[0]; }
    private RiskAssessment only() { assertEquals(1, assessments().size()); return assessments().get(0); }

    // ---------------------------------------------------------------- allow + binding
    @Test
    void ordinaryPaymentIsAllowedBoundAndAudited() {
        GateTicket t = gate.authorise(alice, req(RiskType.STOCK_ORDER, "order-1", "500.00"));
        assertFalse(t.disabled());
        gate.bind(alice, t, "PULA-SO-order-1", new BigDecimal("500.00"), "ZAR", false);
        gate.requireBound("PULA-SO-order-1", new BigDecimal("500.00"), "ZAR", false);
        gate.recordOutcome(t, true, "checkout started");
        TransactionIntent i = intents().get(0);
        assertEquals(IntentStatus.INITIATED, i.getStatus());
        assertEquals("PULA-SO-order-1", i.getPaymentReference());
        assertEquals(RiskDecision.ALLOW, only().getDecision());
        assertEquals("2026.10-v1", only().getRuleSetVersion());
        assertTrue(eventStore.values().stream().map(o -> ((RiskEvent) o).getType()).collect(Collectors.toList()).containsAll(List.of("ASSESSED", "BOUND", "INITIATED")));
    }

    @Test
    void identicalRequestsReplayTheSameIntentAndAssessmentWithoutDuplicates() {
        GateTicket a = gate.authorise(alice, req(RiskType.STOCK_ORDER, "order-1", "500.00"));
        GateTicket b = gate.authorise(alice, req(RiskType.STOCK_ORDER, "order-1", "500.00"));
        assertEquals(a.intentId(), b.intentId());
        assertEquals(a.assessmentId(), b.assessmentId());
        assertEquals(1, intents().size());
        assertEquals(1, assessments().size());
    }

    // ---------------------------------------------------------------- tampering and staleness
    @Test
    void amountTamperingAfterAssessmentIsRefusedAtBind() {
        GateTicket t = gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k1", "100.00"));
        ApiException e = assertThrows(ApiException.class, () -> gate.bind(alice, t, "ref", new BigDecimal("100.01"), "ZAR", false));
        assertEquals("RISK_REASSESSMENT_REQUIRED", code(e));
        assertNull(intents().get(0).getPaymentReference(), "nothing may be bound after tampering");
    }

    @Test
    void currencyTamperingAfterAssessmentIsRefusedAtBind() {
        GateTicket t = gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k1", "100.00"));
        assertEquals("RISK_REASSESSMENT_REQUIRED", code(assertThrows(ApiException.class, () -> gate.bind(alice, t, "ref", new BigDecimal("100.00"), "USD", false))));
    }

    @Test
    void changedRecipientOrAmountCreatesANewIntentThatMustBeAssessedAgain() {
        gate.authorise(alice, req(RiskType.STOCK_ORDER, "order-1", "500.00"));
        gate.authorise(alice, new GateRequest(RiskType.STOCK_ORDER, "order-1", new BigDecimal("500.00"), "ZAR", "biz-OTHER", "buyer-biz", true, true, false));
        gate.authorise(alice, req(RiskType.STOCK_ORDER, "order-1", "600.00"));
        assertEquals(3, intents().size());
        assertEquals(3, assessments().size());
        assertEquals(3, intents().stream().map(TransactionIntent::getFingerprint).distinct().count());
    }

    @Test
    void anotherUserCannotUseSomeoneElsesTicket() {
        GateTicket t = gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k1", "100.00"));
        assertEquals(403, status(assertThrows(ApiException.class, () -> gate.bind(bob, t, "ref", new BigDecimal("100.00"), "ZAR", false))));
    }

    @Test
    void staleAssessmentFailsSafelyAndFreshOneIsCreatedOnRetry() {
        GateTicket t = gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k1", "100.00"));
        setExpiry(only(), Instant.now().minusSeconds(1));
        assertEquals("RISK_ASSESSMENT_EXPIRED", code(assertThrows(ApiException.class, () -> gate.bind(alice, t, "ref", new BigDecimal("100.00"), "ZAR", false))));
        GateTicket fresh = gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k1", "100.00"));
        assertNotEquals(t.assessmentId(), fresh.assessmentId());
        assertEquals(2, assessments().size());
    }

    // ---------------------------------------------------------------- decisions
    @Test
    void stepUpHoldsUntilTheOwnerReconfirmsTheExactAmount() {
        ApiException e = assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k1", "10000.01")));
        assertEquals("RISK_STEP_UP_REQUIRED", code(e));
        assertEquals(409, status(e));
        String id = e.getMessage().split("\\|")[1];
        assertEquals(400, status(assertThrows(ApiException.class, () -> service.confirmStepUp(alice, id, new BigDecimal("1.00"), "ZAR"))));
        assertEquals(400, status(assertThrows(ApiException.class, () -> service.confirmStepUp(alice, id, new BigDecimal("10000.01"), "USD"))));
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.confirmStepUp(bob, id, new BigDecimal("10000.01"), "ZAR"))), "only the owner may confirm");
        service.confirmStepUp(alice, id, new BigDecimal("10000.01"), "ZAR");
        GateTicket t = gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k1", "10000.01"));
        assertEquals(id, t.assessmentId());
        service.confirmStepUp(alice, id, new BigDecimal("10000.01"), "ZAR"); // idempotent
    }

    @Test
    void stepUpConfirmationDoesNotCarryOverToADifferentAmount() {
        ApiException e = assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k1", "10000.01")));
        service.confirmStepUp(alice, e.getMessage().split("\\|")[1], new BigDecimal("10000.01"), "ZAR");
        // a bigger amount is a new intent and needs its own assessment
        assertEquals("RISK_STEP_UP_REQUIRED", code(assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k1", "20000.00")))));
    }

    @Test
    void repeatedFailuresHoldForReviewAndAnAdminCanApproveOrReject() {
        for (int i = 0; i < 3; i++) {
            GateTicket t = gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "fail-" + i, "100.00"));
            gate.recordOutcome(t, false, "provider declined");
        }
        ApiException held = assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k-next", "100.00")));
        assertEquals("RISK_REVIEW_PENDING", code(held));
        String id = held.getMessage().split("\\|")[1];
        assertEquals(ReviewStatus.PENDING, ((RiskAssessment) assessStore.get(id)).getReviewStatus());

        assertEquals(403, status(assertThrows(ApiException.class, () -> service.review(alice, id, true, "self"))), "no self-review");
        service.review(admin, id, true, "checked with the customer");
        GateTicket ok = gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k-next", "100.00"));
        assertEquals(id, ok.assessmentId());
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.review(admin, id, false, "again"))), "already decided");
        assertTrue(audits.contains("RISK_REVIEW_APPROVED:" + id));
    }

    @Test
    void rejectedReviewBlocks() {
        for (int i = 0; i < 3; i++) gate.recordOutcome(gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "f" + i, "100.00")), false, "x");
        ApiException held = assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k", "100.00")));
        String id = held.getMessage().split("\\|")[1];
        service.review(admin, id, false, "looks wrong");
        ApiException blocked = assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k", "100.00")));
        assertEquals("RISK_BLOCKED", code(blocked));
        assertEquals(403, status(blocked));
    }

    @Test
    void hardRuleBlocksAndCannotBeOverriddenByReviewOrStepUp() {
        ApiException e = assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.STOCK_ORDER, "o", "500000.01")));
        assertEquals("RISK_BLOCKED", code(e));
        assertEquals(403, status(e));
        String id = e.getMessage().split("\\|")[1];
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.review(admin, id, true, "override?"))));
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.confirmStepUp(alice, id, new BigDecimal("500000.01"), "ZAR"))));
        assertEquals("RISK_BLOCKED", code(assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.STOCK_ORDER, "o", "500000.01")))));
    }

    @Test
    void ownershipViolationAndInactiveRecipientAreExplicitBlocks() {
        GateRequest foreign = new GateRequest(RiskType.STOCK_ORDER, "o", new BigDecimal("10"), "ZAR", "biz", "b", false, true, false);
        assertEquals("RISK_BLOCKED", code(assertThrows(ApiException.class, () -> gate.authorise(alice, foreign))));
        assertTrue(only().getRuleIds().startsWith("H_OWNERSHIP_MISMATCH"));
        GateRequest dead = new GateRequest(RiskType.STOCK_ORDER, "o2", new BigDecimal("10"), "ZAR", "biz", "b", true, false, false);
        assertEquals("RISK_BLOCKED", code(assertThrows(ApiException.class, () -> gate.authorise(alice, dead))));
    }

    @Test
    void inactiveUserIsBlocked() {
        alice.setActive(false);
        assertEquals("RISK_BLOCKED", code(assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.STOCK_ORDER, "o", "10")))));
    }

    @Test
    void frequencyAndScamShieldSignalsAreRecordedForAdministrators() {
        scamCount = 1;
        for (int i = 0; i < 9; i++) {
            try { gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "burst-" + i, "100.00")); }
            catch (ApiException expectedStepUp) { assertEquals("RISK_STEP_UP_REQUIRED", code(expectedStepUp)); } // 20 (scam) + 30 (frequency) = step-up band
        }
        RiskAssessment last = assessments().get(assessments().size() - 1);
        assertTrue(last.getRuleIds().contains("S_RECENT_SCAM_ALERT"), last.getRuleIds());
        assertTrue(last.getRuleIds().contains("S_FREQUENCY_HIGH"), last.getRuleIds());
    }

    @Test
    void scamShieldOutageIsNeutralAndListedAsUnavailableNotFailed() {
        scamDown = true;
        GateTicket t = gate.authorise(alice, req(RiskType.STOCK_ORDER, "o", "100.00"));
        assertNotNull(t.assessmentId());
        assertTrue(only().getUnavailableSignals().contains("SCAMSHIELD"));
    }

    // ---------------------------------------------------------------- outage policy
    @Test
    void riskLayerOutageHoldsThePaymentAndNeverSilentlyAllowsIt() {
        dbDown = true;
        ApiException e = assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.STOCK_ORDER, "o", "100.00")));
        assertEquals("RISK_UNAVAILABLE", code(e));
        assertEquals(503, status(e));
        assertTrue(e.getMessage().contains("Nothing was charged"));
    }

    @Test
    void backstopOutageAlsoHolds() {
        RiskAssessmentService broken = new RiskAssessmentService(null, null, null, null, props, null);
        PaymentGate g = new PaymentGate(broken, props);
        assertEquals(503, status(assertThrows(ApiException.class, () -> g.requireBound("ref", new BigDecimal("1"), "ZAR", false))));
    }

    // ---------------------------------------------------------------- provider backstop
    @Test
    void providerIsCalledOnlyAfterAnAllowedAndBoundAssessment() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        PaystackGateway fake = (PaystackGateway) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PaystackGateway.class}, (p, m, a) -> {
            if (m.getName().equals("mode")) return "SANDBOX_MOCK";
            if (m.getName().equals("initialize")) { providerCalls.incrementAndGet(); return new PaystackGateway.CheckoutSession("r", null, "SANDBOX_MOCK"); }
            throw new UnsupportedOperationException(m.getName());
        });
        PaymentOrchestrator orch = new PaymentOrchestrator(fake, null);
        Field f = PaymentOrchestrator.class.getDeclaredField("riskGate");
        f.setAccessible(true);
        f.set(orch, gate);
        PaystackGateway.InitRequest init = new PaystackGateway.InitRequest("PULA-OD-1", new BigDecimal("100.00"), "ZAR", "a@b.c", "cb");

        assertEquals(403, status(assertThrows(ApiException.class, () -> orch.startMarketCheckout(init))), "never assessed");
        assertEquals(0, providerCalls.get());

        GateTicket t = gate.authorise(alice, new GateRequest(RiskType.CONSUMER_CHECKOUT, "k", new BigDecimal("100.00"), "ZAR", "biz", "biz", true, true, true));
        assertEquals(403, status(assertThrows(ApiException.class, () -> orch.startMarketCheckout(init))), "assessed but not bound to this reference");
        assertEquals(0, providerCalls.get());

        gate.bind(alice, t, "PULA-OD-1", new BigDecimal("100.00"), "ZAR", true);
        orch.startMarketCheckout(init);
        assertEquals(1, providerCalls.get());

        PaystackGateway.InitRequest tampered = new PaystackGateway.InitRequest("PULA-OD-1", new BigDecimal("999.00"), "ZAR", "a@b.c", "cb");
        assertEquals("RISK_REASSESSMENT_REQUIRED", code(assertThrows(ApiException.class, () -> orch.startMarketCheckout(tampered))));
        assertEquals(1, providerCalls.get(), "tampered amount must never reach the provider");
    }

    @Test
    void simulatedAssessmentCannotAuthoriseARealProviderCall() throws Exception {
        PaystackGateway realMode = (PaystackGateway) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PaystackGateway.class}, (p, m, a) -> {
            if (m.getName().equals("mode")) return "PAYSTACK";
            throw new AssertionError("real provider must not be called: " + m.getName());
        });
        PaymentOrchestrator orch = new PaymentOrchestrator(realMode, null);
        Field f = PaymentOrchestrator.class.getDeclaredField("riskGate");
        f.setAccessible(true);
        f.set(orch, gate);
        GateTicket t = gate.authorise(alice, new GateRequest(RiskType.CONSUMER_CHECKOUT, "k", new BigDecimal("100.00"), "ZAR", "biz", "biz", true, true, true));
        gate.bind(alice, t, "PULA-OD-9", new BigDecimal("100.00"), "ZAR", true);
        ApiException e = assertThrows(ApiException.class, () -> orch.startMarketCheckout(
                new PaystackGateway.InitRequest("PULA-OD-9", new BigDecimal("100.00"), "ZAR", "a@b.c", "cb")));
        assertEquals("RISK_CONTEXT_MISMATCH", code(e));
        // and the reverse at bind time
        GateTicket t2 = gate.authorise(alice, new GateRequest(RiskType.CONSUMER_CHECKOUT, "k2", new BigDecimal("50.00"), "ZAR", "biz", "biz", true, true, true));
        assertEquals("RISK_CONTEXT_MISMATCH", code(assertThrows(ApiException.class, () -> gate.bind(alice, t2, "PULA-OD-10", new BigDecimal("50.00"), "ZAR", false))));
    }

    @Test
    void simulatedAndRealHistoryAreKeptApart() {
        GateTicket sim = gate.authorise(alice, new GateRequest(RiskType.STOCK_ORDER, "a", new BigDecimal("100"), "ZAR", "biz", "b", true, true, true));
        GateTicket real = gate.authorise(alice, new GateRequest(RiskType.STOCK_ORDER, "a", new BigDecimal("100"), "ZAR", "biz", "b", true, true, false));
        assertNotEquals(sim.intentId(), real.intentId());
    }

    // ---------------------------------------------------------------- configuration and rollout
    @Test
    void shadowModeAssessesAndAuditsButNeverHoldsOrBlocks() {
        props.setMode("SHADOW");
        GateTicket t = gate.authorise(alice, req(RiskType.STOCK_ORDER, "o", "500000.01")); // would be a hard BLOCK when enforcing
        assertTrue(t.shadow());
        assertEquals(RiskDecision.BLOCK, only().getDecision(), "verdict is still recorded");
        assertTrue(audits.stream().anyMatch(s -> s.startsWith("RISK_BLOCK")));
    }

    @Test
    void unrecognisedModeEnforcesRatherThanDisables() {
        props.setMode("off");
        assertEquals(RiskPolicy.Mode.ENFORCE, props.toPolicy().mode());
        props.setMode("");
        assertEquals(RiskPolicy.Mode.ENFORCE, props.toPolicy().mode());
    }

    @Test
    void onlyTaxiCanBeSwitchedOffAndItIsOffByDefault() {
        assertFalse(props.isEnabledFor(RiskType.TAXI));
        for (RiskType t : RiskType.values()) if (t != RiskType.TAXI) assertTrue(props.isEnabledFor(t), t.name());
        GateTicket t = gate.authorise(alice, req(RiskType.TAXI, "fare", "50.00"));
        assertTrue(t.disabled());
        assertTrue(assessments().isEmpty(), "disabled taxi path persists nothing and changes nothing");
        gate.bind(alice, t, "x", new BigDecimal("50"), "ZAR", false);
        gate.recordOutcome(t, true, "ok");
        props.setTaxiEnabled(true);
        assertFalse(gate.authorise(alice, req(RiskType.TAXI, "fare2", "50.00")).disabled());
        assertEquals(RiskDecision.BLOCK, RiskEngine.evaluate(new RiskContext(RiskType.TAXI, new BigDecimal("2500"), "ZAR", true, true, null, null, null, 0, 0, null, null, null, 0, false), props.toPolicy()).decision());
    }

    @Test
    void policyOverridesFromConfigurationAreApplied() {
        RiskProperties.TypeOverride o = new RiskProperties.TypeOverride();
        o.setHardLimit(new BigDecimal("100.00"));
        props.getTypes().put("stock-order", o);
        assertEquals("RISK_BLOCKED", code(assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.STOCK_ORDER, "o", "100.01")))));
        assertEquals(new BigDecimal("30000"), props.toPolicy().forType(RiskType.STOCK_ORDER).stepUpAmount(), "unset fields keep defaults");
    }

    @Test
    void ruleSetVersionIsConfigurableAndStoredOnEachAssessment() {
        props.setRuleSetVersion("2026.11-v2");
        gate.authorise(alice, req(RiskType.STOCK_ORDER, "o", "10"));
        assertEquals("2026.11-v2", only().getRuleSetVersion());
    }

    // ---------------------------------------------------------------- tenant isolation and redaction
    @Test
    void ownerViewHidesScoreRulesAndThresholdsAndOthersGet404() {
        for (java.lang.reflect.RecordComponent c : UserView.class.getRecordComponents()) {
            assertFalse(c.getName().toLowerCase().contains("score"), c.getName());
            assertFalse(c.getName().toLowerCase().contains("rule"), c.getName());
            assertFalse(c.getName().toLowerCase().contains("signal"), c.getName());
        }
        ApiException e = assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k", "10000.01")));
        String id = e.getMessage().split("\\|")[1];
        RiskController asBob = new RiskController(controllerAssess, controllerIntents, controllerEvents, service, fakeUser(bob));
        assertEquals(404, status(assertThrows(ApiException.class, () -> asBob.mine(id))));
        RiskController asAlice = new RiskController(controllerAssess, controllerIntents, controllerEvents, service, fakeUser(alice));
        UserView v = asAlice.mine(id);
        assertEquals(RiskDecision.STEP_UP, v.decision());
        assertTrue(v.stepUpRequired());
        assertFalse(v.canProceed());
        assertFalse(v.explanation().matches(".*\\d.*"));
    }

    @Test
    void adminViewCarriesScoreRulesAndHistoryAndRequiresAdmin() {
        for (int i = 0; i < 3; i++) gate.recordOutcome(gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "f" + i, "100.00")), false, "x");
        ApiException held = assertThrows(ApiException.class, () -> gate.authorise(alice, req(RiskType.CONSUMER_CHECKOUT, "k", "100.00")));
        String id = held.getMessage().split("\\|")[1];
        RiskController asAdmin = new RiskController(controllerAssess, controllerIntents, controllerEvents, service, fakeUser(admin));
        AdminView v = asAdmin.adminGet(id);
        assertTrue(v.ruleIds().contains("H_REPEATED_FAILURES"));
        assertEquals(RiskDecision.REVIEW, v.decision());
        assertFalse(v.events().isEmpty());
        RiskController asBob = new RiskController(controllerAssess, controllerIntents, controllerEvents, service, fakeUser(bob));
        assertEquals(403, status(assertThrows(ApiException.class, () -> asBob.adminGet(id))));
        assertEquals(403, status(assertThrows(ApiException.class, () -> asBob.review(id, new RiskDtos.ReviewRequest(true, "let me through")))));
    }

    @Test
    void nothingStoredContainsDeviceFingerprintsOrContactDetails() {
        gate.authorise(alice, req(RiskType.STOCK_ORDER, "o", "100.00"));
        for (Field f : TransactionIntent.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase();
            assertFalse(n.contains("device") || n.contains("ip") && n.length() == 2 || n.contains("phone") || n.contains("email") || n.contains("card"), n);
        }
        for (Field f : RiskAssessment.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase();
            assertFalse(n.contains("device") || n.contains("phone") || n.contains("email") || n.contains("card"), n);
        }
    }

    // ---------------------------------------------------------------- helpers
    private static CurrentUserService fakeUser(User u) {
        return new CurrentUserService(null) {
            @Override public User require() { return u; }
            @Override public User requireAdmin() {
                if (u.getRole() != PlatformRole.ADMIN) throw ApiException.forbidden("Admin only");
                return u;
            }
        };
    }

    private static void setExpiry(RiskAssessment a, Instant when) {
        try {
            Field f = RiskAssessment.class.getDeclaredField("expiresAt");
            f.setAccessible(true);
            f.set(a, when);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
