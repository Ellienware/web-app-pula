package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.StokvelDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Executes the REAL StokvelRotationService and StokvelMembershipGuard against in-memory fakes (Proxy repositories,
 * no Spring/Mockito/database): join-order default, founder override, lock on first payout, deterministic cycles.
 */
class StokvelRotationServiceTest {

    interface Custom { Object handle(String name, Object[] a, Map<String, Object> store); }

    @SuppressWarnings("unchecked")
    static <T> T repo(Class<T> type, Map<String, Object> store, Custom custom) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            Object[] args = a == null ? new Object[0] : a;
            switch (m.getName()) {
                case "save" -> { BaseEntity e = (BaseEntity) args[0]; if (e.getCreatedAt() == null) e.setCreatedAt(Instant.now()); store.put(e.getId(), e); return e; }
                case "saveAll" -> { List<Object> out = new ArrayList<>(); for (Object o : (Iterable<Object>) args[0]) { BaseEntity e = (BaseEntity) o; if (e.getCreatedAt() == null) e.setCreatedAt(Instant.now()); store.put(e.getId(), e); out.add(e); } return out; }
                case "deleteAll" -> { for (Object o : (Iterable<Object>) args[0]) store.remove(((BaseEntity) o).getId()); return null; }
                case "findAll" -> { return new ArrayList<>(store.values()); }
                case "findById" -> { return Optional.ofNullable(store.get((String) args[0])); }
                case "hashCode" -> { return System.identityHashCode(p); }
                case "equals" -> { return p == args[0]; }
                default -> { return custom.handle(m.getName(), args, store); }
            }
        });
    }

    static final class World {
        final Map<String, Object> slotStore = new LinkedHashMap<>(), cycleStore = new LinkedHashMap<>(), profileStore = new LinkedHashMap<>();
        final Map<Integer, BigDecimal> collectedByPeriod = new HashMap<>();
        final List<String> activity = new ArrayList<>(), notes = new ArrayList<>();
        final Circle circle = new Circle();
        final StokvelProfile profile = new StokvelProfile();
        final List<CircleMembership> members = new ArrayList<>();
        long clock = 1_000;
        StokvelRotationService service;
        StokvelRotationSlotRepository slots;
        StokvelCycleRepository cycles;

        @SuppressWarnings("unchecked")
        World() {
            circle.setId("C1");
            circle.setName("Rotating");
            circle.setCurrency("ZAR");
            circle.setType(CircleType.STOKVEL);
            profile.setCircle(circle);
            profile.setStokvelType(StokvelType.ROTATIONAL);
            profile.setContributionAmount(new BigDecimal("500.00"));
            profile.setContributionFrequency(RecurringFrequency.MONTHLY);
            profile.setStartDate(LocalDate.now().minusMonths(6));
            profile.setApprovalRule(ApprovalRuleType.SINGLE);
            slots = repo(StokvelRotationSlotRepository.class, slotStore, (n, a, s) -> {
                List<StokvelRotationSlot> all = s.values().stream().map(o -> (StokvelRotationSlot) o).toList();
                return switch (n) {
                    case "findByCircleIdOrderByPositionAsc" -> all.stream().filter(x -> x.getCircle().getId().equals(a[0]))
                            .sorted(Comparator.comparingInt(StokvelRotationSlot::getPosition)).toList();
                    case "existsByCircleIdAndUserId" -> all.stream().anyMatch(x -> x.getCircle().getId().equals(a[0]) && x.getUser().getId().equals(a[1]));
                    default -> throw new UnsupportedOperationException(n);
                };
            });
            cycles = repo(StokvelCycleRepository.class, cycleStore, (n, a, s) -> {
                List<StokvelCycle> all = s.values().stream().map(o -> (StokvelCycle) o).toList();
                return switch (n) {
                    case "countByCircleId" -> (long) all.size();
                    case "findByCircleIdOrderByCycleNumberAsc" -> all.stream().sorted(Comparator.comparingInt(StokvelCycle::getCycleNumber)).toList();
                    case "existsByCircleIdAndBeneficiaryId" -> all.stream().anyMatch(c -> c.getBeneficiary().getId().equals(a[1]));
                    default -> throw new UnsupportedOperationException(n);
                };
            });
            StokvelProfileRepository profiles = repo(StokvelProfileRepository.class, profileStore, (n, a, s) -> {
                if (n.equals("findByCircleId") || n.equals("findByCircleIdForUpdate")) return Optional.of(profile);
                throw new UnsupportedOperationException(n);
            });
            StokvelContributionLinkRepository links = repo(StokvelContributionLinkRepository.class, new HashMap<>(), (n, a, s) -> {
                if (n.equals("confirmedForPeriod")) return collectedByPeriod.getOrDefault((Integer) a[1], BigDecimal.ZERO);
                if (n.equals("confirmedByMemberAndPeriod")) return List.of();
                throw new UnsupportedOperationException(n);
            });
            final World w = this;
            StokvelSupport support = new StokvelSupport(null, null, null, null, null) {
                @Override public Context requireForUpdate(String circleId, User user) { return require(circleId, user); }
                @Override public Context require(String circleId, User user) {
                    CircleMembership m = w.members.stream().filter(x -> x.getUser().getId().equals(user.getId())).findFirst()
                            .orElseThrow(() -> ApiException.forbidden("not a member"));
                    return new Context(w.profile, m);
                }
                @Override public List<CircleMembership> members(String circleId) { return w.members; }
                @Override public LocalDate today() { return LocalDate.now(); }
            };
            ActivityService act = new ActivityService(null, null) {
                @Override public void record(Circle c, User u, ActivityType t, String m) { activity.add(t.name()); }
            };
            NotificationService notif = new NotificationService(null) {
                @Override public void notify(User r, NotificationType t, String m, String ref) { notes.add(r.getFullName() + ":" + t.name()); }
            };
            StokvelContributionService contributions = new StokvelContributionService(support, null, null, links, null, null);
            service = new StokvelRotationService(support, profiles, slots, cycles, links, contributions, act, notif);
        }

        User join(String name, CircleRole role) {
            User u = new User();
            u.setFullName(name);
            u.setEmail(name + "@x.test");
            CircleMembership m = new CircleMembership();
            m.setCircle(circle);
            m.setUser(u);
            m.setRole(role);
            m.setCreatedAt(Instant.ofEpochSecond(clock += 100)); // strictly increasing join times
            members.add(m);
            return u;
        }

        List<String> order() {
            return slots.findByCircleIdOrderByPositionAsc("C1").stream().map(s -> s.getUser().getFullName()).toList();
        }
    }

    private static void expect(String status, Runnable r) {
        assertEquals(status, assertThrows(ApiException.class, r::run).getStatus().name());
    }

    private static List<String> names(RotationView v) {
        return v.order().stream().map(RotationSlotView::fullName).toList();
    }

    @Test
    void defaultOrderIsJoinOrderAndIsStable() {
        World w = new World();
        User owner = w.join("Owner", CircleRole.OWNER);
        w.join("Bea", CircleRole.MEMBER);
        w.join("Cy", CircleRole.MEMBER);
        RotationView v = w.service.get("C1", owner);
        assertEquals(List.of("Owner", "Bea", "Cy"), names(v));
        assertEquals("Owner", v.currentBeneficiaryName());
        assertEquals("Bea", v.upcomingBeneficiaryName());
        assertFalse(v.locked());
        assertEquals(names(v), names(w.service.get("C1", owner))); // deterministic on re-read
    }

    @Test
    void lateJoinerIsAppendedAtTheEnd() {
        World w = new World();
        User owner = w.join("Owner", CircleRole.OWNER);
        w.join("Bea", CircleRole.MEMBER);
        w.service.get("C1", owner);
        w.join("Dee", CircleRole.MEMBER);
        assertEquals(List.of("Owner", "Bea", "Dee"), names(w.service.get("C1", owner)));
    }

    @Test
    void onlyTheFounderCanOverrideAndOnlyWithExactlyTheCurrentMembers() {
        World w = new World();
        User owner = w.join("Owner", CircleRole.OWNER);
        User admin = w.join("Adam", CircleRole.ADMIN);
        User tina = w.join("Tina", CircleRole.TREASURER);
        User bea = w.join("Bea", CircleRole.MEMBER);
        w.service.get("C1", owner);
        List<String> wanted = List.of(bea.getId(), tina.getId(), owner.getId(), admin.getId());
        expect("FORBIDDEN", () -> w.service.setOrder("C1", admin, new SetRotationOrderRequest(wanted)));
        expect("FORBIDDEN", () -> w.service.setOrder("C1", tina, new SetRotationOrderRequest(wanted)));
        expect("FORBIDDEN", () -> w.service.setOrder("C1", bea, new SetRotationOrderRequest(wanted)));
        expect("BAD_REQUEST", () -> w.service.setOrder("C1", owner, new SetRotationOrderRequest(List.of(bea.getId(), owner.getId()))));
        expect("BAD_REQUEST", () -> w.service.setOrder("C1", owner, new SetRotationOrderRequest(List.of(bea.getId(), bea.getId(), tina.getId(), owner.getId()))));
        expect("BAD_REQUEST", () -> w.service.setOrder("C1", owner, new SetRotationOrderRequest(List.of(bea.getId(), tina.getId(), owner.getId(), "stranger"))));
        RotationView v = w.service.setOrder("C1", owner, new SetRotationOrderRequest(wanted));
        assertEquals(List.of("Bea", "Tina", "Owner", "Adam"), names(v));
        assertTrue(w.activity.contains("ROTATION_UPDATED"));
        assertEquals(List.of("Bea", "Tina", "Owner", "Adam"), w.order());
    }

    @Test
    void nonMembersCannotSeeOrChangeTheRotation() {
        World w = new World();
        User owner = w.join("Owner", CircleRole.OWNER);
        User outsider = new User();
        outsider.setFullName("Out");
        expect("FORBIDDEN", () -> w.service.get("C1", outsider));
        expect("FORBIDDEN", () -> w.service.setOrder("C1", outsider, new SetRotationOrderRequest(List.of(owner.getId()))));
        expect("FORBIDDEN", () -> w.service.recordPayout("C1", outsider, new RecordPayoutRequest(1, null)));
    }

    @Test
    void firstPayoutGoesToFirstInOrderIsComputedByTheServerAndLocksTheRotation() {
        World w = new World();
        User owner = w.join("Owner", CircleRole.OWNER);
        User tina = w.join("Tina", CircleRole.TREASURER);
        User bea = w.join("Bea", CircleRole.MEMBER);
        w.collectedByPeriod.put(0, new BigDecimal("1500.00"));

        // The beneficiary (Owner, first in order) cannot record their own payout.
        expect("FORBIDDEN", () -> w.service.recordPayout("C1", owner, new RecordPayoutRequest(1, null)));
        // Plain members cannot record payouts at all.
        expect("FORBIDDEN", () -> w.service.recordPayout("C1", bea, new RecordPayoutRequest(1, null)));
        // Double-submit / stale client: wrong cycle number.
        expect("CONFLICT", () -> w.service.recordPayout("C1", tina, new RecordPayoutRequest(2, null)));

        RotationView v = w.service.recordPayout("C1", tina, new RecordPayoutRequest(1, "EFT-7"));
        assertTrue(v.locked());
        assertEquals(1, v.completedCycles());
        assertEquals(2, v.currentCycleNumber());
        assertEquals("Tina", v.currentBeneficiaryName());
        CycleView c = v.history().get(0);
        assertEquals("Owner", c.beneficiaryName());
        assertEquals(new BigDecimal("1500.00"), c.collectedAmount());   // from confirmed payments, not the client
        assertEquals(new BigDecimal("1500.00"), c.expectedAmount());    // 500 x 3 members
        assertEquals("Tina", c.recordedByName());
        assertEquals("EFT-7", c.reference());
        assertTrue(w.activity.contains("ROTATION_LOCKED"));
        assertTrue(w.activity.contains("PAYOUT_RECORDED"));
        assertTrue(w.notes.contains("Owner:SYSTEM"));
        assertTrue(w.notes.contains("Tina:STOKVEL_PAYOUT_UPCOMING"));

        // Recording the same cycle again cannot create a duplicate.
        expect("CONFLICT", () -> w.service.recordPayout("C1", tina, new RecordPayoutRequest(1, null)));
        assertEquals(1, w.cycleStore.size());
        // The order is now locked for the founder too.
        expect("CONFLICT", () -> w.service.setOrder("C1", owner,
                new SetRotationOrderRequest(List.of(bea.getId(), tina.getId(), owner.getId()))));
        assertEquals(List.of("Owner", "Tina", "Bea"), w.order());
    }

    @Test
    void payoutNeedsConfirmedContributionsForThatCycle() {
        World w = new World();
        w.join("Owner", CircleRole.OWNER);
        User tina = w.join("Tina", CircleRole.TREASURER);
        expect("BAD_REQUEST", () -> w.service.recordPayout("C1", tina, new RecordPayoutRequest(1, null)));
        assertTrue(w.cycleStore.isEmpty());
        assertFalse(w.profile.isRotationLocked());
    }

    @Test
    void rotationAdvancesEachCycleThenRepeats() {
        World w = new World();
        User owner = w.join("Owner", CircleRole.OWNER);
        User tina = w.join("Tina", CircleRole.TREASURER);
        w.join("Bea", CircleRole.MEMBER);
        for (int p = 0; p < 4; p++) w.collectedByPeriod.put(p, new BigDecimal("1500.00"));
        List<String> got = new ArrayList<>();
        // Owner (first) is paid by Tina; Tina is paid by Owner; Bea by Owner; round two starts with Owner again (Tina records).
        got.add(w.service.recordPayout("C1", tina, new RecordPayoutRequest(1, null)).history().get(0).beneficiaryName());
        got.add(w.service.recordPayout("C1", owner, new RecordPayoutRequest(2, null)).history().get(1).beneficiaryName());
        got.add(w.service.recordPayout("C1", owner, new RecordPayoutRequest(3, null)).history().get(2).beneficiaryName());
        RotationView v = w.service.recordPayout("C1", tina, new RecordPayoutRequest(4, null));
        got.add(v.history().get(3).beneficiaryName());
        assertEquals(List.of("Owner", "Tina", "Bea", "Owner"), got);
        assertEquals(4, v.completedCycles());
        assertEquals("Tina", v.currentBeneficiaryName());
    }

    @Test
    void nonRotationalStokvelsHaveNoRotation() {
        World w = new World();
        User owner = w.join("Owner", CircleRole.OWNER);
        w.profile.setStokvelType(StokvelType.GROCERY);
        expect("BAD_REQUEST", () -> w.service.get("C1", owner));
    }

    // ---- membership guard ----

    @Test
    void guardBlocksTreasurerOutsideStokvelsAndOwnerInsideThem() {
        Map<String, Object> none = new HashMap<>();
        boolean[] stokvel = {false};
        StokvelProfileRepository profiles = repo(StokvelProfileRepository.class, none, (n, a, s) ->
                stokvel[0] ? Optional.of(new StokvelProfile()) : Optional.empty());
        StokvelMembershipGuard guard = new StokvelMembershipGuard(profiles, null, null);
        expect("BAD_REQUEST", () -> guard.validateRole("c", CircleRole.TREASURER));
        expect("BAD_REQUEST", () -> guard.validateRole("c", CircleRole.CHAIRPERSON));
        assertDoesNotThrow(() -> guard.validateRole("c", CircleRole.MEMBER));
        assertDoesNotThrow(() -> guard.validateRole("c", CircleRole.ADMIN));
        stokvel[0] = true;
        assertDoesNotThrow(() -> guard.validateRole("c", CircleRole.TREASURER));
        assertDoesNotThrow(() -> guard.validateRole("c", CircleRole.CHAIRPERSON));
        expect("BAD_REQUEST", () -> guard.validateRole("c", CircleRole.OWNER));
    }

    @Test
    void lockedRotationKeepsMembersWhoAreStillWaitingForTheirPayout() {
        World w = new World();
        User owner = w.join("Owner", CircleRole.OWNER);
        User tina = w.join("Tina", CircleRole.TREASURER);
        User bea = w.join("Bea", CircleRole.MEMBER);
        w.collectedByPeriod.put(0, new BigDecimal("1500.00"));
        StokvelProfileRepository profiles = repo(StokvelProfileRepository.class, new HashMap<>(), (n, a, s) -> Optional.of(w.profile));
        StokvelMembershipGuard guard = new StokvelMembershipGuard(profiles, w.slots, w.cycles);

        w.service.get("C1", owner);
        assertDoesNotThrow(() -> guard.beforeMemberLeaves("C1", bea.getId())); // unlocked: free to leave
        w.service.recordPayout("C1", tina, new RecordPayoutRequest(1, null));   // locks; Owner paid
        expect("CONFLICT", () -> guard.beforeMemberLeaves("C1", bea.getId())); // Bea still waiting
        assertDoesNotThrow(() -> guard.beforeMemberLeaves("C1", owner.getId())); // Owner already received theirs
    }
}
