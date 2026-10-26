package com.momocircle.domain.enums;

/**
 * Lifecycle of a server-stored AI proposed action. See AssistantProposal.
 *
 * <pre>
 * PENDING -> EXECUTING -> EXECUTED
 *                      -> FAILED   (action threw; proposal is now terminal, ask again)
 * PENDING -> EXPIRED
 * </pre>
 *
 * The PENDING -> EXECUTING transition is the one two simultaneous confirm
 * requests could otherwise both win; it's protected by {@code @Version}
 * optimistic locking (see AssistantProposal.version /
 * AssistantProposalConcurrencyService.claim).
 */
public enum ProposalStatus {
    PENDING,
    EXECUTING,
    EXECUTED,
    FAILED,
    EXPIRED
}
