package com.momocircle.service;

import com.momocircle.domain.ScamAssessment;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ScamRiskLevel;
import com.momocircle.dto.ScamShieldDtos.AssessScamRequest;
import com.momocircle.dto.ScamShieldDtos.ScamAssessmentResponse;
import com.momocircle.repository.ScamAssessmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implements the brief's §19 ScamShield. Two responsibilities, kept
 * strictly separate: {@link #redact} always runs first and strips anything
 * that looks like a real OTP/PIN/CVV/password value before the text is
 * stored anywhere — this app never asks for or keeps a user's actual
 * secrets even if they're accidentally included in a forwarded message.
 * {@link #score} then runs a simple, transparent keyword/pattern heuristic
 * over the (already redacted) text to flag a small, illustrative set of
 * common scam patterns — this is intentionally not an exhaustive,
 * annotated catalogue of scam phrasing; a short list of clear categories
 * has real protective value without doubling as a tuning guide.
 *
 * <p>LOW risk is never presented as "this is safe" — the response always
 * says only that this heuristic didn't find its known indicators.
 */
@Service
public class ScamShieldService {

    // Keyword-then-digits, e.g. "OTP is 123456", "PIN: 4321", "your password 88221100".
    // The gap between keyword and digits is "any non-digit character" (spaces, punctuation,
    // AND connecting words like "is"/"number"), not "any non-alphanumeric character" - the
    // first version of this pattern excluded letters from the gap, which meant it silently
    // failed to match realistic phrasing like "OTP is 123456" (only caught "OTP 123456" with
    // no word in between). Caught by a real `mvn test` run, not by review - see PHASE_I4
    // report addendum.
    private static final Pattern SENSITIVE_CODE_PATTERN = Pattern.compile(
            "(otp|pin|cvv|one[- ]time pin|one[- ]time code|verification code|password)"
                    + "([^0-9]{0,20})([0-9]{4,8})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://\\S+|www\\.\\S+", Pattern.CASE_INSENSITIVE);
    private static final List<String> LINK_SHORTENERS = List.of(
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "cutt.ly");

    private record IndicatorRule(String description, int weight, List<String> keywords) {
        boolean matches(String lowerText) {
            return keywords.stream().anyMatch(lowerText::contains);
        }
    }

    private static final List<IndicatorRule> RULES = List.of(
            new IndicatorRule(
                    "Asks for a one-time PIN, password, or other sensitive account detail — a real "
                            + "bank or provider will never ask you to send these.",
                    3,
                    List.of("otp", "one-time pin", "one time pin", "pin number", "cvv", "password",
                            "verify your account", "confirm your details", "banking app pin")),
            new IndicatorRule(
                    "Uses urgent or pressuring language to rush a decision.",
                    2,
                    List.of("urgent", "immediately", "act now", "expires today", "limited time",
                            "act fast", "within 24 hours", "suspend your account", "account will be blocked")),
            new IndicatorRule(
                    "Asks for an unexpected payment, fee, or transfer.",
                    2,
                    List.of("send money", "transfer immediately", "pay now to", "gift card",
                            "voucher code", "processing fee", "release your funds", "pay a fee to receive")),
            new IndicatorRule(
                    "Promises a prize or reward that seems too good to be true.",
                    2,
                    List.of("you have won", "congratulations you", "claim your prize",
                            "selected as a winner", "free gift")),
            new IndicatorRule(
                    "Mentions a bank, mobile money provider, or government agency — confirm through "
                            + "an official channel (their app or a number you already trust) before acting.",
                    1,
                    List.of("sars", "your bank", "momo support", "customer service department"))
    );

    private final ScamAssessmentRepository scamAssessmentRepository;

    public ScamShieldService(ScamAssessmentRepository scamAssessmentRepository) {
        this.scamAssessmentRepository = scamAssessmentRepository;
    }

    @Transactional
    public ScamAssessmentResponse assess(User user, AssessScamRequest req) {
        String redacted = redact(req.text());
        List<String> indicators = new ArrayList<>();
        int score = 0;

        String lower = redacted.toLowerCase(Locale.ROOT);
        for (IndicatorRule rule : RULES) {
            if (rule.matches(lower)) {
                indicators.add(rule.description());
                score += rule.weight();
            }
        }

        Matcher urlMatcher = URL_PATTERN.matcher(redacted);
        if (urlMatcher.find()) {
            boolean shortener = LINK_SHORTENERS.stream().anyMatch(lower::contains);
            if (shortener) {
                indicators.add("Uses a shortened link, which hides the real destination — "
                        + "don't tap it until you know where it actually goes.");
                score += 3;
            } else {
                indicators.add("Contains a link — be cautious before tapping it, especially from an unfamiliar sender.");
                score += 2;
            }
        }

        ScamRiskLevel riskLevel = classify(score);

        ScamAssessment assessment = new ScamAssessment();
        assessment.setUser(user);
        assessment.setSubmittedText(redacted);
        assessment.setRiskLevel(riskLevel);
        assessment.setIndicators(indicators);

        return toResponse(scamAssessmentRepository.save(assessment));
    }

    @Transactional(readOnly = true)
    public List<ScamAssessmentResponse> history(User user) {
        return scamAssessmentRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream().map(this::toResponse).toList();
    }

    /** Strips anything that looks like a real OTP/PIN/CVV/password value. Always run before storage. */
    String redact(String text) {
        Matcher matcher = SENSITIVE_CODE_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + "[REDACTED]");
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private ScamRiskLevel classify(int score) {
        if (score >= 7) return ScamRiskLevel.HIGH;
        if (score >= 4) return ScamRiskLevel.MEDIUM;
        return ScamRiskLevel.LOW;
    }

    private String guidanceFor(ScamRiskLevel level, boolean anyIndicators) {
        return switch (level) {
            case HIGH -> "Several common scam warning signs were found. Don't click any links, don't reply with "
                    + "any codes or personal details, and verify independently through an official app or number "
                    + "you already trust before doing anything else.";
            case MEDIUM -> "Some warning signs were found. Slow down, and verify this independently before "
                    + "acting on it.";
            case LOW -> anyIndicators
                    ? "No strong warning signs were found, but stay cautious with anything unexpected."
                    : "No common scam indicators were found in this message. That's not a guarantee it's "
                            + "genuine — always stay cautious with unexpected requests for money or personal information.";
        };
    }

    private ScamAssessmentResponse toResponse(ScamAssessment a) {
        return new ScamAssessmentResponse(a.getId(), a.getSubmittedText(), a.getRiskLevel(), a.getIndicators(),
                guidanceFor(a.getRiskLevel(), !a.getIndicators().isEmpty()), a.getCreatedAt());
    }
}
