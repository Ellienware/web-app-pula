package com.momocircle.ussd;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.User;
import com.momocircle.domain.momoride.Driver;
import com.momocircle.domain.momoride.Taxi;
import com.momocircle.dto.momoride.MoMoRideDtos.TaxiPaymentResponse;
import com.momocircle.dto.momoride.MoMoRideDtos.TaxiPaymentRequestResponse;
import com.momocircle.dto.momoride.MoMoRideDtos.RideResponse;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.momoride.DriverRepository;
import com.momocircle.security.UssdPinRateLimiter;
import com.momocircle.service.momoride.MoMoRideService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates raw USSD gateway callbacks into calls against the same
 * authorized MoMoRideService methods the REST API and web frontend use — no
 * business logic lives in the controller or here beyond menu navigation.
 * This is a clean provider-boundary abstraction (see class-level notes in
 * MOMORIDE_PHASEF.md): it does NOT integrate with a live telecom aggregator.
 * A real gateway integration plugs in at {@code MoMoRideUssdController} by
 * adapting that gateway's callback shape into {@link UssdRequest}.
 *
 * <p>Simplification vs. real Africa's-Talking-style gateways: those resend
 * the FULL star-separated input history on every hop; this service instead
 * keeps its own {@link UssdSession} server-side (keyed by sessionId) and only
 * reads the LAST segment of {@code text} as "what the subscriber just typed."
 * That is equivalent in behavior for a single linear menu flow like the ones
 * here, and is the one adapter-specific detail a real integration would need
 * to reconcile with its chosen gateway's exact conventions.
 */
@Service
public class UssdSessionService {

    private static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(3);

    private final ConcurrentHashMap<String, UssdSession> sessions = new ConcurrentHashMap<>();
    private final DriverRepository driverRepository;
    private final UserRepository userRepository;
    private final MoMoRideService moMoRideService;
    private final PasswordEncoder passwordEncoder;
    private final UssdPinRateLimiter pinRateLimiter;
    private final CurrencyProperties currencyProperties;
    private final Duration sessionTtl;

    public UssdSessionService(DriverRepository driverRepository, UserRepository userRepository,
                               MoMoRideService moMoRideService, PasswordEncoder passwordEncoder,
                               UssdPinRateLimiter pinRateLimiter, CurrencyProperties currencyProperties,
                               @Value("${app.momoride.ussd-session-ttl-seconds:180}") long sessionTtlSeconds) {
        this.driverRepository = driverRepository;
        this.userRepository = userRepository;
        this.moMoRideService = moMoRideService;
        this.passwordEncoder = passwordEncoder;
        this.pinRateLimiter = pinRateLimiter;
        this.currencyProperties = currencyProperties;
        this.sessionTtl = sessionTtlSeconds > 0 ? Duration.ofSeconds(sessionTtlSeconds) : DEFAULT_SESSION_TTL;
    }

    public UssdResponse handle(UssdRequest req) {
        UssdSession session = sessions.computeIfAbsent(req.sessionId(),
                id -> new UssdSession(id, req.phoneNumber()));

        if (Duration.between(session.startedAt, Instant.now()).compareTo(sessionTtl) > 0) {
            sessions.remove(req.sessionId());
            return UssdResponse.end("Session expired. Please dial again.");
        }

        String input = lastToken(req.text());
        try {
            UssdResponse response = route(session, input);
            if (!response.continueSession()) {
                sessions.remove(req.sessionId());
            }
            return response;
        } catch (RuntimeException e) {
            sessions.remove(req.sessionId());
            return UssdResponse.end("Something went wrong: " + e.getMessage());
        }
    }

    private String lastToken(String text) {
        if (text == null || text.isBlank()) return "";
        String[] parts = text.split("\\*");
        return parts.length == 0 ? "" : parts[parts.length - 1].trim();
    }

    private UssdResponse route(UssdSession s, String input) {
        return switch (s.step) {
            case "ROOT" -> root(s);
            case "PIN_ENTRY" -> pinEntry(s, input);
            case "DRIVER_MENU" -> driverMenu(s, input);
            case "REQ_AMOUNT" -> reqAmount(s, input);
            case "REQ_PASSENGER" -> reqPassenger(s, input);
            case "REQ_REVIEW" -> reqReview(s, input);
            case "PASSENGER_MENU" -> passengerMenu(s, input);
            case "PAY_CODE" -> payCode(s, input);
            case "PAY_AMOUNT" -> payAmount(s, input);
            case "PAY_REVIEW" -> payReview(s, input);
            case "FIND_ORIGIN" -> findOrigin(s, input);
            case "FIND_DEST" -> findDest(s, input);
            default -> UssdResponse.end("Session error. Please dial again.");
        };
    }

