package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.ReviewDtos.ReviewResponse;
import com.momocircle.dto.ServiceDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import com.momocircle.security.ServiceRateLimiter;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Executes the REAL services-marketplace classes (provider, discovery, request, quote, materials, booking, review,
 * insights, catalogue) against in-memory fakes: Proxy repositories, subclassed collaborators, no Spring, no database.
 * Payment goes through a fake ServicePaymentGateway (the seam over the existing market payment path), and the Save-to-Buy
 * hooks are recorded rather than run (their own logic is covered in SaveToBuyServiceTest).
 */
class ServicesMarketplaceTest {

    interface Custom { Object handle(String name, Object[] a, Map<String, Object> store); }

    @SuppressWarnings("unchecked")
    static <T> T repo(Class<T> type, Map<String, Object> store, Custom custom) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            Object[] args = a == null ? new Object[0] : a;
            switch (m.getName()) {
                case "save", "saveAndFlush" -> { BaseEntity e = (BaseEntity) args[0]; if (e.getCreatedAt() == null) e.setCreatedAt(Instant.now()); store.put(e.getId(), e); return e; }
                case "findById" -> { return Optional.ofNullable(store.get((String) args[0])); }
                case "findAll" -> { return new ArrayList<>(store.values()); }
                case "delete" -> { store.remove(((BaseEntity) args[0]).getId()); return null; }
                case "flush" -> { return null; }
                case "hashCode" -> { return System.identityHashCode(p); }
                case "equals" -> { return p == args[0]; }
                default -> { return custom.handle(m.getName(), args, store); }
            }
        });
    }

    static <E> List<E> all(Map<String, Object> s, Class<E> c) { return s.values().stream().map(c::cast).toList(); }

    // ---------------------------------------------------------------- world
    static final class World {
        final Map<String, Object> cats = new LinkedHashMap<>(), profiles = new LinkedHashMap<>(), listings = new LinkedHashMap<>(),
                reqs = new LinkedHashMap<>(), quotes = new LinkedHashMap<>(), bookings = new LinkedHashMap<>(), mats = new LinkedHashMap<>(),
                products = new LinkedHashMap<>(), stockOrders = new LinkedHashMap<>(), businesses = new LinkedHashMap<>(),
                payments = new LinkedHashMap<>(), reviewStore = new LinkedHashMap<>();
        final List<String> audits = new ArrayList<>(), notes = new ArrayList<>(), s2bCalls = new ArrayList<>();
        final Map<String, Business> businessByOwner = new HashMap<>();
        final List<Object[]> searchCalls = new ArrayList<>();
        final List<String> gatewayRefs = new ArrayList<>();

        ServiceCatalogService catalog; ServiceProviderService providerSvc; ServiceDiscoveryService discovery;
        ServiceRequestService requestSvc; ServiceQuoteService quoteSvc; ServiceMaterialService materialSvc;
        ServiceBookingService bookingSvc; ServiceInsightService insightSvc; ServiceRateLimiter limiter = new ServiceRateLimiter();

        @SuppressWarnings("unchecked")
        World() {
            ServiceCategoryRepository catRepo = repo(ServiceCategoryRepository.class, cats, (n, a, s) -> {
                List<ServiceCategory> l = all(s, ServiceCategory.class);
                return switch (n) {
                    case "findBySlug" -> l.stream().filter(c -> c.getSlug().equals(a[0])).findFirst();
                    case "findAllByOrderBySortOrderAscNameAsc" -> l;
                    case "findByActiveTrueOrderBySortOrderAscNameAsc" -> l.stream().filter(ServiceCategory::isActive).toList();
                    default -> throw new UnsupportedOperationException(n);
                };
            });
            ServiceProviderProfileRepository profRepo = repo(ServiceProviderProfileRepository.class, profiles, (n, a, s) -> {
                if (n.equals("findByBusinessId")) return all(s, ServiceProviderProfile.class).stream().filter(p -> p.getBusiness().getId().equals(a[0])).findFirst();
                if (n.equals("findByVerificationStatusOrderByCreatedAtAsc")) {
                    List<ServiceProviderProfile> content =
                            all(s, ServiceProviderProfile.class).stream()
                                    .filter(p -> p.getVerificationStatus() == a[0])
                                    .toList();

                    return new org.springframework.data.domain.PageImpl<>(
                            content,
                            org.springframework.data.domain.PageRequest.of(0, Math.max(content.size(), 1)),
                            content.size()
                    );
                }
                throw new UnsupportedOperationException(n);
            });
            ServiceListingRepository listingRepo = repo(ServiceListingRepository.class, listings, (n, a, s) -> {
                List<ServiceListing> l = all(s, ServiceListing.class);
                switch (n) {
                    case "findByBusinessIdAndStatusNotOrderByCreatedAtDesc": return l.stream().filter(x -> x.getBusiness().getId().equals(a[0]) && x.getStatus() != a[1]).toList();
                    case "findByIdAndBusinessId": return l.stream().filter(x -> x.getId().equals(a[0]) && x.getBusiness().getId().equals(a[1])).findFirst();
                    case "search": {
                        searchCalls.add(a);

                        return new org.springframework.data.domain.PageImpl<>(
                                List.<ServiceListing>of(),
                                org.springframework.data.domain.PageRequest.of(0, 1),
                                0
                        );
                    }
                    default: throw new UnsupportedOperationException(n);
                }
            });
            ServiceRequestRepository reqRepo = repo(ServiceRequestRepository.class, reqs, (n, a, s) -> {
                List<ServiceRequest> l = all(s, ServiceRequest.class);
                switch (n) {
                    case "findForUpdate": return Optional.ofNullable(s.get((String) a[0]));
                    case "findByCustomerIdOrderByCreatedAtDesc": return l.stream().filter(r -> r.getCustomer().getId().equals(a[0])).toList();
                    case "findByBusinessIdAndStatusNotOrderByCreatedAtDesc": return l.stream().filter(r -> r.getBusiness().getId().equals(a[0]) && r.getStatus() != a[1]).toList();
                    case "findByCustomerIdAndIdempotencyKey": return l.stream().filter(r -> r.getCustomer().getId().equals(a[0]) && Objects.equals(r.getIdempotencyKey(), a[1])).findFirst();
                    case "findIdsPastExpiry": return l.stream().filter(r -> ((Collection<ServiceRequestStatus>) a[0]).contains(r.getStatus()) && r.getExpiresAt() != null && r.getExpiresAt().isBefore((Instant) a[1])).map(BaseEntity::getId).toList();
                    default: throw new UnsupportedOperationException(n);
                }
            });
            ServiceQuoteRepository quoteRepo = repo(ServiceQuoteRepository.class, quotes, (n, a, s) -> {
                List<ServiceQuote> l = all(s, ServiceQuote.class);
                switch (n) {
                    case "findByRequestIdOrderByVersionNoDesc": return l.stream().filter(q -> q.getRequest().getId().equals(a[0])).sorted(Comparator.comparingInt(ServiceQuote::getVersionNo).reversed()).toList();
                    case "findByIdAndRequestId": return l.stream().filter(q -> q.getId().equals(a[0]) && q.getRequest().getId().equals(a[1])).findFirst();
                    case "findIdsPastValidity": return l.stream().filter(q -> ((Collection<ServiceQuoteStatus>) a[0]).contains(q.getStatus()) && q.getValidUntil().isBefore((Instant) a[1])).map(BaseEntity::getId).toList();
                    default: throw new UnsupportedOperationException(n);
                }
            });
            ServiceBookingRepository bookingRepo = repo(ServiceBookingRepository.class, bookings, (n, a, s) -> {
                List<ServiceBooking> l = all(s, ServiceBooking.class);
                switch (n) {
                    case "findForUpdate": return Optional.ofNullable(s.get((String) a[0]));
                    case "findByRequestId": return l.stream().filter(b -> b.getRequest().getId().equals(a[0])).findFirst();
                    case "findByPaymentId": return l.stream().filter(b -> b.getPayment() != null && b.getPayment().getId().equals(a[0])).findFirst();
                    case "findByCustomerIdOrderByCreatedAtDesc": return l.stream().filter(b -> b.getCustomer().getId().equals(a[0])).toList();
                    case "findByBusinessIdOrderByCreatedAtDesc": return l.stream().filter(b -> b.getBusiness().getId().equals(a[0])).toList();
                    default: throw new UnsupportedOperationException(n);
                }
            });
            ServiceMaterialLineRepository matRepo = repo(ServiceMaterialLineRepository.class, mats, (n, a, s) -> {
                List<ServiceMaterialLine> l = all(s, ServiceMaterialLine.class);
                switch (n) {
                    case "findByRequestIdOrderByCreatedAtAsc": return l.stream().filter(m -> m.getRequest().getId().equals(a[0])).toList();
                    case "findByIdAndRequestId": return l.stream().filter(m -> m.getId().equals(a[0]) && m.getRequest().getId().equals(a[1])).findFirst();
                    case "existsByStockOrderIdAndRequestIdNot": return l.stream().anyMatch(m -> m.getStockOrder() != null && m.getStockOrder().getId().equals(a[0]) && !m.getRequest().getId().equals(a[1]));
                    default: throw new UnsupportedOperationException(n);
                }
            });
            WholesaleProductRepository productRepo = repo(WholesaleProductRepository.class, products, (n, a, s) -> { throw new UnsupportedOperationException(n); });
            StockOrderRepository stockRepo = repo(StockOrderRepository.class, stockOrders, (n, a, s) -> {
                if (n.equals("findByIdAndBuyerBusinessId")) return all(s, StockOrder.class).stream().filter(o -> o.getId().equals(a[0]) && o.getBuyerBusiness().getId().equals(a[1])).findFirst();
                throw new UnsupportedOperationException(n);
            });
            BusinessRepository bizRepo = repo(BusinessRepository.class, businesses, (n, a, s) -> {
                if (n.equals("findByOwnerId")) return Optional.ofNullable(businessByOwner.get((String) a[0]));
                throw new UnsupportedOperationException(n);
            });
            PaymentRepository payRepo = repo(PaymentRepository.class, payments, (n, a, s) -> { throw new UnsupportedOperationException(n); });
            ReviewRepository reviewRepo = repo(ReviewRepository.class, reviewStore, (n, a, s) -> {
                List<Review> l = all(s, Review.class);
                switch (n) {
                    case "existsByDedupeKey": return l.stream().anyMatch(r -> r.getDedupeKey().equals(a[0]));
                    case "averageRating": { var ms = l.stream().filter(r -> r.getTargetType() == a[0] && r.getTargetId().equals(a[1])).toList();
                        return ms.isEmpty() ? null : BigDecimal.valueOf(ms.stream().mapToInt(Review::getRating).average().orElse(0)); }
                    case "countByTargetTypeAndTargetIdAndHiddenFalse": return l.stream().filter(r -> r.getTargetType() == a[0] && r.getTargetId().equals(a[1])).count();
                    default: throw new UnsupportedOperationException(n);
                }
            });

            final World w = this;
            BusinessService businessService = new BusinessService(null) {
                @Override public Business requireOwn(User owner) {
                    Business b = w.businessByOwner.get(owner.getId());
                    if (b == null) throw ApiException.notFound("No business");
                    return b;
                }
            };
            AuditLogService audit = new AuditLogService(null) {
                @Override public void record(User actor, String action, String type, String id, String details) { w.audits.add(action); }
            };
            NotificationService notif = new NotificationService(null) {
                @Override public void notify(User r, NotificationType t, String m, String ref) { w.notes.add(r.getFullName() + ":" + t.name()); }
            };
            ReviewService reviewService = new ReviewService(reviewRepo,
                    repo(OrderRepository.class, new HashMap<>(), (n, a, s) -> { throw new UnsupportedOperationException(n); }), bizRepo,
                    repo(ProductRepository.class, new HashMap<>(), (n, a, s) -> { throw new UnsupportedOperationException(n); }));
            try {
                var f = ReviewService.class.getDeclaredField("serviceListingRepository");
                f.setAccessible(true);
                f.set(reviewService, listingRepo);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }

            ServicePaymentGateway gateway = new ServicePaymentGateway() {
                public Session start(String ref, BigDecimal amount, String currency, String email) { w.gatewayRefs.add(ref + "|" + amount + "|" + currency); return new Session(ref, "https://pay.test/" + ref); }
                public String providerLabel() { return "TEST"; }
            };
            SaveToBuyService s2b = new SaveToBuyService(null, null, null, null, null, null, null, null, null, null) {
                @Override public SaveToBuyRequest validateForCheckout(User b, String id, SaveToBuyVertical v, BigDecimal total, String key) {
                    if (id == null || id.isBlank()) return null;
                    w.s2bCalls.add("validate:" + id + ":" + v + ":" + total);
                    SaveToBuyRequest r = new SaveToBuyRequest();
                    r.setId(id);
                    return r;
                }
                @Override public void attachOrder(SaveToBuyRequest r, RelatedOrderKind k, String orderId, String key) { w.s2bCalls.add("attach:" + k + ":" + orderId); }
                @Override public String onOrderPaid(RelatedOrderKind k, String orderId, BigDecimal total) { w.s2bCalls.add("paid:" + k + ":" + total); return null; }
                @Override public void onOrderFailed(RelatedOrderKind k, String orderId) { w.s2bCalls.add("failed:" + k); }
            };

            catalog = new ServiceCatalogService(catRepo, audit);
            providerSvc = new ServiceProviderService(businessService, profRepo, listingRepo, catRepo, audit);
            discovery = new ServiceDiscoveryService(listingRepo, profRepo);
            requestSvc = new ServiceRequestService(reqRepo, quoteRepo, bookingRepo, matRepo, profRepo, discovery, limiter, notif, audit);
            quoteSvc = new ServiceQuoteService(reqRepo, quoteRepo, bookingRepo, matRepo, requestSvc, limiter, notif, audit);
            materialSvc = new ServiceMaterialService(reqRepo, quoteRepo, matRepo, productRepo, stockRepo, bizRepo, requestSvc, limiter, audit);
            bookingSvc = new ServiceBookingService(bookingRepo, reqRepo, matRepo, profRepo, payRepo, reviewRepo, reviewService, gateway, notif, audit);
            try {
                var f = ServiceBookingService.class.getDeclaredField("saveToBuy");
                f.setAccessible(true);
                f.set(bookingSvc, s2b);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            insightSvc = new ServiceInsightService(reqRepo, quoteRepo, requestSvc, discovery);
        }

        User user(String name) { User u = new User(); u.setFullName(name); u.setEmail(name + "@x.test"); u.setRole(PlatformRole.USER); return u; }
        User admin() { User u = user("Admin"); u.setRole(PlatformRole.ADMIN); return u; }

        Business business(User owner, String name, boolean capability) {
            Business b = new Business();
            b.setOwner(owner); b.setName(name); b.setActive(true);
            b.setCapabilities(capability ? new HashSet<>(Set.of(BusinessCapability.CAN_PROVIDE_SERVICES)) : new HashSet<>());
            b.setCreatedAt(Instant.now());
            businesses.put(b.getId(), b);
            businessByOwner.put(owner.getId(), b);
            return b;
        }

        ServiceCategory category(String name, boolean active) {
            ServiceCategory c = new ServiceCategory();
            c.setName(name); c.setSlug(name.toLowerCase()); c.setActive(active);
            cats.put(c.getId(), c);
            return c;
        }

        WholesaleProduct product(String name, String price, Business supplier) {
            WholesaleProduct p = new WholesaleProduct();
            p.setName(name); p.setPricePerPack(new BigDecimal(price)); p.setActive(true); p.setSupplierBusiness(supplier);
            products.put(p.getId(), p);
            return p;
        }

        /** A provider with an active listing; returns the listing view. */
        ListingView activeListing(User owner, ServiceCategory cat, ServicePricingModel model, String price, MaterialsResponsibility mr) {
            providerSvc.upsertProfile(owner, new ProviderProfileRequest("Pro", "About", "p@x.test", "Weekdays", true, List.of("Soweto"), List.of()));
            return providerSvc.createListing(owner, new ListingRequest(cat.getId(), "Fix taps", "Plumbing work", model,
                    price == null ? null : new BigDecimal(price), 60, mr, List.of("Soweto"), List.of(), ServiceListingStatus.ACTIVE));
        }

        CreateServiceRequestRequest ask(String listingId, String key) {
            return new CreateServiceRequestRequest(listingId, "Leaking tap", "12 Vilakazi St", LocalDate.now().plusDays(3), "morning", null, null, true, key);
        }

        SubmitQuoteRequest quote(String labour, String hours) {
            return new SubmitQuoteRequest(hours == null ? null : new BigDecimal(hours), labour == null ? null : new BigDecimal(labour),
                    new BigDecimal("50.00"), null, null, Instant.now().plusSeconds(86400), "see you");
        }

        /** Full path to a COMPLETED booking for a FIXED R450 listing (+R50 delivery = R500). */
        record Job(User provider, User customer, ListingView listing, RequestView request, BookingView booking) {}

        Job completedJob() {
            User p = user("Pro"); business(p, "Pro Plumbing", true);
            User c = user("Cust");
            ListingView l = activeListing(p, category("plumbing", true), ServicePricingModel.FIXED, "450.00", MaterialsResponsibility.NONE);
            RequestView r = requestSvc.create(c, ask(l.id(), null));
            QuoteView q = quoteSvc.submit(p, r.id(), quote(null, null));
            BookingView b = quoteSvc.accept(c, r.id(), q.id());
            bookingSvc.start(p, b.id());
            b = bookingSvc.complete(p, b.id());
            return new Job(p, c, l, r, b);
        }
    }

    private static void expect(String status, Runnable r) {
        assertEquals(status, assertThrows(ApiException.class, r::run).getStatus().name());
    }

    // ================================================================ provider onboarding & listings

    @Test
    void providerNeedsTheCapabilityAndCannotVerifyThemselves() {
        World w = new World();
        User noCap = w.user("NoCap"); w.business(noCap, "Plain Shop", false);
        expect("FORBIDDEN", () -> w.providerSvc.upsertProfile(noCap, new ProviderProfileRequest("h", null, null, null, true, List.of(), List.of())));
        User stranger = w.user("NoBusiness");
        expect("NOT_FOUND", () -> w.providerSvc.upsertProfile(stranger, new ProviderProfileRequest("h", null, null, null, true, List.of(), List.of())));

        User p = w.user("Pro"); Business b = w.business(p, "Pro Plumbing", true);
        ProviderProfileView v = w.providerSvc.upsertProfile(p, new ProviderProfileRequest("Best plumber", "About", "p@x.test", "Weekdays", true,
                List.of(" Soweto ", "SOWETO", "Pretoria"), List.of("https://cdn.test/a.jpg")));
        assertEquals(ProviderVerificationStatus.UNVERIFIED, v.verificationStatus());
        assertEquals(List.of("soweto", "pretoria"), v.serviceAreas());
        expect("BAD_REQUEST", () -> w.providerSvc.upsertProfile(p, new ProviderProfileRequest("h", null, null, null, true, List.of(), List.of("http://insecure.test/a.jpg"))));
        expect("BAD_REQUEST", () -> w.providerSvc.upsertProfile(p, new ProviderProfileRequest("h", null, null, null, true, List.of(), List.of("javascript:alert(1)"))));
        // a provider can only ASK for verification; an admin decides
        assertEquals(ProviderVerificationStatus.PENDING, w.providerSvc.requestVerification(p).verificationStatus());
        assertEquals(ProviderVerificationStatus.VERIFIED, w.providerSvc.setVerification(w.admin(), b.getId(), ProviderVerificationStatus.VERIFIED).verificationStatus());
        expect("CONFLICT", () -> w.providerSvc.requestVerification(p));
        assertTrue(w.audits.contains("SERVICE_PROVIDER_VERIFICATION"));
    }

    @Test
    void listingValidationOwnershipAndStatusRules() {
        World w = new World();
        User p = w.user("Pro"); w.business(p, "P", true);
        ServiceCategory cat = w.category("plumbing", true);
        w.providerSvc.upsertProfile(p, new ProviderProfileRequest("h", null, null, null, true, List.of("soweto"), List.of()));
        ListingRequest base = new ListingRequest(cat.getId(), "Taps", "d", ServicePricingModel.FIXED, new BigDecimal("100.00"), 30,
                MaterialsResponsibility.NONE, List.of("soweto"), List.of("https://cdn.test/1.jpg"), null);
        expect("BAD_REQUEST", () -> w.providerSvc.createListing(p, new ListingRequest(cat.getId(), "Taps", "d", ServicePricingModel.FIXED, null, 30, MaterialsResponsibility.NONE, null, null, null)));
        expect("BAD_REQUEST", () -> w.providerSvc.createListing(p, new ListingRequest(cat.getId(), "Taps", "d", ServicePricingModel.QUOTE_REQUIRED, new BigDecimal("5.00"), 30, MaterialsResponsibility.NONE, null, null, null)));
        expect("BAD_REQUEST", () -> w.providerSvc.createListing(p, new ListingRequest("nope", "Taps", "d", ServicePricingModel.FIXED, new BigDecimal("1.00"), 30, MaterialsResponsibility.NONE, null, null, null)));
        ServiceCategory inactive = w.category("old", false);
        expect("BAD_REQUEST", () -> w.providerSvc.createListing(p, new ListingRequest(inactive.getId(), "Taps", "d", ServicePricingModel.FIXED, new BigDecimal("1.00"), 30, MaterialsResponsibility.NONE, null, null, null)));
        ListingView l = w.providerSvc.createListing(p, base);
        assertEquals(ServiceListingStatus.DRAFT, l.status());
        assertEquals("ZAR", l.currency());

        // another provider cannot touch it (IDOR)
        User other = w.user("Other"); w.business(other, "O", true);
        w.providerSvc.upsertProfile(other, new ProviderProfileRequest("h", null, null, null, true, List.of(), List.of()));
        expect("NOT_FOUND", () -> w.providerSvc.updateListing(other, l.id(), base));
        expect("NOT_FOUND", () -> w.providerSvc.removeListing(other, l.id()));

        // status machine: DRAFT -> ACTIVE -> PAUSED -> ACTIVE ok; REMOVED is final
        ListingRequest toActive = new ListingRequest(cat.getId(), "Taps", "d", ServicePricingModel.FIXED, new BigDecimal("100.00"), 30, MaterialsResponsibility.NONE, null, null, ServiceListingStatus.ACTIVE);
        assertEquals(ServiceListingStatus.ACTIVE, w.providerSvc.updateListing(p, l.id(), toActive).status());
        w.providerSvc.removeListing(p, l.id());
        expect("CONFLICT", () -> w.providerSvc.updateListing(p, l.id(), toActive));
        assertTrue(w.providerSvc.myListings(p).isEmpty());
    }

    // ================================================================ discovery

    @Test
    void discoveryOnlyShowsActiveUnhiddenListingsOfProvidersInGoodStanding() {
        World w = new World();
        User p = w.user("Pro"); Business b = w.business(p, "P", true);
        ListingView l = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.FIXED, "100.00", MaterialsResponsibility.NONE);
        assertEquals(l.id(), w.discovery.listing(l.id()).id());
        assertEquals(1, w.discovery.compare(List.of(l.id(), "does-not-exist")).size()); // unknown ids are skipped, not revealed

        w.providerSvc.setHidden(w.admin(), l.id(), true);
        expect("NOT_FOUND", () -> w.discovery.listing(l.id()));
        assertTrue(w.discovery.compare(List.of(l.id())).isEmpty());
        w.providerSvc.setHidden(w.admin(), l.id(), false);
        assertNotNull(w.discovery.listing(l.id()));

        w.providerSvc.setSuspended(w.admin(), b.getId(), true, "complaints");
        expect("NOT_FOUND", () -> w.discovery.listing(l.id()));
        expect("NOT_FOUND", () -> w.providerSvc.publicProfile(b.getId()));
        expect("FORBIDDEN", () -> w.providerSvc.upsertProfile(p, new ProviderProfileRequest("h", null, null, null, true, List.of(), List.of())));
        w.providerSvc.setSuspended(w.admin(), b.getId(), false, null);
        assertEquals(1, w.providerSvc.publicProfile(b.getId()).listings().size());

        expect("BAD_REQUEST", () -> w.discovery.compare(List.of("1", "2", "3", "4", "5")));
        expect("BAD_REQUEST", () -> w.discovery.compare(List.of()));
    }

    @Test
    void searchNormalisesTextAndAreaBeforeQuerying() {
        World w = new World();
        w.discovery.search(" cat1 ", "  TaP %_ ", ServicePricingModel.FIXED, new BigDecimal("500"), new BigDecimal("4"), true, " Soweto ", null);
        Object[] call = w.searchCalls.get(0);
        assertEquals(" cat1 ", call[0]);
        assertEquals("%tap %", call[1]);          // lower-cased, wildcard characters from the user stripped
        assertEquals("soweto", call[6]);
        w.discovery.search(null, " ", null, null, null, false, " ", null);
        Object[] empty = w.searchCalls.get(1);
        assertNull(empty[1]);
        assertNull(empty[6]);
    }

    // ================================================================ request

    @Test
    void customerRequestRulesAndPrivacy() {
        World w = new World();
        User p = w.user("Pro"); w.business(p, "P", true);
        User c = w.user("Cust");
        ListingView l = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.FIXED, "100.00", MaterialsResponsibility.CUSTOMER);

        expect("BAD_REQUEST", () -> w.requestSvc.create(p, w.ask(l.id(), null)));                                  // own service
        expect("NOT_FOUND", () -> w.requestSvc.create(c, w.ask("missing", null)));
        expect("BAD_REQUEST", () -> w.requestSvc.create(c, new CreateServiceRequestRequest(l.id(), "d", "loc", LocalDate.now().minusDays(1), null, null, null, true, null)));
        expect("BAD_REQUEST", () -> w.requestSvc.create(c, new CreateServiceRequestRequest(l.id(), "d", "loc", null, null, null, MaterialsResponsibility.PROVIDER, true, null)));
        expect("BAD_REQUEST", () -> w.requestSvc.create(c, new CreateServiceRequestRequest(l.id(), "  ", "loc", null, null, null, null, true, null)));

        RequestView r = w.requestSvc.create(c, w.ask(l.id(), "key-1"));
        assertEquals(ServiceRequestStatus.SUBMITTED, r.status());
        assertEquals(MaterialsResponsibility.CUSTOMER, r.materialsResponsibility());   // defaulted from the listing
        assertEquals(r.id(), w.requestSvc.create(c, w.ask(l.id(), "key-1")).id());    // idempotent
        assertTrue(w.notes.contains("Pro:SERVICE_REQUEST_RECEIVED"));
        assertEquals(1, w.requestSvc.mine(c).size());

        // drafts are private to the customer; strangers never see the request
        RequestView draft = w.requestSvc.create(c, new CreateServiceRequestRequest(l.id(), "Later", "loc", null, null, null, null, false, null));
        assertEquals(ServiceRequestStatus.DRAFT, draft.status());
        expect("NOT_FOUND", () -> w.requestSvc.get(p, draft.id()));
        assertEquals(1, w.requestSvc.inbox(p, w.businessByOwner.get(p.getId()).getId()).size());
        User stranger = w.user("Stranger");
        expect("NOT_FOUND", () -> w.requestSvc.get(stranger, r.id()));
        expect("NOT_FOUND", () -> w.requestSvc.cancel(stranger, r.id()));
        expect("NOT_FOUND", () -> w.requestSvc.decline(stranger, r.id()));
        assertEquals(r.id(), w.requestSvc.get(p, r.id()).id());
        // submit the draft, then cancel it
        assertEquals(ServiceRequestStatus.SUBMITTED, w.requestSvc.submit(c, draft.id()).status());
        assertEquals(ServiceRequestStatus.CANCELLED, w.requestSvc.cancel(c, draft.id()).status());
        expect("CONFLICT", () -> w.requestSvc.submit(c, draft.id()));
    }

    @Test
    void requestCreationIsRateLimited() {
        World w = new World();
        User p = w.user("Pro"); w.business(p, "P", true);
        User c = w.user("Cust");
        ListingView l = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.FIXED, "100.00", MaterialsResponsibility.NONE);
        for (int i = 0; i < 10; i++) w.requestSvc.create(c, w.ask(l.id(), "k" + i));
        expect("TOO_MANY_REQUESTS", () -> w.requestSvc.create(c, w.ask(l.id(), "k-over")));
    }

    // ================================================================ quotes

    @Test
    void quoteTotalsAreComputedByTheServerAndTamperingIsRefused() {
        World w = new World();
        User p = w.user("Pro"); w.business(p, "P", true);
        User c = w.user("Cust");
        ListingView l = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.FIXED, "450.00", MaterialsResponsibility.NONE);
        RequestView r = w.requestSvc.create(c, w.ask(l.id(), null));

        expect("NOT_FOUND", () -> w.quoteSvc.submit(c, r.id(), w.quote(null, null)));                        // the customer cannot quote
        User other = w.user("Other"); w.business(other, "O", true);
        expect("NOT_FOUND", () -> w.quoteSvc.submit(other, r.id(), w.quote(null, null)));                    // nor another provider
        expect("BAD_REQUEST", () -> w.quoteSvc.submit(p, r.id(), w.quote("1.00", null)));                    // fixed price: wrong labour
        expect("BAD_REQUEST", () -> w.quoteSvc.submit(p, r.id(), new SubmitQuoteRequest(null, null, null, null, null, Instant.now().minusSeconds(60), null)));
        expect("BAD_REQUEST", () -> w.quoteSvc.submit(p, r.id(), new SubmitQuoteRequest(null, null, null, null, null, Instant.now().plusSeconds(60L * 86400), null)));

        QuoteView q = w.quoteSvc.submit(p, r.id(), w.quote("450.00", null));
        assertEquals(new BigDecimal("450.00"), q.labourAmount());
        assertEquals(new BigDecimal("500.00"), q.total());          // 450 labour + 0 materials + 50 delivery + 0 other
        assertEquals(1, q.versionNo());
        assertEquals(ServiceRequestStatus.QUOTED, w.requestSvc.get(c, r.id()).status());
        assertTrue(w.notes.contains("Cust:SERVICE_QUOTE_UPDATE"));
        // only one live quote at a time
        expect("CONFLICT", () -> w.quoteSvc.submit(p, r.id(), w.quote(null, null)));
    }

    @Test
    void acceptingAQuoteCreatesTheBookingWithTheQuotedTotalAndNothingFromTheClient() {
        World w = new World();
        User p = w.user("Pro"); w.business(p, "P", true);
        User c = w.user("Cust");
        ListingView l = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.FIXED, "450.00", MaterialsResponsibility.NONE);
        RequestView r = w.requestSvc.create(c, w.ask(l.id(), null));
        QuoteView q = w.quoteSvc.submit(p, r.id(), w.quote(null, null));

        User stranger = w.user("Stranger");
        expect("NOT_FOUND", () -> w.quoteSvc.accept(stranger, r.id(), q.id()));
        expect("NOT_FOUND", () -> w.quoteSvc.accept(p, r.id(), q.id()));          // the provider cannot accept for the customer
        expect("NOT_FOUND", () -> w.quoteSvc.accept(c, r.id(), "other-quote"));

        BookingView b = w.quoteSvc.accept(c, r.id(), q.id());
        assertEquals(ServiceBookingStatus.BOOKED, b.status());
        assertEquals(new BigDecimal("500.00"), b.total());
        assertEquals("UNPAID", b.paymentStatus());
        assertEquals(ServiceRequestStatus.BOOKED, w.requestSvc.get(c, r.id()).status());
        assertTrue(w.notes.contains("Pro:SERVICE_BOOKING_UPDATE"));
        expect("CONFLICT", () -> w.quoteSvc.accept(c, r.id(), q.id()));            // no double booking
        expect("CONFLICT", () -> w.requestSvc.cancel(c, r.id()));                  // booked: cancel the booking instead
        expect("CONFLICT", () -> w.quoteSvc.submit(p, r.id(), w.quote(null, null)));
    }

    @Test
    void clarificationRevisionSupersedesOldQuoteAndExpiredQuotesCannotBeAccepted() {
        World w = new World();
        User p = w.user("Pro"); w.business(p, "P", true);
        User c = w.user("Cust");
        ListingView l = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.HOURLY, "120.00", MaterialsResponsibility.NONE);
        RequestView r = w.requestSvc.create(c, w.ask(l.id(), null));
        QuoteView v1 = w.quoteSvc.submit(p, r.id(), w.quote(null, "2.00"));
        assertEquals(new BigDecimal("240.00"), v1.labourAmount());

        QuoteView asked = w.quoteSvc.requestClarification(c, r.id(), v1.id(), new ClarificationRequest("Why 2 hours?"));
        assertEquals(ServiceQuoteStatus.CLARIFICATION_REQUESTED, asked.status());
        expect("CONFLICT", () -> w.quoteSvc.accept(c, r.id(), v1.id()));           // cannot accept while a question is open
        QuoteView v2 = w.quoteSvc.submit(p, r.id(), w.quote(null, "1.50"));
        assertEquals(2, v2.versionNo());
        assertEquals(new BigDecimal("180.00"), v2.labourAmount());
        RequestView after = w.requestSvc.get(c, r.id());
        assertEquals(ServiceQuoteStatus.SUPERSEDED, after.quotes().get(1).status());
        expect("CONFLICT", () -> w.quoteSvc.accept(c, r.id(), v1.id()));           // old version is dead

        // expiry: a quote past validity cannot be accepted
        ServiceQuote stored = (ServiceQuote) w.quotes.get(v2.id());
        stored.setValidUntil(Instant.now().minusSeconds(5));
        expect("CONFLICT", () -> w.quoteSvc.accept(c, r.id(), v2.id()));
        // reject path
        stored.setValidUntil(Instant.now().plusSeconds(3600));
        assertEquals(ServiceQuoteStatus.REJECTED, w.quoteSvc.reject(c, r.id(), v2.id()).status());
        expect("CONFLICT", () -> w.quoteSvc.reject(c, r.id(), v2.id()));
        // the provider may quote again after a rejection
        assertEquals(3, w.quoteSvc.submit(p, r.id(), w.quote(null, "1.00")).versionNo());
    }

    @Test
    void staleRequestsAndQuotesExpire() {
        World w = new World();
        User p = w.user("Pro"); w.business(p, "P", true);
        User c = w.user("Cust");
        ListingView l = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.FIXED, "450.00", MaterialsResponsibility.NONE);
        RequestView r = w.requestSvc.create(c, w.ask(l.id(), null));
        QuoteView q = w.quoteSvc.submit(p, r.id(), w.quote(null, null));
        ((ServiceRequest) w.reqs.get(r.id())).setExpiresAt(Instant.now().minusSeconds(10));
        assertEquals(1, w.requestSvc.expireDue());
        assertEquals(ServiceRequestStatus.EXPIRED, ((ServiceRequest) w.reqs.get(r.id())).getStatus());
        assertEquals(ServiceQuoteStatus.EXPIRED, ((ServiceQuote) w.quotes.get(q.id())).getStatus());
        assertEquals(0, w.requestSvc.expireDue());
        expect("CONFLICT", () -> w.quoteSvc.accept(c, r.id(), q.id()));
    }

    // ================================================================ materials (PULA Stock)

    @Test
    void materialsUsePulaStockPricedByTheServerAndFeedTheQuote() {
        World w = new World();
        User p = w.user("Pro"); Business pb = w.business(p, "P", true);
        User c = w.user("Cust");
        User sup = w.user("Supplier"); Business sb = w.business(sup, "Supplier Co", false);
        WholesaleProduct pipes = w.product("Copper pipe 15mm", "129.99", sb);
        ListingView l = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.FIXED, "450.00", MaterialsResponsibility.MIXED);
        RequestView r = w.requestSvc.create(c, new CreateServiceRequestRequest(l.id(), "Re-pipe", "loc", null, null, null, MaterialsResponsibility.MIXED, true, null));

        // each party can only add what they supply
        expect("FORBIDDEN", () -> w.materialSvc.add(c, r.id(), new AddMaterialRequest(pipes.getId(), 2, MaterialSupplier.PROVIDER)));
        expect("FORBIDDEN", () -> w.materialSvc.add(p, r.id(), new AddMaterialRequest(pipes.getId(), 2, MaterialSupplier.CUSTOMER)));
        User stranger = w.user("Stranger");
        expect("NOT_FOUND", () -> w.materialSvc.add(stranger, r.id(), new AddMaterialRequest(pipes.getId(), 1, MaterialSupplier.CUSTOMER)));
        expect("BAD_REQUEST", () -> w.materialSvc.add(p, r.id(), new AddMaterialRequest("missing", 1, MaterialSupplier.PROVIDER)));

        MaterialLineView line = w.materialSvc.add(p, r.id(), new AddMaterialRequest(pipes.getId(), 3, MaterialSupplier.PROVIDER));
        assertEquals(new BigDecimal("129.99"), line.unitPrice());                   // from PULA Stock, not from the client
        assertEquals(new BigDecimal("389.97"), line.lineTotal());
        MaterialLineView mine = w.materialSvc.add(c, r.id(), new AddMaterialRequest(pipes.getId(), 1, MaterialSupplier.CUSTOMER));
        expect("FORBIDDEN", () -> w.materialSvc.remove(p, r.id(), mine.id()));      // only the adder removes a line

        // the quote's materials amount is computed from PROVIDER lines only
        QuoteView q = w.quoteSvc.submit(p, r.id(), w.quote(null, null));
        assertEquals(new BigDecimal("389.97"), q.materialsAmount());
        assertEquals(new BigDecimal("889.97"), q.total());                          // 450 + 389.97 + 50
        // lines are locked while a quote awaits a decision
        expect("CONFLICT", () -> w.materialSvc.add(p, r.id(), new AddMaterialRequest(pipes.getId(), 1, MaterialSupplier.PROVIDER)));
        BookingView b = w.quoteSvc.accept(c, r.id(), q.id());
        expect("CONFLICT", () -> w.materialSvc.remove(c, r.id(), mine.id()));

        // linking the existing stock order: must be the supplying party's own order
        StockOrder order = new StockOrder();
        order.setBuyerBusiness(pb); order.setStatus(StockOrderStatus.PAID); order.setTotal(new BigDecimal("389.97"));
        StockOrderItem item = new StockOrderItem();
        item.setWholesaleProduct(pipes); item.setQuantityPacks(3);
        order.getItems().add(item);
        w.stockOrders.put(order.getId(), order);

        expect("FORBIDDEN", () -> w.materialSvc.linkStockOrder(c, r.id(), line.id(), order.getId()));      // customer cannot link provider's line
        User otherProvider = w.user("Other"); w.business(otherProvider, "Other", true);
        expect("NOT_FOUND", () -> w.materialSvc.linkStockOrder(otherProvider, r.id(), line.id(), order.getId()));
        MaterialLineView linked = w.materialSvc.linkStockOrder(p, r.id(), line.id(), order.getId());
        assertEquals(order.getId(), linked.stockOrderId());
        assertEquals(b.requestId(), r.id());

        // insufficient quantity / cancelled order / reuse on another request
        item.setQuantityPacks(1);
        ServiceMaterialLine stored = (ServiceMaterialLine) w.mats.get(line.id());
        stored.setStockOrder(null);
        expect("BAD_REQUEST", () -> w.materialSvc.linkStockOrder(p, r.id(), line.id(), order.getId()));
        item.setQuantityPacks(3);
        order.setStatus(StockOrderStatus.CANCELLED);
        expect("CONFLICT", () -> w.materialSvc.linkStockOrder(p, r.id(), line.id(), order.getId()));
    }

    @Test
    void materialsResponsibilityIsEnforced() {
        World w = new World();
        User p = w.user("Pro"); w.business(p, "P", true);
        User c = w.user("Cust");
        User sup = w.user("Sup"); Business sb = w.business(sup, "S", false);
        WholesaleProduct item = w.product("Cement", "99.00", sb);
        ListingView none = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.FIXED, "100.00", MaterialsResponsibility.NONE);
        RequestView r = w.requestSvc.create(c, w.ask(none.id(), null));
        expect("BAD_REQUEST", () -> w.materialSvc.add(p, r.id(), new AddMaterialRequest(item.getId(), 1, MaterialSupplier.PROVIDER)));
        expect("BAD_REQUEST", () -> w.materialSvc.add(c, r.id(), new AddMaterialRequest(item.getId(), 1, MaterialSupplier.CUSTOMER)));
    }

    // ================================================================ booking, payment, review

    @Test
    void jobLifecycleAndStatusTamperingAreRefused() {
        World w = new World();
        User p = w.user("Pro"); w.business(p, "P", true);
        User c = w.user("Cust");
        ListingView l = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.FIXED, "450.00", MaterialsResponsibility.NONE);
        RequestView r = w.requestSvc.create(c, w.ask(l.id(), null));
        BookingView b = w.quoteSvc.accept(c, r.id(), w.quoteSvc.submit(p, r.id(), w.quote(null, null)).id());

        expect("NOT_FOUND", () -> w.bookingSvc.start(c, b.id()));                  // customers cannot run the job
        expect("NOT_FOUND", () -> w.bookingSvc.start(w.user("Stranger"), b.id()));
        expect("CONFLICT", () -> w.bookingSvc.complete(p, b.id()));                // BOOKED -> COMPLETED skips IN_PROGRESS
        assertEquals(ServiceBookingStatus.IN_PROGRESS, w.bookingSvc.start(p, b.id()).status());
        assertEquals(ServiceRequestStatus.IN_PROGRESS, w.requestSvc.get(c, r.id()).status());
        expect("CONFLICT", () -> w.bookingSvc.start(p, b.id()));
        BookingView done = w.bookingSvc.complete(p, b.id());
        assertEquals(ServiceBookingStatus.COMPLETED, done.status());
        assertEquals(ServiceRequestStatus.COMPLETED, w.requestSvc.get(c, r.id()).status());
        expect("CONFLICT", () -> w.bookingSvc.cancel(c, b.id(), "changed my mind"));   // finished jobs cannot be cancelled
        expect("CONFLICT", () -> w.bookingSvc.complete(p, b.id()));
    }

    @Test
    void cancellationByEitherPartyBeforeCompletionAndPrivacyOfBookings() {
        World w = new World();
        User p = w.user("Pro"); w.business(p, "P", true);
        User c = w.user("Cust");
        ListingView l = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.FIXED, "450.00", MaterialsResponsibility.NONE);
        RequestView r = w.requestSvc.create(c, w.ask(l.id(), null));
        BookingView b = w.quoteSvc.accept(c, r.id(), w.quoteSvc.submit(p, r.id(), w.quote(null, null)).id());
        User stranger = w.user("Stranger");
        expect("NOT_FOUND", () -> w.bookingSvc.get(stranger, b.id()));
        expect("NOT_FOUND", () -> w.bookingSvc.cancel(stranger, b.id(), "x"));
        assertEquals(1, w.bookingSvc.mine(c).size());
        assertEquals(0, w.bookingSvc.mine(stranger).size());
        assertEquals(1, w.bookingSvc.forProvider(p, w.businessByOwner.get(p.getId()).getId()).size());
        assertEquals(0, w.bookingSvc.forProvider(stranger, w.businessByOwner.get(p.getId()).getId()).size()); // not the owner

        BookingView cancelled = w.bookingSvc.cancel(c, b.id(), "No longer needed");
        assertEquals(ServiceBookingStatus.CANCELLED, cancelled.status());
        assertEquals("No longer needed", cancelled.cancelReason());
        assertEquals(ServiceRequestStatus.CANCELLED, w.requestSvc.get(c, r.id()).status());
        expect("CONFLICT", () -> w.bookingSvc.start(p, b.id()));
        assertTrue(w.audits.contains("SERVICE_BOOKING_CANCELLED"));
    }

    @Test
    void paymentUsesTheExistingPathChargesTheBookingTotalAndNeverChargesTwice() {
        World w = new World();
        User p = w.user("Pro"); w.business(p, "P", true);
        User c = w.user("Cust");
        ListingView l = w.activeListing(p, w.category("plumbing", true), ServicePricingModel.FIXED, "450.00", MaterialsResponsibility.NONE);
        RequestView r = w.requestSvc.create(c, w.ask(l.id(), null));
        BookingView b = w.quoteSvc.accept(c, r.id(), w.quoteSvc.submit(p, r.id(), w.quote(null, null)).id());
        expect("CONFLICT", () -> w.bookingSvc.pay(c, b.id(), null));                 // not complete yet: payment is due after the job
        w.bookingSvc.start(p, b.id());
        w.bookingSvc.complete(p, b.id());

        expect("NOT_FOUND", () -> w.bookingSvc.pay(w.user("Stranger"), b.id(), null));
        expect("NOT_FOUND", () -> w.bookingSvc.pay(p, b.id(), null));                 // the provider cannot pay for the customer

        PaymentSessionView s1 = w.bookingSvc.pay(c, b.id(), null);
        assertEquals(new BigDecimal("500.00"), s1.amount());                          // booking total (server-computed), no client amount exists
        assertEquals("PULA-SV-" + b.id() + "-1", s1.paymentReference());
        assertEquals(List.of(s1.paymentReference() + "|500.00|ZAR"), w.gatewayRefs);
        Payment pay = ((ServiceBooking) w.bookings.get(b.id())).getPayment();
        assertEquals(PaymentPurpose.MARKET_PAYMENT, pay.getPurpose());
        assertEquals(PaymentStatus.PROCESSING, pay.getStatus());
        // a second tap resumes the same checkout; it does not start another charge
        PaymentSessionView s2 = w.bookingSvc.pay(c, b.id(), null);
        assertEquals(s1.paymentReference(), s2.paymentReference());
        assertEquals(1, w.gatewayRefs.size());
        expect("CONFLICT", () -> w.bookingSvc.cancel(c, b.id(), "too late"));         // payment in progress

        // provider-verified failure -> retry creates attempt 2 with a new reference
        pay.transitionTo(PaymentStatus.FAILED);
        w.bookingSvc.onPaymentFailed((ServiceBooking) w.bookings.get(b.id()));
        PaymentSessionView s3 = w.bookingSvc.pay(c, b.id(), null);
        assertEquals("PULA-SV-" + b.id() + "-2", s3.paymentReference());
        assertEquals(2, w.gatewayRefs.size());

        // success
        Payment second = ((ServiceBooking) w.bookings.get(b.id())).getPayment();
        second.transitionTo(PaymentStatus.SUCCESSFUL);
        w.bookingSvc.onPaymentSuccess((ServiceBooking) w.bookings.get(b.id()));
        assertEquals("PAID", w.bookingSvc.get(c, b.id()).paymentStatus());
        expect("CONFLICT", () -> w.bookingSvc.pay(c, b.id(), null));                  // already paid
        assertTrue(w.notes.contains("Pro:PAYMENT_SUCCESS"));
        assertTrue(w.audits.contains("SERVICE_PAYMENT_CONFIRMED"));
    }

    @Test
    void saveToBuyHooksRunAroundTheServicePaymentWithTheServerTotal() {
        World w = new World();
        World.Job j = w.completedJob();
        PaymentSessionView s = w.bookingSvc.pay(j.customer(), j.booking().id(), new PayRequest("s2b-1"));
        assertEquals("validate:s2b-1:SERVICE:500.00", w.s2bCalls.get(0));           // vertical SERVICE, total from the booking
        assertEquals("attach:SERVICE_BOOKING:" + j.booking().id(), w.s2bCalls.get(1));
        assertEquals("s2b-1", ((ServiceBooking) w.bookings.get(j.booking().id())).getSaveToBuyRequestId());
        Payment p = ((ServiceBooking) w.bookings.get(j.booking().id())).getPayment();
        p.transitionTo(PaymentStatus.SUCCESSFUL);
        w.bookingSvc.onPaymentSuccess((ServiceBooking) w.bookings.get(j.booking().id()));
        assertTrue(w.s2bCalls.contains("paid:SERVICE_BOOKING:500.00"));
        assertNotNull(s.checkoutUrl());
    }

    @Test
    void failedServicePaymentReleasesTheSaveToBuyRequest() {
        World w = new World();
        World.Job j = w.completedJob();
        w.bookingSvc.pay(j.customer(), j.booking().id(), new PayRequest("s2b-9"));
        ServiceBooking b = (ServiceBooking) w.bookings.get(j.booking().id());
        b.getPayment().transitionTo(PaymentStatus.FAILED);
        w.bookingSvc.onPaymentFailed(b);
        assertTrue(w.s2bCalls.contains("failed:SERVICE_BOOKING"));
    }

    @Test
    void reviewsNeedACompletedPaidBookingAndExtendTheExistingReviewSystem() {
        World w = new World();
        World.Job j = w.completedJob();
        ServiceReviewRequest rev = new ServiceReviewRequest(5, "Great job", true);
        expect("CONFLICT", () -> w.bookingSvc.review(j.customer(), j.booking().id(), rev));      // not paid yet
        w.bookingSvc.pay(j.customer(), j.booking().id(), null);
        ServiceBooking b = (ServiceBooking) w.bookings.get(j.booking().id());
        b.getPayment().transitionTo(PaymentStatus.SUCCESSFUL);

        assertTrue(w.bookingSvc.get(j.customer(), j.booking().id()).canReview());
        expect("NOT_FOUND", () -> w.bookingSvc.review(w.user("Stranger"), j.booking().id(), rev));
        expect("NOT_FOUND", () -> w.bookingSvc.review(j.provider(), j.booking().id(), rev));    // providers cannot review themselves
        expect("BAD_REQUEST", () -> w.bookingSvc.review(j.customer(), j.booking().id(), new ServiceReviewRequest(6, null, null)));

        List<ReviewResponse> out = w.bookingSvc.review(j.customer(), j.booking().id(), rev);
        assertEquals(2, out.size());                                                           // provider + listing
        Business biz = w.businessByOwner.get(j.provider().getId());
        assertEquals(new BigDecimal("5.00"), biz.getRatingAverage());                         // the shared Business aggregate
        assertEquals(1, biz.getRatingCount());
        ServiceListing listing = (ServiceListing) w.listings.get(j.listing().id());
        assertEquals(new BigDecimal("5.00"), listing.getRatingAverage());
        assertEquals(1, listing.getRatingCount());
        expect("CONFLICT", () -> w.bookingSvc.review(j.customer(), j.booking().id(), rev));    // once per booking
        BookingView after = w.bookingSvc.get(j.customer(), j.booking().id());
        assertTrue(after.reviewed());
        assertFalse(after.canReview());
        assertTrue(w.audits.contains("SERVICE_REVIEW_CREATED"));
        // a service review is tied to the booking, not to a retail order
        Review stored = (Review) w.reviewStore.values().iterator().next();
        assertNull(stored.getOrder());
        assertEquals(j.booking().id(), stored.getServiceBooking().getId());
    }

    // ================================================================ catalogue (admin) & AI boundary

    @Test
    void adminManagedCategories() {
        World w = new World();
        User admin = w.admin();
        CategoryView parent = w.catalog.create(admin, new CategoryRequest(null, "Home Improvement", null, 1, true));
        assertEquals("home-improvement", parent.slug());
        CategoryView child = w.catalog.create(admin, new CategoryRequest(parent.id(), "Plumbing", null, 1, true));
        assertEquals(parent.id(), child.parentId());
        expect("CONFLICT", () -> w.catalog.create(admin, new CategoryRequest(null, "plumbing", null, 2, true)));
        expect("BAD_REQUEST", () -> w.catalog.create(admin, new CategoryRequest(child.id(), "Too deep", null, 1, true)));
        expect("BAD_REQUEST", () -> w.catalog.create(admin, new CategoryRequest(null, "!!!", null, 1, true)));
        CategoryView off = w.catalog.update(admin, child.id(), new CategoryRequest(parent.id(), "Plumbing", "plumbing", 1, false));
        assertFalse(off.active());
        assertEquals(1, w.catalog.listActive().size());
        assertEquals(2, w.catalog.listAll(admin).size());
        assertTrue(w.audits.contains("SERVICE_CATEGORY_CREATED") && w.audits.contains("SERVICE_CATEGORY_UPDATED"));
    }

    @Test
    void insightsExplainQuotesAndComparisonsButChangeNothing() {
        World w = new World();
        World.Job j = w.completedJob();
        String quoteId = w.requestSvc.get(j.customer(), j.request().id()).quotes().get(0).id();
        InsightsView v = w.insightSvc.explainQuote(j.customer(), j.request().id(), quoteId);
        assertTrue(v.summary().contains("500.00"));
        assertTrue(v.points().stream().anyMatch(s -> s.startsWith("Labour: ZAR 450.00") && s.contains("90%")));
        assertTrue(v.notice().contains("Nothing is accepted, booked, ordered or paid"));
        expect("NOT_FOUND", () -> w.insightSvc.explainQuote(w.user("Stranger"), j.request().id(), quoteId));
        InsightsView cmp = w.insightSvc.compare(List.of(j.listing().id()));
        assertTrue(cmp.points().stream().anyMatch(s -> s.contains("Lowest listed price")));
        // explaining changed no state
        assertEquals(ServiceBookingStatus.COMPLETED, ((ServiceBooking) w.bookings.get(j.booking().id())).getStatus());
    }
}
