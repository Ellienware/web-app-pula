package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.AssistantProposal;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.ProposalStatus;
import com.momocircle.dto.AssistantDtos.ActionType;
import com.momocircle.dto.AssistantDtos.ExecuteActionRequest;
import com.momocircle.dto.ContributionDtos.ContributionResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.AssistantProposalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the exact five scenarios the Phase D.1 brief asks for, against
 * {@link AssistantService#execute}: another user attempting to execute the
 * proposal, an expired proposal, duplicate execution, a changed circle
 * context, and successful execution using the stored (not client-resent)
 * parameters. The atomic-claim/concurrency-safety property itself (Phase
 * D.1.1) is covered separately and more thoroughly in
 * {@code AssistantProposalConcurrencyServiceTest}; here
 * {@link AssistantProposalConcurrencyService} is a real instance (not
 * mocked) wired to the same mocked repository, so execute()'s delegation to
 * it is exercised for real, just without a genuine concurrent race.
 *
 * All other of AssistantService's dependencies are mocks — execute()'s
 * CONTRIBUTE branch is the one under test since it's the simplest real
 * mutating path, and ContributionService itself is mocked here (its own
 * correctness is covered separately by PaymentTest/PaymentIdempotencyServiceTest).
 *
 * NOT VERIFIED IN THIS SANDBOX: no network access to Maven Central meant
 * ./mvnw test could not be run here.
 */
class AssistantServiceProposalTest {

    private final AssistantProposalRepository proposalRepository = mock(AssistantProposalRepository.class);
    private final ContributionService contributionService = mock(ContributionService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AssistantProposalConcurrencyService proposalConcurrencyService =
            new AssistantProposalConcurrencyService(proposalRepository);

    private final AssistantService service = new AssistantService(
            "", "", "openai/gpt-4o-mini",
            mock(CircleService.class),
            contributionService,
            mock(GoalService.class),
            mock(ExpenseService.class),
            mock(RecurringContributionService.class),
            mock(CartService.class),
            mock(BusinessService.class),
            mock(ProductService.class),
            mock(CurrencyProperties.class),
            proposalRepository,
            proposalConcurrencyService,
            auditLogService,
            mock(com.momocircle.service.momoride.MoMoRideService.class),
            mock(BudgetService.class),
            mock(AffordabilityService.class),
            mock(FinancialProfileService.class),
            mock(ScamShieldService.class),
            mock(CreditEducationService.class),
            10);

    private User owner;
    private User stranger;
    private AssistantProposal proposal;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId("user-owner");
        stranger = new User();
        stranger.setId("user-stranger");

        proposal = new AssistantProposal();
        proposal.setUser(owner);
        proposal.setCircleId("circle-1");
        proposal.setIntent(ActionType.CONTRIBUTE);
        proposal.setParametersJson("{\"amount\":5000}");
        proposal.setStatus(ProposalStatus.PENDING);
        proposal.setExpiresAt(Instant.now().plusSeconds(600));
        proposal.setVersion(0L);

        when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));
        // These tests aren't exercising the version-conflict race itself (see
        // AssistantProposalConcurrencyServiceTest for that) — echo back whatever's saved,
        // same object reference, so subsequent assertions on `proposal` see the mutation.
        when(proposalRepository.saveAndFlush(any(AssistantProposal.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(proposalRepository.save(any(AssistantProposal.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private ContributionResponse fakeContribution() {
        return new ContributionResponse("c1", "circle-1", owner.getId(), "Owner",
                new BigDecimal("5000"), "XAF", PaymentStatus.PROCESSING, null, null, null, Instant.now());
    }

    @Test
    void anotherUserCannotExecuteTheProposal() {
        ExecuteActionRequest req = new ExecuteActionRequest(proposal.getId(), "circle-1");

        // Same contract as a genuinely missing proposal id — must not leak that this id belongs
        // to someone else.
        ApiException ex = assertThrows(ApiException.class, () -> service.execute(stranger, req));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(contributionService, never()).contribute(anyString(), any(), any());
    }

    @Test
    void expiredProposalIsRejected() {
        proposal.setExpiresAt(Instant.now().minusSeconds(1));
        ExecuteActionRequest req = new ExecuteActionRequest(proposal.getId(), "circle-1");

        ApiException ex = assertThrows(ApiException.class, () -> service.execute(owner, req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(ProposalStatus.EXPIRED, proposal.getStatus(), "must be marked EXPIRED, not left PENDING");
        verify(contributionService, never()).contribute(anyString(), any(), any());
    }

    @Test
    void duplicateExecutionIsRejected() {
        proposal.setStatus(ProposalStatus.EXECUTED);
        ExecuteActionRequest req = new ExecuteActionRequest(proposal.getId(), "circle-1");

        ApiException ex = assertThrows(ApiException.class, () -> service.execute(owner, req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(contributionService, never()).contribute(anyString(), any(), any());
    }

    @Test
    void changedCircleContextIsRejected() {
        // Proposal was made while looking at circle-1; client now claims to be on circle-2.
        ExecuteActionRequest req = new ExecuteActionRequest(proposal.getId(), "circle-2");

        ApiException ex = assertThrows(ApiException.class, () -> service.execute(owner, req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(ProposalStatus.PENDING, proposal.getStatus(), "not consumed — the user can retry correctly");
        verify(contributionService, never()).contribute(anyString(), any(), any());
    }

    @Test
    void successfulExecutionUsesStoredParametersAndMarksExecuted() {
        when(contributionService.contribute(eq("circle-1"), eq(owner), any())).thenReturn(fakeContribution());
        ExecuteActionRequest req = new ExecuteActionRequest(proposal.getId(), "circle-1");

        var result = service.execute(owner, req);

        assertEquals("circle-1", result.redirectCircleId());
        // The amount came from the stored parametersJson (5000), not anything on the request —
        // ExecuteActionRequest has no amount field at all, which is the point.
        verify(contributionService, times(1)).contribute(eq("circle-1"), eq(owner),
                argThat(r -> r.amount().compareTo(new BigDecimal("5000")) == 0));
        assertEquals(ProposalStatus.EXECUTED, proposal.getStatus());
        verify(proposalRepository, times(1)).save(proposal);
        verify(auditLogService, times(1)).record(eq(owner), eq("AI_ACTION_EXECUTED"), anyString(), anyString(), anyString());
    }

    @Test
    void missingProposalIsNotFound() {
        when(proposalRepository.findById("nope")).thenReturn(Optional.empty());
        ExecuteActionRequest req = new ExecuteActionRequest("nope", null);

        ApiException ex = assertThrows(ApiException.class, () -> service.execute(owner, req));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void marketActionExecutesUsingStoredProductIdNotAnythingOnTheRequest() {
        AssistantProposal cartProposal = new AssistantProposal();
        cartProposal.setUser(owner);
        cartProposal.setCircleId(null); // market actions have no circle context
        cartProposal.setIntent(ActionType.ADD_TO_CART);
        cartProposal.setParametersJson("{\"productId\":\"product-42\",\"quantity\":2}");
        cartProposal.setStatus(ProposalStatus.PENDING);
        cartProposal.setExpiresAt(Instant.now().plusSeconds(600));
        cartProposal.setVersion(0L);
        when(proposalRepository.findById(cartProposal.getId())).thenReturn(Optional.of(cartProposal));

        CartService cartService = mock(CartService.class);
        var cartResponse = new com.momocircle.dto.MarketDtos.CartResponse(
                java.util.List.of(), java.math.BigDecimal.ZERO, "XAF");
        when(cartService.add(eq(owner), any())).thenReturn(cartResponse);

        AssistantService withCart = new AssistantService(
                "", "", "openai/gpt-4o-mini",
                mock(CircleService.class), contributionService, mock(GoalService.class),
                mock(ExpenseService.class), mock(RecurringContributionService.class),
                cartService, mock(BusinessService.class), mock(ProductService.class),
                mock(CurrencyProperties.class), proposalRepository, proposalConcurrencyService,
                auditLogService, mock(com.momocircle.service.momoride.MoMoRideService.class),
                mock(BudgetService.class), mock(AffordabilityService.class),
                mock(FinancialProfileService.class), mock(ScamShieldService.class),
                mock(CreditEducationService.class), 10);

        ExecuteActionRequest req = new ExecuteActionRequest(cartProposal.getId(), null);
        withCart.execute(owner, req);

        verify(cartService, times(1)).add(eq(owner), argThat2(r -> r.productId().equals("product-42") && r.quantity() == 2));
        assertEquals(ProposalStatus.EXECUTED, cartProposal.getStatus());
    }

    private static com.momocircle.dto.MarketDtos.AddCartItemRequest argThat2(
            java.util.function.Predicate<com.momocircle.dto.MarketDtos.AddCartItemRequest> predicate) {
        return org.mockito.ArgumentMatchers.argThat(predicate::test);
    }

    // Thin wrapper around Mockito's argThat for readability at the call site above.
    private static com.momocircle.dto.ContributionDtos.ContributeRequest argThat(
            java.util.function.Predicate<com.momocircle.dto.ContributionDtos.ContributeRequest> predicate) {
        return org.mockito.ArgumentMatchers.argThat(predicate::test);
    }
}
