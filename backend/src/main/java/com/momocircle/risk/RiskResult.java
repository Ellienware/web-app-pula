package com.momocircle.risk;

import java.util.List;

/**
 * Engine output. {@code ruleIds} and {@code score} are for audit and administrators only. {@code safeExplanation} is the only
 * text shown to ordinary users and never reveals thresholds or which signal fired.
 */
public record RiskResult(int score, RiskLevel level, RiskDecision decision, List<String> ruleIds,
                         List<String> unavailableSignals, String safeExplanation, boolean hardRule) {}
