package com.momocircle.savings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.ActivityEvent;
import com.momocircle.domain.Circle;
import com.momocircle.domain.CircleGoal;
import com.momocircle.domain.CircleInvitation;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.Contribution;
import com.momocircle.domain.Expense;
import com.momocircle.domain.Payment;
import com.momocircle.domain.PaymentRequest;
import com.momocircle.domain.RecurringDue;
import com.momocircle.domain.RecurringPeriod;
import com.momocircle.domain.RecurringPlan;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.dto.CircleDtos.CircleResponse;
import com.momocircle.dto.CircleDtos.CreateCircleRequest;
import com.momocircle.payment.PaymentIdempotencyService;
import com.momocircle.payment.SandboxPaymentProvider;
import com.momocircle.repository.ActivityRepository;
import com.momocircle.repository.CircleGoalRepository;
import com.momocircle.repository.CircleInvitationRepository;
import com.momocircle.repository.CircleMembershipRepository;
import com.momocircle.repository.CircleRepository;
import com.momocircle.repository.ContributionRepository;
import com.momocircle.repository.ExpenseRepository;
import com.momocircle.repository.ExpenseShareRepository;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.PaymentRequestRepository;
import com.momocircle.repository.RecurringDueRepository;
import com.momocircle.repository.RecurringPlanRepository;
import com.momocircle.repository.UserRepository;
import com.momocircle.service.ActivityService;
import com.momocircle.service.CircleService;
import com.momocircle.service.ContributionService;
import com.momocircle.service.ExpenseService;
import com.momocircle.service.FinancialValidationService;
import com.momocircle.service.GoalService;
import com.momocircle.service.InvitationService;
import com.momocircle.service.ReceiptService;
import com.momocircle.service.RecurringContributionService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PHASE 2A BASELINE. Wires the REAL, UNCHANGED Savings services (circle, invitation, contribution, goal,
 * recurring, expense, activity, validation, payment idempotency, sandbox payment provider) over in-memory
 * stand-ins for the repositories. Only persistence is faked, so the tests characterise the current production
 * logic exactly as it is. Nothing here knows about stokvels.
 *
 * Sandbox payment provider behaviour (real code): a payer phone ending 0000 FAILS, ending 0001 stays
 * PROCESSING, anything else SUCCEEDS.
 */
final class SavingsFixture {

    final Map<String, User> users = new LinkedHashMap<>();
    final Map<String, Circle> circles = new LinkedHashMap<>();
    final List<CircleMembership> memberships = new ArrayList<>();
    final List<Contribution> contributions = new ArrayList<>();
    final Map<String, CircleGoal> goals = new LinkedHashMap<>();
    final Map<String, CircleInvitation> invitations = new LinkedHashMap<>();
    final Map<String, PaymentRequest> paymentRequests = new LinkedHashMap<>();
    final Map<String, Payment> payments = new LinkedHashMap<>();
    final Map<String, RecurringPlan> plans = new LinkedHashMap<>();
    final Map<String, Expense> expenses = new LinkedHashMap<>();
    final List<ActivityEvent> activity = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger(1);

    final CircleRepository circleRepo = mock(CircleRepository.class);
    final CircleMembershipRepository membershipRepo = mock(CircleMembershipRepository.class);
    final ContributionRepository contributionRepo = mock(ContributionRepository.class);
    final CircleGoalRepository goalRepo = mock(CircleGoalRepository.class);
    final CircleInvitationRepository invitationRepo = mock(CircleInvitationRepository.class);
    final PaymentRequestRepository paymentRequestRepo = mock(PaymentRequestRepository.class);
    final PaymentRepository paymentRepo = mock(PaymentRepository.class);
    final RecurringPlanRepository planRepo = mock(RecurringPlanRepository.class);
    final RecurringDueRepository dueRepo = mock(RecurringDueRepository.class);
    final ExpenseRepository expenseRepo = mock(ExpenseRepository.class);
    final ExpenseShareRepository shareRepo = mock(ExpenseShareRepository.class);
    final ActivityRepository activityRepo = mock(ActivityRepository.class);
    final UserRepository userRepo = mock(UserRepository.class);
    final ReceiptService receipts = mock(ReceiptService.class);

