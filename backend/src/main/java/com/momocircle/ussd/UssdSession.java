package com.momocircle.ussd;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side session state for one in-progress USSD dial. Deliberately
 * in-memory and ephemeral — a real telecom gateway session is itself
 * short-lived (a couple of minutes, torn down the moment the subscriber's
 * phone times out or the session ends), so there is no product requirement
 * to survive an app restart, and every value here is either re-derivable or
 * about to be committed to a real domain table (Payment, TaxiPaymentRequest,
 * RideBooking, ...) once the flow completes.
 */
public class UssdSession {
    public final String sessionId;
    public final String phoneNumber;
    public String step = "ROOT";
    public boolean pinVerified = false;
    public final Map<String, String> data = new HashMap<>();
    public final Instant startedAt = Instant.now();

    public UssdSession(String sessionId, String phoneNumber) {
        this.sessionId = sessionId;
        this.phoneNumber = phoneNumber;
    }
}