    // --- Entry ---

    private UssdResponse root(UssdSession s) {
        var driver = driverRepository.findByUserId(
                userRepository.findByPhoneNumber(s.phoneNumber).map(User::getId).orElse(""));
        if (driver.isPresent() && driver.get().getStatus() == Driver.DriverStatus.ACTIVE) {
            s.data.put("driverId", driver.get().getId());
            if (!driver.get().hasPin()) {
                return UssdResponse.end(
                        "No PULA Ride PIN is set on your driver profile yet. Set one from the PULA app, "
                                + "then dial in again.");
            }
            pinRateLimiter.checkAllowed(driver.get().getId());
            s.step = "PIN_ENTRY";
            return UssdResponse.menu("PULA Ride Driver\nEnter your PULA Ride PIN:");
        }
        s.step = "PASSENGER_MENU";
        return passengerMenuPrompt();
    }

    // --- Driver flow ---

    private UssdResponse pinEntry(UssdSession s, String input) {
        String driverId = s.data.get("driverId");
        Driver driver = driverRepository.findById(driverId).orElseThrow();
        if (input == null || input.isBlank() || !passwordEncoder.matches(input, driver.getPinHash())) {
            pinRateLimiter.recordFailure(driverId);
            return UssdResponse.end("Incorrect PIN.");
        }
        pinRateLimiter.recordSuccess(driverId);
        s.step = "DRIVER_MENU";
        return driverMenuPrompt();
    }

    private UssdResponse driverMenuPrompt() {
        return UssdResponse.menu("PULA Ride\n1. Request Payment\n2. Payment History\n3. My Taxi\n4. Ride Sharing\n5. Help");
    }

    private UssdResponse driverMenu(UssdSession s, String input) {
        return switch (input) {
            case "1" -> { s.step = "REQ_AMOUNT"; yield UssdResponse.menu("Enter amount:"); }
            case "2" -> {
                User user = userRepository.findByPhoneNumber(s.phoneNumber).orElseThrow();
                List<TaxiPaymentRequestResponse> recent = moMoRideService.myRequests(user);
                if (recent.isEmpty()) yield UssdResponse.end("No payment requests yet.");
                StringBuilder sb = new StringBuilder("Recent payment requests:\n");
                recent.stream().limit(5).forEach(r ->
                        sb.append(r.amount()).append(" ").append(r.currency()).append(" - ").append(r.status()).append("\n"));
                yield UssdResponse.end(sb.toString().trim());
            }
            case "3" -> {
                User user = userRepository.findByPhoneNumber(s.phoneNumber).orElseThrow();
                var driverInfo = moMoRideService.myDriver(user);
                yield driverInfo.taxi() == null
                        ? UssdResponse.end("No taxi registered.")
                        : UssdResponse.end("Taxi ID: " + driverInfo.taxi().publicTaxiId()
                            + "\nPayment code: " + driverInfo.taxi().paymentCode());
            }
            case "4" -> UssdResponse.end("Ride sharing via USSD is coming soon. Use the PULA app to offer rides.");
            case "5" -> UssdResponse.end("PULA Ride lets you request taxi fare payments and manage your taxi. For full features use the PULA app.");
            default -> UssdResponse.end("Invalid option.");
        };
    }

    private UssdResponse reqAmount(UssdSession s, String input) {
        BigDecimal amount = parseAmount(input);
        if (amount == null) return UssdResponse.end("Invalid amount.");
        s.data.put("amount", amount.toPlainString());
        s.step = "REQ_PASSENGER";
        return UssdResponse.menu("Enter passenger number:");
    }

    private UssdResponse reqPassenger(UssdSession s, String input) {
        if (input == null || input.isBlank()) return UssdResponse.end("Invalid passenger number.");
        s.data.put("passenger", input.trim());
        s.step = "REQ_REVIEW";
        return UssdResponse.menu("Amount: " + s.data.get("amount") + "\nPassenger: " + input.trim()
                + "\n1. Send\n2. Cancel");
    }

    private UssdResponse reqReview(UssdSession s, String input) {
        if ("2".equals(input)) return UssdResponse.end("Cancelled.");
        if (!"1".equals(input)) return UssdResponse.end("Invalid option.");
        User driverUser = userRepository.findByPhoneNumber(s.phoneNumber).orElseThrow();
        moMoRideService.createPaymentRequestFromUssd(driverUser, new BigDecimal(s.data.get("amount")),
                currencyProperties.getDefaultCurrency(), s.data.get("passenger"));
        return UssdResponse.end("Payment request created.");
    }

