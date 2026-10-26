package com.momocircle.service;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.ServiceDtos.*;

import java.math.BigDecimal;
import java.util.List;

/** Entity -> DTO mapping for the services marketplace. Pure; no repositories. */
final class ServiceViews {

    private ServiceViews() {}

    static CategoryView category(ServiceCategory c) {
        return new CategoryView(c.getId(), c.getParent() == null ? null : c.getParent().getId(), c.getName(), c.getSlug(),
                c.getSortOrder(), c.isActive());
    }

    static ListingView listing(ServiceListing l, ServiceProviderProfile p) {
        return new ListingView(l.getId(), l.getBusiness().getId(), l.getBusiness().getName(), l.getCategory().getId(),
                l.getCategory().getName(), l.getName(), l.getDescription(), l.getPricingModel(), l.getPrice(), l.getCurrency(),
                l.getDurationMinutes(), l.getMaterialsResponsibility(), l.getStatus(), l.isHiddenByAdmin(),
                List.copyOf(l.getServiceAreas()), List.copyOf(l.getImageUrls()), l.getRatingAverage(), l.getRatingCount(),
                p == null ? ProviderVerificationStatus.UNVERIFIED : p.getVerificationStatus(),
                p != null && p.isAcceptingRequests());
    }

    static ProviderProfileView profile(ServiceProviderProfile p, List<ListingView> listings) {
        Business b = p.getBusiness();
        return new ProviderProfileView(b.getId(), b.getName(), b.getContactPhone(), p.getContactEmail(), p.getHeadline(),
                p.getAbout(), p.getAvailabilityNote(), p.isAcceptingRequests(), p.getVerificationStatus(), p.isSuspended(),
                List.copyOf(p.getServiceAreas()), List.copyOf(p.getPortfolioUrls()), b.getRatingAverage(), b.getRatingCount(),
                listings);
    }

    static QuoteView quote(ServiceQuote q) {
        return new QuoteView(q.getId(), q.getRequest().getId(), q.getVersionNo(), q.getStatus(), q.getHours(),
                q.getLabourAmount(), q.getMaterialsAmount(), q.getDeliveryAmount(), q.getOtherAmount(),
                q.getOtherDescription(), q.getTotal(), q.getCurrency(), q.getValidUntil(), q.getNotes(),
                q.getCustomerMessage(), q.getCreatedAt());
    }

    static MaterialLineView material(ServiceMaterialLine m) {
        WholesaleProduct p = m.getWholesaleProduct();
        BigDecimal line = m.getUnitPriceSnapshot().multiply(BigDecimal.valueOf(m.getQuantityPacks()));
        return new MaterialLineView(m.getId(), p.getId(), p.getName(), p.getSupplierBusiness().getId(), m.getQuantityPacks(),
                m.getSuppliedBy(), m.getUnitPriceSnapshot(), line, m.getStockOrder() == null ? null : m.getStockOrder().getId(),
                m.getAddedBy().getId());
    }

    static RequestView request(ServiceRequest r, List<QuoteView> quotes, List<MaterialLineView> materials, String bookingId) {
        ServiceListing l = r.getListing();
        return new RequestView(r.getId(), l.getId(), l.getName(), r.getBusiness().getId(), r.getBusiness().getName(),
                r.getCustomer().getId(), r.getCustomer().getFullName(), r.getStatus(), r.getDescription(), r.getLocationText(),
                r.getPreferredDate(), r.getPreferredTime(), r.getRequirements(), r.getMaterialsResponsibility(),
                l.getPricingModel(), l.getPrice(), l.getCurrency(), quotes, materials, bookingId, r.getCreatedAt());
    }

    static String paymentStatus(Payment p) {
        if (p == null) return "UNPAID";
        return switch (p.getStatus()) {
            case SUCCESSFUL -> "PAID";
            case FAILED -> "FAILED";
            default -> "PROCESSING";
        };
    }

    static BookingView booking(ServiceBooking b, boolean canReview, boolean reviewed, List<MaterialLineView> materials) {
        return new BookingView(b.getId(), b.getRequest().getId(), b.getQuote().getId(), b.getListing().getId(),
                b.getListing().getName(), b.getBusiness().getId(), b.getBusiness().getName(), b.getCustomer().getId(),
                b.getCustomer().getFullName(), b.getStatus(), b.getTotal(), b.getCurrency(), b.getScheduledDate(),
                b.getScheduledTime(), b.getStartedAt(), b.getCompletedAt(), b.getCancelledAt(), b.getCancelReason(),
                paymentStatus(b.getPayment()), b.getSaveToBuyRequestId(), canReview, reviewed, materials, b.getCreatedAt());
    }
}
