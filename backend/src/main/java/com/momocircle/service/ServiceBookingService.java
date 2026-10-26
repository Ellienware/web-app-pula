package com.momocircle.service;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.ReviewDtos.ReviewResponse;
import com.momocircle.dto.ServiceDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import com.momocircle.services.ServiceLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Bookings (jobs): start, complete, cancel, pay and review. Payment REUSES the existing market payment path through
 * {@link ServicePaymentGateway}; there is no services payment provider, checkout or ledger. The amount charged is
 * always the booking total copied from the accepted, server-computed quote.
 *
 * Timing: payment is due after completion (Find -> Request -> Quote -> Accept -> Booking -> Job -> Completion ->
 * Payment -> Review). Reviews require a COMPLETED booking whose payment succeeded.
 */
@Service
public class ServiceBookingService {
    /** Phase 7 transaction-risk gate. Always injected in a running application; null only in hand-constructed unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.risk.PaymentGate riskGate;


    private final ServiceBookingRepository bookings;
    private final ServiceRequestRepository requests;
    private final ServiceMaterialLineRepository materials;
    private final ServiceProviderProfileRepository profiles;
    private final PaymentRepository payments;
    private final ReviewRepository reviews;
    private final ReviewService reviewService;
    private final ServicePaymentGateway gateway;
    private final NotificationService notifications;
    private final AuditLogService audit;

    /** Optional (field-injected) so existing construction is unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SaveToBuyService saveToBuy;

    /** Shared settlement engine: a confirmed payment for a COMPLETED job opens and earns the provider's settlement. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.settlement.MarketplaceSettlementService marketplaceSettlement;

    public ServiceBookingService(ServiceBookingRepository bookings, ServiceRequestRepository requests,
                                 ServiceMaterialLineRepository materials, ServiceProviderProfileRepository profiles,
                                 PaymentRepository payments, ReviewRepository reviews, ReviewService reviewService,
                                 ServicePaymentGateway gateway, NotificationService notifications, AuditLogService audit) {
        this.bookings = bookings;
        this.requests = requests;
        this.materials = materials;
        this.profiles = profiles;
        this.payments = payments;
        this.reviews = reviews;
        this.reviewService = reviewService;
        this.gateway = gateway;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public BookingView get(User viewer, String bookingId) {
        return view(requireParty(bookings.findById(bookingId).orElseThrow(() -> ApiException.notFound("Booking not found")), viewer), viewer);
    }

    @Transactional(readOnly = true)
    public List<BookingView> mine(User customer) {
        return bookings.findByCustomerIdOrderByCreatedAtDesc(customer.getId()).stream().map(b -> view(b, customer)).toList();
    }

    @Transactional(readOnly = true)
    public List<BookingView> forProvider(User owner, String businessId) {
        return bookings.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .filter(b -> b.getBusiness().getOwner().getId().equals(owner.getId()))
                .map(b -> view(b, owner)).toList();
    }

    /** Admin visibility (read-only): there is no admin action that moves money or changes a booking. */
    @Transactional(readOnly = true)
    public List<BookingView> adminList(User admin, org.springframework.data.domain.Pageable pageable) {
        return bookings.findAllByOrderByCreatedAtDesc(pageable).map(b -> view(b, admin)).getContent();
    }

    @Transactional
    public BookingView start(User provider, String bookingId) {
        ServiceBooking b = lockProvider(provider, bookingId);
        move(b, ServiceBookingStatus.IN_PROGRESS);
        b.setStartedAt(Instant.now());
        syncRequest(b, ServiceRequestStatus.IN_PROGRESS);
        bookings.save(b);
        audit.record(provider, "SERVICE_BOOKING_STARTED", "SERVICE_BOOKING", b.getId(), "");
        notifications.notify(b.getCustomer(), NotificationType.SERVICE_BOOKING_UPDATE, b.getBusiness().getName() + " started your job", b.getId());
        return view(b, provider);
    }

