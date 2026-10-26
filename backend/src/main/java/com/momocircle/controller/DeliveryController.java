package com.momocircle.controller;

import com.momocircle.dto.DeliveryDtos.DeliveryRequestResponse;
import com.momocircle.dto.DeliveryDtos.RequestDeliveryRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.DeliveryRequestService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeliveryController {

    private final DeliveryRequestService deliveryRequestService;
    private final CurrentUserService currentUser;

    public DeliveryController(DeliveryRequestService deliveryRequestService, CurrentUserService currentUser) {
        this.deliveryRequestService = deliveryRequestService;
        this.currentUser = currentUser;
    }

    /** Seller-only: request MoMoRide delivery for an order already set to that fulfilment method. */
    @PostMapping("/api/deliveries")
    public ResponseEntity<DeliveryRequestResponse> request(@Valid @RequestBody RequestDeliveryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(deliveryRequestService.request(currentUser.require(), req.orderId()));
    }

    /** Buyer or seller of the order: check its delivery status, if one has been requested. */
    @GetMapping("/api/orders/{orderId}/delivery")
    public DeliveryRequestResponse getForOrder(@PathVariable String orderId) {
        return deliveryRequestService.getForOrder(currentUser.require(), orderId);
    }

    /** Driver-only: browse open delivery jobs. */
    @GetMapping("/api/deliveries/open")
    public Page<DeliveryRequestResponse> browseOpen(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return deliveryRequestService.browseOpen(currentUser.require(), PageRequest.of(page, Math.min(size, 50)));
    }

    /** Driver-only: this driver's own delivery history. */
    @GetMapping("/api/deliveries/mine")
    public Page<DeliveryRequestResponse> mine(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return deliveryRequestService.myDeliveries(currentUser.require(), PageRequest.of(page, Math.min(size, 50)));
    }

    @PostMapping("/api/deliveries/{id}/accept")
    public DeliveryRequestResponse accept(@PathVariable String id) {
        return deliveryRequestService.accept(currentUser.require(), id);
    }

    @PostMapping("/api/deliveries/{id}/picked-up")
    public DeliveryRequestResponse markPickedUp(@PathVariable String id) {
        return deliveryRequestService.markPickedUp(currentUser.require(), id);
    }

    @PostMapping("/api/deliveries/{id}/delivered")
    public DeliveryRequestResponse markDelivered(@PathVariable String id) {
        return deliveryRequestService.markDelivered(currentUser.require(), id);
    }

    @PostMapping("/api/deliveries/{id}/cancel")
    public DeliveryRequestResponse cancel(@PathVariable String id) {
        return deliveryRequestService.cancel(currentUser.require(), id);
    }
}
