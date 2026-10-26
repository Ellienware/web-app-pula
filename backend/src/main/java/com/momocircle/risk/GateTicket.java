package com.momocircle.risk;

/** Proof that a specific intent was assessed and currently permits initiation. Not a credential: bind() re-checks everything. */
public record GateTicket(String intentId, String assessmentId, boolean shadow, boolean disabled) {
    public static final GateTicket DISABLED = new GateTicket(null, null, false, true);
}
