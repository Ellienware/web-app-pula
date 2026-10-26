package com.momocircle.service.momoride;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.Payment;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.momoride.*;
import com.momocircle.dto.momoride.MoMoRideDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.*;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.momoride.*;
import com.momocircle.security.PaymentCodeRateLimiter;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.FinancialValidationService;
import com.momocircle.service.NotificationService;
import com.momocircle.sms.SmsProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * All MoMoRide business logic. Reuses the platform's existing Payment,
 * PaymentProvider, PaymentIdempotencyService, FinancialValidationService and
 * AuditLogService rather than duplicating any of them — see MOMORIDE_PHASEF.md
 * for the full "what's real / what's sandboxed" breakdown.
 */
@Service
public class MoMoRideService {
    /** Phase 7 transaction-risk gate. Always injected in a running application; null only in hand-constructed unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.risk.PaymentGate riskGate;


    private static final Duration PAYMENT_REQUEST_TTL = Duration.ofMinutes(15);

    private final DriverRepository drivers;
    private final TaxiRepository taxis;
    private final TaxiPaymentRequestRepository requests;
    private final RideRepository rides;
    private final RideBookingRepository bookings;
    private final RideRatingRepository ratings;
    private final MoMoRideReportRepository reports;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final PaymentProvider provider;
    private final PaymentIdempotencyService idem;
    private final FinancialValidationService financial;
    private final CurrencyProperties currencies;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final SmsProvider smsProvider;
    private final PaymentCodeRateLimiter paymentCodeRateLimiter;
    private final RideSeatConcurrencyService seatConcurrency;

    public MoMoRideService(DriverRepository d, TaxiRepository t, TaxiPaymentRequestRepository r,
            RideRepository rides, RideBookingRepository b, RideRatingRepository ratings,
            MoMoRideReportRepository reports, PaymentRepository p, UserRepository users,
            PaymentProvider provider, PaymentIdempotencyService idem, FinancialValidationService financial,
            CurrencyProperties currencies, PasswordEncoder passwordEncoder, AuditLogService auditLogService,
            NotificationService notificationService, SmsProvider smsProvider,
            PaymentCodeRateLimiter paymentCodeRateLimiter, RideSeatConcurrencyService seatConcurrency) {
        this.drivers = d; this.taxis = t; this.requests = r; this.rides = rides; this.bookings = b;
        this.ratings = ratings; this.reports = reports; this.payments = p; this.users = users;
        this.provider = provider; this.idem = idem; this.financial = financial; this.currencies = currencies;
        this.passwordEncoder = passwordEncoder; this.auditLogService = auditLogService;
        this.notificationService = notificationService; this.smsProvider = smsProvider;
        this.paymentCodeRateLimiter = paymentCodeRateLimiter; this.seatConcurrency = seatConcurrency;
    }

    // ================= DRIVER =================

    @Transactional
    public DriverResponse registerDriver(User user, RegisterDriverRequest req) {
        if (drivers.findByUserId(user.getId()).isPresent())
            throw ApiException.conflict("You are already registered as a PULA Ride driver");
        if (taxis.existsByRegistrationNumber(req.registrationNumber()))
            throw ApiException.conflict("Taxi registration already exists");
        Driver d = new Driver();
        d.setUser(user);
        d.setPinHash(passwordEncoder.encode(req.pin()));
        d = drivers.save(d);
        Taxi t = new Taxi();
        t.setDriver(d);
        t.setRegistrationNumber(req.registrationNumber());
        t.setMakeModel(req.makeModel());
        t.setPublicTaxiId(uniquePublicId());
        t.setPaymentCode(uniquePaymentCode());
        t.setQrToken(uniqueQrToken());
        t = taxis.save(t);
        auditLogService.record(user, "MOMORIDE_DRIVER_REGISTERED", "Driver", d.getId(), "Registered as PULA Ride driver");
        auditLogService.record(user, "MOMORIDE_TAXI_CREATED", "Taxi", t.getId(), "Taxi " + t.getPublicTaxiId() + " created");
        return driver(d, t);
    }

    @Transactional(readOnly = true)
    public DriverResponse myDriver(User u) {
        Driver d = drivers.findByUserId(u.getId()).orElseThrow(() -> ApiException.notFound("PULA Ride driver profile not found"));
        Taxi t = taxis.findByDriverIdOrderByCreatedAtDesc(d.getId()).stream().findFirst().orElse(null);
        return driver(d, t);
    }

    // ================= TAXI LOOKUP =================

    @Transactional(readOnly = true)
    public TaxiResponse lookupTaxi(User actor, String code) {
        rateLimitCode(actor.getId());
        Taxi t = taxis.findByPaymentCode(code).orElseThrow(() -> ApiException.notFound("Taxi payment code not found"));
        requireTaxiActive(t);
        return taxi(t);
    }

    @Transactional(readOnly = true)
    public TaxiResponse lookupTaxiByQr(User actor, String qrToken) {
        rateLimitCode(actor.getId());
        Taxi t = taxis.findByQrToken(qrToken).orElseThrow(() -> ApiException.notFound("QR code not recognized"));
        requireTaxiActive(t);
        return taxi(t);
    }

    /** USSD-only variant: rate-limited by phone number since there is no authenticated user yet at lookup time. */
    @Transactional(readOnly = true)
    public Taxi lookupTaxiEntityForUssd(String code) {
        Taxi t = taxis.findByPaymentCode(code).orElseThrow(() -> ApiException.notFound("Invalid taxi code"));
        requireTaxiActive(t);
        return t;
    }

