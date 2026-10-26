package com.momocircle.domain.enums;

/**
 * Output of AffordabilityService#assess — never phrased as a directive
 * ("don't buy this") and never presented as regulated financial advice
 * (brief §17). {@code INSUFFICIENT_INFORMATION} is a first-class result,
 * not a fallback error: with no budget and no income history, an honest
 * engine says it doesn't know rather than guessing.
 */
public enum AffordabilityResult {
    AFFORDABLE,
    CAUTION,
    NOT_RECOMMENDED,
    INSUFFICIENT_INFORMATION
}
