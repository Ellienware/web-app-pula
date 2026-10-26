package com.momocircle.future;

import com.momocircle.domain.User;
import com.momocircle.exception.ApiException;
import com.momocircle.future.FutureDtos.*;
import com.momocircle.repository.*;
import com.momocircle.service.CircleService;
import com.momocircle.service.GoalService;
import com.momocircle.config.CurrencyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FutureServiceTest {
    @Mock AllocationPlanRepository plans; @Mock AllocationPlanLineRepository planLines;
    @Mock AllocationCalculationRepository calcs; @Mock AllocationCalculationLineRepository calcLines;
    @Mock ProjectionScenarioRepository scenarios; @Mock CircleGoalRepository goals;
    @Mock CircleMembershipRepository memberships; @Mock ContributionRepository contributions;
    @Mock BusinessRepository businesses; @Mock BusinessIncomeEntryRepository businessIncome; @Mock CircleService circleService; @Mock GoalService goalService; @Mock BusinessPlanItemRepository planItems;
    @Mock RestaurantSettlementRepository settlements; @Mock StokvelProfileRepository stokvelProfiles; @Mock CurrencyProperties currency;
    FutureService service; User alice;

    @BeforeEach void setUp() {
        service = new FutureService(plans, planLines, calcs, calcLines, scenarios, goals, memberships, contributions, businesses, businessIncome, circleService, goalService, planItems, settlements, stokvelProfiles, currency);
        alice = new User(); alice.setId("alice");
        when(calcs.save(any())).thenAnswer(i -> i.getArguments()[0]);
        when(calcLines.save(any())).thenAnswer(i -> i.getArguments()[0]);
    }
    private AllocationPlan plan(PlanStatus s) {
        AllocationPlan p = new AllocationPlan(); p.setId("p1"); p.setOwnerUserId("alice"); p.setName("n"); p.setStatus(s);
        when(plans.findByIdAndOwnerUserId("p1", "alice")).thenReturn(Optional.of(p));
        AllocationPlanLine l = new AllocationPlanLine(); l.setPlanId("p1"); l.setLabel("EF");
        l.setLineType(AllocationCalculator.LineType.PERCENT); l.setPercentBp(1000);
        when(planLines.findByPlanIdOrderByPositionAsc("p1")).thenReturn(List.of(l));
        return p;
    }
    private CalculateRequest req(String key, String amount) {
        return new CalculateRequest(key, IncomeSource.USER_ENTERED, new BigDecimal(amount), null, null);
    }

    @Test void otherUsersPlanIsNotFound() {
        when(plans.findByIdAndOwnerUserId("p1", "mallory")).thenReturn(Optional.empty());
        User mallory = new User(); mallory.setId("mallory");
        assertEquals(404, assertThrows(ApiException.class, () -> service.getPlan(mallory, "p1")).getStatus().value());
    }
    @Test void calculationIsPersistedAsSimulationOnly() {
        plan(PlanStatus.ACTIVE);
        CalculationResponse r = service.calculate(alice, "p1", req("k1", "10000.00"));
        assertTrue(r.valid()); assertTrue(r.persisted()); assertTrue(r.simulated());
        assertEquals(0, new BigDecimal("1000.00").compareTo(r.totalAllocated()));
        assertEquals(FutureDtos.PLANNING_NOTICE, r.notice());
    }
    @Test void repeatedKeyReplaysAndDoesNotSaveAgain() {
        plan(PlanStatus.ACTIVE);
        service.calculate(alice, "p1", req("k1", "10000.00"));
        ArgumentCaptorHolder.capture(calcs);
        AllocationCalculation stored = ArgumentCaptorHolder.last;
        when(calcs.findByPlanIdAndIdempotencyKey("p1", "k1")).thenReturn(Optional.of(stored));
        clearInvocations(calcs);
        service.calculate(alice, "p1", req("k1", "10000.00"));
        verify(calcs, never()).save(any());
    }
    @Test void invalidScenarioIsReturnedButNeverPersisted() {
        AllocationPlan p = plan(PlanStatus.ACTIVE);
        AllocationPlanLine big = new AllocationPlanLine(); big.setPlanId("p1"); big.setLabel("big");
        big.setLineType(AllocationCalculator.LineType.FIXED); big.setFixedMinor(5_000_000);
        when(planLines.findByPlanIdOrderByPositionAsc("p1")).thenReturn(List.of(big));
        CalculationResponse r = service.calculate(alice, "p1", req("k2", "100.00"));
        assertFalse(r.valid()); assertFalse(r.persisted()); assertFalse(r.warnings().isEmpty());
        verify(calcs, never()).save(any());
    }
    @Test void pausedAndArchivedPlansCannotBeCalculated() {
        plan(PlanStatus.PAUSED);
        assertEquals(409, assertThrows(ApiException.class, () -> service.calculate(alice, "p1", req("k", "1.00"))).getStatus().value());
    }
    @Test void archivedPlanCannotBeReactivated() {
        plan(PlanStatus.ARCHIVED);
        assertEquals(409, assertThrows(ApiException.class, () -> service.setStatus(alice, "p1", PlanStatus.ACTIVE)).getStatus().value());
    }
    @Test void businessPlanRequiresOwnership() {
        when(businesses.findByOwnerId("alice")).thenReturn(Optional.empty());
        var line = new PlanLineRequest(null, "x", AllocationCalculator.LineType.PERCENT, null, new BigDecimal("10"));
        var create = new CreatePlanRequest("n", "someone-elses-business", null, null, null, List.of(line));
        assertEquals(403, assertThrows(ApiException.class, () -> service.createPlan(alice, create)).getStatus().value());
    }
    @Test void moneyHelpersRejectBadAmounts() {
        assertThrows(ApiException.class, () -> FutureService.toMinor(new BigDecimal("1.234"), "x"));
        assertThrows(ApiException.class, () -> FutureService.toMinor(new BigDecimal("-1"), "x"));
        assertEquals(12_345, FutureService.toMinor(new BigDecimal("123.45"), "x"));
    }

    @Test void goalMetaForNonMemberLooksLikeNotFound() {
        var circle = new com.momocircle.domain.Circle(); circle.setId("c1");
        var g = new com.momocircle.domain.CircleGoal(); g.setId("g1"); g.setCircle(circle);
        when(goals.findById("g1")).thenReturn(Optional.of(g));
        when(circleService.requireMembership("c1", alice)).thenThrow(ApiException.forbidden("Not a member"));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.updateGoalMeta(alice, "g1", new GoalMetaRequest("EDUCATION", "HIGH"))).getStatus().value());
        verify(goals, never()).save(any());
    }
    @Test void goalMetaRejectsUnknownCategory() {
        var circle = new com.momocircle.domain.Circle(); circle.setId("c1");
        var g = new com.momocircle.domain.CircleGoal(); g.setId("g1"); g.setCircle(circle);
        when(goals.findById("g1")).thenReturn(Optional.of(g));
        assertEquals(400, assertThrows(ApiException.class,
                () -> service.updateGoalMeta(alice, "g1", new GoalMetaRequest("CRYPTO_MOONSHOT", null))).getStatus().value());
    }
    @Test void educationIsAvailableAndLessonNotFoundIs404() {
        assertFalse(service.education().lessons().isEmpty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.lesson("nope")).getStatus().value());
    }


    @Test void itemsRequireABusinessPlan() {
        plan(PlanStatus.DRAFT); // personal plan (no business id)
        var req = new PlanItemRequest(PlanItemKind.RENT_UTILITIES, "Rent", new BigDecimal("5000"));
        assertEquals(400, assertThrows(ApiException.class, () -> service.addItem(alice, "p1", req)).getStatus().value());
        verify(planItems, never()).save(any());
    }
    @Test void itemsCannotChangeAnArchivedPlan() {
        AllocationPlan p = plan(PlanStatus.ARCHIVED); p.setBusinessId("b1");
        var req = new PlanItemRequest(PlanItemKind.TAXES, "VAT", new BigDecimal("100"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.addItem(alice, "p1", req)).getStatus().value());
    }
    @Test void businessItemsReduceWhatIsAvailableToAllocate() {
        AllocationPlan p = plan(PlanStatus.ACTIVE); p.setBusinessId("b1");
        BusinessPlanItem rent = new BusinessPlanItem(); rent.setPlanId("p1"); rent.setKind(PlanItemKind.RENT_UTILITIES); rent.setAmountMinor(300_000);
        BusinessPlanItem cushion = new BusinessPlanItem(); cushion.setPlanId("p1"); cushion.setKind(PlanItemKind.EMERGENCY_RESERVE); cushion.setAmountMinor(200_000);
        when(planItems.findByPlanIdOrderByCreatedAtAsc("p1")).thenReturn(List.of(rent, cushion));
        CalculationResponse r = service.calculate(alice, "p1", req("kb", "10000.00"));
        assertEquals(0, new BigDecimal("3000.00").compareTo(r.commitments()));
        assertEquals(0, new BigDecimal("2000.00").compareTo(r.reserves()));
        assertEquals(0, new BigDecimal("5000.00").compareTo(r.allocatable()));
    }
    @Test void settlementIncomeUsesOnlyPaidSettlementsAndNothingElse() {
        AllocationPlan p = plan(PlanStatus.ACTIVE); p.setBusinessId("b1");
        when(settlements.futurePaidNet(any(), any(), any())).thenReturn(new BigDecimal("900.00"));
        when(settlements.futurePaidGross(any(), any(), any())).thenReturn(new BigDecimal("1000.00"));
        when(settlements.futurePaidRefunded(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(settlements.futurePendingNet(any(), any(), any())).thenReturn(new BigDecimal("250.00"));
        when(settlements.futurePaidCount(any(), any(), any())).thenReturn(3L);
        when(settlements.futurePendingCount(any(), any(), any())).thenReturn(2L);
        var request = new CalculateRequest("ks", IncomeSource.MERCHANT_SIMULATED_SETTLEMENTS, null,
                java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 31));
        CalculationResponse r = service.calculate(alice, "p1", request);
        assertEquals(0, new BigDecimal("900.00").compareTo(r.incomeBasis()));       // pending R250 NOT included
        assertTrue(r.incomeNotes().stream().anyMatch(n -> n.contains("pending")));
        assertTrue(r.incomeSourceNote().toLowerCase().contains("simulated"));
        verifyNoInteractions(businessIncome);                                        // manual entries not mixed in
    }
    @Test void settlementIncomeNeedsALinkedBusiness() {
        plan(PlanStatus.ACTIVE);
        var request = new CalculateRequest("kx", IncomeSource.MERCHANT_SIMULATED_SETTLEMENTS, null,
                java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 31));
        assertEquals(400, assertThrows(ApiException.class, () -> service.calculate(alice, "p1", request)).getStatus().value());
    }
    @Test void settlementPreviewRequiresOwnedBusiness() {
        when(businesses.findByOwnerId("alice")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.settlementPreview(alice, "b-other",
                java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 31))).getStatus().value());
        verifyNoInteractions(settlements);
    }
    @Test void pauseOnAGoalYouCannotSeeLooksLikeNotFound() {
        var circle = new com.momocircle.domain.Circle(); circle.setId("c9");
        var g = new com.momocircle.domain.CircleGoal(); g.setId("g9"); g.setCircle(circle);
        when(goals.findById("g9")).thenReturn(Optional.of(g));
        when(memberships.findByCircleIdAndUserId("c9", "alice")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.pauseGoal(alice, "g9")).getStatus().value());
        verifyNoInteractions(goalService);
    }

    /** Structural guard: planning code must never depend on anything that can move money. */
    @Test void futureServiceHasNoMoneyMovingDependencies() {
        Constructor<?> c = FutureService.class.getConstructors()[0];
        for (Class<?> t : c.getParameterTypes()) {
            String n = t.getName().toLowerCase();
            assertFalse(n.contains("wallet") || n.contains("ledger") || n.contains("payment") || n.contains("payout")
                    || (n.contains("settlement") && !n.endsWith("restaurantsettlementrepository")), "Forbidden dependency: " + t.getName());
        }
        assertFalse(java.util.Arrays.stream(c.getParameterTypes()).anyMatch(t -> t.getSimpleName().equals("ContributionService")));
    }

    /** Tiny helper to read the last saved calculation without extra imports. */
    static final class ArgumentCaptorHolder {
        static AllocationCalculation last;
        static void capture(AllocationCalculationRepository repo) {
            var cap = org.mockito.ArgumentCaptor.forClass(AllocationCalculation.class);
            verify(repo).save(cap.capture()); last = cap.getValue();
        }
    }
}
