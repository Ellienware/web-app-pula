package com.momocircle.services;

import com.momocircle.domain.enums.ServicePricingModel;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The ONLY place a quote total is computed. Everything is BigDecimal at 2 dp. The provider supplies inputs; the server
 * derives labour for FIXED/HOURLY listings, takes materials from priced PULA Stock lines, and adds up the total.
 * The customer never supplies an amount.
 */
public final class ServiceQuoteCalculator {

    public static final BigDecimal MAX_COMPONENT = new BigDecimal("10000000.00");
    public static final BigDecimal MAX_HOURS = new BigDecimal("1000");

    private ServiceQuoteCalculator() {}

    public record Result(BigDecimal hours, BigDecimal labour, BigDecimal materials, BigDecimal delivery, BigDecimal other,
                         BigDecimal total) {}

    public static Result compute(ServicePricingModel model, BigDecimal listingPrice, BigDecimal hours,
                                 BigDecimal labourInput, BigDecimal delivery, BigDecimal other, String otherDescription,
                                 BigDecimal materials) {
        BigDecimal del = money(delivery, "delivery");
        BigDecimal oth = money(other, "other");
        BigDecimal mat = money(materials, "materials");
        if (oth.signum() > 0 && (otherDescription == null || otherDescription.isBlank())) {
            throw new IllegalArgumentException("Describe what the 'other' amount is for");
        }
        BigDecimal labour;
        BigDecimal storedHours = null;
        switch (model) {
            case FIXED -> {
                requirePrice(listingPrice);
                labour = scale(listingPrice);
                if (labourInput != null && scale(labourInput).compareTo(labour) != 0) {
                    throw new IllegalArgumentException("A fixed-price service must be quoted at its listed price");
                }
            }
            case HOURLY -> {
                requirePrice(listingPrice);
                if (hours == null || hours.signum() <= 0 || hours.compareTo(MAX_HOURS) > 0) {
                    throw new IllegalArgumentException("Hours must be between 0 and " + MAX_HOURS.toPlainString());
                }
                storedHours = hours.setScale(2, RoundingMode.HALF_UP);
                labour = scale(listingPrice.multiply(storedHours));
                if (labourInput != null && scale(labourInput).compareTo(labour) != 0) {
                    throw new IllegalArgumentException("Labour for an hourly service is the listed rate multiplied by the hours");
                }
            }
            case STARTING_FROM -> {
                requirePrice(listingPrice);
                if (labourInput == null) {
                    throw new IllegalArgumentException("Labour is required");
                }
                labour = money(labourInput, "labour");
                if (labour.compareTo(scale(listingPrice)) < 0) {
                    throw new IllegalArgumentException("The quote cannot be below the listed starting price");
                }
            }
            case QUOTE_REQUIRED -> {
                if (labourInput == null) {
                    throw new IllegalArgumentException("Labour is required");
                }
                labour = money(labourInput, "labour");
            }
            default -> throw new IllegalArgumentException("Unsupported pricing model");
        }
        if (labour.compareTo(MAX_COMPONENT) > 0) {
            throw new IllegalArgumentException("Labour is too large");
        }
        BigDecimal total = labour.add(mat).add(del).add(oth);
        if (total.signum() <= 0) {
            throw new IllegalArgumentException("The quote total must be more than zero");
        }
        if (total.compareTo(MAX_COMPONENT) > 0) {
            throw new IllegalArgumentException("The quote total is too large");
        }
        return new Result(storedHours, labour, mat, del, oth, total);
    }

    private static void requirePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("This listing has no price to quote against");
        }
    }

    private static BigDecimal money(BigDecimal v, String what) {
        if (v == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (v.signum() < 0) {
            throw new IllegalArgumentException(what + " cannot be negative");
        }
        BigDecimal s = scale(v);
        if (s.compareTo(MAX_COMPONENT) > 0) {
            throw new IllegalArgumentException(what + " is too large");
        }
        return s;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