    @Transactional
    public BookingView complete(User provider, String bookingId) {
        ServiceBooking b = lockProvider(provider, bookingId);
        move(b, ServiceBookingStatus.COMPLETED);
        b.setCompletedAt(Instant.now());
        syncRequest(b, ServiceRequestStatus.COMPLETED);
        bookings.save(b);
        audit.record(provider, "SERVICE_BOOKING_COMPLETED", "SERVICE_BOOKING", b.getId(), "total=" + b.getTotal());
        notifications.notify(b.getCustomer(), NotificationType.SERVICE_BOOKING_UPDATE,
                b.getBusiness().getName() + " marked your job complete. Payment is now due.", b.getId());
        return view(b, provider);
    }

    @Transactional
    public BookingView cancel(User actor, String bookingId, String reason) {
        ServiceBooking b = bookings.findForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking not found"));
        requireParty(b, actor);
        boolean provider = b.getBusiness().getOwner().getId().equals(actor.getId());
        if (provider) {
            requireProviderNotSuspended(b);
        }
        if (b.getPayment() != null && b.getPayment().getStatus() != PaymentStatus.FAILED) {
            throw ApiException.conflict("A booking with a payment in progress or paid cannot be cancelled here");
        }
        move(b, ServiceBookingStatus.CANCELLED);
        b.setCancelledAt(Instant.now());
        b.setCancelledBy(actor);
        b.setCancelReason(reason == null ? null : reason.trim());
        syncRequest(b, ServiceRequestStatus.CANCELLED);
        bookings.save(b);
        audit.record(actor, "SERVICE_BOOKING_CANCELLED", "SERVICE_BOOKING", b.getId(), reason);
        User other = provider ? b.getCustomer() : b.getBusiness().getOwner();
        notifications.notify(other, NotificationType.SERVICE_BOOKING_UPDATE, "A service booking was cancelled", b.getId());
        return view(b, actor);
    }

    /**
     * Starts payment of a COMPLETED booking through the existing market payment path. Optional Save-to-Buy: the same
     * validate/attach hooks the retail, food and stock checkouts use; a stokvel purchase cannot exceed what was approved.
     */
    @Transactional
    public PaymentSessionView pay(User customer, String bookingId, PayRequest req) {
        return startPayment(customer, bookingId, req, false);
    }

    /**
     * Starts SIMULATED PULA Money wallet payment for the customer's own completed booking. Same ownership, state, risk and
     * Save-to-Buy rules as {@link #pay}; charges nothing here (the debit is done by ServiceWalletCheckoutService after an
     * explicit confirmation). A new attempt after a FAILED one gets a new payment row and therefore a new ledger key.
     */
    @Transactional
    public PaymentSessionView startWalletPayment(User customer, String bookingId, PayRequest req) {
        return startPayment(customer, bookingId, req, true);
    }

