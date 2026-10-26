package com.momocircle.stokvel;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.StokvelDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import com.momocircle.service.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Executes the REAL StokvelRequestService against in-memory fakes (java.lang.reflect.Proxy repositories and small
 * subclasses of the collaborating services) - no Spring, no Mockito, no database. Covers the approval workflow,
 * authorisation, tenant isolation and the stokvel -> marketplace order reference.
 */
class StokvelRequestWorkflowTest {

    // ---------- in-memory repository ----------
    interface Custom { Object handle(String name, Object[] args, Map<String, Object> store); }

    @SuppressWarnings("unchecked")
    static <T> T repo(Class<T> type, Map<String, Object> store, Custom custom) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            Object[] args = a == null ? new Object[0] : a;
            switch (m.getName()) {
                case "save", "saveAndFlush" -> {
                    BaseEntity e = (BaseEntity) args[0];
                    if (e.getCreatedAt() == null) e.setCreatedAt(Instant.now());
                    store.put(e.getId(), e);
                    return e;
                }
                case "findById" -> { return Optional.ofNullable(store.get((String) args[0])); }
                case "findAll" -> { return new ArrayList<>(store.values()); }
                case "flush" -> { return null; }
                case "hashCode" -> { return System.identityHashCode(p); }
                case "equals" -> { return p == args[0]; }
                case "toString" -> { return "fake-" + type.getSimpleName(); }
                default -> {
                    if (custom != null) return custom.handle(m.getName(), args, store);
                    throw new UnsupportedOperationException(m.getName());
                }
            }
        });
    }

    // ---------- world ----------
    static final class World {
        final Map<String, Object> reqStore = new LinkedHashMap<>(), voteStore = new LinkedHashMap<>(),
                orderStore = new LinkedHashMap<>(), stockStore = new LinkedHashMap<>();
        final List<String> activity = new ArrayList<>();
        final List<String> notifications = new ArrayList<>();
        final Map<String, Circle> circles = new HashMap<>();
        final Map<String, StokvelProfile> profiles = new HashMap<>();
        final Map<String, List<CircleMembership>> members = new HashMap<>();
        BigDecimal confirmed = new BigDecimal("10000.00");
        StokvelRequestRepository requests;
        StokvelRequestVoteRepository votes;
        OrderRepository orders;
        StokvelRequestService service;

        User user(String name) {
            User u = new User();
            u.setFullName(name);
            u.setEmail(name.toLowerCase() + "@x.test");
            return u;
        }

        Circle circle(String id, ApprovalRuleType rule, Integer threshold) {
            Circle c = new Circle();
            c.setId(id);
            c.setName("Stokvel " + id);
            c.setCurrency("ZAR");
            c.setType(CircleType.STOKVEL);
            circles.put(id, c);
            StokvelProfile p = new StokvelProfile();
            p.setCircle(c);
            p.setStokvelType(StokvelType.GROCERY);
            p.setApprovalRule(rule);
            p.setApprovalThreshold(threshold);
            p.setRequestValidityDays(14);
            profiles.put(id, p);
            members.put(id, new ArrayList<>());
            return c;
        }

        User join(String circleId, String name, CircleRole role) {
            User u = user(name);
            CircleMembership m = new CircleMembership();
            m.setCircle(circles.get(circleId));
            m.setUser(u);
            m.setRole(role);
            m.setCreatedAt(Instant.now());
            members.get(circleId).add(m);
            return u;
        }

        @SuppressWarnings("unchecked")
        World() {
            requests = repo(StokvelRequestRepository.class, reqStore, (name, a, store) -> {
                List<StokvelRequest> all = store.values().stream().map(o -> (StokvelRequest) o).toList();
                switch (name) {
                    case "findByIdAndCircleId", "findForUpdate":
                        return all.stream().filter(r -> r.getId().equals(a[0]) && r.getCircle().getId().equals(a[1])).findFirst();
                    case "findByCircleIdOrderByCreatedAtDesc":
                        return all.stream().filter(r -> r.getCircle().getId().equals(a[0])).toList();
                    case "findByCircleIdAndRequesterIdAndIdempotencyKey":
                        return all.stream().filter(r -> r.getCircle().getId().equals(a[0])
                                && r.getRequester().getId().equals(a[1]) && Objects.equals(r.getIdempotencyKey(), a[2])).findFirst();
                    case "existsByCircleIdAndRequesterIdAndRequestTypeAndAmountAndPurposeAndStatus":
                        return all.stream().anyMatch(r -> r.getCircle().getId().equals(a[0]) && r.getRequester().getId().equals(a[1])
                                && r.getRequestType() == a[2] && r.getAmount().compareTo((BigDecimal) a[3]) == 0
                                && r.getPurpose().equals(a[4]) && r.getStatus() == a[5]);
                    case "sumAmountByStatuses":
                        return all.stream().filter(r -> r.getCircle().getId().equals(a[0])
                                && ((Collection<StokvelRequestStatus>) a[1]).contains(r.getStatus()))
                                .map(StokvelRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    case "findIdsPastExpiry":
                        return all.stream().filter(r -> ((Collection<StokvelRequestStatus>) a[0]).contains(r.getStatus())
                                && r.getExpiresAt().isBefore((Instant) a[1])).map(BaseEntity::getId).toList();
                    default: throw new UnsupportedOperationException(name);
                }
            });
            votes = repo(StokvelRequestVoteRepository.class, voteStore, (name, a, store) -> {
                List<StokvelRequestVote> all = store.values().stream().map(o -> (StokvelRequestVote) o).toList();
                switch (name) {
                    case "findByRequestIdOrderByCreatedAtAsc":
                        return all.stream().filter(v -> v.getRequest().getId().equals(a[0])).toList();
                    case "findByRequestIdAndVoterId":
                        return all.stream().filter(v -> v.getRequest().getId().equals(a[0]) && v.getVoter().getId().equals(a[1])).findFirst();
                    case "countByRequestIdAndDecision":
                        return all.stream().filter(v -> v.getRequest().getId().equals(a[0]) && v.getDecision() == a[1]).count();
                    default: throw new UnsupportedOperationException(name);
                }
            });
            orders = repo(OrderRepository.class, orderStore, null);
            StockOrderRepository stock = repo(StockOrderRepository.class, stockStore, null);

            final World w = this;
            StokvelSupport support = new StokvelSupport(null, null, null, null, requests) {
                @Override public Context require(String circleId, User user) {
                    CircleMembership m = w.members.getOrDefault(circleId, List.of()).stream()
                            .filter(x -> x.getUser().getId().equals(user.getId())).findFirst()
                            .orElseThrow(() -> ApiException.forbidden("not a member"));
                    return new Context(w.profiles.get(circleId), m);
                }
                @Override public List<CircleMembership> members(String circleId) { return w.members.get(circleId); }
                @Override public BigDecimal confirmedContributions(String circleId) { return w.confirmed; }
                @Override public BigDecimal committed(String circleId) {
                    return requests.sumAmountByStatuses(circleId, List.of(StokvelRequestStatus.APPROVED, StokvelRequestStatus.EXECUTED));
                }
            };
            FinancialValidationService validation = new FinancialValidationService(null, null) {
                @Override public void requireValidAmount(BigDecimal amount, String field) {
                    if (amount == null || amount.signum() <= 0) throw ApiException.badRequest(field + " must be positive");
                }
            };
            ActivityService activityService = new ActivityService(null, null) {
                @Override public void record(Circle c, User actor, ActivityType type, String msg) { activity.add(type.name()); }
            };
            NotificationService notificationService = new NotificationService(null) {
                @Override public void notify(User r, NotificationType t, String m, String ref) { notifications.add(r.getFullName() + ":" + t.name()); }
            };
            GoalService goals = new GoalService(null, null, null, null, null);
            service = new StokvelRequestService(support, requests, votes, orders, stock, goals, activityService,
                    notificationService, validation);
        }

        CreateStokvelRequestRequest purchase(String buyerId, String amount, String purpose, String key) {
            return new CreateStokvelRequestRequest(StokvelRequestType.MARKETPLACE_PURCHASE, new BigDecimal(amount),
                    purpose, buyerId, null, key);
        }
    }

    private static void expect(String status, Runnable r) {
        ApiException e = assertThrows(ApiException.class, r::run);
        assertEquals(status, e.getStatus().name());
    }

    /** Owner + treasurer + chair + admin + plain member in one stokvel with a SINGLE approver rule. */
    private static World stokvelA(ApprovalRuleType rule, Integer threshold) {
        World w = new World();
        w.circle("A", rule, threshold);
        return w;
    }

    // ---------- tests ----------

    @Test
    void treasurerCreatesRequestButCannotApproveOrSpendIt() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        User owner = w.join("A", "Owner", CircleRole.OWNER);
        User treasurer = w.join("A", "Tina", CircleRole.TREASURER);
        RequestView v = w.service.create("A", treasurer, w.purchase(treasurer.getId(), "1500.00", "Bulk maize meal", "k1"));
        assertEquals(StokvelRequestStatus.PENDING, v.status());
        assertEquals(1, v.requiredApprovals());
        expect("FORBIDDEN", () -> w.service.vote("A", v.id(), treasurer, VoteDecision.APPROVE, null));
        // pending: cannot be linked to an order, so nothing can be spent
        expect("CONFLICT", () -> w.service.linkOrder("A", v.id(), treasurer,
                new LinkOrderRequest(RelatedOrderKind.ORDER, "whatever")));
        RequestView approved = w.service.vote("A", v.id(), owner, VoteDecision.APPROVE, "ok");
        assertEquals(StokvelRequestStatus.APPROVED, approved.status());
        assertTrue(w.activity.contains("STOKVEL_REQUEST_CREATED"));
        assertTrue(w.activity.contains("STOKVEL_REQUEST_APPROVED"));
        assertTrue(w.notifications.contains("Owner:STOKVEL_APPROVAL_REQUESTED"));
        assertTrue(w.notifications.contains("Tina:STOKVEL_APPROVAL_COMPLETED"));
    }

    @Test
    void plainMembersCannotCreateRequestsOrVoteByDefault() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        User owner = w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        User member = w.join("A", "Mo", CircleRole.MEMBER);
        expect("FORBIDDEN", () -> w.service.create("A", member, w.purchase(member.getId(), "100.00", "x", null)));
        RequestView v = w.service.create("A", tina, w.purchase(member.getId(), "100.00", "snacks", null));
        expect("FORBIDDEN", () -> w.service.vote("A", v.id(), member, VoteDecision.APPROVE, null));
        assertEquals(StokvelRequestStatus.PENDING, w.service.get("A", v.id(), owner).status());
    }

    @Test
    void ownerAloneCannotCreateWhenNobodyElseCanApprove() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        User owner = w.join("A", "Owner", CircleRole.OWNER);
        User member = w.join("A", "Mo", CircleRole.MEMBER);
        expect("CONFLICT", () -> w.service.create("A", owner, w.purchase(member.getId(), "100.00", "snacks", null)));
        assertTrue(w.reqStore.isEmpty());
    }

    @Test
    void thresholdTwoOfThreeNeedsTwoDistinctApprovals() {
        World w = stokvelA(ApprovalRuleType.THRESHOLD, 2);
        User owner = w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        User chair = w.join("A", "Chair", CircleRole.CHAIRPERSON);
        User admin = w.join("A", "Adam", CircleRole.ADMIN);
        RequestView v = w.service.create("A", tina, w.purchase(tina.getId(), "900.00", "Stock", null));
        assertEquals(3, v.eligibleVoters()); // owner, chair, admin (requester excluded)
        assertEquals(StokvelRequestStatus.PENDING, w.service.vote("A", v.id(), owner, VoteDecision.APPROVE, null).status());
        // replaying the same vote is idempotent and does not count twice
        assertEquals(StokvelRequestStatus.PENDING, w.service.vote("A", v.id(), owner, VoteDecision.APPROVE, null).status());
        assertEquals(1, w.voteStore.size());
        // changing the vote is refused: votes are append-only
        expect("CONFLICT", () -> w.service.vote("A", v.id(), owner, VoteDecision.REJECT, null));
        assertEquals(StokvelRequestStatus.APPROVED, w.service.vote("A", v.id(), chair, VoteDecision.APPROVE, null).status());
        // decided: further votes refused
        expect("CONFLICT", () -> w.service.vote("A", v.id(), admin, VoteDecision.APPROVE, null));
    }

    @Test
    void requestIsRejectedOnceApprovalBecomesImpossible() {
        World w = stokvelA(ApprovalRuleType.THRESHOLD, 2);
        User owner = w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        User chair = w.join("A", "Chair", CircleRole.CHAIRPERSON);
        RequestView v = w.service.create("A", tina, w.purchase(tina.getId(), "900.00", "Stock", null));
        assertEquals(2, v.eligibleVoters());
        assertEquals(StokvelRequestStatus.REJECTED, w.service.vote("A", v.id(), owner, VoteDecision.REJECT, "no").status());
        assertTrue(w.activity.contains("STOKVEL_REQUEST_REJECTED"));
        expect("CONFLICT", () -> w.service.vote("A", v.id(), chair, VoteDecision.APPROVE, null));
    }

    @Test
    void majorityRuleSnapshotsRequirementAtCreation() {
        World w = stokvelA(ApprovalRuleType.MAJORITY, null);
        w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        w.join("A", "Chair", CircleRole.CHAIRPERSON);
        w.join("A", "Adam", CircleRole.ADMIN);
        RequestView v = w.service.create("A", tina, w.purchase(tina.getId(), "50.00", "x", null));
        assertEquals(3, v.eligibleVoters());
        assertEquals(2, v.requiredApprovals());
        // changing the rule later does not alter the stored request
        w.profiles.get("A").setApprovalRule(ApprovalRuleType.SINGLE);
        assertEquals(2, w.service.get("A", v.id(), tina).requiredApprovals());
    }

    @Test
    void duplicateRequestsAreHandled() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        RequestView a = w.service.create("A", tina, w.purchase(tina.getId(), "300.00", "Rice", "same-key"));
        RequestView b = w.service.create("A", tina, w.purchase(tina.getId(), "300.00", "Rice", "same-key"));
        assertEquals(a.id(), b.id());
        assertEquals(1, w.reqStore.size());
        // same content, different key: refused as a duplicate of a pending request
        expect("CONFLICT", () -> w.service.create("A", tina, w.purchase(tina.getId(), "300.00", "Rice", "other-key")));
    }

    @Test
    void amountIsValidatedAndCappedByTrackedContributions() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        expect("BAD_REQUEST", () -> w.service.create("A", tina, w.purchase(tina.getId(), "0.00", "x", null)));
        expect("BAD_REQUEST", () -> w.service.create("A", tina, w.purchase(tina.getId(), "-5.00", "x", null)));
        w.confirmed = new BigDecimal("1000.00");
        expect("BAD_REQUEST", () -> w.service.create("A", tina, w.purchase(tina.getId(), "1000.01", "too much", null)));
        assertDoesNotThrow(() -> w.service.create("A", tina, w.purchase(tina.getId(), "1000.00", "exactly", null)));
    }

    @Test
    void purchaseMustNameAMemberAsBuyer() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        expect("BAD_REQUEST", () -> w.service.create("A", tina, new CreateStokvelRequestRequest(
                StokvelRequestType.MARKETPLACE_PURCHASE, new BigDecimal("10.00"), "x", null, null, null)));
        expect("BAD_REQUEST", () -> w.service.create("A", tina, w.purchase("not-a-member", "10.00", "x", null)));
    }

    @Test
    void tenantIsolationAcrossStokvels() {
        World w = new World();
        w.circle("A", ApprovalRuleType.SINGLE, null);
        w.circle("B", ApprovalRuleType.SINGLE, null);
        User ownerA = w.join("A", "OwnerA", CircleRole.OWNER);
        User tinaA = w.join("A", "TinaA", CircleRole.TREASURER);
        User ownerB = w.join("B", "OwnerB", CircleRole.OWNER);
        RequestView inA = w.service.create("A", tinaA, w.purchase(tinaA.getId(), "100.00", "A stuff", null));
        // owner of B is not a member of A
        expect("FORBIDDEN", () -> w.service.get("A", inA.id(), ownerB));
        expect("FORBIDDEN", () -> w.service.list("A", ownerB));
        expect("FORBIDDEN", () -> w.service.vote("A", inA.id(), ownerB, VoteDecision.APPROVE, null));
        // ID manipulation: A's request id presented under B's circle id is "not found", never leaked
        expect("NOT_FOUND", () -> w.service.get("B", inA.id(), ownerB));
        expect("NOT_FOUND", () -> w.service.vote("B", inA.id(), ownerB, VoteDecision.APPROVE, null));
        expect("NOT_FOUND", () -> w.service.cancel("B", inA.id(), ownerB));
        assertEquals(StokvelRequestStatus.PENDING, w.service.get("A", inA.id(), ownerA).status());
    }

    @Test
    void cancelRulesAndTerminalStates() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        User owner = w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        User chair = w.join("A", "Chair", CircleRole.CHAIRPERSON);
        User member = w.join("A", "Mo", CircleRole.MEMBER);
        RequestView v = w.service.create("A", tina, w.purchase(tina.getId(), "100.00", "x", null));
        expect("FORBIDDEN", () -> w.service.cancel("A", v.id(), member));
        assertEquals(StokvelRequestStatus.CANCELLED, w.service.cancel("A", v.id(), tina).status());
        expect("CONFLICT", () -> w.service.cancel("A", v.id(), chair));
        expect("CONFLICT", () -> w.service.vote("A", v.id(), owner, VoteDecision.APPROVE, null));
        RequestView v2 = w.service.create("A", tina, w.purchase(tina.getId(), "200.00", "y", null));
        w.service.vote("A", v2.id(), owner, VoteDecision.APPROVE, null);
        // an approved-but-unspent request can still be withdrawn by the chairperson
        assertEquals(StokvelRequestStatus.CANCELLED, w.service.cancel("A", v2.id(), chair).status());
    }

    @Test
    void approvedRequestIsLinkedToTheNamedBuyersOrderAndBecomesExecuted() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        User owner = w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        User buyer = w.join("A", "Bea", CircleRole.MEMBER);
        RequestView v = w.service.create("A", tina, w.purchase(buyer.getId(), "1000.00", "Groceries", null));
        w.service.vote("A", v.id(), owner, VoteDecision.APPROVE, null);

        Order order = new Order();
        order.setBuyer(buyer);
        order.setTotal(new BigDecimal("950.00"));
        order.setCurrency("ZAR");
        w.orders.save(order);
        Order stranger = new Order();
        stranger.setBuyer(tina);
        stranger.setTotal(new BigDecimal("10.00"));
        stranger.setCurrency("ZAR");
        w.orders.save(stranger);

        // someone other than the named buyer cannot link, even the treasurer who made the request
        expect("FORBIDDEN", () -> w.service.linkOrder("A", v.id(), tina, new LinkOrderRequest(RelatedOrderKind.ORDER, stranger.getId())));
        // the buyer cannot link somebody else's order (looks like it does not exist)
        expect("NOT_FOUND", () -> w.service.linkOrder("A", v.id(), buyer, new LinkOrderRequest(RelatedOrderKind.ORDER, stranger.getId())));
        // wrong order kind for this request type
        expect("BAD_REQUEST", () -> w.service.linkOrder("A", v.id(), buyer, new LinkOrderRequest(RelatedOrderKind.STOCK_ORDER, order.getId())));

        RequestView done = w.service.linkOrder("A", v.id(), buyer, new LinkOrderRequest(RelatedOrderKind.ORDER, order.getId()));
        assertEquals(StokvelRequestStatus.EXECUTED, done.status());
        assertEquals(order.getId(), done.relatedOrderId());
        assertEquals(RelatedOrderKind.ORDER, done.relatedOrderKind());
        assertEquals(v.id(), order.getStokvelRequestId());
        assertEquals(new BigDecimal("1000.00"), done.amount()); // approved amount untouched
        assertTrue(w.activity.contains("STOKVEL_REQUEST_EXECUTED"));
        // the request cannot be spent twice
        expect("CONFLICT", () -> w.service.linkOrder("A", v.id(), buyer, new LinkOrderRequest(RelatedOrderKind.ORDER, order.getId())));
    }

    @Test
    void orderLargerThanApprovedAmountIsRefusedAndRequestStaysApproved() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        User owner = w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        User buyer = w.join("A", "Bea", CircleRole.MEMBER);
        RequestView v = w.service.create("A", tina, w.purchase(buyer.getId(), "500.00", "Groceries", null));
        w.service.vote("A", v.id(), owner, VoteDecision.APPROVE, null);
        Order big = new Order();
        big.setBuyer(buyer);
        big.setTotal(new BigDecimal("500.01"));
        big.setCurrency("ZAR");
        w.orders.save(big);
        expect("BAD_REQUEST", () -> w.service.linkOrder("A", v.id(), buyer, new LinkOrderRequest(RelatedOrderKind.ORDER, big.getId())));
        assertEquals(StokvelRequestStatus.APPROVED, w.service.get("A", v.id(), owner).status());
    }

    @Test
    void nonMarketplaceRequestsAreRecordedByAnAuthorisedRecorderOnlyAfterApproval() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        User owner = w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        User chair = w.join("A", "Chair", CircleRole.CHAIRPERSON);
        RequestView v = w.service.create("A", chair, new CreateStokvelRequestRequest(
                StokvelRequestType.EMERGENCY_EXPENSE, new BigDecimal("400.00"), "Funeral costs", null, null, null));
        expect("CONFLICT", () -> w.service.recordExecution("A", v.id(), tina, new RecordExecutionRequest("EFT-1")));
        w.service.vote("A", v.id(), owner, VoteDecision.APPROVE, null);
        expect("FORBIDDEN", () -> w.service.recordExecution("A", v.id(), chair, new RecordExecutionRequest("EFT-1")));
        RequestView done = w.service.recordExecution("A", v.id(), tina, new RecordExecutionRequest("EFT-1"));
        assertEquals(StokvelRequestStatus.EXECUTED, done.status());
        assertEquals("EFT-1", done.externalReference());
        assertEquals(RelatedOrderKind.EXTERNAL, done.relatedOrderKind());
    }

    @Test
    void committedAmountsReduceWhatCanBeRequested() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        User owner = w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        w.confirmed = new BigDecimal("1000.00");
        RequestView v = w.service.create("A", tina, w.purchase(tina.getId(), "800.00", "first", null));
        w.service.vote("A", v.id(), owner, VoteDecision.APPROVE, null); // now committed
        expect("BAD_REQUEST", () -> w.service.create("A", tina, w.purchase(tina.getId(), "300.00", "second", null)));
    }

    @Test
    void staleRequestsExpireAndCannotBeVotedOrExecuted() {
        World w = stokvelA(ApprovalRuleType.SINGLE, null);
        User owner = w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        RequestView v = w.service.create("A", tina, w.purchase(tina.getId(), "100.00", "x", null));
        StokvelRequest r = (StokvelRequest) w.reqStore.get(v.id());
        r.setExpiresAt(Instant.now().minusSeconds(60));
        // visible as expired straight away, even before the scheduler persists it
        assertEquals(StokvelRequestStatus.EXPIRED, w.service.get("A", v.id(), owner).status());
        expect("CONFLICT", () -> w.service.vote("A", v.id(), owner, VoteDecision.APPROVE, null));
        assertEquals(1, w.service.expireDue());
        assertEquals(StokvelRequestStatus.EXPIRED, r.getStatus());
        assertEquals(0, w.service.expireDue());
        assertTrue(w.activity.contains("STOKVEL_REQUEST_EXPIRED"));
    }

    @Test
    void membersCanVoteWhenTheStokvelOpensVoting() {
        World w = stokvelA(ApprovalRuleType.MAJORITY, null);
        w.profiles.get("A").setMembersCanVote(true);
        w.join("A", "Owner", CircleRole.OWNER);
        User tina = w.join("A", "Tina", CircleRole.TREASURER);
        User m1 = w.join("A", "M1", CircleRole.MEMBER);
        User m2 = w.join("A", "M2", CircleRole.MEMBER);
        RequestView v = w.service.create("A", tina, w.purchase(tina.getId(), "100.00", "x", null));
        assertEquals(3, v.eligibleVoters());
        w.service.vote("A", v.id(), m1, VoteDecision.APPROVE, null);
        assertEquals(StokvelRequestStatus.APPROVED, w.service.vote("A", v.id(), m2, VoteDecision.APPROVE, null).status());
    }
}