    final CurrencyProperties currencies = new CurrencyProperties();
    final FinancialValidationService validation = new FinancialValidationService(currencies, new BigDecimal("10000000"));
    final SandboxPaymentProvider provider = new SandboxPaymentProvider();
    final PaymentIdempotencyService idempotency = new PaymentIdempotencyService(paymentRepo);
    final ActivityService activityService = new ActivityService(activityRepo, membershipRepo);
    final CircleService circleService = new CircleService(circleRepo, membershipRepo, contributionRepo, userRepo, activityService, validation);
    final GoalService goalService = new GoalService(goalRepo, contributionRepo, circleService, activityService, validation);
    final ContributionService contributionService = new ContributionService(contributionRepo, paymentRepo, paymentRequestRepo,
            circleService, provider, idempotency, activityService, goalService, receipts, validation);
    final InvitationService invitationService = new InvitationService(invitationRepo, membershipRepo, userRepo, circleService, activityService);
    final com.momocircle.repository.ExpenseEventRepository eventRepo = mock(com.momocircle.repository.ExpenseEventRepository.class);
    final com.momocircle.service.NotificationService notifications = mock(com.momocircle.service.NotificationService.class);
    final com.momocircle.repository.ledger.LedgerTransactionRepository ledgerTxRepo = mock(com.momocircle.repository.ledger.LedgerTransactionRepository.class);
    final List<com.momocircle.domain.ExpenseEvent> events = new ArrayList<>();
    final ExpenseService expenseService = new ExpenseService(expenseRepo, shareRepo, membershipRepo, userRepo, circleService, activityService, validation,
            eventRepo, notifications, ledgerTxRepo, currencies);
    final RecurringContributionService recurringService = new RecurringContributionService(planRepo, dueRepo, circleService,
            goalService, contributionService, activityService, validation);

    SavingsFixture() {
        wire();
    }

    // ------------------------------------------------------------------ people and circles

    /** @param phoneEnding last digits of the payer phone: "0000" fails, "0001" processing, else success. */
    User user(String name, String phoneEnding) {
        User u = new User();
        u.setId("u-" + name.toLowerCase());
        u.setFullName(name);
        u.setEmail(name.toLowerCase() + "@example.com");
        u.setPhoneNumber("08255" + phoneEnding);
        users.put(u.getId(), u);
        return u;
    }

    User user(String name) { return user(name, "1234"); }

    CircleResponse newCircle(User owner, String name, CircleType type, BigDecimal goalAmount) {
        return circleService.create(owner, new CreateCircleRequest(name, "desc", type, "ZAR", goalAmount));
    }

    /** Puts a user in a circle directly with a role (test setup only; bypasses the service on purpose). */
    CircleMembership join(String circleId, User user, CircleRole role) {
        CircleMembership m = new CircleMembership();
        m.setCircle(circles.get(circleId));
        m.setUser(user);
        m.setRole(role);
        return storeMembership(m);
    }

    Optional<CircleMembership> membershipOf(String circleId, User u) {
        return memberships.stream().filter(m -> m.getCircle().getId().equals(circleId) && m.getUser().getId().equals(u.getId())).findFirst();
    }

    long activityCount(com.momocircle.domain.enums.ActivityType type) {
        return activity.stream().filter(a -> a.getType() == type).count();
    }

    // ------------------------------------------------------------------ in-memory repositories

    private String nextId(String prefix) { return prefix + "-" + ids.getAndIncrement(); }

    private CircleMembership storeMembership(CircleMembership m) {
        if (m.getId() == null) m.setId(nextId("m"));
        if (!memberships.contains(m)) memberships.add(m);
        return m;
    }