    private PaymentSessionView startPayment(User customer, String bookingId, PayRequest req, boolean wallet) {
        ServiceBooking b = bookings.findForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking not found"));
        if (!b.getCustomer().getId().equals(customer.getId())) {
            throw ApiException.notFound("Booking not found");
        }
        if (b.getStatus() != ServiceBookingStatus.COMPLETED) {
            throw ApiException.conflict("A booking can be paid once the provider marks it complete");
        }
        Payment existing = b.getPayment();
        if (existing != null) {
            if (existing.getStatus() == PaymentStatus.SUCCESSFUL) {
                throw ApiException.conflict("This booking is already paid");
            }
            if (existing.getStatus() != PaymentStatus.FAILED) {
                return session(b, existing); // resume the same checkout, never a second charge
            }
        }
        int attempt = b.getPaymentAttempts() + 1;
        String key = "service-booking:" + b.getId() + ":" + attempt;
        // Phase 7 transaction-risk gate (ownership and booking state were verified above).
        boolean riskSimulated = wallet || (gateway.providerLabel() != null && gateway.providerLabel().toLowerCase().contains("mock"));
        com.momocircle.risk.GateTicket riskTicket = riskGate == null ? null : riskGate.authorise(customer,
                new com.momocircle.risk.GateRequest(com.momocircle.risk.RiskType.SERVICE_BOOKING, key, b.getTotal(), b.getCurrency(),
                        b.getBusiness() == null ? null : b.getBusiness().getId(), b.getBusiness() == null ? null : b.getBusiness().getId(),
                        true, b.getBusiness() == null ? null : b.getBusiness().isActive(), riskSimulated));
        com.momocircle.domain.SaveToBuyRequest s2b = null;
        if (saveToBuy != null && req != null && req.saveToBuyRequestId() != null && !req.saveToBuyRequestId().isBlank()) {
            s2b = saveToBuy.validateForCheckout(customer, req.saveToBuyRequestId(), SaveToBuyVertical.SERVICE, b.getTotal(), key);
        }
        String reference = "PULA-SV-" + b.getId() + "-" + attempt;
        if (riskGate != null) riskGate.bind(customer, riskTicket, reference, b.getTotal(), b.getCurrency(), riskSimulated);
        ServicePaymentGateway.Session session;
        try {
            session = wallet ? new ServicePaymentGateway.Session(reference, null)
                    : gateway.start(reference, b.getTotal(), b.getCurrency(), customer.getEmail());
        } catch (RuntimeException e) {
            if (riskGate != null) riskGate.recordOutcome(riskTicket, false, "provider initiation failed");
            throw e;
        }
        if (riskGate != null) riskGate.recordOutcome(riskTicket, true, "checkout started");

        Payment payment = new Payment();
        payment.setAmount(b.getTotal());
        payment.setCurrency(b.getCurrency());
        payment.setProvider(wallet ? com.momocircle.payment.PaymentRails.WALLET : gateway.providerLabel());
        payment.setProviderReference(reference);
        payment.setIdempotencyKey(key);
        payment.setPurpose(PaymentPurpose.MARKET_PAYMENT);
        payment.setCheckoutUrl(session.checkoutUrl());
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment = payments.save(payment);

        b.setPayment(payment);
        b.setPaymentAttempts(attempt);
        if (s2b != null) {
            b.setSaveToBuyRequestId(s2b.getId());
        }
        bookings.save(b);
        if (s2b != null) {
            saveToBuy.attachOrder(s2b, RelatedOrderKind.SERVICE_BOOKING, b.getId(), key);
        }
        audit.record(customer, wallet ? "SERVICE_WALLET_PAYMENT_STARTED" : "SERVICE_PAYMENT_STARTED", "SERVICE_BOOKING", b.getId(), reference);
        return session(b, payment);
    }

    /** Called by StockPaymentService.confirmByReference after the provider has verified the payment. Idempotent. */
    @Transactional
    public void onPaymentSuccess(ServiceBooking b) {
        if (b.getPayment() == null) return;
        if (saveToBuy != null && b.getSaveToBuyRequestId() != null) {
            saveToBuy.onOrderPaid(RelatedOrderKind.SERVICE_BOOKING, b.getId(), b.getTotal());
        }
        if (marketplaceSettlement != null) {
            marketplaceSettlement.openSafely(com.momocircle.domain.enums.SettlementSourceType.SERVICE_BOOKING, b.getId());
        }
        notifications.notify(b.getBusiness().getOwner(), NotificationType.PAYMENT_SUCCESS,
                "Payment received for a completed service job.", b.getId());
        notifications.notify(b.getCustomer(), NotificationType.PAYMENT_SUCCESS, "Payment received. You can now review the service.", b.getId());
        audit.record(b.getCustomer(), "SERVICE_PAYMENT_CONFIRMED", "SERVICE_BOOKING", b.getId(), "total=" + b.getTotal());
    }

    @Transactional
    public void onPaymentFailed(ServiceBooking b) {
        if (saveToBuy != null && b.getSaveToBuyRequestId() != null) {
            saveToBuy.onOrderFailed(RelatedOrderKind.SERVICE_BOOKING, b.getId());
        }
        notifications.notify(b.getCustomer(), NotificationType.PAYMENT_FAILED, "Payment failed for your service booking. You can try again.", b.getId());
    }

