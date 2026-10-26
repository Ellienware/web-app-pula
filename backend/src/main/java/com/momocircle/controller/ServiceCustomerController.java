package com.momocircle.controller;

import com.momocircle.dto.ReviewDtos.ReviewResponse;
import com.momocircle.dto.ServiceDtos.*;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Requests, quotes, materials and bookings. Party-checked in the services; non-parties get 404. */
@RestController
@RequestMapping("/api/services")
public class ServiceCustomerController {

    private final ServiceRequestService requests;
    private final ServiceQuoteService quotes;
    private final ServiceMaterialService materials;
    private final ServiceBookingService bookings;
    private final ServiceInsightService insights;
    private final CurrentUserService currentUser;

    public ServiceCustomerController(ServiceRequestService requests, ServiceQuoteService quotes, ServiceMaterialService materials,
                                     ServiceBookingService bookings, ServiceInsightService insights, CurrentUserService currentUser) {
        this.requests = requests;
        this.quotes = quotes;
        this.materials = materials;
        this.bookings = bookings;
        this.insights = insights;
        this.currentUser = currentUser;
    }

    @PostMapping("/requests")
    public ResponseEntity<RequestView> create(@Valid @RequestBody CreateServiceRequestRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(requests.create(currentUser.require(), req));
    }

    @GetMapping("/requests")
    public List<RequestView> mine() {
        return requests.mine(currentUser.require());
    }

    @GetMapping("/requests/{id}")
    public RequestView get(@PathVariable String id) {
        return requests.get(currentUser.require(), id);
    }

    @PostMapping("/requests/{id}/submit")
    public RequestView submit(@PathVariable String id) {
        return requests.submit(currentUser.require(), id);
    }

    @PostMapping("/requests/{id}/cancel")
    public RequestView cancel(@PathVariable String id) {
        return requests.cancel(currentUser.require(), id);
    }

    @PostMapping("/requests/{id}/quotes/{quoteId}/accept")
    public BookingView accept(@PathVariable String id, @PathVariable String quoteId) {
        return quotes.accept(currentUser.require(), id, quoteId);
    }

    @PostMapping("/requests/{id}/quotes/{quoteId}/reject")
    public QuoteView reject(@PathVariable String id, @PathVariable String quoteId) {
        return quotes.reject(currentUser.require(), id, quoteId);
    }

    @PostMapping("/requests/{id}/quotes/{quoteId}/clarify")
    public QuoteView clarify(@PathVariable String id, @PathVariable String quoteId, @Valid @RequestBody ClarificationRequest req) {
        return quotes.requestClarification(currentUser.require(), id, quoteId, req);
    }

    @GetMapping("/requests/{id}/quotes/{quoteId}/explain")
    public InsightsView explain(@PathVariable String id, @PathVariable String quoteId) {
        return insights.explainQuote(currentUser.require(), id, quoteId);
    }

    // materials (the provider's own add endpoint is under /provider)
    @GetMapping("/requests/{id}/materials")
    public List<MaterialLineView> materials(@PathVariable String id) {
        return materials.list(currentUser.require(), id);
    }

    @PostMapping("/requests/{id}/materials")
    public ResponseEntity<MaterialLineView> addMaterial(@PathVariable String id, @Valid @RequestBody AddMaterialRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(materials.add(currentUser.require(), id, req));
    }

    @DeleteMapping("/requests/{id}/materials/{lineId}")
    public ResponseEntity<Void> removeMaterial(@PathVariable String id, @PathVariable String lineId) {
        materials.remove(currentUser.require(), id, lineId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/requests/{id}/materials/{lineId}/stock-order")
    public MaterialLineView linkStockOrder(@PathVariable String id, @PathVariable String lineId,
                                           @Valid @RequestBody LinkStockOrderRequest req) {
        return materials.linkStockOrder(currentUser.require(), id, lineId, req.stockOrderId());
    }

    // bookings
    @GetMapping("/bookings")
    public List<BookingView> myBookings() {
        return bookings.mine(currentUser.require());
    }

    @GetMapping("/bookings/{id}")
    public BookingView booking(@PathVariable String id) {
        return bookings.get(currentUser.require(), id);
    }

    @PostMapping("/bookings/{id}/cancel")
    public BookingView cancelBooking(@PathVariable String id, @Valid @RequestBody CancelRequest req) {
        return bookings.cancel(currentUser.require(), id, req.reason());
    }

    @PostMapping("/bookings/{id}/pay")
    public PaymentSessionView pay(@PathVariable String id, @RequestBody(required = false) PayRequest req) {
        return bookings.pay(currentUser.require(), id, req);
    }

    @PostMapping("/bookings/{id}/review")
    public ResponseEntity<List<ReviewResponse>> review(@PathVariable String id, @Valid @RequestBody ServiceReviewRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bookings.review(currentUser.require(), id, req));
    }
}
