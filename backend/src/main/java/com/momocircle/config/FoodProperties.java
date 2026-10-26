package com.momocircle.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PULA Food settings, bound to {@code app.food.*}. Placeholders until product owners confirm
 * them: the accept window, its per-business cap, and an optional minimum PULA Ride delivery fee.
 */
@Component
@ConfigurationProperties(prefix = "app.food")
public class FoodProperties {

    /** Minutes a restaurant has to accept a paid order when it hasn't set its own window. */
    private int acceptTimeoutMinutes = 10;

    /** Largest accept window a restaurant may configure for itself. */
    private int maxAcceptTimeoutMinutes = 15;

    /**
     * Minimum flat fee a restaurant may charge for PULA Ride delivery. 0 = no floor. The driver
     * receives 80% of this fee, so a floor protects drivers from uneconomic trips.
     */
    private BigDecimal minPulaRideDeliveryFee = BigDecimal.ZERO;

    /** Minutes after a food delivery is offered with no driver before the customer is told. */
    private int noDriverNotifyMinutes = 10;

    /** Minutes after which, still with no driver, the customer may cancel for a full refund. */
    private int noDriverCancelMinutes = 20;

    /** Most distinct modifiers on one cart line (keeps the signature and pricing bounded). */
    private int maxModifiersPerLine = 12;

    public int getAcceptTimeoutMinutes() { return acceptTimeoutMinutes; }
    public void setAcceptTimeoutMinutes(int v) { this.acceptTimeoutMinutes = v; }
    public int getMaxAcceptTimeoutMinutes() { return maxAcceptTimeoutMinutes; }
    public void setMaxAcceptTimeoutMinutes(int v) { this.maxAcceptTimeoutMinutes = v; }
    public BigDecimal getMinPulaRideDeliveryFee() { return minPulaRideDeliveryFee; }
    public void setMinPulaRideDeliveryFee(BigDecimal v) { this.minPulaRideDeliveryFee = v; }
    public int getNoDriverNotifyMinutes() { return noDriverNotifyMinutes; }
    public void setNoDriverNotifyMinutes(int v) { this.noDriverNotifyMinutes = v; }
    public int getNoDriverCancelMinutes() { return noDriverCancelMinutes; }
    public void setNoDriverCancelMinutes(int v) { this.noDriverCancelMinutes = v; }
    public int getMaxModifiersPerLine() { return maxModifiersPerLine; }
    public void setMaxModifiersPerLine(int v) { this.maxModifiersPerLine = v; }
}
