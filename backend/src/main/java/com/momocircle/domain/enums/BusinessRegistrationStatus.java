package com.momocircle.domain.enums;

/**
 * Self-declared by the business owner during PULA Business Setup — this is
 * NOT verified against CIPC or any other official government registry.
 * PULA provides registration *readiness*, never a claim of actual legal
 * registration (brief §7: "Do NOT claim that PULA legally registers a
 * company with CIPC or another government authority unless an actual
 * official integration exists" — none does, so this stays self-reported).
 */
public enum BusinessRegistrationStatus {
    /** Owner hasn't started formal registration, or is intentionally staying informal. */
    NOT_REGISTERED,
    /** Owner has started the process (e.g. applied) but it isn't complete yet. */
    REGISTRATION_IN_PROGRESS,
    /** Owner reports the business is formally registered (self-declared; not verified by PULA). */
    FORMALLY_REGISTERED
}
