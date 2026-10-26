package com.momocircle.ussd;

/**
 * Outbound USSD response. {@code continueSession=true} maps to the gateway
 * convention of prefixing the text with "CON " (menu/prompt, session stays
 * open); {@code false} maps to "END " (final message, session closes).
 * {@link #raw()} renders that exact prefixed string, which is what a real
 * gateway integration would put on the wire.
 */
public record UssdResponse(String message, boolean continueSession) {

    public static UssdResponse menu(String message) {
        return new UssdResponse(message, true);
    }

    public static UssdResponse end(String message) {
        return new UssdResponse(message, false);
    }

    public String raw() {
        return (continueSession ? "CON " : "END ") + message;
    }
}
