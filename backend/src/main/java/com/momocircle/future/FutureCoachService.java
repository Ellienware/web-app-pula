package com.momocircle.future;

import com.fasterxml.jackson.databind.JsonNode;
import com.momocircle.domain.User;
import com.momocircle.exception.ApiException;
import com.momocircle.future.FutureDtos.CoachRequest;
import com.momocircle.future.FutureDtos.CoachResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * PULA Future coach. EXPLAINS figures the backend already calculated; never calculates, decides balances or payment status,
 * or acts. It has no wallet, ledger, payment, contribution or payout dependency and exposes no tools to the model.
 *
 * Data minimisation: only the redacted question text and the plan's rand figures and warnings are sent. No names, emails,
 * ids, phone numbers, business names, other users' data or settlement identifiers ever leave the server.
 * If the AI gateway is not configured, fails, is rate limited, or returns anything that fails CoachGuard, the deterministic
 * fallback answers instead.
 */
@Service
public class FutureCoachService {
    private static final Logger log = LoggerFactory.getLogger(FutureCoachService.class);

    static final String SYSTEM_PROMPT = """
            You are the PULA Future coach: a plain-language financial education helper for everyday people and small businesses in South Africa.
            Rules you must follow:
            - Explain only. The figures provided were calculated by PULA; never recalculate them or invent numbers. If a figure is missing, say so.
            - You cannot move money, make contributions, buy, sell or invest, and you must never say or imply that you did or can.
            - Never promise or guarantee any return. Say that returns fluctuate and can be negative when relevant.
            - You are not a licensed financial adviser and must not recommend specific products, providers, shares or funds.
            - Simulated balances and planning scenarios are not real money. Settlement data labelled simulated is not bank-confirmed.
            - Do not ask for or repeat PINs, one-time codes, account numbers or personal details.
            - Keep answers under 150 words, simple and friendly, with no jargon unless you explain it.
            """;

    private final AllocationPlanRepository plans;
    private final AllocationCalculationRepository calcs;
    private final String gatewayUrl;
    private final String apiKey;
    private final String model;
    private final CoachRateLimiter limiter = new CoachRateLimiter(20, 60 * 60 * 1000L);
    private final RestClient client;

    public FutureCoachService(AllocationPlanRepository plans, AllocationCalculationRepository calcs,
                              @Value("${app.ai.gateway-url:}") String gatewayUrl,
                              @Value("${app.ai.api-key:}") String apiKey,
                              @Value("${app.ai.model:openai/gpt-4o-mini}") String model) {
        this.plans = plans; this.calcs = calcs; this.gatewayUrl = gatewayUrl; this.apiKey = apiKey; this.model = model;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3_000); f.setReadTimeout(15_000);
        this.client = RestClient.builder().requestFactory(f).build();
    }

    public CoachResponse ask(User user, CoachRequest req) {
        if (!limiter.tryAcquire(user.getId(), System.currentTimeMillis())) {
            throw ApiException.tooManyRequests("You have reached the hourly limit for coach questions. Please try again later.");
        }
        CoachGuard.Context ctx = contextFor(user, req.calculationId());
        String question = CoachGuard.redact(req.question());
        if (question.isBlank()) throw ApiException.badRequest("Please type a question");

        if (aiConfigured()) {
            try {
                String text = callModel(question, ctx);
                if (CoachGuard.isSafe(text)) {
                    return new CoachResponse(text.trim() + " " + CoachGuard.DISCLAIMER, "ai", CoachGuard.DISCLAIMER);
                }
                log.warn("Coach AI answer rejected by guard; using fallback");
            } catch (RuntimeException e) {
                log.warn("Coach AI call failed; using fallback: {}", e.getMessage());
            }
        }
        CoachGuard.Answer a = CoachGuard.fallback(question, ctx);
        return new CoachResponse(a.text(), a.source(), CoachGuard.DISCLAIMER);
    }

    private boolean aiConfigured() {
        return gatewayUrl != null && !gatewayUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    /** Loads the caller's own calculation. Someone else's calculation is reported as not found. */
    private CoachGuard.Context contextFor(User user, String calculationId) {
        if (calculationId == null || calculationId.isBlank()) return null;
        AllocationCalculation c = calcs.findById(calculationId).orElseThrow(() -> ApiException.notFound("Calculation not found"));
        plans.findByIdAndOwnerUserId(c.getPlanId(), user.getId()).orElseThrow(() -> ApiException.notFound("Calculation not found"));
        List<String> warnings = c.getWarnings() == null ? List.of() : List.of(c.getWarnings().split("\n"));
        return new CoachGuard.Context(c.isValid(), c.getIncomeBasisMinor(), c.getAllocatableMinor(),
                c.getTotalAllocatedMinor(), c.getRemainingMinor(), warnings);
    }

    private String callModel(String question, CoachGuard.Context ctx) {
        StringBuilder user = new StringBuilder("Question: ").append(question).append('\n');
        if (ctx != null) {
            user.append("Plan figures calculated by PULA (rand; do not recalculate): income basis ").append(rand(ctx.incomeBasisMinor()))
                .append(", available after declared reserves and commitments ").append(rand(ctx.allocatableMinor()))
                .append(", proposed total ").append(rand(ctx.totalAllocatedMinor()))
                .append(", remaining ").append(rand(ctx.remainingMinor()))
                .append(", scenario valid: ").append(ctx.valid()).append('\n');
            if (ctx.warnings() != null && !ctx.warnings().isEmpty()) user.append("Warnings: ").append(String.join(" ", ctx.warnings())).append('\n');
        }
        Map<String, Object> body = Map.of("model", model, "max_tokens", 300, "temperature", 0.2,
                "messages", List.of(Map.of("role", "system", "content", SYSTEM_PROMPT),
                                    Map.of("role", "user", "content", user.toString())));
        JsonNode response = client.post().uri(gatewayUrl)
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .body(body).retrieve().body(JsonNode.class);
        return response == null ? "" : response.at("/choices/0/message/content").asText("");
    }

    private static String rand(Long minor) {
        long m = minor == null ? 0 : minor;
        return String.format(java.util.Locale.ROOT, "R%d.%02d", m / 100, Math.abs(m % 100));
    }
}
