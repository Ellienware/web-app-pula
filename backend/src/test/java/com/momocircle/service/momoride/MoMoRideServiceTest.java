package com.momocircle.service.momoride;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.Payment;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.momoride.*;
import com.momocircle.dto.momoride.MoMoRideDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.ChargeRequest;
import com.momocircle.payment.ChargeResult;
import com.momocircle.payment.PaymentIdempotencyService;
import com.momocircle.payment.PaymentProvider;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.momoride.*;
import com.momocircle.security.PaymentCodeRateLimiter;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.FinancialValidationService;
import com.momocircle.service.NotificationService;
import com.momocircle.sms.SmsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MoMoRideService's core financial and authorization rules,
 * covering the DRIVER / TAXI PAYMENT / PAYMENT REQUEST / RATINGS / SECURITY
 * cases MOMORIDE_PHASEF.md section 42 calls mandatory. Dependencies are
 * Mockito mocks except for the real, cheap-to-construct collaborators
 * (FinancialValidationService, CurrencyProperties, a real BCryptPasswordEncoder)
 * so amount/currency/PIN-hash validation is exercised for real rather than
 * stubbed away.
 *
 * NOT VERIFIED IN THIS SANDBOX: no network access to Maven Central meant
 * ./mvnw test could not be run here — see MOMORIDE_PHASEF.md. Ride-booking
 * concurrency is covered separately in RideSeatConcurrencyServiceTest.
 */
class MoMoRideServiceTest {

    private DriverRepository drivers;
    private TaxiRepository taxis;
    private TaxiPaymentRequestRepository requests;
    private RideRepository rides;
    private RideBookingRepository bookings;
    private RideRatingRepository ratings;
    private MoMoRideReportRepository reports;
    private PaymentRepository payments;
    private UserRepository users;
    private PaymentProvider provider;
    private PaymentIdempotencyService idem;
    private NotificationService notificationService;
    private SmsProvider smsProvider;
    private AuditLogService auditLogService;
    private RideSeatConcurrencyService seatConcurrency;
    private MoMoRideService service;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private User driverUser;
    private User passengerUser;

