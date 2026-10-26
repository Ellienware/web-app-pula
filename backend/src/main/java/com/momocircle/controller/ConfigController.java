package com.momocircle.controller;

import com.momocircle.config.CurrencyProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public, read-only app configuration the frontend needs to render correctly
 * without hard-coding a market. No auth required — nothing here is secret.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final CurrencyProperties currencyProperties;

    public ConfigController(CurrencyProperties currencyProperties) {
        this.currencyProperties = currencyProperties;
    }

    public record CurrencyConfigResponse(String defaultCurrency, List<String> supportedCurrencies) {}

    @org.springframework.beans.factory.annotation.Value("${app.payments.market-checkout:legacy}")
    private String marketCheckout;

    @org.springframework.beans.factory.annotation.Value("${app.payments.wallet-checkout.enabled:true}")
    private boolean walletCheckout;

    /** {@code walletCheckout} is additive: older clients ignore it. The wallet is SIMULATED demo value, never real money. */
    public record MarketPaymentsConfig(String consumerCheckout, boolean walletCheckout) {}

    /** Tells clients which consumer checkout to use ("paystack" = simulated hosted checkout, "legacy" = original) and whether the demo wallet can pay. */
    @GetMapping("/market-payments")
    public MarketPaymentsConfig marketPayments() { return new MarketPaymentsConfig(marketCheckout.toLowerCase(), walletCheckout); }

    @GetMapping("/currency")
    public CurrencyConfigResponse currency() {
        return new CurrencyConfigResponse(currencyProperties.getDefaultCurrency(), currencyProperties.getSupported());
    }
}
