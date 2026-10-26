package com.momocircle.domain.enums;

/**
 * Output of ScamShieldService#assess (brief §19). LOW does not mean "safe"
 * — it means no common indicator this heuristic checks for was found; the
 * response text is always careful not to claim certainty either way.
 */
public enum ScamRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
