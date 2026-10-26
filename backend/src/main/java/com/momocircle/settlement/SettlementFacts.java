package com.momocircle.settlement;

import com.momocircle.domain.Business;
import com.momocircle.domain.Order;
import com.momocircle.domain.Payment;
import com.momocircle.domain.enums.SellerKind;
import com.momocircle.domain.enums.SettlementSourceType;

import java.math.BigDecimal;

/**
 * What a domain policy reports about one sale, read from the source record (order / stock order / booking) and its
 * payment. The engine decides nothing from client input: every field comes from the database.
 *
 * @param gross    seller revenue (delivery fees excluded)
 * @param paid     the customer's payment was provider-confirmed
 * @param earned   the domain's "completion" condition is met (settlement may become payable)
 * @param cancelled the sale was cancelled or fully reversed before it could be earned
 * @param order    the Order for retail/food, otherwise null
 */
public record SettlementFacts(SettlementSourceType sourceType, String sourceId, SellerKind sellerKind, Business seller,
                              Payment payment, String currency, BigDecimal gross, BigDecimal sourceTotal,
                              boolean paid, boolean earned, boolean cancelled, Order order) {}