    // --- Passenger flow ---

    private UssdResponse passengerMenuPrompt() {
        return UssdResponse.menu("PULA Ride\n1. Pay Taxi\n2. Find Ride\n3. My Payments\n4. Help");
    }

    private UssdResponse passengerMenu(UssdSession s, String input) {
        return switch (input) {
            case "1" -> { s.step = "PAY_CODE"; yield UssdResponse.menu("Enter Taxi Payment Code:"); }
            case "2" -> { s.step = "FIND_ORIGIN"; yield UssdResponse.menu("Enter origin:"); }
            case "3" -> {
                var user = userRepository.findByPhoneNumber(s.phoneNumber);
                if (user.isEmpty()) yield UssdResponse.end("No PULA account found for this number.");
                // Passenger-side payment history isn't tracked by a dedicated query yet in this
                // phase (only driver-side myRequests() is) — direct the subscriber to the app.
                yield UssdResponse.end("View your full PULA Ride payment history in the PULA app.");
            }
            case "4" -> UssdResponse.end("PULA Ride lets you pay a taxi by its payment code or find a shared ride. For full features use the PULA app.");
            default -> UssdResponse.end("Invalid option.");
        };
    }

    private UssdResponse payCode(UssdSession s, String input) {
        Taxi taxi;
        try {
            taxi = moMoRideService.lookupTaxiEntityForUssd(input == null ? "" : input.trim());
        } catch (RuntimeException e) {
            return UssdResponse.end("Invalid or inactive taxi code.");
        }
        s.data.put("code", taxi.getPaymentCode());
        s.data.put("taxiPublicId", taxi.getPublicTaxiId());
        s.step = "PAY_AMOUNT";
        return UssdResponse.menu("Taxi: " + taxi.getPublicTaxiId() + "\nEnter amount:");
    }

    private UssdResponse payAmount(UssdSession s, String input) {
        BigDecimal amount = parseAmount(input);
        if (amount == null) return UssdResponse.end("Invalid amount.");
        s.data.put("amount", amount.toPlainString());
        s.step = "PAY_REVIEW";
        return UssdResponse.menu("Taxi: " + s.data.get("taxiPublicId") + "\nAmount: " + amount
                + "\n1. Confirm\n2. Cancel");
    }

    private UssdResponse payReview(UssdSession s, String input) {
        if ("2".equals(input)) return UssdResponse.end("Cancelled.");
        if (!"1".equals(input)) return UssdResponse.end("Invalid option.");
        var payer = userRepository.findByPhoneNumber(s.phoneNumber);
        if (payer.isEmpty()) {
            return UssdResponse.end("Please register a PULA account with this phone number first.");
        }
        TaxiPaymentResponse result;
        try {
            result = moMoRideService.payTaxiFromUssd(payer.get(), s.data.get("code"), new BigDecimal(s.data.get("amount")),
                    currencyProperties.getDefaultCurrency(), s.sessionId);
        } catch (RuntimeException e) {
            return UssdResponse.end("Payment failed: " + e.getMessage());
        }
        return switch (result.status()) {
            case "SUCCESSFUL" -> UssdResponse.end("Payment successful.\nTransaction: " + result.paymentId());
            case "PROCESSING" -> UssdResponse.end("Payment is processing. You'll get an SMS confirmation.");
            default -> UssdResponse.end("Payment failed.");
        };
    }

    private UssdResponse findOrigin(UssdSession s, String input) {
        if (input == null || input.isBlank()) return UssdResponse.end("Invalid origin.");
        s.data.put("origin", input.trim());
        s.step = "FIND_DEST";
        return UssdResponse.menu("Enter destination:");
    }

    private UssdResponse findDest(UssdSession s, String input) {
        if (input == null || input.isBlank()) return UssdResponse.end("Invalid destination.");
        List<RideResponse> rides = moMoRideService.search(s.data.get("origin"), input.trim(), (LocalDate) null);
        if (rides.isEmpty()) return UssdResponse.end("No rides found.");
        StringBuilder sb = new StringBuilder("Rides found:\n");
        rides.stream().limit(3).forEach(r -> sb.append(r.origin()).append(" -> ").append(r.destination())
                .append(" @ ").append(r.departureTime()).append(" - ").append(r.pricePerPassenger())
                .append(" ").append(r.currency()).append("\n"));
        sb.append("Book via the PULA app.");
        return UssdResponse.end(sb.toString());
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            BigDecimal amount = new BigDecimal(raw.trim());
            return amount.signum() > 0 ? amount : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
