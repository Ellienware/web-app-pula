package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.ContributionDtos.ContributeRequest;
import com.momocircle.dto.ContributionDtos.ContributionResponse;
import com.momocircle.dto.StokvelDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Executes the REAL StokvelContributionService against fakes. The existing ContributionService is replaced by a
 * stand-in that only records the call and creates a Contribution/Payment, so this verifies what the stokvel layer
 * adds: server-chosen period (oldest unpaid first), linking, idempotency, confirmed-only status, goal earmarking.
 */
class StokvelContributionServiceTest {

    interface Custom { Object handle(String name, Object[] a, Map<String, Object> store); }

    @SuppressWarnings("unchecked")
    static <T> T repo(Class<T> type, Map<String, Object> store, Custom custom) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            Object[] args = a == null ? new Object[0] : a;
            switch (m.getName()) {
                case "save" -> { BaseEntity e = (BaseEntity) args[0]; if (e.getCreatedAt() == null) e.setCreatedAt(Instant.now()); store.put(e.getId(), e); return e; }
                case "findById" -> { return Optional.ofNullable(store.get((String) args[0])); }
                case "hashCode" -> { return System.identityHashCode(p); }
                case "equals" -> { return p == args[0]; }
                default -> { return custom.handle(m.getName(), args, store); }
            }
        });
    }

    static final class World {
        final Map<String, Object> contribStore = new LinkedHashMap<>(), linkStore = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();
        final List<ContributeRequest> forwarded = new ArrayList<>();
        final Map<String, String> byKey = new HashMap<>();
        final Circle circle = new Circle();
        final StokvelProfile profile = new StokvelProfile();
        final CircleGoal goal = new CircleGoal();
        final List<CircleMembership> members = new ArrayList<>();
        LocalDate today = LocalDate.of(2026, 10, 8);
        PaymentStatus nextStatus = PaymentStatus.SUCCESSFUL;
        boolean reachGoalOnNext = false;
        StokvelContributionService service;

        @SuppressWarnings("unchecked")
        World() {
            circle.setId("C1");
            circle.setName("Grocery club");
            circle.setCurrency("ZAR");
            goal.setId("G1");
            goal.setTargetAmount(new BigDecimal("5000.00"));
            goal.setStatus(GoalStatus.ACTIVE);
            profile.setCircle(circle);
            profile.setGoal(goal);
            profile.setStokvelType(StokvelType.GOAL_BASED);
            profile.setContributionAmount(new BigDecimal("500.00"));
            profile.setContributionFrequency(RecurringFrequency.MONTHLY);
            profile.setStartDate(LocalDate.of(2026, 7, 1)); // periods: Jul(0) Aug(1) Sep(2) Oct(3)
            profile.setApprovalRule(ApprovalRuleType.SINGLE);

            ContributionRepository contributions = repo(ContributionRepository.class, contribStore, (n, a, s) -> { throw new UnsupportedOperationException(n); });
            StokvelContributionLinkRepository links = repo(StokvelContributionLinkRepository.class, linkStore, (n, a, s) -> {
                List<StokvelContributionLink> all = s.values().stream().map(o -> (StokvelContributionLink) o).toList();
                switch (n) {
                    case "findByContributionId": return all.stream().filter(l -> l.getContribution().getId().equals(a[0])).findFirst();
                    case "confirmedByMemberAndPeriod": {
                        Map<String, BigDecimal> sums = new LinkedHashMap<>();
                        for (StokvelContributionLink l : all) {
                            if (l.getContribution().getPayment().getStatus() == a[1]) {
                                sums.merge(l.getUser().getId() + "|" + l.getPeriodIndex(), l.getContribution().getPayment().getAmount(), BigDecimal::add);
                            }
                        }
                        List<Object[]> rows = new ArrayList<>();
                        sums.forEach((k, v) -> { String[] parts = k.split("\\|"); rows.add(new Object[] {parts[0], Integer.parseInt(parts[1]), v}); });
                        return rows;
                    }
                    default: throw new UnsupportedOperationException(n);
                }
            });
            final World w = this;
            StokvelSupport support = new StokvelSupport(null, null, null, null, null) {
                @Override public Context require(String circleId, User user) {
                    CircleMembership m = w.members.stream().filter(x -> x.getUser().getId().equals(user.getId())).findFirst()
                            .orElseThrow(() -> ApiException.forbidden("not a member"));
                    return new Context(w.profile, m);
                }
                @Override public List<CircleMembership> members(String circleId) { return w.members; }
                @Override public LocalDate today() { return w.today; }
            };
            ContributionService stand = new ContributionService(null, null, null, null, null, null, null, null, null, null) {
                @Override public ContributionResponse contribute(String circleId, User user, ContributeRequest req) {
                    w.forwarded.add(req);
                    String existing = req.idempotencyKey() == null ? null : w.byKey.get(user.getId() + req.idempotencyKey());
                    Contribution c = existing != null ? (Contribution) w.contribStore.get(existing) : null;
                    if (c == null) {
                        c = new Contribution();
                        c.setCircle(w.circle);
                        c.setContributor(user);
                        Payment p = new Payment();
                        p.setAmount(req.amount());
                        setStatus(p, w.nextStatus);
                        if (w.reachGoalOnNext) w.goal.setStatus(GoalStatus.REACHED);
                        c.setPayment(p);
                        w.contribStore.put(c.getId(), c);
                        if (req.idempotencyKey() != null) w.byKey.put(user.getId() + req.idempotencyKey(), c.getId());
                    }
                    return new ContributionResponse(c.getId(), circleId, user.getId(), user.getFullName(), req.amount(), "ZAR",
                            w.nextStatus, null, req.goalId(), null, Instant.now());
                }
            };
            ContributionRepository byId = repo(ContributionRepository.class, contribStore, (n, a, s) -> { throw new UnsupportedOperationException(n); });
            GoalService goals = new GoalService(null, null, null, null, null) {
                @Override public CircleGoal requireGoal(String circleId, String goalId) { return w.goal; }
            };
            NotificationService notif = new NotificationService(null) {
                @Override public void notify(User r, NotificationType t, String m, String ref) { notes.add(r.getFullName() + ":" + t.name()); }
            };
            service = new StokvelContributionService(support, stand, byId, links, goals, notif);
        }

        User join(String name, CircleRole role) {
            User u = new User();
            u.setFullName(name);
            u.setEmail(name + "@x.test");
            CircleMembership m = new CircleMembership();
            m.setCircle(circle);
            m.setUser(u);
            m.setRole(role);
            m.setCreatedAt(Instant.parse("2026-06-01T08:00:00Z")); // before the stokvel starts
            members.add(m);
            return u;
        }

        List<Integer> periods() {
            return linkStore.values().stream().map(o -> ((StokvelContributionLink) o).getPeriodIndex()).toList();
        }
    }

    /** Payment only exposes guarded transitions; tests set the stored status directly. */
    static void setStatus(Payment p, PaymentStatus st) {
        try {
            var f = Payment.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(p, st);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StokvelContributeRequest pay(String amount, String key) {
        return new StokvelContributeRequest(new BigDecimal(amount), key);
    }

    @Test
    void contributionsFillTheOldestUnpaidPeriodFirstThenTheCurrentOne() {
        World w = new World();
        User bea = w.join("Bea", CircleRole.MEMBER);
        for (int i = 0; i < 5; i++) w.service.contribute("C1", bea, pay("500.00", "k" + i));
        assertEquals(List.of(0, 1, 2, 3, 3), w.periods());
    }

    @Test
    void partialPaymentLeavesThePeriodOpenUntilTheRestArrives() {
        World w = new World();
        User bea = w.join("Bea", CircleRole.MEMBER);
        w.service.contribute("C1", bea, pay("200.00", "a"));
        ContributionStatusView s = w.service.status("C1", bea);
        MemberPeriodStatus first = s.missed().stream().filter(m -> m.periodIndex() == 0).findFirst().orElseThrow();
        assertEquals(ContributionStatus.MISSED, first.status());          // July ended, only 200 of 500 paid
        assertEquals(new BigDecimal("200.00"), first.paid());
        w.service.contribute("C1", bea, pay("300.00", "b"));              // goes to July again (still short)
        assertEquals(List.of(0, 0), w.periods());
        assertTrue(w.service.status("C1", bea).missed().stream().noneMatch(m -> m.periodIndex() == 0));
    }

    @Test
    void statusShowsMissedPeriodsAndTheCurrentPeriodForEveryMember() {
        World w = new World();
        User bea = w.join("Bea", CircleRole.MEMBER);
        w.join("Cy", CircleRole.MEMBER);
        w.service.contribute("C1", bea, pay("500.00", "x")); // July paid
        ContributionStatusView s = w.service.status("C1", bea);
        assertEquals(3, s.currentPeriod());
        assertEquals(LocalDate.of(2026, 10, 1), s.periodStart());
        assertEquals(LocalDate.of(2026, 10, 31), s.periodEnd());
        assertEquals(2, s.currentPeriodMembers().size());
        assertTrue(s.currentPeriodMembers().stream().allMatch(m -> m.status() == ContributionStatus.DUE));
        // Bea missed Aug+Sep (2), Cy missed Jul+Aug+Sep (3)
        assertEquals(5, s.missed().size());
        assertTrue(s.missed().stream().allMatch(m -> m.status() == ContributionStatus.MISSED));
    }

    @Test
    void onlyConfirmedPaymentsCount() {
        World w = new World();
        User bea = w.join("Bea", CircleRole.MEMBER);
        w.nextStatus = PaymentStatus.PENDING;
        w.service.contribute("C1", bea, pay("500.00", "p1"));
        assertTrue(w.service.status("C1", bea).missed().stream().anyMatch(m -> m.periodIndex() == 0));
    }

    @Test
    void replayWithTheSameKeyCreatesOneLink() {
        World w = new World();
        User bea = w.join("Bea", CircleRole.MEMBER);
        ContributionResponse a = w.service.contribute("C1", bea, pay("500.00", "same"));
        ContributionResponse b = w.service.contribute("C1", bea, pay("500.00", "same"));
        assertEquals(a.id(), b.id());
        assertEquals(1, w.linkStore.size());
    }

    @Test
    void contributionsAreEarmarkedToTheSharedGoalAndGoalReachedNotifiesEveryone() {
        World w = new World();
        User bea = w.join("Bea", CircleRole.MEMBER);
        w.join("Cy", CircleRole.MEMBER);
        w.service.contribute("C1", bea, pay("500.00", "g1"));
        assertEquals("G1", w.forwarded.get(0).goalId());
        assertTrue(w.notes.isEmpty());
        w.reachGoalOnNext = true; // the existing ContributionService flips the goal to REACHED on this payment
        w.service.contribute("C1", bea, pay("500.00", "g2"));
        assertEquals(List.of("Bea:GOAL_COMPLETED", "Cy:GOAL_COMPLETED"), w.notes);
        // already reached: further contributions do not notify again
        w.service.contribute("C1", bea, pay("500.00", "g3"));
        assertEquals(2, w.notes.size());
    }

    @Test
    void cannotContributeBeforeTheStokvelStarts() {
        World w = new World();
        User bea = w.join("Bea", CircleRole.MEMBER);
        w.today = LocalDate.of(2026, 6, 30);
        ApiException e = assertThrows(ApiException.class, () -> w.service.contribute("C1", bea, pay("500.00", "e")));
        assertEquals("BAD_REQUEST", e.getStatus().name());
        assertTrue(w.linkStore.isEmpty());
    }

    @Test
    void nonMembersCannotContributeOrSeeStatus() {
        World w = new World();
        w.join("Bea", CircleRole.MEMBER);
        User outsider = new User();
        outsider.setFullName("Out");
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> w.service.contribute("C1", outsider, pay("500.00", "o"))).getStatus().name());
        assertEquals("FORBIDDEN", assertThrows(ApiException.class, () -> w.service.status("C1", outsider)).getStatus().name());
        assertTrue(w.forwarded.isEmpty());
    }

    @Test
    void aMemberWhoJoinedLaterDoesNotOweEarlierPeriods() {
        World w = new World();
        User late = w.join("Late", CircleRole.MEMBER);
        w.members.get(0).setCreatedAt(Instant.parse("2026-09-15T08:00:00Z")); // joined mid-September (period 2)
        ContributionStatusView s = w.service.status("C1", late);
        // July and August are not owed; September (the period they joined in) has ended unpaid.
        assertEquals(1, s.missed().size());
        assertEquals(2, s.missed().get(0).periodIndex());
        // their first contribution is allocated to September, not July.
        w.service.contribute("C1", late, pay("500.00", "late1"));
        assertEquals(List.of(2), w.periods());
    }
}
