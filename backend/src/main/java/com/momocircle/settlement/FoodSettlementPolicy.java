package com.momocircle.settlement;

import com.momocircle.config.SettlementProperties;
import com.momocircle.domain.Order;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.SellerKind;
import com.momocircle.domain.enums.SettlementSourceType;
import com.momocircle.exception.ApiException;
import com.momocircle.finance.MarketplaceSettlementCalculator.Rates;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRefundRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Food: reads the existing app.food.settlement.* configuration, so Food rates and delay stay where they are. Food's
 * special lifecycle (accept/reject, no-driver merchant protection) remains in FoodSettlementService, which now uses the
 * shared payout machinery; this policy lets the shared engine, reconciliation and reports treat food like every domain.
 */
@Component
public class FoodSettlementPolicy implements SettlementPolicy {

    private final OrderRepository orders;
    private final PaymentRefundRepository refunds;
    private final SettlementProperties props;

    public FoodSettlementPolicy(OrderRepository orders, PaymentRefundRepository refunds, SettlementProperties props) {
        this.orders = orders;
        this.refunds = refunds;
        this.props = props;
    }

    @Override public SettlementSourceType sourceType() { return SettlementSourceType.FOOD_ORDER; }

    @Override
    public SettlementFacts facts(String sourceId) {
        Order o = orders.findById(sourceId).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!o.isFood()) {
            throw ApiException.badRequest("Not a food order");
        }
        boolean paid = o.getPayment() != null && o.getPayment().getStatus() == PaymentStatus.SUCCESSFUL;
        boolean cancelled = o.getStatus() == OrderStatus.CANCELLED || o.getStatus() == OrderStatus.REFUNDED;
        return new SettlementFacts(sourceType(), o.getId(), SellerKind.RESTAURANT, o.getBusiness(), o.getPayment(),
                o.getCurrency(), o.getSubtotal(), o.getTotal(), paid, o.getStatus() == OrderStatus.COMPLETED, cancelled, o);
    }

    @Override public Rates rates() { return Rates.of(props.getCommissionRate(), props.getFixedFee()); }
    @Override public int disputeWindowMinutes() { return props.getDelayMinutes(); }

    @Override
    public BigDecimal refunded(SettlementFacts f) {
        BigDecimal v = refunds.sumRefundedByOrder(f.sourceId());
        return v == null ? BigDecimal.ZERO : v;
    }
}
