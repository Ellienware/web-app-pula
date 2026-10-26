package com.momocircle.domain.enums;

/**
 * How predictably a user's income arrives — drives which planning UI the
 * Money Confidence layer shows (a single monthly figure vs. a
 * week-by-week/entry-by-entry view). See the ecosystem brief's §16.
 */
public enum IncomeType {
    FIXED_INCOME,
    VARIABLE_INCOME,
    IRREGULAR_INCOME
}
