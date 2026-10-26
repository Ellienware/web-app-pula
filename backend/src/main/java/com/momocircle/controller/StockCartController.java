package com.momocircle.controller;

import com.momocircle.dto.StockDtos.AddStockCartItemRequest;
import com.momocircle.dto.StockDtos.StockCartResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.StockCartService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StockCartController {

    private final StockCartService service;
    private final CurrentUserService currentUser;

    public StockCartController(StockCartService service, CurrentUserService currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/stock/cart")
    public StockCartResponse get() {
        return service.getCart(currentUser.require());
    }

    @PostMapping("/api/stock/cart/items")
    public StockCartResponse addOrUpdate(@Valid @RequestBody AddStockCartItemRequest req) {
        return service.addOrUpdate(currentUser.require(), req);
    }

    @DeleteMapping("/api/stock/cart/items/{wholesaleProductId}")
    public StockCartResponse remove(@PathVariable String wholesaleProductId) {
        return service.remove(currentUser.require(), wholesaleProductId);
    }
}
