package com.momocircle.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount in integer minor units (e.g. cents). The ledger never uses floating point and never rounds silently:
 * a decimal with more fractional digits than the currency allows is rejected, not truncated.
 * Legacy {@code DECIMAL(19,2)} columns convert exactly through {@link #ofDecimal}.
 */
public record Money(long minor, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (!currency.matches("[A-Z]{3}")) {
            throw new LedgerException(LedgerException.Code.INVALID_CURRENCY, "Currency must be a 3-letter ISO code");
        }
        if (minor < 0) {
            throw new LedgerException(LedgerException.Code.INVALID_AMOUNT, "Amount cannot be negative");
        }
    }

    public static Money ofDecimal(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "amount");
        int digits = fractionDigits(currency);
        try {
            long minor = amount.setScale(digits, RoundingMode.UNNECESSARY).movePointRight(digits).longValueExact();
            return new Money(minor, currency);
        } catch (ArithmeticException e) {
            throw new LedgerException(LedgerException.Code.INVALID_AMOUNT,
                    "Amount has more precision than " + currency + " allows, or is out of range");
        }
    }

    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minor, fractionDigits(currency));
    }

    public boolean isPositive() {
        return minor > 0;
    }

    static int fractionDigits(String currency) {
        try {
            int d = Currency.getInstance(currency).getDefaultFractionDigits();
            if (d < 0) {
                throw new LedgerException(LedgerException.Code.INVALID_CURRENCY, "Unsupported currency " + currency);
            }
            return d;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new LedgerException(LedgerException.Code.INVALID_CURRENCY, "Unknown currency " + currency);
        }
    }
}
