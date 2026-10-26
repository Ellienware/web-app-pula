package com.momocircle.settlement;

import com.momocircle.domain.StockOrder;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.SellerKind;
import com.momocircle.domain.enums.SettlementSourceType;
import com.momocircle.domain.enums.StockOrderStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.finance.MarketplaceSettlementCalculator.Rates;
import com.momocircle.repository.StockOrderRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Stock: the SUPPLIER earns the goods subtotal once the order is DELIVERED (collected or delivered). The delivery fee is
 * not supplier revenue; it belongs to the delivery engine. No refund path exists for stock orders today, so refunds are
 * zero here and corrections are made with admin adjustments.
 */
@Component
public class StockSettlementPolicy implements SettlementPolicy {

    private final StockOrderRepository stockOrders;
    private final MarketplaceSettlementProperties props;

    public StockSettlementPolicy(StockOrderRepository stockOrders, MarketplaceSettlementProperties props) {
        this.stockOrders = stockOrders;
        this.props = props;
    }

    @Override public SettlementSourceType sourceType() { return SettlementSourceType.STOCK_ORDER; }

    @Override
    public SettlementFacts facts(String sourceId) {
        StockOrder o = stockOrders.findById(sourceId).orElseThrow(() -> ApiException.notFound("Stock order not found"));
        boolean paid = o.getPayment() != null && o.getPayment().getStatus() == PaymentStatus.SUCCESSFUL;
        boolean cancelled = o.getStatus() == StockOrderStatus.CANCELLED || o.getStatus() == StockOrderStatus.REFUNDED;
        return new SettlementFacts(sourceType(), o.getId(), SellerKind.SUPPLIER, o.getSupplierBusiness(), o.getPayment(),
                o.getCurrency(), o.getSubtotal(), o.getTotal(), paid, o.getStatus() == StockOrderStatus.DELIVERED, cancelled, null);
    }

    @Override public Rates rates() { return props.getStock().rates(); }
    @Override public int disputeWindowMinutes() { return props.getStock().getDisputeWindowMinutes(); }
    @Override public BigDecimal refunded(SettlementFacts f) { return BigDecimal.ZERO; }
}
