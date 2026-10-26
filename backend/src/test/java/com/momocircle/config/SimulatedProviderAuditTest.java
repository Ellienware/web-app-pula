package com.momocircle.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedProviderAuditTest {

    @Test
    void allDefaultsAreReportedAsSimulated() {
        assertEquals(4, SimulatedProviderAudit.findSimulated("", "", "", "").size());
        assertEquals(4, SimulatedProviderAudit.findSimulated(null, null, null, null).size());
    }

    @Test
    void explicitSandboxAndMockAreReportedCaseInsensitively() {
        List<String> r = SimulatedProviderAudit.findSimulated("SANDBOX", " Mock ", "sandbox", "Sandbox");
        assertEquals(4, r.size());
    }

    @Test
    void realProvidersProduceNoFindings() {
        assertTrue(SimulatedProviderAudit.findSimulated("mtn", "paystack", "stitch", "twilio").isEmpty());
    }

    @Test
    void onlySimulatedOnesAreListed() {
        List<String> r = SimulatedProviderAudit.findSimulated("mtn", "paystack", "sandbox", "twilio");
        assertEquals(1, r.size());
        assertTrue(r.get(0).startsWith("app.payouts.provider"));
    }

    @Test
    void devOnHostedEnvironmentIsDetectedOnlyWhenDevWithoutProdAndDatabaseUrl() {
        assertTrue(SimulatedProviderAudit.devProfileOnHostedEnvironment(true, false, "postgres://x"));
        assertFalse(SimulatedProviderAudit.devProfileOnHostedEnvironment(true, false, ""));
        assertFalse(SimulatedProviderAudit.devProfileOnHostedEnvironment(true, false, null));
        assertFalse(SimulatedProviderAudit.devProfileOnHostedEnvironment(false, true, "postgres://x"));
        assertFalse(SimulatedProviderAudit.devProfileOnHostedEnvironment(true, true, "postgres://x"));
    }

    @Test
    void retiredGuardAcceptsBlankAndSimulatorValues() {
        assertTrue(SimulatedProviderAudit.findRetiredRealProviders(null, "", " ").isEmpty());
        assertTrue(SimulatedProviderAudit.findRetiredRealProviders("SANDBOX", " Mock ", "sandbox").isEmpty());
    }

    @Test
    void retiredGuardRejectsEveryRetiredSelection() {
        List<String> r = SimulatedProviderAudit.findRetiredRealProviders("mtn", "paystack", "stitch");
        assertEquals(3, r.size());
        assertTrue(r.get(0).startsWith("app.payments.provider=mtn"));
        assertTrue(r.get(1).startsWith("app.payments.paystack.mode=paystack"));
        assertTrue(r.get(2).startsWith("app.payouts.provider=stitch"));
        assertEquals(1, SimulatedProviderAudit.findRetiredRealProviders("sandbox", "mock", "anything-else").size());
    }
}