    private void requireTaxiActive(Taxi t) {
        if (t.getStatus() != Taxi.TaxiStatus.ACTIVE || t.getDriver().getStatus() != Driver.DriverStatus.ACTIVE
                || !t.getDriver().getUser().isActive()) {
            throw ApiException.badRequest("This taxi is not active");
        }
    }

    private void rateLimitCode(String key) {
        paymentCodeRateLimiter.checkAllowed(key);
        paymentCodeRateLimiter.recordAttempt(key);
    }

    // ================= TAXI PAYMENT =================

    @Transactional
    public TaxiPaymentResponse payTaxi(User payer, PayTaxiRequest req) {
        rateLimitCode(payer.getId());
        return doPayTaxi(payer, req.paymentCode(), req.amount(), req.currency(), req.idempotencyKey());
    }

    /** Legacy overload: no session to tie a retry to, so every call is a new payment. Prefer the sessionId overload. */
    @Transactional
    public TaxiPaymentResponse payTaxiFromUssd(User payer, String paymentCode, BigDecimal amount, String currency) {
        rateLimitCode(payer.getId());
        return doPayTaxi(payer, paymentCode, amount, currency, "ussd_" + UUID.randomUUID());
    }

    /**
     * USSD payment with a RETRY-SAFE idempotency key. A USSD gateway can re-deliver the same "1. Confirm" input; with a random
     * key that was a second charge. The key now derives from the session, the taxi code and the amount (scoped to the payer by
     * the idempotency service), so a re-delivery replays the first payment instead of charging again. A new dial creates a new
     * session id and therefore a new, legitimate payment.
     */
    @Transactional
    public TaxiPaymentResponse payTaxiFromUssd(User payer, String paymentCode, BigDecimal amount, String currency, String sessionId) {
        rateLimitCode(payer.getId());
        String key = sessionId == null || sessionId.isBlank() ? "ussd_" + UUID.randomUUID()
                : "ussd:" + payer.getId() + ":" + sessionId + ":" + paymentCode + ":" + amount.stripTrailingZeros().toPlainString();
        return doPayTaxi(payer, paymentCode, amount, currency, key);
    }

    private TaxiPaymentResponse doPayTaxi(User payer, String paymentCode, BigDecimal amount, String currency, String idempotencyKey) {
        financial.requireValidAmount(amount, "amount");
        String c = currency.toUpperCase();
        financial.requireValidCurrency(c);
        Taxi t = taxis.findByPaymentCode(paymentCode).orElseThrow(() -> ApiException.notFound("Taxi payment code not found"));
        requireTaxiActive(t);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey;
        // Phase 7: explicit risk hook. GateTicket.DISABLED (no-op) unless app.risk.taxi-enabled=true, so the existing taxi flow,
        // endpoints, driver-first semantics and provider call are unchanged by default.
        boolean riskSimulated = com.momocircle.risk.PaymentGate.isSimulatedProvider(provider.name());
        com.momocircle.risk.GateTicket riskTicket = riskGate == null ? null : riskGate.authorise(payer,
                new com.momocircle.risk.GateRequest(com.momocircle.risk.RiskType.TAXI, key, amount, c, t.getId(), null, true, null, riskSimulated));
        if (riskGate != null) riskGate.bind(payer, riskTicket, key, amount, c, riskSimulated);
        var res = idem.reserve(key, amount, c, provider.name());
        Payment p = res.payment();
        if (!res.alreadyExisted()) {
            auditLogService.record(payer, "MOMORIDE_PAYMENT_INITIATED", "Payment", p.getId(),
                    "Taxi fare " + amount + " " + c + " to " + t.getPublicTaxiId());
            ChargeResult result = provider.charge(new ChargeRequest(amount, c, payer.getPhoneNumber(), key,
                    "PULA Ride taxi fare " + t.getPublicTaxiId()));
            p.transitionTo(result.status());
            p.setProviderReference(result.providerReference());
            p.setFailureReason(result.failureReason());
            payments.save(p);
            if (riskGate != null) riskGate.recordOutcome(riskTicket, p.getStatus() != PaymentStatus.FAILED, "charge " + p.getStatus());
            notifyPaymentOutcome(payer, t.getDriver().getUser(), p, "taxi fare to " + t.getPublicTaxiId());
        } else if (p.getStatus() == PaymentStatus.PENDING) {
            throw ApiException.conflict("A payment with this idempotency key is already in progress");
        }
        return new TaxiPaymentResponse(p.getId(), t.getId(), t.getPublicTaxiId(), t.getDriver().getUser().getFullName(),
                p.getAmount(), p.getCurrency(), p.getStatus().name(), p.getProviderReference());
    }

