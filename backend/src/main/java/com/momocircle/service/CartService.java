package com.momocircle.service;

import com.momocircle.domain.CartItem;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.dto.MarketDtos.AddCartItemRequest;
import com.momocircle.dto.MarketDtos.CartItemResponse;
import com.momocircle.dto.MarketDtos.CartResponse;
import com.momocircle.dto.MarketDtos.ProductResponse;
import com.momocircle.dto.MarketDtos.UpdateCartItemRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CartItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductService productService;
    private final FoodCheckoutService foodCheckout;

    public CartService(CartItemRepository cartItemRepository, ProductService productService,
                       FoodCheckoutService foodCheckout) {
        this.cartItemRepository = cartItemRepository;
        this.productService = productService;
        this.foodCheckout = foodCheckout;
    }

    @Transactional
    public CartResponse add(User user, AddCartItemRequest req) {
        Product product = productService.requireById(req.productId());
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw ApiException.badRequest("This item is not currently available");
        }

        // PULA Food: a line is (product, modifier set). Retail items have no modifiers, so their
        // signature is "" and this behaves exactly as before.
        java.util.List<com.momocircle.domain.Modifier> mods = foodCheckout.resolveModifiers(product, req.modifierIds());
        String signature = FoodCheckoutService.signature(mods.stream().map(com.momocircle.domain.Modifier::getId).toList());
        CartItem item = cartItemRepository.findByUserIdAndProductIdAndModifierSignature(user.getId(), product.getId(), signature)
                .orElseGet(() -> {
                    CartItem fresh = new CartItem();
                    fresh.setUser(user);
                    fresh.setProduct(product);
                    fresh.setQuantity(0);
                    fresh.setModifierSignature(signature);
                    fresh.getModifiers().addAll(foodCheckout.toCartModifiers(fresh, mods));
                    return fresh;
                });
        item.setQuantity(item.getQuantity() + req.quantity());
        cartItemRepository.save(item);
        return get(user);
    }

    @Transactional
    public CartResponse updateQuantity(User user, String cartItemId, UpdateCartItemRequest req) {
        CartItem item = requireOwn(user, cartItemId);
        item.setQuantity(req.quantity());
        cartItemRepository.save(item);
        return get(user);
    }

    @Transactional
    public CartResponse remove(User user, String cartItemId) {
        CartItem item = requireOwn(user, cartItemId);
        cartItemRepository.delete(item);
        return get(user);
    }

    @Transactional(readOnly = true)
    public CartResponse get(User user) {
        List<CartItem> items = cartItemRepository.findByUserIdOrderByCreatedAtAsc(user.getId());
        List<CartItemResponse> responses = items.stream().map(this::toItemResponse).toList();
        BigDecimal total = responses.stream().map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = responses.isEmpty() ? null : responses.get(0).product().currency();
        return new CartResponse(responses, total, currency);
    }

    /** Cart items belonging to a single business — used by checkout. Never trust a client-supplied total. */
    @Transactional(readOnly = true)
    public List<CartItem> itemsForBusiness(User user, String businessId) {
        return cartItemRepository.findByUserIdAndProductBusinessId(user.getId(), businessId);
    }

    @Transactional
    public void clearForBusiness(User user, String businessId) {
        cartItemRepository.deleteByUserIdAndProductBusinessId(user.getId(), businessId);
    }

    private CartItem requireOwn(User user, String cartItemId) {
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> ApiException.notFound("Cart item not found"));
        if (!item.getUser().getId().equals(user.getId())) {
            throw ApiException.forbidden("This is not your cart item");
        }
        return item;
    }

    private CartItemResponse toItemResponse(CartItem item) {
        Product p = item.getProduct();
        ProductResponse productResponse = new ProductResponse(
                p.getId(), p.getBusiness().getId(), p.getBusiness().getName(), p.getName(), p.getDescription(),
                p.getImageUrls(), p.getCategory(), p.getPrice(), p.getCurrency(), p.isService(), p.getStock(),
                p.getStatus(), p.getCanonicalProduct() == null ? null : p.getCanonicalProduct().getId(),
                p.getMatchClassification(), p.getRatingAverage(), p.getRatingCount(), p.getCreatedAt());
        // Display only: includes modifier deltas but not any per-branch price override, which is
        // applied (and the authoritative total computed) at checkout.
        java.util.List<com.momocircle.dto.MarketDtos.CartModifierResponse> mods = item.getModifiers().stream()
                .map(m -> new com.momocircle.dto.MarketDtos.CartModifierResponse(
                        m.getModifier().getId(), m.getModifier().getName(), m.getModifier().getPriceDelta()))
                .toList();
        BigDecimal modTotal = mods.stream().map(com.momocircle.dto.MarketDtos.CartModifierResponse::priceDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lineTotal = p.getPrice().add(modTotal).multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(item.getId(), productResponse, item.getQuantity(), lineTotal, mods);
    }
}
