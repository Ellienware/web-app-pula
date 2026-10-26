package com.momocircle.settlement;

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

/** Retail: the seller earns the order's goods revenue once the order is COMPLETED. Delivery fees are not seller revenue. */
@Component
public class RetailSettlementPolicy implements SettlementPolicy {

    private final OrderRepository orders;
    private final PaymentRefundRepository refunds;
    private final MarketplaceSettlementProperties props;

    public RetailSettlementPolicy(OrderRepository orders, PaymentRefundRepository refunds, MarketplaceSettlementProperties props) {
        this.orders = orders;
        this.refunds = refunds;
        this.props = props;
    }

    @Override public SettlementSourceType sourceType() { return SettlementSourceType.RETAIL_ORDER; }

    @Override
    public SettlementFacts facts(String sourceId) {
        Order o = orders.findById(sourceId).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (o.isFood()) {
            throw ApiException.badRequest("Food orders are settled by the food policy");
        }
        boolean paid = o.getPayment() != null && o.getPayment().getStatus() == PaymentStatus.SUCCESSFUL;
        boolean cancelled = o.getStatus() == OrderStatus.CANCELLED || o.getStatus() == OrderStatus.REFUNDED;
        return new SettlementFacts(sourceType(), o.getId(), SellerKind.RETAIL_BUSINESS, o.getBusiness(), o.getPayment(),
                o.getCurrency(), o.getSubtotal(), o.getTotal(), paid, o.getStatus() == OrderStatus.COMPLETED, cancelled, o);
    }

    @Override public Rates rates() { return props.getRetail().rates(); }
    @Override public int disputeWindowMinutes() { return props.getRetail().getDisputeWindowMinutes(); }

    @Override
    public BigDecimal refunded(SettlementFacts f) {
        BigDecimal v = refunds.sumRefundedByOrder(f.sourceId());
        return v == null ? BigDecimal.ZERO : v;
    }
}
