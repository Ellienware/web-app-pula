package com.momocircle.dto;

import com.momocircle.domain.enums.ScamRiskLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ScamShieldDtos {

    private ScamShieldDtos() {}

    public record AssessScamRequest(
            @NotBlank @Size(max = 5000) String text
    ) {}

    public record ScamAssessmentResponse(
            String id,
            /** The redacted version of what was submitted — never the raw text if it contained anything sensitive. */
            String submittedText,
            ScamRiskLevel riskLevel,
            List<String> indicators,
            String guidance,
            Instant createdAt
    ) {}
}
