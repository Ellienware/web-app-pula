package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Every financial amount in the app — contributions, payment requests,
 * goals, expenses, recurring plans, product prices — passes through here
 * before being persisted, regardless of whether it came from a manual form,
 * the AI assistant, or (eventually) any other API client. This is
 * deliberately a single shared service rather than validation duplicated
 * inside each domain service, so "AI output is untrusted input" and "manual
 * forms can also send garbage" are the same problem with the same fix, not
 * two problems that could drift out of sync.
 */
@Service
public class FinancialValidationService {

    private final CurrencyProperties currencyProperties;
    private final BigDecimal maxAmount;

    public FinancialValidationService(
            CurrencyProperties currencyProperties,
            @Value("${app.payments.max-amount:10000000}") BigDecimal maxAmount) {
        this.currencyProperties = currencyProperties;
        this.maxAmount = maxAmount;
    }

    /**
     * @param amount the value to check
     * @param field  human-readable field name for the error message (e.g. "amount", "targetAmount")
     */
    public void requireValidAmount(BigDecimal amount, String field) {
        if (amount == null) {
            throw ApiException.badRequest("\"" + field + "\" is required");
        }
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("\"" + field + "\" must be greater than zero");
        }
        if (amount.scale() > 2) {
            throw ApiException.badRequest("\"" + field + "\" cannot have more than 2 decimal places");
        }
        // unscaledValue()/precision() catches both "too many digits to be a sane amount" and
        // BigDecimal values a client could construct to overflow a DB numeric(19,2) column.
        if (amount.precision() - amount.scale() > 17) {
            throw ApiException.badRequest("\"" + field + "\" is too large to process");
        }
        if (amount.compareTo(maxAmount) > 0) {
            throw ApiException.badRequest(
                    "\"" + field + "\" exceeds the maximum allowed amount of " + maxAmount);
        }
    }

    /**
     * Same checks as {@link #requireValidAmount}, except zero is allowed.
     * For contexts where zero is meaningful — e.g. a budget category
     * allocated R0 to say "I'm not spending on this right now" — rather
     * than reusing requireValidAmount and accidentally rejecting a
     * legitimate zero, or skipping validation for these fields entirely
     * and losing the overflow/scale/max-amount checks every other amount
     * in the app gets.
     */
    public void requireValidNonNegativeAmount(BigDecimal amount, String field) {
        if (amount == null) {
            throw ApiException.badRequest("\"" + field + "\" is required");
        }
        if (amount.signum() < 0) {
            throw ApiException.badRequest("\"" + field + "\" cannot be negative");
        }
        if (amount.scale() > 2) {
            throw ApiException.badRequest("\"" + field + "\" cannot have more than 2 decimal places");
        }
        if (amount.precision() - amount.scale() > 17) {
            throw ApiException.badRequest("\"" + field + "\" is too large to process");
        }
        if (amount.compareTo(maxAmount) > 0) {
            throw ApiException.badRequest(
                    "\"" + field + "\" exceeds the maximum allowed amount of " + maxAmount);
        }
    }

    public void requireValidCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw ApiException.badRequest("Currency is required");
        }
        if (!currencyProperties.isSupported(currency)) {
            throw ApiException.badRequest(
                    "\"" + currency + "\" is not a supported currency for this deployment");
        }
    }
}
