package com.momocircle.service;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.ServiceDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import com.momocircle.security.ServiceRateLimiter;
import com.momocircle.services.ServiceLifecycle;
import com.momocircle.services.ServiceQuoteCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Quotes. The provider supplies only inputs; the total is computed here (ServiceQuoteCalculator) and stored immutably.
 * The customer can accept, reject or ask for clarification, and can never supply or alter an amount. Accepting creates
 * the booking with the quote's total copied in.
 */
@Service
public class ServiceQuoteService {

    private static final Duration MAX_VALIDITY = Duration.ofDays(30);
    private static final Duration MIN_VALIDITY = Duration.ofMinutes(5);

    private final ServiceRequestRepository requests;
    private final ServiceQuoteRepository quotes;
    private final ServiceBookingRepository bookings;
    private final ServiceMaterialLineRepository materials;
    private final ServiceRequestService requestService;
    private final ServiceRateLimiter rateLimiter;
    private final NotificationService notifications;
    private final AuditLogService audit;

    public ServiceQuoteService(ServiceRequestRepository requests, ServiceQuoteRepository quotes, ServiceBookingRepository bookings,
                               ServiceMaterialLineRepository materials, ServiceRequestService requestService,
                               ServiceRateLimiter rateLimiter, NotificationService notifications, AuditLogService audit) {
        this.requests = requests;
        this.quotes = quotes;
        this.bookings = bookings;
        this.materials = materials;
        this.requestService = requestService;
        this.rateLimiter = rateLimiter;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public QuoteView submit(User provider, String requestId, SubmitQuoteRequest req) {
        ServiceRequest r = requests.findForUpdate(requestId).orElseThrow(() -> ApiException.notFound("Request not found"));
        requestService.requireProviderActor(r, provider);
        rateLimiter.check("service-quote", provider.getId(), 30, Duration.ofHours(1));
        List<ServiceQuote> existing = quotes.findByRequestIdOrderByVersionNoDesc(requestId);
        boolean live = existing.stream().anyMatch(q -> q.getStatus() == ServiceQuoteStatus.SUBMITTED);
        boolean canQuote = r.getStatus() == ServiceRequestStatus.SUBMITTED
                || (r.getStatus() == ServiceRequestStatus.QUOTED && !live);
        if (!canQuote) {
            throw ApiException.conflict("This request cannot be quoted while it is " + r.getStatus().name().toLowerCase()
                    + (live ? " with a quote awaiting a decision" : ""));
        }
        Instant now = Instant.now();
        if (req.validUntil().isBefore(now.plus(MIN_VALIDITY)) || req.validUntil().isAfter(now.plus(MAX_VALIDITY))) {
            throw ApiException.badRequest("A quote must be valid for between 5 minutes and 30 days");
        }
        // Materials come from PULA Stock lines priced by the server; the provider cannot type a materials amount.
        BigDecimal materialsAmount = materials.findByRequestIdOrderByCreatedAtAsc(requestId).stream()
                .filter(m -> m.getSuppliedBy() == MaterialSupplier.PROVIDER)
                .map(m -> m.getUnitPriceSnapshot().multiply(BigDecimal.valueOf(m.getQuantityPacks())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ServiceListing listing = r.getListing();
        ServiceQuoteCalculator.Result calc;
        try {
            calc = ServiceQuoteCalculator.compute(listing.getPricingModel(), listing.getPrice(), req.hours(), req.labourAmount(),
                    req.deliveryAmount(), req.otherAmount(), req.otherDescription(), materialsAmount);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        for (ServiceQuote q : existing) {
            if (q.getStatus() == ServiceQuoteStatus.SUBMITTED || q.getStatus() == ServiceQuoteStatus.CLARIFICATION_REQUESTED) {
                q.setStatus(ServiceQuoteStatus.SUPERSEDED);
                q.setDecidedAt(now);
                quotes.save(q);
            }
        }
        ServiceQuote q = new ServiceQuote();
        q.setRequest(r);
        q.setBusiness(r.getBusiness());
        q.setVersionNo(existing.isEmpty() ? 1 : existing.get(0).getVersionNo() + 1);
        q.setStatus(ServiceQuoteStatus.SUBMITTED);
        q.setHours(calc.hours());
        q.setLabourAmount(calc.labour());
        q.setMaterialsAmount(calc.materials());
        q.setDeliveryAmount(calc.delivery());
        q.setOtherAmount(calc.other());
        q.setOtherDescription(req.otherDescription() == null || req.otherDescription().isBlank() ? null : req.otherDescription().trim());
        q.setTotal(calc.total());
        q.setCurrency(listing.getCurrency());
        q.setValidUntil(req.validUntil());
        q.setNotes(req.notes() == null || req.notes().isBlank() ? null : req.notes().trim());
        q = quotes.save(q);
        if (r.getStatus() != ServiceRequestStatus.QUOTED) {
            requestService.transition(r, ServiceRequestStatus.QUOTED);
            requests.save(r);
        }
        audit.record(provider, "SERVICE_QUOTE_SUBMITTED", "SERVICE_REQUEST", requestId, "v" + q.getVersionNo() + " total=" + q.getTotal());
        notifications.notify(r.getCustomer(), NotificationType.SERVICE_QUOTE_UPDATE,
                r.getBusiness().getName() + " sent you a quote for " + listing.getName(), r.getId());
        return ServiceViews.quote(q);
    }

    @Transactional
    public BookingView accept(User customer, String requestId, String quoteId) {
        ServiceRequest r = requestService.lockCustomerRequest(customer, requestId);
        ServiceQuote q = quotes.findByIdAndRequestId(quoteId, requestId).orElseThrow(() -> ApiException.notFound("Quote not found"));
        if (r.getStatus() != ServiceRequestStatus.QUOTED) {
            throw ApiException.conflict("This request is " + r.getStatus().name().toLowerCase() + " and has nothing to accept");
        }
        if (q.getStatus() != ServiceQuoteStatus.SUBMITTED) {
            throw ApiException.conflict("This quote is " + q.getStatus().name().toLowerCase() + " and cannot be accepted");
        }
        List<ServiceQuote> all = quotes.findByRequestIdOrderByVersionNoDesc(requestId);
        if (all.get(0).getVersionNo() != q.getVersionNo()) {
            throw ApiException.conflict("A newer quote exists for this request");
        }
        Instant now = Instant.now();
        if (q.getValidUntil().isBefore(now)) {
            throw ApiException.conflict("This quote has expired");
        }
        if (bookings.findByRequestId(requestId).isPresent()) {
            throw ApiException.conflict("This request is already booked");
        }
        if (!ServiceLifecycle.canTransition(q.getStatus(), ServiceQuoteStatus.ACCEPTED)) {
            throw ApiException.conflict("This quote cannot be accepted");
        }
        q.setStatus(ServiceQuoteStatus.ACCEPTED);
        q.setDecidedAt(now);
        quotes.save(q);

        ServiceBooking b = new ServiceBooking();
        b.setRequest(r);
        b.setQuote(q);
        b.setCustomer(r.getCustomer());
        b.setBusiness(r.getBusiness());
        b.setListing(r.getListing());
        b.setStatus(ServiceBookingStatus.BOOKED);
        b.setTotal(q.getTotal());           // copied from the server-computed quote; nothing from the client
        b.setCurrency(q.getCurrency());
        b.setScheduledDate(r.getPreferredDate());
        b.setScheduledTime(r.getPreferredTime());
        b = bookings.save(b);

        requestService.transition(r, ServiceRequestStatus.BOOKED);
        requests.save(r);
        audit.record(customer, "SERVICE_QUOTE_ACCEPTED", "SERVICE_BOOKING", b.getId(), "total=" + b.getTotal());
        notifications.notify(r.getBusiness().getOwner(), NotificationType.SERVICE_BOOKING_UPDATE,
                r.getCustomer().getFullName() + " accepted your quote", b.getId());
        return ServiceViews.booking(b, false, false,
                materials.findByRequestIdOrderByCreatedAtAsc(requestId).stream().map(ServiceViews::material).toList());
    }

    @Transactional
    public QuoteView reject(User customer, String requestId, String quoteId) {
        ServiceRequest r = requestService.lockCustomerRequest(customer, requestId);
        ServiceQuote q = decidable(r, quoteId);
        q.setStatus(ServiceQuoteStatus.REJECTED);
        q.setDecidedAt(Instant.now());
        quotes.save(q);
        audit.record(customer, "SERVICE_QUOTE_REJECTED", "SERVICE_REQUEST", requestId, "v" + q.getVersionNo());
        notifications.notify(r.getBusiness().getOwner(), NotificationType.SERVICE_QUOTE_UPDATE,
                r.getCustomer().getFullName() + " declined your quote", r.getId());
        return ServiceViews.quote(q);
    }

    @Transactional
    public QuoteView requestClarification(User customer, String requestId, String quoteId, ClarificationRequest req) {
        ServiceRequest r = requestService.lockCustomerRequest(customer, requestId);
        ServiceQuote q = decidable(r, quoteId);
        if (q.getStatus() != ServiceQuoteStatus.SUBMITTED) {
            throw ApiException.conflict("Clarification was already requested for this quote");
        }
        q.setStatus(ServiceQuoteStatus.CLARIFICATION_REQUESTED);
        q.setCustomerMessage(req.message().trim());
        quotes.save(q);
        audit.record(customer, "SERVICE_QUOTE_CLARIFICATION", "SERVICE_REQUEST", requestId, "v" + q.getVersionNo());
        notifications.notify(r.getBusiness().getOwner(), NotificationType.SERVICE_QUOTE_UPDATE,
                r.getCustomer().getFullName() + " has a question about your quote", r.getId());
        return ServiceViews.quote(q);
    }

    private ServiceQuote decidable(ServiceRequest r, String quoteId) {
        ServiceQuote q = quotes.findByIdAndRequestId(quoteId, r.getId()).orElseThrow(() -> ApiException.notFound("Quote not found"));
        if (r.getStatus() != ServiceRequestStatus.QUOTED
                || (q.getStatus() != ServiceQuoteStatus.SUBMITTED && q.getStatus() != ServiceQuoteStatus.CLARIFICATION_REQUESTED)) {
            throw ApiException.conflict("This quote is " + q.getStatus().name().toLowerCase());
        }
        return q;
    }
}
