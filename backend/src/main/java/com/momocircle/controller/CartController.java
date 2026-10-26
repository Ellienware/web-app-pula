package com.momocircle.controller;

import com.momocircle.dto.MarketDtos.AddCartItemRequest;
import com.momocircle.dto.MarketDtos.CartResponse;
import com.momocircle.dto.MarketDtos.UpdateCartItemRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.CartService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;
    private final CurrentUserService currentUser;

    public CartController(CartService cartService, CurrentUserService currentUser) {
        this.cartService = cartService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public CartResponse get() {
        return cartService.get(currentUser.require());
    }

    @PostMapping("/items")
    public CartResponse add(@Valid @RequestBody AddCartItemRequest req) {
        return cartService.add(currentUser.require(), req);
    }

    @PutMapping("/items/{cartItemId}")
    public CartResponse update(@PathVariable String cartItemId, @Valid @RequestBody UpdateCartItemRequest req) {
        return cartService.updateQuantity(currentUser.require(), cartItemId, req);
    }

    @DeleteMapping("/items/{cartItemId}")
    public CartResponse remove(@PathVariable String cartItemId) {
        return cartService.remove(currentUser.require(), cartItemId);
    }
}