    @Transactional
    public List<ReviewResponse> review(User customer, String bookingId, ServiceReviewRequest req) {
        ServiceBooking b = bookings.findById(bookingId).orElseThrow(() -> ApiException.notFound("Booking not found"));
        if (!b.getCustomer().getId().equals(customer.getId())) {
            throw ApiException.notFound("Booking not found");
        }
        if (!canReview(b)) {
            throw ApiException.conflict("You can review a service after it is completed and paid");
        }
        List<ReviewResponse> out = new java.util.ArrayList<>();
        out.add(reviewService.createForServiceBooking(customer, b, ReviewTargetType.BUSINESS, b.getBusiness().getId(),
                req.rating(), req.text()));
        if (Boolean.TRUE.equals(req.rateListing())) {
            out.add(reviewService.createForServiceBooking(customer, b, ReviewTargetType.SERVICE, b.getListing().getId(),
                    req.rating(), req.text()));
        }
        audit.record(customer, "SERVICE_REVIEW_CREATED", "SERVICE_BOOKING", b.getId(), "rating=" + req.rating());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    static boolean canReview(ServiceBooking b) {
        return b.getStatus() == ServiceBookingStatus.COMPLETED && b.getPayment() != null
                && b.getPayment().getStatus() == PaymentStatus.SUCCESSFUL;
    }

    private ServiceBooking requireParty(ServiceBooking b, User u) {
        boolean customer = b.getCustomer().getId().equals(u.getId());
        boolean provider = b.getBusiness().getOwner().getId().equals(u.getId());
        if (!customer && !provider) {
            throw ApiException.notFound("Booking not found"); // no existence leak
        }
        return b;
    }

    private ServiceBooking lockProvider(User provider, String bookingId) {
        ServiceBooking b = bookings.findForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking not found"));
        if (!b.getBusiness().getOwner().getId().equals(provider.getId())) {
            throw ApiException.notFound("Booking not found");
        }
        requireProviderNotSuspended(b);
        return b;
    }

    private void requireProviderNotSuspended(ServiceBooking b) {
        ServiceProviderProfile p = profiles.findByBusinessId(b.getBusiness().getId()).orElse(null);
        if (p == null || p.isSuspended()) {
            throw ApiException.forbidden("This provider profile is suspended");
        }
    }

    private void move(ServiceBooking b, ServiceBookingStatus to) {
        if (!ServiceLifecycle.canTransition(b.getStatus(), to)) {
            throw ApiException.conflict("A booking that is " + b.getStatus().name().toLowerCase() + " cannot become "
                    + to.name().toLowerCase());
        }
        b.setStatus(to);
    }

    private void syncRequest(ServiceBooking b, ServiceRequestStatus to) {
        ServiceRequest r = b.getRequest();
        if (ServiceLifecycle.canTransition(r.getStatus(), to)) {
            r.setStatus(to);
            requests.save(r);
        }
    }

    private PaymentSessionView session(ServiceBooking b, Payment p) {
        return new PaymentSessionView(b.getId(), p.getProviderReference(), p.getCheckoutUrl(), p.getStatus(), b.getTotal(), b.getCurrency());
    }

    BookingView view(ServiceBooking b, User viewer) {
        boolean can = canReview(b) && b.getCustomer().getId().equals(viewer.getId());
        boolean reviewed = reviews.existsByDedupeKey(viewer.getId() + "|SERVICE_BOOKING|" + b.getId() + "|" + ReviewTargetType.BUSINESS
                + "|" + b.getBusiness().getId());
        List<MaterialLineView> ms = materials.findByRequestIdOrderByCreatedAtAsc(b.getRequest().getId()).stream()
                .map(ServiceViews::material).toList();
        return ServiceViews.booking(b, can && !reviewed, reviewed, ms);
    }
}
