package com.momocircle.service;

import com.momocircle.domain.ScamAssessment;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ScamRiskLevel;
import com.momocircle.dto.ScamShieldDtos.AssessScamRequest;
import com.momocircle.dto.ScamShieldDtos.ScamAssessmentResponse;
import com.momocircle.repository.ScamAssessmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this. The redaction tests matter
 * most here: a bug there means a real secret could be persisted, which is
 * a much worse failure mode than a wrong risk classification.
 */
class ScamShieldServiceTest {

    private ScamAssessmentRepository repository;
    private ScamShieldService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(ScamAssessmentRepository.class);
        service = new ScamShieldService(repository);
        user = new User();
        user.setId("user-1");
        when(repository.save(any(ScamAssessment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void redactsOtpFollowedByDigits() {
        String redacted = service.redact("Your OTP is 123456, do not share it.");
        assertFalse(redacted.contains("123456"));
        assertTrue(redacted.contains("[REDACTED]"));
        assertTrue(redacted.toLowerCase().contains("otp"));
    }

    @Test
    void redactsThroughAConnectingWordNotJustPunctuation() {
        // Regression test for a real bug caught by a full `mvn test` run: the first
        // version of the redaction regex only bridged punctuation/whitespace between
        // the keyword and the digits, so "OTP 123456" redacted but "OTP is 123456"
        // and "PIN number is 4321" did not, because "is"/"number" contain letters
        // that were (wrongly) excluded from the gap. See PHASE_I4 report addendum.
        assertFalse(service.redact("Your banking PIN number is 445566").contains("445566"));
        assertFalse(service.redact("verification code was 778899").contains("778899"));
    }

    @Test
    void redactsPinWithColon() {
        String redacted = service.redact("Your banking PIN: 4321 was used to log in.");
        assertFalse(redacted.contains("4321"));
        assertTrue(redacted.contains("[REDACTED]"));
    }

    @Test
    void redactsMultipleSensitiveValuesInOneMessage() {
        String redacted = service.redact("OTP 111111 and password 22334455 were both requested.");
        assertFalse(redacted.contains("111111"));
        assertFalse(redacted.contains("22334455"));
    }

    @Test
    void leavesUnrelatedNumbersUntouched() {
        String redacted = service.redact("Your order #4521 will arrive at 14:30.");
        assertTrue(redacted.contains("4521"));
        assertTrue(redacted.contains("14:30"));
    }

    @Test
    void assessedTextStoredIsAlwaysTheRedactedVersion() {
        AssessScamRequest req = new AssessScamRequest("URGENT: your OTP is 999888 - reply now or account is suspended");
        ScamAssessmentResponse response = service.assess(user, req);

        assertFalse(response.submittedText().contains("999888"));
    }

    @Test
    void classicPhishingMessageIsHighRisk() {
        AssessScamRequest req = new AssessScamRequest(
                "URGENT: Your account will be suspended. Verify your account immediately by clicking "
                        + "http://bit.ly/abc123 and confirm your details.");
        ScamAssessmentResponse response = service.assess(user, req);

        assertEquals(ScamRiskLevel.HIGH, response.riskLevel());
        assertFalse(response.indicators().isEmpty());
    }

    @Test
    void ordinaryMessageIsLowRiskWithNoIndicators() {
        AssessScamRequest req = new AssessScamRequest("Hey, are we still meeting for coffee tomorrow at 10?");
        ScamAssessmentResponse response = service.assess(user, req);

        assertEquals(ScamRiskLevel.LOW, response.riskLevel());
        assertTrue(response.indicators().isEmpty());
    }

    @Test
    void lowRiskGuidanceNeverClaimsTheMessageIsSafe() {
        AssessScamRequest req = new AssessScamRequest("Hey, are we still meeting for coffee tomorrow at 10?");
        ScamAssessmentResponse response = service.assess(user, req);

        String guidance = response.guidance().toLowerCase();
        // The guidance is allowed to say something is NOT guaranteed genuine/safe
        // (that's the correct hedge) - what it must never do is assert a positive
        // safety claim like "this is safe" or "this is genuine".
        assertFalse(guidance.contains("this is safe"));
        assertFalse(guidance.contains("is a genuine"));
        assertTrue(guidance.contains("not a guarantee"));
    }
}
