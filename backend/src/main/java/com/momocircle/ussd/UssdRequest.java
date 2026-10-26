package com.momocircle.ussd;

import jakarta.validation.constraints.NotBlank;

/**
 * Inbound callback shape used by most telecom USSD gateways (Africa's Talking,
 * Twilio-style aggregators, etc.): a stable sessionId, the caller's MSISDN,
 * and the FULL accumulated input for the session so far, star-separated
 * (e.g. "1*30*0821234567"). The gateway resends the whole session text on
 * every hop — this app never has to persist raw gateway state, only its own
 * derived {@link UssdSession}.
 */
public record UssdRequest(
        @NotBlank String sessionId,
        @NotBlank String phoneNumber,
        String text
) {}
