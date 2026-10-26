package com.momocircle.risk;

import java.math.BigDecimal;

/**
 * Normalised facts about ONE transaction intent. Every nullable field means "this signal is not available": the engine
 * treats it as neutral and records it as unavailable. It never guesses.
 *
 * @param ownershipValid     false when the order/booking/contribution does not belong to the user (caller already verified; defence in depth)
 * @param userActive         null if unknown
 * @param recipientActive    false when the merchant/business is deactivated; null if there is no recipient business
 * @param recipientVerified  there is NO verified-merchant data in PULA today (registration is self-declared), so callers pass null
 * @param fundsSufficient    null unless a caller can really know (provider-hosted checkout cannot)
 * @param attemptsLastHour   assessments by this user for this type in the last hour, from the risk layer's own history
 * @param failuresLast24h    provider/initiation failures recorded by the risk layer for this user in 24h
 * @param historyCount       prior successfully-initiated intents of this type by this user (null = none recorded)
 * @param historyAverage     average amount of those intents
 * @param firstTimeRecipient true when the user has never initiated a payment to this recipient through the risk layer
 * @param recentHighScamAlerts HIGH ScamShield assessments the user ran in the last 24h (null = ScamShield not available)
 * @param simulated          true when the payment context is sandbox/simulated funds
 */
public record RiskContext(
        RiskType type,
        BigDecimal amount,
        String currency,
        boolean ownershipValid,
        Boolean userActive,
        Boolean recipientActive,
        Boolean recipientVerified,
        Boolean fundsSufficient,
        Integer attemptsLastHour,
        Integer failuresLast24h,
        Integer historyCount,
        BigDecimal historyAverage,
        Boolean firstTimeRecipient,
        Integer recentHighScamAlerts,
        boolean simulated) {}
