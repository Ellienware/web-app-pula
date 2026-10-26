package com.momocircle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single source of truth for which currency this deployment defaults to and
 * which currencies it accepts — previously "XAF" was hard-coded separately
 * in {@code ProductService}, {@code Product}, {@code Circle}, and three
 * different spots in {@code AssistantService}, so switching markets (e.g.
 * from a Cameroon/XAF deployment to a South Africa/ZAR or Ghana/GHS one)
 * meant hunting down every literal instead of changing one property.
 *
 * Bound to {@code app.currency.default} and {@code app.currency.supported}
 * in application.yml, both overridable by environment variable
 * ({@code DEFAULT_CURRENCY}, {@code SUPPORTED_CURRENCIES}) so a deployment
 * can pick its market without a code change. See application.yml's comment
 * for the exact steps to change market.
 */
@Component
@ConfigurationProperties(prefix = "app.currency")
public class CurrencyProperties {

    /** ISO 4217 code new Circles/Products default to when the caller doesn't specify one. */
    private String defaultCurrency = "ZAR";

    /** ISO 4217 codes this deployment accepts. Anything else is rejected by FinancialValidationService. */
    private List<String> supported = List.of("ZAR");

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public List<String> getSupported() {
        return supported;
    }

    public void setSupported(List<String> supported) {
        this.supported = supported;
    }

    public boolean isSupported(String currency) {
        return currency != null && supported.contains(currency.toUpperCase());
    }
}
