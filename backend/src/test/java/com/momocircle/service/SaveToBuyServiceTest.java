package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.SaveToBuyDtos.*;
import com.momocircle.dto.StokvelDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Executes the REAL SaveToBuyService together with the REAL StokvelRequestService (the approval engine) against
 * in-memory fakes: Proxy repositories, no Spring, no Mockito, no database. The existing checkout classes are not run
 * here; the four hook methods they call (validate / attach / paid / failed) are exercised directly.
 */
class SaveToBuyServiceTest {

    interface Custom { Object handle(String name, Object[] a, Map<String, Object> store); }

    @SuppressWarnings("unchecked")
    static <T> T repo(Class<T> type, Map<String, Object> store, Custom custom) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            Object[] args = a == null ? new Object[0] : a;
            switch (m.getName()) {
                case "save" -> { BaseEntity e = (BaseEntity) args[0]; if (e.getCreatedAt() == null) e.setCreatedAt(Instant.now()); store.put(e.getId(), e); return e; }
                case "findById" -> { return Optional.ofNullable(store.get((String) args[0])); }
                case "findAll" -> { return new ArrayList<>(store.values()); }
                case "flush" -> { return null; }
                case "hashCode" -> { return System.identityHashCode(p); }
                case "equals" -> { return p == args[0]; }
                default -> { return custom.handle(m.getName(), args, store); }
            }
        });
    }

    static final class World {
        final Map<String, Object> s2bStore = new LinkedHashMap<>(), goalStore = new LinkedHashMap<>(),
                reqStore = new LinkedHashMap<>(), voteStore = new LinkedHashMap<>(), orderStore = new HashMap<>(), stockStore = new HashMap<>();
        final List<String> activity = new ArrayList<>();
        final Map<String, Circle> circles = new HashMap<>();
        final Map<String, StokvelProfile> profiles = new HashMap<>();
        final List<CircleMembership> memberships = new ArrayList<>();
        final Map<String, BigDecimal> savedByGoal = new HashMap<>();
        BigDecimal trackedFunds = new BigDecimal("100000.00");
        StokvelRequestService stokvelRequests;
        SaveToBuyService service;
        StokvelRequestRepository stokvelRepo;

        @SuppressWarnings("unchecked")
        World() {
            SaveToBuyRequestRepository s2b = repo(SaveToBuyRequestRepository.class, s2bStore, (n, a, s) -> {
                List<SaveToBuyRequest> all = s.values().stream().map(o -> (SaveToBuyRequest) o).toList();
                switch (n) {
                    case "findByOwnerIdAndScopeOrderByCreatedAtDesc": return all.stream().filter(r -> r.getOwner().getId().equals(a[0]) && r.getScope() == a[1]).toList();
                    case "findByCircleIdOrderByCreatedAtDesc": return all.stream().filter(r -> r.getCircle().getId().equals(a[0])).toList();
                    case "findByOwnerIdAndIdempotencyKey": return all.stream().filter(r -> r.getOwner().getId().equals(a[0]) && Objects.equals(r.getIdempotencyKey(), a[1])).findFirst();
                    case "findByOrderKindAndOrderId": return all.stream().filter(r -> r.getOrderKind() == a[0] && Objects.equals(r.getOrderId(), a[1])).findFirst();
                    case "findByStokvelRequestId": return all.stream().filter(r -> r.getStokvelRequest() != null && r.getStokvelRequest().getId().equals(a[0])).findFirst();
                    case "existsByStokvelRequestId": return all.stream().anyMatch(r -> r.getStokvelRequest() != null && r.getStokvelRequest().getId().equals(a[0]));
                    case "findForUpdate": return Optional.ofNullable(s.get((String) a[0]));
                    case "existsOpenDuplicate": return all.stream().anyMatch(r -> r.getGoal().getId().equals(a[0]) && r.getOwner().getId().equals(a[1])
                            && r.getVertical() == a[2] && r.getRequestedAmount().compareTo((BigDecimal) a[3]) == 0 && r.getPurpose().equals(a[4])
                            && ((Collection<SaveToBuyStatus>) a[5]).contains(r.getStatus()));
                    default: throw new UnsupportedOperationException(n);
                }
            });
            CircleGoalRepository goals = repo(CircleGoalRepository.class, goalStore, (n, a, s) -> {
                if (n.equals("findByCircleIdInOrderByCreatedAtDesc")) {
                    return s.values().stream().map(o -> (CircleGoal) o).filter(g -> ((Collection<String>) a[0]).contains(g.getCircle().getId())).toList();
                }
                if (n.equals("findByCircleIdInAndPurchaseVerticalIsNotNullOrderByCreatedAtDesc")) {
                    return s.values().stream().map(o -> (CircleGoal) o)
                            .filter(g -> ((Collection<String>) a[0]).contains(g.getCircle().getId()) && g.getPurchaseVertical() != null).toList();
                }
                throw new UnsupportedOperationException(n);
            });
            CircleMembershipRepository ms = repo(CircleMembershipRepository.class, new HashMap<>(), (n, a, s) -> {
                switch (n) {
                    case "findByUserId": return memberships.stream().filter(m -> m.getUser().getId().equals(a[0])).toList();
                    case "findByCircleIdAndUserId": return memberships.stream().filter(m -> m.getCircle().getId().equals(a[0]) && m.getUser().getId().equals(a[1])).findFirst();
                    case "existsByCircleIdAndUserId": return memberships.stream().anyMatch(m -> m.getCircle().getId().equals(a[0]) && m.getUser().getId().equals(a[1]));
                    case "countByCircleId": return memberships.stream().filter(m -> m.getCircle().getId().equals(a[0])).count();
                    default: throw new UnsupportedOperationException(n);
                }
            });
            StokvelProfileRepository sp = repo(StokvelProfileRepository.class, new HashMap<>(), (n, a, s) -> {
                if (n.equals("findByCircleId")) return Optional.ofNullable(profiles.get((String) a[0]));
                throw new UnsupportedOperationException(n);
            });
            stokvelRepo = repo(StokvelRequestRepository.class, reqStore, (n, a, s) -> {
                List<StokvelRequest> all = s.values().stream().map(o -> (StokvelRequest) o).toList();
                switch (n) {
                    case "findByIdAndCircleId", "findForUpdate": return all.stream().filter(r -> r.getId().equals(a[0]) && r.getCircle().getId().equals(a[1])).findFirst();
                    case "findByCircleIdOrderByCreatedAtDesc": return all.stream().filter(r -> r.getCircle().getId().equals(a[0])).toList();
                    case "findByCircleIdAndRequesterIdAndIdempotencyKey": return all.stream().filter(r -> r.getCircle().getId().equals(a[0]) && r.getRequester().getId().equals(a[1]) && Objects.equals(r.getIdempotencyKey(), a[2])).findFirst();
                    case "existsByCircleIdAndRequesterIdAndRequestTypeAndAmountAndPurposeAndStatus": return all.stream().anyMatch(r -> r.getCircle().getId().equals(a[0]) && r.getRequester().getId().equals(a[1]) && r.getRequestType() == a[2] && r.getAmount().compareTo((BigDecimal) a[3]) == 0 && r.getPurpose().equals(a[4]) && r.getStatus() == a[5]);
                    case "sumAmountByStatuses": return all.stream().filter(r -> r.getCircle().getId().equals(a[0]) && ((Collection<StokvelRequestStatus>) a[1]).contains(r.getStatus())).map(StokvelRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    default: throw new UnsupportedOperationException(n);
                }
            });
            StokvelRequestVoteRepository votes = repo(StokvelRequestVoteRepository.class, voteStore, (n, a, s) -> {
                List<StokvelRequestVote> all = s.values().stream().map(o -> (StokvelRequestVote) o).toList();
                switch (n) {
                    case "findByRequestIdOrderByCreatedAtAsc": return all.stream().filter(v -> v.getRequest().getId().equals(a[0])).toList();
                    case "findByRequestIdAndVoterId": return all.stream().filter(v -> v.getRequest().getId().equals(a[0]) && v.getVoter().getId().equals(a[1])).findFirst();
                    case "countByRequestIdAndDecision": return all.stream().filter(v -> v.getRequest().getId().equals(a[0]) && v.getDecision() == a[1]).count();
                    default: throw new UnsupportedOperationException(n);
                }
            });
            ContributionRepository contributions = repo(ContributionRepository.class, new HashMap<>(), (n, a, s) -> {
                if (n.equals("sumConfirmedAmountForGoal")) return savedByGoal.getOrDefault((String) a[0], BigDecimal.ZERO);
                throw new UnsupportedOperationException(n);
            });
            final World w = this;
            StokvelSupport support = new StokvelSupport(null, null, null, null, stokvelRepo) {
                @Override public Context require(String circleId, User user) {
                    CircleMembership m = w.memberships.stream().filter(x -> x.getCircle().getId().equals(circleId) && x.getUser().getId().equals(user.getId())).findFirst()
                            .orElseThrow(() -> ApiException.forbidden("not a member"));
                    return new Context(w.profiles.get(circleId), m);
                }
                @Override public List<CircleMembership> members(String circleId) { return w.memberships.stream().filter(x -> x.getCircle().getId().equals(circleId)).toList(); }
                @Override public BigDecimal confirmedContributions(String circleId) { return w.trackedFunds; }
                @Override public BigDecimal committed(String circleId) {
                    return stokvelRepo.sumAmountByStatuses(circleId, List.of(StokvelRequestStatus.APPROVED, StokvelRequestStatus.EXECUTED));
                }
            };
            FinancialValidationService validation = new FinancialValidationService(null, null) {
                @Override public void requireValidAmount(BigDecimal amount, String field) {
                    if (amount == null || amount.signum() <= 0) throw ApiException.badRequest(field + " must be positive");
                }
            };
            ActivityService act = new ActivityService(null, null) {
                @Override public void record(Circle c, User u, ActivityType t, String m) { activity.add(t.name()); }
            };
            NotificationService notif = new NotificationService(null) {
                @Override public void notify(User r, NotificationType t, String m, String ref) { }
            };
            GoalService goalSvc = new GoalService(null, null, null, null, null) {
                @Override public CircleGoal requireGoal(String circleId, String goalId) { return (CircleGoal) w.goalStore.get(goalId); }
            };
            stokvelRequests = new StokvelRequestService(support, stokvelRepo, votes,
                    repo(OrderRepository.class, orderStore, (n, a, s) -> { throw new UnsupportedOperationException(n); }),
                    repo(StockOrderRepository.class, stockStore, (n, a, s) -> { throw new UnsupportedOperationException(n); }),
                    goalSvc, act, notif, validation);
            service = new SaveToBuyService(s2b, goals, ms, sp, stokvelRepo, votes, stokvelRequests, contributions, act, validation);
            try {
                var f = StokvelRequestService.class.getDeclaredField("saveToBuyRequests");
                f.setAccessible(true);
                f.set(stokvelRequests, s2b);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }

        User user(String name) { User u = new User(); u.setFullName(name); u.setEmail(name + "@x.test"); return u; }

        Circle circle(String id, boolean stokvel, ApprovalRuleType rule) {
            Circle c = new Circle();
            c.setId(id); c.setName("Circle " + id); c.setCurrency("ZAR");
            c.setType(stokvel ? CircleType.STOKVEL : CircleType.SAVINGS_GOAL);
            circles.put(id, c);
            if (stokvel) {
                StokvelProfile p = new StokvelProfile();
                p.setCircle(c); p.setStokvelType(StokvelType.STOCK); p.setApprovalRule(rule); p.setRequestValidityDays(14);
                profiles.put(id, p);
            }
            return c;
        }

        User join(String circleId, String name, CircleRole role) {
            User u = user(name);
            joinExisting(circleId, u, role);
            return u;
        }

        void joinExisting(String circleId, User u, CircleRole role) {
            CircleMembership m = new CircleMembership();
            m.setCircle(circles.get(circleId)); m.setUser(u); m.setRole(role); m.setCreatedAt(Instant.now());
            memberships.add(m);
        }

        CircleGoal goal(String circleId, User creator, String target, String saved, SaveToBuyVertical vertical, boolean reached) {
            CircleGoal g = new CircleGoal();
            g.setCircle(circles.get(circleId)); g.setCreatedBy(creator); g.setTitle("Goal " + circleId);
            g.setTargetAmount(new BigDecimal(target));
            g.setStatus(reached ? GoalStatus.REACHED : GoalStatus.ACTIVE);
            g.setPurchaseVertical(vertical);
            goalStore.put(g.getId(), g);
            savedByGoal.put(g.getId(), new BigDecimal(saved));
            return g;
        }

        CreateSaveToBuyRequest plan(CircleGoal g, String amount, String purpose, String buyerId, String key) {
            return new CreateSaveToBuyRequest(g.getId(), null, purpose, new BigDecimal(amount), buyerId, key);
        }
    }

    private static void expect(String status, Runnable r) {
        assertEquals(status, assertThrows(ApiException.class, r::run).getStatus().name());
    }

    // =============================================================== personal

    @Test
    void personalGoalCanBeDesignatedOnlyByItsOwnerAndOnlyWhenNotShared() {
        World w = new World();
        w.circle("P", false, null);
        User me = w.join("P", "Me", CircleRole.OWNER);
        User stranger = w.user("Stranger");
        CircleGoal g = w.goal("P", me, "1000.00", "0.00", null, false);
        expect("NOT_FOUND", () -> w.service.designateGoal(g.getId(), stranger, new DesignateGoalRequest(SaveToBuyVertical.RETAIL, null)));
        EligibleGoal e = w.service.designateGoal(g.getId(), me, new DesignateGoalRequest(SaveToBuyVertical.RETAIL, "Groceries"));
        assertEquals(SaveToBuyVertical.RETAIL, e.vertical());
        assertEquals(SaveToBuyScope.PERSONAL, e.scope());
        assertTrue(e.canCreateRequest());
        assertEquals(1, w.service.eligibleGoals(me).size());
        assertTrue(w.service.eligibleGoals(stranger).isEmpty());
        // someone joins: a shared circle's money cannot be spent by one member's purchase plan
        w.join("P", "Friend", CircleRole.MEMBER);
        expect("CONFLICT", () -> w.service.designateGoal(g.getId(), me, new DesignateGoalRequest(SaveToBuyVertical.FOOD, null)));
    }

    @Test
    void undesignatedGoalsAreListedOnlyOnRequestAndCannotHaveRequestsUntilDesignated() {
        World w = new World();
        w.circle("P", false, null);
        User me = w.join("P", "Me", CircleRole.OWNER);
        CircleGoal g = w.goal("P", me, "1000.00", "0.00", null, false);
        assertTrue(w.service.eligibleGoals(me).isEmpty());
        List<EligibleGoal> all = w.service.eligibleGoals(me, true);
        assertEquals(1, all.size());
        assertNull(all.get(0).vertical());
        assertTrue(all.get(0).canDesignate());
        assertFalse(all.get(0).canCreateRequest());
        // stokvel plain members cannot designate
        w.circle("S", true, ApprovalRuleType.SINGLE);
        w.join("S", "Owner", CircleRole.OWNER);
        User bea = w.join("S", "Bea", CircleRole.MEMBER);
        w.goal("S", me, "100.00", "0.00", null, false);
        EligibleGoal sg = w.service.eligibleGoals(bea, true).get(0);
        assertFalse(sg.canDesignate());
        expect("FORBIDDEN", () -> w.service.designateGoal(sg.goalId(), bea, new DesignateGoalRequest(SaveToBuyVertical.STOCK, null)));
    }

    @Test
    void personalRequestLifecycleDraftThenConfirmOnlyWhenTheGoalIsReached() {
        World w = new World();
        w.circle("P", false, null);
        User me = w.join("P", "Me", CircleRole.OWNER);
        CircleGoal g = w.goal("P", me, "1000.00", "400.00", SaveToBuyVertical.RETAIL, false);

        expect("BAD_REQUEST", () -> w.service.create(me, w.plan(g, "0.00", "x", null, null)));
        SaveToBuyView draft = w.service.create(me, w.plan(g, "900.00", "Groceries", null, "k1"));
        assertEquals(SaveToBuyStatus.DRAFT, draft.status());
        assertEquals(me.getId(), draft.namedBuyerId());
        // idempotent create, and an identical open request is refused
        assertEquals(draft.id(), w.service.create(me, w.plan(g, "900.00", "Groceries", null, "k1")).id());
        expect("CONFLICT", () -> w.service.create(me, w.plan(g, "900.00", "Groceries", null, "k2")));

        // goal not reached: nothing is confirmed and nothing is bought automatically
        expect("CONFLICT", () -> w.service.confirm(draft.id(), me));
        assertEquals(SaveToBuyStatus.DRAFT, w.service.get(draft.id(), me).status());

        w.savedByGoal.put(g.getId(), new BigDecimal("1000.00"));
        SaveToBuyView ok = w.service.confirm(draft.id(), me);
        assertEquals(SaveToBuyStatus.APPROVED, ok.status());
        assertNotNull(ok.expiresAt());
        assertTrue(w.activity.contains("SAVE_TO_BUY_APPROVED"));
        expect("CONFLICT", () -> w.service.confirm(draft.id(), me));
    }

    @Test
    void shortfallAndDifferenceAreShownAndFundedIsNotClaimedWhenTheGoalIsNotCovering() {
        World w = new World();
        w.circle("P", false, null);
        User me = w.join("P", "Me", CircleRole.OWNER);
        CircleGoal g = w.goal("P", me, "1000.00", "1000.00", SaveToBuyVertical.RETAIL, true);
        SaveToBuyView big = w.service.create(me, w.plan(g, "1250.00", "TV", null, null));
        assertEquals(new BigDecimal("250.00"), big.shortfall());
        assertEquals(new BigDecimal("250.00"), big.differenceFromTarget());
        assertFalse(big.fullyFunded());
        assertTrue(big.goalReached());
        SaveToBuyView small = w.service.create(me, w.plan(g, "800.00", "Fridge", null, null));
        assertEquals(0, small.shortfall().signum());
        assertTrue(small.fullyFunded());
        assertEquals(new BigDecimal("-200.00"), small.differenceFromTarget());
        assertTrue(big.notice().contains("does not hold"));
    }

    @Test
    void goalMustBeDesignatedAndVerticalMustMatch() {
        World w = new World();
        w.circle("P", false, null);
        User me = w.join("P", "Me", CircleRole.OWNER);
        CircleGoal plain = w.goal("P", me, "1000.00", "0.00", null, false);
        expect("BAD_REQUEST", () -> w.service.create(me, w.plan(plain, "100.00", "x", null, null)));
        CircleGoal food = w.goal("P", me, "1000.00", "0.00", SaveToBuyVertical.FOOD, false);
        expect("BAD_REQUEST", () -> w.service.create(me, new CreateSaveToBuyRequest(food.getId(), SaveToBuyVertical.STOCK, "x", new BigDecimal("1.00"), null, null)));
        expect("BAD_REQUEST", () -> w.service.create(me, w.plan(food, "10.00", "x", "someone-else", null)));
    }

    @Test
    void personalRequestsAreInvisibleToEveryoneElse() {
        World w = new World();
        w.circle("P", false, null);
        User me = w.join("P", "Me", CircleRole.OWNER);
        w.circle("Q", false, null);
        User other = w.join("Q", "Other", CircleRole.OWNER);
        CircleGoal g = w.goal("P", me, "100.00", "100.00", SaveToBuyVertical.RETAIL, true);
        SaveToBuyView v = w.service.create(me, w.plan(g, "100.00", "x", null, null));
        expect("NOT_FOUND", () -> w.service.get(v.id(), other));
        expect("NOT_FOUND", () -> w.service.confirm(v.id(), other));
        expect("NOT_FOUND", () -> w.service.cancel(v.id(), other));
        expect("NOT_FOUND", () -> w.service.create(other, w.plan(g, "100.00", "x", null, null))); // someone else's goal id
        expect("NOT_FOUND", () -> w.service.get("no-such-id", me));
        assertTrue(w.service.listMine(other).isEmpty());
        assertEquals(1, w.service.listMine(me).size());
    }

    @Test
    void checkoutHooksAttachPayAndReleaseWithoutDuplicatingTheOrder() {
        World w = new World();
        w.circle("P", false, null);
        User me = w.join("P", "Me", CircleRole.OWNER);
        CircleGoal g = w.goal("P", me, "1000.00", "1000.00", SaveToBuyVertical.RETAIL, true);
        SaveToBuyView v = w.service.create(me, w.plan(g, "1000.00", "Groceries", null, null));
        w.service.confirm(v.id(), me);
        SaveToBuyRequest r = (SaveToBuyRequest) w.s2bStore.get(v.id());

        // ordinary checkout (no id) is untouched
        assertNull(w.service.validateForCheckout(me, null, SaveToBuyVertical.RETAIL, new BigDecimal("50.00"), "k"));
        assertNull(w.service.validateForCheckout(me, "  ", SaveToBuyVertical.RETAIL, new BigDecimal("50.00"), "k"));
        // wrong vertical
        expect("BAD_REQUEST", () -> w.service.validateForCheckout(me, v.id(), SaveToBuyVertical.FOOD, new BigDecimal("50.00"), "k"));
        // a personal purchase may cost more than the goal: the checkout charges the real total
        assertSame(r, w.service.validateForCheckout(me, v.id(), SaveToBuyVertical.RETAIL, new BigDecimal("1250.00"), "key-1"));

        w.service.attachOrder(r, RelatedOrderKind.ORDER, "order-1", "key-1");
        assertEquals(SaveToBuyStatus.CHECKOUT_STARTED, r.getStatus());
        // replay of the same checkout is recognised; a different checkout cannot reuse the request
        assertSame(r, w.service.validateForCheckout(me, v.id(), SaveToBuyVertical.RETAIL, new BigDecimal("1250.00"), "key-1"));
        expect("CONFLICT", () -> w.service.validateForCheckout(me, v.id(), SaveToBuyVertical.RETAIL, new BigDecimal("10.00"), "key-2"));
        // cannot be cancelled mid-checkout
        expect("CONFLICT", () -> w.service.cancel(v.id(), me));

        // payment failed -> the request is usable again and the order link is cleared
        w.service.onOrderFailed(RelatedOrderKind.ORDER, "order-1");
        assertEquals(SaveToBuyStatus.APPROVED, r.getStatus());
        assertNull(r.getOrderId());
        w.service.attachOrder(r, RelatedOrderKind.ORDER, "order-2", "key-3");
        assertNull(w.service.onOrderPaid(RelatedOrderKind.ORDER, "order-2", new BigDecimal("1250.00"))); // personal: no stokvel request
        assertEquals(SaveToBuyStatus.PURCHASED, r.getStatus());
        assertEquals(new BigDecimal("1250.00"), r.getPurchasedAmount());           // what was actually purchased
        assertEquals(new BigDecimal("1000.00"), r.getRequestedAmount());           // what was requested
        assertTrue(w.activity.contains("SAVE_TO_BUY_PURCHASED"));
        // paying the same order again changes nothing; a purchased request cannot be used again
        assertNull(w.service.onOrderPaid(RelatedOrderKind.ORDER, "order-2", new BigDecimal("1.00")));
        assertEquals(new BigDecimal("1250.00"), r.getPurchasedAmount());
        expect("CONFLICT", () -> w.service.validateForCheckout(me, v.id(), SaveToBuyVertical.RETAIL, new BigDecimal("10.00"), "key-9"));
        // "which goal motivated this order?"
        SaveToBuyView byOrder = w.service.forOrder(RelatedOrderKind.ORDER, "order-2", me);
        assertEquals(g.getId(), byOrder.goalId());
        assertEquals("order-2", byOrder.orderId());
        expect("NOT_FOUND", () -> w.service.forOrder(RelatedOrderKind.ORDER, "order-2", w.user("Nobody")));
    }

    @Test
    void foodAndStockPersonalGoalsUseTheirOwnVerticals() {
        World w = new World();
        w.circle("P", false, null);
        User me = w.join("P", "Me", CircleRole.OWNER);
        for (SaveToBuyVertical vert : new SaveToBuyVertical[] {SaveToBuyVertical.FOOD, SaveToBuyVertical.STOCK}) {
            CircleGoal g = w.goal("P", me, "500.00", "500.00", vert, true);
            SaveToBuyView v = w.service.create(me, w.plan(g, "500.00", vert.name() + " plan", null, null));
            w.service.confirm(v.id(), me);
            assertSame(w.s2bStore.get(v.id()), w.service.validateForCheckout(me, v.id(), vert, null, "k"));
            SaveToBuyVertical other = vert == SaveToBuyVertical.FOOD ? SaveToBuyVertical.STOCK : SaveToBuyVertical.FOOD;
            expect("BAD_REQUEST", () -> w.service.validateForCheckout(me, v.id(), other, null, "k"));
        }
    }

    @Test
    void serviceGoalFollowsTheSameRulesAndLinksToABookingKind() {
        World w = new World();
        w.circle("P", false, null);
        User me = w.join("P", "Me", CircleRole.OWNER);
        CircleGoal g = w.goal("P", me, "800.00", "800.00", SaveToBuyVertical.SERVICE, true);
        SaveToBuyView v = w.service.create(me, w.plan(g, "800.00", "Plumber for the bathroom", null, null));
        // not confirmed yet: nothing can be paid under it
        expect("CONFLICT", () -> w.service.validateForCheckout(me, v.id(), SaveToBuyVertical.SERVICE, new BigDecimal("500.00"), "k1"));
        w.service.confirm(v.id(), me);
        // a request for services cannot be used on a retail checkout
        expect("BAD_REQUEST", () -> w.service.validateForCheckout(me, v.id(), SaveToBuyVertical.RETAIL, new BigDecimal("500.00"), "k1"));
        SaveToBuyRequest r = w.service.validateForCheckout(me, v.id(), SaveToBuyVertical.SERVICE, new BigDecimal("950.00"), "k1");
        assertNotNull(r); // personal: the real booking total may differ from the goal; the payment charges the booking total
        w.service.attachOrder(r, RelatedOrderKind.SERVICE_BOOKING, "booking-1", "k1");
        assertEquals(SaveToBuyStatus.CHECKOUT_STARTED, r.getStatus());
        assertEquals(RelatedOrderKind.SERVICE_BOOKING, r.getOrderKind());
        assertNull(w.service.onOrderPaid(RelatedOrderKind.SERVICE_BOOKING, "booking-1", new BigDecimal("950.00")));
        assertEquals(SaveToBuyStatus.PURCHASED, r.getStatus());
        assertEquals(new BigDecimal("950.00"), r.getPurchasedAmount());
        assertEquals("booking-1", w.service.forOrder(RelatedOrderKind.SERVICE_BOOKING, "booking-1", me).orderId());
    }

    @Test
    void stokvelCanDesignateAndCapAServicePurchase() {
        Stokvel s = new Stokvel("5000.00", true, ApprovalRuleType.SINGLE);
        World w = s.w;
        CircleGoal g = w.goal("S", s.owner, "5000.00", "5000.00", null, true);
        assertEquals(SaveToBuyVertical.SERVICE, w.service.designateGoal(g.getId(), s.owner,
                new DesignateGoalRequest(SaveToBuyVertical.SERVICE, "Roof repair")).vertical());
        SaveToBuyView d = w.service.create(s.tina, w.plan(g, "3000.00", "Roof repair", s.bea.getId(), null));
        String sid = w.service.submit(d.id(), s.tina).approval().stokvelRequestId();
        w.stokvelRequests.vote("S", sid, s.owner, VoteDecision.APPROVE, null);
        // the named buyer pays; a booking total above the approved amount is refused
        expect("BAD_REQUEST", () -> w.service.validateForCheckout(s.bea, d.id(), SaveToBuyVertical.SERVICE, new BigDecimal("3000.01"), "k"));
        assertNotNull(w.service.validateForCheckout(s.bea, d.id(), SaveToBuyVertical.SERVICE, new BigDecimal("2800.00"), "k"));
        expect("FORBIDDEN", () -> w.service.validateForCheckout(s.member, d.id(), SaveToBuyVertical.SERVICE, new BigDecimal("2800.00"), "k"));
    }

    @Test
    void taxiGoalsHandOffToTheExistingRideFlowAndAreSelfCompleted() {
        World w = new World();
        w.circle("P", false, null);
        User me = w.join("P", "Me", CircleRole.OWNER);
        CircleGoal g = w.goal("P", me, "300.00", "300.00", SaveToBuyVertical.TAXI, true);
        SaveToBuyView v = w.service.create(me, w.plan(g, "300.00", "Monthly taxi fare", null, null));
        expect("CONFLICT", () -> w.service.complete(v.id(), me, new CompleteRequest("ride-1"))); // not confirmed yet
        w.service.confirm(v.id(), me);
        SaveToBuyView done = w.service.complete(v.id(), me, new CompleteRequest("RIDE-77"));
        assertEquals(SaveToBuyStatus.PURCHASED, done.status());
        assertEquals(RelatedOrderKind.EXTERNAL, done.orderKind());
        assertEquals("RIDE-77", done.completionReference());
        // a retail request cannot be completed by self-declaration
        CircleGoal rg = w.goal("P", me, "300.00", "300.00", SaveToBuyVertical.RETAIL, true);
        SaveToBuyView rv = w.service.create(me, w.plan(rg, "300.00", "Shop", null, null));
        w.service.confirm(rv.id(), me);
        expect("BAD_REQUEST", () -> w.service.complete(rv.id(), me, new CompleteRequest("x")));
    }

    // =============================================================== stokvel

    private static final class Stokvel {
        final World w = new World();
        User owner, tina, chair, bea, member;
        CircleGoal goal;
        Stokvel(String saved, boolean reached, ApprovalRuleType rule) {
            w.circle("S", true, rule);
            owner = w.join("S", "Owner", CircleRole.OWNER);
            tina = w.join("S", "Tina", CircleRole.TREASURER);
            chair = w.join("S", "Chair", CircleRole.CHAIRPERSON);
            bea = w.join("S", "Bea", CircleRole.MEMBER);
            member = w.join("S", "Mo", CircleRole.MEMBER);
            goal = w.goal("S", owner, "5000.00", saved, SaveToBuyVertical.STOCK, reached);
        }
    }

    @Test
    void stokvelGoalToApprovedRequestToNamedBuyerToPaidPurchase() {
        Stokvel s = new Stokvel("5000.00", true, ApprovalRuleType.SINGLE);
        World w = s.w;
        // 1. an authorised member (treasurer) prepares the purchase and names the buyer
        SaveToBuyView draft = w.service.create(s.tina, w.plan(s.goal, "4800.00", "Stock: maize, oil, sugar", s.bea.getId(), "s2b-1"));
        assertEquals(SaveToBuyScope.STOKVEL, draft.scope());
        assertEquals(s.bea.getId(), draft.namedBuyerId());
        // 2. submit -> group approval (the existing stokvel engine); nothing is approved or spent yet
        SaveToBuyView pending = w.service.submit(draft.id(), s.tina);
        assertEquals(SaveToBuyStatus.PENDING_APPROVAL, pending.status());
        assertEquals(1, pending.approval().required());
        assertEquals(StokvelRequestType.STOCK_PURCHASE, ((StokvelRequest) w.reqStore.get(pending.approval().stokvelRequestId())).getRequestType());
        assertEquals(pending.id(), w.service.submit(draft.id(), s.tina).id()); // replay is idempotent
        expect("CONFLICT", () -> w.service.validateForCheckout(s.bea, draft.id(), SaveToBuyVertical.STOCK, null, "k"));
        // the requester cannot approve their own request
        String sid = pending.approval().stokvelRequestId();
        expect("FORBIDDEN", () -> w.stokvelRequests.vote("S", sid, s.tina, VoteDecision.APPROVE, null));
        // 3. an eligible approver approves; the Save-to-Buy request follows
        w.stokvelRequests.vote("S", sid, s.owner, VoteDecision.APPROVE, "go");
        SaveToBuyView approved = w.service.get(draft.id(), s.owner);
        assertEquals(SaveToBuyStatus.APPROVED, approved.status());
        // 4. only the named buyer can start checkout
        expect("FORBIDDEN", () -> w.service.validateForCheckout(s.member, draft.id(), SaveToBuyVertical.STOCK, null, "k"));
        expect("FORBIDDEN", () -> w.service.validateForCheckout(s.tina, draft.id(), SaveToBuyVertical.STOCK, null, "k"));
        expect("NOT_FOUND", () -> w.service.validateForCheckout(w.user("Outsider"), draft.id(), SaveToBuyVertical.STOCK, null, "k"));
        SaveToBuyRequest r = w.service.validateForCheckout(s.bea, draft.id(), SaveToBuyVertical.STOCK, null, "co-1");
        // total above the approved amount is refused; at or below is fine
        expect("BAD_REQUEST", () -> w.service.requireWithinApproval(r, new BigDecimal("4800.01")));
        assertDoesNotThrow(() -> w.service.requireWithinApproval(r, new BigDecimal("4750.00")));
        w.service.attachOrder(r, RelatedOrderKind.STOCK_ORDER, "stock-1", "co-1");
        assertEquals(SaveToBuyStatus.CHECKOUT_STARTED, r.getStatus());
        // 5. payment succeeds in the existing stock payment flow -> purchased, stokvel request executed, order referenced
        String stokvelId = w.service.onOrderPaid(RelatedOrderKind.STOCK_ORDER, "stock-1", new BigDecimal("4750.00"));
        assertEquals(sid, stokvelId);
        assertEquals(SaveToBuyStatus.PURCHASED, r.getStatus());
        assertEquals(new BigDecimal("4750.00"), r.getPurchasedAmount());
        StokvelRequest sr = (StokvelRequest) w.reqStore.get(sid);
        assertEquals(StokvelRequestStatus.EXECUTED, sr.getStatus());
        assertEquals("stock-1", sr.getRelatedOrderId());
        assertEquals(new BigDecimal("4800.00"), sr.getAmount()); // approved amount untouched
        // the approval record cannot also be linked by hand (no double execution)
        expect("CONFLICT", () -> w.stokvelRequests.linkOrder("S", sid, s.bea, new LinkOrderRequest(RelatedOrderKind.STOCK_ORDER, "stock-2")));
    }

    @Test
    void stokvelRequestNeedsTheGoalReachedAndAnAuthorisedCreator() {
        Stokvel s = new Stokvel("2000.00", false, ApprovalRuleType.SINGLE);
        World w = s.w;
        expect("FORBIDDEN", () -> w.service.create(s.bea, w.plan(s.goal, "1000.00", "x", s.bea.getId(), null)));       // plain member
        expect("BAD_REQUEST", () -> w.service.create(s.tina, w.plan(s.goal, "1000.00", "x", null, null)));              // buyer required
        expect("BAD_REQUEST", () -> w.service.create(s.tina, w.plan(s.goal, "1000.00", "x", "not-a-member", null)));    // buyer must be a member
        expect("BAD_REQUEST", () -> w.service.create(s.tina, new CreateSaveToBuyRequest(s.goal.getId(), SaveToBuyVertical.TAXI, "x", new BigDecimal("5.00"), s.bea.getId(), null)));
        SaveToBuyView d = w.service.create(s.tina, w.plan(s.goal, "1000.00", "Stock", s.bea.getId(), null));
        expect("CONFLICT", () -> w.service.submit(d.id(), s.tina));       // goal not reached: no request, no approval
        assertTrue(w.reqStore.isEmpty());
        expect("FORBIDDEN", () -> w.service.submit(d.id(), s.owner));     // only the creator submits
        expect("BAD_REQUEST", () -> w.service.confirm(d.id(), s.tina));   // stokvel purchases are never "self-confirmed"
        w.savedByGoal.put(s.goal.getId(), new BigDecimal("5000.00"));
        assertEquals(SaveToBuyStatus.PENDING_APPROVAL, w.service.submit(d.id(), s.tina).status());
    }

    @Test
    void nonMembersAndOtherStokvelsCannotSeeOrTouchTheRequest() {
        Stokvel s = new Stokvel("5000.00", true, ApprovalRuleType.SINGLE);
        World w = s.w;
        w.circle("T", true, ApprovalRuleType.SINGLE);
        User otherOwner = w.join("T", "OtherOwner", CircleRole.OWNER);
        SaveToBuyView d = w.service.create(s.tina, w.plan(s.goal, "1000.00", "Stock", s.bea.getId(), null));
        expect("NOT_FOUND", () -> w.service.get(d.id(), otherOwner));
        expect("NOT_FOUND", () -> w.service.submit(d.id(), otherOwner));
        expect("NOT_FOUND", () -> w.service.cancel(d.id(), otherOwner));
        expect("NOT_FOUND", () -> w.service.create(otherOwner, w.plan(s.goal, "10.00", "x", otherOwner.getId(), null)));
        expect("FORBIDDEN", () -> w.service.listForCircle("S", otherOwner));
        assertEquals(1, w.service.listForCircle("S", s.member).size()); // members can see group requests
        // members can see but not cancel
        expect("FORBIDDEN", () -> w.service.cancel(d.id(), s.member));
    }

    @Test
    void rejectionCancelsTheSaveToBuyRequestAndTheNamedBuyerCannotCheckOut() {
        Stokvel s = new Stokvel("5000.00", true, ApprovalRuleType.SINGLE);
        World w = s.w;
        SaveToBuyView d = w.service.create(s.tina, w.plan(s.goal, "1000.00", "Stock", s.bea.getId(), null));
        String sid = w.service.submit(d.id(), s.tina).approval().stokvelRequestId();
        // with a SINGLE rule and several approvers one rejection is not final; reject by everyone eligible
        w.stokvelRequests.vote("S", sid, s.owner, VoteDecision.REJECT, "no");
        w.stokvelRequests.vote("S", sid, s.chair, VoteDecision.REJECT, "no");
        assertEquals(SaveToBuyStatus.CANCELLED, w.service.get(d.id(), s.owner).status());
        expect("CONFLICT", () -> w.service.validateForCheckout(s.bea, d.id(), SaveToBuyVertical.STOCK, null, "k"));
    }

    @Test
    void creatorCanCancelAPendingRequestWhichAlsoCancelsTheApprovalRecord() {
        Stokvel s = new Stokvel("5000.00", true, ApprovalRuleType.SINGLE);
        World w = s.w;
        SaveToBuyView d = w.service.create(s.tina, w.plan(s.goal, "1000.00", "Stock", s.bea.getId(), null));
        String sid = w.service.submit(d.id(), s.tina).approval().stokvelRequestId();
        assertEquals(SaveToBuyStatus.CANCELLED, w.service.cancel(d.id(), s.tina).status());
        assertEquals(StokvelRequestStatus.CANCELLED, ((StokvelRequest) w.reqStore.get(sid)).getStatus());
        assertTrue(w.activity.contains("SAVE_TO_BUY_CANCELLED"));
    }

    @Test
    void expiredApprovalCannotBeUsedAtCheckout() {
        Stokvel s = new Stokvel("5000.00", true, ApprovalRuleType.SINGLE);
        World w = s.w;
        SaveToBuyView d = w.service.create(s.tina, w.plan(s.goal, "1000.00", "Stock", s.bea.getId(), null));
        String sid = w.service.submit(d.id(), s.tina).approval().stokvelRequestId();
        w.stokvelRequests.vote("S", sid, s.owner, VoteDecision.APPROVE, null);
        assertEquals(SaveToBuyStatus.APPROVED, w.service.get(d.id(), s.owner).status());
        ((StokvelRequest) w.reqStore.get(sid)).setExpiresAt(Instant.now().minusSeconds(5));
        ((SaveToBuyRequest) w.s2bStore.get(d.id())).setExpiresAt(Instant.now().minusSeconds(5));
        expect("CONFLICT", () -> w.service.validateForCheckout(s.bea, d.id(), SaveToBuyVertical.STOCK, null, "k"));
        assertEquals(SaveToBuyStatus.EXPIRED, w.service.get(d.id(), s.owner).status());
    }

    @Test
    void approvalRulesOfTheStokvelDecideWhatSaveToBuyNeeds() {
        Stokvel s = new Stokvel("5000.00", true, ApprovalRuleType.MAJORITY);
        World w = s.w;
        SaveToBuyView d = w.service.create(s.tina, w.plan(s.goal, "1000.00", "Stock", s.bea.getId(), null));
        SaveToBuyView pending = w.service.submit(d.id(), s.tina);
        assertEquals(2, pending.approval().required()); // owner, chair (tina excluded as requester) -> majority of 2 = 2
        String sid = pending.approval().stokvelRequestId();
        w.stokvelRequests.vote("S", sid, s.owner, VoteDecision.APPROVE, null);
        assertEquals(SaveToBuyStatus.PENDING_APPROVAL, w.service.get(d.id(), s.owner).status());
        w.stokvelRequests.vote("S", sid, s.chair, VoteDecision.APPROVE, null);
        assertEquals(SaveToBuyStatus.APPROVED, w.service.get(d.id(), s.owner).status());
    }

    // =============================================================== financial safety / AI

    @Test
    void saveToBuyDoesNotIntroduceAWalletBalanceOrLedger() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Paths.get("src/main/resources/db/migration/V35__save_to_buy.sql")).toLowerCase()
                .replaceAll("(?m)^\\s*--.*$", "");
        assertFalse(java.util.regex.Pattern.compile("create\\s+table\\s+\\w*(wallet|balance|ledger|savings_payment|savings_transaction)\\w*").matcher(sql).find());
        // no column that could hold money on behalf of the user
        assertFalse(sql.contains("wallet"));
        assertFalse(sql.contains("alter table circles"));
        try (var files = java.nio.file.Files.walk(java.nio.file.Paths.get("src/main/java"))) {
            List<String> bad = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("(?i).*(SavingsWallet|SavingsPaymentProvider|SavingsTransaction|StokvelWallet).*"))
                    .toList();
            assertTrue(bad.isEmpty(), "unexpected custody classes: " + bad);
        }
    }

    @Test
    void checkoutIntegrationPointsAreWiredIntoTheExistingCheckoutsAndNotDuplicated() throws Exception {
        String base = "src/main/java/com/momocircle/service/";
        for (String f : new String[] {"OrderService.java", "ConsumerPaystackCheckoutService.java", "StockOrderService.java"}) {
            String src = java.nio.file.Files.readString(java.nio.file.Paths.get(base + f));
            assertTrue(src.contains("saveToBuy.validateForCheckout"), f + " must validate the request before creating the order");
            assertTrue(src.contains("saveToBuy.attachOrder"), f + " must attach the order after creating it");
        }
        String pay = java.nio.file.Files.readString(java.nio.file.Paths.get(base + "StockPaymentService.java"));
        assertTrue(pay.contains("saveToBuy.onOrderPaid"));
        String orders = java.nio.file.Files.readString(java.nio.file.Paths.get(base + "OrderService.java"));
        assertTrue(orders.contains("saveToBuy.onOrderPaid") && orders.contains("saveToBuy.onOrderFailed"));
        // there is no Save-to-Buy cart or checkout of its own
        try (var files = java.nio.file.Files.walk(java.nio.file.Paths.get("src/main/java"))) {
            List<String> dup = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("(?i)SaveToBuy(Cart|Checkout|Order|Payment).*")).toList();
            assertTrue(dup.isEmpty(), "duplicate marketplace infrastructure: " + dup);
        }
    }
}
