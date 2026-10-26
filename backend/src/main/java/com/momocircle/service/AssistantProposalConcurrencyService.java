package com.momocircle.service;

import com.momocircle.domain.AssistantProposal;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ProposalStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.AssistantProposalRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Makes "confirm this AI proposal" safe under concurrent duplicate requests
 * (a double-tapped Confirm button, or a client retry racing the original) —
 * the same class of problem {@link PaymentIdempotencyService} solves for
 * payments, applied one layer up at the proposal level.
 *
 * <p>The naive pattern — load the proposal, check {@code status == PENDING},
 * run the action, then save {@code EXECUTED} — has a race window: two
 * concurrent requests can both observe PENDING and both run the action
 * (e.g. both create a contribution). {@link AssistantProposal#getVersion}
 * (JPA {@code @Version} optimistic locking) is what actually prevents that;
 * this class exists to perform the PENDING -&gt; EXECUTING claim in its own
 * transaction — committed immediately, independent of whatever the caller's
 * action does next — and to translate a lost race into a clean
 * {@link ApiException} instead of a raw {@link OptimisticLockingFailureException}.
 *
 * <p>Running the claim (and the FAILED fallback) in {@code REQUIRES_NEW} is
 * what makes the state machine's failure path actually work: if the
 * caller's own transaction later rolls back because the action threw, that
 * rollback must not erase the EXECUTING claim it already committed to –
 * otherwise the proposal would revert to PENDING and become re-runnable,
 * defeating the whole point. {@link #markFailed} runs in its own
 * transaction for the same reason: it needs to persist even though the
 * transaction that's about to roll back is the one calling it.
 */
@Service
public class AssistantProposalConcurrencyService {

    private final AssistantProposalRepository proposalRepository;

    public AssistantProposalConcurrencyService(AssistantProposalRepository proposalRepository) {
        this.proposalRepository = proposalRepository;
    }

    /**
     * Validates and atomically claims a proposal for execution, or throws.
     * Every ownership/expiry/replay/circle-context check from Phase D.1
     * lives here now (moved from AssistantService.execute, which used to do
     * these against a single non-atomic read-then-branch).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssistantProposal claim(String proposalId, User actor, String currentCircleId) {
        AssistantProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> ApiException.notFound("This proposal no longer exists — please ask again"));

        if (!proposal.getUser().getId().equals(actor.getId())) {
            // Same response a genuinely missing proposal id would produce — don't leak
            // whether a given id belongs to someone else.
            throw ApiException.notFound("This proposal no longer exists — please ask again");
        }
        switch (proposal.getStatus()) {
            case EXECUTED -> throw ApiException.conflict("This action was already completed.");
            case EXECUTING -> throw ApiException.conflict(
                    "This action is already being processed — please wait a moment.");
            case FAILED -> throw ApiException.conflict("This action previously failed — please ask again.");
            case EXPIRED -> throw ApiException.badRequest("This proposal has expired — please ask again");
            case PENDING -> { /* fall through to the expiry/circle/claim checks below */ }
        }
        if (proposal.isExpired()) {
            proposal.setStatus(ProposalStatus.EXPIRED);
            proposalRepository.save(proposal);
            throw ApiException.badRequest("This proposal has expired — please ask again");
        }
        if (!Objects.equals(proposal.getCircleId(), currentCircleId)) {
            throw ApiException.badRequest(
                    "The circle you're viewing changed since this was proposed — please ask again");
        }

        proposal.setStatus(ProposalStatus.EXECUTING);
        try {
            // flush (not just save) so the optimistic-lock check on `version` happens now,
            // inside this REQUIRES_NEW transaction, rather than whenever the persistence
            // context next happens to flush.
            return proposalRepository.saveAndFlush(proposal);
        } catch (OptimisticLockingFailureException e) {
            // Another request won the race between our read above and this write — same
            // outcome as if we'd seen EXECUTING to begin with.
            throw ApiException.conflict("This action is already being processed — please wait a moment.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String proposalId) {
        proposalRepository.findById(proposalId).ifPresent(p -> {
            p.setStatus(ProposalStatus.FAILED);
            proposalRepository.save(p);
        });
    }
}