    private void notifyPaymentOutcome(User payer, User driverUser, Payment p, String description) {
        if (p.getStatus() == PaymentStatus.SUCCESSFUL) {
            notificationService.notify(payer, NotificationType.PAYMENT_SUCCESS, "Payment of " + p.getAmount()
                    + " " + p.getCurrency() + " for " + description + " was successful", p.getId());
            notificationService.notify(driverUser, NotificationType.PAYMENT_SUCCESS, "Received " + p.getAmount()
                    + " " + p.getCurrency() + " via PULA Ride", p.getId());
            smsProvider.send(payer.getPhoneNumber(), "PULA Ride: payment of " + p.getAmount() + " " + p.getCurrency()
                    + " successful. Ref " + p.getId());
            smsProvider.send(driverUser.getPhoneNumber(), "PULA Ride: you received " + p.getAmount() + " "
                    + p.getCurrency());
        } else if (p.getStatus() == PaymentStatus.FAILED) {
            notificationService.notify(payer, NotificationType.PAYMENT_FAILED, "Payment for " + description
                    + " failed" + (p.getFailureReason() != null ? ": " + p.getFailureReason() : ""), p.getId());
            smsProvider.send(payer.getPhoneNumber(), "PULA Ride: your payment of " + p.getAmount() + " "
                    + p.getCurrency() + " failed.");
        }
    }

    // ================= DRIVER PAYMENT REQUESTS =================

    @Transactional
    public TaxiPaymentRequestResponse createPaymentRequest(User driverUser, CreateTaxiPaymentRequest req) {
        return doCreatePaymentRequest(driverUser, req.amount(), req.currency(), req.passengerPhone());
    }

    @Transactional
    public TaxiPaymentRequestResponse createPaymentRequestFromUssd(User driverUser, BigDecimal amount, String currency, String passengerPhone) {
        return doCreatePaymentRequest(driverUser, amount, currency, passengerPhone);
    }

