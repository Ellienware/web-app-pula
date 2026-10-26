package com.momocircle.savings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.AssistantProposal;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ProposalStatus;
import com.momocircle.dto.AssistantDtos.ActionType;
import com.momocircle.dto.AssistantDtos.AssistantRequest;
import com.momocircle.dto.AssistantDtos.AssistantResponse;
import com.momocircle.dto.AssistantDtos.ExecuteActionRequest;
import com.momocircle.repository.AssistantProposalRepository;
import com.momocircle.service.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE 2A: the AI safety boundary for Savings. The assistant (heuristic mode, no model configured) may only
 * PROPOSE. Nothing mutates until the user explicitly executes a stored proposal. Complements
 * AssistantServiceProposalTest (which covers who may execute, expiry, duplicates and stored parameters).
 */
class SavingsAiBoundaryCharacterisationTest {

    private final AssistantProposalRepository proposals = mock(AssistantProposalRepository.class);
    private final CircleService circleService = mock(CircleService.class);
    private final ContributionService contributionService = mock(ContributionService.class);
    private final GoalService goalService = mock(GoalService.class);
    private final ExpenseService expenseService = mock(ExpenseService.class);
    private final RecurringContributionService recurringService = mock(RecurringContributionService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final List<AssistantProposal> stored = new ArrayList<>();
    private AssistantService service;
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId("u1");
        user.setFullName("Thandi");
        when(proposals.save(any(AssistantProposal.class))).thenAnswer(i -> {
            AssistantProposal p = i.getArgument(0);
            if (p.getId() == null) p.setId("prop-" + (stored.size() + 1));
            stored.add(p);
            return p;
        });
        when(proposals.saveAndFlush(any(AssistantProposal.class))).thenAnswer(i -> i.getArgument(0));
        when(proposals.findById(anyString())).thenAnswer(i -> stored.stream().filter(p -> p.getId().equals(i.getArgument(0))).findFirst());
        when(contributionService.contribute(anyString(), any(), any())).thenReturn(new com.momocircle.dto.ContributionDtos.ContributionResponse(
                "k1", "circle-1", "u1", "Thandi", new BigDecimal("500"), "ZAR", com.momocircle.domain.enums.PaymentStatus.SUCCESSFUL,
                "ref", null, null, java.time.Instant.now()));
        CurrencyProperties currency = mock(CurrencyProperties.class);
        when(currency.getDefaultCurrency()).thenReturn("ZAR");
        service = new AssistantService("", "", "openai/gpt-4o-mini", circleService, contributionService, goalService, expenseService,
                recurringService, mock(CartService.class), mock(BusinessService.class), mock(ProductService.class), currency,
                proposals, new AssistantProposalConcurrencyService(proposals), audit, mock(com.momocircle.service.momoride.MoMoRideService.class),
                mock(BudgetService.class), mock(AffordabilityService.class), mock(FinancialProfileService.class),
                mock(ScamShieldService.class), mock(CreditEducationService.class), 10);
    }

    private void assertNothingMutated() {
        verifyNoMoreInteractions(contributionService, goalService, expenseService, recurringService);
        verify(circleService, never()).create(any(), any());
        verify(circleService, never()).addMember(anyString(), any(), any());
    }

    @Test
    void everySavingsRequestProducesAPendingProposalAndMutatesNothing() {
        String[] messages = {
                "contribute 500 to my circle",
                "create a new circle called Street Savers",
                "create a goal to save up 2000 for stock",
                "we need an expense split of 300 for groceries",
                "set up a recurring monthly 200 contribution",
        };
        int proposed = 0;
        for (String m : messages) {
            AssistantResponse r = service.interpret(user, new AssistantRequest(m, null, "circle-1", null));
            if (r.action() == null) continue; // the heuristic did not recognise this phrasing: nothing was proposed or done
            proposed++;
            assertTrue(r.action().requiresConfirmation(), m + " must require confirmation");
            assertNotNull(r.action().proposalId(), m);
        }
        assertTrue(proposed >= 3, "the heuristic recognised at least three savings intents, got " + proposed);
        assertTrue(stored.stream().allMatch(p -> p.getStatus() == ProposalStatus.PENDING), "every stored proposal is PENDING");
        assertNothingMutated();
    }

    @Test
    void aContributionOnlyHappensWhenTheUserExecutesTheStoredProposal() {
        AssistantResponse r = service.interpret(user, new AssistantRequest("contribute 500 to my circle", null, "circle-1", null));
        assertNotNull(r.action());
        assertEquals(ActionType.CONTRIBUTE, r.action().intent());
        verifyNoMoreInteractions(contributionService); // proposed, not executed: no call has been made
        assertEquals(ProposalStatus.PENDING, stored.get(0).getStatus());

        service.execute(user, new ExecuteActionRequest(r.action().proposalId(), "circle-1"));
        verify(contributionService, times(1)).contribute(eq("circle-1"), eq(user), any());
        assertEquals(ProposalStatus.EXECUTED, stored.get(0).getStatus());
    }

    @Test
    void anUnknownOrAlreadyUsedProposalCannotBeExecutedForAnyAction() {
        assertThrows(com.momocircle.exception.ApiException.class, () -> service.execute(user, new ExecuteActionRequest("nope", "circle-1")));
        AssistantResponse r = service.interpret(user, new AssistantRequest("contribute 500 to my circle", null, "circle-1", null));
        service.execute(user, new ExecuteActionRequest(r.action().proposalId(), "circle-1"));
        assertThrows(com.momocircle.exception.ApiException.class, () -> service.execute(user, new ExecuteActionRequest(r.action().proposalId(), "circle-1")));
        verify(contributionService, times(1)).contribute(anyString(), any(), any());
    }

    @Test
    void theAssistantsActionVocabularyContainsNoApprovalWithdrawalPayoutOrRemovalAction() {
        List<String> names = java.util.Arrays.stream(ActionType.values()).map(Enum::name).toList();
        for (String forbidden : new String[] {"APPROVE", "WITHDRAW", "PAYOUT", "REMOVE_MEMBER", "CHANGE_ROLE", "TRANSFER"}) {
            assertTrue(names.stream().noneMatch(n -> n.contains(forbidden)), "no " + forbidden + " action exists: " + names);
        }
        assertTrue(names.contains("ADD_MEMBER"), "ADD_MEMBER exists but, like every action, only runs after explicit confirmation");
    }

    @Test
    void messagesWithoutASavingsIntentProposeNothing() {
        AssistantResponse r = service.interpret(user, new AssistantRequest("hello there", null, "circle-1", null));
        assertNull(r.action());
        assertTrue(stored.isEmpty());
        assertNothingMutated();
    }
}
