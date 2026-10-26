package com.momocircle.controller;

import com.momocircle.domain.enums.StockOrderStatus;
import com.momocircle.dto.StockDtos.PlaceStockOrderRequest;
import com.momocircle.dto.StockDtos.StockOrderResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.StockOrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class StockOrderController {

    private final StockOrderService service;
    private final CurrentUserService currentUser;

    public StockOrderController(StockOrderService service, CurrentUserService currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    // ---- Buyer ----

    @PostMapping("/api/stock/orders/checkout")
    public ResponseEntity<List<StockOrderResponse>> checkout(@Valid @RequestBody PlaceStockOrderRequest req) {
        var buyer = currentUser.require();
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.checkout(buyer, req));
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // Two identical checkouts landed at the same instant: the unique (buyer, supplier, key) index let one
            // win. Replay in a fresh transaction so the loser gets the winner's orders, not a server error.
            return ResponseEntity.status(HttpStatus.CREATED).body(service.checkout(buyer, req));
        }
    }

    @GetMapping("/api/stock/orders/mine")
    public Page<StockOrderResponse> myOrders(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.myOrders(currentUser.require(), PageRequest.of(page, Math.min(size, 50)));
    }

    @GetMapping("/api/stock/orders/mine/{orderId}")
    public StockOrderResponse myOrder(@PathVariable String orderId) {
        return service.getBuyerOrder(currentUser.require(), orderId);
    }

    @DeleteMapping("/api/stock/orders/mine/{orderId}")
    public StockOrderResponse cancel(@PathVariable String orderId) {
        return service.cancelByBuyer(currentUser.require(), orderId);
    }

    // ---- Supplier ----

    @GetMapping("/api/stock/orders/supplier")
    public Page<StockOrderResponse> supplierOrders(@RequestParam(required = false) StockOrderStatus status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return service.supplierOrders(currentUser.require(), status, PageRequest.of(page, Math.min(size, 50)));
    }

    @GetMapping("/api/stock/orders/supplier/{orderId}")
    public StockOrderResponse supplierOrder(@PathVariable String orderId) {
        return service.getSupplierOrder(currentUser.require(), orderId);
    }

    @PostMapping("/api/stock/orders/supplier/{orderId}/advance")
    public StockOrderResponse advance(@PathVariable String orderId, @RequestParam StockOrderStatus to) {
        return service.advanceStatus(currentUser.require(), orderId, to);
    }

    @PostMapping("/api/stock/orders/supplier/{orderId}/redispatch")
    public StockOrderResponse redispatch(@PathVariable String orderId) {
        return service.redispatch(currentUser.require(), orderId);
    }
}