    @BeforeEach
    void setUp() {
        drivers = mock(DriverRepository.class);
        taxis = mock(TaxiRepository.class);
        requests = mock(TaxiPaymentRequestRepository.class);
        rides = mock(RideRepository.class);
        bookings = mock(RideBookingRepository.class);
        ratings = mock(RideRatingRepository.class);
        reports = mock(MoMoRideReportRepository.class);
        payments = mock(PaymentRepository.class);
        users = mock(UserRepository.class);
        provider = mock(PaymentProvider.class);
        notificationService = mock(NotificationService.class);
        smsProvider = mock(SmsProvider.class);
        auditLogService = mock(AuditLogService.class);
        seatConcurrency = mock(RideSeatConcurrencyService.class);

        payments = mock(PaymentRepository.class);
        when(payments.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(payments.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        idem = new PaymentIdempotencyService(payments);

        when(provider.name()).thenReturn("sandbox");

        CurrencyProperties currencyProperties = new CurrencyProperties();
        currencyProperties.setDefaultCurrency("ZAR");
        currencyProperties.setSupported(java.util.List.of("ZAR"));
        FinancialValidationService financial = new FinancialValidationService(currencyProperties, new BigDecimal("10000000"));

        service = new MoMoRideService(drivers, taxis, requests, rides, bookings, ratings, reports, payments, users,
                provider, idem, financial, currencyProperties, passwordEncoder, auditLogService, notificationService,
                smsProvider, new PaymentCodeRateLimiter(1000, 15), seatConcurrency);

        driverUser = new User(); driverUser.setId("driver-user-1"); driverUser.setFullName("Sipho Driver"); driverUser.setPhoneNumber("0821111111"); driverUser.setActive(true);
        passengerUser = new User(); passengerUser.setId("passenger-1"); passengerUser.setFullName("Nomsa Passenger"); passengerUser.setPhoneNumber("0822222222"); passengerUser.setActive(true);
    }

    private Driver activeDriver() {
        Driver d = new Driver(); d.setId("driver-1"); d.setUser(driverUser); d.setStatus(Driver.DriverStatus.ACTIVE);
        return d;
    }

    private Taxi activeTaxi(Driver d) {
        Taxi t = new Taxi(); t.setId("taxi-1"); t.setDriver(d); t.setPublicTaxiId("MO000001");
        t.setPaymentCode("48291"); t.setStatus(Taxi.TaxiStatus.ACTIVE); t.setRegistrationNumber("ND12345");
        return t;
    }

    // ================= DRIVER REGISTRATION =================

    @Test
    void registerDriver_createsAssociatedDriverAndTaxiWithHashedPin() {
        when(drivers.findByUserId(driverUser.getId())).thenReturn(Optional.empty());
        when(taxis.existsByRegistrationNumber("ND12345")).thenReturn(false);
        when(drivers.save(any(Driver.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taxis.save(any(Taxi.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taxis.findByPublicTaxiId(anyString())).thenReturn(Optional.empty());
        when(taxis.findByPaymentCode(anyString())).thenReturn(Optional.empty());
        when(taxis.existsByQrToken(anyString())).thenReturn(false);

        DriverResponse response = service.registerDriver(driverUser, new RegisterDriverRequest("ND12345", "Toyota Quantum", "1234"));

        assertNotNull(response.taxi());
        assertEquals("ND12345", response.taxi().registrationNumber());
        assertTrue(response.hasPin());
        verify(auditLogService).record(eq(driverUser), eq("MOMORIDE_DRIVER_REGISTERED"), anyString(), anyString(), anyString());
        verify(auditLogService).record(eq(driverUser), eq("MOMORIDE_TAXI_CREATED"), anyString(), anyString(), anyString());
    }

    @Test
    void registerDriver_rejectsSecondRegistrationForSameUser() {
        when(drivers.findByUserId(driverUser.getId())).thenReturn(Optional.of(activeDriver()));
        assertThrows(ApiException.class,
                () -> service.registerDriver(driverUser, new RegisterDriverRequest("ND99999", null, "1234")));
    }

    @Test
    void registerDriver_rejectsDuplicateTaxiRegistrationNumber() {
        when(drivers.findByUserId(driverUser.getId())).thenReturn(Optional.empty());
        when(taxis.existsByRegistrationNumber("ND12345")).thenReturn(true);
        assertThrows(ApiException.class,
                () -> service.registerDriver(driverUser, new RegisterDriverRequest("ND12345", null, "1234")));
    }

    // ================= TAXI PAYMENT =================

    @Test
    void payTaxi_successfulChargeReturnsSuccessfulStatus() {
        Driver d = activeDriver();
        Taxi t = activeTaxi(d);
        when(taxis.findByPaymentCode("48291")).thenReturn(Optional.of(t));
        when(provider.charge(any(ChargeRequest.class))).thenReturn(ChargeResult.of(PaymentStatus.SUCCESSFUL, "sbx_ref"));

        TaxiPaymentResponse response = service.payTaxi(passengerUser,
                new PayTaxiRequest("48291", new BigDecimal("30.00"), "ZAR", "key-1"));

        assertEquals("SUCCESSFUL", response.status());
        assertEquals(t.getPublicTaxiId(), response.publicTaxiId());
        verify(notificationService, atLeastOnce()).notify(any(), any(), anyString(), any());
        verify(smsProvider, atLeastOnce()).send(anyString(), anyString());
    }

    @Test
    void payTaxi_invalidCodeThrowsNotFound() {
        when(taxis.findByPaymentCode("00000")).thenReturn(Optional.empty());
        ApiException e = assertThrows(ApiException.class,
                () -> service.payTaxi(passengerUser, new PayTaxiRequest("00000", new BigDecimal("30"), "ZAR", "key-2")));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void payTaxi_inactiveTaxiRejected() {
        Driver d = activeDriver();
        Taxi t = activeTaxi(d);
        t.setStatus(Taxi.TaxiStatus.SUSPENDED);
        when(taxis.findByPaymentCode("48291")).thenReturn(Optional.of(t));

        assertThrows(ApiException.class,
                () -> service.payTaxi(passengerUser, new PayTaxiRequest("48291", new BigDecimal("30"), "ZAR", "key-3")));
        verify(provider, never()).charge(any());
    }

    @Test
    void payTaxi_invalidAmountRejected() {
        assertThrows(ApiException.class,
                () -> service.payTaxi(passengerUser, new PayTaxiRequest("48291", new BigDecimal("-5"), "ZAR", "key-4")));
        verifyNoInteractions(provider);
    }

    @Test
    void payTaxi_unsupportedCurrencyRejected() {
        assertThrows(ApiException.class,
                () -> service.payTaxi(passengerUser, new PayTaxiRequest("48291", new BigDecimal("30"), "USD", "key-5")));
        verifyNoInteractions(provider);
    }

    @Test
    void payTaxi_duplicateIdempotencyKeyDoesNotChargeTwice() {
        Driver d = activeDriver();
        Taxi t = activeTaxi(d);
        when(taxis.findByPaymentCode("48291")).thenReturn(Optional.of(t));

        Payment existing = new Payment();
        existing.setAmount(new BigDecimal("30.00"));
        existing.setCurrency("ZAR");
        existing.setProvider("sandbox");
        existing.setIdempotencyKey("dup-key");
        existing.transitionTo(PaymentStatus.PROCESSING);
        existing.transitionTo(PaymentStatus.SUCCESSFUL);
        when(payments.findByIdempotencyKey("dup-key")).thenReturn(Optional.of(existing));

        TaxiPaymentResponse response = service.payTaxi(passengerUser,
                new PayTaxiRequest("48291", new BigDecimal("30.00"), "ZAR", "dup-key"));

        assertEquals("SUCCESSFUL", response.status());
        verify(provider, never()).charge(any()); // the already-resolved payment is returned, not re-charged
    }

    @Test
    void payTaxi_providerFailureReturnsFailedStatus() {
        Driver d = activeDriver();
        Taxi t = activeTaxi(d);
        when(taxis.findByPaymentCode("48291")).thenReturn(Optional.of(t));
        when(provider.charge(any())).thenReturn(ChargeResult.failed("Insufficient funds"));

        TaxiPaymentResponse response = service.payTaxi(passengerUser,
                new PayTaxiRequest("48291", new BigDecimal("30"), "ZAR", "key-6"));
        assertEquals("FAILED", response.status());
    }

    @Test
    void payTaxi_providerProcessingReturnsProcessingStatus() {
        Driver d = activeDriver();
        Taxi t = activeTaxi(d);
        when(taxis.findByPaymentCode("48291")).thenReturn(Optional.of(t));
        when(provider.charge(any())).thenReturn(ChargeResult.of(PaymentStatus.PROCESSING, "sbx_ref"));

        TaxiPaymentResponse response = service.payTaxi(passengerUser,
                new PayTaxiRequest("48291", new BigDecimal("30"), "ZAR", "key-7"));
        assertEquals("PROCESSING", response.status());
    }

    // ================= PAYMENT REQUESTS =================

    @Test
    void createPaymentRequest_byActiveDriverSucceeds() {
        Driver d = activeDriver();
        Taxi t = activeTaxi(d);
        when(drivers.findByUserId(driverUser.getId())).thenReturn(Optional.of(d));
        when(taxis.findByDriverIdOrderByCreatedAtDesc(d.getId())).thenReturn(java.util.List.of(t));
        when(requests.save(any(TaxiPaymentRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        TaxiPaymentRequestResponse response = service.createPaymentRequest(driverUser,
                new CreateTaxiPaymentRequest(new BigDecimal("30"), "ZAR", null));

        assertEquals("OPEN", response.status());
        assertNotNull(response.expiresAt());
    }

    @Test
    void fulfillPaymentRequest_passengerPaysOpenRequestSuccessfully() {
        Driver d = activeDriver();
        Taxi t = activeTaxi(d);
        TaxiPaymentRequest r = new TaxiPaymentRequest();
        r.setId("req-1"); r.setDriver(d); r.setTaxi(t);
        r.setAmount(new BigDecimal("30")); r.setCurrency("ZAR");
        r.setStatus(TaxiPaymentRequest.Status.OPEN);
        r.setExpiresAt(Instant.now().plusSeconds(600));
        when(requests.findById("req-1")).thenReturn(Optional.of(r));
        when(requests.save(any(TaxiPaymentRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(provider.charge(any())).thenReturn(ChargeResult.of(PaymentStatus.SUCCESSFUL, "sbx_ref"));

        TaxiPaymentResponse response = service.fulfillPaymentRequest(passengerUser, "req-1", new FulfillPaymentRequestRequest("key-8"));

        assertEquals("SUCCESSFUL", response.status());
        assertEquals(TaxiPaymentRequest.Status.FULFILLED, r.getStatus());
    }

    @Test
    void fulfillPaymentRequest_wrongTargetedPassengerRejected() {
        Driver d = activeDriver();
        Taxi t = activeTaxi(d);
        User otherPassenger = new User(); otherPassenger.setId("other-passenger");

        TaxiPaymentRequest r = new TaxiPaymentRequest();
        r.setId("req-2"); r.setDriver(d); r.setTaxi(t);
        r.setAmount(new BigDecimal("30")); r.setCurrency("ZAR");
        r.setStatus(TaxiPaymentRequest.Status.OPEN);
        r.setExpiresAt(Instant.now().plusSeconds(600));
        r.setPassenger(otherPassenger);
        when(requests.findById("req-2")).thenReturn(Optional.of(r));

        assertThrows(ApiException.class,
                () -> service.fulfillPaymentRequest(passengerUser, "req-2", new FulfillPaymentRequestRequest("key-9")));
        verifyNoInteractions(provider);
    }

    @Test
    void fulfillPaymentRequest_expiredRequestRejectedAndMarkedExpired() {
        Driver d = activeDriver();
        Taxi t = activeTaxi(d);
        TaxiPaymentRequest r = new TaxiPaymentRequest();
        r.setId("req-3"); r.setDriver(d); r.setTaxi(t);
        r.setAmount(new BigDecimal("30")); r.setCurrency("ZAR");
        r.setStatus(TaxiPaymentRequest.Status.OPEN);
        r.setExpiresAt(Instant.now().minusSeconds(60));
        when(requests.findById("req-3")).thenReturn(Optional.of(r));
        when(requests.save(any(TaxiPaymentRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(ApiException.class,
                () -> service.fulfillPaymentRequest(passengerUser, "req-3", new FulfillPaymentRequestRequest("key-10")));
        assertEquals(TaxiPaymentRequest.Status.EXPIRED, r.getStatus());
    }

    @Test
    void fulfillPaymentRequest_alreadyClosedRequestRejected() {
        Driver d = activeDriver();
        Taxi t = activeTaxi(d);
        TaxiPaymentRequest r = new TaxiPaymentRequest();
        r.setId("req-4"); r.setDriver(d); r.setTaxi(t);
        r.setAmount(new BigDecimal("30")); r.setCurrency("ZAR");
        r.setStatus(TaxiPaymentRequest.Status.FULFILLED);
        r.setExpiresAt(Instant.now().plusSeconds(600));
        when(requests.findById("req-4")).thenReturn(Optional.of(r));

        assertThrows(ApiException.class,
                () -> service.fulfillPaymentRequest(passengerUser, "req-4", new FulfillPaymentRequestRequest("key-11")));
    }

    @Test
    void cancelPaymentRequest_onlyOwningDriverCanCancel() {
        Driver d = activeDriver();
        Taxi t = activeTaxi(d);
        TaxiPaymentRequest r = new TaxiPaymentRequest();
        r.setId("req-5"); r.setDriver(d); r.setTaxi(t);
        r.setStatus(TaxiPaymentRequest.Status.OPEN);
        when(requests.findById("req-5")).thenReturn(Optional.of(r));

        User someoneElse = new User(); someoneElse.setId("someone-else");
        assertThrows(ApiException.class, () -> service.cancelPaymentRequest(someoneElse, "req-5"));
    }

    // ================= RATINGS =================

    private Ride completedRide(Driver d) {
        Ride ride = new Ride(); ride.setId("ride-1"); ride.setDriver(d);
        ride.setOrigin("Durban"); ride.setDestination("Umhlanga");
        ride.setDepartureTime(LocalDateTime.now().minusHours(1));
        ride.setAvailableSeats(0); ride.setPricePerPassenger(new BigDecimal("25")); ride.setCurrency("ZAR");
        ride.setStatus(Ride.Status.COMPLETED);
        return ride;
    }

    @Test
    void rateDriver_validRatingByParticipantSucceeds() {
        Driver d = activeDriver();
        Ride ride = completedRide(d);
        RideBooking booking = new RideBooking(); booking.setRide(ride); booking.setPassenger(passengerUser); booking.setStatus(RideBooking.Status.CONFIRMED);

        when(rides.findById("ride-1")).thenReturn(Optional.of(ride));
        when(bookings.findByRideIdAndPassengerId("ride-1", passengerUser.getId())).thenReturn(Optional.of(booking));
        when(ratings.existsByRideIdAndRaterId("ride-1", passengerUser.getId())).thenReturn(false);
        when(ratings.save(any(RideRating.class))).thenAnswer(inv -> inv.getArgument(0));

        RideRatingResponse response = service.rateDriver(passengerUser, "ride-1", new CreateRideRatingRequest(5, "Great ride"));
        assertEquals(5, response.rating());
    }

    @Test
    void rateDriver_duplicateRatingRejected() {
        Driver d = activeDriver();
        Ride ride = completedRide(d);
        RideBooking booking = new RideBooking(); booking.setRide(ride); booking.setPassenger(passengerUser); booking.setStatus(RideBooking.Status.CONFIRMED);

        when(rides.findById("ride-1")).thenReturn(Optional.of(ride));
        when(bookings.findByRideIdAndPassengerId("ride-1", passengerUser.getId())).thenReturn(Optional.of(booking));
        when(ratings.existsByRideIdAndRaterId("ride-1", passengerUser.getId())).thenReturn(true);

        assertThrows(ApiException.class,
                () -> service.rateDriver(passengerUser, "ride-1", new CreateRideRatingRequest(4, null)));
    }

    @Test
    void rateDriver_beforeCompletionRejected() {
        Driver d = activeDriver();
        Ride ride = completedRide(d);
        ride.setStatus(Ride.Status.OPEN);
        when(rides.findById("ride-1")).thenReturn(Optional.of(ride));

        assertThrows(ApiException.class,
                () -> service.rateDriver(passengerUser, "ride-1", new CreateRideRatingRequest(5, null)));
    }

    @Test
    void rateDriver_nonParticipantRejected() {
        Driver d = activeDriver();
        Ride ride = completedRide(d);
        when(rides.findById("ride-1")).thenReturn(Optional.of(ride));
        when(bookings.findByRideIdAndPassengerId("ride-1", passengerUser.getId())).thenReturn(Optional.empty());

        assertThrows(ApiException.class,
                () -> service.rateDriver(passengerUser, "ride-1", new CreateRideRatingRequest(5, null)));
    }

    @Test
    void rateDriver_driverCannotRateOwnRide() {
        Driver d = activeDriver();
        Ride ride = completedRide(d);
        when(rides.findById("ride-1")).thenReturn(Optional.of(ride));

        assertThrows(ApiException.class,
                () -> service.rateDriver(driverUser, "ride-1", new CreateRideRatingRequest(5, null)));
    }

    // ================= SECURITY / IDOR =================

    @Test
    void cancelBooking_passengerCannotCancelSomeoneElsesBooking() {
        Driver d = activeDriver();
        Ride ride = completedRide(d);
        ride.setStatus(Ride.Status.OPEN);
        RideBooking booking = new RideBooking();
        booking.setId("booking-1"); booking.setRide(ride); booking.setPassenger(passengerUser); booking.setStatus(RideBooking.Status.CONFIRMED);
        when(bookings.findById("booking-1")).thenReturn(Optional.of(booking));

        User attacker = new User(); attacker.setId("attacker");
        assertThrows(ApiException.class, () -> service.cancelBooking(attacker, "booking-1"));
    }

    @Test
    void completeRide_driverCannotCompleteAnotherDriversRide() {
        Driver owner = activeDriver();
        Ride ride = completedRide(owner);
        ride.setStatus(Ride.Status.OPEN);

        User otherDriverUser = new User(); otherDriverUser.setId("other-driver-user");
        Driver otherDriver = new Driver(); otherDriver.setId("driver-2"); otherDriver.setUser(otherDriverUser); otherDriver.setStatus(Driver.DriverStatus.ACTIVE);

        when(drivers.findByUserId(otherDriverUser.getId())).thenReturn(Optional.of(otherDriver));
        when(rides.findById("ride-1")).thenReturn(Optional.of(ride));

        assertThrows(ApiException.class, () -> service.completeRide(otherDriverUser, "ride-1"));
    }

    @Test
    void adminEndpoints_requireNoDirectServiceBypass_statusChangesAreAudited() {
        Driver d = activeDriver();
        when(drivers.findById("driver-1")).thenReturn(Optional.of(d));
        when(drivers.save(any(Driver.class))).thenAnswer(inv -> inv.getArgument(0));

        User admin = new User(); admin.setId("admin-1");
        service.adminSetDriverStatus(admin, "driver-1", Driver.DriverStatus.SUSPENDED);

        assertEquals(Driver.DriverStatus.SUSPENDED, d.getStatus());
        verify(auditLogService).record(eq(admin), eq("MOMORIDE_ADMIN_DRIVER_STATUS"), anyString(), anyString(), anyString());
    }
}
