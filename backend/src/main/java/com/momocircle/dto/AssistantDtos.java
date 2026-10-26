package com.momocircle.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public final class AssistantDtos {

    private AssistantDtos() {}

    /**
     * A natural-language (and optionally visual) request to the assistant.
     *
     * @param message      the user's text prompt
     * @param imageDataUrl optional base64 data URL (e.g. a photo of a receipt or a
     *                     handwritten savings note) for multimodal understanding
     * @param circleId     optional context: the circle the user is currently viewing
     * @param productId    optional context: the product the user is currently viewing
     *                     (e.g. "add this to my cart") — never resolved by guessing a
     *                     name; only used when the frontend supplies it directly
     */
    public record AssistantRequest(
            @NotBlank String message,
            String imageDataUrl,
            String circleId,
            String productId,
            /** Optional, for "near me" food searches. Used for distance only; never stored or echoed. */
            Double lat,
            Double lng
    ) {
        public AssistantRequest(String message, String imageDataUrl, String circleId, String productId) {
            this(message, imageDataUrl, circleId, productId, null, null);
        }
    }

    /** Actions the assistant may propose. Nothing executes without confirmation. */
    public enum ActionType {
        CREATE_CIRCLE,
        CONTRIBUTE,
        CREATE_PAYMENT_REQUEST,
        ADD_MEMBER,
        CREATE_GOAL,
        CREATE_EXPENSE,
        CREATE_RECURRING_CONTRIBUTION,
        SEARCH_MARKET,
        ADD_TO_CART,
        // PULA Food. The four read-only ones are answered from PULA data on the server; ADD_FOOD_TO_CART
        // needs confirmation and only ever puts an item in the cart (checkout and payment stay manual).
        SEARCH_FOOD,
        SEARCH_RESTAURANTS,
        COMPARE_FOOD,
        VIEW_MENU,
        ADD_FOOD_TO_CART,
        CREATE_BUSINESS,
        CREATE_PRODUCT,
        PAY_TAXI,
        REQUEST_TAXI_PAYMENT,
        CREATE_RIDE,
        JOIN_RIDE,
        FIND_RIDE,
        VIEW_TAXI,
        CREATE_BUDGET,
        CHECK_AFFORDABILITY,
        PLAN_IRREGULAR_INCOME,
        CHECK_SCAM,
        EXPLAIN_CREDIT,
        COMPARE_PRODUCTS,
        COMPARE_BASKET,
        ANALYZE_SPENDING,
        PLAN_STOCK,
        VIEW_CASH_FLOW,
        VIEW_FUNDING_READINESS,
        NONE
    }

    /**
     * A structured, human-reviewable action derived from the request. The
     * frontend renders {@code summary}/{@code parameters} for display only —
     * confirming sends back {@code proposalId}, nothing else, since the
     * validated intent/parameters/circleId are held server-side (see
     * {@link com.momocircle.domain.AssistantProposal}).
     */
    public record ProposedAction(
            String proposalId,
            ActionType intent,
            String summary,
            Map<String, Object> parameters,
            double confidence,
            /**
             * True for every mutating action — false only for the small, fixed set of
             * read-only lookups (SEARCH_MARKET, FIND_RIDE, VIEW_TAXI, COMPARE_PRODUCTS,
             * COMPARE_BASKET, ANALYZE_SPENDING) that are executed directly by the frontend
             * via the normal REST API, never through POST /assistant/execute. This is a
             * deliberately narrow, explicit list — every new intent defaults to requiring
             * confirmation unless there's a specific reason (no persistence, nothing to
             * misuse) to add it here; see AssistantService's execute() switch, which throws
             * for any of these if a client somehow tries to execute one anyway.
             */
            boolean requiresConfirmation
    ) {}

    public record AssistantResponse(
            String reply,
            ProposedAction action,
            /** "ai" when the model produced this, "heuristic" for the offline fallback. */
            String source
    ) {}

    /**
     * Sent only after the user taps Confirm in the UI. Deliberately carries
     * nothing but which proposal to run and which circle the client
     * currently believes it's looking at — the server loads the actual
     * intent/parameters/circleId from the stored, already-validated
     * {@link com.momocircle.domain.AssistantProposal} instead of trusting
     * anything else the client resends. {@code currentCircleId} is used only
     * to detect a changed circle context (e.g. the user navigated to a
     * different circle after asking but before confirming) — never to
     * choose which circle to act on.
     */
    public record ExecuteActionRequest(
            @NotBlank String proposalId,
            String currentCircleId
    ) {}

    public record ExecuteActionResponse(
            String message,
            String redirectCircleId
    ) {}
}