    private TaxiPaymentRequestResponse doCreatePaymentRequest(User driverUser, BigDecimal amount, String currency, String passengerPhone) {
        Driver d = drivers.findByUserId(driverUser.getId()).orElseThrow(() -> ApiException.forbidden("PULA Ride driver profile required"));
        if (d.getStatus() != Driver.DriverStatus.ACTIVE) throw ApiException.forbidden("Driver is not active");
        Taxi t = taxis.findByDriverIdOrderByCreatedAtDesc(d.getId()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Taxi not found"));
        financial.requireValidAmount(amount, "amount");
        String c = currency.toUpperCase();
        financial.requireValidCurrency(c);
        TaxiPaymentRequest r = new TaxiPaymentRequest();
        r.setDriver(d);
        r.setTaxi(t);
        r.setAmount(amount);
        r.setCurrency(c);
        r.setExpiresAt(Instant.now().plus(PAYMENT_REQUEST_TTL));
        if (passengerPhone != null && !passengerPhone.isBlank()) {
            users.findByPhoneNumber(passengerPhone.trim()).ifPresent(passenger -> {
                r.setPassenger(passenger);
                notificationService.notify(passenger, NotificationType.PAYMENT_REQUEST,
                        driverUser.getFullName() + " is requesting " + amount + " " + c + " for a taxi fare", null);
                smsProvider.send(passenger.getPhoneNumber(), "PULA Ride: " + driverUser.getFullName()
                        + " requests " + amount + " " + c + " for your taxi fare. Reply via the PULA app.");
            });
        }
        TaxiPaymentRequest saved = requests.save(r);
        auditLogService.record(driverUser, "MOMORIDE_PAYMENT_REQUEST_CREATED", "TaxiPaymentRequest", saved.getId(),
                amount + " " + c);
        return reqResp(saved);
    }

    @Transactional(readOnly = true)
    public List<TaxiPaymentRequestResponse> myRequests(User u) {
        Driver d = drivers.findByUserId(u.getId()).orElseThrow(() -> ApiException.notFound("Driver profile not found"));
        return requests.findByDriverIdOrderByCreatedAtDesc(d.getId()).stream().map(this::reqResp).toList();
    }

    @Transactional
    public TaxiPaymentResponse fulfillPaymentRequest(User passenger, String requestId, FulfillPaymentRequestRequest req) {
        TaxiPaymentRequest r = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("Payment request not found"));
        if (r.getStatus() == TaxiPaymentRequest.Status.EXPIRED
                || (r.getExpiresAt() != null && r.getExpiresAt().isBefore(Instant.now()))) {
            r.setStatus(TaxiPaymentRequest.Status.EXPIRED);
            requests.save(r);
            throw ApiException.badRequest("This payment request has expired");
        }
        if (r.getStatus() != TaxiPaymentRequest.Status.OPEN) throw ApiException.conflict("This payment request is no longer open");
        if (r.getPassenger() != null && !r.getPassenger().getId().equals(passenger.getId()))
            throw ApiException.forbidden("This payment request was addressed to a different passenger");

        String key = req.idempotencyKey() == null || req.idempotencyKey().isBlank()
                ? UUID.randomUUID().toString() : req.idempotencyKey();
        boolean riskSimulated = com.momocircle.risk.PaymentGate.isSimulatedProvider(provider.name());
        com.momocircle.risk.GateTicket riskTicket = riskGate == null ? null : riskGate.authorise(passenger,
                new com.momocircle.risk.GateRequest(com.momocircle.risk.RiskType.TAXI, key, r.getAmount(), r.getCurrency(),
                        r.getTaxi().getId(), null, true, null, riskSimulated));
        if (riskGate != null) riskGate.bind(passenger, riskTicket, key, r.getAmount(), r.getCurrency(), riskSimulated);
        var res = idem.reserve(key, r.getAmount(), r.getCurrency(), provider.name());
        Payment p = res.payment();
        if (!res.alreadyExisted()) {
            r.setStatus(TaxiPaymentRequest.Status.PROCESSING);
            requests.save(r);
            ChargeResult result = provider.charge(new ChargeRequest(r.getAmount(), r.getCurrency(), passenger.getPhoneNumber(),
                    key, "PULA Ride payment request " + r.getId()));
            p.transitionTo(result.status());
            p.setProviderReference(result.providerReference());
            p.setFailureReason(result.failureReason());
            payments.save(p);
            if (riskGate != null) riskGate.recordOutcome(riskTicket, p.getStatus() != PaymentStatus.FAILED, "charge " + p.getStatus());
            r.setPassenger(passenger);
            r.setPayment(p);
            r.setStatus(p.getStatus() == PaymentStatus.SUCCESSFUL ? TaxiPaymentRequest.Status.FULFILLED
                    : p.getStatus() == PaymentStatus.FAILED ? TaxiPaymentRequest.Status.FAILED
                    : TaxiPaymentRequest.Status.PROCESSING);
            requests.save(r);
            notifyPaymentOutcome(passenger, r.getDriver().getUser(), p, "a driver payment request");
            auditLogService.record(passenger, "MOMORIDE_PAYMENT_REQUEST_FULFILLED", "TaxiPaymentRequest", r.getId(), p.getStatus().name());
        } else if (p.getStatus() == PaymentStatus.PENDING) {
            throw ApiException.conflict("A payment with this idempotency key is already in progress");
        }
        return new TaxiPaymentResponse(p.getId(), r.getTaxi().getId(), r.getTaxi().getPublicTaxiId(),
                r.getDriver().getUser().getFullName(), p.getAmount(), p.getCurrency(), p.getStatus().name(), p.getProviderReference());
    }

