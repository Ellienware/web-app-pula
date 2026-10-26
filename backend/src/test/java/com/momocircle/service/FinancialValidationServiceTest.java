package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FinancialValidationService is the single choke point every amount in the
 * app passes through — manual forms, AI actions, and (eventually) any other
 * API client alike — so these cases matter regardless of which layer calls
 * in. See the class javadoc for why this isn't duplicated per-service.
 *
 * NOT VERIFIED IN THIS SANDBOX: no network access to Maven Central meant
 * ./mvnw test could not be run here.
 */
class FinancialValidationServiceTest {

    private FinancialValidationService service;

    @BeforeEach
    void setUp() {
        CurrencyProperties currencyProperties = new CurrencyProperties();
        currencyProperties.setDefaultCurrency("XAF");
        currencyProperties.setSupported(java.util.List.of("XAF", "GHS", "UGX"));
        service = new FinancialValidationService(currencyProperties, new BigDecimal("10000000"));
    }

    @Test
    void zeroAmountIsRejected() {
        assertThrows(ApiException.class, () -> service.requireValidAmount(BigDecimal.ZERO, "amount"));
    }

    @Test
    void negativeAmountIsRejected() {
        assertThrows(ApiException.class,
                () -> service.requireValidAmount(new BigDecimal("-100"), "amount"));
    }

    @Test
    void nullAmountIsRejected() {
        assertThrows(ApiException.class, () -> service.requireValidAmount(null, "amount"));
    }

    @Test
    void excessiveAmountIsRejected() {
        assertThrows(ApiException.class,
                () -> service.requireValidAmount(new BigDecimal("99999999999"), "amount"));
    }

    @Test
    void tooManyDecimalPlacesIsRejected() {
        assertThrows(ApiException.class,
                () -> service.requireValidAmount(new BigDecimal("100.999"), "amount"));
    }

    @Test
    void validAmountIsAccepted() {
        assertDoesNotThrow(() -> service.requireValidAmount(new BigDecimal("5000.50"), "amount"));
    }

    @Test
    void amountAtExactlyTheMaxIsAccepted() {
        assertDoesNotThrow(() -> service.requireValidAmount(new BigDecimal("10000000"), "amount"));
    }

    @Test
    void amountOneCentOverTheMaxIsRejected() {
        assertThrows(ApiException.class,
                () -> service.requireValidAmount(new BigDecimal("10000000.01"), "amount"));
    }

    @Test
    void unsupportedCurrencyIsRejected() {
        assertThrows(ApiException.class, () -> service.requireValidCurrency("USD"));
    }

    @Test
    void blankCurrencyIsRejected() {
        assertThrows(ApiException.class, () -> service.requireValidCurrency(""));
        assertThrows(ApiException.class, () -> service.requireValidCurrency(null));
    }

    @Test
    void supportedCurrencyIsAccepted() {
        assertDoesNotThrow(() -> service.requireValidCurrency("XAF"));
        assertDoesNotThrow(() -> service.requireValidCurrency("ghs")); // case-insensitive
    }

    @Test
    void nonNegativeVariantAcceptsZeroUnlikeTheStrictVariant() {
        // requireValidAmount rejects zero (a R0 contribution/goal/loan makes no
        // sense); requireValidNonNegativeAmount exists specifically for the
        // opposite case — a R0 budget category is a legitimate "not spending on
        // this" choice. Both must reject negative amounts the same way.
        assertDoesNotThrow(() -> service.requireValidNonNegativeAmount(BigDecimal.ZERO, "allocatedAmount"));
        assertThrows(ApiException.class, () -> service.requireValidAmount(BigDecimal.ZERO, "amount"));
    }

    @Test
    void nonNegativeVariantStillRejectsNegative() {
        assertThrows(ApiException.class,
                () -> service.requireValidNonNegativeAmount(new BigDecimal("-1.00"), "allocatedAmount"));
    }

    @Test
    void nonNegativeVariantStillEnforcesTheMaximum() {
        assertThrows(ApiException.class,
                () -> service.requireValidNonNegativeAmount(new BigDecimal("10000000.01"), "allocatedAmount"));
    }
}
