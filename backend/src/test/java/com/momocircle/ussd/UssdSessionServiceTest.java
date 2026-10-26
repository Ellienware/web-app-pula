package com.momocircle.ussd;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.User;
import com.momocircle.domain.momoride.Driver;
import com.momocircle.dto.momoride.MoMoRideDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.momoride.DriverRepository;
import com.momocircle.security.UssdPinRateLimiter;
import com.momocircle.service.momoride.MoMoRideService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers MOMORIDE_PHASEF.md section 42's USSD cases: driver login (PIN),
 * payment request creation, passenger taxi payment, invalid input, session
 * timeout, and cancellation. "Driver registration" is intentionally NOT
 * covered here — registration happens through the authenticated web app
 * (reusing the existing User/JWT auth model per the "no second auth system"
 * rule), not over USSD, so there is no USSD registration flow to test.
 *
 * NOT VERIFIED IN THIS SANDBOX: no network access to Maven Central meant
 * ./mvnw test could not be run here — see MOMORIDE_PHASEF.md.
 */
class UssdSessionServiceTest {

    private DriverRepository driverRepository;
    private UserRepository userRepository;
    private MoMoRideService moMoRideService;
    private UssdPinRateLimiter pinRateLimiter;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private UssdSessionService service;

    private User driverUser;
    private Driver driver;

    @BeforeEach
    void setUp() {
        driverRepository = mock(DriverRepository.class);
        userRepository = mock(UserRepository.class);
        moMoRideService = mock(MoMoRideService.class);
        pinRateLimiter = mock(UssdPinRateLimiter.class);

        CurrencyProperties currencyProperties = new CurrencyProperties();
        currencyProperties.setDefaultCurrency("ZAR");

        service = new UssdSessionService(driverRepository, userRepository, moMoRideService,
                passwordEncoder, pinRateLimiter, currencyProperties, 180);

        driverUser = new User();
        driverUser.setId("driver-user-1");
        driverUser.setPhoneNumber("0821111111");

        driver = new Driver();
        driver.setId("driver-1");
        driver.setUser(driverUser);
        driver.setStatus(Driver.DriverStatus.ACTIVE);
        driver.setPinHash(passwordEncoder.encode("1234"));

        when(userRepository.findByPhoneNumber("0821111111")).thenReturn(Optional.of(driverUser));
        when(driverRepository.findByUserId("driver-user-1")).thenReturn(Optional.of(driver));
        when(driverRepository.findById("driver-1")).thenReturn(Optional.of(driver));
    }

    private String sid() {
        return UUID.randomUUID().toString();
    }

    // ================= DRIVER LOGIN (PIN) =================

    @Test
    void driverEntersCorrectPinAndSeesDriverMenu() {
        String sessionId = sid();
        UssdResponse first = service.handle(new UssdRequest(sessionId, "0821111111", ""));
        assertTrue(first.continueSession());
        assertTrue(first.message().contains("PIN"));

        UssdResponse second = service.handle(new UssdRequest(sessionId, "0821111111", "1234"));
        assertTrue(second.continueSession());
        assertTrue(second.message().contains("Request Payment"));
        verify(pinRateLimiter).recordSuccess("driver-1");
    }

    @Test
    void driverEntersWrongPinIsRejectedAndFailureRecorded() {
        String sessionId = sid();
        service.handle(new UssdRequest(sessionId, "0821111111", ""));
        UssdResponse response = service.handle(new UssdRequest(sessionId, "0821111111", "9999"));

        assertFalse(response.continueSession());
        assertTrue(response.message().contains("Incorrect PIN"));
        verify(pinRateLimiter).recordFailure("driver-1");
    }

    @Test
    void lockedOutDriverIsRejectedBeforePinPrompt() {
        doThrow(ApiException.tooManyRequests("locked")).when(pinRateLimiter).checkAllowed("driver-1");
        UssdResponse response = service.handle(new UssdRequest(sid(), "0821111111", ""));
        assertFalse(response.continueSession());
        assertTrue(response.message().toLowerCase().contains("wrong"));
    }

    // ================= DRIVER: REQUEST PAYMENT =================

    @Test
    void driverRequestsPaymentEndToEnd() {
        String sessionId = sid();
        service.handle(new UssdRequest(sessionId, "0821111111", ""));       // root -> PIN prompt
        service.handle(new UssdRequest(sessionId, "0821111111", "1234"));   // PIN -> menu
        service.handle(new UssdRequest(sessionId, "0821111111", "1"));      // "Request Payment" -> amount prompt
        service.handle(new UssdRequest(sessionId, "0821111111", "30"));     // amount -> passenger prompt
        UssdResponse review = service.handle(new UssdRequest(sessionId, "0821111111", "0822222222")); // passenger -> review
        assertTrue(review.message().contains("Amount: 30"));
        assertTrue(review.message().contains("0822222222"));

        UssdResponse result = service.handle(new UssdRequest(sessionId, "0821111111", "1")); // confirm send
        assertFalse(result.continueSession());
        assertEquals("Payment request created.", result.message());
        verify(moMoRideService).createPaymentRequestFromUssd(driverUser, new BigDecimal("30"), "ZAR", "0822222222");
    }

    @Test
    void driverCancelsPaymentRequestReview() {
        String sessionId = sid();
        service.handle(new UssdRequest(sessionId, "0821111111", ""));
        service.handle(new UssdRequest(sessionId, "0821111111", "1234"));
        service.handle(new UssdRequest(sessionId, "0821111111", "1"));
        service.handle(new UssdRequest(sessionId, "0821111111", "30"));
        service.handle(new UssdRequest(sessionId, "0821111111", "0822222222"));

        UssdResponse result = service.handle(new UssdRequest(sessionId, "0821111111", "2")); // cancel
        assertFalse(result.continueSession());
        assertEquals("Cancelled.", result.message());
        verify(moMoRideService, never()).createPaymentRequestFromUssd(any(), any(), any(), any());
    }