    @Transactional
    public void cancelPaymentRequest(User driverUser, String requestId) {
        TaxiPaymentRequest r = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("Payment request not found"));
        if (!r.getDriver().getUser().getId().equals(driverUser.getId())) throw ApiException.forbidden("This is not your payment request");
        if (r.getStatus() != TaxiPaymentRequest.Status.OPEN) throw ApiException.conflict("This payment request can no longer be cancelled");
        r.setStatus(TaxiPaymentRequest.Status.CANCELLED);
        requests.save(r);
        auditLogService.record(driverUser, "MOMORIDE_PAYMENT_REQUEST_CANCELLED", "TaxiPaymentRequest", r.getId(), null);
    }

    /** Scheduled by MoMoRideScheduledTasks. Expires stale OPEN payment requests. */
    @Transactional
    public int expireStalePaymentRequests() {
        List<TaxiPaymentRequest> stale = requests.findByStatusAndExpiresAtBefore(TaxiPaymentRequest.Status.OPEN, Instant.now());
        for (TaxiPaymentRequest r : stale) {
            r.setStatus(TaxiPaymentRequest.Status.EXPIRED);
            requests.save(r);
            auditLogService.record(null, "MOMORIDE_PAYMENT_REQUEST_EXPIRED", "TaxiPaymentRequest", r.getId(), null);
        }
        return stale.size();
    }

    // ================= RIDES =================

    @Transactional
    public RideResponse createRide(User u, CreateRideRequest req) {
        Driver d = drivers.findByUserId(u.getId()).orElseThrow(() -> ApiException.forbidden("PULA Ride driver profile required"));
        if (d.getStatus() != Driver.DriverStatus.ACTIVE) throw ApiException.forbidden("Driver is not active");
        if (req.departureTime().isBefore(LocalDateTime.now())) throw ApiException.badRequest("Departure time must be in the future");
        String c = req.currency().toUpperCase();
        financial.requireValidCurrency(c);
        if (req.pricePerPassenger().signum() < 0) throw ApiException.badRequest("Price cannot be negative");
        if (req.pricePerPassenger().signum() > 0) financial.requireValidAmount(req.pricePerPassenger(), "pricePerPassenger");
        Ride r = new Ride();
        r.setDriver(d);
        r.setOrigin(req.origin().trim());
        r.setDestination(req.destination().trim());
        r.setDepartureTime(req.departureTime());
        r.setAvailableSeats(req.availableSeats());
        r.setPricePerPassenger(req.pricePerPassenger());
        r.setCurrency(c);
        r = rides.save(r);
        auditLogService.record(u, "MOMORIDE_RIDE_CREATED", "Ride", r.getId(),
                r.getOrigin() + " -> " + r.getDestination() + " at " + r.getDepartureTime());
        return ride(r);
    }

    @Transactional(readOnly = true)
    public List<RideResponse> search(String origin, String destination, LocalDate date) {
        LocalDateTime from = date == null ? LocalDateTime.now() : date.atStartOfDay();
        LocalDateTime to = date == null ? LocalDateTime.now().plusDays(30) : date.atTime(LocalTime.MAX);
        return rides.findByStatusAndOriginContainingIgnoreCaseAndDestinationContainingIgnoreCaseAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                Ride.Status.OPEN, origin == null ? "" : origin, destination == null ? "" : destination, from, to)
                .stream().map(this::ride).toList();
    }

    @Transactional(readOnly = true)
    public List<RideResponse> myRides(User u) {
        Driver d = drivers.findByUserId(u.getId()).orElseThrow(() -> ApiException.notFound("Driver profile not found"));
        return rides.findByDriverIdOrderByDepartureTimeDesc(d.getId()).stream().map(this::ride).toList();
    }

    @Transactional(readOnly = true)
    public List<RideBookingResponse> myBookings(User u) {
        return bookings.findByPassengerIdOrderByCreatedAtDesc(u.getId()).stream().map(this::bookingResp).toList();
    }

    @Transactional
    public RideBookingResponse book(User u, String rideId, BookRideRequest req) {
        if (bookings.findByRideIdAndPassengerId(rideId, u.getId()).isPresent())
            throw ApiException.conflict("You already booked this ride");

        // See RideSeatConcurrencyService's javadoc for why this must go through a separate bean
        // rather than a same-class @Transactional(REQUIRES_NEW) method.
        Ride r = seatConcurrency.reserveSeatWithRetry(rideId, u.getId());

        Payment p = null;
        String paymentStatus = "NOT_REQUIRED";
        if (r.getPricePerPassenger().signum() > 0) {
            String key = req.idempotencyKey() == null || req.idempotencyKey().isBlank()
                    ? UUID.randomUUID().toString() : req.idempotencyKey();
            var res = idem.reserve(key, r.getPricePerPassenger(), r.getCurrency(), provider.name());
            p = res.payment();
            if (!res.alreadyExisted()) {
                ChargeResult cr = provider.charge(new ChargeRequest(r.getPricePerPassenger(), r.getCurrency(),
                        u.getPhoneNumber(), key, "PULA Ride booking " + r.getId()));
                p.transitionTo(cr.status());
                p.setProviderReference(cr.providerReference());
                p.setFailureReason(cr.failureReason());
                payments.save(p);
            }
            paymentStatus = p.getStatus().name();
            if (p.getStatus() == PaymentStatus.FAILED) {
                seatConcurrency.releaseSeat(r.getId());
                throw ApiException.badRequest("Ride payment failed");
            }
        }
        RideBooking b = new RideBooking();
        b.setRide(r);
        b.setPassenger(u);
        b.setPayment(p);
        b = bookings.save(b);
        auditLogService.record(u, "MOMORIDE_RIDE_BOOKED", "Ride", r.getId(), "Booking " + b.getId());
        notificationService.notify(r.getDriver().getUser(), NotificationType.SYSTEM,
                u.getFullName() + " booked a seat on your ride " + r.getOrigin() + " -> " + r.getDestination(), r.getId());
        smsProvider.send(u.getPhoneNumber(), "PULA Ride: seat booked on " + r.getOrigin() + " -> " + r.getDestination()
                + " at " + r.getDepartureTime());
        return new RideBookingResponse(b.getId(), r.getId(), u.getFullName(), b.getStatus().name(),
                p == null ? null : p.getId(), paymentStatus);
    }

    @Transactional
    public void cancelBooking(User u, String bookingId) {
        RideBooking b = bookings.findById(bookingId).orElseThrow(() -> ApiException.notFound("Booking not found"));
        if (!b.getPassenger().getId().equals(u.getId())) throw ApiException.forbidden("This is not your booking");
        if (b.getStatus() != RideBooking.Status.CONFIRMED) throw ApiException.conflict("This booking is already cancelled");
        Ride r = b.getRide();
        if (r.getStatus() == Ride.Status.COMPLETED || r.getStatus() == Ride.Status.CANCELLED)
            throw ApiException.conflict("This ride can no longer be cancelled");
        if (b.getPayment() != null && b.getPayment().getStatus() == PaymentStatus.SUCCESSFUL) {
            refundPayment(b.getPayment());
        }
        b.setStatus(RideBooking.Status.CANCELLED);
        bookings.save(b);
        seatConcurrency.releaseSeat(r.getId());
        auditLogService.record(u, "MOMORIDE_BOOKING_CANCELLED", "RideBooking", b.getId(), null);
        smsProvider.send(u.getPhoneNumber(), "PULA Ride: your booking on " + r.getOrigin() + " -> " + r.getDestination() + " was cancelled.");
    }

    @Transactional
    public RideResponse completeRide(User driverUser, String rideId) {
        Ride r = requireOwnRide(driverUser, rideId);
        if (r.getStatus() == Ride.Status.COMPLETED || r.getStatus() == Ride.Status.CANCELLED)
            throw ApiException.conflict("This ride cannot be completed");
        r.setStatus(Ride.Status.COMPLETED);
        r = rides.save(r);
        auditLogService.record(driverUser, "MOMORIDE_RIDE_COMPLETED", "Ride", r.getId(), null);
        return ride(r);
    }

    @Transactional
    public RideResponse cancelRide(User driverUser, String rideId) {
        Ride r = requireOwnRide(driverUser, rideId);
        if (r.getStatus() == Ride.Status.COMPLETED || r.getStatus() == Ride.Status.CANCELLED)
            throw ApiException.conflict("This ride is already finished");
        r.setStatus(Ride.Status.CANCELLED);
        r = rides.save(r);
        for (RideBooking b : bookings.findByRideId(r.getId())) {
            if (b.getStatus() != RideBooking.Status.CONFIRMED) continue;
            if (b.getPayment() != null && b.getPayment().getStatus() == PaymentStatus.SUCCESSFUL) {
                refundPayment(b.getPayment());
            }
            b.setStatus(RideBooking.Status.CANCELLED);
            bookings.save(b);
            notificationService.notify(b.getPassenger(), NotificationType.SYSTEM,
                    "Your ride " + r.getOrigin() + " -> " + r.getDestination() + " was cancelled by the driver", r.getId());
            smsProvider.send(b.getPassenger().getPhoneNumber(), "PULA Ride: your ride " + r.getOrigin() + " -> "
                    + r.getDestination() + " was cancelled by the driver.");
        }
        auditLogService.record(driverUser, "MOMORIDE_RIDE_CANCELLED", "Ride", r.getId(), null);
        return ride(r);
    }

    private void refundPayment(Payment p) {
        ChargeResult refund = provider.refund(p.getProviderReference(), "refund_" + UUID.randomUUID());
        p.transitionTo(refund.status());
        payments.save(p);
    }

    private Ride requireOwnRide(User driverUser, String rideId) {
        Driver d = drivers.findByUserId(driverUser.getId()).orElseThrow(() -> ApiException.forbidden("PULA Ride driver profile required"));
        Ride r = rides.findById(rideId).orElseThrow(() -> ApiException.notFound("Ride not found"));
        if (!r.getDriver().getId().equals(d.getId())) throw ApiException.forbidden("This is not your ride");
        return r;
    }

    // ================= RATINGS =================

    @Transactional
    public RideRatingResponse rateDriver(User passenger, String rideId, CreateRideRatingRequest req) {
        Ride r = rides.findById(rideId).orElseThrow(() -> ApiException.notFound("Ride not found"));
        if (r.getStatus() != Ride.Status.COMPLETED) throw ApiException.badRequest("You can only rate a completed ride");
        if (r.getDriver().getUser().getId().equals(passenger.getId())) throw ApiException.badRequest("You cannot rate yourself");
        RideBooking booking = bookings.findByRideIdAndPassengerId(rideId, passenger.getId())
                .filter(b -> b.getStatus() == RideBooking.Status.CONFIRMED)
                .orElseThrow(() -> ApiException.forbidden("Only passengers who took this ride can rate it"));
        if (ratings.existsByRideIdAndRaterId(rideId, passenger.getId())) throw ApiException.conflict("You already rated this ride");
        RideRating rating = new RideRating();
        rating.setRide(r);
        rating.setRater(passenger);
        rating.setDriver(r.getDriver());
        rating.setRating(req.rating());
        rating.setComment(req.comment());
        rating = ratings.save(rating);
        auditLogService.record(passenger, "MOMORIDE_RATING_CREATED", "RideRating", rating.getId(), req.rating() + " stars");
        return new RideRatingResponse(rating.getId(), r.getId(), passenger.getFullName(), rating.getRating(), rating.getComment(), rating.getCreatedAt());
    }

    // ================= REPORTS =================

    @Transactional
    public ReportResponse createReport(User reporter, CreateReportRequest req) {
        MoMoRideReport.TargetType type;
        try {
            type = MoMoRideReport.TargetType.valueOf(req.targetType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid report target type");
        }
        MoMoRideReport report = new MoMoRideReport();
        report.setReporter(reporter);
        report.setTargetType(type);
        if (req.rideId() != null) report.setRide(rides.findById(req.rideId()).orElseThrow(() -> ApiException.notFound("Ride not found")));
        if (req.taxiId() != null) report.setTaxi(taxis.findById(req.taxiId()).orElseThrow(() -> ApiException.notFound("Taxi not found")));
        if (req.paymentId() != null) report.setPayment(payments.findById(req.paymentId()).orElseThrow(() -> ApiException.notFound("Payment not found")));
        if (req.reportedUserId() != null) report.setReportedUser(users.findById(req.reportedUserId()).orElseThrow(() -> ApiException.notFound("User not found")));
        report.setReason(req.reason());
        report = reports.save(report);
        auditLogService.record(reporter, "MOMORIDE_REPORT_CREATED", "MoMoRideReport", report.getId(), type.name());
        return reportResp(report);
    }

    // ================= ADMIN =================

    @Transactional(readOnly = true)
    public Page<DriverResponse> adminListDrivers(Pageable pageable) {
        return drivers.findAllByOrderByCreatedAtDesc(pageable).map(d ->
                driver(d, taxis.findByDriverIdOrderByCreatedAtDesc(d.getId()).stream().findFirst().orElse(null)));
    }

    @Transactional(readOnly = true)
    public Page<TaxiResponse> adminListTaxis(Pageable pageable) {
        return taxis.findAllByOrderByCreatedAtDesc(pageable).map(this::taxi);
    }

    @Transactional
    public void adminSetTaxiStatus(User admin, String taxiId, Taxi.TaxiStatus status) {
        Taxi t = taxis.findById(taxiId).orElseThrow(() -> ApiException.notFound("Taxi not found"));
        t.setStatus(status);
        taxis.save(t);
        auditLogService.record(admin, "MOMORIDE_ADMIN_TAXI_STATUS", "Taxi", taxiId, status.name());
    }

    @Transactional
    public void adminSetDriverStatus(User admin, String driverId, Driver.DriverStatus status) {
        Driver d = drivers.findById(driverId).orElseThrow(() -> ApiException.notFound("Driver not found"));
        d.setStatus(status);
        drivers.save(d);
        auditLogService.record(admin, "MOMORIDE_ADMIN_DRIVER_STATUS", "Driver", driverId, status.name());
    }

    @Transactional(readOnly = true)
    public Page<RideResponse> adminListRides(Pageable pageable) {
        return rides.findAllByOrderByDepartureTimeDesc(pageable).map(this::ride);
    }

    @Transactional(readOnly = true)
    public Page<RideBookingResponse> adminListBookings(Pageable pageable) {
        return bookings.findAllByOrderByCreatedAtDesc(pageable).map(this::bookingResp);
    }

    @Transactional(readOnly = true)
    public Page<TaxiPaymentRequestResponse> adminListPaymentRequests(Pageable pageable) {
        return requests.findAllByOrderByCreatedAtDesc(pageable).map(this::reqResp);
    }

    @Transactional(readOnly = true)
    public Page<AdminReportResponse> adminListReports(Pageable pageable) {
        return reports.findAllByOrderByCreatedAtDesc(pageable).map(rep -> new AdminReportResponse(
                rep.getId(), rep.getReporter().getFullName(), rep.getTargetType().name(),
                rep.getRide() == null ? null : rep.getRide().getId(),
                rep.getTaxi() == null ? null : rep.getTaxi().getId(),
                rep.getPayment() == null ? null : rep.getPayment().getId(),
                rep.getReportedUser() == null ? null : rep.getReportedUser().getFullName(),
                rep.getReason(), rep.getStatus().name(), rep.getCreatedAt()));
    }

    @Transactional
    public void adminResolveReport(User admin, String reportId, MoMoRideReport.Status status) {
        MoMoRideReport r = reports.findById(reportId).orElseThrow(() -> ApiException.notFound("Report not found"));
        r.setStatus(status);
        reports.save(r);
        auditLogService.record(admin, "MOMORIDE_ADMIN_REPORT_RESOLVED", "MoMoRideReport", reportId, status.name());
    }

    // ================= MAPPING =================

    private DriverResponse driver(Driver d, Taxi t) {
        return new DriverResponse(d.getId(), d.getUser().getId(), d.getUser().getFullName(), d.getStatus().name(),
                d.hasPin(), t == null ? null : taxi(t));
    }

    private TaxiResponse taxi(Taxi t) {
        return new TaxiResponse(t.getId(), t.getPublicTaxiId(), t.getRegistrationNumber(), t.getPaymentCode(),
                "MOMO_RIDE:" + t.getQrToken(), t.getMakeModel(), t.getStatus().name(), t.getDriver().getUser().getFullName());
    }

    private TaxiPaymentRequestResponse reqResp(TaxiPaymentRequest r) {
        return new TaxiPaymentRequestResponse(r.getId(), r.getTaxi().getId(), r.getTaxi().getPublicTaxiId(),
                r.getDriver().getUser().getFullName(), r.getAmount(), r.getCurrency(), r.getStatus().name(),
                r.getPayment() == null ? null : r.getPayment().getId(), r.getExpiresAt());
    }

    private RideResponse ride(Ride r) {
        return new RideResponse(r.getId(), r.getDriver().getId(), r.getDriver().getUser().getFullName(), r.getOrigin(),
                r.getDestination(), r.getDepartureTime(), r.getAvailableSeats(), r.getPricePerPassenger(), r.getCurrency(),
                r.getStatus().name());
    }

    private RideBookingResponse bookingResp(RideBooking b) {
        return new RideBookingResponse(b.getId(), b.getRide().getId(), b.getPassenger().getFullName(), b.getStatus().name(),
                b.getPayment() == null ? null : b.getPayment().getId(),
                b.getPayment() == null ? "NOT_REQUIRED" : b.getPayment().getStatus().name());
    }

    private ReportResponse reportResp(MoMoRideReport r) {
        return new ReportResponse(r.getId(), r.getTargetType().name(),
                r.getRide() == null ? null : r.getRide().getId(),
                r.getTaxi() == null ? null : r.getTaxi().getId(),
                r.getPayment() == null ? null : r.getPayment().getId(),
                r.getStatus().name(), r.getCreatedAt());
    }

    // ================= ID GENERATION =================

    private String uniquePublicId() {
        String s;
        do { s = "MO" + String.format("%06d", new Random().nextInt(1_000_000)); }
        while (taxis.findByPublicTaxiId(s).isPresent());
        return s;
    }

    private String uniquePaymentCode() {
        String s;
        do { s = String.format("%05d", new Random().nextInt(100_000)); }
        while (taxis.findByPaymentCode(s).isPresent());
        return s;
    }

    private String uniqueQrToken() {
        String s;
        do { s = UUID.randomUUID().toString().replace("-", ""); }
        while (taxis.existsByQrToken(s));
        return s;
    }
}
