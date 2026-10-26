package com.momocircle.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure (no Spring) classification of which configured providers are SIMULATED, i.e. never move real money or send real
 * messages. Kept framework-free so it is unit-testable and cannot itself affect any payment flow.
 */
public final class SimulatedProviderAudit {

    private SimulatedProviderAudit() { }

    /**
     * @return human-readable descriptions of every simulated provider; empty when everything is real.
     *         Blank values are treated as the application default (which is simulated for all four settings).
     */
    public static List<String> findSimulated(String paymentProvider, String paystackMode,
                                             String payoutProvider, String smsProvider) {
        List<String> out = new ArrayList<>();
        if (isSim(paymentProvider, "sandbox")) {
            out.add("app.payments.provider=" + show(paymentProvider, "sandbox")
                    + " (legacy payments: taxi fares, ride bookings, contributions, legacy orders are SIMULATED)");
        }
        if (isSim(paystackMode, "mock")) {
            out.add("app.payments.paystack.mode=" + show(paystackMode, "mock")
                    + " (marketplace collections and refunds are SIMULATED)");
        }
        if (isSim(payoutProvider, "sandbox")) {
            out.add("app.payouts.provider=" + show(payoutProvider, "sandbox") + " (payouts are SIMULATED)");
        }
        if (isSim(smsProvider, "sandbox")) {
            out.add("app.sms.provider=" + show(smsProvider, "sandbox") + " (SMS are not sent)");
        }
        return out;
    }


    /**
     * Phase 10 retirement guard. Paystack, MTN MoMo and Stitch are no longer integrations; their client code has been
     * removed. Any setting that selects them is a misconfiguration that would otherwise look like "real payments are on",
     * so it must stop the application rather than silently fall back.
     *
     * @return one description per retired selection; empty when every value is blank or the simulator.
     */
    public static List<String> findRetiredRealProviders(String paymentProvider, String paystackMode, String payoutProvider) {
        List<String> out = new ArrayList<>();
        if (!isBlankOr(paymentProvider, "sandbox")) {
            out.add("app.payments.provider=" + paymentProvider.trim() + " (MTN MoMo and every other real payment provider are retired; only 'sandbox' exists)");
        }
        if (!isBlankOr(paystackMode, "mock")) {
            out.add("app.payments.paystack.mode=" + paystackMode.trim() + " (Paystack is retired; only the simulated 'mock' gateway exists)");
        }
        if (!isBlankOr(payoutProvider, "sandbox")) {
            out.add("app.payouts.provider=" + payoutProvider.trim() + " (Stitch and every other real payout provider are not supported; only 'sandbox' exists)");
        }
        return out;
    }

    private static boolean isBlankOr(String value, String allowed) {
        return value == null || value.isBlank() || value.trim().toLowerCase(Locale.ROOT).equals(allowed);
    }

    private static boolean isSim(String value, String simulatedName) {
        if (value == null || value.isBlank()) return true; // application default is the simulator
        return value.trim().toLowerCase(Locale.ROOT).equals(simulatedName);
    }

    private static String show(String v, String dflt) {
        return v == null || v.isBlank() ? dflt + " (default)" : v.trim();
    }

    /** True when the dev profile is active but the environment looks like a hosted deployment (DATABASE_URL present). */
    public static boolean devProfileOnHostedEnvironment(boolean devActive, boolean prodActive, String databaseUrlEnv) {
        return devActive && !prodActive && databaseUrlEnv != null && !databaseUrlEnv.isBlank();
    }
}