    private void wire() {
        currencies.setSupported(List.of("ZAR"));

        when(userRepo.findByEmail(anyString())).thenAnswer(i -> users.values().stream()
                .filter(u -> u.getEmail().equalsIgnoreCase(i.getArgument(0))).findFirst());
        when(userRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(users.get(i.<String>getArgument(0))));

        when(circleRepo.save(any(Circle.class))).thenAnswer(i -> {
            Circle c = i.getArgument(0);
            if (c.getId() == null) c.setId(nextId("c"));
            circles.put(c.getId(), c);
            return c;
        });
        when(circleRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(circles.get(i.<String>getArgument(0))));

        when(membershipRepo.save(any(CircleMembership.class))).thenAnswer(i -> storeMembership(i.getArgument(0)));
        when(membershipRepo.findById(anyString())).thenAnswer(i -> memberships.stream().filter(m -> m.getId().equals(i.getArgument(0))).findFirst());
        when(membershipRepo.findByCircleId(anyString())).thenAnswer(i -> memberships.stream()
                .filter(m -> m.getCircle().getId().equals(i.getArgument(0))).toList());
        when(membershipRepo.findByUserId(anyString())).thenAnswer(i -> memberships.stream()
                .filter(m -> m.getUser().getId().equals(i.getArgument(0))).toList());
        when(membershipRepo.findByCircleIdAndUserId(anyString(), anyString())).thenAnswer(i -> memberships.stream()
                .filter(m -> m.getCircle().getId().equals(i.getArgument(0)) && m.getUser().getId().equals(i.getArgument(1))).findFirst());
        when(membershipRepo.existsByCircleIdAndUserId(anyString(), anyString())).thenAnswer(i -> memberships.stream()
                .anyMatch(m -> m.getCircle().getId().equals(i.getArgument(0)) && m.getUser().getId().equals(i.getArgument(1))));
        when(membershipRepo.countByCircleId(anyString())).thenAnswer(i -> memberships.stream()
                .filter(m -> m.getCircle().getId().equals(i.getArgument(0))).count());
        org.mockito.Mockito.doAnswer(i -> { memberships.remove((CircleMembership) i.getArgument(0)); return null; })
                .when(membershipRepo).delete(any(CircleMembership.class));

        when(paymentRepo.save(any(Payment.class))).thenAnswer(i -> storePayment(i.getArgument(0)));
        when(paymentRepo.saveAndFlush(any(Payment.class))).thenAnswer(i -> storePayment(i.getArgument(0)));
        when(paymentRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(payments.get(i.<String>getArgument(0))));
        when(paymentRepo.findByIdempotencyKey(anyString())).thenAnswer(i -> payments.values().stream()
                .filter(p -> i.getArgument(0).equals(p.getIdempotencyKey())).findFirst());

        when(contributionRepo.save(any(Contribution.class))).thenAnswer(i -> {
            Contribution c = i.getArgument(0);
            if (c.getId() == null) c.setId(nextId("k"));
            if (!contributions.contains(c)) contributions.add(c);
            return c;
        });
        when(contributionRepo.findByCircleIdOrderByCreatedAtDesc(anyString())).thenAnswer(i -> {
            List<Contribution> out = new ArrayList<>(contributions.stream().filter(c -> c.getCircle().getId().equals(i.getArgument(0))).toList());
            java.util.Collections.reverse(out);
            return out;
        });
        when(contributionRepo.sumConfirmedAmount(anyString(), any())).thenAnswer(i -> contributions.stream()
                .filter(c -> c.getCircle().getId().equals(i.getArgument(0)) && c.getPayment().getStatus() == i.getArgument(1))
                .map(c -> c.getPayment().getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add));
        when(contributionRepo.sumConfirmedAmountForGoal(anyString(), any())).thenAnswer(i -> contributions.stream()
                .filter(c -> c.getGoal() != null && c.getGoal().getId().equals(i.getArgument(0)) && c.getPayment().getStatus() == i.getArgument(1))
                .map(c -> c.getPayment().getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add));

        when(goalRepo.save(any(CircleGoal.class))).thenAnswer(i -> {
            CircleGoal g = i.getArgument(0);
            if (g.getId() == null) g.setId(nextId("g"));
            goals.put(g.getId(), g);
            return g;
        });
        when(goalRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(goals.get(i.<String>getArgument(0))));
        when(goalRepo.findByCircleIdOrderByCreatedAtDesc(anyString())).thenAnswer(i -> {
            List<CircleGoal> out = new ArrayList<>(goals.values().stream().filter(g -> g.getCircle().getId().equals(i.getArgument(0))).toList());
            java.util.Collections.reverse(out);
            return out;
        });
        when(goalRepo.findByCircleIdInAndStatus(anyList(), any())).thenAnswer(i -> goals.values().stream()
                .filter(g -> ((List<String>) i.getArgument(0)).contains(g.getCircle().getId()) && g.getStatus() == i.getArgument(1)).toList());

        when(invitationRepo.save(any(CircleInvitation.class))).thenAnswer(i -> {
            CircleInvitation v = i.getArgument(0);
            if (v.getId() == null) v.setId(nextId("i"));
            invitations.put(v.getId(), v);
            return v;
        });
        when(invitationRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(invitations.get(i.<String>getArgument(0))));
        when(invitationRepo.findByToken(anyString())).thenAnswer(i -> invitations.values().stream()
                .filter(v -> v.getToken().equals(i.getArgument(0))).findFirst());
        when(invitationRepo.findByCircleIdOrderByCreatedAtDesc(anyString())).thenAnswer(i -> invitations.values().stream()
                .filter(v -> v.getCircle().getId().equals(i.getArgument(0))).toList());
        when(invitationRepo.existsByCircleIdAndInvitedEmailAndStatus(anyString(), anyString(), any())).thenAnswer(i -> invitations.values().stream()
                .anyMatch(v -> v.getCircle().getId().equals(i.getArgument(0)) && v.getInvitedEmail().equals(i.getArgument(1)) && v.getStatus() == i.getArgument(2)));
        when(invitationRepo.findByInvitedEmailAndStatusOrderByCreatedAtDesc(anyString(), any())).thenAnswer(i -> invitations.values().stream()
                .filter(v -> v.getInvitedEmail().equals(i.getArgument(0)) && v.getStatus() == i.getArgument(1)).toList());

        when(paymentRequestRepo.save(any(PaymentRequest.class))).thenAnswer(i -> {
            PaymentRequest r = i.getArgument(0);
            if (r.getId() == null) r.setId(nextId("pr"));
            paymentRequests.put(r.getId(), r);
            return r;
        });
        when(paymentRequestRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(paymentRequests.get(i.<String>getArgument(0))));
        when(paymentRequestRepo.findByCircleIdOrderByCreatedAtDesc(anyString())).thenAnswer(i -> paymentRequests.values().stream()
                .filter(r -> r.getCircle().getId().equals(i.getArgument(0))).toList());
        when(paymentRequestRepo.findByStatusAndDueDateBefore(any(), any())).thenAnswer(i -> paymentRequests.values().stream()
                .filter(r -> r.getStatus() == i.getArgument(0) && r.getDueDate() != null
                        && r.getDueDate().isBefore((java.time.LocalDate) i.getArgument(1))).toList());

        when(planRepo.save(any(RecurringPlan.class))).thenAnswer(i -> storePlan(i.getArgument(0)));
        when(planRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(plans.get(i.<String>getArgument(0))));
        when(planRepo.findByCircleIdOrderByCreatedAtDesc(anyString())).thenAnswer(i -> plans.values().stream()
                .filter(p -> p.getCircle().getId().equals(i.getArgument(0))).toList());
        when(planRepo.findByStatusAndNextDueDateLessThanEqual(any(), any())).thenAnswer(i -> plans.values().stream()
                .filter(p -> p.getStatus() == i.getArgument(0) && !p.getNextDueDate().isAfter((java.time.LocalDate) i.getArgument(1))).toList());
        when(dueRepo.findById(anyString())).thenAnswer(i -> plans.values().stream().flatMap(p -> p.getPeriods().stream())
                .flatMap(pe -> pe.getDues().stream()).filter(d -> d.getId().equals(i.getArgument(0))).findFirst());
        when(dueRepo.save(any(RecurringDue.class))).thenAnswer(i -> i.getArgument(0));

        when(expenseRepo.save(any(Expense.class))).thenAnswer(i -> {
            Expense e = i.getArgument(0);
            if (e.getId() == null) e.setId(nextId("e"));
            e.getShares().forEach(s -> { if (s.getId() == null) s.setId(nextId("s")); });
            expenses.put(e.getId(), e);
            return e;
        });
        when(expenseRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(expenses.get(i.<String>getArgument(0))));
        when(expenseRepo.findByCircleIdOrderByCreatedAtDesc(anyString())).thenAnswer(i -> expenses.values().stream()
                .filter(e -> e.getCircle().getId().equals(i.getArgument(0))).toList());
        when(shareRepo.save(any(com.momocircle.domain.ExpenseShare.class))).thenAnswer(i -> i.getArgument(0));
        wireSplitBills();

        when(activityRepo.save(any(ActivityEvent.class))).thenAnswer(i -> {
            ActivityEvent a = i.getArgument(0);
            if (a.getId() == null) a.setId(nextId("a"));
            activity.add(a);
            return a;
        });
        when(activityRepo.findByCircleIdOrderByCreatedAtDesc(anyString())).thenAnswer(i -> {
            List<ActivityEvent> out = new ArrayList<>(activity.stream().filter(a -> a.getCircle().getId().equals(i.getArgument(0))).toList());
            java.util.Collections.reverse(out);
            return out;
        });
    }


