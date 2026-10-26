package com.momocircle.service;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.ServiceDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import com.momocircle.security.ServiceRateLimiter;
import com.momocircle.services.ServiceInput;
import com.momocircle.services.ServiceLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Service requests: customer -> provider. A request is visible only to its customer and to the provider business that
 * owns the listing; anyone else gets 404. Status changes always go through {@link ServiceLifecycle}.
 */
@Service
public class ServiceRequestService {

    static final ZoneId ZONE = ZoneId.of("Africa/Johannesburg");
    static final Duration REQUEST_VALIDITY = Duration.ofDays(14);
    private static final Set<ServiceRequestStatus> OPEN_FOR_EXPIRY = EnumSet.of(ServiceRequestStatus.SUBMITTED, ServiceRequestStatus.QUOTED);

    private final ServiceRequestRepository requests;
    private final ServiceQuoteRepository quotes;
    private final ServiceBookingRepository bookings;
    private final ServiceMaterialLineRepository materials;
    private final ServiceProviderProfileRepository profiles;
    private final ServiceDiscoveryService discovery;
    private final ServiceRateLimiter rateLimiter;
    private final NotificationService notifications;
    private final AuditLogService audit;

    public ServiceRequestService(ServiceRequestRepository requests, ServiceQuoteRepository quotes,
                                 ServiceBookingRepository bookings, ServiceMaterialLineRepository materials,
                                 ServiceProviderProfileRepository profiles, ServiceDiscoveryService discovery,
                                 ServiceRateLimiter rateLimiter, NotificationService notifications, AuditLogService audit) {
        this.requests = requests;
        this.quotes = quotes;
        this.bookings = bookings;
        this.materials = materials;
        this.profiles = profiles;
        this.discovery = discovery;
        this.rateLimiter = rateLimiter;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public RequestView create(User customer, CreateServiceRequestRequest req) {
        String key = req.idempotencyKey() == null || req.idempotencyKey().isBlank() ? null : req.idempotencyKey().trim();
        if (key != null) {
            var existing = requests.findByCustomerIdAndIdempotencyKey(customer.getId(), key);
            if (existing.isPresent()) {
                return view(existing.get());
            }
        }
        rateLimiter.check("service-request", customer.getId(), 10, Duration.ofHours(1));
        ServiceListing listing = discovery.visible(req.listingId());
        if (listing.getBusiness().getOwner().getId().equals(customer.getId())) {
            throw ApiException.badRequest("You cannot request your own service");
        }
        ServiceRequest r = new ServiceRequest();
        try {
            r.setDescription(ServiceInput.text(req.description(), 2000, "Description", true));
            r.setLocationText(ServiceInput.text(req.location(), 300, "Location", true));
            r.setPreferredTime(ServiceInput.text(req.preferredTime(), 40, "Preferred time", false));
            r.setRequirements(ServiceInput.text(req.requirements(), 1000, "Requirements", false));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (req.preferredDate() != null && req.preferredDate().isBefore(LocalDate.now(ZONE))) {
            throw ApiException.badRequest("The preferred date is in the past");
        }
        r.setPreferredDate(req.preferredDate());
        r.setMaterialsResponsibility(resolveMaterials(listing, req.materialsResponsibility()));
        r.setCustomer(customer);
        r.setListing(listing);
        r.setBusiness(listing.getBusiness());
        r.setIdempotencyKey(key);
        boolean submit = req.submit() == null || req.submit();
        r.setStatus(submit ? ServiceRequestStatus.SUBMITTED : ServiceRequestStatus.DRAFT);
        if (submit) {
            r.setExpiresAt(Instant.now().plus(REQUEST_VALIDITY));
        }
        r = requests.save(r);
        audit.record(customer, "SERVICE_REQUEST_CREATED", "SERVICE_REQUEST", r.getId(), listing.getName() + " " + r.getStatus());
        if (submit) {
            notifyProvider(r, "New service request for " + listing.getName());
        }
        return view(r);
    }

    @Transactional
    public RequestView submit(User customer, String requestId) {
        ServiceRequest r = lockCustomerRequest(customer, requestId);
        transition(r, ServiceRequestStatus.SUBMITTED);
        r.setExpiresAt(Instant.now().plus(REQUEST_VALIDITY));
        requests.save(r);
        audit.record(customer, "SERVICE_REQUEST_SUBMITTED", "SERVICE_REQUEST", r.getId(), "");
        notifyProvider(r, "New service request for " + r.getListing().getName());
        return view(r);
    }

    @Transactional
    public RequestView cancel(User customer, String requestId) {
        ServiceRequest r = lockCustomerRequest(customer, requestId);
        if (r.getStatus() == ServiceRequestStatus.BOOKED || r.getStatus() == ServiceRequestStatus.IN_PROGRESS) {
            throw ApiException.conflict("This request is already booked. Cancel the booking instead");
        }
        transition(r, ServiceRequestStatus.CANCELLED);
        closeLiveQuotes(r.getId(), ServiceQuoteStatus.REJECTED);
        requests.save(r);
        audit.record(customer, "SERVICE_REQUEST_CANCELLED", "SERVICE_REQUEST", r.getId(), "");
        notifyProviderUpdate(r, "A service request was cancelled");
        return view(r);
    }

    @Transactional
    public RequestView decline(User actor, String requestId) {
        ServiceRequest r = requests.findForUpdate(requestId).orElseThrow(() -> ApiException.notFound("Request not found"));
        requireProviderActor(r, actor);
        transition(r, ServiceRequestStatus.DECLINED);
        closeLiveQuotes(r.getId(), ServiceQuoteStatus.REJECTED);
        requests.save(r);
        audit.record(actor, "SERVICE_REQUEST_DECLINED", "SERVICE_REQUEST", r.getId(), "");
        notifications.notify(r.getCustomer(), NotificationType.SERVICE_QUOTE_UPDATE,
                r.getBusiness().getName() + " declined your service request", r.getId());
        return view(r);
    }

    @Transactional(readOnly = true)
    public RequestView get(User viewer, String requestId) {
        ServiceRequest r = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("Request not found"));
        requireParty(r, viewer);
        return view(r);
    }

    @Transactional(readOnly = true)
    public List<RequestView> mine(User customer) {
        return requests.findByCustomerIdOrderByCreatedAtDesc(customer.getId()).stream().map(this::view).toList();
    }

    /** The provider's inbox: requests for the caller's business (drafts are never visible to providers). */
    @Transactional(readOnly = true)
    public List<RequestView> inbox(User providerOwner, String businessId) {
        return requests.findByBusinessIdAndStatusNotOrderByCreatedAtDesc(businessId, ServiceRequestStatus.DRAFT).stream()
                .filter(r -> r.getBusiness().getOwner().getId().equals(providerOwner.getId()))
                .map(this::view).toList();
    }

    /** Scheduled: expire stale requests and quotes. Returns how many requests were expired. */
    @Transactional
    public int expireDue() {
        Instant now = Instant.now();
        int n = 0;
        for (String id : requests.findIdsPastExpiry(OPEN_FOR_EXPIRY, now)) {
            ServiceRequest r = requests.findById(id).orElse(null);
            if (r == null || !OPEN_FOR_EXPIRY.contains(r.getStatus())) continue;
            r.setStatus(ServiceRequestStatus.EXPIRED);
            requests.save(r);
            closeLiveQuotes(r.getId(), ServiceQuoteStatus.EXPIRED);
            n++;
        }
        for (String qid : quotes.findIdsPastValidity(EnumSet.of(ServiceQuoteStatus.SUBMITTED, ServiceQuoteStatus.CLARIFICATION_REQUESTED), now)) {
            quotes.findById(qid).ifPresent(q -> { q.setStatus(ServiceQuoteStatus.EXPIRED); quotes.save(q); });
        }
        return n;
    }

    // ------------------------------------------------------------------ shared access helpers

    ServiceRequest lockCustomerRequest(User customer, String requestId) {
        ServiceRequest r = requests.findForUpdate(requestId).orElseThrow(() -> ApiException.notFound("Request not found"));
        if (!r.getCustomer().getId().equals(customer.getId())) {
            throw ApiException.notFound("Request not found");
        }
        return r;
    }

    boolean isProvider(ServiceRequest r, User u) {
        return r.getBusiness().getOwner().getId().equals(u.getId());
    }

    void requireParty(ServiceRequest r, User u) {
        if (!r.getCustomer().getId().equals(u.getId()) && !isProvider(r, u)) {
            throw ApiException.notFound("Request not found");
        }
        if (r.getStatus() == ServiceRequestStatus.DRAFT && !r.getCustomer().getId().equals(u.getId())) {
            throw ApiException.notFound("Request not found");
        }
    }

    /** Provider-side actions: caller owns the provider business and the provider is not suspended. */
    void requireProviderActor(ServiceRequest r, User u) {
        if (!isProvider(r, u) || r.getStatus() == ServiceRequestStatus.DRAFT) {
            throw ApiException.notFound("Request not found");
        }
        ServiceProviderProfile p = profiles.findByBusinessId(r.getBusiness().getId()).orElse(null);
        if (p == null || p.isSuspended()) {
            throw ApiException.forbidden("This provider profile is suspended");
        }
    }

    void transition(ServiceRequest r, ServiceRequestStatus to) {
        if (!ServiceLifecycle.canTransition(r.getStatus(), to)) {
            throw ApiException.conflict("A request that is " + r.getStatus().name().toLowerCase() + " cannot become "
                    + to.name().toLowerCase());
        }
        r.setStatus(to);
    }

    void closeLiveQuotes(String requestId, ServiceQuoteStatus to) {
        for (ServiceQuote q : quotes.findByRequestIdOrderByVersionNoDesc(requestId)) {
            if (q.getStatus() == ServiceQuoteStatus.SUBMITTED || q.getStatus() == ServiceQuoteStatus.CLARIFICATION_REQUESTED) {
                q.setStatus(to);
                q.setDecidedAt(Instant.now());
                quotes.save(q);
            }
        }
    }

    void notifyProvider(ServiceRequest r, String message) {
        notifications.notify(r.getBusiness().getOwner(), NotificationType.SERVICE_REQUEST_RECEIVED, message, r.getId());
    }

    void notifyProviderUpdate(ServiceRequest r, String message) {
        notifications.notify(r.getBusiness().getOwner(), NotificationType.SERVICE_QUOTE_UPDATE, message, r.getId());
    }

    RequestView view(ServiceRequest r) {
        List<QuoteView> qs = quotes.findByRequestIdOrderByVersionNoDesc(r.getId()).stream().map(ServiceViews::quote).toList();
        List<MaterialLineView> ms = materials.findByRequestIdOrderByCreatedAtAsc(r.getId()).stream().map(ServiceViews::material).toList();
        String bookingId = bookings.findByRequestId(r.getId()).map(BaseEntity::getId).orElse(null);
        return ServiceViews.request(r, qs, ms, bookingId);
    }

    private MaterialsResponsibility resolveMaterials(ServiceListing l, MaterialsResponsibility asked) {
        MaterialsResponsibility fixed = l.getMaterialsResponsibility();
        if (asked == null) return fixed;
        if (fixed != MaterialsResponsibility.MIXED && asked != fixed) {
            throw ApiException.badRequest("This service is offered with materials responsibility: " + fixed.name().toLowerCase());
        }
        return asked;
    }
}
