package com.momocircle.controller;

import com.momocircle.dto.momoride.MoMoRideDtos.*;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.momoride.MoMoRideService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController @RequestMapping("/api/momoride")
public class MoMoRideController {
    private final MoMoRideService service;
    private final CurrentUserService current;
    public MoMoRideController(MoMoRideService service, CurrentUserService current) { this.service = service; this.current = current; }

    @PostMapping("/drivers") public DriverResponse register(@Valid @RequestBody RegisterDriverRequest req) { return service.registerDriver(current.require(), req); }
    @GetMapping("/drivers/me") public DriverResponse me() { return service.myDriver(current.require()); }

    @GetMapping("/taxis/by-code/{code}") public TaxiResponse lookup(@PathVariable String code) { return service.lookupTaxi(current.require(), code); }
    @GetMapping("/taxis/by-qr/{token}") public TaxiResponse lookupByQr(@PathVariable String token) { return service.lookupTaxiByQr(current.require(), token); }

    @PostMapping("/payments/taxi") public TaxiPaymentResponse payTaxi(@Valid @RequestBody PayTaxiRequest req) { return service.payTaxi(current.require(), req); }

    @PostMapping("/payment-requests") public TaxiPaymentRequestResponse request(@Valid @RequestBody CreateTaxiPaymentRequest req) { return service.createPaymentRequest(current.require(), req); }
    @GetMapping("/payment-requests/me") public List<TaxiPaymentRequestResponse> requests() { return service.myRequests(current.require()); }
    @PostMapping("/payment-requests/{id}/pay") public TaxiPaymentResponse fulfill(@PathVariable String id, @RequestBody(required = false) FulfillPaymentRequestRequest req) { return service.fulfillPaymentRequest(current.require(), id, req == null ? new FulfillPaymentRequestRequest(null) : req); }
    @PostMapping("/payment-requests/{id}/cancel") public void cancelRequest(@PathVariable String id) { service.cancelPaymentRequest(current.require(), id); }

    @PostMapping("/rides") public RideResponse createRide(@Valid @RequestBody CreateRideRequest req) { return service.createRide(current.require(), req); }
    @GetMapping("/rides") public List<RideResponse> search(@RequestParam(required = false) String origin, @RequestParam(required = false) String destination, @RequestParam(required = false) LocalDate date) { return service.search(origin, destination, date); }
    @GetMapping("/rides/me") public List<RideResponse> myRides() { return service.myRides(current.require()); }
    @PostMapping("/rides/{rideId}/complete") public RideResponse complete(@PathVariable String rideId) { return service.completeRide(current.require(), rideId); }
    @PostMapping("/rides/{rideId}/cancel") public RideResponse cancelRide(@PathVariable String rideId) { return service.cancelRide(current.require(), rideId); }
    @PostMapping("/rides/{rideId}/rate") public RideRatingResponse rate(@PathVariable String rideId, @Valid @RequestBody CreateRideRatingRequest req) { return service.rateDriver(current.require(), rideId, req); }

    @PostMapping("/rides/{rideId}/book") public RideBookingResponse book(@PathVariable String rideId, @RequestBody(required = false) BookRideRequest req) { return service.book(current.require(), rideId, req == null ? new BookRideRequest(null) : req); }
    @GetMapping("/bookings/me") public List<RideBookingResponse> bookings() { return service.myBookings(current.require()); }
    @PostMapping("/bookings/{id}/cancel") public void cancelBooking(@PathVariable String id) { service.cancelBooking(current.require(), id); }

    @PostMapping("/reports") public ReportResponse report(@Valid @RequestBody CreateReportRequest req) { return service.createReport(current.require(), req); }
}