    // ================= PASSENGER: PAY TAXI =================

    @Test
    void passengerPaysTaxiEndToEnd() {
        User passenger = new User();
        passenger.setId("passenger-1");
        passenger.setPhoneNumber("0833333333");
        when(userRepository.findByPhoneNumber("0833333333")).thenReturn(Optional.of(passenger));

        com.momocircle.domain.momoride.Taxi taxi = new com.momocircle.domain.momoride.Taxi();
        taxi.setId("taxi-1");
        taxi.setDriver(driver);
        taxi.setPublicTaxiId("MO000001");
        taxi.setPaymentCode("48291");
        when(moMoRideService.lookupTaxiEntityForUssd("48291")).thenReturn(taxi);
        when(moMoRideService.payTaxiFromUssd(eq(passenger), eq("48291"), eq(new BigDecimal("30")), eq("ZAR"), any()))
                .thenReturn(new TaxiPaymentResponse("payment-1", "taxi-1", "MO000001", "Sipho", new BigDecimal("30"), "ZAR", "SUCCESSFUL", "sbx_ref"));

        String sessionId = sid();
        service.handle(new UssdRequest(sessionId, "0833333333", ""));       // root -> passenger menu (no driver for this phone)
        service.handle(new UssdRequest(sessionId, "0833333333", "1"));      // Pay Taxi -> code prompt
        service.handle(new UssdRequest(sessionId, "0833333333", "48291"));  // code -> amount prompt
        service.handle(new UssdRequest(sessionId, "0833333333", "30"));     // amount -> review

        UssdResponse result = service.handle(new UssdRequest(sessionId, "0833333333", "1")); // confirm
        assertFalse(result.continueSession());
        assertTrue(result.message().contains("Payment successful"));
        assertTrue(result.message().contains("payment-1"));
    }

    @Test
    void passengerEntersInvalidTaxiCode() {
        when(moMoRideService.lookupTaxiEntityForUssd("00000")).thenThrow(ApiException.notFound("not found"));

        String sessionId = sid();
        service.handle(new UssdRequest(sessionId, "0833333333", ""));
        service.handle(new UssdRequest(sessionId, "0833333333", "1"));
        UssdResponse result = service.handle(new UssdRequest(sessionId, "0833333333", "00000"));

        assertFalse(result.continueSession());
        assertTrue(result.message().toLowerCase().contains("invalid"));
    }

    @Test
    void passengerWithoutAnAccountIsToldToRegister() {
        when(userRepository.findByPhoneNumber("0844444444")).thenReturn(Optional.empty());
        com.momocircle.domain.momoride.Taxi taxi = new com.momocircle.domain.momoride.Taxi();
        taxi.setDriver(driver); taxi.setPublicTaxiId("MO000001"); taxi.setPaymentCode("48291");
        when(moMoRideService.lookupTaxiEntityForUssd("48291")).thenReturn(taxi);

        String sessionId = sid();
        service.handle(new UssdRequest(sessionId, "0844444444", ""));
        service.handle(new UssdRequest(sessionId, "0844444444", "1"));
        service.handle(new UssdRequest(sessionId, "0844444444", "48291"));
        service.handle(new UssdRequest(sessionId, "0844444444", "30"));
        UssdResponse result = service.handle(new UssdRequest(sessionId, "0844444444", "1"));

        assertFalse(result.continueSession());
        assertTrue(result.message().toLowerCase().contains("register"));
        verify(moMoRideService, never()).payTaxiFromUssd(any(), any(), any(), any(), any());
    }

    // ================= INVALID INPUT / TIMEOUT / CANCELLATION =================

    @Test
    void invalidMenuOptionIsRejected() {
        String sessionId = sid();
        service.handle(new UssdRequest(sessionId, "0833333333", ""));
        UssdResponse result = service.handle(new UssdRequest(sessionId, "0833333333", "9"));
        assertFalse(result.continueSession());
        assertTrue(result.message().toLowerCase().contains("invalid"));
    }

    @Test
    void expiredSessionIsRejected() throws InterruptedException {
        UssdSessionService tinyTtl = new UssdSessionService(driverRepository, userRepository, moMoRideService,
                passwordEncoder, pinRateLimiter, new CurrencyProperties(), 1);

        String sessionId = sid();
        tinyTtl.handle(new UssdRequest(sessionId, "0833333333", ""));
        Thread.sleep(1100);
        UssdResponse result = tinyTtl.handle(new UssdRequest(sessionId, "0833333333", "1"));

        assertFalse(result.continueSession());
        assertTrue(result.message().toLowerCase().contains("expired"));
    }

    @Test
    void unknownSessionAfterCompletionStartsFresh() {
        String sessionId = sid();
        UssdResponse first = service.handle(new UssdRequest(sessionId, "0833333333", ""));
        assertTrue(first.continueSession());
        UssdResponse cancelled = service.handle(new UssdRequest(sessionId, "0833333333", "4")); // Help -> ends session
        assertFalse(cancelled.continueSession());

        // Same sessionId reused after the session ended (gateway would issue a new one in
        // practice, but this proves a finished session doesn't linger in a stale state).
        UssdResponse restarted = service.handle(new UssdRequest(sessionId, "0833333333", ""));
        assertTrue(restarted.continueSession());
        assertTrue(restarted.message().contains("PULA Ride"));
    }
}
