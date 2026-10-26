package com.momocircle.controller;

import com.momocircle.dto.ServiceDtos.*;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Provider side. Every method resolves the caller's OWN business; ids of other providers' records answer 404. */
@RestController
@RequestMapping("/api/services/provider")
public class ServiceProviderController {

    private final ServiceProviderService providers;
    private final ServiceRequestService requests;
    private final ServiceQuoteService quotes;
    private final ServiceBookingService bookings;
    private final ServiceMaterialService materials;
    private final CurrentUserService currentUser;

    public ServiceProviderController(ServiceProviderService providers, ServiceRequestService requests, ServiceQuoteService quotes,
                                     ServiceBookingService bookings, ServiceMaterialService materials, CurrentUserService currentUser) {
        this.providers = providers;
        this.requests = requests;
        this.quotes = quotes;
        this.bookings = bookings;
        this.materials = materials;
        this.currentUser = currentUser;
    }

    @PutMapping("/profile")
    public ProviderProfileView upsertProfile(@Valid @RequestBody ProviderProfileRequest req) {
        return providers.upsertProfile(currentUser.require(), req);
    }

    @GetMapping("/profile")
    public ProviderProfileView profile() {
        return providers.myProfile(currentUser.require());
    }

    @PostMapping("/verification-request")
    public ProviderProfileView requestVerification() {
        return providers.requestVerification(currentUser.require());
    }

    @GetMapping("/listings")
    public List<ListingView> listings() {
        return providers.myListings(currentUser.require());
    }

    @PostMapping("/listings")
    public ResponseEntity<ListingView> createListing(@Valid @RequestBody ListingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(providers.createListing(currentUser.require(), req));
    }

    @PutMapping("/listings/{id}")
    public ListingView updateListing(@PathVariable String id, @Valid @RequestBody ListingRequest req) {
        return providers.updateListing(currentUser.require(), id, req);
    }

    @DeleteMapping("/listings/{id}")
    public ListingView removeListing(@PathVariable String id) {
        return providers.removeListing(currentUser.require(), id);
    }

    @GetMapping("/requests")
    public List<RequestView> inbox() {
        var user = currentUser.require();
        return requests.inbox(user, providers.myBusinessId(user));
    }

    @PostMapping("/requests/{id}/decline")
    public RequestView decline(@PathVariable String id) {
        return requests.decline(currentUser.require(), id);
    }

    @PostMapping("/requests/{id}/quotes")
    public ResponseEntity<QuoteView> quote(@PathVariable String id, @Valid @RequestBody SubmitQuoteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(quotes.submit(currentUser.require(), id, req));
    }

    @PostMapping("/requests/{id}/materials")
    public ResponseEntity<MaterialLineView> addMaterial(@PathVariable String id, @Valid @RequestBody AddMaterialRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(materials.add(currentUser.require(), id, req));
    }

    @GetMapping("/bookings")
    public List<BookingView> bookings() {
        var user = currentUser.require();
        return bookings.forProvider(user, providers.myBusinessId(user));
    }

    @PostMapping("/bookings/{id}/start")
    public BookingView start(@PathVariable String id) {
        return bookings.start(currentUser.require(), id);
    }

    @PostMapping("/bookings/{id}/complete")
    public BookingView complete(@PathVariable String id) {
        return bookings.complete(currentUser.require(), id);
    }
}
