package com.momocircle.risk;

/** Payment-initiation contexts the transaction-risk layer protects. One per distinct code path found in the Phase 7 audit. */
public enum RiskType {
    STOCK_ORDER,        // wholesale stock + bulk-purchase shares (StockPaymentService)
    CONSUMER_CHECKOUT,  // retail + food, Paystack (ConsumerPaystackCheckoutService)
    LEGACY_ORDER,       // retail/food via the legacy PaymentProvider (OrderService)
    SERVICE_BOOKING,    // services marketplace (ServiceBookingService)
    CONTRIBUTION,       // circle/stokvel contribution (ContributionService)
    TAXI                // PULA Ride fare; integration is flag-controlled and OFF by default
}
