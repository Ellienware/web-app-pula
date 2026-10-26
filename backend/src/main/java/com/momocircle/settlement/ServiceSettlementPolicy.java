package com.momocircle.settlement;

import com.momocircle.domain.ServiceBooking;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.SellerKind;
import com.momocircle.domain.enums.ServiceBookingStatus;
import com.momocircle.domain.enums.SettlementSourceType;
import com.momocircle.exception.ApiException;
import com.momocircle.finance.MarketplaceSettlementCalculator.Rates;
import com.momocircle.repository.ServiceBookingRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Services: the PROVIDER earns the accepted quote total once the job is COMPLETED and the customer's payment has been
 * confirmed (payment is due after completion in this marketplace, so both conditions are required). Materials bought
 * through PULA Stock are the supplier's separate stock-order settlement, not part of this one's gross beyond the quote.
 */
@Component
public class ServiceSettlementPolicy implements SettlementPolicy {

    private final ServiceBookingRepository bookings;
    private final MarketplaceSettlementProperties props;

    public ServiceSettlementPolicy(ServiceBookingRepository bookings, MarketplaceSettlementProperties props) {
        this.bookings = bookings;
        this.props = props;
    }

    @Override public SettlementSourceType sourceType() { return SettlementSourceType.SERVICE_BOOKING; }

    @Override
    public SettlementFacts facts(String sourceId) {
        ServiceBooking b = bookings.findById(sourceId).orElseThrow(() -> ApiException.notFound("Booking not found"));
        boolean paid = b.getPayment() != null && b.getPayment().getStatus() == PaymentStatus.SUCCESSFUL;
        boolean completed = b.getStatus() == ServiceBookingStatus.COMPLETED;
        return new SettlementFacts(sourceType(), b.getId(), SellerKind.SERVICE_PROVIDER, b.getBusiness(), b.getPayment(),
                b.getCurrency(), b.getTotal(), b.getTotal(), paid, completed && paid,
                b.getStatus() == ServiceBookingStatus.CANCELLED, null);
    }

    @Override public Rates rates() { return props.getService().rates(); }
    @Override public int disputeWindowMinutes() { return props.getService().getDisputeWindowMinutes(); }
    @Override public BigDecimal refunded(SettlementFacts f) { return BigDecimal.ZERO; }
}