    // ------------------------------------------------------------------ split bills (Phase 9C)

    private java.util.stream.Stream<com.momocircle.domain.ExpenseShare> allShares() {
        return expenses.values().stream().flatMap(e -> e.getShares().stream());
    }

    private com.momocircle.domain.ExpenseShare shareById(String id) {
        return allShares().filter(s -> s.getId().equals(id)).findFirst().orElse(null);
    }

    com.momocircle.domain.ExpenseShare share(String id) { return shareById(id); }

    /**
     * In-memory stand-ins for the GUARDED UPDATE queries of ExpenseShareRepository. They apply the same WHERE conditions
     * (expected status, owner, amount) and return the changed-row count, so the real service logic is exercised.
     * They do not prove the JPQL itself; that needs the database tests (NOT RUN here).
     */
    @SuppressWarnings("unchecked")
    private void wireSplitBills() {
        when(eventRepo.save(any(com.momocircle.domain.ExpenseEvent.class))).thenAnswer(i -> {
            events.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(eventRepo.findByExpenseIdOrderByCreatedAtAsc(anyString())).thenAnswer(i -> events.stream()
                .filter(e -> e.getExpenseId().equals(i.getArgument(0))).toList());
        when(expenseRepo.findByCreatedByIdAndClientRequestKey(anyString(), anyString())).thenAnswer(i -> expenses.values().stream()
                .filter(e -> e.getCreatedBy().getId().equals(i.getArgument(0)) && i.getArgument(1).equals(e.getClientRequestKey())).findFirst());
        when(shareRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(shareById(i.getArgument(0))));
        when(shareRepo.lockByExpense(anyString())).thenAnswer(i -> new ArrayList<>(expenses.get(i.<String>getArgument(0)).getShares()));
        when(shareRepo.respond(anyString(), anyString(), any(), any(), any(), any())).thenAnswer(i -> {
            var s = shareById(i.getArgument(0));
            java.util.Collection<com.momocircle.domain.enums.ShareStatus> from = i.getArgument(2);
            if (s == null || !s.getDebtor().getId().equals(i.getArgument(1)) || !from.contains(s.getStatus())) return 0;
            s.setStatus(i.getArgument(3)); s.setResponseNote(i.getArgument(4)); s.setRespondedAt(i.getArgument(5));
            return 1;
        });
        when(shareRepo.reopen(anyString(), any(), any(), any(), any())).thenAnswer(i -> {
            var s = shareById(i.getArgument(0));
            java.util.Collection<com.momocircle.domain.enums.ShareStatus> from = i.getArgument(1);
            if (s == null || !from.contains(s.getStatus())) return 0;
            s.setStatus(i.getArgument(2)); s.setResponseNote(i.getArgument(3)); s.setRespondedAt(null);
            return 1;
        });
        when(shareRepo.claim(anyString(), anyString(), any(), any(), any(), any())).thenAnswer(i -> {
            var s = shareById(i.getArgument(0));
            java.util.Collection<com.momocircle.domain.enums.ShareStatus> from = i.getArgument(2);
            BigDecimal amount = i.getArgument(4);
            synchronized (this) {
                if (s == null || !s.getDebtor().getId().equals(i.getArgument(1)) || !from.contains(s.getStatus())
                        || s.getAmount().compareTo(amount) != 0) return 0;
                s.setStatus(i.getArgument(3)); s.setClaimUserId(i.getArgument(1)); s.setRespondedAt(i.getArgument(5));
                return 1;
            }
        });
        when(shareRepo.release(anyString(), anyString(), any(), any(), any())).thenAnswer(i -> {
            var s = shareById(i.getArgument(0));
            synchronized (this) {
                if (s == null || !i.getArgument(1).equals(s.getClaimUserId()) || s.getStatus() != i.getArgument(2)) return 0;
                s.setStatus(i.getArgument(3)); s.setClaimUserId(null);
                return 1;
            }
        });
        when(shareRepo.markPaidByLedger(anyString(), anyString(), anyString(), any(), any(), any(), any())).thenAnswer(i -> {
            var s = shareById(i.getArgument(0));
            synchronized (this) {
                if (s == null || !i.getArgument(1).equals(s.getClaimUserId()) || s.getStatus() != i.getArgument(4)) return 0;
                s.setStatus(i.getArgument(5)); s.setSettled(true); s.setSettledAt(i.getArgument(6));
                s.setSettlementMethod(i.getArgument(3)); s.setLedgerTransactionId(i.getArgument(2));
                s.setSettledById(i.getArgument(1)); s.setClaimUserId(null);
                return 1;
            }
        });
        when(shareRepo.markPaidExternally(anyString(), anyString(), any(), any(), any(), any())).thenAnswer(i -> {
            var s = shareById(i.getArgument(0));
            java.util.Collection<com.momocircle.domain.enums.ShareStatus> from = i.getArgument(2);
            if (s == null || !from.contains(s.getStatus())) return 0;
            s.setStatus(i.getArgument(4)); s.setSettled(true); s.setSettledAt(i.getArgument(5));
            s.setSettlementMethod(i.getArgument(3)); s.setSettledById(i.getArgument(1));
            return 1;
        });
        when(shareRepo.markUnsettled(anyString(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            var s = shareById(i.getArgument(0));
            synchronized (this) {
                if (s == null || s.getStatus() != i.getArgument(3) || s.getSettlementMethod() != i.getArgument(4)) return 0;
                s.setStatus(i.getArgument(2)); s.setSettled(false); s.setSettledAt(null);
                s.setRefundTransactionId(i.getArgument(1)); s.setSettleRound(s.getSettleRound() + 1);
                return 1;
            }
        });
        when(shareRepo.retractExternal(anyString(), any(), any(), any(), any())).thenAnswer(i -> {
            var s = shareById(i.getArgument(0));
            java.util.Collection<com.momocircle.domain.enums.SettlementMethod> methods = i.getArgument(2);
            if (s == null || s.getStatus() != i.getArgument(1) || !methods.contains(s.getSettlementMethod())) return 0;
            s.setStatus(i.getArgument(3)); s.setSettled(false); s.setSettledAt(null); s.setSettlementMethod(null);
            s.setSettledById(null); s.setRespondedAt(null);
            return 1;
        });
        when(shareRepo.cancelOutstanding(anyString(), any(), any(), any())).thenAnswer(i -> {
            java.util.Collection<com.momocircle.domain.enums.ShareStatus> from = i.getArgument(1);
            int n = 0;
            for (var s : expenses.get(i.<String>getArgument(0)).getShares()) {
                if (from.contains(s.getStatus())) { s.setStatus(i.getArgument(2)); n++; }
            }
            return n;
        });
        when(shareRepo.findOwedBy(anyString(), any())).thenAnswer(i -> allShares()
                .filter(s -> s.getDebtor().getId().equals(i.getArgument(0)) && !s.getExpense().getPaidBy().getId().equals(i.getArgument(0))).toList());
        when(shareRepo.findOwedTo(anyString(), any())).thenAnswer(i -> allShares()
                .filter(s -> s.getExpense().getPaidBy().getId().equals(i.getArgument(0)) && !s.getDebtor().getId().equals(i.getArgument(0))).toList());
    }

    /** Removes a user's circle membership without touching their expense history (what leaving or removal does). */
    void leave(String circleId, User user) {
        memberships.removeIf(m -> m.getCircle().getId().equals(circleId) && m.getUser().getId().equals(user.getId()));
    }

    private Payment storePayment(Payment p) {
        if (p.getId() == null) p.setId(nextId("pay"));
        payments.put(p.getId(), p);
        return p;
    }

    private RecurringPlan storePlan(RecurringPlan plan) {
        if (plan.getId() == null) plan.setId(nextId("rp"));
        plans.put(plan.getId(), plan);
        for (RecurringPeriod period : plan.getPeriods()) { // emulate cascade = ALL
            if (period.getId() == null) period.setId(nextId("pe"));
            for (RecurringDue due : period.getDues()) {
                if (due.getId() == null) due.setId(nextId("d"));
            }
        }
        return plan;
    }

    static BigDecimal d(String v) { return new BigDecimal(v); }

    static PaymentStatus status(com.momocircle.dto.ContributionDtos.ContributionResponse r) { return r.status(); }
}
