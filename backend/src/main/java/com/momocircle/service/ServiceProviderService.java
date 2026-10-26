package com.momocircle.service;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.ServiceDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import com.momocircle.services.ServiceInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Provider onboarding, profile and listings. A provider is an EXISTING Business that holds the CAN_PROVIDE_SERVICES
 * capability (the same capability architecture stock/supplier use). Verification and suspension are admin-only.
 */
@Service
public class ServiceProviderService {

    public static final String DEFAULT_CURRENCY = "ZAR";

    private final BusinessService businessService;
    private final ServiceProviderProfileRepository profiles;
    private final ServiceListingRepository listings;
    private final ServiceCategoryRepository categories;
    private final AuditLogService audit;

    public ServiceProviderService(BusinessService businessService, ServiceProviderProfileRepository profiles,
                                  ServiceListingRepository listings, ServiceCategoryRepository categories, AuditLogService audit) {
        this.businessService = businessService;
        this.profiles = profiles;
        this.listings = listings;
        this.categories = categories;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ profile

    @Transactional
    public ProviderProfileView upsertProfile(User owner, ProviderProfileRequest req) {
        Business b = requireProviderBusiness(owner);
        ServiceProviderProfile p = profiles.findByBusinessId(b.getId()).orElseGet(() -> {
            ServiceProviderProfile n = new ServiceProviderProfile();
            n.setBusiness(b);
            n.setVerificationStatus(ProviderVerificationStatus.UNVERIFIED);
            return n;
        });
        if (p.isSuspended()) {
            throw ApiException.forbidden("This provider profile is suspended");
        }
        try {
            p.setHeadline(ServiceInput.text(req.headline(), 160, "Headline", false));
            p.setAbout(ServiceInput.text(req.about(), 2000, "About", false));
            p.setContactEmail(ServiceInput.text(req.contactEmail(), 160, "Contact email", false));
            p.setAvailabilityNote(ServiceInput.text(req.availabilityNote(), 300, "Availability", false));
            if (req.acceptingRequests() != null) p.setAcceptingRequests(req.acceptingRequests());
            if (req.serviceAreas() != null) p.setServiceAreas(ServiceInput.areas(req.serviceAreas(), 30));
            if (req.portfolioUrls() != null) p.setPortfolioUrls(ServiceInput.imageUrls(req.portfolioUrls(), 12));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        p = profiles.save(p);
        return ServiceViews.profile(p, myListingViews(b, p));
    }

    @Transactional(readOnly = true)
    public ProviderProfileView myProfile(User owner) {
        Business b = requireProviderBusiness(owner);
        ServiceProviderProfile p = profiles.findByBusinessId(b.getId())
                .orElseThrow(() -> ApiException.notFound("Set up your provider profile first"));
        return ServiceViews.profile(p, myListingViews(b, p));
    }

    /** Public view: only providers that are not suspended, with only their publicly visible listings. */
    @Transactional(readOnly = true)
    public ProviderProfileView publicProfile(String businessId) {
        ServiceProviderProfile p = profiles.findByBusinessId(businessId)
                .filter(x -> !x.isSuspended() && x.getBusiness().isActive())
                .orElseThrow(() -> ApiException.notFound("Provider not found"));
        List<ListingView> visible = listings.findByBusinessIdAndStatusNotOrderByCreatedAtDesc(businessId, ServiceListingStatus.REMOVED)
                .stream().filter(l -> l.getStatus() == ServiceListingStatus.ACTIVE && !l.isHiddenByAdmin())
                .map(l -> ServiceViews.listing(l, p)).toList();
        return ServiceViews.profile(p, visible);
    }

    @Transactional(readOnly = true)
    public String myBusinessId(User owner) {
        return requireProviderBusiness(owner).getId();
    }

    // ------------------------------------------------------------------ listings

    @Transactional
    public ListingView createListing(User owner, ListingRequest req) {
        ServiceProviderProfile p = requireActiveProfile(owner);
        ServiceListing l = new ServiceListing();
        l.setBusiness(p.getBusiness());
        l.setCurrency(DEFAULT_CURRENCY);
        apply(l, req);
        l.setStatus(req.status() == null ? ServiceListingStatus.DRAFT : req.status());
        if (l.getStatus() == ServiceListingStatus.REMOVED) {
            throw ApiException.badRequest("A new listing cannot be created as removed");
        }
        l = listings.save(l);
        audit.record(owner, "SERVICE_LISTING_CREATED", "SERVICE_LISTING", l.getId(), l.getName());
        return ServiceViews.listing(l, p);
    }

    @Transactional
    public ListingView updateListing(User owner, String listingId, ListingRequest req) {
        ServiceProviderProfile p = requireActiveProfile(owner);
        ServiceListing l = ownListing(p, listingId);
        if (l.getStatus() == ServiceListingStatus.REMOVED) {
            throw ApiException.conflict("This listing was removed");
        }
        apply(l, req);
        if (req.status() != null && req.status() != l.getStatus()) {
            requireListingTransition(l.getStatus(), req.status());
            l.setStatus(req.status());
        }
        l = listings.save(l);
        audit.record(owner, "SERVICE_LISTING_UPDATED", "SERVICE_LISTING", l.getId(), l.getName() + " " + l.getStatus());
        return ServiceViews.listing(l, p);
    }

    @Transactional
    public ListingView removeListing(User owner, String listingId) {
        ServiceProviderProfile p = requireActiveProfile(owner);
        ServiceListing l = ownListing(p, listingId);
        l.setStatus(ServiceListingStatus.REMOVED); // soft delete: requests/bookings keep their reference
        l = listings.save(l);
        audit.record(owner, "SERVICE_LISTING_REMOVED", "SERVICE_LISTING", l.getId(), l.getName());
        return ServiceViews.listing(l, p);
    }

    @Transactional(readOnly = true)
    public List<ListingView> myListings(User owner) {
        Business b = requireProviderBusiness(owner);
        ServiceProviderProfile p = profiles.findByBusinessId(b.getId()).orElse(null);
        return myListingViews(b, p);
    }

    // ------------------------------------------------------------------ admin moderation (no financial powers)

    @Transactional
    public ProviderProfileView setVerification(User admin, String businessId, ProviderVerificationStatus status) {
        ServiceProviderProfile p = profiles.findByBusinessId(businessId)
                .orElseThrow(() -> ApiException.notFound("Provider not found"));
        p.setVerificationStatus(status);
        p.setVerifiedAt(status == ProviderVerificationStatus.VERIFIED ? Instant.now() : null);
        p = profiles.save(p);
        audit.record(admin, "SERVICE_PROVIDER_VERIFICATION", "BUSINESS", businessId, status.name());
        return ServiceViews.profile(p, List.of());
    }

    @Transactional
    public ProviderProfileView setSuspended(User admin, String businessId, boolean suspended, String reason) {
        ServiceProviderProfile p = profiles.findByBusinessId(businessId)
                .orElseThrow(() -> ApiException.notFound("Provider not found"));
        p.setSuspended(suspended);
        p.setSuspendedReason(suspended ? reason : null);
        p = profiles.save(p);
        audit.record(admin, suspended ? "SERVICE_PROVIDER_SUSPENDED" : "SERVICE_PROVIDER_REINSTATED", "BUSINESS", businessId,
                reason == null ? "" : reason);
        return ServiceViews.profile(p, List.of());
    }

    @Transactional
    public ListingView setHidden(User admin, String listingId, boolean hidden) {
        ServiceListing l = listings.findById(listingId).orElseThrow(() -> ApiException.notFound("Listing not found"));
        l.setHiddenByAdmin(hidden);
        l = listings.save(l);
        audit.record(admin, hidden ? "SERVICE_LISTING_HIDDEN" : "SERVICE_LISTING_UNHIDDEN", "SERVICE_LISTING", l.getId(), l.getName());
        return ServiceViews.listing(l, profiles.findByBusinessId(l.getBusiness().getId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<ProviderProfileView> pendingVerification(org.springframework.data.domain.Pageable pageable) {
        return profiles.findByVerificationStatusOrderByCreatedAtAsc(ProviderVerificationStatus.PENDING, pageable)
                .map(p -> ServiceViews.profile(p, List.of())).getContent();
    }

    /** The provider asks to be verified; an admin decides. */
    @Transactional
    public ProviderProfileView requestVerification(User owner) {
        ServiceProviderProfile p = requireActiveProfile(owner);
        if (p.getVerificationStatus() == ProviderVerificationStatus.VERIFIED) {
            throw ApiException.conflict("Already verified");
        }
        p.setVerificationStatus(ProviderVerificationStatus.PENDING);
        p = profiles.save(p);
        audit.record(owner, "SERVICE_PROVIDER_VERIFICATION_REQUESTED", "BUSINESS", p.getBusiness().getId(), "");
        return ServiceViews.profile(p, myListingViews(p.getBusiness(), p));
    }

    // ------------------------------------------------------------------ helpers

    Business requireProviderBusiness(User owner) {
        Business b = businessService.requireOwn(owner);
        if (!b.getCapabilities().contains(BusinessCapability.CAN_PROVIDE_SERVICES)) {
            throw ApiException.forbidden("Enable the service provider capability on your business first");
        }
        if (!b.isActive()) {
            throw ApiException.forbidden("Your business is not active");
        }
        return b;
    }

    ServiceProviderProfile requireActiveProfile(User owner) {
        Business b = requireProviderBusiness(owner);
        ServiceProviderProfile p = profiles.findByBusinessId(b.getId())
                .orElseThrow(() -> ApiException.notFound("Set up your provider profile first"));
        if (p.isSuspended()) {
            throw ApiException.forbidden("This provider profile is suspended");
        }
        return p;
    }

    private ServiceListing ownListing(ServiceProviderProfile p, String listingId) {
        // Looked up by (id AND business): another provider's listing id is simply "not found".
        return listings.findByIdAndBusinessId(listingId, p.getBusiness().getId())
                .orElseThrow(() -> ApiException.notFound("Listing not found"));
    }

    private List<ListingView> myListingViews(Business b, ServiceProviderProfile p) {
        return listings.findByBusinessIdAndStatusNotOrderByCreatedAtDesc(b.getId(), ServiceListingStatus.REMOVED).stream()
                .map(l -> ServiceViews.listing(l, p)).toList();
    }

    private void apply(ServiceListing l, ListingRequest req) {
        try {
            l.setName(ServiceInput.text(req.name(), 120, "Name", true));
            l.setDescription(ServiceInput.text(req.description(), 2000, "Description", false));
            ServiceCategory c = categories.findById(req.categoryId())
                    .orElseThrow(() -> ApiException.badRequest("Unknown category"));
            if (!c.isActive()) throw ApiException.badRequest("That category is not available");
            l.setCategory(c);
            l.setPricingModel(req.pricingModel());
            if (req.pricingModel() == ServicePricingModel.QUOTE_REQUIRED) {
                if (req.price() != null) throw ApiException.badRequest("A quote-required service has no listed price");
                l.setPrice(null);
            } else {
                if (req.price() == null || req.price().signum() <= 0) {
                    throw ApiException.badRequest("This pricing model needs a price");
                }
                if (req.price().compareTo(com.momocircle.services.ServiceQuoteCalculator.MAX_COMPONENT) > 0) {
                    throw ApiException.badRequest("Price is too large");
                }
                l.setPrice(req.price().setScale(2, java.math.RoundingMode.HALF_UP));
            }
            if (req.durationMinutes() != null && (req.durationMinutes() < 1 || req.durationMinutes() > 60 * 24 * 30)) {
                throw ApiException.badRequest("Invalid duration");
            }
            l.setDurationMinutes(req.durationMinutes());
            l.setMaterialsResponsibility(req.materialsResponsibility());
            if (req.serviceAreas() != null) l.setServiceAreas(ServiceInput.areas(req.serviceAreas(), 30));
            if (req.imageUrls() != null) l.setImageUrls(ServiceInput.imageUrls(req.imageUrls(), 8));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private void requireListingTransition(ServiceListingStatus from, ServiceListingStatus to) {
        boolean ok = switch (from) {
            case DRAFT -> to == ServiceListingStatus.ACTIVE || to == ServiceListingStatus.PAUSED || to == ServiceListingStatus.REMOVED;
            case ACTIVE -> to == ServiceListingStatus.PAUSED || to == ServiceListingStatus.REMOVED;
            case PAUSED -> to == ServiceListingStatus.ACTIVE || to == ServiceListingStatus.REMOVED;
            case REMOVED -> false;
        };
        if (!ok) {
            throw ApiException.conflict("A listing cannot go from " + from + " to " + to);
        }
    }
}
