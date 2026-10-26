package com.momocircle.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Phase 8 startup safety check. It never changes behaviour by default: it only logs loudly when the application is
 * running with simulated providers under the "prod" profile, or with the "dev" profile on a hosted-looking environment
 * (DATABASE_URL set), where the dev JWT secret and seeded admin would be active.
 *
 * <p>Operators may opt in to a hard stop with {@code app.safety.fail-on-simulated-providers=true}
 * (env APP_SAFETY_FAIL_ON_SIMULATED_PROVIDERS). Default is false so existing deployments are not broken.
 */
@Component
public class ProductionSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

    private final Environment env;
    private final String paymentProvider;
    private final String paystackMode;
    private final String payoutProvider;
    private final String smsProvider;
    private final boolean failOnSimulated;

    public ProductionSafetyCheck(Environment env,
                                 @Value("${app.payments.provider:}") String paymentProvider,
                                 @Value("${app.payments.paystack.mode:}") String paystackMode,
                                 @Value("${app.payouts.provider:}") String payoutProvider,
                                 @Value("${app.sms.provider:}") String smsProvider,
                                 @Value("${app.safety.fail-on-simulated-providers:false}") boolean failOnSimulated) {
        this.env = env;
        this.paymentProvider = paymentProvider;
        this.paystackMode = paystackMode;
        this.payoutProvider = payoutProvider;
        this.smsProvider = smsProvider;
        this.failOnSimulated = failOnSimulated;
    }

    @PostConstruct
    void check() {
        List<String> profiles = Arrays.asList(env.getActiveProfiles());
        boolean prod = profiles.contains("prod");
        boolean dev = profiles.contains("dev");
        if (SimulatedProviderAudit.devProfileOnHostedEnvironment(dev, prod, System.getenv("DATABASE_URL"))) {
            log.error("SAFETY: profile 'dev' is active but DATABASE_URL is set. Dev uses a public JWT secret, an "
                    + "in-memory database and a seeded admin account. Set SPRING_PROFILES_ACTIVE=prod.");
        }
        // Phase 10: retired real-money providers fail startup in EVERY profile (never just a log line).
        List<String> retired = SimulatedProviderAudit.findRetiredRealProviders(paymentProvider, paystackMode, payoutProvider);
        if (!retired.isEmpty()) {
            throw new IllegalStateException("SAFETY: retired real-money provider selected: " + String.join("; ", retired)
                    + ". Unset these variables; PULA only runs simulated money flows.");
        }
        if (!prod) return;
        List<String> simulated = SimulatedProviderAudit.findSimulated(paymentProvider, paystackMode, payoutProvider, smsProvider);
        if (simulated.isEmpty()) return;
        String msg = "SAFETY: prod profile is running with SIMULATED providers: " + String.join("; ", simulated);
        if (failOnSimulated) {
            throw new IllegalStateException(msg + ". Refusing to start (app.safety.fail-on-simulated-providers=true).");
        }
        log.error(msg + ". Statuses such as SUCCESSFUL/REFUNDED for these flows do NOT reflect real money movement.");
    }
}
